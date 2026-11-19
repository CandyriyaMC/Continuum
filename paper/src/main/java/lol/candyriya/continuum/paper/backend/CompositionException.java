package lol.candyriya.continuum.paper.backend;

/**
 * Thrown when the backend composition fails. The message names the step that
 * failed ({@code storage}, {@code scheduler}, {@code gate}, {@code messaging})
 * so operators know where to look; the cause carries the real problem.
 */
public final class CompositionException extends RuntimeException {

    private final String step;

    public CompositionException(String step, String message, Throwable cause) {
        super("backend composition failed at step '" + step + "': " + message, cause);
        this.step = step;
    }

    public CompositionException(String step, String message) {
        super("backend composition failed at step '" + step + "': " + message);
        this.step = step;
    }

    /** The composition step that failed. */
    public String step() {
        return step;
    }
}
