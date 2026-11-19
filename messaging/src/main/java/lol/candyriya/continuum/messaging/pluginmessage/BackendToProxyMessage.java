package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * Messages a backend server may send to the proxy. Every one of these is a
 * request or a report; none of them is authoritative on its own — the proxy
 * decides what to do and, for the request kinds, answers with a
 * {@link ProxyToBackendMessage} reply carrying the same correlation id.
 *
 * <p>On the proxy this hierarchy must only be decoded after the source has
 * been proven to be a backend connection (see {@link PluginMessageChannel}
 * for the exact steps). A player-sourced frame is hostile input and must be
 * rejected before it reaches this type.</p>
 */
public sealed interface BackendToProxyMessage extends PluginMessage
        permits HandoffRequestMessage, HandoffReadyMessage, HandoffResumedMessage,
                LocaleSetMessage, PingMessage, BackendStatusMessage, CaptureResultMessage {
}
