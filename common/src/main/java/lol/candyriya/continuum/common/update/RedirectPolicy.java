package lol.candyriya.continuum.common.update;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether an HTTP redirect target is allowed.
 *
 * <p>Only {@code https} URLs whose host equals, or is a subdomain of, an
 * allow-listed suffix are followed. Credentials embedded in the URL,
 * non-default ports, IP literals, empty hosts and anything that fails to
 * parse are refused outright. A refused redirect is not fatal - the check
 * cycle simply ends and tries again 6h later.</p>
 *
 * <p>The list always contains the host the update URLs were issued against;
 * Cloudflare R2's public suffix comes from settings ({@code .r2.dev} by
 * default).</p>
 */
public final class RedirectPolicy {

    private final List<String> allowedSuffixes;

    public RedirectPolicy(List<String> allowedSuffixes) {
        this.allowedSuffixes = allowedSuffixes == null || allowedSuffixes.isEmpty()
                ? UpdateSettings.DEFAULT_REDIRECT_SUFFIXES
                : allowedSuffixes.stream()
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
    }

    /** True when following a redirect to {@code target} is allowed. {@code baseHost} is always allowed. */
    public boolean allows(String target, String baseHost) {
        final URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            return false;
        }
        // only the default https port may travel implicitly
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        host = normalize(host);
        String base = baseHost == null ? "" : normalize(baseHost);
        if (!base.isEmpty() && host.equals(base)) {
            return true;
        }
        for (String suffix : allowedSuffixes) {
            String needle = suffix.startsWith(".") ? suffix : "." + suffix;
            if (host.equals(needle.substring(1)) || host.endsWith(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String host) {
        try {
            return IDN.toASCII(host.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // unparseable idna - keep the raw lowercase form; it simply will not match anything
            return host.trim().toLowerCase(Locale.ROOT);
        }
    }
}
