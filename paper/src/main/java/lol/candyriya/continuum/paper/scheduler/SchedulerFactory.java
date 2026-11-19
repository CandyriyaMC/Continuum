package lol.candyriya.continuum.paper.scheduler;

import net.kyori.adventure.key.Key;
import org.bukkit.plugin.Plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.papermc.paper.ServerBuildInfo;

/**
 * Picks the platform flavour at runtime.
 *
 * <p>Detection is by brand compatibility reported by the server itself
 * ({@code ServerBuildInfo#buildInfo().isBrandCompatible(Key.key("papermc", "folia"))}) —
 * never by class probing. A brand-compatible fork therefore gets the right flavour.</p>
 */
public final class SchedulerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerFactory.class);

    private SchedulerFactory() {
    }

    /** True when this server is Folia (or a Folia-brand-compatible fork). */
    public static boolean isFolia() {
        try {
            return ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
        } catch (Throwable t) {
            // no build info at all means this is not a Paper/Folia server; fail loud, not sideways
            throw new IllegalStateException("cannot determine the server brand via ServerBuildInfo — is this a Paper/Folia 26.3+ server?", t);
        }
    }

    /** Creates the scheduler flavour this server runs, and logs what was detected. */
    public static RegionizedScheduler detect(Plugin plugin) {
        ServerBuildInfo info = ServerBuildInfo.buildInfo();
        boolean folia = isFolia();
        LOGGER.info("detected server brand '{}' (Minecraft {}, build {}), running the {} scheduler",
                info.brandName(), info.minecraftVersionId(),
                info.buildNumber().isPresent() ? String.valueOf(info.buildNumber().getAsInt()) : "unknown",
                folia ? "Folia" : "Paper");
        return folia ? new FoliaScheduler(plugin) : new PaperScheduler(plugin);
    }
}
