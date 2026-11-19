package lol.candyriya.continuum.paper.modules.inventory;

/**
 * What the currently open view of a player means for capture/restore.
 *
 * <p>{@code getOpenInventory()} never returns null for an online player — the "no window open"
 * state is a real view whose top inventory is the internal 2×2 crafting grid. So the capture
 * flow has exactly three situations to tell apart, and this classifier is the pure decision
 * behind it.</p>
 */
final class ViewResolution {

    /** The three possible readings of the open view. */
    enum ViewKind {
        /**
         * The top inventory is the player's own crafting grid (type {@code CRAFTING}, 2×2 matrix).
         * Either nothing is open, or the inventory screen is — same internal grid, nothing to close.
         */
        INTERNAL_GRID,
        /**
         * The top inventory is something else — a chest, a furnace, a GUI, a workbench, a merchant.
         * Its contents are not ours to touch; the view must be closed ({@code PLUGIN} reason) before capture.
         */
        FOREIGN_CONTAINER,
        /** No view at all. Should not happen for an online player; degrade gracefully. */
        NO_VIEW
    }

    private ViewResolution() {
        // static access only
    }

    /**
     * Classifies the open view from cheap facts about its top inventory.
     *
     * @param viewPresent      whether {@code getOpenInventory()} returned a view
     * @param topTypeIsCrafting whether the top inventory reports type {@code CRAFTING}
     * @param topMatrixLength  {@code CraftingInventory.getMatrix().length} when the top is a
     *                         crafting inventory, otherwise -1
     */
    static ViewKind resolve(boolean viewPresent, boolean topTypeIsCrafting, int topMatrixLength) {
        if (!viewPresent) {
            return ViewKind.NO_VIEW;
        }
        // the internal grid is exactly: crafting-typed top with a 4-slot matrix; a workbench is 9
        if (topTypeIsCrafting && topMatrixLength == 4) {
            return ViewKind.INTERNAL_GRID;
        }
        return ViewKind.FOREIGN_CONTAINER;
    }
}
