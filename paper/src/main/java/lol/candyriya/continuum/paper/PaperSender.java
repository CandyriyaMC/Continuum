package lol.candyriya.continuum.paper;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import lol.candyriya.continuum.common.command.ContinuumSender;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Adapter from the native Paper sender to the shared {@link ContinuumSender}.
 *
 * <p>Cloud's modern manager hands out {@link CommandSourceStack}, the real sender
 * sits in {@code getSender()}. Paper's CommandSender is both an Audience
 * (speaks Components) and a permission holder — a three-line adapter.</p>
 */
final class PaperSender implements ContinuumSender {

    private final CommandSender sender;

    PaperSender(@NotNull CommandSourceStack stack) {
        this.sender = Objects.requireNonNull(stack, "stack").getSender();
    }

    @Override
    public @NotNull String name() {
        return sender.getName();
    }

    @Override
    public boolean hasPermission(@NotNull String node) {
        return sender.hasPermission(node);
    }

    @Override
    public void sendMessage(@NotNull Component message) {
        sender.sendMessage(message);
    }
}
