package lol.candyriya.continuum.common.protocol;

/**
 * Wire protocol versioning for every cross-component message (RabbitMQ events
 * and Plugin Messaging frames alike). Bump {@link #CURRENT_VERSION} whenever
 * the envelope shape changes; receivers must tolerate every version reported
 * by {@link #isSupported(int)}.
 */
public final class Protocol {

    /** Version this build writes. */
    public static final int CURRENT_VERSION = 1;

    /** Oldest version this build can read. */
    public static final int MIN_SUPPORTED_VERSION = 1;

    private Protocol() {
    }

    public static boolean isSupported(int version) {
        return version >= MIN_SUPPORTED_VERSION && version <= CURRENT_VERSION;
    }
}
