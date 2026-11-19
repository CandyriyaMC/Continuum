package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * Routing outcome the proxy decided for a handoff request. Carried on the
 * wire as the declared name; never reorder or rename constants, only append.
 */
public enum RouteOutcome {

    /** Target accepted the player; {@code targetServer} on the result names it. */
    ALLOWED,

    /** Route denied by policy (allow-list, cooldown, permission). {@code reason} explains. */
    DENIED,

    /** The requested target exists but cannot currently take players. */
    TARGET_UNAVAILABLE,

    /** Routing blew up inside the proxy. Not the caller's fault, retryable. */
    INTERNAL_ERROR
}
