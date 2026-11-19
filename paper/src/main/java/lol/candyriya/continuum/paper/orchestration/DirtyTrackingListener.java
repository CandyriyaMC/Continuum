package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Platform events that mark dirty-tracked modules.
 *
 * <p>MONITOR priority with no cancellation and no mutation — the listener is a
 * pure observer. Bukkit fires identical events for players in any state, so
 * held players in the restore window get their flags set too; that is harmless
 * (extra re-capture) and preferable to enumerating "who may have changed".</p>
 *
 * <p>Ender chest clicks are attributed through the open view: when the top
 * inventory is an ender chest the click marks {@code continuum.enderchest},
 * otherwise {@code continuum.inventory}.</p>
 */
public final class DirtyTrackingListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirtyTrackingListener.class);

    private final DirtyTracker dirty;

    public DirtyTrackingListener(DirtyTracker dirty) {
        this.dirty = dirty;
    }

    // ---------- inventory family ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        try {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            boolean enderChest = event.getView() != null
                    && event.getView().getTopInventory() != null
                    && event.getView().getTopInventory().getType() == InventoryType.ENDER_CHEST;
            dirty.markDirty(player.getUniqueId(),
                    enderChest ? BuiltinModules.ENDERCHEST : BuiltinModules.INVENTORY);
        } catch (Throwable t) {
            LOGGER.debug("could not mark dirty from inventory click", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        try {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            boolean enderChest = event.getView() != null
                    && event.getView().getTopInventory() != null
                    && event.getView().getTopInventory().getType() == InventoryType.ENDER_CHEST;
            dirty.markDirty(player.getUniqueId(),
                    enderChest ? BuiltinModules.ENDERCHEST : BuiltinModules.INVENTORY);
        } catch (Throwable t) {
            LOGGER.debug("could not mark dirty from inventory drag", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        try {
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            if (event.getView() != null && event.getView().getTopInventory() != null
                    && event.getView().getTopInventory().getType() == InventoryType.ENDER_CHEST) {
                dirty.markDirty(player.getUniqueId(), BuiltinModules.ENDERCHEST);
            } else {
                dirty.markDirty(player.getUniqueId(), BuiltinModules.INVENTORY);
            }
        } catch (Throwable t) {
            LOGGER.debug("could not mark dirty from inventory open", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        markSafely(event.getPlayer().getUniqueId(), BuiltinModules.INVENTORY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent event) {
        markSafely(event.getPlayer().getUniqueId(), BuiltinModules.INVENTORY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        markSafely(event.getPlayer().getUniqueId(), BuiltinModules.INVENTORY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("removal")
    public void onPickupItem(PlayerAttemptPickupItemEvent event) {
        markSafely(event.getPlayer().getUniqueId(), BuiltinModules.INVENTORY);
    }

    // ---------- vitals family ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            markSafely(player.getUniqueId(), BuiltinModules.VITALS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player) {
            markSafely(player.getUniqueId(), BuiltinModules.VITALS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFoodLevel(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            markSafely(player.getUniqueId(), BuiltinModules.VITALS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAirChange(EntityAirChangeEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player) {
            markSafely(player.getUniqueId(), BuiltinModules.VITALS);
        }
    }

    // ---------- experience ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExpChange(PlayerExpChangeEvent event) {
        markSafely(event.getPlayer().getUniqueId(), BuiltinModules.EXPERIENCE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void onArmorChange(com.destroystokyo.paper.event.player.PlayerArmorChangeEvent event) {
        // equipment carries attribute modifiers — a swap is an attributes change
        markSafely(event.getPlayer().getUniqueId(), BuiltinModules.ATTRIBUTES);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        markSafely(event.getPlayer().getUniqueId(), BuiltinModules.EXPERIENCE);
    }

    // ---------- effects ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player) {
            markSafely(player.getUniqueId(), BuiltinModules.EFFECTS);
        }
    }

    private void markSafely(UUID playerId, String moduleId) {
        try {
            dirty.markDirty(playerId, moduleId);
        } catch (Throwable t) {
            LOGGER.debug("could not mark {} dirty for {}", moduleId, playerId, t);
        }
    }
}
