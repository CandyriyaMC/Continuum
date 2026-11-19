package lol.candyriya.continuum.velocity.session;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Local-development token source.
 *
 * <p>Issues {@code 1, 2, 3 ...} from an in-memory counter. Useful when poking at
 * the plugin on a laptop; it is deliberately NOT wired into the production
 * bootstrap. Two nodes running this source would mint overlapping tokens, which
 * is exactly the failure the fencing token exists to prevent - the production
 * source is seeded by the database and lands together with the transfer
 * orchestrator.</p>
 */
public final class InMemorySessionTokenSource implements SessionTokenSource {

    private final AtomicLong counter;

    public InMemorySessionTokenSource() {
        this(1L);
    }

    /** Creates a source whose next token is {@code seed}. */
    public InMemorySessionTokenSource(long seed) {
        if (seed < 1) {
            throw new IllegalArgumentException("seed must be >= 1, got " + seed);
        }
        this.counter = new AtomicLong(seed - 1);
    }

    @Override
    public long nextToken() throws SessionTokenException {
        long token = counter.incrementAndGet();
        if (token <= 0) {
            // wrapped past Long.MAX_VALUE; the in-memory space is exhausted
            throw new SessionTokenException("in-memory session token space exhausted");
        }
        return token;
    }
}
