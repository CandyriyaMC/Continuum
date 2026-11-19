package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * Messages the proxy may send to backends.
 *
 * <p><b>These are mostly sent as replies.</b> {@link RouteResultMessage},
 * {@link SessionTokenMessage} and {@link PongMessage} only ever answer a
 * backend-originated frame, and platform code is expected to build them at
 * the reply site so the pairing is structural, not remembered. The one
 * exception is {@link CaptureRequestMessage}: the proxy fires it unprompted
 * from the connection pipeline when a player is about to move servers, and
 * the backend answers with a {@link CaptureResultMessage} carrying the same
 * correlation id. Durable semantics still live on the storage/outbox side;
 * this pipe is for presence and hints.</p>
 *
 * <p>On the backend this hierarchy is received over a connection the proxy is
 * assumed to control; a player can still spray bytes on any registered
 * channel, so a backend that cannot assume a modern-forwarding proxy must not
 * act on these frames either — match them to a pending entry, or drop them.</p>
 */
public sealed interface ProxyToBackendMessage extends PluginMessage
        permits RouteResultMessage, SessionTokenMessage, PongMessage, CaptureRequestMessage {
}
