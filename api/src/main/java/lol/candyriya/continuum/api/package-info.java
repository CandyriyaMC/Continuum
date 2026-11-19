/**
 * The public API contract of Continuum — the surface third-party plugins compile against.
 *
 * <p>Continuum is a distributed player-state synchronization layer for Minecraft networks:
 * a Velocity coordinator watches sessions, Paper/Folia backends capture and restore player
 * state, a database holds the truth, and this module is the stable boundary between the
 * platform plugins and everyone else.</p>
 *
 * <p><b>What this package provides</b></p>
 * <ul>
 *   <li>{@link lol.candyriya.continuum.api.ContinuumProvider} — the single entry point used
 *       by third-party code to reach the API of a running plugin instance.</li>
 *   <li>{@link lol.candyriya.continuum.api.ContinuumApi} — the root object exposing the
 *       sub-APIs: sessions, snapshots, modules, players, history and events.</li>
 *   <li>{@link lol.candyriya.continuum.api.ContinuumUnavailableException} — thrown by
 *       {@code ContinuumProvider.get()} when no implementation is registered.</li>
 * </ul>
 *
 * <p><b>Package map</b></p>
 * <ul>
 *   <li>{@code lol.candyriya.continuum.api.session} — session lifecycle model and queries.</li>
 *   <li>{@code lol.candyriya.continuum.api.snapshot} — snapshot metadata, reasons and results.</li>
 *   <li>{@code lol.candyriya.continuum.api.module} — module/adapter contracts, ids, registration.</li>
 *   <li>{@code lol.candyriya.continuum.api.report} — capture/restore reports and module codes.</li>
 *   <li>{@code lol.candyriya.continuum.api.player} — the player diagnostics aggregate.</li>
 *   <li>{@code lol.candyriya.continuum.api.history} — snapshot history and rollback.</li>
 *   <li>{@code lol.candyriya.continuum.api.event} — the public event model and subscription API.</li>
 * </ul>
 *
 * <p><b>Platform neutrality.</b> Nothing in this module references Bukkit, Folia, Velocity,
 * Adventure or any other platform type. Everything here is plain DTOs, contracts and
 * sub-API interfaces, safe to compile against from any platform.</p>
 *
 * <p><b>Stability policy.</b> Unless a type is annotated with
 * {@code @ApiStatus.Experimental}, it is stable within the 1.x line: it will not be renamed,
 * have members removed, or change semantics in an incompatible way. Additions are always
 * backwards-compatible (new methods on interfaces other than the contract interfaces, or
 * new types). Types marked experimental may evolve; their package documentation says so.</p>
 *
 * <p><b>Threading model.</b> Calls into this API never perform blocking I/O on the calling
 * thread except where explicitly documented. Storage-backed operations return
 * {@link java.util.concurrent.CompletableFuture} and complete on Continuum's infrastructure
 * executor — never on a game tick. Module capture/restore callbacks run on the platform
 * scheduler owning the player (Paper: the main thread; Folia: the player's entity region
 * thread), not on infrastructure executors. See {@code lol.candyriya.continuum.api.module}
 * for the module threading contract.</p>
 *
 * <p><b>Nullability.</b> Annotations from {@code org.jetbrains.annotations} are used on
 * public signatures: parameters and returns annotated {@code @NotNull} reject nulls (a
 * null causes a {@link NullPointerException} or {@link IllegalArgumentException}), and
 * returns annotated {@code @Nullable} may legitimately be null. Unannotated types in this
 * module do not exist — if you see one, it is a bug.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
package lol.candyriya.continuum.api;

import org.jetbrains.annotations.ApiStatus;
