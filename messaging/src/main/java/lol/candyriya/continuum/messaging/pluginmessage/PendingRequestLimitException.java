package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The pending-request table is full. The bound is what keeps the map from
 * growing without limit when the peer disappears — hitting it means requests
 * are not being answered, not that we need more heap.
 */
public final class PendingRequestLimitException extends PluginMessageException {

    private final int maxPending;

    public PendingRequestLimitException(int maxPending) {
        super(ErrorCode.MESSAGING_FAILED, "pending request table is full (max " + maxPending + ")");
        this.maxPending = maxPending;
    }

    /** The configured cap that was reached. */
    public int maxPending() {
        return maxPending;
    }
}
