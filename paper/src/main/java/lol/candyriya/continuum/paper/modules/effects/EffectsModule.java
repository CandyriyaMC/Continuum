package lol.candyriya.continuum.paper.modules.effects;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Syncs active potion effects across backends; the effects module.
 *
 * <p><b>Payload format.</b> {@link EffectsPayload} format {@value EffectsPayload#FORMAT}:
 * a list of {@link EffectEntry} (type key, duration in ticks, amplifier, ambient,
 * particles, icon, instant flag) plus {@code captured_at_epoch_milli}. Continuum
 * persists the payload as JSON; unknown JSON properties in the payload are tolerated,
 * and this build only restores format {@value EffectsPayload#FORMAT}.</p>
 *
 * <p><b>Restore semantics.</b> Remaining duration is computed against the capture
 * timestamp in the payload: {@code remaining = duration - (now - capturedAt)}, time in
 * milliseconds converted to ticks at 50 ms per tick. Effects that ran out while the
 * player was on another server are skipped; infinite effects stay infinite; duplicate
 * type keys keep the last occurrence; instant effects are never re-applied; amplifiers
 * are clamped to {@code 0..255}. See {@link EffectsRestorePlanner} for the exact rules.</p>
 *
 * <p><b>Unknown effect types on the target server</b> (e.g. an effect from a plugin the
 * target node does not have) are skipped with a warning; the rest of the restore goes on.
 * A corrupt payload is reported via {@link #validate}; a payload that fails validation is
 * never applied to the player.</p>
 *
 * <p><b>Server-local effects.</b> This module has no per-effect exclusion list and never
 * will, by design: an effect that should not cross servers is server-local state, and the
 * supported way to keep it local is to disable this module for the node (the platform's
 * per-module toggle). No hardcoded exclusion set exists because no effect type is
 * universally harmful to carry over, and a hidden, module-specific blocklist would silently
 * drop player state — the exact failure this module exists to prevent.</p>
 *
 * <p><b>Thread affinity.</b> Per the module contract, {@link #capture}, {@link #validate}
 * and {@link #restore} are invoked on the thread that owns the player — the server main
 * thread on Paper, the entity's region thread on Folia. Reading the active effects, the
 * effect-type registry and applying effects are all legal there; Continuum never invokes
 * them from an infrastructure thread, so this module never hops threads itself. On restore,
 * each effect is applied with {@link Player#addPotionEffect} on that same thread; a
 * per-effect exception is logged and collected, and the module completes exceptionally at
 * the end if any effect failed, so Continuum records the module as failed while other
 * modules restore normally (failure isolation). "Applied" therefore means "handed to the
 * platform on the player's thread".</p>
 */
public final class EffectsModule implements SyncModule<Player, EffectsPayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EffectsModule.class);

    /** The module id this module registers as; also exposed for registration wiring. */
    public static final String MODULE_ID = BuiltinModules.EFFECTS;

    /** Registry-backed lookup; reads are immutable-after-bootstrap and safe from the player's thread. */
    private static final EffectTypeLookup LOOKUP = EffectsModule::resolveTypeInfo;

    private final Clock clock = Clock.systemUTC();

    @Override
    public @NotNull String id() {
        return MODULE_ID;
    }

    @Override
    public int version() {
        return EffectsPayload.FORMAT;
    }

    @Override
    public @NotNull CompletionStage<EffectsPayload> capture(@NotNull Player player) {
        try {
            Collection<PotionEffect> active = player.getActivePotionEffects();
            var entries = new ArrayList<EffectEntry>(active.size());
            for (PotionEffect effect : active) {
                Integer duration = EffectsRestorePlanner.normalizeDuration(effect.getDuration());
                if (duration == null) {
                    // zero or meaningless negative duration: nothing worth carrying over
                    LOGGER.warn("dropping effect '{}' with duration {} for player {}: not restorable",
                            effect.getType().getKey(), effect.getDuration(), player.getUniqueId());
                    continue;
                }
                entries.add(new EffectEntry(
                        effect.getType().getKey().toString(),
                        duration,
                        EffectsRestorePlanner.clampAmplifier(effect.getAmplifier()),
                        effect.isAmbient(),
                        effect.hasParticles(),
                        effect.hasIcon(),
                        effect.getType().isInstant()));
            }
            return CompletableFuture.completedFuture(
                    new EffectsPayload(EffectsPayload.FORMAT, clock.millis(),
                            EffectsRestorePlanner.dedupeLastWins(entries)));
        } catch (RuntimeException e) {
            // wrap with context, then let the module failure path handle it
            var failure = new IllegalStateException("failed to capture potion effects for player " + player.getUniqueId(), e);
            LOGGER.error("capture failed for player {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public @NotNull CompletionStage<Void> restore(@NotNull Player player, @NotNull EffectsPayload data) {
        EffectsRestorePlanner.Plan plan = EffectsRestorePlanner.plan(data, clock.millis(), LOOKUP);
        if (plan.clockSkewed()) {
            // target clock behind the capture clock: full durations restored, worth a warning
            LOGGER.warn("effects payload for player {} was captured at {}, which is in the future; "
                            + "restoring full durations (clock skew on the target node?)",
                    player.getUniqueId(), data.capturedAtEpochMilli());
        }
        if (plan.duplicatesDropped() > 0) {
            LOGGER.warn("effects payload for player {} contained {} duplicate type key(s); last occurrence wins",
                    player.getUniqueId(), plan.duplicatesDropped());
        }

        int applied = 0;
        long expired = plan.count(EffectsRestorePlanner.Action.SKIP_EXPIRED);
        long unknown = plan.count(EffectsRestorePlanner.Action.SKIP_UNKNOWN);
        long instants = plan.count(EffectsRestorePlanner.Action.SKIP_INSTANT);
        List<String> failed = new ArrayList<>();

        for (EffectsRestorePlanner.Decision decision : plan.decisions()) {
            switch (decision.action()) {
                case SKIP_EXPIRED -> {
                    // effect ran out while the player was elsewhere; nothing to do
                }
                case SKIP_UNKNOWN -> LOGGER.warn("skipping unknown effect type '{}' for player {}: "
                                + "not known to this server", decision.typeKey(), player.getUniqueId());
                case SKIP_INSTANT -> LOGGER.debug("skipping instant effect type '{}' for player {}: "
                        + "not re-applied on join", decision.typeKey(), player.getUniqueId());
                case APPLY -> {
                    switch (apply(player, decision)) {
                        case APPLIED -> applied++;
                        case REFUSED -> LOGGER.debug("platform refused effect '{}' for player {}",
                                decision.typeKey(), player.getUniqueId());
                        case INCONSISTENT -> LOGGER.warn("skipping effect type '{}' for player {}: "
                                        + "unusable despite passing the planner", decision.typeKey(),
                                player.getUniqueId());
                        case FAILED -> failed.add(decision.typeKey());
                    }
                }
            }
        }
        LOGGER.debug("effects restored for player {}: applied={} expired={} unknown={} instant={} failed={} duplicates={}",
                player.getUniqueId(), applied, expired, unknown, instants, failed.size(), plan.duplicatesDropped());

        if (!failed.isEmpty()) {
            // failure isolation: other modules restore normally; this one is marked failed
            return CompletableFuture.failedFuture(
                    new IllegalStateException("failed to apply effect(s) for player " + player.getUniqueId()
                            + ": " + String.join(", ", failed)));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletionStage<ValidationResult> validate(@NotNull Player player, @NotNull EffectsPayload data) {
        // structural problem in the stored payload; Continuum skips or aborts per the module policy
        return CompletableFuture.completedFuture(EffectsValidation.check(data));
    }

    /** Outcome of a single apply attempt. */
    private enum ApplyResult {
        /** Handed to the platform without an exception. */
        APPLIED,
        /** The platform refused (returned false); logged, not a failure. */
        REFUSED,
        /** The payload passed the planner but the entry cannot be applied; logged, not a failure. */
        INCONSISTENT,
        /** The platform threw; counted, and the module fails at the end. */
        FAILED
    }

    private ApplyResult apply(@NotNull Player player, @NotNull EffectsRestorePlanner.Decision decision) {
        if (decision.remainingTicks() != EffectsPayload.INFINITE_DURATION_TICKS
                && decision.remainingTicks() < 1) {
            // post-plan defensive check; planner already filters these, never trust it blindly
            return ApplyResult.INCONSISTENT;
        }
        PotionEffectType type = resolveType(decision.typeKey());
        if (type == null) {
            // registry disagreed with the lookup path — treat as unknown, not as a crash
            return ApplyResult.INCONSISTENT;
        }
        int duration = decision.remainingTicks() == EffectsPayload.INFINITE_DURATION_TICKS
                ? PotionEffect.INFINITE_DURATION
                : (int) decision.remainingTicks();
        try {
            boolean applied = player.addPotionEffect(new PotionEffect(type, duration, decision.amplifier(),
                    decision.ambient(), decision.particles(), decision.icon()));
            return applied ? ApplyResult.APPLIED : ApplyResult.REFUSED;
        } catch (RuntimeException e) {
            LOGGER.error("failed to apply effect '{}' for player {}", decision.typeKey(), player.getUniqueId(), e);
            return ApplyResult.FAILED;
        }
    }

    private static @NotNull Optional<EffectTypeLookup.Info> resolveTypeInfo(@NotNull String typeKey) {
        PotionEffectType type = resolveType(typeKey);
        return type == null ? Optional.empty() : Optional.of(new EffectTypeLookup.Info(type.isInstant()));
    }

    private static @Nullable PotionEffectType resolveType(@NotNull String typeKey) {
        NamespacedKey key = NamespacedKey.fromString(typeKey);
        if (key == null) {
            // malformed key in the snapshot (no namespace, illegal characters, ...)
            return null;
        }
        return Registry.MOB_EFFECT.get(key);
    }
}
