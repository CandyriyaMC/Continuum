package lol.candyriya.continuum.paper.modules.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadsTest {

    @Test
    @DisplayName("inventory roundtrip: bytes → payload → base64 → bytes restores byte equality in every section")
    void inventoryRoundtrip() {
        byte[] main = bytes(1, 2, 3, 127, 0, 255);
        byte[] armor = bytes(9, 8, 7);
        byte[] extra = bytes(42);
        byte[] crafting = bytes(11, 22, 33, 44, 55);
        byte[] cursor = bytes(99);
        var audit = ItemAuditSummary.EMPTY;

        var payload = Payloads.inventory(main, armor, extra, crafting, cursor, audit);

        assertEquals(PayloadMigrations.CURRENT_FORMAT, payload.format());
        assertEquals("", Base64Codec.encode(new byte[0])); // sanity: known-empty encoding
        org.junit.jupiter.api.Assertions.assertArrayEquals(main, Base64Codec.decode("main", payload.main()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(armor, Base64Codec.decode("armor", payload.armor()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(extra, Base64Codec.decode("extra", payload.extra()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(crafting, Base64Codec.decode("crafting", payload.crafting()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(cursor, Base64Codec.decode("cursor", payload.cursor()));
    }

    @Test
    @DisplayName("ender chest roundtrip: bytes → payload → base64 → bytes restores byte equality")
    void enderChestRoundtrip() {
        byte[] items = bytes(100, 200, 3, 0);
        var payload = Payloads.enderChest(items, ItemAuditSummary.EMPTY);

        assertEquals(PayloadMigrations.CURRENT_FORMAT, payload.format());
        org.junit.jupiter.api.Assertions.assertArrayEquals(items, Base64Codec.decode("items", payload.items()));
    }

    @Test
    @DisplayName("payload with a null section is rejected at construction — half-payloads cannot exist")
    void rejectsNullSections() {
        assertThrows(NullPointerException.class,
                () -> Payloads.inventory(null, new byte[0], new byte[0], new byte[0], new byte[0], ItemAuditSummary.EMPTY));
        assertThrows(NullPointerException.class,
                () -> Payloads.enderChest(null, ItemAuditSummary.EMPTY));
    }

    @Test
    @DisplayName("record compact constructor rejects negative format")
    void rejectsNegativeFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryPayload(-1, "", "", "", "", "", ItemAuditSummary.EMPTY));
        assertThrows(IllegalArgumentException.class,
                () -> new EnderChestPayload(-1, "", ItemAuditSummary.EMPTY));
    }

    @Test
    @DisplayName("record compact constructor tolerates a null audit (it is advisory)")
    void nullAuditNormalizesToEmpty() {
        var payload = new InventoryPayload(1, "", "", "", "", "", null);
        assertEquals(ItemAuditSummary.EMPTY, payload.audit());
    }

    @Test
    @DisplayName("record rejects null sections at construction")
    void recordRejectsNullSections() {
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryPayload(1, null, "", "", "", "", ItemAuditSummary.EMPTY));
        assertThrows(IllegalArgumentException.class,
                () -> new EnderChestPayload(1, null, ItemAuditSummary.EMPTY));
    }

    @Test
    @DisplayName("wire keys are the documented snake_case names — snapshot compatibility depends on them")
    void wireNamesAreStable() throws Exception {
        // contract with common's Jackson SNAKE_CASE mapping — renaming a record component breaks the wire
        var fields = InventoryPayload.class.getRecordComponents();
        var names = java.util.Arrays.stream(fields).map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("format", "main", "armor", "extra", "crafting", "cursor", "audit"), names);

        var enderFields = EnderChestPayload.class.getRecordComponents();
        var enderNames = java.util.Arrays.stream(enderFields).map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("format", "items", "audit"), enderNames);

        var auditFields = ItemAuditSummary.class.getRecordComponents();
        var auditNames = java.util.Arrays.stream(auditFields).map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("stacks", "items", "totalBytes", "materials", "componentTypes", "errors"), auditNames);
    }

    private static byte[] bytes(int... values) {
        var bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
