package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    private static final byte[] JAR_BYTES = "fake continuum jar bytes".getBytes(StandardCharsets.UTF_8);
    private static final String JAR_SHA256 = sha256Hex(JAR_BYTES);

    @TempDir
    Path tmp;
    Path dataDirectory;
    Path pluginsDir;
    Path backupDir;
    Path currentJar;

    private KeyPair keyPair;
    private FakeHttpTransport transport;
    private UpdateStateStore stateStore;
    private Updater updater;

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String MANIFEST = """
            {
              "schema": 1,
              "channel": "release",
              "build": "build-120",
              "sha256": "%s",
              "file": "release/build-120/continuum-build-120.jar",
              "mc": { "min": "1.20", "max": "26.3" },
              "java": { "min": 17 },
              "folia": true,
              "releasedAt": "2026-11-01T00:00:00.000Z"
            }
            """.formatted(JAR_SHA256);

    private byte[] manifestBytes() {
        return MANIFEST.getBytes(StandardCharsets.UTF_8);
    }

    private String sign(byte[] payload) throws Exception {
        Signature engine = Signature.getInstance("Ed25519");
        engine.initSign(keyPair.getPrivate());
        engine.update(payload);
        return Base64.getEncoder().encodeToString(engine.sign());
    }

    private static String okResponse(String build, boolean upToDate) {
        return """
                {
                  "build": "%s",
                  "sha256": "%s",
                  "url": "https://pub-abc123.r2.dev/continuum-artifacts/release/%s/continuum.jar?sig=x",
                  "manifestUrl": "https://upd.candyriya.xsxs.lol/v1/manifest.json",
                  "manifestSignatureUrl": "https://upd.candyriya.xsxs.lol/v1/manifest.json.sig",
                  "upToDate": %s,
                  "channel": "release"
                }
                """.formatted(build, JAR_SHA256, build, upToDate);
    }

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        dataDirectory = Files.createDirectories(tmp.resolve("data"));
        pluginsDir = Files.createDirectories(tmp.resolve("server/plugins"));
        backupDir = tmp.resolve("server/.continuum-backup");
        currentJar = Files.write(pluginsDir.resolve("continuum-paper.jar"), "current jar bytes".getBytes(StandardCharsets.UTF_8));
        transport = new FakeHttpTransport();
        stateStore = new UpdateStateStore(dataDirectory, LoggerFactory.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        if (updater != null) {
            updater.close();
            updater = null;
        }
    }

    private Updater newUpdater(UpdateSettings settings) {
        return newUpdater(settings, "build-119");
    }

    private Updater newUpdater(UpdateSettings settings, String version) {
        ManifestVerifier verifier = new ManifestVerifier(() -> keyPair.getPublic().getEncoded());
        Staging staging = new Staging(dataDirectory, pluginsDir, currentJar, LoggerFactory.getLogger("test"));
        updater = new Updater(LoggerFactory.getLogger("test"), settings, transport, verifier, staging,
                stateStore, version, "1.21.4", 25, false);
        updater.start();
        return updater;
    }

    private static UpdateSettings settings(UpdateConsent consent, UpdateChannel channel) {
        return new UpdateSettings(true, channel, consent, "ctm_live_testtoken0123456789abcdef01234567",
                List.of(".r2.dev"), List.of());
    }

    private void stubHappyPath() throws Exception {
        String manifest = new String(manifestBytes(), StandardCharsets.UTF_8);
        String signature = sign(manifestBytes());
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200, okResponse("build-120", false), Map.of()));
        transport.when("manifest.json.sig", url -> new FakeHttpTransport.FakeResponse(200, signature, Map.of()));
        transport.when("manifest.json", url -> new FakeHttpTransport.FakeResponse(200, manifest, Map.of()));
        transport.when("continuum.jar", url -> new FakeHttpTransport.FakeResponse(200, JAR_BYTES, Map.of()));
    }

    @Test
    void upToDateShortCircuits() {
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200, okResponse("build-119", true), Map.of()));
        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE)).checkNow().join();
        assertEquals(UpdatePhase.UP_TO_DATE, snapshot.phase());
        assertEquals(0, transport.downloadCalls());
    }

    @Test
    void notifyConsentStagesNothingAndNotifiesOnce() throws Exception {
        stubHappyPath();
        Updater updater = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE));

        UpdateSnapshot first = updater.checkNow().join();
        assertEquals(UpdatePhase.UPDATE_AVAILABLE, first.phase());
        assertEquals("build-120", first.availableBuild());
        assertFalse(Files.exists(pluginsDir.resolve("update/continuum-paper.jar")));
        assertEquals("build-120", stateStore.read().pendingNotifiedVersion());
        assertEquals(0, transport.downloadCalls());

        // a second check still asks the service (that is the point of 6h polling),
        // but the dedup marker must not change and the log stays one line
        updater.checkNow().join();
        assertEquals(2, transport.getCallsMatching("/v1/update?"));
        assertEquals("build-120", stateStore.read().pendingNotifiedVersion());
        assertEquals(0, transport.downloadCalls());
    }

    @Test
    void autoInstallStagesTheVerifiedArtifact() throws Exception {
        stubHappyPath();
        Updater updater = newUpdater(settings(UpdateConsent.AUTO_INSTALL, UpdateChannel.RELEASE));

        UpdateSnapshot snapshot = updater.installNow().join();
        assertEquals(UpdatePhase.STAGED, snapshot.phase());

        Path staged = pluginsDir.resolve("update/continuum-paper.jar");
        assertTrue(Files.exists(staged));
        assertEquals(JAR_SHA256, sha256Hex(Files.readAllBytes(staged)));
        assertFalse(Files.exists(pluginsDir.resolve("update/continuum-paper.jar.part")));

        // backup lives outside plugins/ and records the pre-swap jar
        UpdateState state = stateStore.read();
        assertEquals("build-120", state.stagedBuild());
        assertEquals(JAR_SHA256, state.stagedSha256());
        assertNotNull(state.backupFile());
        assertTrue(state.backupFile().contains(".continuum-backup"));
        assertTrue(Files.exists(Path.of(state.backupFile())));
        assertEquals(new String("current jar bytes".getBytes(StandardCharsets.UTF_8)),
                new String(Files.readAllBytes(Path.of(state.backupFile())), StandardCharsets.UTF_8));
    }

    @Test
    void explicitInstallStagesEvenUnderNotifyConsent() throws Exception {
        stubHappyPath();
        Updater updater = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE));
        UpdateSnapshot snapshot = updater.installNow().join();
        assertEquals(UpdatePhase.STAGED, snapshot.phase());
        assertTrue(Files.exists(pluginsDir.resolve("update/continuum-paper.jar")));
    }

    @Test
    void tamperedArtifactIsDiscarded() throws Exception {
        String manifest = new String(manifestBytes(), StandardCharsets.UTF_8);
        String signature = sign(manifestBytes());
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200, okResponse("build-120", false), Map.of()));
        transport.when("manifest.json.sig", url -> new FakeHttpTransport.FakeResponse(200, signature, Map.of()));
        transport.when("manifest.json", url -> new FakeHttpTransport.FakeResponse(200, manifest, Map.of()));
        transport.when("continuum.jar", url -> new FakeHttpTransport.FakeResponse(200, "tampered".getBytes(StandardCharsets.UTF_8), Map.of()));

        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.AUTO_INSTALL, UpdateChannel.RELEASE)).installNow().join();
        assertEquals(UpdatePhase.ERROR, snapshot.phase());
        assertFalse(Files.exists(pluginsDir.resolve("update/continuum-paper.jar")));
        // a bad artifact must never cost us the backup of a working jar
        assertNull(stateStore.read().backupFile());
    }

    @Test
    void badSignatureBlocksEverything() throws Exception {
        String manifest = new String(manifestBytes(), StandardCharsets.UTF_8);
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200, okResponse("build-120", false), Map.of()));
        transport.when("manifest.json.sig", url -> new FakeHttpTransport.FakeResponse(200, "AAAA", Map.of()));
        transport.when("manifest.json", url -> new FakeHttpTransport.FakeResponse(200, manifest, Map.of()));

        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.AUTO_INSTALL, UpdateChannel.RELEASE)).installNow().join();
        assertEquals(UpdatePhase.IDLE, snapshot.phase());
        assertEquals(0, transport.downloadCalls());
    }

    @Test
    void crossChannelManifestIsRefused() throws Exception {
        String devManifest = MANIFEST.replace("\"channel\": \"release\"", "\"channel\": \"dev\"").replace("\"build\": \"build-120\"", "\"build\": \"devbuild-120\"");
        String signature = sign(devManifest.getBytes(StandardCharsets.UTF_8));
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200, okResponse("build-120", false), Map.of()));
        transport.when("manifest.json.sig", url -> new FakeHttpTransport.FakeResponse(200, signature, Map.of()));
        transport.when("manifest.json", url -> new FakeHttpTransport.FakeResponse(200, devManifest, Map.of()));

        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.AUTO_INSTALL, UpdateChannel.RELEASE)).installNow().join();
        assertEquals(UpdatePhase.IDLE, snapshot.phase());
        assertEquals(0, transport.downloadCalls());
    }

    @Test
    void manifestContentSha256HeaderMismatchIsRefused() throws Exception {
        String manifest = new String(manifestBytes(), StandardCharsets.UTF_8);
        String signature = sign(manifestBytes());
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200, okResponse("build-120", false), Map.of()));
        transport.when("manifest.json.sig", url -> new FakeHttpTransport.FakeResponse(200, signature, Map.of()));
        transport.when("manifest.json", url -> new FakeHttpTransport.FakeResponse(200, manifest, Map.of("content-sha256", "0".repeat(64))));

        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.AUTO_INSTALL, UpdateChannel.RELEASE)).installNow().join();
        assertEquals(UpdatePhase.IDLE, snapshot.phase());
        assertEquals(0, transport.downloadCalls());
    }

    @Test
    void http409NeverInstalls() {
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(409,
                "{\"error\":{\"code\":\"INCOMPATIBLE\",\"message\":\"requires Java >= 17 (this server reports Java 11)\"}}", Map.of()));

        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.AUTO_INSTALL, UpdateChannel.RELEASE)).installNow().join();
        assertEquals(UpdatePhase.UPDATE_AVAILABLE, snapshot.phase());
        assertTrue(snapshot.message().contains("incompatible"));
        assertEquals(0, transport.downloadCalls());
    }

    @Test
    void incompatibleServerRefusesAutoInstall() throws Exception {
        String java17Manifest = MANIFEST; // java.min = 17
        String signature = sign(java17Manifest.getBytes(StandardCharsets.UTF_8));
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200, okResponse("build-120", false), Map.of()));
        transport.when("manifest.json.sig", url -> new FakeHttpTransport.FakeResponse(200, signature, Map.of()));
        transport.when("manifest.json", url -> new FakeHttpTransport.FakeResponse(200, java17Manifest, Map.of()));

        // this server reports Java 8 - the client-side compat gate must block staging
        ManifestVerifier verifier = new ManifestVerifier(() -> keyPair.getPublic().getEncoded());
        Staging staging = new Staging(dataDirectory, pluginsDir, currentJar, LoggerFactory.getLogger("test"));
        updater = new Updater(LoggerFactory.getLogger("test"), settings(UpdateConsent.AUTO_INSTALL, UpdateChannel.RELEASE),
                transport, verifier, staging, stateStore, "build-119", "1.21.4", 8, false);
        updater.start();
        UpdateSnapshot snapshot = updater.installNow().join();

        assertEquals(UpdatePhase.UPDATE_AVAILABLE, snapshot.phase());
        assertTrue(snapshot.message().contains("Java"));
        assertEquals(0, transport.downloadCalls());
    }

    @Test
    void noLicenseNagIsOncePerDay() {
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(403,
                "{\"error\":{\"code\":\"LICENSE_INVALID\",\"message\":\"nope\"},\"redirect\":\"https://github.com/CandyriyaMC/Continuum/releases\"}", Map.of()));
        Updater updater = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE));

        updater.checkNow().join();
        updater.checkNow().join();

        assertEquals(LocalDate.now(ZoneOffset.UTC).toEpochDay(),
                stateStore.read().lastLicenseNagEpochDay());
    }

    @Test
    void serviceErrorsFailOpen() {
        transport.when("/v1/update?", url -> {
            throw new IOException("connection refused");
        });
        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE)).checkNow().join();
        assertEquals(UpdatePhase.IDLE, snapshot.phase());
    }

    @Test
    void redirectRefusalFailsOpen() {
        transport.when("/v1/update?", url -> {
            throw new RedirectNotAllowedException("redirect to evil.example.com is not allow-listed");
        });
        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE)).checkNow().join();
        assertEquals(UpdatePhase.IDLE, snapshot.phase());
    }

    @Test
    void singleFlightOnlyOneCheckRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        transport.when("/v1/update?", url -> {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting for the gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return new FakeHttpTransport.FakeResponse(200, okResponse("build-119", true), Map.of());
        });

        Updater updater = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE));
        var first = updater.checkNow();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        // the second trigger must come back immediately with the in-flight note,
        // not queue behind the first and hit the service twice
        var second = updater.checkNow().join();
        assertEquals("update check already in progress", second.message());
        release.countDown();
        first.join();
        assertEquals(1, transport.getCallsMatching("/v1/update?"));
    }

    @Test
    void unstampedVersionSkipsTheService() {
        UpdateSnapshot snapshot = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE), "1.0.0").checkNow().join();
        assertEquals(UpdatePhase.IDLE, snapshot.phase());
        assertEquals(0, transport.getCalls());
    }

    @Test
    void blankTokenSkipsTheService() {
        UpdateSettings noToken = new UpdateSettings(true, UpdateChannel.RELEASE, UpdateConsent.NOTIFY, "",
                List.of(".r2.dev"), List.of());
        UpdateSnapshot snapshot = newUpdater(noToken).checkNow().join();
        assertEquals(UpdatePhase.IDLE, snapshot.phase());
        assertEquals(0, transport.getCalls());
    }

    @Test
    void devChannelWorksWithoutASignature() {
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200,
                """
                {
                  "build": "devbuild-88",
                  "sha256": "%s",
                  "url": "https://pub-abc123.r2.dev/continuum-artifacts/dev/devbuild-88/continuum.jar",
                  "channel": "dev"
                }
                """.formatted(JAR_SHA256), Map.of()));
        transport.when("continuum.jar", url -> new FakeHttpTransport.FakeResponse(200, JAR_BYTES, Map.of()));

        Updater updater = newUpdater(settings(UpdateConsent.AUTO_INSTALL, UpdateChannel.DEV), "devbuild-87");
        UpdateSnapshot snapshot = updater.installNow().join();
        assertEquals(UpdatePhase.STAGED, snapshot.phase());
        assertTrue(Files.exists(pluginsDir.resolve("update/continuum-paper.jar")));
    }

    @Test
    void bootDetectionClearsAPendingApply() throws Exception {
        // pre-seed the state as if an update was staged and the server then restarted onto it
        stateStore.write(new UpdateState("build-119", JAR_SHA256, backupDir + "/continuum-paper.jar.1.old",
                "2026-11-19T00:00:00Z", null, null));
        Files.createDirectories(backupDir);
        Files.write(backupDir.resolve("continuum-paper.jar.1.old"), "backup".getBytes(StandardCharsets.UTF_8));

        newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.RELEASE), "build-119");

        UpdateState after = stateStore.read();
        assertFalse(after.hasStagedUpdate());
        assertFalse(Files.exists(backupDir.resolve("continuum-paper.jar.1.old")));
    }

    @Test
    void devUpToDateWithoutUpToDateFlagFallsBackToBuildEquality() {
        transport.when("/v1/update?", url -> new FakeHttpTransport.FakeResponse(200,
                """
                {
                  "build": "devbuild-87",
                  "url": "https://pub-abc123.r2.dev/x.jar",
                  "channel": "dev"
                }
                """, Map.of()));

        Updater updater = newUpdater(settings(UpdateConsent.NOTIFY, UpdateChannel.DEV), "devbuild-87");
        UpdateSnapshot snapshot = updater.checkNow().join();
        assertEquals(UpdatePhase.UP_TO_DATE, snapshot.phase());
    }

    /** Queue-ish fake: first matching rule wins; responder failures propagate as IOExceptions. */
    @FunctionalInterface
    interface FakeResponder {
        FakeHttpTransport.FakeResponse respond(String url) throws Exception;
    }

    static final class FakeHttpTransport implements HttpTransport {

        record FakeResponse(int status, Object body, Map<String, String> headers) {
        }

        private final Map<String, FakeResponder> rules = new java.util.LinkedHashMap<>();
        private final java.util.List<String> getUrls = new java.util.ArrayList<>();
        private int downloadCalls;

        void when(String urlFragment, FakeResponder responder) {
            rules.put(urlFragment, responder);
        }

        int getCalls() {
            return getUrls.size();
        }

        int downloadCalls() {
            return downloadCalls;
        }

        int getCallsMatching(String fragment) {
            return (int) getUrls.stream().filter(url -> url.contains(fragment)).count();
        }

        @Override
        public HttpTransport.HttpResponse getBytes(String url, Map<String, String> headers, int maxBodyBytes)
                throws IOException {
            getUrls.add(url);
            FakeResponse response = respond(url);
            byte[] body = toBytes(response.body());
            if (body.length > maxBodyBytes) {
                throw new ResponseTooLargeException("fake body bigger than the cap");
            }
            return new HttpTransport.HttpResponse(response.status(), lower(response.headers()), body);
        }

        @Override
        public DownloadResult downloadTo(String url, Map<String, String> headers, Path target, long maxBytes)
                throws IOException {
            downloadCalls++;
            FakeResponse response = respond(url);
            byte[] body = toBytes(response.body());
            if (body.length > maxBytes) {
                throw new ResponseTooLargeException("fake body bigger than the cap");
            }
            if (headers != null) {
                // the artifact url is cross-host; no Authorization must travel
                if (headers.containsKey("Authorization")) {
                    throw new IOException("Authorization leaked onto the artifact download");
                }
            }
            Files.createDirectories(target.getParent());
            Files.write(target, body);
            return new DownloadResult(body.length, sha256Hex(body));
        }

        private FakeResponse respond(String url) throws IOException {
            for (Map.Entry<String, FakeResponder> rule : rules.entrySet()) {
                if (url.contains(rule.getKey())) {
                    try {
                        return rule.getValue().respond(url);
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }
            }
            throw new IOException("no canned response for " + url);
        }

        private static byte[] toBytes(Object body) {
            if (body instanceof byte[] bytes) {
                return bytes;
            }
            return String.valueOf(body).getBytes(StandardCharsets.UTF_8);
        }

        private static Map<String, String> lower(Map<String, String> headers) {
            Map<String, String> map = new java.util.TreeMap<>();
            headers.forEach((k, v) -> map.put(k.toLowerCase(java.util.Locale.ROOT), v));
            return map;
        }
    }
}
