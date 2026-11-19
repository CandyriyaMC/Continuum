package lol.candyriya.continuum.paper.modules.metadata;

import java.util.List;

/**
 * Outcome of a restore pass, exposed so callers (and tests) can see exactly
 * which keys were written, reset or skipped — never inferred from platform
 * state after the fact. Keys are the wire keys from {@link MetadataEntry}.
 *
 * @param applied keys that were written with a decoded custom component, in application order
 * @param reset   keys that were reset to the vanilla default because the snapshot carried no value
 * @param failed  keys whose stored JSON could not be decoded; empty on a clean restore
 */
public record RestoreReport(List<String> applied, List<String> reset, List<String> failed) {

    public RestoreReport {
        applied = List.copyOf(applied);
        reset = List.copyOf(reset);
        failed = List.copyOf(failed);
    }

    /** True when every targeted key was either applied or reset cleanly. */
    public boolean hadFailures() {
        return !failed.isEmpty();
    }
}
