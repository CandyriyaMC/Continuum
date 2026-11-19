package lol.candyriya.continuum.common.update;

import java.util.List;

/**
 * Values the updater reads from {@code update.yml} in the plugin's data
 * directory.
 *
 * <p>{@code licenseToken} is the release-channel credential; it never leaves
 * the machine except as the {@code Authorization: Bearer} header on the
 * update endpoint. {@code redirectAllowSuffixes} lists the host suffixes a
 * redirect may point at; the default is Cloudflare R2's public suffix and is
 * only ever applied to {@code https} targets.</p>
 *
 * <p>{@code problems} is empty for a healthy file. Any entry means the file
 * was rejected as a whole and the updater disables itself for this run -
 * everything else on the server keeps working.</p>
 */
public record UpdateSettings(boolean checkEnabled,
                             UpdateChannel channel,
                             UpdateConsent consent,
                             String licenseToken,
                             List<String> redirectAllowSuffixes,
                             List<String> problems) {

    public static final List<String> DEFAULT_REDIRECT_SUFFIXES = List.of(".r2.dev");

    public UpdateSettings {
        licenseToken = licenseToken == null ? "" : licenseToken.trim();
        redirectAllowSuffixes = redirectAllowSuffixes == null || redirectAllowSuffixes.isEmpty()
                ? DEFAULT_REDIRECT_SUFFIXES
                : List.copyOf(redirectAllowSuffixes);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** All defaults, consent NOTIFY, release channel, no token. Used when the file is absent and just got seeded. */
    public static UpdateSettings defaults() {
        return new UpdateSettings(true, UpdateChannel.RELEASE, UpdateConsent.NOTIFY, "",
                DEFAULT_REDIRECT_SUFFIXES, List.of());
    }

    /**
     * A settings object that disables the updater. Reserved for a broken
     * {@code update.yml}; the reasons are in {@code problems} and get logged
     * once.
     */
    public static UpdateSettings disabled(List<String> problems) {
        return new UpdateSettings(false, UpdateChannel.RELEASE, UpdateConsent.NOTIFY, "",
                DEFAULT_REDIRECT_SUFFIXES, problems);
    }

    /** False when the file was rejected; {@code checkEnabled} alone is the master switch otherwise. */
    public boolean usable() {
        return problems.isEmpty();
    }
}
