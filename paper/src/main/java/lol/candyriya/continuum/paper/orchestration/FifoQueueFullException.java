package lol.candyriya.continuum.paper.orchestration;

import java.util.UUID;

/**
 * Thrown (as an exceptional completion, never inline) when a player's FIFO
 * queue is full. Refusing here is the backpressure contract: the caller logs
 * and the operation is dropped rather than grown into unbounded memory.
 */
public final class FifoQueueFullException extends IllegalStateException {

    private final UUID playerId;
    private final int capacity;

    public FifoQueueFullException(UUID playerId, int capacity) {
        super("fifo queue full for player " + playerId + " (capacity " + capacity + "); refusing to enqueue");
        this.playerId = playerId;
        this.capacity = capacity;
    }

    public UUID playerId() {
        return playerId;
    }

    public int capacity() {
        return capacity;
    }
}
