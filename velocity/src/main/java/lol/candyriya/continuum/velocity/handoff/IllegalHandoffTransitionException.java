package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.common.session.TransferState;

/** Thrown when a handoff tries to take an edge the transfer lifecycle forbids. */
public final class IllegalHandoffTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public IllegalHandoffTransitionException(TransferState from, TransferState to) {
        super("illegal handoff transition: " + from + " -> " + to);
    }
}
