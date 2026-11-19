package lol.candyriya.continuum.velocity.update;

import lol.candyriya.continuum.common.update.UpdatePhase;
import lol.candyriya.continuum.common.update.UpdateSnapshot;
import lol.candyriya.continuum.velocity.VelocityMessages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * User-facing lines for {@code /continuum update}, kept out of the command
 * tree on purpose.
 *
 * <p>Plain, complete sentences - no markup gymnastics, no abbreviations the
 * person reading the console never agreed to. Version numbers are never
 * styled away; operators eyeball these lines when something is wrong.</p>
 */
public final class UpdateMessages {

    private UpdateMessages() {
    }

    /** One-line outcome of a check or an install attempt, prefixed with the proxy prefix. */
    public static Component outcome(VelocityMessages messages, UpdateSnapshot snapshot, boolean install) {
        String current = snapshot.currentBuild() == null ? "unknown" : snapshot.currentBuild();
        Component body;
        switch (snapshot.phase()) {
            case UP_TO_DATE -> body = Component.text(
                    "Continuum is up to date (" + current + ").", NamedTextColor.GRAY);
            case UPDATE_AVAILABLE -> {
                String available = snapshot.availableBuild() == null ? "a new build" : snapshot.availableBuild();
                String message = snapshot.message() == null ? "" : snapshot.message();
                if (message.startsWith("incompatible")) {
                    body = Component.text("Update " + available + " is not compatible with this server: ",
                            NamedTextColor.RED)
                            .append(Component.text(stripPrefix(message), NamedTextColor.GRAY))
                            .append(Component.text(" No update staged.", NamedTextColor.RED));
                } else if (install) {
                    body = Component.text("Update " + available + " could not be staged; check the console.",
                            NamedTextColor.RED);
                } else {
                    body = Component.text("Update available: " + current + " → " + available + ". ",
                            NamedTextColor.GOLD)
                            .append(Component.text("Run '/continuum update install' to stage it.",
                                    NamedTextColor.GRAY));
                }
            }
            case STAGED -> {
                String available = snapshot.availableBuild() == null ? "" : snapshot.availableBuild();
                body = Component.text("Update " + available + " staged. ", NamedTextColor.GOLD)
                        .append(Component.text("Restart the proxy to apply it.", NamedTextColor.GRAY));
            }
            case DISABLED -> body = Component.text(
                    "Update checks are disabled" + reason(snapshot) + ". The proxy runs normally.",
                    NamedTextColor.YELLOW);
            case CHECKING -> body = Component.text(
                    "An update check is already running; try again in a moment.", NamedTextColor.GRAY);
            case ERROR -> body = Component.text(
                    "The update check failed" + reason(snapshot) + "; Continuum keeps running.",
                    NamedTextColor.RED);
            case IDLE -> {
                String message = snapshot.message() == null ? "" : snapshot.message().trim();
                body = Component.text(message.isEmpty()
                        ? "No update information yet; the next check runs shortly."
                        : message + ".", NamedTextColor.GRAY);
            }
            default -> body = Component.text("Update subsystem state: " + snapshot.phase(), NamedTextColor.GRAY);
        }
        return messages.prefixed(body);
    }

    /** The line sent immediately when a check starts, before any network round trip. */
    public static Component started(VelocityMessages messages, boolean install) {
        return messages.prefixed(Component.text(
                install ? "Checking for an update and downloading it if one exists…" : "Checking for an update…",
                NamedTextColor.GRAY));
    }

    private static String reason(UpdateSnapshot snapshot) {
        String message = snapshot.message() == null ? "" : snapshot.message().trim();
        return message.isEmpty() ? "" : " (" + message + ")";
    }

    private static String stripPrefix(String message) {
        int colon = message.indexOf(':');
        return colon >= 0 ? message.substring(colon + 1).trim() : message;
    }
}
