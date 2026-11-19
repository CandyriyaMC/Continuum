package lol.candyriya.continuum.paper.modules.effects;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Bridge from a namespaced effect-type key to what the restore path needs to know
 * about that type on this server: whether the type exists at all, and whether it
 * is instant.
 *
 * <p>Kept as an interface so the restore planner stays free of platform types and
 * can be unit-tested with a fixed namespace map. Production code wires it to the
 * platform registry; tests wire it to a constant map.</p>
 */
@FunctionalInterface
public interface EffectTypeLookup {

    /**
     * Resolves {@code typeKey} against this server's effect-type registry.
     *
     * @param typeKey the namespaced key from the snapshot, e.g. {@code minecraft:speed}
     * @return the type info, or {@link Optional#empty()} when the key is unknown or malformed
     */
    @NotNull
    Optional<Info> resolve(@NotNull String typeKey);

    /**
     * What restore needs to know about a known effect type.
     *
     * @param instant whether the type applies instantly and is never re-applied on restore
     */
    record Info(boolean instant) {
    }
}
