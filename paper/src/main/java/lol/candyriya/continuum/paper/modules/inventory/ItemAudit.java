package lol.candyriya.continuum.paper.modules.inventory;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort, fail-safe audit view over captured stacks. Counts per material, total bytes,
 * a histogram of component types — the kind of picture that answers "what is actually flying
 * through the snapshot" in a log line.
 *
 * <p><b>Not authoritative.</b> Nothing in the restore path reads this. It exists for logs and
 * metrics only, and it must never be able to break a capture: every per-stack inspection is
 * guarded, and whatever fails is counted in {@link ItemAuditSummary#errors()} instead of
 * thrown.</p>
 */
public final class ItemAudit {

    private ItemAudit() {
        // static access only
    }

    /**
     * Summarizes {@code stacks} (null entries are skipped).
     *
     * @param stacks    the stacks to inspect, may be null or contain nulls
     * @param totalBytes serialized size of the section(s) these stacks came from; pass the real
     *                    byte length when it is known (capture does), otherwise any lower bound
     */
    public static ItemAuditSummary summarize(ItemStack[] stacks, long totalBytes) {
        var rows = new ArrayList<ItemAuditCore.ItemAuditRow>();
        int errors = 0;
        if (stacks != null) {
            for (ItemStack stack : stacks) {
                if (stack == null) {
                    continue;
                }
                try {
                    // one material name per stack — counting stacks, not amounts, keeps the map small
                    String material = stack.getType().getKey().asString();
                    int amount = stack.getAmount();
                    List<String> components = new ArrayList<>();
                    for (var componentType : stack.getDataTypes()) {
                        try {
                            components.add(componentType.getKey().asString());
                        } catch (Exception e) {
                            // a broken component key must not fail the stack — count it and move on
                            errors++;
                        }
                    }
                    rows.add(new ItemAuditCore.ItemAuditRow(material, amount, components));
                } catch (Exception e) {
                    // audit is advisory; losing a stack from the summary is fine, failing capture is not
                    errors++;
                }
            }
        }
        return ItemAuditCore.summarize(rows, totalBytes, errors);
    }
}
