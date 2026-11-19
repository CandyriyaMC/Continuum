package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;

/**
 * What happens when a module fails (throws, times out, or rejects its own data) during a
 * capture or restore operation.
 *
 * <p>This is the module's failure isolation policy. Combined with
 * {@link SyncAdapter#required()} it decides whether a single module's failure is
 * contained within its own report entry or takes the whole operation down:</p>
 *
 * <ul>
 *   <li>{@link #ABORT} — the operation fails. Used by modules whose data is load-bearing
 *       for correctness (identity, attributes, vitals). For a capture this means no
 *       snapshot is persisted; for a restore it means the player is not given the saved
 *       state and must go through the recovery flow instead.</li>
 *   <li>{@link #SKIP} — the failure is isolated: the module is marked
 *       {@code FAILED} in the report with the appropriate code, and the operation
 *       continues with the remaining modules. The operation's overall result degrades to
 *       {@code PARTIAL} at best (never silently {@code SUCCESS}).</li>
 * </ul>
 *
 * <p>The default for {@link SyncAdapter#failurePolicy()} is derived from
 * {@link SyncAdapter#required()}: required adapters default to {@code ABORT}, optional
 * ones to {@code SKIP}. Override {@code failurePolicy()} only when you have a concrete
 * reason; a required adapter that wants to be isolated should instead declare itself
 * optional.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public enum FailurePolicy {

    /** The failure aborts the whole capture/restore operation. */
    ABORT,

    /** The failure is contained: the module is marked failed, the operation continues. */
    SKIP
}
