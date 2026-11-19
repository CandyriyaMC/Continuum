package lol.candyriya.continuum.common.protocol;

/**
 * Well-known event type ids emitted by the core transfer flow. Modules
 * define their own ids in their own packages; these exist so every node
 * agrees on the core strings.
 */
public final class ProtocolEvents {

    /** Transfer flow started; payload {@code TransferRequestedPayload}. */
    public static final String TRANSFER_REQUESTED = "continuum.transfer.requested";

    /** Transfer committed end-to-end; payload {@code TransferCommittedPayload}. */
    public static final String TRANSFER_COMMITTED = "continuum.transfer.committed";

    /** Transfer aborted before commit; payload {@code TransferFailedPayload}. */
    public static final String TRANSFER_FAILED = "continuum.transfer.failed";

    /** Session ownership released (clean disconnect or takeover); payload {@code SessionReleasedPayload}. */
    public static final String SESSION_RELEASED = "continuum.session.released";

    private ProtocolEvents() {
    }
}
