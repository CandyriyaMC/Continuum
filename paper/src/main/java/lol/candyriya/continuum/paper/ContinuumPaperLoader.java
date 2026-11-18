package lol.candyriya.continuum.paper;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;

/**
 * Paper plugin loader. Adds libraries to the plugin classpath before the plugin starts,
 * on top of what is already shaded into the jar.
 *
 * <p>Intentionally empty for now: every dependency is shaded with relocation
 * (see shadowJar in paper/build.gradle.kts). A loader becomes relevant if something heavy
 * and rare ends up not shaded — then inject it here, e.g. via
 * {@code builder.addLibrary(new MavenLibraryResolver()...)}. Until then, hands off.</p>
 */
public class ContinuumPaperLoader implements PluginLoader {

    @Override
    public void classloader(final PluginClasspathBuilder builder) {
        // Everything is inside the jar already, no extra libraries needed.
    }
}
