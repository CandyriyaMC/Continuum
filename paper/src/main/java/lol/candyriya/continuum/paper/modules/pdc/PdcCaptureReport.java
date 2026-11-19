package lol.candyriya.continuum.paper.modules.pdc;

/**
 * Accounting for one capture run: what was taken, what was left behind and why.
 * Logged at capture time; never carries values.
 *
 * @param captured            entries encoded into the payload
 * @param denied              entries skipped because their namespace is on the hard deny-list
 * @param unsupported         entries skipped because the storage type cannot be carried losslessly
 *                            (lists, unknown or empty types, unreadable values)
 * @param oversize            entries skipped because they exceed the per-value cap
 * @param nonFinite           FLOAT/DOUBLE entries skipped because JSON has no NaN/Infinity -
 *                            storing one would produce a snapshot the module cannot read back
 * @param encodeFailures      entries that looked supported but failed to encode (e.g. a nested
 *                            container that would not serialize)
 * @param totalBytes          decoded footprint of the captured entries, in bytes
 */
public record PdcCaptureReport(
        int captured,
        int denied,
        int unsupported,
        int oversize,
        int nonFinite,
        int encodeFailures,
        long totalBytes) {

    /** @return total entries examined (captured + skipped) */
    public int totalExamined() {
        return captured + denied + unsupported + oversize + nonFinite + encodeFailures;
    }
}
