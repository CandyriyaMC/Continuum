package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * Thrown (or used to complete a stage exceptionally) when the stored payload written by
 * an older version of a module cannot be migrated to the module's current data schema by
 * {@link SyncAdapter#migrate(int, String)}.
 *
 * <p>Continuum treats this as a module-level failure during restore: the affected module
 * is reported as failed with code {@code MIGRATION_FAILED}. If the adapter is optional,
 * the restore continues and the rest of the snapshot is applied; if the adapter is
 * required, the restore is aborted and the player is put into the recovery flow.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class AdapterMigrationException extends ModuleException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The module whose stored data could not be migrated. */
    private final String moduleId;
    /** The schema version of the stored payload that could not be migrated. */
    private final int fromVersion;

    /**
     * Creates the exception.
     *
     * @param moduleId the module whose data could not be migrated, never null
     * @param fromVersion the schema version of the stored payload, as reported by Continuum
     * @param detail why the migration failed, never null; included in the message
     */
    public AdapterMigrationException(@NotNull String moduleId, int fromVersion, @NotNull String detail) {
        super("module '" + moduleId + "' cannot migrate stored data from version "
                + fromVersion + ": " + detail);
        this.moduleId = moduleId;
        this.fromVersion = fromVersion;
    }

    /**
     * Creates the exception, preserving an underlying cause.
     *
     * @param moduleId the module whose data could not be migrated, never null
     * @param fromVersion the schema version of the stored payload, as reported by Continuum
     * @param cause the underlying failure, never null
     */
    public AdapterMigrationException(@NotNull String moduleId, int fromVersion, @NotNull Throwable cause) {
        super("module '" + moduleId + "' cannot migrate stored data from version "
                + fromVersion + ": " + cause.getMessage(), cause);
        this.moduleId = moduleId;
        this.fromVersion = fromVersion;
    }

    /**
     * The module whose stored data could not be migrated.
     *
     * @return the module id, never null
     */
    @NotNull
    public String moduleId() {
        return moduleId;
    }

    /**
     * The schema version of the stored payload that could not be migrated.
     *
     * @return the stored schema version
     */
    public int fromVersion() {
        return fromVersion;
    }
}
