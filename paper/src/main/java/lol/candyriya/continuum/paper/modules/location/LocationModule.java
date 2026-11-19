package lol.candyriya.continuum.paper.modules.location;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs the player's position and respawn-related state across backends; the location module.
 *
 * <p><b>Two layers, do not confuse them.</b> Placement decisions live on two separate
 * layers and this module only owns one of them:</p>
 * <ul>
 *   <li><b>Gate-time (platform):</b> the async spawn-decision event asks the platform's
 *       spawn policy ({@code platform.yml} → {@code spawn-policy}) where the connection
 *       appears, before the player entity exists. The platform loads last-known data
 *       through its {@code LastKnownLocationProvider} and falls back to the server spawn
 *       when it cannot. This module does <em>not</em> read, fork or re-export that policy,
 *       and never will.</li>
 *   <li><b>Module restore (this class):</b> during the join-gate window, after the gate has
 *       placed the player, this module applies the snapshot.</li>
 * </ul>
 *
 * <p><b>What is captured</b> (see {@link LocationPayload} for the wire format):</p>
 * <ul>
 *   <li>position (world name + environment, x/y/z, yaw, pitch) — via {@code Player#getLocation}</li>
 *   <li>respawn point (bed or respawn anchor) — via {@code Player#getRespawnLocation(false)},
 *       the no-chunk-load, no-validation overload; the validating overload would force a
 *       synchronous chunk load on the caller's thread, which is out of the question on Folia</li>
 *   <li>last death point — via {@code HumanEntity#getLastDeathLocation()}</li>
 * </ul>
 *
 * <p><b>Respawn anchors are first-class, without an anchor-specific field.</b> The public API
 * exposes no bed-vs-anchor discriminator on stored state: {@code getRespawnLocation} returns
 * the active respawn point — bed <em>or</em> respawn anchor — and {@code setRespawnLocation}
 * writes the same single slot. (The {@code isBedSpawn()}/{@code isAnchorSpawn()} flags exist
 * only on the respawn <em>event</em>, where the client learns the answer at respawn time; they
 * are not queryable for the stored point.) There is nothing anchor-specific to capture, so the
 * module carries exactly one respawn-point section and both block types round-trip through it.
 * (Verified against the 26.3 API surface this build compiles against.)</p>
 *
 * <p><b>Restore semantics.</b></p>
 * <ul>
 *   <li>Position restores on every login, subject to policy: {@code LAST_KNOWN_LOCATION}
 *       teleports (async) to the saved position; {@code SERVER_SPAWN} and
 *       {@code RESPAWN_ONLY} leave the player where the gate-time spawn policy placed them.</li>
 *   <li>Respawn point and last death point restore whenever their own world rules pass —
 *       the position mode does not govern them.</li>
 *   <li>World rules (apply to every section, independently): restore only when the snapshot
 *       world exists (is loaded) on this node and is not on the policy's exclude list, and is
 *       not a same-named world of a different dimension family (captured environment vs the
 *       loaded world's environment; unknown on either side = no opinion, restore proceeds).
 *       On any violation the section is skipped and the player keeps the current state.</li>
 *   <li>The {@code SERVER_SPAWN} fallback for an unusable snapshot world is honored by this
 *       module standing aside — the gate-time spawn policy's own fallback (server spawn)
 *       decides where the player lands. The module does not teleport "to spawn" itself
 *       because it must not fork the placement decision.</li>
 * </ul>
 *
 * <p><b>What "unknown world names recorded, not fatal" means here.</b> The snapshot always
 * records the world name as captured. On restore, an unknown world is logged at WARN with
 * the section and world name and otherwise ignored — the payload is <em>not</em> rejected,
 * the other sections proceed, and the module completes normally. Per-skip diagnostics are
 * exactly that: WARN lines. The {@code SyncModule} contract carries no notes field (the
 * report is assembled from module completion only), so there is no channel for a machine
 * readable note and none is faked.</p>
 *
 * <p><b>Excluding a world fences game spaces, it does not filter by taste.</b> Cross-backend
 * portability rests on one convention: the same world name on two nodes is the same game
 * space. Operators who run genuinely different spaces under the same name on different
 * backends must list that name in {@code location.exclude-worlds} (matched case-insensitively)
 * or the module will carry positions into it.</p>
 *
 * <p><b>Restore fidelity notes.</b></p>
 * <ul>
 *   <li>The respawn-point restore writes with {@code force = true}: the write does not require an
 *       already-valid respawn point at the target. Whether the saved bed/anchor block actually
 *       exists and is not obstructed is decided by the vanilla bed obstruction check at respawn
 *       time, which a forced write never overrides — a stale bed just respawns the player at the
 *       world spawn, exactly as it would on the capturing node.</li>
 *   <li>{@code setLastDeathLocation} is server-side state; the client only learns of it when
 *       the player respawns (platform behavior), so setting it pre-respawn is correct.</li>
 *   <li>The position teleport uses {@code teleportAsync} exclusively — synchronous teleport
 *       never returns on Folia — and the returned stage is folded into the restore stage so
 *       Continuum waits for the actual move, not just for dispatch.</li>
 * </ul>
 *
 * <p><b>Failure policy.</b> The three sections restore independently — a failure in one does
 * not preempt the others. Exceptions from a section are collected and surface at the end as an
 * exceptionally completed stage, which Continuum records as a module failure (isolation:
 * other modules restore normally). A teleport the platform <em>refuses</em> (returns false)
 * is not an exception and not a module failure — it is logged at WARN; the player stays at the
 * gate-time spawn location. Validation failures are reported through {@link #validate} and are
 * never applied to the player.</p>
 *
 * <p><b>Thread affinity.</b> Per the module contract, {@link #capture}, {@link #validate} and
 * {@link #restore} are invoked on the thread that owns the player — the server main thread on
 * Paper, the entity's region thread on Folia. Reading the live location/respawn/death state,
 * the world environment and the {@code Bukkit#getWorld} lookups are all legal there. Continuum
 * never invokes them from an infrastructure thread, and this module never hops threads itself;
 * only the async teleport future completes outside this thread, and its result is folded back
 * into the returned stage.</p>
 *
 * <p><b>Ordering and dependencies.</b> No dependencies; default priority (same group as the
 * other first-party modules). Position restore does not interact with the
 * attribute/vitals ordering — it teleports a frozen joiner, whose other modules are
 * world-agnostic.</p>
 */
public final class LocationModule implements SyncModule<Player, LocationPayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationModule.class);

    /** The module id this module registers as; also exposed for registration wiring. */
    public static final String MODULE_ID = BuiltinModules.LOCATION;

    private final Clock clock = Clock.systemUTC();
    private final LocationPolicy policy;
    private final WorldLookup worldLookup;
    private final ServerLookup serverLookup;

    /** The out-of-the-box wiring: default policy, live server lookups. */
    public LocationModule() {
        this(LocationPolicy.defaults(), BukkitWorldLookup.INSTANCE, BukkitServerLookup.INSTANCE);
    }

    /**
     * Full wiring, used when the platform integration lands and by tests.
     *
     * @param policy      the module-layer policy; null maps to {@link LocationPolicy#defaults()}
     * @param worldLookup world existence/environment lookup; null maps to the live server lookup
     * @param serverLookup server facts for diagnostics; null maps to the live server lookup
     */
    public LocationModule(@Nullable LocationPolicy policy, @Nullable WorldLookup worldLookup, @Nullable ServerLookup serverLookup) {
        this.policy = policy == null ? LocationPolicy.defaults() : policy;
        this.worldLookup = worldLookup == null ? BukkitWorldLookup.INSTANCE : worldLookup;
        this.serverLookup = serverLookup == null ? BukkitServerLookup.INSTANCE : serverLookup;
    }

    @Override
    public @NotNull String id() {
        return MODULE_ID;
    }

    @Override
    public int version() {
        return LocationPayload.FORMAT;
    }

    @Override
    public @NotNull CompletionStage<LocationPayload> capture(@NotNull Player player) {
        try {
            PositionSnapshot position = PositionSnapshot.of(player.getLocation());
            if (position == null) {
                // no position = nothing usable for restore; fail the capture, other modules carry on
                var failure = new IllegalStateException(
                        "player " + player.getUniqueId() + " location carries no world; refusing to capture a positionless payload");
                LOGGER.error("capture failed for player {}: {}", player.getUniqueId(), failure.getMessage());
                return CompletableFuture.failedFuture(failure);
            }
            PositionSnapshot respawnPoint = readRespawnPoint(player);
            PositionSnapshot lastDeathPoint = readLastDeathPoint(player);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("captured location snapshot for player {}: position in '{}' respawn_point={} last_death_point={}",
                        player.getUniqueId(), position.world(),
                        respawnPoint == null ? "none" : "'" + respawnPoint.world() + "'",
                        lastDeathPoint == null ? "none" : "'" + lastDeathPoint.world() + "'");
            }
            return CompletableFuture.completedFuture(
                    new LocationPayload(LocationPayload.FORMAT, clock.millis(), position, respawnPoint, lastDeathPoint));
        } catch (RuntimeException e) {
            // wrap with context, then let the module failure path handle it
            LOGGER.error("capture failed for player {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("failed to capture location state for player " + player.getUniqueId(), e));
        }
    }

    @Override
    public @NotNull CompletionStage<Void> restore(@NotNull Player player, @NotNull LocationPayload data) {
        // the documented contract is "this build restores format 1 only" — enforce it here too,
        // not just in validate(): Continuum's validate-then-restore ordering is policy, not a codec guarantee
        if (data.format() != LocationPayload.FORMAT) {
            var failure = new IllegalStateException("unsupported location payload format " + data.format()
                    + " (expected " + LocationPayload.FORMAT + ")");
            LOGGER.error("refusing to restore player {}: {}", player.getUniqueId(), failure.getMessage());
            return CompletableFuture.failedFuture(failure);
        }
        LocationRestorePlanner.Plan plan = LocationRestorePlanner.plan(data, policy, worldLookup);
        logDecisions(player, plan);

        List<String> failed = new ArrayList<>();
        applyRespawnPoint(player, data.respawnPoint(), plan.respawnPoint(), failed);
        applyLastDeathPoint(player, data.lastDeathPoint(), plan.lastDeathPoint(), failed);
        // immutable snapshot of the synchronous failures: the async teleport completion thread
        // must never read a mutable list the player thread already finished writing
        List<String> syncFailures = List.copyOf(failed);
        // async failures travel through the future chain instead of a shared mutable list
        return applyPosition(player, data.position(), plan.position()).thenApply(positionFailures -> {
            var all = new ArrayList<>(syncFailures);
            all.addAll(positionFailures);
            if (!all.isEmpty()) {
                throw new IllegalStateException("location restore failed for player " + player.getUniqueId()
                        + ": " + String.join(", ", all));
            }
            LOGGER.debug("location restore finished for player {}", player.getUniqueId());
            return null;
        });
    }

    @Override
    public @NotNull CompletionStage<ValidationResult> validate(@NotNull Player player, @NotNull LocationPayload data) {
        // structural problem in the stored payload; Continuum skips or aborts per the module policy
        return CompletableFuture.completedFuture(LocationValidation.check(data));
    }

    // ---------- capture internals ----------

    private @Nullable PositionSnapshot readRespawnPoint(@NotNull Player player) {
        try {
            // loadLocationAndValidate=false: no sync chunk load, no validation — the stored point is captured as-is.
            // Deprecated bed-spawn variants are avoided on purpose; this is the current respawn-location API.
            Location respawn = player.getRespawnLocation(false);
            PositionSnapshot snapshot = PositionSnapshot.of(respawn);
            if (respawn != null && snapshot == null) {
                LOGGER.warn("respawn location for player {} carries no world; capturing without it", player.getUniqueId());
            }
            return snapshot;
        } catch (RuntimeException e) {
            // an unreadable respawn point must not fail the whole capture — the section is optional
            LOGGER.error("could not read the respawn location of player {}; capturing without it", player.getUniqueId(), e);
            return null;
        }
    }

    private @Nullable PositionSnapshot readLastDeathPoint(@NotNull Player player) {
        try {
            Location death = player.getLastDeathLocation();
            PositionSnapshot snapshot = PositionSnapshot.of(death);
            if (death != null && snapshot == null) {
                LOGGER.warn("last death location for player {} carries no world; capturing without it", player.getUniqueId());
            }
            return snapshot;
        } catch (RuntimeException e) {
            LOGGER.error("could not read the last death location of player {}; capturing without it", player.getUniqueId(), e);
            return null;
        }
    }

    // ---------- restore internals ----------

    private void logDecisions(@NotNull Player player, @NotNull LocationRestorePlanner.Plan plan) {
        for (LocationRestorePlanner.SectionDecision decision : plan.decisions()) {
            logDecision(player, decision);
        }
    }

    private void logDecision(@NotNull Player player, @NotNull LocationRestorePlanner.SectionDecision decision) {
        switch (decision.action()) {
            case APPLY -> {
                // the common case; the debug line in restore covers it
            }
            case KEEP_CURRENT -> LOGGER.debug("position restore skipped for player {}: mode {} — the gate-time spawn policy stays in effect",
                    player.getUniqueId(), policy.mode());
            case NOTHING_TO_RESTORE -> LOGGER.debug("{}: no data in the snapshot for player {} — nothing to restore",
                    decision.section(), player.getUniqueId());
            case SKIP_UNKNOWN_WORLD -> LOGGER.warn("skipping {} restore for player {}: world '{}' is not loaded on this node; "
                            + "the section stays as the platform left it (fallback '{}' at the gate: expected server spawn '{}')",
                    decision.section(), player.getUniqueId(), decision.world(), policy.fallbackMode(), serverLookup.defaultWorldName());
            case SKIP_EXCLUDED_WORLD -> LOGGER.warn("skipping {} restore for player {}: world '{}' is on location.exclude-worlds",
                    decision.section(), player.getUniqueId(), decision.world());
            case SKIP_ENVIRONMENT_MISMATCH -> LOGGER.warn("skipping {} restore for player {}: world '{}' is a different dimension family on this node than at capture; refusing to mix dimensions",
                    decision.section(), player.getUniqueId(), decision.world());
        }
    }

    private void applyRespawnPoint(@NotNull Player player,
                                   @Nullable PositionSnapshot snapshot,
                                   @NotNull LocationRestorePlanner.SectionDecision decision,
                                   @NotNull List<String> failed) {
        if (!decision.applies()) {
            return;
        }
        if (!LocationValidation.withinBounds(snapshot)) {
            LOGGER.error("skipping respawn point restore for player {}: coordinate out of bounds", player.getUniqueId());
            failed.add("respawn point (coordinate out of bounds)");
            return;
        }
        World world = resolveWorld(decision.world());
        if (world == null) {
            // vanished between the plan and the apply — same honest outcome as the planner's skip
            LOGGER.error("skipping respawn point restore for player {}: world '{}' disappeared mid-restore", player.getUniqueId(), decision.world());
            failed.add("respawn point (world disappeared mid-restore)");
            return;
        }
        try {
            player.setRespawnLocation(toLocation(world, snapshot), true);
        } catch (RuntimeException e) {
            LOGGER.error("could not set the respawn location of player {}", player.getUniqueId(), e);
            failed.add("respawn point");
        }
    }

    private void applyLastDeathPoint(@NotNull Player player,
                                     @Nullable PositionSnapshot snapshot,
                                     @NotNull LocationRestorePlanner.SectionDecision decision,
                                     @NotNull List<String> failed) {
        if (!decision.applies()) {
            return;
        }
        if (!LocationValidation.withinBounds(snapshot)) {
            LOGGER.error("skipping last death point restore for player {}: coordinate out of bounds", player.getUniqueId());
            failed.add("last death point (coordinate out of bounds)");
            return;
        }
        World world = resolveWorld(decision.world());
        if (world == null) {
            LOGGER.error("skipping last death point restore for player {}: world '{}' disappeared mid-restore", player.getUniqueId(), decision.world());
            failed.add("last death point (world disappeared mid-restore)");
            return;
        }
        try {
            player.setLastDeathLocation(toLocation(world, snapshot));
        } catch (RuntimeException e) {
            LOGGER.error("could not set the last death location of player {}", player.getUniqueId(), e);
            failed.add("last death point");
        }
    }

    /**
     * Applies the position section. Returns a stage completing with the section's failure markers
     * (empty list = clean, list of one = the position section failed). Never completes exceptionally:
     * a teleport the platform refuses is a WARN, not a failure; an exceptional teleport outcome
     * travels as a marker and surfaces in the restore stage.
     */
    private @NotNull CompletionStage<List<String>> applyPosition(@NotNull Player player,
                                                                 @Nullable PositionSnapshot snapshot,
                                                                 @NotNull LocationRestorePlanner.SectionDecision decision) {
        if (!decision.applies()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (!LocationValidation.withinBounds(snapshot)) {
            LOGGER.error("skipping position restore for player {}: coordinate out of bounds", player.getUniqueId());
            return CompletableFuture.completedFuture(List.of("position (coordinate out of bounds)"));
        }
        World world = resolveWorld(decision.world());
        if (world == null) {
            LOGGER.error("skipping position restore for player {}: world '{}' disappeared mid-restore", player.getUniqueId(), decision.world());
            return CompletableFuture.completedFuture(List.of("position (world disappeared mid-restore)"));
        }
        Location target = toLocation(world, snapshot);
        CompletableFuture<Boolean> teleport;
        try {
            // never the sync teleport: on Folia it does not return at all
            teleport = player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } catch (RuntimeException e) {
            LOGGER.error("position restore for player {} failed at dispatch", player.getUniqueId(), e);
            return CompletableFuture.completedFuture(List.of("position"));
        }
        // fold the async move into the returned stage so Continuum waits for the actual teleport
        return teleport.handle((ok, ex) -> {
            if (ex != null) {
                LOGGER.error("teleport to the snapshot position failed for player {}", player.getUniqueId(), ex);
                return List.of("position");
            } else if (ok == null || !ok) {
                // refused by the platform: the player stays at the gate-time spawn; not a module failure
                LOGGER.warn("teleport to the snapshot position was refused for player {}; player stays at the gate-time spawn location", player.getUniqueId());
            } else {
                LOGGER.debug("player {} teleported to the snapshot position in '{}'", player.getUniqueId(), decision.world());
            }
            return List.<String>of();
        });
    }

    private @Nullable World resolveWorld(@Nullable String worldName) {
        if (worldName == null) {
            return null;
        }
        try {
            return Bukkit.getWorld(worldName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @NotNull Location toLocation(@NotNull World world, @NotNull PositionSnapshot snapshot) {
        return new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
    }
}
