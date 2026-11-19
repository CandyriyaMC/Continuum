package lol.candyriya.continuum.paper.modules.vitals;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs current health, absorption, food level, saturation, exhaustion, air,
 * fire ticks, freeze ticks and (optionally) fall distance.
 *
 * <p><b>Cross-module ordering.</b> Max health lives on the {@code minecraft:max_health}
 * attribute, which the {@code continuum.attributes} module owns. This module
 * declares {@link BuiltinModules#ATTRIBUTES} as a dependency, so any restore
 * plan built through the API restores attributes first and max health is
 * already raised by the time {@link #restore} runs — current health is clamped
 * to the effective max at that moment, never to a stale pre-restore value.
 * Max health itself is never written here. When the API's dependency mechanism
 * is bypassed (e.g. a hand-rolled restore pipe), the in-module clamp still
 * bounds health to whatever effective max is live, so a corrupt snapshot
 * cannot throw inside the platform layer — it degrades to the vanilla default
 * ceiling with a warning instead.</p>
 *
 * <p><b>In-module restore order</b> (also see {@link VitalsApply}):
 * effective max health read → food → saturation → exhaustion → air → fire
 * ticks → freeze ticks → health (clamped) → absorption (last) → fall distance
 * (value only, no teleport — position belongs to the location module).
 * Absorption comes after health on purpose: nothing may ride on top of a
 * half-restored health state.</p>
 *
 * <p><b>Fall distance.</b> Captured and restored by default because a fall
 * interrupted by a server switch must not be forgotten. Construct the module
 * with {@code captureFallDistance = false} for transports where restoring it
 * is wrong (e.g. cross-world moves) — capture then records {@code null} and
 * restore skips the write entirely.</p>
 *
 * <p><b>What is deliberately not restored:</b> max health (attributes
 * module), max-air limits (no platform API exposes one — see
 * {@link VitalsPayload}), the {@code oxygen_bonus} / {@code air_drag_modifier}
 * attributes (attributes module), visual fire (it self-expires with
 * {@code fireTicks}).</p>
 *
 * <p>Thread-safety: all state is read/written on the player's own thread, as
 * the sync contract requires; this class keeps no mutable state of its own.</p>
 */
public final class VitalsModule implements SyncModule<Player, VitalsPayload> {

    private static final List<String> DEPENDENCIES = List.of(BuiltinModules.ATTRIBUTES);

    private final boolean captureFallDistance;

    /** Creates the module capturing fall distance (default). */
    public VitalsModule() {
        this(true);
    }

    /**
     * Creates the module.
     *
     * @param captureFallDistance whether to capture and restore fall distance;
     *                            when false, {@code fall_distance} is stored as null and skipped at restore
     */
    public VitalsModule(boolean captureFallDistance) {
        this.captureFallDistance = captureFallDistance;
    }

    @Override
    public String id() {
        return BuiltinModules.VITALS;
    }

    @Override
    public int version() {
        return VitalsPayload.FORMAT;
    }

    @Override
    public List<String> dependencies() {
        return DEPENDENCIES;
    }

    @Override
    public CompletionStage<VitalsPayload> capture(Player player) {
        // effective max health is informational here — restore always clamps to the live attribute value
        var maxInstance = player.getAttribute(Attribute.MAX_HEALTH);
        double maxBase = maxInstance != null ? maxInstance.getBaseValue() : Attribute.MAX_HEALTH.getDefaultValue();
        double health = player.getHealth();
        VitalsPayload payload = new VitalsPayload(
                VitalsPayload.FORMAT,
                health,
                VitalsPayload.MAX_HEALTH_ATTRIBUTE_KEY,
                maxBase,
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getRemainingAir(),
                player.getFireTicks(),
                player.getFreezeTicks(),
                captureFallDistance ? player.getFallDistance() : null);
        return CompletableFuture.completedFuture(payload);
    }

    @Override
    public CompletionStage<Void> restore(Player player, VitalsPayload data) {
        VitalsApply.apply(data, new PlayerVitalsOps(player), captureFallDistance);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, VitalsPayload data) {
        return CompletableFuture.completedFuture(VitalsValidation.validate(data));
    }

    /** Thin pass-through from the pure restore logic to the live entity. */
    private record PlayerVitalsOps(Player player) implements VitalsOps {

        @Override
        public double maxHealth() {
            var instance = player.getAttribute(Attribute.MAX_HEALTH);
            return instance != null ? instance.getValue() : Attribute.MAX_HEALTH.getDefaultValue();
        }

        @Override
        public int maxFireTicks() {
            return player.getMaxFireTicks();
        }

        @Override
        public int maxFreezeTicks() {
            return player.getMaxFreezeTicks();
        }

        @Override
        public void setFoodLevel(int level) {
            player.setFoodLevel(level);
        }

        @Override
        public void setSaturation(float value) {
            player.setSaturation(value);
        }

        @Override
        public void setExhaustion(float value) {
            player.setExhaustion(value);
        }

        @Override
        public void setRemainingAir(int ticks) {
            player.setRemainingAir(ticks);
        }

        @Override
        public void setFireTicks(int ticks) {
            player.setFireTicks(ticks);
        }

        @Override
        public void setFreezeTicks(int ticks) {
            player.setFreezeTicks(ticks);
        }

        @Override
        public void setHealth(double health) {
            player.setHealth(health);
        }

        @Override
        public void setAbsorptionAmount(double amount) {
            player.setAbsorptionAmount(amount);
        }

        @Override
        public void setFallDistance(float distance) {
            player.setFallDistance(distance);
        }
    }
}
