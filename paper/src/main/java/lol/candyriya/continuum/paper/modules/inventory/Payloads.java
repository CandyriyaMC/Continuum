package lol.candyriya.continuum.paper.modules.inventory;

import java.util.Objects;

/**
 * Factory for the payload records. Keeps the base64 assembly in one place so a capture
 * cannot accidentally produce a half-encoded section, and so tests can drive the same
 * code path with synthetic byte arrays.
 */
final class Payloads {

    private Payloads() {
        // static access only
    }

    /** Builds an inventory payload, encoding every section; audit is external. */
    static InventoryPayload inventory(byte[] main, byte[] armor, byte[] extra,
                                      byte[] crafting, byte[] cursor,
                                      ItemAuditSummary audit) {
        Objects.requireNonNull(main, "main must not be null (empty section = empty byte array, not null)");
        Objects.requireNonNull(armor, "armor must not be null (empty section = empty byte array, not null)");
        Objects.requireNonNull(extra, "extra must not be null (empty section = empty byte array, not null)");
        Objects.requireNonNull(crafting, "crafting must not be null (empty section = empty byte array, not null)");
        Objects.requireNonNull(cursor, "cursor must not be null (empty section = empty byte array, not null)");
        return new InventoryPayload(PayloadMigrations.CURRENT_FORMAT,
                Base64Codec.encode(main), Base64Codec.encode(armor), Base64Codec.encode(extra),
                Base64Codec.encode(crafting), Base64Codec.encode(cursor),
                audit == null ? ItemAuditSummary.EMPTY : audit);
    }

    /** Builds an ender chest payload, encoding the single section; audit is external. */
    static EnderChestPayload enderChest(byte[] items, ItemAuditSummary audit) {
        Objects.requireNonNull(items, "items must not be null (empty section = empty byte array, not null)");
        return new EnderChestPayload(PayloadMigrations.CURRENT_FORMAT,
                Base64Codec.encode(items),
                audit == null ? ItemAuditSummary.EMPTY : audit);
    }
}
