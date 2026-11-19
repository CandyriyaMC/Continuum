package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * Messages the proxy may send to backends.
 *
 * <p><b>These are only ever sent as replies.</b> The proxy does not get to
 * shout at backends unprompted on this channel — every frame of this
 * hierarchy carries the correlation id of the in-flight
 * {@link BackendToProxyMessage} it answers, and platform code is expected to
 * build them at the reply site so the pairing is structural, not remembered.
 * Anything that is not a reply belongs on the durable transport (RabbitMQ),
 * not here.</p>
 *
 * <p>On the backend this hierarchy is received over a connection the proxy is
 * assumed to control; a player can still spray bytes on any registered
 * channel, so a backend that cannot assume a modern-forwarding proxy must not
 * act on these frames either — match them to a pending entry, or drop them.</p>
 */
public sealed interface ProxyToBackendMessage extends PluginMessage
        permits RouteResultMessage, SessionTokenMessage, PongMessage {
}
