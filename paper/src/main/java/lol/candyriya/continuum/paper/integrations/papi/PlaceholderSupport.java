package lol.candyriya.continuum.paper.integrations.papi;

import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.config.KnownModules;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runtime detection and registration of the internal PlaceholderAPI expansion.
 *
 * <p>No softdepend is declared on purpose: Continuum boots identically with
 * or without PlaceholderAPI installed. Probe order is (1) Bukkit's plugin
 * manager says the plugin is enabled, (2) the expansion base class actually
 * loads, (3) registration is attempted and may still refuse. Every step
 * failing means "inactive", logged, never a stack trace into the operator's
 * face, and never a broken enable.</p>
 *
 * <p>Nothing is touched at class-load time: the PlaceholderAPI types are only
 * referenced inside {@link #start}, after both probes pass, so a server
 * without PlaceholderAPI never loads them at all.</p>
 */
public final class PlaceholderSupport implements AutoCloseable {

    private final Logger logger;
    private final ContinuumExpansion expansion;

    private PlaceholderSupport(Logger logger, ContinuumExpansion expansion) {
        this.logger = logger;
        this.expansion = expansion;
    }

    /**
     * Starts the integration if PlaceholderAPI is present and enabled.
     *
     * @return a handle; closing it unregisters the expansion. Never throws.
     */
    public static PlaceholderSupport start(JavaPlugin plugin, ContinuumConfig config, Logger logger) {
        if (!placeholderApiAvailable(logger)) {
            logger.info("PlaceholderAPI integration inactive (PlaceholderAPI is not installed or not enabled)");
            return new PlaceholderSupport(logger, null);
        }
        ContinuumExpansion expansion;
        try {
            String version = versionOf(plugin);
            expansion = new ContinuumExpansion(version, answersFor(config, version));
        } catch (Throwable e) {
            // a broken integration must not break the backend
            logger.warn("PlaceholderAPI integration inactive (expansion could not be built)", e);
            return new PlaceholderSupport(logger, null);
        }
        boolean registered;
        try {
            registered = expansion.register();
        } catch (Throwable e) {
            logger.warn("PlaceholderAPI integration inactive (registration failed)", e);
            return new PlaceholderSupport(logger, null);
        }
        if (!registered) {
            logger.warn("PlaceholderAPI integration inactive (registration refused)");
            return new PlaceholderSupport(logger, null);
        }
        logger.info("PlaceholderAPI integration enabled (%continuum_version%, %continuum_modules_enabled%, "
                + "%continuum_role%, %continuum_restore_order%)");
        return new PlaceholderSupport(logger, expansion);
    }

    private static boolean placeholderApiAvailable(Logger logger) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                return false;
            }
        } catch (Throwable e) {
            logger.warn("PlaceholderAPI probe failed; treating the integration as inactive", e);
            return false;
        }
        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion");
            return true;
        } catch (Throwable e) {
            // enabled but not visible to this classloader — e.g. an ancient PAPI build
            logger.warn("PlaceholderAPI is enabled but its API classes are not visible; treating the integration as inactive", e);
            return false;
        }
    }

    private static String versionOf(JavaPlugin plugin) {
        String version = plugin.getDescription().getVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    private static PlaceholderAnswers answersFor(ContinuumConfig config, String version) {
        var effective = config.sync().modulesWithDefaults();
        List<String> enabled = new ArrayList<>();
        for (String id : KnownModules.ALL) {
            if (effective.getOrDefault(id, true)) {
                enabled.add(id);
            }
        }
        String role = config.network().role().name().toLowerCase(Locale.ROOT);
        String restoreOrder = config.sync().recovery().restoreOrder().isEmpty() ? "default" : "custom";
        return new PlaceholderAnswers(version, role, enabled, restoreOrder);
    }

    /** True when the expansion registered. */
    public boolean isActive() {
        return expansion != null;
    }

    @Override
    public void close() {
        if (expansion == null) {
            return;
        }
        try {
            expansion.unregister();
        } catch (Throwable e) {
            // unregistering must not break plugin disable
            logger.warn("PlaceholderAPI expansion failed to unregister; PlaceholderAPI will forget it on restart", e);
        }
    }
}
