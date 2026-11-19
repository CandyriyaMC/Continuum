package lol.candyriya.continuum.paper.modules.statistics;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Qualified statistics keys — the wire encoding every counter uses in snapshots
 * and in storage-side atomic increments.
 *
 * <p>Vanilla persists stats per qualifier-type in a fixed category layout; that
 * layout is an implementation detail and not stable across platforms, so this
 * module defines its own canonical form:</p>
 *
 * <pre>{@code
 * family ":" stat                      -- untyped
 * family ":" stat ":" qualifier        -- qualified
 * }</pre>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code untyped:deaths}</li>
 *   <li>{@code untyped:play_one_minute}</li>
 *   <li>{@code item:use_item:diamond_sword}</li>
 *   <li>{@code block:mine_block:stone}</li>
 *   <li>{@code entity:kill_entity:zombie}</li>
 * </ul>
 *
 * <p>Rules, all enforced by {@link #parse(String)}:</p>
 * <ul>
 *   <li>the family is one of {@code untyped}, {@code item}, {@code block}, {@code entity};</li>
 *   <li>{@code untyped} keys have exactly two colon-separated parts and never carry a qualifier;</li>
 *   <li>qualified families have exactly three parts; the qualifier is the lowercase
 *       platform enum constant name ({@code material.name()} / {@code entityType.name()}),
 *       which is always {@code [a-z0-9_]+};</li>
 *   <li>{@code stat} is the lowercase enum constant name ({@code [a-z0-9_]+});</li>
 *   <li>everything is lowercase; anything else is rejected, never normalized silently.</li>
 * </ul>
 *
 * <p>The qualifier is the bare platform enum name, not a namespaced key: the
 * qualifier space is exactly {@code Material} / {@code EntityType}, both vanilla
 * platform enums, so a hard-coded {@code minecraft:} prefix would add bytes to
 * every entry without adding any information.</p>
 *
 * <p>The format is versioned alongside the payload ({@link StatisticsPayload#FORMAT});
 * adding a new family means bumping that, not guessing from part counts.</p>
 *
 * <p>This class is deliberately free of any Bukkit types — encoding and parsing
 * is pure string logic, usable anywhere.</p>
 */
public final class StatKey {

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");

    private StatKey() {
        // constants and statics only
    }

    /** Statistics family — mirrors the qualifier kind of the platform's stat model. */
    public enum Family {
        UNTYPED,
        ITEM,
        BLOCK,
        ENTITY;

        /** The lowercase wire spelling. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Family> fromWire(String value) {
            for (Family family : values()) {
                if (family.wire().equals(value)) {
                    return Optional.of(family);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Decoded form of a qualified key.
     *
     * @param family    the statistics family, never null
     * @param stat      the lowercase stat name (lowercase enum constant), never blank
     * @param qualifier the qualifier (lowercase platform enum constant name), or null for {@link Family#UNTYPED}
     */
    public record Decoded(Family family, String stat, String qualifier) {

        /** Canonical re-encode; round-trips with {@link #parse(String)}. */
        public String encode() {
            return StatKey.encode(family, stat, qualifier);
        }
    }

    /** Builds an untyped key: {@code untyped:<stat>}. */
    public static String encodeUntyped(String stat) {
        return encode(Family.UNTYPED, stat, null);
    }

    /** Builds a qualified key: {@code <family>:<stat>:<qualifier>}. */
    public static String encodeQualified(Family family, String stat, String qualifier) {
        return encode(requireQualified(family), stat, requireQualifier(qualifier));
    }

    private static Family requireQualified(Family family) {
        if (family == null || family == Family.UNTYPED) {
            throw new IllegalArgumentException("family must be ITEM, BLOCK or ENTITY, got: " + family);
        }
        return family;
    }

    private static String requireQualifier(String qualifier) {
        if (qualifier == null || !isValidQualifier(qualifier)) {
            throw new IllegalArgumentException("qualifier must match [a-z0-9_]+, got: " + qualifier);
        }
        return qualifier;
    }

    private static String encode(Family family, String stat, String qualifier) {
        if (family == null) {
            throw new IllegalArgumentException("family is required");
        }
        if (stat == null || !TOKEN.matcher(stat).matches()) {
            throw new IllegalArgumentException("stat must match [a-z0-9_]+, got: " + stat);
        }
        if (family == Family.UNTYPED) {
            if (qualifier != null) {
                throw new IllegalArgumentException("untyped keys never carry a qualifier: " + qualifier);
            }
            return "untyped:" + stat;
        }
        if (qualifier == null || !isValidQualifier(qualifier)) {
            throw new IllegalArgumentException("qualified key needs a [a-z0-9_]+ qualifier, got: " + qualifier);
        }
        return family.wire() + ':' + stat + ':' + qualifier;
    }

    /**
     * Parses a key; empty when malformed, mismatched or non-canonical.
     *
     * <p>Strict on purpose: a snapshot is machine-written, so anything that
     * does not parse cleanly is corrupt data, not something to be lenient with.</p>
     */
    public static Optional<Decoded> parse(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String[] parts = key.split(":", -1);
        Optional<Family> family = Family.fromWire(parts[0]);
        if (family.isEmpty()) {
            return Optional.empty();
        }
        return switch (parts.length) {
            case 2 -> family.get() == Family.UNTYPED && TOKEN.matcher(parts[1]).matches()
                    ? Optional.of(new Decoded(family.get(), parts[1], null))
                    : Optional.empty();
            case 3 -> {
                Family resolved = family.get();
                if (resolved == Family.UNTYPED
                        || !TOKEN.matcher(parts[1]).matches()
                        || !TOKEN.matcher(parts[2]).matches()) {
                    yield Optional.empty();
                }
                yield Optional.of(new Decoded(resolved, parts[1], parts[2]));
            }
            default -> Optional.empty();
        };
    }

    /** True when the value is a {@code [a-z0-9_]+} platform enum name (lowercased). */
    public static boolean isValidQualifier(String qualifier) {
        return qualifier != null && TOKEN.matcher(qualifier).matches();
    }
}
