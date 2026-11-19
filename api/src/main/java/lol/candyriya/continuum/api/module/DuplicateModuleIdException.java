package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * Thrown when a module is registered under an id that is already taken by another
 * registered module.
 *
 * <p>Id uniqueness is enforced at registration time, not at first use: a conflicting
 * registration is rejected immediately and the previously registered module — and the
 * data it captured — stay authoritative. Registration order between two plugins that
 * both ship a module with the same id is therefore deterministic.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class DuplicateModuleIdException extends ModuleException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The id that is already registered. */
    private final String id;

    /**
     * Creates the exception for the duplicated id.
     *
     * @param id the id that is already registered, never null
     */
    public DuplicateModuleIdException(@NotNull String id) {
        super("module id '" + id + "' is already registered");
        this.id = id;
    }

    /**
     * The duplicated id.
     *
     * @return the id that is already registered, never null
     */
    @NotNull
    public String id() {
        return id;
    }
}
