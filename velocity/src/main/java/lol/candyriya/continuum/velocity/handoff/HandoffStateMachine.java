package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.common.session.TransferState;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Legal edges of the transfer lifecycle, as observed by the proxy.
 *
 * <p>The full enum carries the backend-stranded states ({@link TransferState#CAPTURING},
 * {@link TransferState#SNAPSHOT_SAVED}) — the snapshot pipeline owns those, and the
 * proxy only hears about them through the request frame. Everything else moves here:</p>
 * <ul>
 *     <li>{@code CREATED} - pending record minted for a switch the proxy is driving.</li>
 *     <li>{@code HANDOFF_REQUESTED} - a backend asked the proxy to move the player.</li>
 *     <li>{@code CONNECTING} - connection attempt in flight (proxy-initiated).</li>
 *     <li>{@code RESTORING} - player landed on the target; the restore gate holds them.</li>
 *     <li>{@code ACTIVE} - target backend confirmed the session resumed.</li>
 *     <li>{@code COMMITTED} / {@code FAILED} / {@code RECOVERY_REQUIRED} - terminal.</li>
 * </ul>
 *
 * <p>Transitions are validated on every use; an illegal edge throws
 * {@link IllegalHandoffTransitionException} instead of silently corrupting the
 * bookkeeping.</p>
 */
public final class HandoffStateMachine {

    private static final Set<TransferState> TERMINAL =
            EnumSet.of(TransferState.COMMITTED, TransferState.FAILED, TransferState.RECOVERY_REQUIRED);

    private static final Map<TransferState, Set<TransferState>> EDGES = Map.of(
            TransferState.CREATED, EnumSet.of(TransferState.HANDOFF_REQUESTED, TransferState.CONNECTING, TransferState.FAILED),
            TransferState.HANDOFF_REQUESTED, EnumSet.of(TransferState.CONNECTING, TransferState.FAILED),
            TransferState.CONNECTING, EnumSet.of(TransferState.RESTORING, TransferState.FAILED),
            TransferState.RESTORING, EnumSet.of(TransferState.ACTIVE, TransferState.FAILED, TransferState.RECOVERY_REQUIRED),
            TransferState.ACTIVE, EnumSet.of(TransferState.COMMITTED),
            TransferState.COMMITTED, EnumSet.noneOf(TransferState.class),
            TransferState.FAILED, EnumSet.noneOf(TransferState.class),
            TransferState.RECOVERY_REQUIRED, EnumSet.noneOf(TransferState.class));

    private HandoffStateMachine() {
    }

    /** True when {@code from} may move to {@code to}. */
    public static boolean can(TransferState from, TransferState to) {
        if (from == null || to == null) {
            return false;
        }
        return EDGES.getOrDefault(from, Set.of()).contains(to);
    }

    /** True when {@code state} admits no further transitions. */
    public static boolean isTerminal(TransferState state) {
        return state != null && TERMINAL.contains(state);
    }

    /**
     * Requires the transition.
     *
     * @throws IllegalHandoffTransitionException when the edge is not legal
     */
    public static TransferState require(TransferState from, TransferState to) {
        if (!can(from, to)) {
            throw new IllegalHandoffTransitionException(from, to);
        }
        return to;
    }
}
