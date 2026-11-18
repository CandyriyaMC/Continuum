package lol.candyriya.continuum.common.command;

import lol.candyriya.continuum.common.ContinuumCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.incendo.cloud.CommandManager;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Function;

/**
 * All plugin commands in one place, once, for both platforms.
 *
 * <p>The generic {@code C} is the native sender type of the Cloud manager
 * (CommandSourceStack on paper, CommandSource on velocity). The platform passes a
 * {@code Function<C, ContinuumSender>} adapter and the rest is shared.</p>
 *
 * <p>Permissions are enforced by Cloud itself ({@code .permission(...)}), no need to
 * duplicate checks inside handlers. Config reload is a privileged operation and sits
 * on its own node.</p>
 */
public final class ContinuumCommands {

    private ContinuumCommands() {
    }

    public static <C> void register(
            @NotNull CommandManager<C> manager,
            @NotNull Function<C, ContinuumSender> senders,
            @NotNull ContinuumCore core
    ) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(senders, "senders");
        Objects.requireNonNull(core, "core");

        var root = manager.commandBuilder("continuum");

        // /continuum ping — smoke check that commands reach the platform at all
        manager.command(root.literal("ping")
                .permission("continuum.command.ping")
                .handler(ctx -> {
                    var sender = senders.apply(ctx.sender());
                    var pong = core.messages().parse(
                            "<green>pong!</green> <gray>Welcome, <name> :)</gray>",
                            Placeholder.unparsed("name", sender.name()));
                    sender.sendMessage(core.messages().prefixed(core.config().prefix(), pong));
                }));

        // /continuum version — version from paper-plugin.yml / the velocity descriptor, no hardcoding
        manager.command(root.literal("version")
                .permission("continuum.command.version")
                .handler(ctx -> {
                    var sender = senders.apply(ctx.sender());
                    var text = core.messages().parse(
                            "<gray>Version: <white><version></white></gray>",
                            Placeholder.unparsed("version", core.version()));
                    sender.sendMessage(core.messages().prefixed(core.config().prefix(), text));
                }));

        // /continuum reload — re-reads config.yml, its own stricter node
        manager.command(root.literal("reload")
                .permission("continuum.command.reload")
                .handler(ctx -> {
                    var sender = senders.apply(ctx.sender());
                    boolean ok = core.reload();
                    Component text = ok
                            ? core.messages().parse("<green>Config reloaded.</green>")
                            : core.messages().parse("<red>Config did not reload, check the console. Previous values are kept.</red>");
                    sender.sendMessage(core.messages().prefixed(core.config().prefix(), text));
                }));
    }
}
