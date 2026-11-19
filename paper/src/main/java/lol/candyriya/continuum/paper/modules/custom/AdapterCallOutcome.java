package lol.candyriya.continuum.paper.modules.custom;

/**
 * The verdict of one adapter's capture attempt (or restore step), produced off the entity
 * thread. Exactly one of {@code entry} / {@code failure} is set; {@code fatal} is only
 * meaningful when {@code failure} is set, and holds iff the adapter's failure policy is
 * {@code ABORT}.
 *
 * @param entry    the built payload entry, or null on failure
 * @param failure  the honest failure record, or null on success
 * @param fatal    true when the failure policy is ABORT — the operation cannot continue
 * @param sizeBytes serialized size of the entry's payload; 0 on failure
 */
record AdapterCallOutcome(AdapterEntry entry, AdapterFailure failure, boolean fatal, int sizeBytes) {

    static AdapterCallOutcome success(AdapterEntry entry, int sizeBytes) {
        return new AdapterCallOutcome(entry, null, false, sizeBytes);
    }

    static AdapterCallOutcome failure(AdapterFailure failure, boolean fatal) {
        return new AdapterCallOutcome(null, failure, fatal, 0);
    }
}
