package lol.candyriya.continuum.paper.modules.inventory;

import java.util.Collection;
import java.util.Collections;
import java.util.TreeMap;

/**
 * Pure aggregation behind {@link ItemAudit}; kept Bukkit-free so it is unit-testable.
 * Takes already-extracted rows and folds them into an {@link ItemAuditSummary}.
 */
final class ItemAuditCore {

    private ItemAuditCore() {
        // static access only
    }

    /** One stack, reduced to the three things the audit cares about. */
    record ItemAuditRow(String material, int amount, java.util.List<String> componentTypes) {
        ItemAuditRow {
            if (material == null) {
                material = "unknown";
            }
            if (amount < 0) {
                amount = 0;
            }
            componentTypes = componentTypes == null ? java.util.List.of() : java.util.List.copyOf(componentTypes);
        }
    }

    /**
     * Folds {@code rows} into a summary. {@code errors} is the caller-side count of stacks
     * that could not even be reduced to a row.
     */
    static ItemAuditSummary summarize(Collection<ItemAuditRow> rows, long totalBytes, int errors) {
        var materials = new TreeMap<String, Integer>();
        var componentTypes = new TreeMap<String, Integer>();
        long items = 0;
        if (rows != null) {
            for (ItemAuditRow row : rows) {
                items += row.amount();
                materials.merge(row.material(), 1, Integer::sum);
                for (String component : row.componentTypes()) {
                    componentTypes.merge(component, 1, Integer::sum);
                }
            }
        }
        int stacks = rows == null ? 0 : rows.size();
        return new ItemAuditSummary(stacks, items, Math.max(totalBytes, 0),
                Collections.unmodifiableMap(materials),
                Collections.unmodifiableMap(componentTypes),
                Math.max(errors, 0));
    }
}
