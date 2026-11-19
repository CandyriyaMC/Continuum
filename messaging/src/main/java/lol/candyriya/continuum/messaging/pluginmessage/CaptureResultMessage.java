package lol.candyriya.continuum.messaging.pluginmessage;

import org.jetbrains.annotations.Nullable;

/**
 * Backend's answer to a {@link CaptureRequestMessage}: whether the
 * pre-move capture reached the database.
 *
 * <p>The reply carries the correlation id of the request that triggered it —
 * that pairing is how the proxy matches this frame to the pending decision;
 * there is no other authentication on this channel, so the backend must
 * answer even a refusal (never silence) and the proxy must drop replies
 * with unknown correlation ids.</p>
 *
 * <p>Both nullable fields are part of the failure signal: {@code snapshotId}
 * is only meaningful when {@code captured} is true, {@code detail} narrates
 * why it is false. The failure wire shape is typically
 * {@code captured=false, snapshotId=null, detail="..."}; success is
 * {@code captured=true, snapshotId=<id>, detail=null}. Field rules are
 * enforced by {@link PluginMessageValidator}, not here — this record stays
 * constructible for diagnostics.</p>
 *
 * @param captured   true when the snapshot reached the database; false on any
 *                   failure mode (required module failed, nothing persisted,
 *                   capture errored, request refused)
 * @param snapshotId id of the persisted snapshot, or null when nothing was
 *                   persisted; when present it must be >= 1
 * @param detail     free-form English explanation for logs and operators when
 *                   {@code captured} is false; null on success, at most 256
 *                   characters, no control characters
 */
public record CaptureResultMessage(boolean captured,
                                   @Nullable Long snapshotId,
                                   @Nullable String detail) implements BackendToProxyMessage {
}
