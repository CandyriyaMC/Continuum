package lol.candyriya.continuum.paper.modules.inventory;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Synchronizes the player's storage inventory, armor, extra (offhand) slots, the internal
 * 2×2 crafting grid and the cursor item.
 *
 * <p><b>Module id:</b> {@code continuum.inventory} (from {@link BuiltinModules#INVENTORY}),
 * payload format {@value PayloadMigrations#CURRENT_FORMAT}, schema version 1.</p>
 *
 * <p><b>Threading.</b> {@link #capture}, {@link #restore} and {@link #validate} are entity-affine:
 * Continuum invokes them on the player's owning thread — the main thread on Paper, the player's
 * region thread on Folia. Everything here reads and mutates live player state directly and never
 * hops threads. {@code id()}, {@code version()}, {@code priority()} and {@code dependencies()}
 * are constants callable from any thread. The lifecycle methods tripwire-check the calling
 * thread and fail loudly rather than corrupting state from the wrong one.</p>
 *
 * <p><b>Serialization.</b> ItemStacks never roundtrip through DTOs on this path. Every section
 * is the output of {@code ItemStack.serializeItemsAsBytes(...)} (public API, vanilla data
 * converter, forward-migrating), base64-encoded for the JSON payload. Components of every kind
 * ride along: enchantments, custom names, lore, durability, potion contents, PDC, shulker boxes
 * and nested containers, bundles — everything the format knows, nothing is reconstructed
 * component-by-component. Counts — including counts above a stack's natural max on modded
 * servers — are preserved; nothing is clamped. Map items keep their {@code map_id} reference;
 * the pixel data lives in the world folder, not the item, so identical maps restored to a
 * backend without that map file render blank. That is a storage-topology fact, not a bug here.</p>
 *
 * <p><b>Open inventory handling on capture.</b> If a non-player container is open (chest, furnace,
 * workbench, merchant, any GUI): its contents are <em>not</em> transferred (they belong to the
 * block or the GUI's owner); the view is force-closed with {@code PLUGIN} reason instead —
 * closing a crafting-like foreign window returns its items to the player (or drops them at the
 * player's feet when the inventory is full), which is vanilla's close semantics and is visible
 * to the player, never silent. The internal 2×2 grid and the cursor item are then folded into
 * the player's own inventory (via {@code Inventory#addItem}, order: matrix slots 0–3, then the
 * cursor). Anything that does not fit survives in the {@code crafting}/{@code cursor} sections
 * of the payload and travels with the snapshot. Folded items become ordinary inventory contents.
 * The open window is never re-opened after a restore.</p>
 *
 * <p><b>The crafting result slot is deliberately not captured.</b> In vanilla it holds only a
 * recomputable preview of the matrix — capturing it would duplicate the item on restore.
 * The matrix (the real items) is always captured. A plugin that stows an unrelated item into
 * the result slot would lose it; that pathological case is accepted in favor of never mass-
 * duplicating a crafting preview.</p>
 *
 * <p><b>Restore.</b> Restore is effectively all-or-nothing: the payload is fully decoded and the
 * plan validated <em>before</em> any live write, so a rejected payload leaves the player
 * untouched. Writes then go storage → armor → extra → crafting matrix → cursor. In strict mode
 * (the default) any slot-count mismatch rejects the payload; in lenient mode a short section is
 * padded with empty slots and a long one is truncated (lossy, logged). Either way the live
 * state is re-read afterwards and the stack/item totals are compared against what was decoded —
 * a silent loss is impossible; a mismatch in strict mode fails the restore loudly, in lenient
 * mode it logs an error with the counts and leaves the state as written.</p>
 *
 * <p><b>Audit.</b> Every capture attaches an {@link ItemAuditSummary} to the payload for
 * logging/metrics. It is advisory only — restore never reads it, validation never fails on it.</p>
 */
public final class InventoryModule implements SyncModule<Player, InventoryPayload> {

    /** The internal crafting grid always has this many matrix slots. */
    static final int CRAFTING_SLOTS = 4;

    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryModule.class);

    private final boolean lenientValidation;

    /** Strict mode: any shape anomaly rejects the payload. */
    public InventoryModule() {
        this(false);
    }

    /**
     * @param lenientValidation when true, slot-count mismatches pad/truncate instead of
     *                          rejecting the payload (format/decode failures are always fatal);
     *                          post-write anomalies log instead of failing the restore
     */
    public InventoryModule(boolean lenientValidation) {
        this.lenientValidation = lenientValidation;
    }

    @Override
    public String id() {
        return BuiltinModules.INVENTORY;
    }

    @Override
    public int version() {
        // bump together with PayloadMigrations.CURRENT_FORMAT for incompatible shape changes
        return PayloadMigrations.CURRENT_FORMAT;
    }

    @Override
    public CompletionStage<InventoryPayload> capture(Player player) {
        Objects.requireNonNull(player, "player");
        try {
            EntityAffinity.requireEntityThread(player, "inventory capture");
            return CompletableFuture.completedFuture(doCapture(player));
        } catch (Exception e) {
            LOGGER.error("inventory capture failed for {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<Void> restore(Player player, InventoryPayload data) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(data, "data");
        try {
            EntityAffinity.requireEntityThread(player, "inventory restore");
            doRestore(player, data);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            LOGGER.error("inventory restore failed for {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, InventoryPayload data) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(data, "data");
        try {
            EntityAffinity.requireEntityThread(player, "inventory validate");
            return CompletableFuture.completedFuture(doValidate(player, data));
        } catch (Exception e) {
            // an off-thread validate means the platform broke the contract, not that the data is bad
            LOGGER.error("inventory validation could not run for {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // ---------- capture ----------

    private InventoryPayload doCapture(Player player) {
        var inventory = player.getInventory();

        // 1) normalize the open view: close whatever foreign container is up, keep the internal grid
        InventoryView view = player.getOpenInventory();
        Inventory top = topOf(view);
        var kind = ViewResolution.resolve(view != null,
                top != null && top.getType() == InventoryType.CRAFTING,
                top instanceof CraftingInventory crafting ? crafting.getMatrix().length : -1);
        if (kind == ViewResolution.ViewKind.FOREIGN_CONTAINER) {
            // the container keeps its contents — we do not transfer them, we only close the window
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            view = player.getOpenInventory();
            top = topOf(view);
            kind = ViewResolution.resolve(view != null,
                    top != null && top.getType() == InventoryType.CRAFTING,
                    top instanceof CraftingInventory crafting ? crafting.getMatrix().length : -1);
            if (kind != ViewResolution.ViewKind.INTERNAL_GRID) {
                // a plugin re-opened something on closeInventory — nothing we can safely snapshot; capture proceeds without the grid
                LOGGER.warn("inventory view for {} is still not the internal grid after PLUGIN close; grid contents are left untouched", player.getUniqueId());
                top = null;
            }
        }

        // 2) snapshot the fold sources (clone — getMatrix/getItemOnCursor hand out live references)
        CraftingInventory crafting = top instanceof CraftingInventory c && c.getMatrix().length == CRAFTING_SLOTS ? c : null;
        ItemStack[] matrix = crafting != null ? cloneStacks(crafting.getMatrix()) : new ItemStack[CRAFTING_SLOTS];
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null) {
            cursor = cursor.clone();
        }

        // 3) fold matrix slots in order, then the cursor, into the player's inventory
        ItemStack[] gridLeftovers = new ItemStack[CRAFTING_SLOTS];
        for (int i = 0; i < CRAFTING_SLOTS; i++) {
            ItemStack slot = matrix[i];
            if (slot == null) {
                continue;
            }
            gridLeftovers[i] = addLeftover(inventory, slot);
        }
        ItemStack cursorLeftover = cursor == null ? null : addLeftover(inventory, cursor);

        // 4) write the fold sources back unconditionally — it is idempotent: folded items left
        //    the grid/cursor, unfolded ones are set right back. no flag needed, no duplication possible
        if (crafting != null) {
            crafting.setMatrix(gridLeftovers);
        }
        player.setItemOnCursor(cursorLeftover);

        // 5) serialize — after the fold, storage holds the folded items
        ItemStack[] storage = inventory.getStorageContents();
        ItemStack[] armor = inventory.getArmorContents();
        ItemStack[] extra = inventory.getExtraContents();
        byte[] mainBytes = serialize("main", storage);
        byte[] armorBytes = serialize("armor", armor);
        byte[] extraBytes = serialize("extra", extra);
        byte[] craftingBytes = serialize("crafting", gridLeftovers);
        byte[] cursorBytes = serialize("cursor", new ItemStack[] {cursorLeftover});

        // 6) advisory audit across all sections, never authoritative
        var audit = auditOf(storage, armor, extra, gridLeftovers, new ItemStack[] {cursorLeftover},
                mainBytes, armorBytes, extraBytes, craftingBytes, cursorBytes);

        var payload = Payloads.inventory(mainBytes, armorBytes, extraBytes, craftingBytes, cursorBytes, audit);
        LOGGER.debug("captured inventory for {}: stacks={} items={} bytes={}",
                player.getUniqueId(), audit.stacks(), audit.items(), audit.totalBytes());
        return payload;
    }

    private ItemAuditSummary auditOf(ItemStack[] storage, ItemStack[] armor, ItemStack[] extra,
                                     ItemStack[] gridLeftovers, ItemStack[] cursor,
                                     byte[] mainBytes, byte[] armorBytes, byte[] extraBytes,
                                     byte[] craftingBytes, byte[] cursorBytes) {
        long bytes = Sections.totalBytes(mainBytes, armorBytes, extraBytes, craftingBytes, cursorBytes);
        var combined = new ItemStack[storage.length + armor.length + extra.length + gridLeftovers.length + cursor.length];
        int at = 0;
        System.arraycopy(storage, 0, combined, at, storage.length);
        at += storage.length;
        System.arraycopy(armor, 0, combined, at, armor.length);
        at += armor.length;
        System.arraycopy(extra, 0, combined, at, extra.length);
        at += extra.length;
        System.arraycopy(gridLeftovers, 0, combined, at, gridLeftovers.length);
        at += gridLeftovers.length;
        System.arraycopy(cursor, 0, combined, at, cursor.length);
        return ItemAudit.summarize(combined, bytes);
    }

    // ---------- restore ----------

    private void doRestore(Player player, InventoryPayload data) {
        PayloadMigrations.requireSupported(data.format());

        // decode EVERYTHING first — a rejected payload must not leave a half-written inventory behind
        ItemStack[] storage = Sections.decodeItems("main", data.main());
        ItemStack[] armor = Sections.decodeItems("armor", data.armor());
        ItemStack[] extra = Sections.decodeItems("extra", data.extra());
        ItemStack[] crafting = Sections.decodeItems("crafting", data.crafting());
        ItemStack[] cursor = Sections.decodeItems("cursor", data.cursor());

        // defensive: no clamping to natural stack limits anywhere — counts (even > max) survive untouched

        var inventory = player.getInventory();
        var actual = new java.util.LinkedHashMap<String, Integer>();
        actual.put("main", storage.length);
        actual.put("armor", armor.length);
        actual.put("extra", extra.length);
        actual.put("crafting", crafting.length);
        actual.put("cursor", cursor.length);
        var expected = new java.util.LinkedHashMap<String, Integer>();
        expected.put("main", inventory.getStorageContents().length);
        expected.put("armor", inventory.getArmorContents().length);
        expected.put("extra", inventory.getExtraContents().length);
        expected.put("crafting", CRAFTING_SLOTS);
        expected.put("cursor", 1);
        var plan = RestorePlan.of(actual, expected, lenientValidation);
        if (!plan.applicable()) {
            throw new InventoryStateException("payload cannot be applied — " + plan.describeRejected());
        }
        String lossy = plan.describeLossy();
        if (lossy != null) {
            LOGGER.warn("restoring inventory for {} with lossy truncation: {}", player.getUniqueId(), lossy);
        }

        // a foreign container at restore time is an anomaly (restores run under the join gate,
        // before any window can open). closing it would return its items to storage — and our
        // storage write would then wipe them. refuse instead: nothing is written, the payload
        // stays intact, the caller decides what happens to the join.
        InventoryView view = player.getOpenInventory();
        Inventory top = topOf(view);
        var kind = ViewResolution.resolve(view != null,
                top != null && top.getType() == InventoryType.CRAFTING,
                top instanceof CraftingInventory craftingInv ? craftingInv.getMatrix().length : -1);
        if (kind == ViewResolution.ViewKind.FOREIGN_CONTAINER) {
            throw new InventoryStateException(
                    "refusing to restore inventory: a non-player container is open; close it first (restores normally run before any window opens)");
        }
        CraftingInventory grid = top instanceof CraftingInventory c && c.getMatrix().length == CRAFTING_SLOTS
                && top.getType() == InventoryType.CRAFTING ? c : null;

        ItemStack[] adjustedCrafting = adjustTo(crafting, CRAFTING_SLOTS, plan, "crafting");
        ItemStack[] adjustedCursor = adjustTo(cursor, 1, plan, "cursor");
        ItemStack[] adjustedMain = adjustTo(storage, expected.get("main"), plan, "main");
        ItemStack[] adjustedArmor = adjustTo(armor, expected.get("armor"), plan, "armor");
        ItemStack[] adjustedExtra = adjustTo(extra, expected.get("extra"), plan, "extra");

        // atomicity gate: if the payload carries grid items but no internal grid is available, nothing is written
        boolean gridHasItems = sectionsContainItems(adjustedCrafting);
        if (gridHasItems && grid == null) {
            throw new InventoryStateException(
                    "payload carries crafting-grid items but no internal 2x2 crafting view is available; refusing to drop them");
        }

        // apply: storage → armor → extra → grid → cursor (clears included — setters replace wholesale)
        inventory.setStorageContents(adjustedMain);
        inventory.setArmorContents(adjustedArmor);
        inventory.setExtraContents(adjustedExtra);
        if (grid != null) {
            grid.setMatrix(adjustedCrafting);
        }
        player.setItemOnCursor(adjustedCursor[0]);

        verifyRestored(player, inventory, grid, adjustedMain, adjustedArmor, adjustedExtra, adjustedCrafting, adjustedCursor);
        LOGGER.debug("inventory restored for {}", player.getUniqueId());
    }

    private void verifyRestored(Player player, PlayerInventory inventory, CraftingInventory grid,
                                ItemStack[] storage, ItemStack[] armor, ItemStack[] extra,
                                ItemStack[] crafting, ItemStack[] cursor) {
        ItemStack[] liveStorage = inventory.getStorageContents();
        ItemStack[] liveArmor = inventory.getArmorContents();
        ItemStack[] liveExtra = inventory.getExtraContents();
        ItemStack[] liveGrid = grid == null ? null : grid.getMatrix();
        ItemStack liveCursor = player.getItemOnCursor();

        long expectedItems = Sections.countItems(storage) + Sections.countItems(armor) + Sections.countItems(extra)
                + Sections.countItems(crafting) + Sections.countItems(cursor);
        int expectedStacks = Sections.countStacks(storage) + Sections.countStacks(armor) + Sections.countStacks(extra)
                + Sections.countStacks(crafting) + Sections.countStacks(cursor);

        long liveItems = Sections.countItems(liveStorage) + Sections.countItems(liveArmor) + Sections.countItems(liveExtra)
                + (liveGrid == null ? 0 : Sections.countItems(liveGrid))
                + (liveCursor == null ? 0 : liveCursor.getAmount());
        int liveStacks = Sections.countStacks(liveStorage) + Sections.countStacks(liveArmor) + Sections.countStacks(liveExtra)
                + (liveGrid == null ? 0 : Sections.countStacks(liveGrid))
                + (liveCursor == null ? 0 : 1);

        if (liveItems != expectedItems || liveStacks != expectedStacks) {
            String detail = "post-restore verification failed for " + player.getUniqueId()
                    + ": expected " + expectedItems + " item(s) in " + expectedStacks + " stack(s),"
                    + " live state shows " + liveItems + " item(s) in " + liveStacks + " stack(s)"
                    + " (the snapshot is intact; the platform decides the recovery path)";
            if (lenientValidation) {
                LOGGER.error("{} — continuing anyway (lenient mode)", detail);
            } else {
                throw new InventoryStateException(detail);
            }
        }
    }

    // ---------- validate ----------

    private ValidationResult doValidate(Player player, InventoryPayload data) {
        try {
            PayloadMigrations.requireSupported(data.format());
        } catch (InventoryStateException e) {
            return ValidationResult.invalid(e.getMessage());
        }

        // structure first (cheap, pure): base64 + caps — catches garbage before any NMS work happens
        List<String> structural = new ArrayList<>();
        structural.addAll(checkSection("main", data.main()));
        structural.addAll(checkSection("armor", data.armor()));
        structural.addAll(checkSection("extra", data.extra()));
        structural.addAll(checkSection("crafting", data.crafting()));
        structural.addAll(checkSection("cursor", data.cursor()));
        if (!structural.isEmpty()) {
            return ValidationResult.invalid(String.join("; ", structural));
        }

        // then the real thing: decode on this (entity) thread — corrupt bytes or components this
        // server does not understand throw right here instead of inside restore
        var decoded = new java.util.LinkedHashMap<String, ItemStack[]>();
        try {
            decoded.put("main", Sections.decodeItems("main", data.main()));
            decoded.put("armor", Sections.decodeItems("armor", data.armor()));
            decoded.put("extra", Sections.decodeItems("extra", data.extra()));
            decoded.put("crafting", Sections.decodeItems("crafting", data.crafting()));
            decoded.put("cursor", Sections.decodeItems("cursor", data.cursor()));
        } catch (InventoryStateException e) {
            return ValidationResult.invalid(e.getMessage());
        }

        var inventory = player.getInventory();
        var actual = new java.util.LinkedHashMap<String, Integer>();
        decoded.forEach((section, items) -> actual.put(section, items.length));
        var expected = new java.util.LinkedHashMap<String, Integer>();
        expected.put("main", inventory.getStorageContents().length);
        expected.put("armor", inventory.getArmorContents().length);
        expected.put("extra", inventory.getExtraContents().length);
        expected.put("crafting", CRAFTING_SLOTS);
        expected.put("cursor", 1);
        var plan = RestorePlan.of(actual, expected, lenientValidation);
        if (!plan.applicable()) {
            return ValidationResult.invalid(plan.describeRejected());
        }
        return ValidationResult.ok();
    }

    private List<String> checkSection(String section, String value) {
        try {
            Base64Codec.decode(section, value);
            return List.of();
        } catch (InventoryStateException e) {
            return List.of(e.getMessage());
        }
    }

    // ---------- small helpers ----------

    private static Inventory topOf(InventoryView view) {
        return view == null ? null : view.getTopInventory();
    }

    private static ItemStack[] cloneStacks(ItemStack[] stacks) {
        if (stacks == null) {
            return new ItemStack[CRAFTING_SLOTS];
        }
        var clone = new ItemStack[stacks.length];
        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            clone[i] = stack == null ? null : stack.clone();
        }
        return clone;
    }

    /** addItem's leftover for a single input stack, or null when the whole stack was absorbed. */
    private static ItemStack addLeftover(Inventory inventory, ItemStack stack) {
        Map<Integer, ItemStack> leftovers = inventory.addItem(stack);
        if (leftovers.isEmpty()) {
            return null;
        }
        // a single input stack yields at most one leftover entry
        return leftovers.values().iterator().next();
    }

    private static byte[] serialize(String section, ItemStack[] items) {
        try {
            return ItemStack.serializeItemsAsBytes(items);
        } catch (Exception e) {
            throw new InventoryStateException("section '" + section + "' failed to serialize: " + e.getMessage(), e);
        }
    }

    private static boolean sectionsContainItems(ItemStack[] items) {
        for (ItemStack stack : items) {
            if (stack != null) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack[] adjustTo(ItemStack[] items, int targetSlots, RestorePlan plan, String section) {
        var action = plan.sections().stream()
                .filter(s -> s.section().equals(section))
                .findFirst()
                .map(RestorePlan.SectionPlan::action)
                .orElseThrow(() -> new InventoryStateException("no restore plan for section '" + section + "'"));
        return Sections.adjust(items, targetSlots, action);
    }
}
