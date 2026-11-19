package lol.candyriya.continuum.common.update;

/**
 * Immutable view of what the updater knows right now. Returned by command
 * surfaces; never null, never stale by more than the last transition.
 */
public record UpdateSnapshot(UpdatePhase phase,
                             String currentBuild,
                             String availableBuild,
                             String message,
                             long lastCheckedEpochSecond) {

    public static UpdateSnapshot of(UpdatePhase phase, String currentBuild, String availableBuild,
                                    String message, long lastCheckedEpochSecond) {
        return new UpdateSnapshot(phase, currentBuild, availableBuild, message, lastCheckedEpochSecond);
    }
}
