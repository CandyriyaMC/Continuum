package lol.candyriya.continuum.paper.modules.custom;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The completion deadline every adapter stage is composed with. Continuum cannot interrupt
 * a stuck platform call, so the contract is: the adapter's stage is given at most
 * {@code timeout}; when it completes earlier, its result wins; when it does not, the
 * stage is <em>detached</em> — the returned future completes exceptionally with a
 * {@link DeadlineTimeoutException}, {@code cancelled} flips true (refusing late writes
 * through the guarded player) and whatever the stage produces afterwards is dropped.
 *
 * <p>Late completions are dropped, never propagate to the caller: the caller treats the
 * adapter as failed for this operation. The marker subclass distinguishes our deadline
 * from a {@link TimeoutException} the adapter produced itself — that one is the adapter's
 * own failure, not a detachment, and reports as such.</p>
 */
final class TimeoutCall {

    private TimeoutCall() {
        // static access only
    }

    /** Marker: the deadline we attached fired; not an adapter-side timeout. */
    static final class DeadlineTimeoutException extends TimeoutException {

        DeadlineTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * Composes a deadline onto {@code stage}. The returned future completes with the
     * stage's value, exceptionally with the stage's failure, or exceptionally with a
     * {@link DeadlineTimeoutException} — never with both, and never after the timer fired.
     */
    static <T> CompletableFuture<T> detach(CompletionStage<T> stage,
                                           Duration timeout,
                                           ScheduledExecutorService timer,
                                           AtomicBoolean cancelled) {
        CompletableFuture<T> outcome = new CompletableFuture<>();
        stage.whenComplete((value, error) -> {
            if (cancelled.get()) {
                // we already detached; drop whatever this was
                return;
            }
            if (error != null) {
                outcome.completeExceptionally(unwrap(error));
            } else {
                outcome.complete(value);
            }
        });
        timer.schedule(() -> {
            if (outcome.completeExceptionally(
                    new DeadlineTimeoutException("timed out after " + timeout.toMillis() + " ms"))) {
                cancelled.set(true);
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        return outcome;
    }

    /** Unwraps the {@link CompletionException}/{@link ExecutionException} wrappers stages add. */
    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
        }
        return current;
    }

    /** Whether {@code error} (possibly wrapped) is our deadline, not an adapter-side timeout. */
    static boolean isDeadlineTimeout(Throwable error) {
        return unwrap(error) instanceof DeadlineTimeoutException;
    }
}
