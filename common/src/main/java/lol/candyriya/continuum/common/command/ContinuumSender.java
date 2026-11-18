package lol.candyriya.continuum.common.command;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Platform-neutral sender abstraction, identical for paper and velocity.
 *
 * <p>Cloud wants its own native sender types (CommandSourceStack on paper,
 * CommandSource on velocity), which does not fit a shared command tree.
 * The platform passes a small adapter instead — see {@code PaperSender} / {@code VelocitySender}.</p>
 */
public interface ContinuumSender {

    /** Sender display name, used in command feedback. */
    @NotNull String name();

    /** Permission check. Handlers don't re-check — Cloud owns permission gates. */
    boolean hasPermission(@NotNull String node);

    /** Sends a component to the sender. */
    void sendMessage(@NotNull Component message);
}
