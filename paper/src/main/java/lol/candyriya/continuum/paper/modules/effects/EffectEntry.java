package lol.candyriya.continuum.paper.modules.effects;

import org.jetbrains.annotations.NotNull;

/**
 * One captured potion effect, flattened for storage.
 *
 * <p>{@code type} is the effect type's namespaced key (e.g. {@code minecraft:speed}),
 * never a translated name or an id — ids and names are not stable across server builds
 * or locales, keys are. {@code durationTicks} is in server ticks (20 per second);
 * {@value EffectsPayload#INFINITE_DURATION_TICKS} marks an infinite effect. Amplifier
 * is clamped to {@code 0..255} (the NBT byte range) at capture and again at restore.</p>
 *
 * <p>{@code instant} marks instant effects (instant health, instant damage, saturation).
 * They never linger in the active-effects list, so capture normally sees none; if one is
 * recorded anyway it is skipped at restore — re-applying an instant effect on join would
 * hand out free healing or damage.</p>
 *
 * @param type          namespaced key of the effect type, e.g. {@code minecraft:speed}; never blank
 * @param durationTicks effect duration in ticks, or {@value EffectsPayload#INFINITE_DURATION_TICKS} for infinite effects
 * @param amplifier     effect amplifier, clamped to {@code 0..255}
 * @param ambient       ambient flag (beacon-style, fewer particles)
 * @param particles     whether the client shows particles
 * @param icon          whether the client shows the HUD icon
 * @param instant       whether the effect type is instant; such effects are not re-applied on restore
 */
public record EffectEntry(@NotNull String type, int durationTicks, int amplifier,
                          boolean ambient, boolean particles, boolean icon, boolean instant) {

    public EffectEntry {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be null or blank");
        }
        type = type.trim();
    }
}
