package lol.candyriya.continuum.paper.modules.advancements;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs player advancement progress across backends; the advancements module.
 *
 * <p><b>Payload format.</b> {@link AdvancementsPayload} format {@value AdvancementsPayload#FORMAT}:
 * a list of {@link AdvancementEntry} (namespaced key, awarded criteria, done state,
 * per-criterion award dates as epoch milliseconds) plus {@code captured_at_epoch_milli}.
 * Continuum persists the payload as JSON; unknown JSON properties are tolerated, and
 * this build only restores format {@value AdvancementsPayload#FORMAT}. Entries are
 * sorted by key and criteria alphabetically, so captures of the same state serialize
 * identically. No display data is stored — keys, criteria, booleans and timestamps
 * only, so even a five-thousand-advancement modpack keeps the snapshot small.</p>
 *
 * <p><b>What capture records.</b> Every advancement the server knows where the player
 * has at least one awarded criterion or is done — including recipe-unlock advancements,
 * exactly like vanilla tracks them. Advancements untouched by the player are omitted.
 * Award dates are read from {@link AdvancementProgress#getDateAwarded(String)}; a
 * criterion the platform reports no date for is simply omitted from
 * {@code awarded_at} (which stays in the payload for forensics and diagnostics).</p>
 *
 * <p><b>Unknown advancements on the target.</b> When the target server does not have an
 * advancement (absent data pack, different mod set), the advancement is skipped with a
 * warning and the rest of the restore proceeds — that is the whole failure story. This
 * policy is fixed to <em>warn and skip</em>: the configuration schema currently exposes
 * only a per-module on/off switch, and there is deliberately no hidden knob here. A
 * richer policy (plain skip, save-for-later) would land in the common config schema
 * first and be read here second. Criteria captured for an advancement are only ever
 * written if the target declares that advancement.</p>
 *
 * <p><b>Criteria unknown to the target.</b> A captured criterion that the target's
 * version of the advancement does not declare (pack updated in between) is dropped
 * with a warning; the remaining criteria are still restored. The same tolerance applies
 * to a capture from an older snapshot.</p>
 *
 * <p><b>Restore is award-only, never revoke.</b> {@link AdvancementProgress#revokeCriteria}
 * is never called. Absence of a criterion in the snapshot means "not awarded when the
 * capture was taken", not "must be removed now" — revoking on restore would erase
 * progress the player legitimately made on this backend after the capture, and would
 * turn a routine backend switch into data loss. If a deploying operator genuinely wants
 * strict wiping, that is a policy for the config schema to grow, with a config switch
 * this module would honor; it does not exist today, and guessing is worse than
 * documenting.</p>
 *
 * <p><b>Completion converges through criteria.</b> There is no direct "mark done" write:
 * vanilla derives completion from criteria, so awarding {@link AdvancementProgress#awardCriteria}
 * completes the advancement exactly when it should. If the target declares criteria the
 * capture never had (e.g. a datapack added criteria to an already-completed advancement),
 * the advancement cannot be force-completed — it stays incomplete until gameplay awards
 * what is missing. The module logs a warning in that case instead of faking completion.
 * Idempotent: re-running a restore awards nothing the target already has.</p>
 *
 * <p><b>Timestamps.</b> Award dates in {@code awarded_at} describe the moment the
 * criterion was awarded on the backend that produced the capture. Restore cannot
 * backdate: the public API stamps awards made through {@code awardCriteria} with the
 * current time, always. The captured dates are retained in the payload for
 * forensics; once the restore lands, the next capture simply records the new
 * restore-time dates. Nothing is lost silently and nothing is pretended.</p>
 *
 * <p><b>Failure isolation.</b> A corrupt payload is reported via {@link #validate} and
 * never reaches the player. A per-criterion award failure is logged with context and
 * fails the module at the end (so Continuum records it) without aborting the remaining
 * criteria or other modules. An empty snapshot is a no-op.</p>
 *
 * <p><b>Thread affinity.</b> Per the module contract, {@link #capture}, {@link #validate}
 * and {@link #restore} are invoked on the thread that owns the player — the server main
 * thread on Paper, the entity's region thread on Folia. Registry iteration
 * ({@link Bukkit#advancementIterator()}) and progress reads/writes are legal there.
 * The iteration is O(n) in registry size and happens once per capture; the module never
 * hops threads itself, and Continuum persists the payload elsewhere.</p>
 */
public final class AdvancementsModule implements SyncModule<Player, AdvancementsPayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancementsModule.class);

    /** The module id this module registers as; also exposed for registration wiring. */
    public static final String MODULE_ID = BuiltinModules.ADVANCEMENTS;

    private final Clock clock = Clock.systemUTC();

    @Override
    public @NotNull String id() {
        return MODULE_ID;
    }

    @Override
    public int version() {
        return AdvancementsPayload.FORMAT;
    }

    @Override
    public @NotNull CompletionStage<AdvancementsPayload> capture(@NotNull Player player) {
        try {
            var entries = new ArrayList<AdvancementEntry>();
            var iterator = Bukkit.advancementIterator();
            while (iterator.hasNext()) {
                Advancement advancement = iterator.next();
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                Collection<String> awarded = progress.getAwardedCriteria();
                if (awarded.isEmpty() && !progress.isDone()) {
                    // untouched advancement: not our business
                    continue;
                }
                String key = advancement.getKey().toString();
                if (!AdvancementKeys.isValid(key)) {
                    // a registry the server loaded itself should always pass; if a
                    // broken datapack slips in here, lose the entry, not the capture
                    LOGGER.warn("skipping advancement with malformed key '{}' during capture for player {}", key, player.getUniqueId());
                    continue;
                }
                var criteria = new TreeSet<>(awarded);
                var awardedAt = new TreeMap<String, Long>();
                for (String criterion : criteria) {
                    Date at = progress.getDateAwarded(criterion);
                    if (at != null) {
                        awardedAt.put(criterion, at.getTime());
                    } else {
                        LOGGER.debug("advancement '{}': platform reported no award date for criterion '{}' (player {})", key, criterion, player.getUniqueId());
                    }
                }
                entries.add(new AdvancementEntry(key, List.copyOf(criteria), progress.isDone(), awardedAt));
            }
            entries.sort(Comparator.comparing(AdvancementEntry::key));
            return CompletableFuture.completedFuture(
                    new AdvancementsPayload(AdvancementsPayload.FORMAT, clock.millis(), entries));
        } catch (RuntimeException e) {
            // capture nothing rather than persist a partial snapshot
            LOGGER.error("capture failed for player {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("failed to capture advancements for player " + player.getUniqueId(), e));
        }
    }

    @Override
    public @NotNull CompletionStage<Void> restore(@NotNull Player player, @NotNull AdvancementsPayload data) {
        var server = player.getServer();
        AdvancementsRestorePlanner.Plan plan = AdvancementsRestorePlanner.plan(data, key -> {
            NamespacedKey parsed = NamespacedKey.fromString(key);
            if (parsed == null) {
                return Optional.empty(); // unreachable post-planner-validation; cheap belt and braces
            }
            Advancement advancement = server.getAdvancement(parsed);
            if (advancement == null) {
                return Optional.empty(); // unknown on this server: warn-and-skip at decision time
            }
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            return Optional.of(new AdvancementsRestorePlanner.TargetAdvancement(
                    Set.copyOf(advancement.getCriteria()), Set.copyOf(progress.getAwardedCriteria()), progress.isDone()));
        });

        int applied = 0;
        long alreadySynced = 0;
        var failed = new ArrayList<String>();
        for (AdvancementsRestorePlanner.Decision decision : plan.decisions()) {
            switch (decision.action()) {
                case SKIP_MALFORMED_KEY -> LOGGER.warn(
                        "skipping advancement entry with malformed key '{}' for player {}", decision.key(), player.getUniqueId());
                case SKIP_UNKNOWN_ADVANCEMENT -> LOGGER.warn(
                        "skipping advancement '{}' for player {}: this server does not have it (unknown advancement policy: warn and skip)",
                        decision.key(), player.getUniqueId());
                case NONE -> {
                    alreadySynced++;
                    if (!decision.unknownCriteria().isEmpty()) {
                        // all captured criteria are unknown to this node: dropped, not awarded
                        LOGGER.warn("advancement '{}' for player {}: dropping criteria {} — this server does not declare them",
                                decision.key(), player.getUniqueId(), decision.unknownCriteria());
                    }
                    if (decision.doneCaptured() && !decision.targetDone()) {
                        LOGGER.warn("advancement '{}' is marked complete for player {}, but this server still has criteria to award; "
                                        + "completion is criteria-derived and cannot be forced",
                                decision.key(), player.getUniqueId());
                    }
                }
                case APPLY -> {
                    if (!decision.unknownCriteria().isEmpty()) {
                        LOGGER.warn("advancement '{}' for player {}: dropping criteria {} — this server does not declare them",
                                decision.key(), player.getUniqueId(), decision.unknownCriteria());
                    }
                    NamespacedKey parsed = NamespacedKey.fromString(decision.key());
                    Advancement advancement = parsed == null ? null : server.getAdvancement(parsed);
                    if (advancement == null) {
                        // registry changed out from under us mid-restore; skip, don't fail
                        LOGGER.warn("advancement '{}' disappeared between planning and restore for player {}; skipping",
                                decision.key(), player.getUniqueId());
                        break;
                    }
                    AdvancementProgress progress = player.getAdvancementProgress(advancement);
                    for (String criterion : decision.toAward()) {
                        try {
                            if (progress.awardCriteria(criterion)) {
                                applied++;
                            } else {
                                // already awarded or unknown — the plan filtered both, this is a race, not a failure
                                LOGGER.debug("criterion '{}' of advancement '{}' was not awarded to player {} (already awarded?)",
                                        criterion, decision.key(), player.getUniqueId());
                            }
                        } catch (RuntimeException e) {
                            LOGGER.error("failed to award criterion '{}' of advancement '{}' to player {}",
                                    criterion, decision.key(), player.getUniqueId(), e);
                            failed.add(decision.key() + ":" + criterion);
                        }
                    }
                }
            }
        }
        LOGGER.debug("advancements restored for player {}: awardedCriteria={} alreadySynced={} unknown={} malformed={} criteriaDropped={} failed={}",
                player.getUniqueId(), applied, alreadySynced,
                plan.count(AdvancementsRestorePlanner.Action.SKIP_UNKNOWN_ADVANCEMENT),
                plan.count(AdvancementsRestorePlanner.Action.SKIP_MALFORMED_KEY),
                plan.totalUnknownCriteria(), failed.size());

        if (!failed.isEmpty()) {
            // failure isolation: other modules restore normally; this one is marked failed
            return CompletableFuture.failedFuture(
                    new IllegalStateException("failed to award criteria for player " + player.getUniqueId()
                            + ": " + String.join(", ", failed)));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletionStage<ValidationResult> validate(@NotNull Player player, @NotNull AdvancementsPayload data) {
        // structural problem in the stored payload; Continuum skips or aborts per the module policy
        return CompletableFuture.completedFuture(AdvancementsValidation.check(data));
    }
}
