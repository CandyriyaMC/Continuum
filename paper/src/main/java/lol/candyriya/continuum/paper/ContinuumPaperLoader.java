package lol.candyriya.continuum.paper;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;

/**
 * Plugin loader. Runs before the bootstrapper, before any plugin code is loaded.
 *
 * <p>Deliberately empty: every dependency the backend needs is shaded and
 * relocated inside this jar (see the shadow configuration), so the plugin
 * classpath needs no extra libraries and this stage has nothing to add. The
 * class exists because paper-plugin.yml declares it — and as the designated
 * place to plug a MavenLibraryResolver in if a heavy, rarely loaded dependency
 * ever ends up unshaded. Settings belong in bootstrap/enable, not here.</p>
 */
public final class ContinuumPaperLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        // Everything is inside the jar already, no extra libraries needed.
    }
}
