package lol.candyriya.continuum.paper.modules.inventory;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Synchronizes the player's ender chest — one flat section, no grid, no cursor.
 *
 * <p><b>Module id:</b> {@code continuum.enderchest} (from {@link BuiltinModules#ENDERCHEST}),
 * payload format {@value PayloadMigrations#CURRENT_FORMAT}, schema version 1. The config toggle
 * is {@code sync.modules.ender-chest} ({@code KnownModules.ENDER_CHEST}); enablement is a
 * registration-time decision (see {@link InventoryModuleToggles}).</p>
 *
 * <p><b>Why a separate module instead of a field on {@link InventoryModule}:</b> two BuiltinModules
 * ids exist ({@code continuum.inventory}, {@code continuum.enderchest}) and the config exposes the
 * ender chest as its own toggle. One module per id keeps the registry 1:1, so the toggle maps to
 * exactly one place and admins can flip the ender chest off without losing inventory sync. The
 * serialization plumbing is shared via package-private helpers.</p>
 *
 * <p><b>Threading.</b> Same contract as every Continuum module: {@link #capture}, {@link #restore}
 * and {@link #validate} run on the player's owning thread (Paper main thread / Folia region
 * thread); {@code id()}/{@code version()}/{@code priority()} are any-thread constants. The
 * lifecycle methods tripwire-check the calling thread.</p>
 *
 * <p><b>Serialization.</b> {@code ItemStack.serializeItemsAsBytes(...)} per section → base64.
 * Stack counts survive untouched, nested containers ride inside the item bytes, nothing is
 * reconstructed component-by-component.</p>
 *
 * <p><b>Restore.</b> Clears the chest first (setContents replaces wholesale — idempotent),
 * applied atomically: the payload is fully decoded and the plan validated before any write.
 * Strict mode (default) rejects slot-count mismatches; lenient mode pads/truncates. Post-write,
 * the chest is re-read and stack/item totals are compared against the decode — a silent loss
 * fails the restore in strict mode and logs an error with the counts in lenient mode.</p>
 *
 * <p><b>Audit.</b> Every capture attaches an advisory {@link ItemAuditSummary} to the payload;
 * restore never reads it.</p>
 */
public final class EnderChestModule implements SyncModule<Player, EnderChestPayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnderChestModule.class);

    private final boolean lenientValidation;

    /** Strict mode: any shape anomaly rejects the payload. */
    public EnderChestModule() {
        this(false);
    }

    /**
     * @param lenientValidation when true, slot-count mismatches pad/truncate instead of
     *                          rejecting (format/decode failures are always fatal); post-write
     *                          anomalies log instead of failing the restore
     */
    public EnderChestModule(boolean lenientValidation) {
        this.lenientValidation = lenientValidation;
    }

    @Override
    public String id() {
        return BuiltinModules.ENDERCHEST;
    }

    @Override
    public int version() {
        return PayloadMigrations.CURRENT_FORMAT;
    }

    @Override
    public CompletionStage<EnderChestPayload> capture(Player player) {
        Objects.requireNonNull(player, "player");
        try {
            EntityAffinity.requireEntityThread(player, "ender chest capture");
            return CompletableFuture.completedFuture(doCapture(player));
        } catch (Exception e) {
            LOGGER.error("ender chest capture failed for {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<Void> restore(Player player, EnderChestPayload data) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(data, "data");
        try {
            EntityAffinity.requireEntityThread(player, "ender chest restore");
            doRestore(player, data);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            LOGGER.error("ender chest restore failed for {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, EnderChestPayload data) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(data, "data");
        try {
            EntityAffinity.requireEntityThread(player, "ender chest validate");
            return CompletableFuture.completedFuture(doValidate(player, data));
        } catch (Exception e) {
            LOGGER.error("ender chest validation could not run for {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // ---------- capture ----------

    private EnderChestPayload doCapture(Player player) {
        Inventory chest = player.getEnderChest();
        ItemStack[] contents = chest.getContents();
        byte[] itemsBytes = serialize("items", contents);
        var audit = ItemAudit.summarize(contents, itemsBytes.length);
        var payload = Payloads.enderChest(itemsBytes, audit);
        LOGGER.debug("captured ender chest for {}: stacks={} items={} bytes={}",
                player.getUniqueId(), audit.stacks(), audit.items(), audit.totalBytes());
        return payload;
    }

    // ---------- restore ----------

    private void doRestore(Player player, EnderChestPayload data) {
        PayloadMigrations.requireSupported(data.format());

        // decode first — nothing is written until the payload is proven applicable
        ItemStack[] decoded = Sections.decodeItems("items", data.items());
        Inventory chest = player.getEnderChest();
        var actual = new java.util.LinkedHashMap<String, Integer>();
        actual.put("items", decoded.length);
        var expected = new java.util.LinkedHashMap<String, Integer>();
        expected.put("items", chest.getSize());
        var plan = RestorePlan.of(actual, expected, lenientValidation);
        if (!plan.applicable()) {
            throw new InventoryStateException("ender chest payload cannot be applied — " + plan.describeRejected());
        }
        String lossy = plan.describeLossy();
        if (lossy != null) {
            LOGGER.warn("restoring ender chest for {} with lossy truncation: {}", player.getUniqueId(), lossy);
        }
        var action = plan.sections().get(0).action();
        ItemStack[] adjusted = Sections.adjust(decoded, expected.get("items"), action);

        // setContents replaces wholesale — clearing is implicit and restore stays idempotent
        chest.setContents(adjusted);

        verifyRestored(player, chest, adjusted);
        LOGGER.debug("ender chest restored for {}", player.getUniqueId());
    }

    private void verifyRestored(Player player, Inventory chest, ItemStack[] expectedContents) {
        ItemStack[] live = chest.getContents();
        long expectedItems = Sections.countItems(expectedContents);
        int expectedStacks = Sections.countStacks(expectedContents);
        long liveItems = Sections.countItems(live);
        int liveStacks = Sections.countStacks(live);
        if (liveItems != expectedItems || liveStacks != expectedStacks) {
            String detail = "post-restore ender chest verification failed for " + player.getUniqueId()
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

    private ValidationResult doValidate(Player player, EnderChestPayload data) {
        try {
            PayloadMigrations.requireSupported(data.format());
        } catch (InventoryStateException e) {
            return ValidationResult.invalid(e.getMessage());
        }

        try {
            Base64Codec.decode("items", data.items());
        } catch (InventoryStateException e) {
            return ValidationResult.invalid(e.getMessage());
        }

        final ItemStack[] decoded;
        try {
            decoded = Sections.decodeItems("items", data.items());
        } catch (InventoryStateException e) {
            return ValidationResult.invalid(e.getMessage());
        }

        var actual = new java.util.LinkedHashMap<String, Integer>();
        actual.put("items", decoded.length);
        var expected = new java.util.LinkedHashMap<String, Integer>();
        expected.put("items", player.getEnderChest().getSize());
        var plan = RestorePlan.of(actual, expected, lenientValidation);
        if (!plan.applicable()) {
            return ValidationResult.invalid(plan.describeRejected());
        }
        return ValidationResult.ok();
    }

    private static byte[] serialize(String section, ItemStack[] items) {
        try {
            return ItemStack.serializeItemsAsBytes(items);
        } catch (Exception e) {
            throw new InventoryStateException("section '" + section + "' failed to serialize: " + e.getMessage(), e);
        }
    }
}
