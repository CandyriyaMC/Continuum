package lol.candyriya.continuum.common.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * JDK {@link HttpClient}-based transport.
 *
 * <p>Timeouts: 8s connect, 8s per request - hanging the proxy or the game
 * loop is never an option. Redirects are followed manually, at most three
 * hops, only to targets the {@link RedirectPolicy} allows; the
 * {@code Authorization} header is attached to the first request only and
 * never forwarded across a host boundary. TLS uses the platform default
 * trust store and hostname verification - no pinning, no weakening, Ever;
 * the Ed25519 manifest signature is the trust anchor on top.</p>
 */
public final class JdkHttpTransport implements HttpTransport {

    /** Hard cap on redirect hops; a redirect loop must not pin the checker. */
    private static final int MAX_REDIRECTS = 3;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient client;
    private final RedirectPolicy redirectPolicy;
    private final String serviceHost;
    private final String userAgent;

    public JdkHttpTransport(String serviceHost, String userAgent, List<String> redirectAllowSuffixes) {
        this.serviceHost = serviceHost;
        this.userAgent = userAgent == null || userAgent.isBlank() ? "continuum-updater" : userAgent;
        this.redirectPolicy = new RedirectPolicy(redirectAllowSuffixes);
        // one daemon thread per transport is plenty - checks are rare and sequential
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newFixedThreadPool(2, Thread.ofPlatform()
                        .name("continuum-updater-http-", 0).daemon(true).factory()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public HttpTransport.HttpResponse getBytes(String url, Map<String, String> headers, int maxBodyBytes)
            throws IOException, InterruptedException {
        URI original = parse(url);
        URI current = original;
        boolean firstHop = true;
        for (int hop = 0; ; hop++) {
            java.net.http.HttpResponse<InputStream> response = client.send(request(current, headers, firstHop, original),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (isRedirect(response.statusCode())) {
                String location = firstHeader(response, "location");
                closeQuietly(response.body());
                current = checkedRedirect(location, current, hop, original);
                firstHop = false;
                continue;
            }
            Map<String, String> responseHeaders = lowerCased(response.headers());
            long declared = contentLength(responseHeaders);
            if (declared > maxBodyBytes) {
                closeQuietly(response.body());
                throw new ResponseTooLargeException("response declares " + declared + " bytes (cap " + maxBodyBytes + ")");
            }
            try (InputStream in = response.body()) {
                byte[] body = readCapped(in, maxBodyBytes);
                return new HttpTransport.HttpResponse(response.statusCode(), responseHeaders, body);
            }
        }
    }

    @Override
    public DownloadResult downloadTo(String url, Map<String, String> headers, Path target, long maxBytes)
            throws IOException, InterruptedException {
        URI original = parse(url);
        URI current = original;
        boolean firstHop = true;
        for (int hop = 0; ; hop++) {
            java.net.http.HttpResponse<InputStream> response = client.send(request(current, headers, firstHop, original),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (isRedirect(response.statusCode())) {
                String location = firstHeader(response, "location");
                closeQuietly(response.body());
                current = checkedRedirect(location, current, hop, original);
                firstHop = false;
                continue;
            }
            Map<String, String> responseHeaders = lowerCased(response.headers());
            long declared = contentLength(responseHeaders);
            if (declared > maxBytes) {
                closeQuietly(response.body());
                throw new ResponseTooLargeException("artifact declares " + declared + " bytes (cap " + maxBytes + ")");
            }
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MessageDigest digest = sha256();
            long total = 0;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        out.close();
                        Files.deleteIfExists(target);
                        throw new ResponseTooLargeException("artifact exceeded " + maxBytes + " bytes while downloading");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            } catch (IOException | RuntimeException e) {
                // never leave a half-written artifact behind for something to pick up
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // best effort
                }
                throw e;
            }
            return new DownloadResult(total, hex(digest.digest()));
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private HttpRequest request(URI uri, Map<String, String> headers, boolean firstHop, URI original) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .GET();
        if (headers != null) {
            boolean sameHost = uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(original.getHost());
            // first hop may carry Authorization; after a cross-host redirect it never travels
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = entry.getKey();
                if (!firstHop && "authorization".equalsIgnoreCase(name) && !sameHost) {
                    continue;
                }
                builder.header(name, entry.getValue());
            }
        }
        return builder.build();
    }

    private URI checkedRedirect(String location, URI current, int hop, URI original) throws RedirectNotAllowedException {
        if (hop + 1 >= MAX_REDIRECTS) {
            throw new RedirectNotAllowedException("too many redirects chasing " + original);
        }
        if (location == null || location.isBlank()) {
            throw new RedirectNotAllowedException("redirect without a Location header from " + current);
        }
        URI target;
        try {
            target = current.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new RedirectNotAllowedException("redirect target does not resolve: " + abbreviate(location));
        }
        if (!redirectPolicy.allows(target.toString(), serviceHost)) {
            throw new RedirectNotAllowedException("redirect to " + target.getHost() + " is not allow-listed");
        }
        return target;
    }

    private static URI parse(String url) throws RedirectNotAllowedException {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new RedirectNotAllowedException("update URL must be absolute https: " + abbreviate(url));
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new RedirectNotAllowedException("update URL does not parse: " + abbreviate(url));
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String firstHeader(java.net.http.HttpResponse<?> response, String name) {
        List<String> values = response.headers().map().get(name.toLowerCase(java.util.Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Map<String, String> lowerCased(HttpHeaders headers) {
        Map<String, String> map = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : headers.map().entrySet()) {
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!entry.getValue().isEmpty()) {
                map.put(key, entry.getValue().get(0));
            }
        }
        return map;
    }

    private static long contentLength(Map<String, String> headers) {
        String value = headers.get("content-length");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        byte[] buffer = new byte[Math.min(maxBytes + 1, 64 * 1024)];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(maxBytes + 1, 64 * 1024));
        int read;
        int total = 0;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new ResponseTooLargeException("response body exceeded " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the JVM - how?", e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "<null>";
        }
        String singleLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 120 ? singleLine.substring(0, 120) + "..." : singleLine;
    }
}
