package lol.candyriya.continuum.paper.modules.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Decides, per section, how a decoded slot array may be applied to the live inventory —
 * before anything is written. Pure on purpose: the module computes this plan from decoded
 * lengths and the runtime's expected lengths, and only then touches the player.
 *
 * <p>Modes:</p>
 * <ul>
 *   <li><b>strict</b> (default) — any slot-count mismatch rejects the whole payload.
 *       A cross-version shape skew surfaces as a failed restore (nothing written), not as
 *       a quietly damaged inventory.</li>
 *   <li><b>lenient</b> — a short section is padded with nulls at the tail, a long section
 *       is truncated (extras are dropped, and dropped loudly via the plan details). The
 *       caller is expected to log what it applied.</li>
 * </ul>
 *
 * <p>Format/decode failures are never lenient — see {@code PayloadMigrations} and the
 * module's base64/deserialize checks.</p>
 */
final class RestorePlan {

    /** What to do with one section. */
    enum SectionAction {
        /** Length already matches; write as-is. */
        EXACT,
        /** Section is shorter than the target; pad with nulls at the tail. */
        PAD,
        /** Section is longer than the target; drop the tail. */
        TRUNCATE,
        /** Do not apply. Strict-mode shape skew or nonsense. */
        REJECT
    }

    /** Per-section outcome of the plan. */
    record SectionPlan(String section, SectionAction action, int fromSlots, int toSlots, String detail) {

        boolean rejected() {
            return action == SectionAction.REJECT;
        }

        boolean lossy() {
            return action == SectionAction.TRUNCATE;
        }
    }

    private final List<SectionPlan> sections;

    private RestorePlan(List<SectionPlan> sections) {
        this.sections = List.copyOf(sections);
    }

    /** All section plans, in the order the sections were given. */
    List<SectionPlan> sections() {
        return Collections.unmodifiableList(sections);
    }

    /** True when no section was rejected — the only safe point past which writes may begin. */
    boolean applicable() {
        for (SectionPlan plan : sections) {
            if (plan.rejected()) {
                return false;
            }
        }
        return true;
    }

    /** Human-readable one-line digest of every rejected section. */
    String describeRejected() {
        var rejected = sections.stream().filter(SectionPlan::rejected).toList();
        if (rejected.isEmpty()) {
            return null;
        }
        var joiner = new StringJoiner("; ");
        for (SectionPlan plan : rejected) {
            joiner.add(plan.section() + ": " + plan.detail());
        }
        return joiner.toString();
    }

    /** Human-readable digest of every lossy (truncating) section; null when the plan is clean. */
    String describeLossy() {
        var lossy = sections.stream().filter(SectionPlan::lossy).toList();
        if (lossy.isEmpty()) {
            return null;
        }
        var joiner = new StringJoiner("; ");
        for (SectionPlan plan : lossy) {
            joiner.add(plan.section() + ": " + plan.detail());
        }
        return joiner.toString();
    }

    /**
     * Computes the plan for {@code actual} (section → decoded slots) against {@code expected}
     * (section → runtime slots). Section order follows the insertion order of {@code actual}.
     */
    static RestorePlan of(Map<String, Integer> actual, Map<String, Integer> expected, boolean lenient) {
        if (actual == null || expected == null) {
            throw new NullPointerException("actual and expected must not be null");
        }
        var plans = new ArrayList<SectionPlan>(actual.size());
        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            String section = entry.getKey();
            Integer fromBoxed = entry.getValue();
            Integer toBoxed = expected.get(section);
            if (fromBoxed == null || toBoxed == null) {
                plans.add(new SectionPlan(section, SectionAction.REJECT,
                        fromBoxed == null ? -1 : fromBoxed, toBoxed == null ? -1 : toBoxed,
                        "unknown or unmapped section"));
                continue;
            }
            int from = fromBoxed;
            int to = toBoxed;
            if (from == to) {
                plans.add(new SectionPlan(section, SectionAction.EXACT, from, to, "slot count matches"));
            } else if (!lenient) {
                plans.add(new SectionPlan(section, SectionAction.REJECT, from, to,
                        "payload has " + from + " slots but the target holds " + to
                                + " (strict mode; rebuild the snapshot on a matching server or enable lenient mode)"));
            } else if (from < to) {
                plans.add(new SectionPlan(section, SectionAction.PAD, from, to,
                        "payload has " + from + " slots, target holds " + to + "; padding with " + (to - from) + " empty slot(s)"));
            } else {
                plans.add(new SectionPlan(section, SectionAction.TRUNCATE, from, to,
                        "payload has " + from + " slots, target holds " + to + "; dropping " + (from - to) + " trailing slot(s)"));
            }
        }
        return new RestorePlan(plans);
    }

}
