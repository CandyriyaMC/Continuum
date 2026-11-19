package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * Base class for all module-contract failures raised at registration or during a sync
 * operation. Unchecked on purpose — a module bug is a programming error, not an I/O
 * condition the caller should have to handle.
 *
 * <p>Subtypes give callers something specific to catch:</p>
 * <ul>
 *   <li>{@link InvalidModuleIdException} — the id does not match the module id convention.</li>
 *   <li>{@link DuplicateModuleIdException} — the id is already taken by another module.</li>
 *   <li>{@link InvalidModuleDefinitionException} — the module contract is inconsistent
 *       (bad version, timeout, dependencies).</li>
 *   <li>{@link AdapterMigrationException} — stored data could not be migrated to the
 *       adapter's current schema version.</li>
 * </ul>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public abstract class ModuleException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with an explicit message.
     *
     * @param message what is wrong, in English, never null
     */
    protected ModuleException(@NotNull String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and a cause.
     *
     * @param message what is wrong, in English, never null
     * @param cause the underlying failure, never null
     */
    protected ModuleException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
