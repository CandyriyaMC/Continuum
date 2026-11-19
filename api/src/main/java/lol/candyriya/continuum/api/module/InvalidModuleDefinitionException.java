package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * Thrown when a module or adapter satisfies the id rules but its definition is
 * inconsistent — for example a schema version below 1, a non-positive capture timeout,
 * a dependency on itself, or a dependency list containing duplicates.
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class InvalidModuleDefinitionException extends ModuleException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is inconsistent and why, in English, never null
     */
    public InvalidModuleDefinitionException(@NotNull String message) {
        super(message);
    }
}
