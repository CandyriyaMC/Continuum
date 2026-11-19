package lol.candyriya.continuum.paper.modules.attributes;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Post-restore drift check. Re-reads the base value of every attribute the restore
 * touched and compares it against what the snapshot carried. Base values only —
 * effective values depend on the environment (items, effects, other modules' timings)
 * and are out of scope for verification.
 *
 * <p>Equality is judged with an absolute epsilon of {@value #EPSILON}; anything
 * looser would hide real drift, anything tighter would flag double rounding.
 * NaN on either side counts as drift (NaN never passes the comparison).</p>
 */
final class RestoreVerifier {

    /** Absolute tolerance for "the base value survived the roundtrip". */
    static final double EPSILON = 1e-6;

    /**
     * @param attributeKey which attribute drifted
     * @param expected     the captured base value
     * @param actual       what the instance reports after restore; NaN when the
     *                     attribute could not be re-read (this is a drift, not a skip)
     */
    record Drift(String attributeKey, double expected, double actual) {
    }

    private RestoreVerifier() {
    }

    /**
     * @param expected  the entries the restore attempted (unknown-on-target attributes
     *                  are not expected to be verifiable and must be filtered out by the caller)
     * @param reReader  string key → re-read base value, or empty when not re-readable
     */
    static List<Drift> verify(List<CapturedAttribute> expected, Function<String, OptionalDouble> reReader) {
        var drift = new ArrayList<Drift>();
        for (CapturedAttribute attribute : expected) {
            OptionalDouble actual = reReader.apply(attribute.key());
            if (actual.isEmpty()) {
                drift.add(new Drift(attribute.key(), attribute.base(), Double.NaN));
                continue;
            }
            double value = actual.getAsDouble();
            // !(x <= eps) on purpose: a NaN actual must count as drift
            if (!(Math.abs(value - attribute.base()) <= EPSILON)) {
                drift.add(new Drift(attribute.key(), attribute.base(), value));
            }
        }
        return List.copyOf(drift);
    }
}
