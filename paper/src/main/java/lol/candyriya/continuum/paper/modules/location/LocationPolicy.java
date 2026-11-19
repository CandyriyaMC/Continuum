package lol.candyriya.continuum.paper.modules.location;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Module-layer policy for the location module. Distinct from the platform's gate-time
 * spawn policy: the gate decides where a connection <em>appears</em>; this policy decides
 * whether the module <em>moves the player afterwards</em> and which snapshot worlds are
 * off-limits. It is deliberately not a fork of, or a re-export of, the platform's policy.
 *
 * <p>The {@code enabled} switch from the config example lives one level up, in the shared
 * {@code sync.modules.location} toggle — when it is false the module is not registered and
 * this policy is never consulted. Keeping a second flag here would just give operators two
 * switches that can drift apart.</p>
 *
 * <p>{@link #excludeWorlds} is a list of world <em>names</em>, matched case-insensitively
 * (both sides trimmed and lower-cased) because operators type these by hand and a
 * "World" vs "world" typo must not silently un-exclude a world. Capture is never affected —
 * the snapshot always records which world the player was in; the exclusion only decides
 * what "restore into that world" means on the target node.</p>
 *
 * <p>Instances are immutable and safe for concurrent reads; {@link #defaults()} is the
 * out-of-the-box configuration ({@code LAST_KNOWN_LOCATION}, nothing excluded,
 * {@code SERVER_SPAWN} fallback).</p>
 *
 * @param mode          how the position section restores; null maps to {@link LocationMode#LAST_KNOWN_LOCATION}
 * @param excludeWorlds world names (case-insensitive) the module must not restore into; null maps to empty
 * @param fallbackMode  what to do when the snapshot world is unusable; null maps to {@link FallbackMode#SERVER_SPAWN}
 */
public record LocationPolicy(
        @NotNull LocationMode mode,
        @NotNull @Unmodifiable List<String> excludeWorlds,
        @NotNull FallbackMode fallbackMode) {

    /** Default world names are conservative: letters, digits, '.', '_', '-' and spaces, 1-64 chars. */
    private static final java.util.regex.Pattern WORLD_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9 ._-]{1,64}$");

    public LocationPolicy {
        mode = mode == null ? LocationMode.LAST_KNOWN_LOCATION : mode;
        fallbackMode = fallbackMode == null ? FallbackMode.SERVER_SPAWN : fallbackMode;
        if (excludeWorlds == null) {
            excludeWorlds = List.of();
        } else {
            var normalized = new LinkedHashSet<String>();
            for (String entry : excludeWorlds) {
                String cleaned = normalize(entry);
                if (cleaned != null) {
                    normalized.add(cleaned);
                }
            }
            excludeWorlds = List.copyOf(normalized);
        }
    }

    /** The out-of-the-box policy. Never null. */
    public static @NotNull LocationPolicy defaults() {
        return new LocationPolicy(LocationMode.LAST_KNOWN_LOCATION, List.of(), FallbackMode.SERVER_SPAWN);
    }

    /** True when {@code worldName} is excluded (case-insensitive, trimmed). Null/blank never matches. */
    public boolean isExcluded(String worldName) {
        String needle = normalize(worldName);
        return needle != null && excludeWorlds.contains(needle);
    }

    /**
     * Validates this policy, appending one English problem line per issue
     * (same convention as the platform's spawn-policy config).
     *
     * @param problems the collector for problem lines; never null, never thrown from
     */
    public void validate(@NotNull List<String> problems) {
        if (mode == null) {
            problems.add("location.mode: must be one of LAST_KNOWN_LOCATION, SERVER_SPAWN, RESPAWN_ONLY");
        }
        if (fallbackMode == null) {
            problems.add("location.fallback.mode: must be SERVER_SPAWN");
        }
        for (String entry : excludeWorlds) {
            if (!WORLD_NAME.matcher(entry).matches()) {
                problems.add("location.exclude-worlds: '" + entry + "' is not a valid world name "
                        + "(letters, digits, '.', '_', '-', spaces, 1-64 chars)");
            }
        }
    }

    /** Trim + lower-case; blanks and names with control characters become null (= drop the entry). */
    private static @Nullable String normalize(@Nullable String entry) {
        if (entry == null) {
            return null;
        }
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) < 0x21) {
                // control chars: would never match a real world name anyway
                return null;
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
