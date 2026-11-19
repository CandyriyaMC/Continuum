package lol.candyriya.continuum.paper.modules.advancements;

import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;

/**
 * Captured snapshot of a player's advancement progress; the unit of data the
 * advancements module persists and restores.
 *
 * <p>Wire format (stable, versioned; bump {@link #FORMAT} on an incompatible change);
 * the payload is a JSON object with snake_case keys under Continuum's snapshot mapper</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "captured_at_epoch_milli": 1763456789012,
 *   "advancements": [
 *     { "key": "minecraft:story/mine_stone",
 *       "awarded_criteria": ["crafting_table"],
 *       "done": false,
 *       "awarded_at": { "crafting_table": 1763456000000 } }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code format} is the payload schema marker; {@link #FORMAT} is the only value
 * this build writes and the only one it restores. {@code advancements} is sorted by
 * {@code key} so two captures of the same state serialize identically (enforced by
 * the constructor, not just by the capture path). Only
 * advancements with at least one awarded criterion or a done state are recorded —
 * everything else is noise. No display data, descriptions or requirements JSON is
 * stored; the entry is as small as the state demands.</p>
 *
 * @param format               payload format version; always {@value #FORMAT} in this build
 * @param capturedAtEpochMilli wall-clock time of capture, epoch milliseconds
 * @param advancements         the captured advancement entries, sorted by key; empty when the player has no progress
 */
public record AdvancementsPayload(int format, long capturedAtEpochMilli, @NotNull List<AdvancementEntry> advancements) {

    /** Wire format version written and restored by this build. */
    public static final int FORMAT = 1;

    /** Corrupt-payload guard on the number of entries. Several thousand x vanilla; modpacks fit comfortably. */
    public static final int MAX_ADVANCEMENTS = 8_192;

    /** Corrupt-payload guard on the criteria list of a single advancement. Vanilla advancements have a handful. */
    public static final int MAX_CRITERIA_PER_ADVANCEMENT = 256;

    public AdvancementsPayload {
        // sorted by key here as well as at capture: wire output must not depend on who
        // built the payload. stable sort, duplicates keep their relative order
        advancements = advancements == null ? List.of()
                : advancements.stream()
                        .sorted(Comparator.comparing(AdvancementEntry::key))
                        .toList();
    }
}
