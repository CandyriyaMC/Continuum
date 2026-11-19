package lol.candyriya.continuum.paper.modules.inventory;

/**
 * Human-readable summary of a captured section — for logs and metrics, <em>never</em> for
 * restoring. Authoritative state always rides the byte path; this summary exists so operators
 * can see what moved without dumping NBT into a log line.
 *
 * @param stacks         how many non-empty stacks were seen
 * @param items          total item count (sum of stack amounts)
 * @param totalBytes     serialized size of the section(s) this summary describes, in bytes
 * @param materials      material key (e.g. {@code minecraft:diamond}) to stack count, alphabetical
 * @param componentTypes component type key (e.g. {@code minecraft:damage}) to occurrences across stacks, alphabetical
 * @param errors         stacks that could not be inspected (audit is fail-safe, this counts the failures)
 */
public record ItemAuditSummary(int stacks, long items, long totalBytes,
                               java.util.Map<String, Integer> materials,
                               java.util.Map<String, Integer> componentTypes,
                               int errors) {

    /** An all-zero summary; used when there is literally nothing to report. */
    public static final ItemAuditSummary EMPTY = new ItemAuditSummary(
            0, 0, 0, java.util.Map.of(), java.util.Map.of(), 0);

    public ItemAuditSummary {
        // rebuild sorted maps, never Map.copyOf — a stable alphabetical order keeps log diffs readable
        materials = materials == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(materials));
        componentTypes = componentTypes == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(componentTypes));
    }
}
