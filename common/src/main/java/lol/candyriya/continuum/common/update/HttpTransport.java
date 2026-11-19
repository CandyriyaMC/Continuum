package lol.candyriya.continuum.common.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The HTTP surface the updater needs, abstracted so tests can run without a
 * network.
 *
 * <p>Both methods speak only to allow-listed {@code https} endpoints, follow
 * redirects manually (see {@link RedirectPolicy}) and cap what they read.
 * {@link #downloadTo} streams the body to disk while hashing it, so a
 * multi-megabyte jar never occupies the heap.</p>
 */
public interface HttpTransport extends AutoCloseable {

    /**
     * GETs {@code url} and returns the whole body.
     *
     * @throws ResponseTooLargeException   the body exceeded {@code maxBodyBytes}
     * @throws RedirectNotAllowedException a redirect target was refused by the allow-list
     */
    HttpResponse getBytes(String url, Map<String, String> headers, int maxBodyBytes) throws IOException, InterruptedException;

    /**
     * GETs {@code url}, streaming the body into {@code target} while hashing it.
     *
     * @return bytes written and the lowercase hex SHA-256 of those exact bytes
     * @throws ResponseTooLargeException   the body exceeded {@code maxBytes} (partial file is removed)
     * @throws RedirectNotAllowedException a redirect target was refused by the allow-list
     */
    DownloadResult downloadTo(String url, Map<String, String> headers, Path target, long maxBytes)
            throws IOException, InterruptedException;

    @Override
    default void close() {
        // JDK transport holds no persistent connections worth draining; override when that changes
    }

    /** A fully-read response. Headers are lower-cased; the first value wins. */
    record HttpResponse(int status, Map<String, String> headers, byte[] body) {

        /** Case-insensitive header lookup (keys are stored lower-cased). */
        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** What {@link #downloadTo} produced. */
    record DownloadResult(long bytesWritten, String sha256Hex) {
    }
}
