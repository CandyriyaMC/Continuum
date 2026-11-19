package lol.candyriya.continuum.paper.modules.inventory;

/**
 * Payload for the ender chest module ({@code continuum.enderchest}).
 *
 * <p>Kept deliberately apart from {@link InventoryPayload}: the ender chest is one flat section
 * with no grid, no cursor and no fold semantics, and it has its own enable toggle in the config
 * ({@code sync.modules.ender-chest}). Splitting it means the toggle maps to exactly one module,
 * and neither half drags the other along.</p>
 *
 * <p>Sections are standard base64 of {@code ItemStack.serializeItemsAsBytes(...)} output.
 * {@code items} decodes to exactly the ender chest's slot count (27 on vanilla).</p>
 *
 * @param format payload format, must be {@value PayloadMigrations#CURRENT_FORMAT}
 * @param items  base64-encoded serialized ender chest contents (slot count preserved)
 * @param audit  human-readable summary; advisory only, never read by the restore path
 */
public record EnderChestPayload(int format, String items, ItemAuditSummary audit) {

    public EnderChestPayload {
        if (format < 0) {
            throw new IllegalArgumentException("format must be >= 0, got " + format);
        }
        if (items == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        if (audit == null) {
            audit = ItemAuditSummary.EMPTY;
        }
    }
}
