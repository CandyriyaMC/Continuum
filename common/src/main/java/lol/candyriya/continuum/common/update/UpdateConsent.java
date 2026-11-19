package lol.candyriya.continuum.common.update;

/**
 * What the updater may do once it knows an update exists.
 *
 * <p>{@link #NOTIFY} is the default: console-only nudge, nothing written into
 * the server until a human runs {@code /continuum update install}.
 * {@link #AUTO_INSTALL} downloads, verifies and stages the update on its
 * own; the swap still happens at the next restart, never live.</p>
 */
public enum UpdateConsent {

    /** Warn the console; stage nothing without an explicit command. */
    NOTIFY("notify"),

    /** Download, verify and stage automatically. Restart still applies the update. */
    AUTO_INSTALL("auto-install");

    private final String wire;

    UpdateConsent(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** Strict parse; returns null for anything else (callers treat null as "config is broken"). */
    public static UpdateConsent fromWire(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "notify" -> NOTIFY;
            case "auto-install" -> AUTO_INSTALL;
            default -> null;
        };
    }
}
