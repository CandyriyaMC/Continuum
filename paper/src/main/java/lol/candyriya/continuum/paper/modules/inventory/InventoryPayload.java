package lol.candyriya.continuum.paper.modules.inventory;

/**
 * Payload for the inventory module ({@code continuum.inventory}).
 *
 * <p>Wire shape (snake_case via the shared Jackson mapper, {@code byte[]} as base64 strings):
 * {@code {"format":1,"main":"...","armor":"...","extra":"...","crafting":"...","cursor":"...","audit":{...}}}.</p>
 *
 * <p>Each section holds the output of {@code ItemStack.serializeItemsAsBytes(...)} for one
 * logical slot group, base64-encoded for JSON:</p>
 * <ul>
 *   <li>{@code main} — the player's storage contents, exactly {@code getStorageContents().length}
 *       slots (36 on vanilla; hotbar included — storage, not the legacy 27+9 split);</li>
 *   <li>{@code armor} — {@code getArmorContents()}, boots→helmet order as the platform returns it;</li>
 *   <li>{@code extra} — {@code getExtraContents()}, the offhand on vanilla (1 slot);</li>
 *   <li>{@code crafting} — the internal 2×2 crafting grid's leftover matrix, 4 slots including
 *       nulls; normally empty because grid items are folded into storage at capture, and only
 *       anything that did not fit survives here;</li>
 *   <li>{@code cursor} — whatever rides the cursor that did not fit the inventory, 1 slot;</li>
 *   <li>{@code audit} — {@link ItemAuditSummary}, advisory only.</li>
 * </ul>
 *
 * <p>Container contents (shulker boxes, bundles) are <em>not</em> special-cased: they live
 * inside the item bytes and roundtrip automatically. Stack counts, including counts above a
 * stack's natural max (modded servers do this), are preserved by the byte format — nothing
 * is clamped anywhere in this pipeline.</p>
 *
 * @param format    payload format, must be {@value PayloadMigrations#CURRENT_FORMAT}
 * @param main      base64-encoded serialized storage contents
 * @param armor     base64-encoded serialized armor contents
 * @param extra     base64-encoded serialized extra contents (offhand)
 * @param crafting  base64-encoded serialized internal 2×2 grid leftovers, 4 slots
 * @param cursor    base64-encoded serialized cursor leftover, 1 slot
 * @param audit     human-readable summary; advisory only, never read by the restore path
 */
public record InventoryPayload(int format, String main, String armor, String extra,
                               String crafting, String cursor, ItemAuditSummary audit) {

    public InventoryPayload {
        if (format < 0) {
            throw new IllegalArgumentException("format must be >= 0, got " + format);
        }
        if (main == null || armor == null || extra == null || crafting == null || cursor == null) {
            throw new IllegalArgumentException("payload sections must not be null");
        }
        if (audit == null) {
            audit = ItemAuditSummary.EMPTY;
        }
    }
}
