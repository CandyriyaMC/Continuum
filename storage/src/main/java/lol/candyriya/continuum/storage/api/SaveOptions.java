package lol.candyriya.continuum.storage.api;

import java.util.List;
import java.util.Objects;

/**
 * Knobs for one snapshot save: why it happened, which backend wrote it, and
 * which outbox events must ride along in the same database transaction.
 *
 * <p>The outbox entries are appended in the same transaction as the snapshot
 * row; if the transaction rolls back, no events are emitted. Atomicity is the
 * whole point of the outbox pattern — never publish anywhere else first.</p>
 *
 * @param reason        why the snapshot was taken (see {@link SnapshotSummary#reason()});
 *                      non-blank, at most 32 characters
 * @param sourceBackend the backend capturing the snapshot, non-blank, at most 64 characters
 * @param outbox        events to append in the same transaction; may be empty, never null
 */
public record SaveOptions(String reason, String sourceBackend, List<OutboxDraft> outbox) {

    /**
     * One event to append to the outbox inside the save transaction.
     *
     * @param msgType     event type discriminator for consumers, non-blank, at most 64 characters
     * @param payloadJson the event payload as a JSON string, never null
     */
    public record OutboxDraft(String msgType, String payloadJson) {

        public OutboxDraft {
            Objects.requireNonNull(msgType, "msgType must not be null");
            if (msgType.isBlank()) {
                throw new IllegalArgumentException("msgType must not be blank");
            }
            if (msgType.length() > 64) {
                throw new IllegalArgumentException("msgType must be at most 64 characters, got " + msgType.length());
            }
            Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        }
    }

    public SaveOptions {
        reason = SnapshotSummary.requireReason(reason);
        sourceBackend = SnapshotSummary.requireBackend(sourceBackend);
        outbox = outbox == null ? List.of() : List.copyOf(outbox);
    }

    /** A save with no outbox events. */
    public static SaveOptions of(String reason, String sourceBackend) {
        return new SaveOptions(reason, sourceBackend, List.of());
    }

    /** Reason {@value #REASON_API}, backend {@value #BACKEND_UNKNOWN}, no events. */
    public static final String REASON_API = "API";
    public static final String BACKEND_UNKNOWN = "unknown";

    public static SaveOptions defaults() {
        return new SaveOptions(REASON_API, BACKEND_UNKNOWN, List.of());
    }
}
