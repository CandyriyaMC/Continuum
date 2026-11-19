package lol.candyriya.continuum.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;

import lol.candyriya.continuum.velocity.VelocityText;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal {@link CommandSource} fake: permission bits in, components out.
 * Not a {@code Player}, so locale resolution lands on the proxy default,
 * exactly like console/RCON sources in production.
 */
final class FakeCommandSource implements CommandSource {

    private final Map<String, Tristate> permissions;
    private final List<Component> sent = new ArrayList<>();

    FakeCommandSource(Map<String, Tristate> permissions) {
        this.permissions = permissions;
    }

    static FakeCommandSource admin() {
        return new FakeCommandSource(Map.of(
                "continuum.admin", Tristate.TRUE,
                "continuum.readonly", Tristate.TRUE,
                "continuum.license", Tristate.TRUE,
                "continuum.update", Tristate.TRUE));
    }

    static FakeCommandSource adminOnly() {
        return new FakeCommandSource(Map.of("continuum.admin", Tristate.TRUE));
    }

    static FakeCommandSource readonlyOnly() {
        return new FakeCommandSource(Map.of("continuum.readonly", Tristate.TRUE));
    }

    static FakeCommandSource noPermissions() {
        return new FakeCommandSource(Map.of());
    }

    @Override
    public Tristate getPermissionValue(String permission) {
        return permissions.getOrDefault(permission, Tristate.FALSE);
    }

    @Override
    public void sendMessage(Component component) {
        sent.add(component);
    }

    /** Every component sent so far, flattened to plain text. */
    String allText() {
        return sent.stream().map(VelocityText::plain).collect(Collectors.joining("\n"));
    }

    List<Component> sent() {
        return List.copyOf(sent);
    }

    boolean isEmpty() {
        return sent.isEmpty();
    }
}
