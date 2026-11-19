package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;

/**
 * Handle for an in-flight request registered in {@link PendingRequests}.
 *
 * <p>{@link #correlationId()} is the id both sides carry in the envelope;
 * {@link #response()} completes when the peer's reply arrives, when the
 * request times out ({@link PendingRequestTimeoutException}), when it is
 * failed explicitly, or when the table is closed.</p>
 */
public record PendingRequest(UUID correlationId, CompletableFuture<ProxyToBackendMessage> response) {

    public PendingRequest {
        if (correlationId == null) {
            throw new NullPointerException("correlationId must not be null");
        }
        if (response == null) {
            throw new NullPointerException("response must not be null");
        }
    }
}
