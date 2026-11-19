package lol.candyriya.continuum.paper.modules.metadata;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs the player's plugin-owned profile presentation — the two names a
 * backend is allowed to put on a player — and nothing else. This is the
 * metadata module, and its scope is the result of an explicit split, not a
 * shortcut. The Bukkit {@code Metadatable} store, every runtime entity flag,
 * the tab-list header/footer, and everything derived from the scoreboard
 * family are deliberately absent: they are server-local runtime state and
 * must never travel between backends. The full, machine-checked matrix lives
 * in {@link MetadataEntry}; this javadoc carries the same decisions in prose.
 *
 * <h2>What syncs, and why these slots are defensible</h2>
 *
 * <p>Both synced keys are the values plugins set to represent the player
 * (role, group, clan, patron flair). They are not persisted by the server
 * anywhere, so on a backend switch they silently revert to the plain account
 * name; syncing them is the gap this module closes. They round-trip as
 * <b>adventure gson component JSON</b> (the serializer the platform itself
 * ships — MiniMessage is deliberately not used: it does not round-trip
 * user-typed markup safely) under hard per-key budgets.</p>
 *
 * <ul>
 *   <li><b>{@code display_name}</b> — {@code Player#displayName()} /
 *       {@code displayName(Component)} (Paper). The friendly name plugins put
 *       in chat.</li>
 *   <li><b>{@code player_list_name}</b> — {@code Player#playerListName()} /
 *       {@code playerListName(Component)} (Paper). The tab-list name;
 *       {@code playerListName(null)} resets to the plain player name.</li>
 * </ul>
 *
 * <p>A captured component equal to the plain player name
 * ({@code Component.text(name)}) means "never customized" and is normalized
 * to the absent state, so default players occupy nothing in the snapshot and
 * a restore does not gratuitously re-apply the default on the target.</p>
 *
 * <h2>What does not sync, and why (per key family)</h2>
 *
 * <p>Excluded keys are never read or written by this module; each family's
 * reason is encoded in {@link MetadataEntry} and pinned by tests. Summary:</p>
 *
 * <ul>
 *   <li><b>Runtime entity flags</b> — {@code Entity#setGlowing/isGlowing},
 *       {@code setInvisible/isInvisible}, {@code setInvulnerable/isMarkedInvulnerable/
 *       isInvulnerableTo(DamageSource)}, {@code setFreezeTicks/lockFreezeTicks},
 *       {@code LivingEntity#setCollidable/isCollidable}: shared data flags the
 *       server decays or re-derives; owned per backend by vanish/highlight/
 *       minigame tooling. The 26.3 javadoc also restricts collidable to
 *       non-player entities, making it a documented no-op for players.</li>
 *   <li><b>No-ops and read-only hints</b> — {@code Nameable#customName()}
 *       ("has no effect on players"), {@code Player#getPlayerListOrder()}
 *       (positive-only sort hint, no encoding for unset), {@code getLocale()}
 *       and {@code getClientBrandName()} (read-only client hints).</li>
 *   <li><b>Clientbound send-only state</b> — {@code Player#playerListHeader()} /
 *       {@code playerListFooter()} and the Adventure
 *       {@code Audience#sendPlayerListHeaderAndFooter(Component, Component)}
 *       calls: tab-list plugins re-render these every join (usually from
 *       placeholders); restoring a capture duplicates or freezes the render.</li>
 *   <li><b>Derived-from-scoreboard presentation</b> — {@code Entity#teamDisplayName()}
 *       and {@code Player#getDeathScreenScore()}: composed from scoreboard
 *       state; the scoreboard family itself is out of scope for this module.</li>
 *   <li><b>The Bukkit metadata store</b> — {@code Metadatable#setMetadata/getMetadata/
 *       hasMetadata/removeMetadata}: deprecated (javadoc: "extremely misleading",
 *       no cleanup on entity removal, no wire format). The sanctioned persistent
 *       store is the PDC, owned by {@code continuum.pdc}.</li>
 *   <li><b>Scoreboard teams</b> — team membership and options (prefix/suffix,
 *       collision rule, nametag visibility): owned by the scoreboard domain per
 *       backend. Not this module.</li>
 *   <li><b>PersistentDataContainer</b> — the Player PDC is persisted by the
 *       server and owned by {@code continuum.pdc}. One owner per store; this
 *       module never touches it.</li>
 *   <li><b>Position-domain state</b> — {@code getRespawnLocation()},
 *       {@code HumanEntity#getLastDeathLocation()}, {@code getCompassTarget()}:
 *       persistent, but {@code Location} carries world identity across backends,
 *       which is the location domain's problem. Not this module.</li>
 *   <li><b>Flight and movement</b> — {@code getAllowFlight/isFlying/getFlySpeed/
 *       getWalkSpeed/setWalkSpeed} and the {@code isSneaking/isSprinting/isGliding}
 *       pose flags: vanilla abilities persist in player data, but they are owned
 *       by {@code continuum.gamemode} together with per-backend flight permission
 *       policy. Mirroring them would leak flight onto restrictive backends.</li>
 *   <li><b>Skin</b> — session-server-signed profile material; the proxy/session
 *       owns identity resolution. A backend must not re-sign identity per server.</li>
 *   <li><b>Everything else</b> — health/food ({@code continuum.vitals}), experience
 *       ({@code continuum.experience}), inventory and ender chest
 *       ({@code continuum.inventory} / {@code continuum.enderchest}), advancements,
 *       recipes, statistics, attributes: owned by their own modules.</li>
 * </ul>
 *
 * <h2>Restore semantics: exact mirror</h2>
 *
 * <p>Restore is unconditional and idempotent — the snapshot is the source of
 * truth, never merged with target state. Keys present in the snapshot are
 * re-applied as stored; keys absent are reset to the vanilla default
 * ({@code displayName(null)} / {@code playerListName(null)}). Per key this is a
 * faithful mirror of "what the player last carried": if the player had no
 * custom name when captured, the target resets to default instead of keeping a
 * stale one. Application order is fixed ({@link MetadataEntry#applicable()}),
 * each key is applied independently, and a key whose stored JSON cannot be
 * decoded is logged, recorded in {@link RestoreReport} and skipped while the
 * other keys still apply. A non-empty failure list completes the restore
 * exceptionally — the stored snapshot is bogus and the runner must see it.</p>
 *
 * <h2>Capture semantics and failure modes</h2>
 *
 * <p>Capture fails loudly (exceptionally completing stage, visible error) when
 * a component exceeds the {@link MetadataPayload#MAX_COMPONENT_CHARS} budget —
 * never a truncated name in the snapshot. The budget bounds the damage a
 * corrupt or hand-edited payload can do before validation bounces it; it is
 * generous against real display names (gradients + hover + click arrive under
 * 2 KiB).</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #capture}, {@link #validate} and {@link #restore} run on the
 * player's owning thread — the server main thread on Paper, the entity's
 * region thread on Folia. Every platform call inside goes through
 * {@link BukkitProfileOps}, a thin pass-through with no decisions; nothing
 * hops threads.</p>
 *
 * <h2>Configuration</h2>
 *
 * <p>Registered under {@code continuum.metadata} ({@link BuiltinModules#METADATA}).
 * The toggle is <code>sync.modules.metadata</code>, default enabled;
 * enablement is a registration-time decision consulted by the platform wiring
 * via {@link MetadataModuleToggles}. The modules themselves are
 * toggle-agnostic.</p>
 *
 * @see MetadataEntry the machine-checked decision matrix
 * @see MetadataPayload the wire format
 * @see MetadataEngine the capture/restore logic
 */
public final class MetadataModule implements SyncModule<Player, MetadataPayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataModule.class);

    /** The module id this module registers as; exposed for registration wiring. */
    public static final String MODULE_ID = BuiltinModules.METADATA;

    private final MetadataEngine engine = new MetadataEngine();

    @Override
    public @NotNull String id() {
        return MODULE_ID;
    }

    @Override
    public int version() {
        return MetadataPayload.FORMAT;
    }

    @Override
    public @NotNull List<String> dependencies() {
        // the two names carry no ordering requirement against other modules
        return List.of();
    }

    @Override
    public @NotNull CompletionStage<MetadataPayload> capture(@NotNull Player player) {
        try {
            return CompletableFuture.completedFuture(engine.capture(new BukkitProfileOps(player)));
        } catch (RuntimeException e) {
            var failure = new IllegalStateException("failed to capture metadata for player " + player.getUniqueId(), e);
            LOGGER.error("capture failed for player {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public @NotNull CompletionStage<Void> restore(@NotNull Player player, @NotNull MetadataPayload data) {
        try {
            RestoreReport report = engine.restore(new BukkitProfileOps(player), data);
            if (report.hadFailures()) {
                // snapshot is corrupt for at least one key — the runner must see a loud failure
                throw new IllegalStateException("failed to restore keys: " + String.join(", ", report.failed()));
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            var failure = new IllegalStateException("failed to restore metadata for player " + player.getUniqueId(), e);
            LOGGER.error("restore failed for player {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public @NotNull CompletionStage<ValidationResult> validate(@NotNull Player player, @NotNull MetadataPayload data) {
        return CompletableFuture.completedFuture(MetadataPayloadValidation.validate(data));
    }
}
