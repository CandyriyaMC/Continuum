package lol.candyriya.continuum.paper.modules.statistics;

import org.bukkit.Statistic;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import org.jetbrains.annotations.Nullable;

/**
 * One addressable cell of the cumulative statistics grid: a stat plus, when the
 * stat is qualified, a concrete material or entity type.
 *
 * <p>A slot is fully described by {@link #key()} — the rest is convenience for
 * the platform calls. A slot only exists when the platform itself considers the
 * stat/qualifier pair legal (e.g. a block stat never pairs with a non-block
 * material), so a slot that made it into the catalog is always safe to read.</p>
 *
 * @param key       the canonical qualified key, see {@link StatKey}
 * @param statistic  the statistic enum constant, never null
 * @param material   the material qualifier for ITEM/BLOCK slots, never null there
 * @param entityType the entity type qualifier for ENTITY slots, never null there
 */
public record StatSlot(String key, Statistic statistic, @Nullable Material material, @Nullable EntityType entityType) {

    public StatSlot {
        // shape invariant: families line up with their qualifiers
        switch (statistic.getType()) {
            case UNTYPED -> {
                if (material != null || entityType != null) {
                    throw new IllegalArgumentException("untyped slot cannot carry a qualifier");
                }
            }
            case ITEM, BLOCK -> {
                if (material == null || entityType != null) {
                    throw new IllegalArgumentException("ITEM/BLOCK slot needs exactly a material qualifier");
                }
            }
            case ENTITY -> {
                if (entityType == null || material != null) {
                    throw new IllegalArgumentException("ENTITY slot needs exactly an entity type qualifier");
                }
            }
        }
    }
}
