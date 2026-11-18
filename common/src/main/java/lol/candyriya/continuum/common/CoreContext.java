package lol.candyriya.continuum.common;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Everything the platform module hands over to the core on startup.
 *
 * <p>No Paper/Velocity types here: paper passes its own dataDirectory and logger,
 * velocity passes theirs. The core only works with this context from then on.</p>
 *
 * @param dataDirectory plugin folder on disk; the core creates it if missing
 * @param logger        platform logger; everything the core logs goes through it
 * @param version       plugin version, from PluginMeta on paper and from BuildConstants on velocity
 */
public record CoreContext(
        @NotNull Path dataDirectory,
        @NotNull Logger logger,
        @NotNull String version
) {
    public CoreContext {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(version, "version");
    }
}
