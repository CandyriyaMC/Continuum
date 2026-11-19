package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * Explicit registry of every kind of frame that may travel on the
 * {@code continuum:main} channel.
 *
 * <p>The kind byte is part of the wire contract: never renumber an existing
 * constant, never reuse one. Add a new kind by appending a new constant with
 * a fresh byte id and its dotted-lowercase wire id, bump nothing else, and
 * teach both sides in the same release.</p>
 *
 * <p>Wire ids follow the {@code continuum.<area>.<verb>} convention used for
 * the durable event ids; the kind byte exists because plugin-message frames
 * carry a 1-byte kind tag rather than a string, not because the string is a
 * different namespace.</p>
 */
public enum PluginMessageKind {

    /** Backend asks the proxy to move a player to another backend. Reply: {@link #ROUTE_RESULT}. */
    HANDOFF_REQUEST(1, "continuum.handoff.request", PluginMessageDirection.BACKEND_TO_PROXY, HandoffRequestMessage.class),

    /** Target backend reports whether it can accept the incoming session. Reply: {@link #SESSION_TOKEN}. */
    HANDOFF_READY(2, "continuum.handoff.ready", PluginMessageDirection.BACKEND_TO_PROXY, HandoffReadyMessage.class),

    /** Target backend reports the player's session was restored and resumed. Terminal report, no reply. */
    HANDOFF_RESUMED(3, "continuum.handoff.resumed", PluginMessageDirection.BACKEND_TO_PROXY, HandoffResumedMessage.class),

    /** Backend reports the effective locale for a player's session. Report, no reply. */
    LOCALE_SET(4, "continuum.locale.set", PluginMessageDirection.BACKEND_TO_PROXY, LocaleSetMessage.class),

    /** Backend probes the proxy's liveness/latency. Reply: {@link #PONG}. */
    PING(5, "continuum.ping", PluginMessageDirection.BACKEND_TO_PROXY, PingMessage.class),

    /** Backend reports its load so the proxy can make routing decisions. Report, no reply. */
    BACKEND_STATUS(6, "continuum.backend.status", PluginMessageDirection.BACKEND_TO_PROXY, BackendStatusMessage.class),

    /** Proxy's routing decision for a {@link #HANDOFF_REQUEST}. Carries the request's correlation id. */
    ROUTE_RESULT(7, "continuum.route.result", PluginMessageDirection.PROXY_TO_BACKEND, RouteResultMessage.class),

    /** Proxy delivers the session token for a player whose {@link #HANDOFF_READY} was accepted. Carries that report's correlation id. */
    SESSION_TOKEN(8, "continuum.session.token", PluginMessageDirection.PROXY_TO_BACKEND, SessionTokenMessage.class),

    /** Proxy's answer to a {@link #PING}. Carries the ping's correlation id. */
    PONG(9, "continuum.pong", PluginMessageDirection.PROXY_TO_BACKEND, PongMessage.class),

    /** Proxy tells a backend the player is about to move: capture now. Sent before a switch is allowed. Reply: {@link #CAPTURE_RESULT}. */
    CAPTURE_REQUEST(10, "continuum.capture.request", PluginMessageDirection.PROXY_TO_BACKEND, CaptureRequestMessage.class),

    /** Backend's answer to a {@link #CAPTURE_REQUEST}: whether the snapshot reached the database. Carries the request's correlation id. */
    CAPTURE_RESULT(11, "continuum.capture.result", PluginMessageDirection.BACKEND_TO_PROXY, CaptureResultMessage.class);

    private final byte id;
    private final String wireId;
    private final PluginMessageDirection direction;
    private final Class<? extends PluginMessage> type;

    PluginMessageKind(int id, String wireId, PluginMessageDirection direction, Class<? extends PluginMessage> type) {
        if (id < 1 || id > 255) {
            throw new IllegalStateException("kind id must fit in an unsigned byte: " + id);
        }
        if (!PluginMessageConstraints.WIRE_ID_PATTERN.matcher(wireId).matches()) {
            throw new IllegalStateException("wire id must be dotted lowercase: " + wireId);
        }
        this.id = (byte) id;
        this.wireId = wireId;
        this.direction = direction;
        this.type = type;
    }

    /** Unsigned byte used on the wire. */
    public int id() {
        return id & 0xff;
    }

    /** Dotted lowercase wire id, e.g. {@code continuum.handoff.request}. */
    public String wireId() {
        return wireId;
    }

    /** Which side of the connection may originate this kind. The direction is not carried on the wire. */
    public PluginMessageDirection direction() {
        return direction;
    }

    /** The record type bound to this kind, binding both directions of the codec. */
    public Class<? extends PluginMessage> type() {
        return type;
    }

    /**
     * Resolves a kind by its wire byte.
     *
     * @throws UnknownMessageKindException when nothing is registered for {@code kind}
     */
    public static PluginMessageKind fromId(int kind) {
        for (PluginMessageKind value : values()) {
            if ((value.id & 0xff) == (kind & 0xff)) {
                return value;
            }
        }
        throw new UnknownMessageKindException(kind);
    }

    /**
     * Resolves a kind by the exact record class bound to it.
     *
     * @throws IllegalArgumentException when no kind uses {@code type}
     */
    public static PluginMessageKind fromType(Class<? extends PluginMessage> type) {
        if (type != null) {
            for (PluginMessageKind value : values()) {
                if (value.type == type) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException("no message kind registered for type " + type);
    }

    /**
     * Resolves a kind by its dotted wire id.
     *
     * @throws IllegalArgumentException when no kind uses {@code wireId}
     */
    public static PluginMessageKind fromWireId(String wireId) {
        if (wireId != null) {
            for (PluginMessageKind value : values()) {
                if (value.wireId.equals(wireId)) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException("no message kind registered for wire id '" + wireId + "'");
    }
}
