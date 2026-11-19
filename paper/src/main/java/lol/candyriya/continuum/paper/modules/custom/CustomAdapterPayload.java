package lol.candyriya.continuum.paper.modules.custom;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Wire payload of the {@code continuum.custom} module.
 *
 * <p>{@code adapters} carries one entry per adapter that captured successfully;
 * {@code failures} carries one record per adapter that did not (or could not). Both lists
 * are part of the persisted snapshot: a permission-style audit of "which adapters contributed
 * what, and which lost data at this transition" is possible without any external store.</p>
 *
 * <p>Unknown JSON properties are tolerated at decode time so newer writers can coexist with
 * older readers during rolling upgrades; renaming or removing a component is a schema bump
 * and requires {@link #FORMAT} to change.</p>
 *
 * @param format     payload format version, always {@value #FORMAT}
 * @param capturedAt when the capture ran, never null
 * @param adapters   the successfully captured entries, never null, adapter ids unique
 * @param failures   the honest per-adapter failure records, never null
 * @since 1.0.0
 */
public record CustomAdapterPayload(int format,
                                   Instant capturedAt,
                                   List<AdapterEntry> adapters,
                                   List<AdapterFailure> failures) {

    /** Current wire format. Bump when the serialized shape changes incompatibly. */
    public static final int FORMAT = 1;

    public CustomAdapterPayload {
        if (format < 1) {
            throw new IllegalArgumentException("format must be >= 1, got " + format);
        }
        Objects.requireNonNull(capturedAt, "capturedAt must not be null");
        Objects.requireNonNull(adapters, "adapters must not be null");
        Objects.requireNonNull(failures, "failures must not be null");
        adapters = List.copyOf(adapters);
        failures = List.copyOf(failures);
        var seen = new HashSet<String>();
        for (AdapterEntry entry : adapters) {
            if (entry == null) {
                throw new IllegalArgumentException("adapters must not contain null entries");
            }
            if (!seen.add(entry.adapterId())) {
                throw new IllegalArgumentException("duplicate adapter entry '" + entry.adapterId() + "'");
            }
        }
        for (AdapterFailure failure : failures) {
            if (failure == null) {
                throw new IllegalArgumentException("failures must not contain null entries");
            }
        }
    }
}
