package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * Root of every typed message on the {@code continuum:main} channel.
 *
 * <p>Sealed, and split by direction rather than lumped together: a backend
 * can only ever emit {@link BackendToProxyMessage}s and the proxy only ever
 * emits {@link ProxyToBackendMessage}s. Crossing the hierarchies is a
 * compile-time error on the sending side and an explicit direction check on
 * the receiving side — never an implicit convention.</p>
 *
 * <p>There is deliberately no {@code toJson}/{@code fromJson} here; binding to
 * the wire goes through {@link PluginMessageCodec}, which owns framing,
 * size caps and validation.</p>
 */
public sealed interface PluginMessage permits BackendToProxyMessage, ProxyToBackendMessage {
}
