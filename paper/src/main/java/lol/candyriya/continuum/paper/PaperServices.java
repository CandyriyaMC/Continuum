package lol.candyriya.continuum.paper;

import lol.candyriya.continuum.common.ContinuumCore;
import org.incendo.cloud.paper.PaperCommandManager;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Bridge between the bootstrapper and the plugin instance.
 *
 * <p>Paper creates the bootstrap and the plugin as two different objects with no shared
 * state, so the core and the command manager are handed over through this holder.
 * Written by the bootstrapper only, read by the plugin only — no races by lifecycle
 * construction.</p>
 */
final class PaperServices {

    private static volatile ContinuumCore core;
    private static volatile PaperCommandManager<?> commands;

    private PaperServices() {
    }

    static void core(@NotNull ContinuumCore value) {
        core = Objects.requireNonNull(value, "core");
    }

    static void commands(@NotNull PaperCommandManager<?> value) {
        commands = Objects.requireNonNull(value, "commands");
    }

    static @NotNull ContinuumCore core() {
        return Objects.requireNonNull(core, "Core not created yet: bootstrap has not run");
    }
}
