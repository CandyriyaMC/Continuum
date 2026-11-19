package lol.candyriya.continuum.paper.modules.statistics;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The catalog of every stat cell this build knows how to read and write.
 *
 * <p>Built once from the platform enums: {@code Statistic.values()} drives the
 * stat set, qualifiers come from the subsets of {@code Material} /
 * {@code EntityType} that are legal for that stat kind (block stats pair with
 * block materials, item stats with item materials, entity stats with keyed,
 * known entity types). The qualifier is the lowercase enum constant name
 * ({@code diamond_sword}, {@code stone}, {@code zombie}), which is the stable
 * part of a vanilla platform enum. Constants the platform cannot address —
 * exotic unusable enum constants like {@code EntityType.UNKNOWN} — are
 * excluded, never guessed.</p>
 *
 * <p>Capturing stats means iterating {@link #all()}; restoring a snapshot goes
 * through {@link #resolve(String)}, which maps a stored key back onto a slot —
 * or rejects it when the key is from a different build (unknown stat, unknown
 * qualifier, family/stat mismatch).</p>
 */
public final class StatSlots {

    private static final List<StatSlot> ALL = buildAll();
    private static final Map<String, StatSlot> BY_KEY = indexByKey(ALL);

    private StatSlots() {
        // static catalog
    }

    /** Every slot, in deterministic (stat-then-qualifier) order. Unmodifiable. */
    public static List<StatSlot> all() {
        return ALL;
    }

    /** Number of addressable cells; useful for diagnostics. */
    public static int size() {
        return ALL.size();
    }

    /**
     * Maps a stored key onto a slot. Empty when the key is unknown to this
     * build, when its family does not match the stat's qualifier kind, or when
     * the qualifier does not resolve to a material/entity type this server
     * knows. Callers skip-and-warn on empty; a snapshot from a newer build
     * must not abort the restore of everything else.
     */
    public static Optional<StatSlot> resolve(String key) {
        Optional<StatKey.Decoded> decoded = StatKey.parse(key);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        StatKey.Decoded parsed = decoded.get();
        StatSlot slot = BY_KEY.get(key);
        if (slot == null) {
            return Optional.empty();
        }
        // belt and braces: a hand-crafted key could pair a family with a stat of another kind
        if (slot.statistic().getType() != expectedType(parsed.family())) {
            return Optional.empty();
        }
        return Optional.of(slot);
    }

    private static Statistic.Type expectedType(StatKey.Family family) {
        return switch (family) {
            case UNTYPED -> Statistic.Type.UNTYPED;
            case ITEM -> Statistic.Type.ITEM;
            case BLOCK -> Statistic.Type.BLOCK;
            case ENTITY -> Statistic.Type.ENTITY;
        };
    }

    private static List<StatSlot> buildAll() {
        List<StatSlot> slots = new ArrayList<>();
        Map<String, StatSlot> seen = new HashMap<>();
        for (Statistic statistic : Statistic.values()) {
            String stat = statistic.name().toLowerCase(Locale.ROOT);
            switch (statistic.getType()) {
                case UNTYPED -> add(slots, seen, new StatSlot(StatKey.encodeUntyped(stat), statistic, null, null));
                case ITEM -> {
                    for (Material material : Material.values()) {
                        if (!safeIsItem(material)) {
                            continue;
                        }
                        String qualifier = qualifierOf(material.name());
                        if (qualifier == null) {
                            continue;
                        }
                        add(slots, seen, new StatSlot(StatKey.encodeQualified(StatKey.Family.ITEM, stat, qualifier), statistic, material, null));
                    }
                }
                case BLOCK -> {
                    for (Material material : Material.values()) {
                        if (!safeIsBlock(material)) {
                            continue;
                        }
                        String qualifier = qualifierOf(material.name());
                        if (qualifier == null) {
                            continue;
                        }
                        add(slots, seen, new StatSlot(StatKey.encodeQualified(StatKey.Family.BLOCK, stat, qualifier), statistic, material, null));
                    }
                }
                case ENTITY -> {
                    for (EntityType entityType : EntityType.values()) {
                        String qualifier = unusableQualifier(entityType.name());
                        if (qualifier == null) {
                            // constants like UNKNOWN cannot be stat cells
                            continue;
                        }
                        add(slots, seen, new StatSlot(StatKey.encodeQualified(StatKey.Family.ENTITY, stat, qualifier), statistic, null, entityType));
                    }
                }
            }
        }
        return Collections.unmodifiableList(slots);
    }

    private static void add(List<StatSlot> slots, Map<String, StatSlot> seen, StatSlot slot) {
        StatSlot previous = seen.putIfAbsent(slot.key(), slot);
        if (previous != null) {
            // enum registries should never collide us; if a platform build ever
            // does, fail loud instead of silently dropping a counter cell
            throw new IllegalStateException("duplicate statistics slot key: " + slot.key());
        }
        slots.add(slot);
    }

    private static Map<String, StatSlot> indexByKey(List<StatSlot> slots) {
        Map<String, StatSlot> byKey = new HashMap<>(slots.size() * 2);
        for (StatSlot slot : slots) {
            byKey.put(slot.key(), slot);
        }
        return Collections.unmodifiableMap(byKey);
    }

    private static boolean safeIsItem(Material material) {
        try {
            return material.isItem();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean safeIsBlock(Material material) {
        try {
            return material.isBlock();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Lowercased enum constant name — the wire qualifier. Returns null when the
     * name cannot appear in a key (anything outside {@code [a-z0-9_]+}); platform
     * enum names never do, but a module that crashes on a weird constant is worse
     * than one cell missing.
     */
    private static String qualifierOf(String enumName) {
        String lowered = enumName.toLowerCase(Locale.ROOT);
        return StatKey.isValidQualifier(lowered) ? lowered : null;
    }

    /**
     * The qualifier for an ENTITY slot, or null when the constant cannot be a
     * stat cell ({@code UNKNOWN} and friends are not tracked by the platform).
     */
    private static String unusableQualifier(String enumName) {
        if ("UNKNOWN".equals(enumName)) {
            return null;
        }
        return qualifierOf(enumName);
    }
}
