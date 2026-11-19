package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The contract third-party plugins implement to extend Continuum with their own
 * synchronizable state. Registered via
 * {@code ContinuumProvider.get().modules().register(adapter)} and unregistered again when
 * the owning plugin disables (returning the {@link ModuleRegistration} and closing it, or
 * letting the platform unregister everything on shutdown).
 *
 * <p>An adapter is a {@link SyncModule} with the knobs a third party needs and Continuum
 * does not prescribe:</p>
 * <ul>
 *   <li>{@link #required()} — whether this adapter's data is load-bearing. An optional
 *       adapter's failure is isolated (the module is marked {@code FAILED} and the
 *       operation continues); a required adapter's failure aborts the operation.</li>
 *   <li>{@link #timeout()} — the maximum wall-clock time Continuum waits for
 *       {@link #capture}/{@link #restore}. Exceeding it is reported as {@code TIMEOUT}
 *       and handled per the adapter's failure policy.</li>
 *   <li>{@link #failurePolicy()} — {@link FailurePolicy#ABORT} (default for required
 *       adapters) or {@link FailurePolicy#SKIP} (default for optional adapters). See
 *       {@link FailurePolicy} for the exact semantics.</li>
 *   <li>{@link #migrate(int, String)} — the hook that rewrites stored payloads written by
 *       an older schema version of this adapter into the current one.</li>
 * </ul>
 *
 * <p>All threading semantics of {@link SyncModule} apply unchanged — capture/restore run
 * on the player's platform scheduler thread, never on Continuum's infrastructure
 * executors.</p>
 *
 * <p>Implementations should be immutable and stateless: Continuum may call
 * {@link #id()}, {@link #version()}, {@link #priority()} and {@link #dependencies()} at
 * planning time from arbitrary threads and may cast or otherwise probe the adapter during
 * registration.</p>
 *
 * @param <P> the platform player handle type (e.g. {@code org.bukkit.entity.Player})
 * @param <T> the adapter's captured data type
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface SyncAdapter<P, T> extends SyncModule<P, T> {

    /** The default per-operation timeout applied to capture and restore. */
    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Whether this adapter is load-bearing for a correct restore.
     *
     * <p>When false (an <em>optional</em> adapter), a failing capture or restore does not
     * fail the operation: the module is reported as {@code FAILED}, the overall result
     * degrades to {@code PARTIAL} at best, and the rest of the snapshot is applied. When
     * true (the default), the failure aborts the operation per {@link #failurePolicy()}.</p>
     *
     * <p>First-party Continuum modules are always required. Third-party adapters whose
     * state is cosmetic or re-derivable (nicknames, play-time effects, cached metadata)
     * should declare themselves optional so their failure cannot take a player's
     * inventory, vitals and experience down with them.</p>
     *
     * @return true when this adapter's data is required for a coherent restore
     */
    default boolean required() {
        return true;
    }

    /**
     * The maximum wall-clock time Continuum waits for {@link #capture} or {@link #restore}
     * to complete. Must be positive.
     *
     * <p>A timed-out module is reported as {@code TIMEOUT}; a required adapter then aborts
     * the operation, an optional one is skipped. Continuum cannot interrupt a stuck
     * platform call, so keep your handlers prompt and non-blocking.</p>
     *
     * @return the timeout, never null, must be positive (&gt; 0)
     */
    @NotNull
    default Duration timeout() {
        return DEFAULT_TIMEOUT;
    }

    /**
     * The failure isolation policy for this adapter: what to do when its capture,
     * restore or validation fails.
     *
     * <p>The default is derived from {@link #required()}: required adapters default to
     * {@link FailurePolicy#ABORT}, optional ones to {@link FailurePolicy#SKIP}. Override
     * only with a concrete reason — an optional adapter that fails the whole restore when
     * it throws is not optional in any meaningful sense.</p>
     *
     * @return the policy, never null
     */
    @NotNull
    default FailurePolicy failurePolicy() {
        return required() ? FailurePolicy.ABORT : FailurePolicy.SKIP;
    }

    /**
     * Migrates a stored payload written by an older schema version of this adapter to the
     * current {@link #version()}.
     *
     * <p>Continuum invokes this at restore time when the stored schema version differs
     * from the adapter's current version. {@code rawPayload} is the payload exactly as
     * serialized for storage (UTF-8 JSON produced from {@code T}'s serialized form);
     * parsing it is the adapter's responsibility, because the adapter owns the shape of
     * its own data.</p>
     *
     * <p>The default implementation is a deliberate guard, not a stub: an adapter that has
     * never registered a migration cannot be trusted to interpret old payloads, so the
     * migration completes exceptionally with {@link AdapterMigrationException}. Continuum
     * then reports the module as {@code MIGRATION_FAILED} — isolated when the adapter is
     * optional, aborting when it is required. To support old data, implement this method;
     * doing nothing is a safe, explicit choice, never a silent half-restore.</p>
     *
     * @param fromVersion the schema version that wrote {@code rawPayload}; always &lt; {@link #version()}
     * @param rawPayload the stored payload, never null; UTF-8 JSON
     * @return the migrated payload in the current schema
     */
    @NotNull
    default CompletionStage<T> migrate(int fromVersion, @NotNull String rawPayload) {
        return CompletableFuture.failedFuture(
                new AdapterMigrationException(id(), fromVersion, "no migration registered"));
    }
}
