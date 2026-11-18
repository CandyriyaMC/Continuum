package lol.candyriya.continuum.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import lol.candyriya.continuum.common.command.ContinuumSender;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Adapter from the native Velocity sender to the shared {@link ContinuumSender}.
 *
 * <p>Everything is trivial here: CommandSource out of the box is an Audience
 * (speaks Components directly) and a permission holder. The adapter is thin,
 * all the command logic lives in common.</p>
 */
final class VelocitySender implements ContinuumSender {

    private final CommandSource source;

    VelocitySender(@NotNull CommandSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public @NotNull String name() {
        // Pattern matching switch: console and other non-players fall into default.
        return switch (source) {
            case Player player -> player.getUsername();
            default -> "console";
        };
    }

    @Override
    public boolean hasPermission(@NotNull String node) {
        return source.hasPermission(node);
    }

    @Override
    public void sendMessage(@NotNull Component message) {
        source.sendMessage(message);
    }
}
