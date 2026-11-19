package lol.candyriya.continuum.paper.modules.recipes;

import java.util.List;

/**
 * Computed effect of a mirror restore, before anything is applied.
 *
 * <p>All lists are sorted, duplicate-free and contain only strings that
 * passed strict validation — the diff math is plain set algebra, so the
 * result is deterministic and unit-testable without a server. Anything the
 * plan could not use is reported, never dropped quietly.</p>
 *
 * @param toDiscover           effective captured keys the player has not discovered yet; discover these
 * @param toForget             local discovered keys the target server knows but the capture does not;
 *                             undiscover these (exact-replica mirror)
 * @param unknownToTarget      captured keys the target server does not know (e.g. modded vs vanilla
 *                             mismatch); skipped with a warning, restore continues without them
 * @param forgetSkippedUnknown local discovered keys the target server does not know; the book cannot
 *                             carry them anyway, skipped with a warning
 * @param invalidCaptured      captured strings that failed strict validation; defensive — the
 *                             validation gate rejects the payload before restore in production, this
 *                             exists so a bypassed gate still never gets malformed keys onto the wire
 * @param duplicatesCollapsed  captured entries dropped as duplicates
 */
public record MirrorPlan(
        List<String> toDiscover,
        List<String> toForget,
        List<String> unknownToTarget,
        List<String> forgetSkippedUnknown,
        List<String> invalidCaptured,
        int duplicatesCollapsed) {

    /** Whether applying this plan mutates the player at all. */
    public boolean changed() {
        return !toDiscover.isEmpty() || !toForget.isEmpty();
    }
}
