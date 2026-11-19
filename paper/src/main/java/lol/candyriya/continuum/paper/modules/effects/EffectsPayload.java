package lol.candyriya.continuum.paper.modules.effects;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Captured snapshot of a player's active potion effects; the unit of data the effects
 * module persists and restores.
 *
 * <p>Wire format (stable, versioned; bump {@link #FORMAT} on an incompatible change):
 * the payload is a JSON object with snake_case keys under Continuum's snapshot mapper</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "captured_at_epoch_milli": 1763456789012,
 *   "effects": [
 *     { "type": "minecraft:speed", "duration_ticks": 600, "amplifier": 0,
 *       "ambient": false, "particles": true, "icon": true, "instant": false }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code format} is the payload schema marker; {@link #FORMAT} is the only value
 * this build writes and the only one it restores. {@code captured_at_epoch_milli} is
 * the capture wall-clock time in epoch milliseconds — restore subtracts the elapsed
 * time so effects that ran out while the player was on another server are never
 * re-applied. {@code duration_ticks} of {@value #INFINITE_DURATION_TICKS} (-1) marks
 * an infinite effect; it stays infinite across restore.</p>
 *
 * @param format                payload format version; always {@value #FORMAT} in this build
 * @param capturedAtEpochMilli  wall-clock time of capture, epoch milliseconds
 * @param effects               the captured effects, or an empty list when none were active
 */
public record EffectsPayload(int format, long capturedAtEpochMilli, @NotNull List<EffectEntry> effects) {

    /** Wire format version written and restored by this build. */
    public static final int FORMAT = 1;

    /** Duration marker for effects that never expire (beacon, conduit power, {@code /effect give ... infinite}). */
    public static final int INFINITE_DURATION_TICKS = -1;

    /** Server ticks per second; durations are stored in ticks and converted with 50 ms per tick. */
    public static final int TICKS_PER_SECOND = 20;

    /** Milliseconds per server tick. */
    public static final long MILLIS_PER_TICK = 1_000L / TICKS_PER_SECOND;

    /** Hard cap on the effects list, as a corrupt-payload guard. Well above the vanilla type count. */
    public static final int MAX_EFFECTS = 128;

    public EffectsPayload {
        effects = effects == null ? List.of() : List.copyOf(effects);
    }
}
