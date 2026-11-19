package lol.candyriya.continuum.api;

import lol.candyriya.continuum.api.event.EventService;
import lol.candyriya.continuum.api.history.HistoryService;
import lol.candyriya.continuum.api.module.ModuleService;
import lol.candyriya.continuum.api.player.PlayerService;
import lol.candyriya.continuum.api.session.SessionService;
import lol.candyriya.continuum.api.snapshot.SnapshotService;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * The root of the Continuum public API — everything a third-party plugin can do with
 * Continuum, reached through {@code ContinuumProvider.get()}.
 *
 * <p>The API is deliberately split into focused sub-APIs; obtain this once at plugin
 * enable (the platform registers it before enabling plugins) and keep it for as long as
 * you need it. The same instance is the platform's own API, so what you see is what the
 * platform sees.</p>
 *
 * <ul>
 *   <li>{@link #sessions()} — current session ownership state (read-only).</li>
 *   <li>{@link #snapshots()} — capture on demand and read stored snapshot metadata.</li>
 *   <li>{@link #modules()} — the module registry: inspect everything registered and
 *       register third-party synchronization modules.</li>
 *   <li>{@link #players()} — per-player diagnostics view.</li>
 *   <li>{@link #history()} — snapshot history and non-destructive rollback.</li>
 *   <li>{@link #events()} — the event model (experimental).</li>
 * </ul>
 *
 * <p><b>Threading.</b> Storage-backed calls return {@link java.util.concurrent.CompletableFuture}
 * and complete on Continuum's infrastructure executor, never on a game thread. Metadata
 * lookups ({@code modules().modules()}, registration) may be called from any thread and
 * never perform I/O.</p>
 *
 * <p><b>Failure model.</b> A missing or disabled Continuum is reported by
 * {@code ContinuumProvider.get()} throwing {@link ContinuumUnavailableException}; failures
 * inside operations complete futures exceptionally with typed exceptions from the relevant
 * package (see each service's Javadoc). No sub-API throws checked exceptions.</p>
 *
 * <p><b>Stability.</b> The sub-APIs are stable within the 1.x line. {@code events()} is
 * the experimental exception — see the {@code event} package documentation.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface ContinuumApi {

    /**
     * The Continuum version string, same one as in the plugin descriptors
     * (e.g. {@code "1.0.0"}). Useful for capability probing in third-party plugins.
     *
     * @return the version, never null
     */
    @NotNull
    String version();

    /**
     * Session ownership state: who currently owns a player's data.
     *
     * @return the session service, never null
     */
    @NotNull
    SessionService sessions();

    /**
     * Snapshot metadata reads and on-demand capture.
     *
     * @return the snapshot service, never null
     */
    @NotNull
    SnapshotService snapshots();

    /**
     * Module registry: inspect registered modules, register third-party adapters.
     *
     * @return the module service, never null
     */
    @NotNull
    ModuleService modules();

    /**
     * Per-player diagnostics view.
     *
     * @return the player service, never null
     */
    @NotNull
    PlayerService players();

    /**
     * Snapshot history and non-destructive rollback.
     *
     * @return the history service, never null
     */
    @NotNull
    HistoryService history();

    /**
     * The event model (experimental).
     *
     * @return the event service, never null
     */
    @NotNull
    EventService events();
}
