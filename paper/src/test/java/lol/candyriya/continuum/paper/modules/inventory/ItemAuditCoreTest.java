package lol.candyriya.continuum.paper.modules.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemAuditCoreTest {

    private static ItemAuditCore.ItemAuditRow row(String material, int amount, List<String> components) {
        return new ItemAuditCore.ItemAuditRow(material, amount, components);
    }

    @Test
    @DisplayName("empty input yields the zero summary")
    void emptyInput() {
        var summary = ItemAuditCore.summarize(List.of(), 0, 0);
        assertEquals(0, summary.stacks());
        assertEquals(0, summary.items());
        assertEquals(0, summary.totalBytes());
        assertTrue(summary.materials().isEmpty());
        assertTrue(summary.componentTypes().isEmpty());
        assertEquals(0, summary.errors());
    }

    @Test
    @DisplayName("counts per material are stack counts, items is the sum of amounts")
    void countsMaterialsAndItems() {
        var summary = ItemAuditCore.summarize(List.of(
                row("minecraft:diamond", 64, List.of()),
                row("minecraft:diamond", 32, List.of()),
                row("minecraft:ender_pearl", 16, List.of())), 512, 0);
        assertEquals(3, summary.stacks());
        assertEquals(112, summary.items());
        assertEquals(2, summary.materials().get("minecraft:diamond"));
        assertEquals(1, summary.materials().get("minecraft:ender_pearl"));
        assertEquals(512, summary.totalBytes());
    }

    @Test
    @DisplayName("component histogram counts occurrences across stacks")
    void histogram() {
        var summary = ItemAuditCore.summarize(List.of(
                row("minecraft:diamond_sword", 1, List.of("minecraft:damage", "minecraft:enchantments")),
                row("minecraft:diamond_helmet", 1, List.of("minecraft:damage")),
                row("minecraft:book", 1, List.of("minecraft:written_book_content"))), 100, 0);
        assertEquals(2, summary.componentTypes().get("minecraft:damage"));
        assertEquals(1, summary.componentTypes().get("minecraft:enchantments"));
        assertEquals(1, summary.componentTypes().get("minecraft:written_book_content"));
    }

    @Test
    @DisplayName("materials map is deterministic (alphabetical) — log diffs stay stable")
    void deterministicOrdering() {
        var summary = ItemAuditCore.summarize(List.of(
                row("minecraft:zombie_head", 1, List.of()),
                row("minecraft:apple", 1, List.of()),
                row("minecraft:chest", 1, List.of())), 0, 0);
        assertEquals(List.of("minecraft:apple", "minecraft:chest", "minecraft:zombie_head"),
                List.copyOf(summary.materials().keySet()));
    }

    @Test
    @DisplayName("a negative byte total clamps to zero; negative amounts clamp to zero too")
    void clampsNonsense() {
        var summary = ItemAuditCore.summarize(List.of(row("minecraft:stone", -5, List.of())), -1, 0);
        assertEquals(0, summary.totalBytes());
        assertEquals(0, summary.items());
        assertEquals(1, summary.stacks());
    }

    @Test
    @DisplayName("caller-side audit errors are counted, not thrown")
    void errorsCounted() {
        var summary = ItemAuditCore.summarize(List.of(row("minecraft:stone", 1, List.of())), 10, 3);
        assertEquals(3, summary.errors());
    }

    @Test
    @DisplayName("null component list is tolerated")
    void toleratesNullComponentList() {
        var summary = ItemAuditCore.summarize(List.of(new ItemAuditCore.ItemAuditRow("minecraft:stone", 1, null)), 0, 0);
        assertEquals(1, summary.stacks());
        assertTrue(summary.componentTypes().isEmpty());
    }

    @Test
    @DisplayName("null rows collection yields the zero summary")
    void toleratesNullRows() {
        var summary = ItemAuditCore.summarize(null, 42, 0);
        assertEquals(0, summary.stacks());
        assertEquals(42, summary.totalBytes());
    }

    @Test
    @DisplayName("returned maps are unmodifiable — a caller cannot corrupt the shared summary")
    void unmodifiableMaps() {
        var summary = ItemAuditCore.summarize(List.of(row("minecraft:stone", 1, List.of())), 0, 0);
        assertThrowsUnsupported(() -> summary.materials().put("minecraft:apple", 1));
        assertThrowsUnsupported(() -> summary.componentTypes().put("minecraft:damage", 1));
    }

    private static void assertThrowsUnsupported(Runnable runnable) {
        try {
            runnable.run();
        } catch (UnsupportedOperationException e) {
            return;
        }
        throw new AssertionError("expected UnsupportedOperationException");
    }
}
