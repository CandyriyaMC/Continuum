package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.module.ModuleIds;

import java.time.Instant;
import java.util.Objects;

/**
 * One adapter's slice of the custom module's payload. Serialized by the snapshot layer
 * as JSON (snake_case via the shared mapper); renaming a component is a schema bump.
 *
 * @param adapterId      the id of the adapter that captured this entry, never null, well-formed
 * @param adapterVersion the adapter's schema version at capture time, &ge; 1
 * @param required       whether the adapter's data is load-bearing; recorded so restore can
 *                       honor the failure policy even when the adapter is not registered anymore
 * @param failurePolicy  the adapter's failure policy at capture time, same caveat as {@code required}
 * @param payload        the adapter's opaque JSON payload (UTF-8 JSON produced from its data
 *                       type); never interpreted by Continuum, never null
 * @param capturedAt     when the entry was captured, never null
 * @since 1.0.0
 */
public record AdapterEntry(String adapterId,
                           int adapterVersion,
                           boolean required,
                           FailurePolicy failurePolicy,
                           String payload,
                           Instant capturedAt) {

    public AdapterEntry {
        Objects.requireNonNull(adapterId, "adapterId must not be null");
        ModuleIds.requireValid(adapterId);
        if (adapterVersion < 1) {
            throw new IllegalArgumentException("adapterVersion must be >= 1, got " + adapterVersion);
        }
        Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        // an empty payload string is corrupt; a null payload never reaches here
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("payload must not be empty for adapter '" + adapterId + "'");
        }
        Objects.requireNonNull(capturedAt, "capturedAt must not be null");
    }
}
