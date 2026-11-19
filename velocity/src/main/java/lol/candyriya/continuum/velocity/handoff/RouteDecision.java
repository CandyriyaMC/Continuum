package lol.candyriya.continuum.velocity.handoff;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Picks the server a transfer lands on.
 *
 * <p>Rules, in order:</p>
 * <ol>
 *     <li>A named candidate is used as-is when it is a configured server and not
 *         the backend the player already sits on. Unknown names are refused, never
 *         silently remapped.</li>
 *     <li>Without a name, the lexicographically first configured server other than
 *         the player's current backend wins. The public proxy API exposes no
 *         {@code try} order from velocity.toml, so the fallback is deterministic
 *         rather than clever.</li>
 *     <li>Nothing usable and the decision is {@code TARGET_UNAVAILABLE} - the
 *         caller fails closed instead of guessing a server.</li>
 * </ol>
 *
 * <p>Readiness is not live-pinged here: route selection runs on the event thread
 * and a synchronous ping would stall the switch. "Known" means configured in
 * velocity.toml; a dead server surfaces as a failed connect, which the transfer
 * state machine handles.</p>
 */
public final class RouteDecision {

    public enum Outcome {
        /** The candidate is usable; {@code targetServer} names it. */
        ALLOWED,
        /** The candidate is refused by policy; {@code reason} explains. */
        DENIED,
        /** No usable target exists; {@code reason} explains. */
        TARGET_UNAVAILABLE
    }

    /**
     * @param allowed      whether {@code targetServer} may be used
     * @param targetServer the chosen server, or {@code null} when not allowed
     * @param reason       English explanation for operators; empty when allowed
     * @param outcome      the decision class, kept even for the refuse paths
     */
    public record Decision(boolean allowed, String targetServer, String reason, Outcome outcome) {

        static Decision allowed(String target) {
            return new Decision(true, target, "", Outcome.ALLOWED);
        }

        static Decision denied(String reason) {
            return new Decision(false, null, reason, Outcome.DENIED);
        }

        static Decision unavailable(String reason) {
            return new Decision(false, null, reason, Outcome.TARGET_UNAVAILABLE);
        }
    }

    private final Supplier<? extends Collection<RegisteredServer>> servers;

    public RouteDecision(Supplier<? extends Collection<RegisteredServer>> servers) {
        if (servers == null) {
            throw new NullPointerException("servers");
        }
        this.servers = servers;
    }

    /**
     * Decides where the player goes.
     *
     * @param requested      the desired server, or null/blank for "any configured server"
     * @param currentBackend the backend the player is on right now, or null when unknown
     */
    public Decision decide(String requested, String currentBackend) {
        List<RegisteredServer> known;
        try {
            Collection<RegisteredServer> raw = servers.get();
            known = raw == null ? List.of() : List.copyOf(raw);
        } catch (RuntimeException e) {
            return Decision.unavailable("failed to enumerate configured servers: " + e.getMessage());
        }
        if (known.isEmpty()) {
            return Decision.unavailable("no servers are configured");
        }
        if (requested != null) {
            String name = requested.trim();
            if (!name.isEmpty()) {
                Optional<RegisteredServer> match = known.stream()
                        .filter(server -> server.getServerInfo().getName().equals(name))
                        .findFirst();
                if (match.isEmpty()) {
                    return Decision.denied("unknown server '" + name + "'");
                }
                if (currentBackend != null && name.equals(currentBackend)) {
                    return Decision.denied("already connected to '" + name + "'");
                }
                return Decision.allowed(name);
            }
        }
        return known.stream()
                .map(server -> server.getServerInfo().getName())
                .filter(name -> !name.equals(currentBackend))
                .sorted()
                .findFirst()
                .map(Decision::allowed)
                .orElse(Decision.unavailable(currentBackend != null
                        ? "no configured server other than '" + currentBackend + "'"
                        : "no configured server"));
    }
}
