/**
 * The module contract package — the heart of Continuum.
 *
 * <p>Every synchronizable kind of state is a &ldquo;module&rdquo;: a small, versioned,
 * dependency-aware unit that captures player state on the way out and restores it on the
 * way in. Built-in modules capture inventory, vitals, experience and the rest; third-party
 * plugins add their own by implementing {@link lol.candyriya.continuum.api.module.SyncAdapter}
 * and registering it through
 * {@code ContinuumProvider.get().modules().register(adapter)}.</p>
 *
 * <p><b>Module ids.</b> Ids are dotted lowercase identifiers ({@code continuum.inventory}),
 * validated by {@link lol.candyriya.continuum.api.module.ModuleIds}. The
 * {@code continuum} namespace is reserved for Continuum's first-party modules (their ids
 * are published as constants in
 * {@link lol.candyriya.continuum.api.module.BuiltinModules}). Third-party plugins pick
 * their own namespace, usually the plugin name. Ids are persisted in snapshots and are
 * effectively permanent.</p>
 *
 * <p><b>Ordering.</b> Modules run through the sync pipeline in dependency order, then by
 * ascending {@code priority()}, ties broken by id — always deterministic. Declare real
 * requirements with {@link lol.candyriya.continuum.api.module.SyncModule#dependencies()}
 * (e.g. vitals depends on attributes) instead of relying on priority.</p>
 *
 * <p><b>Failure model.</b> Each module failure is either isolated or fatal, controlled by
 * {@link lol.candyriya.continuum.api.module.FailurePolicy} (derived from
 * {@code required()} by default). An optional module's failure degrades the operation to
 * {@code PARTIAL} and touches nothing else. A required module's failure aborts the
 * operation; a restore aborted this way puts the player into the recovery flow instead of
 * half-loading state.</p>
 *
 * <p><b>Threading.</b> Capture/restore/validate run on the thread that owns the player
 * (Paper main thread, Folia entity region thread), never on Continuum's infrastructure
 * executors. Module contract methods called by Continuum at planning time ({@code id()},
 * {@code version()}, {@code priority()}, {@code dependencies()}) run on arbitrary threads
 * and must be cheap and stable.</p>
 *
 * <p><b>Nullability and contracts.</b> Method parameters are {@code @NotNull} unless the
 * signature says otherwise; violating that is a programming error. Records in this package
 * validate their components and throw {@code IllegalArgumentException}/
 * {@code NullPointerException} on bad input; the contract interfaces throw
 * {@link lol.candyriya.continuum.api.module.ModuleException} subtypes for consistency
 * failures discovered at registration or migration time.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
