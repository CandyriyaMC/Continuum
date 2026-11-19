package lol.candyriya.continuum.paper.integrations.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * The internal PlaceholderAPI expansion for Continuum.
 *
 * <p>Internal means exactly what it says: {@link #persist()} is true, the
 * expansion registers at boot while this plugin is installed, and it is never
 * published to the eCloud store — it exists so that server configs, scoreboard
 * plugins and tab-list formatters can show honest Continuum state.</p>
 *
 * <p>Every answer is derived from the shared configuration or the plugin
 * descriptor; no answer depends on who is asking, so no answer can leak
 * anything about any player. Unknown keys return {@code null}.</p>
 */
public final class ContinuumExpansion extends PlaceholderExpansion {

    private final String version;
    private final PlaceholderAnswers answers;

    public ContinuumExpansion(String version, PlaceholderAnswers answers) {
        this.version = version;
        this.answers = answers;
    }

    @Override
    public String getIdentifier() {
        return "continuum";
    }

    @Override
    public String getAuthor() {
        return "CandyriyaMC";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getName() {
        return "Continuum";
    }

    @Override
    public String getDescription() {
        return "Continuum — distributed player-state synchronization for Minecraft networks.";
    }

    /** Internal expansion: it does not need, and never required, any other plugin from the store. */
    @Override
    public String getRequiredPlugin() {
        return null;
    }

    @Override
    public List<String> getPlaceholders() {
        return List.of("version", "modules_enabled", "role", "restore_order");
    }

    /** Internal expansion: PlaceholderAPI keeps and re-registers it across reloads. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        // deliberately ignores the player: the answers are the same for everyone
        return answers.answer(params);
    }
}
