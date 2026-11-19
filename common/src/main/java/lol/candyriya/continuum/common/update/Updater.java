package lol.candyriya.continuum.common.update;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The update engine: checks at enable + every 6h, downloads, verifies,
 * stages. Fail-open by construction - every failure mode ends in "the server
 * keeps running, we check again later", never in a stopped plugin or a
 * blocked boot.
 *
 * <p>Protocol (see {@code docs/updater.md}): {@code GET /v1/update} on the
 * configured channel; on 200, the signed manifest and its detached Ed25519
 * signature are fetched and verified before any byte of the artifact is
 * trusted; the artifact sha256 must match the signed manifest (or, on the
 * anonymous dev channel which carries none, the response); a verified
 * artifact lands in {@code plugins/update/} and only a restart applies it.</p>
 *
 * <p>Single-flight: one check at a time per updater; a concurrent request
 * gets the current snapshot instead of a parallel thundering herd. The
 * consent default is {@link UpdateConsent#NOTIFY} - nothing reaches the
 * update folder without either an explicit command or
 * {@code updates.consent: auto-install}.</p>
 */
public final class Updater {

    public static final String BASE_URL = "https://upd.candyriya.xsxs.lol";
    /** The host the service answers on; redirects may target it or the allow-listed suffixes. */
    public static final String SERVICE_HOST = "upd.candyriya.xsxs.lol";

    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 1024;
    private static final int MAX_RELEASE_RESPONSE_BYTES = 64 * 1024;
    private static final long MAX_JAR_BYTES = 128L * 1024 * 1024;

    static final Duration INITIAL_DELAY = Duration.ofSeconds(10);
    static final Duration CHECK_PERIOD = Duration.ofHours(6);
    private static final Duration CLOSE_AWAIT = Duration.ofSeconds(2);

    private static final JsonMapper MAPPER = new JsonMapper();

    private final Logger logger;
    private final UpdateSettings settings;
    private final HttpTransport transport;
    private final ManifestVerifier verifier;
    private final Staging staging;
    private final UpdateStateStore stateStore;
    private final String version;
    private final BuildRef currentBuild;
    private final String mcVersion;
    private final int javaMajor;
    private final boolean folia;

    private final AtomicBoolean checkInFlight = new AtomicBoolean();
    private final AtomicReference<UpdateSnapshot> snapshot;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Set once the embedded key is proven unusable; polling stops for this boot (it cannot heal itself). */
    private volatile boolean keyUnusable;

    private ScheduledExecutorService executor;
    private volatile boolean armed;
    private volatile boolean started;

    public Updater(Logger logger,
                   UpdateSettings settings,
                   HttpTransport transport,
                   ManifestVerifier verifier,
                   Staging staging,
                   UpdateStateStore stateStore,
                   String version,
                   String mcVersion,
                   int javaMajor,
                   boolean folia) {
        this.logger = logger;
        this.settings = settings;
        this.transport = transport;
        this.verifier = verifier;
        this.staging = staging;
        this.stateStore = stateStore;
        this.version = version;
        this.currentBuild = BuildRef.parse(version);
        this.mcVersion = mcVersion;
        this.javaMajor = javaMajor;
        this.folia = folia;
        this.snapshot = new AtomicReference<>(UpdateSnapshot.of(
                settings.usable() && settings.checkEnabled() ? UpdatePhase.IDLE : UpdatePhase.DISABLED,
                version, null, "", 0));
    }

    /**
     * Boot-time housekeeping + arming the check task. Call once, early in
     * platform enable. Never throws; problems end in a disabled updater, not
     * a dead plugin.
     */
    public void start() {
        if (closed.get() || started) {
            return;
        }
        started = true;
        try {
            bootDetection();
            staging.cleanupStrayPart();
        } catch (Throwable t) {
            logger.error("update boot housekeeping failed; continuing without it", t);
        }
        if (!settings.usable()) {
            for (String problem : settings.problems()) {
                logger.warn("update.yml rejected: {}", problem);
            }
            logger.warn("updater is disabled for this boot (the rest of the plugin runs normally)");
            snapshot.set(UpdateSnapshot.of(UpdatePhase.DISABLED, version, null,
                    "update.yml rejected; updater disabled", 0));
            return;
        }
        if (!settings.checkEnabled()) {
            logger.info("update checks are disabled (updates.check: false); the plugin runs normally");
            snapshot.set(UpdateSnapshot.of(UpdatePhase.DISABLED, version, null,
                    "update checks disabled by configuration", 0));
            return;
        }
        // one line per boot, not per check - the token story is operator-visible, not naggy
        if (settings.channel() == UpdateChannel.RELEASE && settings.licenseToken().isBlank()) {
            logger.info("release channel selected but no license token is configured "
                    + "(updates.license-token in update.yml); update checks will be skipped. "
                    + "Continuum works normally.");
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("continuum-updater-", 0).daemon(true).factory());
        armed = true;
        executor.scheduleWithFixedDelay(this::guardedCycle, INITIAL_DELAY.toMillis(),
                CHECK_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        logger.info("update checks armed: first check in {}s, then every {}h ({} channel, consent {})",
                INITIAL_DELAY.toSeconds(), CHECK_PERIOD.toHours(),
                settings.channel().wire(), settings.consent().wire());
    }

    /** Enqueued check for the command path; completes with the resulting snapshot. */
    public CompletableFuture<UpdateSnapshot> checkNow() {
        return enqueue(false);
    }

    /** Explicit operator action: check, then download + stage. Consent bypassed; compat gates are not. */
    public CompletableFuture<UpdateSnapshot> installNow() {
        return enqueue(true);
    }

    /** Current view for status output; never blocks, never null. */
    public UpdateSnapshot snapshot() {
        return snapshot.get();
    }

    public UpdatePhase phase() {
        return snapshot.get().phase();
    }

    /** Marks the executor and transport shut; late completions are dropped. Safe to call twice. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService pool = executor;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(CLOSE_AWAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        try {
            transport.close();
        } catch (Exception e) {
            logger.debug("transport close failed: {}", e.toString());
        }
    }

    /** Promotes a staged update to "applied" when this boot is running it. Also prunes expired backups. */
    private void bootDetection() {
        UpdateState state = stateStore.read();
        if (state.hasStagedUpdate() && state.stagedBuild().equals(version)) {
            staging.deleteBackupQuietly(state.backupFile());
            stateStore.write(state.clearedStaged());
            logger.info("update to {} is applied and this boot is clean; the pre-swap backup was removed", version);
        }
    }

    private CompletableFuture<UpdateSnapshot> enqueue(boolean forceInstall) {
        CompletableFuture<UpdateSnapshot> future = new CompletableFuture<>();
        if (closed.get() || !armed) {
            // updates off: answer immediately with the current view instead of queueing a lie
            future.complete(snapshot.get());
            return future;
        }
        // manual triggers get their own daemon thread; the flight guard dedupes them
        Thread thread = new Thread(() -> {
            try {
                future.complete(doCheck(forceInstall));
            } catch (Throwable t) {
                // doCheck already never throws; this guard keeps the promise airtight
                logger.error("update {} blew up outside the guarded path; the server is fine", forceInstall ? "install" : "check", t);
                future.complete(snapshot.get());
            }
        }, "continuum-update-manual-");
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    /** The scheduler task body; must never let an exception escape (it would cancel future cycles). */
    private void guardedCycle() {
        try {
            doCheck(false);
        } catch (Throwable t) {
            logger.error("scheduled update check failed; the server is fine, next cycle in {}h", CHECK_PERIOD.toHours(), t);
        }
    }

    private UpdateSnapshot doCheck(boolean forceInstall) {
        if (closed.get()) {
            return snapshot.get();
        }
        if (!checkInFlight.compareAndSet(false, true)) {
            UpdateSnapshot busy = snapshot.get();
            return UpdateSnapshot.of(busy.phase(), busy.currentBuild(), busy.availableBuild(),
                    "update check already in progress", busy.lastCheckedEpochSecond());
        }
        try {
            snapshot.set(UpdateSnapshot.of(UpdatePhase.CHECKING, version, null, "", epochSeconds()));
            return runCheck(forceInstall);
        } catch (Throwable t) {
            logger.error("update check failed; continuum keeps running, next cycle in {}h", CHECK_PERIOD.toHours(), t);
            return finish(UpdatePhase.ERROR, null, "check failed (fail-open): " + abbreviate(t));
        } finally {
            checkInFlight.set(false);
        }
    }

    private UpdateSnapshot runCheck(boolean forceInstall) {
        if (keyUnusable) {
            // the classpath cannot grow a key mid-boot; stop paying for checks that cannot pass
            return finish(UpdatePhase.DISABLED, null,
                    "updater disabled: no usable manifest public key embedded in this build");
        }
        String buildParam = currentBuild.queryParam(settings.channel());
        if (buildParam == null) {
            logger.info("running '{}' cannot be checked on the {} channel; skipping", version, settings.channel().wire());
            return finish(UpdatePhase.IDLE, null, "running an unstamped version; check skipped");
        }
        if (settings.channel() == UpdateChannel.RELEASE && settings.licenseToken().isBlank()) {
            // expected steady state on tip-jar releases without a license; keep it quiet
            return finish(UpdatePhase.IDLE, null, "no license token configured; update checks skipped");
        }

        String query = "build=" + encode(buildParam)
                + "&mc=" + encode(mcVersion == null ? "" : mcVersion)
                + "&java=" + Math.max(0, javaMajor)
                + "&folia=" + (folia ? "1" : "0")
                + "&channel=" + settings.channel().wire();
        Map<String, String> headers = settings.channel() == UpdateChannel.RELEASE
                ? Map.of("Authorization", "Bearer " + settings.licenseToken())
                : Map.of();

        final HttpTransport.HttpResponse response;
        try {
            response = getBytesWithOneRetry(BASE_URL + "/v1/update?" + query, headers, MAX_RELEASE_RESPONSE_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(UpdatePhase.IDLE, null, "check interrupted");
        } catch (RedirectNotAllowedException | ResponseTooLargeException e) {
            logger.warn("update check refused: {}; continuum keeps running", abbreviate(e));
            return finish(UpdatePhase.IDLE, null, "check refused: " + abbreviate(e));
        } catch (IOException e) {
            logger.info("update check could not reach {} ({}); continuum keeps running, next cycle in {}h",
                    BASE_URL, abbreviate(e), CHECK_PERIOD.toHours());
            return finish(UpdatePhase.IDLE, null, "service unreachable; check later");
        }

        switch (response.status()) {
            case 200 -> {
                return handleOk(response, forceInstall);
            }
            case 400 -> {
                logger.warn("update service rejected the check (400 VALIDATION_FAILED) - the running build is not what it expects; continuum keeps running");
                return finish(UpdatePhase.IDLE, null, "check rejected by the service");
            }
            case 403 -> {
                handleLicenseNag(response);
                return finish(UpdatePhase.IDLE, null, "no valid license; updates paused");
            }
            case 404 -> {
                logger.debug("dev build {} is not published on the service; nothing to check", buildParam);
                return finish(UpdatePhase.IDLE, null, "no such dev build published");
            }
            case 409 -> {
                String message = errorMessage(response.body());
                logger.warn("update refused by the compatibility gate: {}", message);
                return finish(UpdatePhase.UPDATE_AVAILABLE, null, "incompatible: " + message);
            }
            case 429 -> {
                logger.info("update check rate limited; backing off until the next cycle");
                return finish(UpdatePhase.IDLE, null, "rate limited; check later");
            }
            case 502, 503 -> {
                logger.info("update service is unavailable ({}); check later", response.status());
                return finish(UpdatePhase.IDLE, null, "service unavailable; check later");
            }
            default -> {
                logger.warn("update service answered {}; continuum keeps running", response.status());
                return finish(UpdatePhase.IDLE, null, "unexpected response " + response.status());
            }
        }
    }

    private UpdateSnapshot handleOk(HttpTransport.HttpResponse response, boolean forceInstall) {
        final JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (Exception e) {
            logger.warn("update response is not valid JSON ({}); continuum keeps running", abbreviate(e));
            return finish(UpdatePhase.IDLE, null, "malformed service response");
        }
        if (root == null || !root.isObject()) {
            return finish(UpdatePhase.IDLE, null, "malformed service response");
        }
        String build = text(root, "build");
        if (build == null) {
            return finish(UpdatePhase.IDLE, null, "malformed service response (no build)");
        }
        String channel = text(root, "channel");
        if (channel != null && !channel.equalsIgnoreCase(settings.channel().wire())) {
            logger.warn("service answered on channel '{}' while we asked for '{}'; refusing to act on it",
                    channel, settings.channel().wire());
            return finish(UpdatePhase.IDLE, null, "channel mismatch; refused");
        }

        String responseSha = text(root, "sha256");
        if (responseSha != null) {
            responseSha = responseSha.toLowerCase(java.util.Locale.ROOT);
            if (!Manifest.SHA256_FORMAT.matcher(responseSha).matches()) {
                logger.warn("service sent a malformed sha256; refusing to act on it");
                return finish(UpdatePhase.IDLE, null, "malformed sha256; refused");
            }
        }

        Boolean upToDate = booleanOrNull(root, "upToDate");
        boolean runningLatest = upToDate != null ? upToDate : build.equals(version);
        if (runningLatest) {
            logger.info("continuum is up to date ({}, {} channel)", version, settings.channel().wire());
            return finish(UpdatePhase.UP_TO_DATE, null, "up to date");
        }

        // a newer build exists
        String artifactUrl = text(root, "url");
        String manifestUrl = text(root, "manifestUrl");
        String manifestSignatureUrl = text(root, "manifestSignatureUrl");

        // manifest: required on release; optional on dev (the dev channel ships none)
        Manifest manifest = null;
        if (manifestUrl != null) {
            manifest = fetchAndVerifyManifest(manifestUrl, manifestSignatureUrl);
            if (manifest == null) {
                // the only no-go for the server, not for gameplay
                return finish(UpdatePhase.IDLE, null, "manifest verification failed; see the log");
            }
        } else if (settings.channel() == UpdateChannel.RELEASE) {
            logger.info("service returned no manifest URLs; refusing to install unverified bytes this cycle");
            return finish(UpdatePhase.IDLE, null, "manifest unavailable; check later");
        } else {
            logger.info("dev channel: no manifest; the artifact sha256 from the response is the only check");
        }

        // cross-checks between response and manifest
        if (manifest != null) {
            if (!manifest.channel().wire().equals(settings.channel().wire())) {
                logger.error("signed manifest channel '{}' does not match configured channel '{}'; refusing the update",
                        manifest.channel().wire(), settings.channel().wire());
                return finish(UpdatePhase.IDLE, null, "manifest channel mismatch; refused");
            }
            if (!manifest.build().equals(build)) {
                logger.error("signed manifest build '{}' does not match the service response '{}'; refusing the update",
                        manifest.build(), build);
                return finish(UpdatePhase.IDLE, null, "manifest build mismatch; refused");
            }
            if (responseSha != null && !responseSha.equals(manifest.sha256())) {
                logger.error("response sha256 does not match the signed manifest; refusing the update");
                return finish(UpdatePhase.IDLE, null, "sha256 mismatch; refused");
            }
            List<String> problems = CompatChecker.problems(mcVersion, javaMajor, folia, manifest);
            if (!problems.isEmpty()) {
                String reason = String.join("; ", problems);
                logger.warn("update {} is not compatible with this server ({}); will not install it", build, reason);
                return finish(UpdatePhase.UPDATE_AVAILABLE, build, "incompatible: " + reason);
            }
        }

        String jarSha = manifest != null ? manifest.sha256() : responseSha;
        if (jarSha == null) {
            logger.warn("cannot verify the update artifact (no sha256 anywhere); refusing to stage it");
            return finish(UpdatePhase.UPDATE_AVAILABLE, build, "artifact unverifiable; refused");
        }
        if (artifactUrl == null) {
            logger.warn("service did not return a download url; nothing staged");
            return finish(UpdatePhase.UPDATE_AVAILABLE, build, "no download url; check later");
        }

        UpdateState state = stateStore.read();
        boolean mayStage = forceInstall || settings.consent() == UpdateConsent.AUTO_INSTALL;
        if (!mayStage) {
            if (!build.equals(state.pendingNotifiedVersion())) {
                logger.warn("continuum {} available (running {}, {} channel) - run '/continuum update install' to stage it "
                                + "(or set updates.consent: auto-install)",
                        build, version, settings.channel().wire());
                stateStore.write(state.withPendingNotified(build));
            } else {
                logger.info("continuum {} is still available (already notified)", build);
            }
            return finish(UpdatePhase.UPDATE_AVAILABLE, build, "update available: " + build);
        }

        // staging path: download to .part, verify, back up current, move into place
        String jarName = staging.currentJarFileName();
        Path part = staging.updateDir().resolve(jarName + ".part");
        try {
            Files.createDirectories(staging.updateDir());
            HttpTransport.DownloadResult download = downloadWithOneRetry(artifactUrl, Map.of(), part, MAX_JAR_BYTES);
            if (!download.sha256Hex().equalsIgnoreCase(jarSha)) {
                Files.deleteIfExists(part);
                logger.warn("downloaded artifact sha256 mismatch (expected {}, got {}); discarded", jarSha, download.sha256Hex());
                return finish(UpdatePhase.ERROR, build, "artifact sha256 mismatch; discarded");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(UpdatePhase.IDLE, build, "download interrupted; nothing staged");
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // best effort
            }
            logger.warn("update download failed ({}); continuum keeps running, nothing staged", abbreviate(e));
            return finish(UpdatePhase.ERROR, build, "download failed; check later");
        }

        final Path backup;
        try {
            backup = staging.backupCurrentJar();
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // best effort
            }
            logger.warn("could not back up the running jar ({}); refusing to stage without a rollback path", abbreviate(e));
            return finish(UpdatePhase.ERROR, build, "backup failed; nothing staged");
        }

        try {
            staging.stagePartIntoUpdateDir(part);
        } catch (IOException e) {
            logger.warn("could not move the staged artifact into {} ({}); backup is at {}", staging.updateDir(), abbreviate(e), backup);
            return finish(UpdatePhase.ERROR, build, "could not stage; backup kept at " + backup);
        }

        stateStore.write(state.withStaged(build, jarSha, backup.toString(), Instant.now().toString()));
        logger.warn("continuum {} staged as {} - restart to apply (rollback: restore {})",
                build, staging.destinationInUpdateDir(), backup);
        return finish(UpdatePhase.STAGED, build, "staged " + build + "; restart to apply");
    }

    /** Fetch manifest + signature, verify bytes, parse only after verification. Null on any failure (caller aborts). */
    private Manifest fetchAndVerifyManifest(String manifestUrl,
                                            String manifestSignatureUrl) {
        if (manifestSignatureUrl == null) {
            logger.warn("service returned a manifest URL but no signature URL; refusing to use the manifest");
            return null;
        }
        try {
            HttpTransport.HttpResponse manifestResponse = transport.getBytes(manifestUrl, Map.of(), MAX_MANIFEST_BYTES);
            if (manifestResponse.status() != 200) {
                logger.warn("manifest fetch answered {}; refusing to use it", manifestResponse.status());
                return null;
            }
            byte[] manifestBytes = manifestResponse.body();
            String contentSha = manifestResponse.header("content-sha256");
            if (contentSha != null) {
                String actual = sha256Hex(manifestBytes);
                if (!contentSha.trim().equalsIgnoreCase(actual)) {
                    logger.warn("manifest content-sha256 header does not match its bytes; refusing to use it");
                    return null;
                }
            }
            HttpTransport.HttpResponse signatureResponse = transport.getBytes(manifestSignatureUrl, Map.of(), MAX_SIGNATURE_BYTES);
            if (signatureResponse.status() != 200) {
                logger.warn("manifest signature fetch answered {}; refusing to use the manifest", signatureResponse.status());
                return null;
            }
            String signatureBase64 = new String(signatureResponse.body(), StandardCharsets.UTF_8).trim();
            ManifestVerifier.Result result = verifier.verify(manifestBytes, signatureBase64);
            switch (result) {
                case VALID -> {
                    return Manifest.parse(manifestBytes);
                }
                case NO_PUBLIC_KEY -> {
                    keyUnusable = true;
                    logger.warn("no manifest public key embedded in this build; update checks are disabled until the release pipeline embeds one");
                    return null;
                }
                case MALFORMED_SIGNATURE -> {
                    logger.warn("manifest signature is not a 64-byte Ed25519 signature; refusing to use it");
                    return null;
                }
                case KEY_UNREADABLE -> {
                    keyUnusable = true;
                    logger.warn("the embedded manifest public key cannot be used; refusing every update");
                    return null;
                }
                case INVALID_SIGNATURE -> {
                    logger.warn("manifest signature does not verify; refusing the update (this is the check that stops a compromised file host)");
                    return null;
                }
                default -> {
                    logger.warn("manifest verification failed with an unknown outcome; refusing the update");
                    return null;
                }
            }
        } catch (Manifest.ManifestException e) {
            logger.warn("manifest parse failed ({}); refusing the update", abbreviate(e));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            logger.warn("manifest fetch failed ({}); continuum keeps running", abbreviate(e));
            return null;
        }
    }

    private HttpTransport.HttpResponse getBytesWithOneRetry(String url, Map<String, String> headers, int maxBytes)
            throws IOException, InterruptedException {
        try {
            return transport.getBytes(url, headers, maxBytes);
        } catch (RedirectNotAllowedException | ResponseTooLargeException e) {
            throw e; // deterministic - retrying the same request buys nothing
        } catch (IOException e) {
            logger.debug("update request failed once ({}); retrying once", e.toString());
            return transport.getBytes(url, headers, maxBytes);
        }
    }

    private HttpTransport.DownloadResult downloadWithOneRetry(String url, Map<String, String> headers,
                                                              Path target, long maxBytes)
            throws IOException, InterruptedException {
        try {
            return transport.downloadTo(url, headers, target, maxBytes);
        } catch (RedirectNotAllowedException | ResponseTooLargeException e) {
            throw e;
        } catch (IOException e) {
            logger.debug("artifact download failed once ({}); retrying once", e.toString());
            return transport.downloadTo(url, headers, target, maxBytes);
        }
    }

    /** The 403 one-liner, throttled to once per UTC day. Everything else about licensing is out of scope here. */
    private void handleLicenseNag(HttpTransport.HttpResponse response) {
        JsonNode root = tryRead(response.body());
        String code = errorCode(root);
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        UpdateState state = stateStore.read();
        if (state.lastLicenseNagEpochDay() != null && state.lastLicenseNagEpochDay() == today) {
            logger.debug("no-valid-license line already logged today; skipping ({})", code);
            return;
        }
        logger.warn("Continuum: no valid license - source builds are on GitHub; ready-made jars are on the marketplaces.");
        stateStore.write(state.withLastLicenseNagEpochDay(today));
    }

    private static JsonNode tryRead(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorCode(JsonNode root) {
        if (root == null) {
            return "";
        }
        JsonNode error = root.get("error");
        if (error != null && error.isObject()) {
            JsonNode code = error.get("code");
            if (code != null && code.isString()) {
                return code.stringValue();
            }
        }
        return "";
    }

    private static String errorMessage(byte[] body) {
        JsonNode root = tryRead(body);
        if (root == null) {
            return "incompatible build";
        }
        JsonNode error = root.get("error");
        String message = null;
        if (error != null && error.isObject()) {
            JsonNode node = error.get("message");
            if (node != null && node.isString()) {
                message = node.stringValue();
            }
        }
        if (message == null || message.isBlank()) {
            JsonNode redirect = root.get("redirect");
            message = redirect != null && redirect.isString() ? "see " + redirect.stringValue() : "incompatible build";
        }
        return abbreviate(message);
    }

    private static String text(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        return node != null && node.isString() && !node.stringValue().isBlank() ? node.stringValue().trim() : null;
    }

    private static Boolean booleanOrNull(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        return node != null && node.isBoolean() ? node.booleanValue() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private UpdateSnapshot finish(UpdatePhase phase, String availableBuild, String message) {
        UpdateSnapshot next = UpdateSnapshot.of(phase, version, availableBuild, message, epochSeconds());
        snapshot.set(next);
        return next;
    }

    private static long epochSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the JVM - how?", e);
        }
    }

    private static String abbreviate(Throwable t) {
        String message = t.getMessage();
        return abbreviate(message != null ? message : t.getClass().getSimpleName());
    }

    private static String abbreviate(String message) {
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 160 ? singleLine.substring(0, 160) : singleLine;
    }
}
