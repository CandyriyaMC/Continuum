package lol.candyriya.continuum.velocity;

/**
 * Proxy-local settings, produced by {@link VelocityLocalConfigLoader} from
 * {@code velocity.yml} in the plugin's data directory. The shared
 * {@code config.yml} holds everything the whole network agrees on; this holds
 * the knobs Velocity itself owns.
 *
 * @param velocity the proxy-local tuning block
 */
public record VelocityLocalConfig(VelocityTuning velocity) {

    public VelocityLocalConfig {
        if (velocity == null) {
            velocity = VelocityTuning.DEFAULT;
        }
    }

    public static VelocityLocalConfig defaults() {
        return new VelocityLocalConfig(VelocityTuning.DEFAULT);
    }

    /** Convenience accessor; see {@link VelocityTuning#reconnectDebounceSeconds()}. */
    public long reconnectDebounceSeconds() {
        return velocity.reconnectDebounceSeconds();
    }

    /** Convenience accessor; see {@link VelocityTuning#handoffRestoreTimeoutSeconds()}. */
    public long handoffRestoreTimeoutSeconds() {
        return velocity.handoffRestoreTimeoutSeconds();
    }
}
