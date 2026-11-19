package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * Who is allowed to speak for a frame. The direction is not carried on the
 * wire — it is a property of the {@link PluginMessageKind}, and the sealed
 * hierarchies under {@link PluginMessage} make crossing them a compile-time
 * concern on the sending side and an explicit check on the receiving side.
 */
public enum PluginMessageDirection {

    /** Backend servers talk to the proxy: requests, readiness, locale, ping. */
    BACKEND_TO_PROXY,

    /**
     * The proxy talks to backends. Only ever as a reply to an in-flight
     * request — never spontaneously; see {@code ProxyToBackendMessage} and the
     * package documentation.
     */
    PROXY_TO_BACKEND;

    /** The other direction. */
    public PluginMessageDirection opposite() {
        return this == BACKEND_TO_PROXY ? PROXY_TO_BACKEND : BACKEND_TO_PROXY;
    }
}
