package lol.candyriya.continuum.common.update;

/**
 * Which distribution channel an artifact travels on.
 *
 * <p>The channel is part of the signed manifest payload on purpose: a dev jar
 * can never present itself as a release, and vice versa. The updater never
 * infers the channel from the version string - it comes from the operator's
 * own {@code update.yml}.</p>
 */
public enum UpdateChannel {

    /** Tip-jar release channel. Requires a valid license token on every check. */
    RELEASE("release"),

    /** Development channel. Anonymous, no signature verification, never published to GitHub Releases. */
    DEV("dev");

    private final String wire;

    UpdateChannel(String wire) {
        this.wire = wire;
    }

    /** The literal used on the wire and in manifests. */
    public String wire() {
        return wire;
    }

    /** Strict parse; returns null for anything else (callers treat null as "config is broken"). */
    public static UpdateChannel fromWire(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "release" -> RELEASE;
            case "dev" -> DEV;
            default -> null;
        };
    }
}
