package lol.candyriya.continuum.velocity.session;

import lol.candyriya.continuum.velocity.VelocityText;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

/**
 * Default kick reconciliation policy: keep the backend waiting, log loudly.
 *
 * <p>Until the transfer orchestrator lands there is no safe automatic action to
 * take - the backend may still hold authoritative state for the player, and
 * re-seeding it blindly is how state gets clobbered. So the proxy marks the
 * connection as awaiting a backend outcome (visible in {@code /continuum
 * status}), debounces duplicate loss signals that arrive inside the reconnect
 * window (flip-flops are one episode, not a flood), and logs the kick at
 * warning level with everything an operator needs to judge it.</p>
 *
 * <p>The full policy - redirect, release, or guaranteed notify - is bound when
 * the handoff orchestration ships; it replaces this class, not the interface.</p>
 */
public final class NotifyAndKeepWaitPolicy implements KickDecisionPolicy {

    private final NetworkSessionRegistry registry;
    private final Logger logger;

    public NotifyAndKeepWaitPolicy(NetworkSessionRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    @Override
    public void reconcile(KickContext context) {
        Component reason = context.kickReason();
        String reasonText = reason == null ? "<no reason given>" : VelocityText.plain(reason);
        var debounced = registry.isReconnectDebounced(context.playerId());

        // the backend is told (in effect): "hold the session, the proxy has it" -
        // the actual plugin-message notify lands with the envelope codec
        if (debounced) {
            logger.debug("debounced duplicate loss signal for player {} kicked from '{}' ({})",
                    context.playerId(), context.backendName(), reasonText);
        } else {
            logger.warn("player {} kicked from backend '{}': {}; backend should keep waiting for the session handoff instead of re-seeding state",
                    context.playerId(), context.backendName(), reasonText);
        }
        registry.markAwaitingBackend(context.playerId(), context.backendName(), reasonText);
    }
}
