package lol.candyriya.continuum.paper.modules.inventory;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/**
 * Shared plumbing for the two payload modules: section decode with context, and the
 * pad/truncate adjustment derived from a {@link RestorePlan}. Centralizes the only places
 * where "base64 string → ItemStack[]" can fail, so both modules report errors identically.
 */
final class Sections {

    private Sections() {
        // static access only
    }

    /**
     * Decodes one base64 section into a slot array (nulls preserved).
     *
     * @throws InventoryStateException when the section fails base64 decoding, blows the
     *         size cap, or carries bytes this server cannot interpret (corrupt data or
     *         components from a newer game version)
     */
    static ItemStack[] decodeItems(String section, String base64) {
        byte[] bytes = Base64Codec.decode(section, base64);
        try {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
            return items == null ? new ItemStack[0] : items;
        } catch (Exception e) {
            throw new InventoryStateException("section '" + section + "' failed to deserialize: "
                    + e.getMessage()
                    + " (the snapshot may be corrupt, or written by a newer game version)", e);
        }
    }

    /** Applies a pad/truncate action to a decoded section. EXACT returns the array unchanged; REJECT throws — it must never reach a write. */
    static <T> T[] adjust(T[] items, int targetSlots, RestorePlan.SectionAction action) {
        return switch (action) {
            case EXACT -> items;
            case PAD -> Arrays.copyOf(items, targetSlots);
            case TRUNCATE -> Arrays.copyOf(items, targetSlots);
            case REJECT -> throw new InventoryStateException(
                    "attempted to apply a rejected section (" + items.length + " slots vs target " + targetSlots + ")");
        };
    }

    /** Non-null stack count. */
    static int countStacks(ItemStack[] items) {
        int stacks = 0;
        for (ItemStack stack : items) {
            if (stack != null) {
                stacks++;
            }
        }
        return stacks;
    }

    /** Sum of stack amounts. */
    static long countItems(ItemStack[] items) {
        long count = 0;
        for (ItemStack stack : items) {
            if (stack != null) {
                count += Math.max(stack.getAmount(), 0);
            }
        }
        return count;
    }

    /** Serialized byte length of a combined view over all sections; audit-only input. */
    static long totalBytes(byte[]... sections) {
        long total = 0;
        for (byte[] section : sections) {
            total += section == null ? 0 : section.length;
        }
        return total;
    }
}
