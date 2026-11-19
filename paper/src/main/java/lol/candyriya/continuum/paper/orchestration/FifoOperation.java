package lol.candyriya.continuum.paper.orchestration;

/** One unit of work for the per-player FIFO queue. */
@FunctionalInterface
public interface FifoOperation<T> {

    /** Performs the DB-referencing operation; may throw for expected failure modes. */
    T run() throws Exception;
}
