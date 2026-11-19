package lol.candyriya.continuum.common.update;

/**
 * What the updater remembers between restarts, persisted as
 * {@code .update-state} in the plugin's data directory.
 *
 * <p>All fields are optional. {@code stagedBuild} non-null means an update
 * jar is sitting in the update folder (Paper) or has been swapped in place
 * (Velocity) and has not yet proved itself by booting. {@code
 * pendingNotifiedVersion} dedupes the console nudge; {@code
 * lastLicenseNagEpochDay} throttles the no-license line to once per
 * day.</p>
 */
public record UpdateState(String stagedBuild,
                          String stagedSha256,
                          String backupFile,
                          String swappedAt,
                          String pendingNotifiedVersion,
                          Long lastLicenseNagEpochDay) {

    public static UpdateState empty() {
        return new UpdateState(null, null, null, null, null, null);
    }

    public boolean hasStagedUpdate() {
        return stagedBuild != null && !stagedBuild.isBlank();
    }

    public UpdateState withStaged(String build, String sha256, String backupFile, String swappedAt) {
        return new UpdateState(build, sha256, backupFile, swappedAt, null, lastLicenseNagEpochDay);
    }

    /** Clears the pending-apply record; used after a clean boot proves the swap worked. */
    public UpdateState clearedStaged() {
        return new UpdateState(null, null, null, null, pendingNotifiedVersion, lastLicenseNagEpochDay);
    }

    public UpdateState withPendingNotified(String build) {
        return new UpdateState(stagedBuild, stagedSha256, backupFile, swappedAt, build, lastLicenseNagEpochDay);
    }

    public UpdateState withLastLicenseNagEpochDay(long epochDay) {
        return new UpdateState(stagedBuild, stagedSha256, backupFile, swappedAt, pendingNotifiedVersion, epochDay);
    }
}
