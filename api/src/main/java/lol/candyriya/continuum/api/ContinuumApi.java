package lol.candyriya.continuum.api;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Public API for third-party plugins.
 *
 * <p>The implementation lives in {@code common} (the core class); platform modules
 * register it on enable and unregister it on shutdown via {@link Continuums}.
 * Deliberately no Paper/Velocity API types here: this module can be dropped in
 * as compileOnly SPI anywhere.</p>
 */
public interface ContinuumApi {

    /**
     * The plugin version, same one as in {@code paper-plugin.yml} / the Velocity plugin descriptor.
     */
    @NotNull String version();

    /**
     * The welcome message for a player from the active config.
     * Empty when welcomes are disabled in the config.
     */
    @NotNull Optional<Component> welcomeMessage(@NotNull String playerName);

    /**
     * Re-reads {@code config.yml} from disk.
     *
     * @return true if the config reloaded without errors
     */
    boolean reload();
}
