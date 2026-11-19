package lol.candyriya.continuum.paper.gate;

import lol.candyriya.continuum.paper.PaperMessages;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * The gate's listeners. One instance per armed gate; unregistered by {@link RestoreGate#close()}.
 *
 * <p>Every cancel-set handler runs at {@code LOWEST} and asks the gate whether
 * the player is currently held — a map lookup and a boolean read in the hot
 * path. Movement is reverted in place (orientation passes through); everything
 * else is cancelled outright. The pre-login refusal runs at {@code HIGHEST} so
 * other validators have had their say before we refuse entry on storage or
 * failure-cooldown grounds.</p>
 *
 * <p>The teleport whitelist is deliberate: {@code PLUGIN}-cause teleports are
 * allowed (they are how the restore repositions players); everything else —
 * pearls, chorus, portal transport, commands, dismounts — is refused while the
 * player is held. Portal events are {@link PlayerTeleportEvent}s and share the
 * rule.</p>
 */
public final class JoinGateListeners implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(JoinGateListeners.class);

    private final RestoreGate gate;
    private final Plugin plugin;
    private final PaperMessages messages;

    JoinGateListeners(RestoreGate gate, Plugin plugin, PaperMessages messages) {
        this.gate = gate;
        this.plugin = plugin;
        this.messages = messages;
    }

    // ---------- join / pre-login ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        if (!gate.isEnabled()) {
            return;
        }
        try {
            gate.decideSpawn(event);
        } catch (Throwable t) {
            // a spawn decision is never worth failing a login over
            LOGGER.error("spawn decision failed; keeping the platform's spawn location", t);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!gate.isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        try {
            gate.enqueueRestore(player);
        } catch (Throwable t) {
            // fail-closed: a player we cannot hold is a player we do not admit onto half-restored state
            LOGGER.error("join gate could not freeze {}; kicking to stay fail-closed", player.getName(), t);
            try {
                player.kick(messages.renderForPlayer(player, "player.restore-failed"));
            } catch (Throwable e) {
                LOGGER.error("kick after a gate failure also failed for {}", player.getName(), e);
            }
        }
    }

    /** Pre-login refusal: restore-failure cooldown and degraded storage. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!gate.isEnabled()) {
            return;
        }
        UUID playerId = event.getUniqueId();
        if (gate.isRefused(playerId)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    messages.render("player.restore-failed"));
            LOGGER.info("refused login for {} — restore-failure cooldown ({}s remaining)", playerId, gate.refusedSecondsRemaining(playerId));
            return;
        }
        if (gate.disallowOnStorageDegraded() && gate.isStorageDegraded()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    messages.render("sync.error.database"));
            LOGGER.warn("refused login for {} — storage is degraded", playerId);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerRespawnEvent event) {
        try {
            gate.onRespawn(event);
        } catch (Throwable t) {
            LOGGER.error("respawn re-application failed for {}", event.getPlayer().getName(), t);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        try {
            gate.onQuit(event.getPlayer().getUniqueId());
        } catch (Throwable t) {
            LOGGER.error("could not close the gate session for {}", event.getPlayer().getName(), t);
        }
    }

    // ---------- movement & teleportation ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        // teleports have their own handler with the cause whitelist
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        if (!gate.isRestoring(event.getPlayer().getUniqueId())) {
            return;
        }
        // rotation-only updates pass through; position changes are reverted in place
        if (event.hasChangedBlock()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!gate.isRestoring(event.getPlayer().getUniqueId())) {
            return;
        }
        // PLUGIN is the cause our restore path uses — everything else would let a
        // held player bypass the hold (pearls, chorus, portals, commands, dismounts)
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.setCancelled(true);
        }
    }

    // ---------- interaction family ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---------- items ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // Paper has no dedicated item-pickup event in 26.3; the attempt event is the one
    // the platform fires. class-level deprecated for removal — kept deliberately, no replacement exists.
    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings({"deprecation", "removal"})
    public void onPickupItem(PlayerAttemptPickupItemEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditBook(PlayerEditBookEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // deprecated for removal ([removal] lint), zero replacement in 26.3 — kept deliberately to block the sign editor while gated
    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("removal")
    public void onSignOpen(PlayerSignOpenEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---------- world interactions ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onShear(PlayerShearEntityEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFish(PlayerFishEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRiptide(PlayerRiptideEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onShootBow(EntityShootBowEvent event) {
        Entity shooter = event.getEntity();
        if (shooter instanceof Player player && gate.isRestoring(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---------- damage ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && gate.isRestoring(player.getUniqueId())) {
            // the frozen player is invulnerable; this closes the remaining paths (e.g. void)
            event.setCancelled(true);
        }
    }

    // ---------- chat & commands ----------

    // String-based chat command blocking is intentional: the player is frozen, not silenced
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (gate.isRestoring(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---------- inventories ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!gate.isRestoring(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        // some GUIs force-open past the cancel — shut them again next tick
        player.getScheduler().execute(plugin, player::closeInventory, () -> { }, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && gate.isRestoring(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && gate.isRestoring(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
