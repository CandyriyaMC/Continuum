package lol.candyriya.continuum.paper.modules.pdc;

import java.util.ArrayList;

/**
 * Decides which payload entries a restore may apply. Pure: no server types.
 *
 * <p>Re-applies every safety net at restore time instead of trusting that the payload
 * passed validation earlier:</p>
 * <ul>
 *   <li>denied namespaces are skipped - a snapshot must never be able to write a
 *       credential-shaped namespace onto a target server, whatever produced it;</li>
 *   <li>malformed keys and undecodable binary values are skipped;</li>
 *   <li>the per-value and total caps apply here too, so a corrupt or hostile snapshot
 *       cannot write unbounded data into the target's PDC. Entries that do not fit the
 *       remaining budget are skipped, not fatal - additive restore stays idempotent.</li>
 * </ul>
 *
 * <p>Restore never removes anything: unknown keys the target already has are left alone.
 * That is deliberate - see the module javadoc for the stale-key tradeoff.</p>
 */
public final class PdcRestorePlanner {

    private PdcRestorePlanner() {
        // static access only
    }

    /**
     * Plans the restore for {@code payload}.
     *
     * @param payload the payload to plan, never null
     * @return the plan; never null, actions are in payload order
     */
    public static PdcRestorePlan plan(PdcPayload payload) {
        var actions = new ArrayList<PdcEntry>();
        int skippedDenied = 0;
        int skippedIllegalKey = 0;
        int skippedUndecodable = 0;
        int skippedTooLarge = 0;
        int skippedBudgetExhausted = 0;
        long remaining = PdcLimits.TOTAL_BYTES;

        for (PdcEntry entry : payload.entries()) {
            String key = entry.key();
            if (!PdcCodec.isWellFormedKey(key)) {
                skippedIllegalKey++;
                continue;
            }
            String namespace = PdcCodec.namespaceOf(key);
            if (namespace != null && PdcNamespaceDenyList.isDenied(namespace)) {
                skippedDenied++;
                continue;
            }
            long size;
            try {
                size = PdcCodec.storedSize(entry.type(), entry.value());
            } catch (IllegalArgumentException e) {
                skippedUndecodable++;
                continue;
            }
            if (size > PdcLimits.PER_KEY_BYTES) {
                skippedTooLarge++;
                continue;
            }
            if (size > remaining) {
                skippedBudgetExhausted++;
                continue;
            }
            remaining -= size;
            actions.add(entry);
        }

        return new PdcRestorePlan(actions, skippedDenied, skippedIllegalKey,
                skippedUndecodable, skippedTooLarge, skippedBudgetExhausted);
    }
}
