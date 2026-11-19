package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The frame decoded fine but travels the wrong direction for this endpoint.
 * E.g. a proxy-side handler fed a proxy→backend frame, or a backend fed a
 * backend→proxy frame — both are crossed-wire bugs or hostile input, never
 * something to "just handle".
 */
public final class DirectionMismatchException extends PluginMessageException {

    private final PluginMessageDirection expected;
    private final PluginMessageDirection actual;

    public DirectionMismatchException(PluginMessageDirection expected, PluginMessageDirection actual) {
        super(ErrorCode.PROTOCOL_VIOLATION, "expected a " + expected + " message, got a " + actual + " message");
        this.expected = expected;
        this.actual = actual;
    }

    /** Direction the caller asked for. */
    public PluginMessageDirection expected() {
        return expected;
    }

    /** Direction the frame actually encodes. */
    public PluginMessageDirection actual() {
        return actual;
    }
}
