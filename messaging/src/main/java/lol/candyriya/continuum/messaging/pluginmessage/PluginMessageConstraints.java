package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.regex.Pattern;

/**
 * Hard format limits for every string and count-like field on the wire.
 *
 * <p>Everything here is enforced on decode <i>and</i> on encode — a message
 * that cannot go over the wire also cannot be built by accident. Limits are
 * deliberately generous for their purpose and far below anything that could
 * hurt: a 256-character reason is already more log than anyone needs.</p>
 *
 * <p>These are constants on purpose, not config: raising them per-deployment
 * buys nothing and makes the wire contract depend on the environment. The
 * one configurable knob is the frame cap ({@link PluginMessageLimits}).</p>
 */
public final class PluginMessageConstraints {

    /** Server names as registered on the proxy: lowercase, dotted/underscored, 1..64 chars. */
    public static final Pattern SERVER_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

    /** BCP 47-ish language tags: {@code en_US}, {@code ru}, {@code zh-CN}, {@code kk-Cyrl}, ... */
    public static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-zA-Z]{2,8}([-_][a-zA-Z0-9]{1,8}){0,4}$");

    /** Dotted lowercase wire ids, e.g. {@code continuum.handoff.request}. Mirrors the event-id convention used across the codebase. */
    public static final Pattern WIRE_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+$");

    /** Largest accepted length for a server name, in Java chars. */
    public static final int MAX_SERVER_NAME = 64;

    /** Largest accepted length for free-form log context ({@code reason}, {@code detail}), in Java chars. */
    public static final int MAX_LOG_TEXT = 256;

    /** Largest accepted length for an opaque session token, in Java chars. Printable ASCII only. */
    public static final int MAX_TOKEN = 512;

    /** Largest accepted length for a locale tag, in Java chars. */
    public static final int MAX_LOCALE = 35;

    /** Largest accepted player capacity count on a status report. */
    public static final int MAX_PLAYER_COUNT = 1_000_000;

    /** Instants outside [1970-01-01T00:00:00Z, 2100-01-01T00:00:00Z) are treated as broken input. */
    public static final long MIN_INSTANT_EPOCH_MILLI = 0L;

    /** Upper bound for instant-carrying fields, epoch milli (2100-01-01T00:00:00Z). */
    public static final long MAX_INSTANT_EPOCH_MILLI = 4_102_444_800_000L;

    private PluginMessageConstraints() {
    }

    /** Whether {@code id} is the all-zeros UUID, which never names a real player or correlation. */
    public static boolean isNilUuid(java.util.UUID id) {
        return id != null && id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L;
    }

    /**
     * Whether {@code text} contains no characters below 0x20 and none at 0x7F.
     * Free-form log context may carry any printable text, including non-ASCII;
     * control bytes are what turn a log line into an injection.
     */
    public static boolean hasNoControlChars(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code token} is non-blank printable ASCII (0x21..0x7E). */
    public static boolean isPrintableAsciiToken(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code name} is a valid server name for the wire. */
    public static boolean isValidServerName(String name) {
        return name != null && name.length() <= MAX_SERVER_NAME && SERVER_NAME_PATTERN.matcher(name).matches();
    }

    /** Whether {@code locale} is an accepted locale tag for the wire. */
    public static boolean isValidLocale(String locale) {
        return locale != null && locale.length() <= MAX_LOCALE && LOCALE_PATTERN.matcher(locale).matches();
    }

    /** Whether {@code millis} is within [1970-01-01T00:00:00Z, 2100-01-01T00:00:00Z). */
    public static boolean isSaneInstant(long millis) {
        return millis >= MIN_INSTANT_EPOCH_MILLI && millis < MAX_INSTANT_EPOCH_MILLI;
    }
}
