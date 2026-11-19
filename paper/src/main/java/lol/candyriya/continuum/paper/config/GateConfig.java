package lol.candyriya.continuum.paper.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.List;

/**
 * Join-gate tuning for this backend node.
 *
 * @param enabled                  hold players until their data is restored on join; see platform.yml
 * @param maxFailures              consecutive restore failures from one player before logins are refused
 * @param failureCooldownMinutes   how long the refusal lasts after the failure that tripped the limit
 * @param restoreTimeoutSeconds    seconds to wait for a restore before the player is kicked; 0 disables (not recommended)
 * @param disallowOnStorageDegraded refuse logins while the storage layer reports it cannot read snapshots; fail-closed
 */
@ConfigSerializable
public record GateConfig(boolean enabled,
                         int maxFailures,
                         int failureCooldownMinutes,
                         int restoreTimeoutSeconds,
                         boolean disallowOnStorageDegraded) {

    public static final int MAX_FAILURES_LIMIT = 50;
    public static final int MAX_COOLDOWN_MINUTES = 24 * 60;
    public static final int MAX_RESTORE_TIMEOUT_SECONDS = 10 * 60;

    void validate(List<String> problems) {
        if (maxFailures < 1 || maxFailures > MAX_FAILURES_LIMIT) {
            problems.add("gate.max-failures: must be between 1 and " + MAX_FAILURES_LIMIT);
        }
        if (failureCooldownMinutes < 1 || failureCooldownMinutes > MAX_COOLDOWN_MINUTES) {
            problems.add("gate.failure-cooldown-minutes: must be between 1 and " + MAX_COOLDOWN_MINUTES);
        }
        if (restoreTimeoutSeconds < 0 || restoreTimeoutSeconds > MAX_RESTORE_TIMEOUT_SECONDS) {
            problems.add("gate.restore-timeout-seconds: must be between 0 (disabled) and " + MAX_RESTORE_TIMEOUT_SECONDS);
        }
    }
}
