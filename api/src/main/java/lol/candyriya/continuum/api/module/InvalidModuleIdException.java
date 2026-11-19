package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * Thrown when a module id does not follow the module id convention documented on
 * {@link ModuleIds}: dotted lowercase segments such as {@code continuum.inventory} or
 * {@code myplugin.cool_module}.
 *
 * <p>Also thrown when a third-party module tries to use the reserved
 * {@code continuum.*} namespace, which is owned by Continuum's first-party modules.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class InvalidModuleIdException extends ModuleException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The rejected id, or null when the supplied id was null. */
    private final String id;

    /**
     * Creates the exception for the offending id.
     *
     * @param id the rejected id, may be null when the id itself was null — never required to be valid
     * @param detail why it was rejected, never null
     */
    public InvalidModuleIdException(String id, @NotNull String detail) {
        super("invalid module id " + (id == null ? "<null>" : "'" + id + "'") + ": " + detail);
        this.id = id;
    }

    /**
     * The offending id, or null when the supplied id was null.
     *
     * @return the id passed in, never invented
     */
    public String id() {
        return id;
    }
}
