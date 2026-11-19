package lol.candyriya.continuum.paper.modules.statistics;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs cumulative player statistics: mob kills, deaths, distance walked,
 * play time, items used, blocks mined and the rest of the platform's stat grid.
 *
 * <p><b>Why not plain overwrite.</b> Statistics are monotonic counters, not
 * state. If server A and server B each hold their own counters and a snapshot
 * is naïvely applied on top, one of two bad things happens: values get
 * overwritten and this server's progress evaporates, or a naive {@code +=}
 * merge double-counts everything the snapshot already saw. Neither is
 * acceptable, so the reconciliation here is deliberately asymmetric.</p>
 *
 * <p><b>The reconciliation rule (catch-up only).</b> On restore, for every
 * captured cell the module compares the snapshot value against the live local
 * counter:</p>
 * <ul>
 *   <li>snapshot &gt; local — this server is behind: the local counter is
 *       raised to the snapshot value (an absolute write, never a relative
 *       increment);</li>
 *   <li>snapshot ≤ local — this server is ahead: nothing is written, the
 *       local counter wins;</li>
 *   <li>key unknown to this build — skipped with a warning, the rest of the
 *       grid applies normally.</li>
 * </ul>
 *
 * <p>Net effect: a player's unified view converges to the maximum across
 * servers. Because both capture (at quit/autosave) and restore (at join) go
 * through this one code path on the same store, the value can never be lost
 * and never be double-counted.</p>
 *
 * <p><b>Crash semantics, honestly.</b> All writes are absolute sets of
 * already-validated values, so restore is naturally idempotent: if the server
 * dies mid-restore, the next join re-runs the same catch-up writes and lands
 * in exactly the same state. There is no compute-then-apply window that can
 * strand a delta, because there is no delta. The trade-offs of absolute-max
 * rather than atomic-increment reconciliation are worth naming plainly:</p>
 * <ul>
 *   <li>an intentional stat <em>decrease</em> (an admin or a plugin resetting
 *       a counter) cannot propagate: the higher local counter wins on every
 *       restore, so the reset is silently undone on rejoin. Vanilla gameplay
 *       never decreases stats, and no supported flow depends on decreases;</li>
 *   <li>stats reset on server A never make it to server B while B holds a
 *       stale higher counter — same root cause;</li>
 *   <li>counters that only ever grow — i.e. everything the platform tracks —
 *       are exactly and safely merged, including across 2, 3 or N backends,
 *       with no shared lock and no coordination beyond the snapshot itself.</li>
 * </ul>
 *
 * <p><b>Where the atomic increment path lives.</b> The storage layer ships an
 * atomic {@code SET stat = stat + ?} / {@code $inc} applier for statistics.
 * That is the right tool for a <em>continuous</em> mid-session flush (backend
 * tracks counters since session start, flushes on autosave, resets the marker
 * only after the write succeeds; a crash loses at most the unflushed window,
 * never double-counts). That wiring needs a session baseline and a storage
 * hook this module does not have yet, and half-wiring it would produce exactly
 * the double-count failure this module exists to prevent. Session restore uses
 * catch-up reconciliation exclusively — it is correct today for every
 * quit→join cycle, which is the dominant path; the continuous flush is a
 * separate, additive step on top of the same keys.</p>
 *
 * <p><b>Capture is a full dump, compactly stored.</b> Every addressable cell
 * is read (untyped stats, item/block stats × their legal material qualifiers,
 * entity stats × keyed entity types — bounded by the platform enums, roughly
 * 65–70k cells today, read in-memory for online players). Zero-value cells
 * are omitted: under the restore rule a missing key and a zero are the same
 * information. A typical snapshot carries tens to a few hundred entries.</p>
 *
 * <p><b>Validation is structural, not semantic.</b> Format, key syntax,
 * non-negative values, range, duplicates, entry cap. Keys this build cannot
 * resolve (captured on a newer server) are explicitly not a validation
 * failure — restore skips them with a warning and applies everything else.</p>
 *
 * <p><b>Threading.</b> Capture/restore run on the player's own scheduler
 * thread per the platform contract. The dump is a tight in-memory read loop
 * with no blocking I/O on the calling thread's side beyond the platform's own
 * stat access. The restore writes are plain stat sets on the same thread.</p>
 *
 * <p>Thread-safety: this class keeps no mutable state.</p>
 */
public final class StatisticsModule implements SyncModule<Player, StatisticsPayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsModule.class);

    @Override
    public String id() {
        return BuiltinModules.STATISTICS;
    }

    @Override
    public int version() {
        return StatisticsPayload.FORMAT;
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public CompletionStage<StatisticsPayload> capture(Player player) {
        return CompletableFuture.completedFuture(StatisticsDump.dump(StatisticsDump.player(player)));
    }

    @Override
    public CompletionStage<Void> restore(Player player, StatisticsPayload data) {
        StatisticsRestorePlanner.RestorePlan<StatSlot> plan =
                StatisticsRestorePlanner.plan(data.stats(), StatSlots::resolve, StatisticsDump.player(player));
        StatisticsApply.ApplyResult result = StatisticsApply.apply(player, plan.writes());
        LOGGER.info("statistics restore for player {}: wrote {} counters, kept {} ahead-of-snapshot locally, "
                        + "skipped {} unknown keys, {} unreadable, {} failed writes",
                player.getUniqueId(), result.applied(), plan.keptAhead(),
                plan.skippedUnknown().size(), plan.unreadable().size(), result.failed().size());
        if (!plan.skippedUnknown().isEmpty()) {
            LOGGER.warn("statistics keys unknown to this build were skipped: {}", plan.skippedUnknown());
        }
        if (!result.failed().isEmpty()) {
            // absolute sets are idempotent — retrying after the reason is fixed is safe
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "statistics restore could not write " + result.failed().size() + " counters: " + result.failed()));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, StatisticsPayload data) {
        return CompletableFuture.completedFuture(StatisticsValidation.validate(data));
    }
}
