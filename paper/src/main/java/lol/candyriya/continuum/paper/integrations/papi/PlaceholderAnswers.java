package lol.candyriya.continuum.paper.integrations.papi;

import java.util.List;
import java.util.Locale;

/**
 * The answer table behind the internal PlaceholderAPI expansion.
 *
 * <p>Pure functions of immutable inputs: no Bukkit, no PlaceholderAPI types, no
 * network, no player context — every answer is identical for every player on
 * the server. Unknown keys return {@code null}, which PlaceholderAPI renders
 * as "nothing here", instead of a fabricated value.</p>
 *
 * <p>Documented keys (the value after {@code %continuum_} and before the
 * closing {@code %}):</p>
 * <ul>
 *   <li>{@code version} — the plugin version string from the descriptor</li>
 *   <li>{@code modules_enabled} — comma-separated ids of the modules whose
 *       effective toggle is on, in restore order; empty string when none</li>
 *   <li>{@code role} — the node's {@code network.role}, lower-cased
 *       ({@code backend} | {@code coordinator})</li>
 *   <li>{@code restore_order} — {@code custom} when the operator pinned a
 *       {@code sync.recovery.restore-order}, else {@code default}</li>
 * </ul>
 *
 * <p>Everything else returns {@code null}. There is deliberately no
 * {@code restore_status}: the restore pipeline is not wired to a registry
 * yet, and making one up would put a lie in a scoreboard.</p>
 */
public final class PlaceholderAnswers {

    private final String version;
    private final String role;
    private final List<String> modulesEnabled;
    private final String restoreOrder;

    public PlaceholderAnswers(String version, String role, List<String> modulesEnabled, String restoreOrder) {
        this.version = version;
        this.role = role;
        this.modulesEnabled = List.copyOf(modulesEnabled);
        this.restoreOrder = restoreOrder;
    }

    /** Answers {@code placeholder}, or {@code null} when the key is unknown. */
    public String answer(String placeholder) {
        if (placeholder == null) {
            return null;
        }
        return switch (placeholder.trim().toLowerCase(Locale.ROOT)) {
            case "version" -> version;
            case "modules_enabled" -> String.join(",", modulesEnabled);
            case "role" -> role;
            case "restore_order" -> restoreOrder;
            default -> null;
        };
    }
}
