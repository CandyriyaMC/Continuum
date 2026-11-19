package lol.candyriya.continuum.paper.modules.pdc;

import lol.candyriya.continuum.api.module.ValidationResult;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The pre-restore sanity gate for {@link PdcPayload}s. Pure: structure and size
 * checks only, no semantics, no I/O. Never throws - anything ambiguous is an
 * invalid result with an English reason the operator can act on.
 *
 * <p>What this deliberately does not check: namespace policy. Whether an entry's
 * namespace may cross a server boundary is a restore-time decision made by
 * {@link PdcRestorePlanner}, because a snapshot can carry entries this build
 * would refuse to write, and rejecting the whole payload over a policy choice
 * would be worse than skipping that one entry.</p>
 */
public final class PdcValidation {

    private PdcValidation() {
        // static access only
    }

    /**
     * Checks a payload before it is applied.
     *
     * @param payload the payload to check, never null
     * @return ok() when the payload may be applied; invalid(reason) otherwise
     */
    public static ValidationResult validate(PdcPayload payload) {
        Objects.requireNonNull(payload, "payload");

        if (payload.format() != PdcPayload.FORMAT) {
            return ValidationResult.invalid(
                    "unsupported PDC payload format " + payload.format() + " (expected " + PdcPayload.FORMAT + ")");
        }

        List<PdcEntry> entries = payload.entries();
        var seen = new HashSet<String>();
        long total = 0;
        for (PdcEntry entry : entries) {
            String key = entry.key();
            if (!PdcCodec.isWellFormedKey(key)) {
                return ValidationResult.invalid("entry key '" + key + "' is not a legal namespaced key");
            }
            if (!seen.add(key)) {
                return ValidationResult.invalid("duplicate PDC entry for key '" + key + "'");
            }
            long size;
            try {
                size = PdcCodec.storedSize(entry.type(), entry.value());
            } catch (IllegalArgumentException e) {
                return ValidationResult.invalid("entry '" + key + "': " + e.getMessage());
            }
            if (size > PdcLimits.PER_KEY_BYTES) {
                return ValidationResult.invalid(
                        "entry '" + key + "' is " + size + " bytes, over the per-value cap of " + PdcLimits.PER_KEY_BYTES);
            }
            total += size;
            if (total > PdcLimits.TOTAL_BYTES) {
                return ValidationResult.invalid(
                        "PDC payload exceeds the total cap of " + PdcLimits.TOTAL_BYTES + " bytes");
            }
        }
        return ValidationResult.ok();
    }
}
