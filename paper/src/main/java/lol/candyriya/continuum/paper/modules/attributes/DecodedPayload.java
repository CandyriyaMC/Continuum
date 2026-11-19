package lol.candyriya.continuum.paper.modules.attributes;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Result of tolerantly decoding a stored payload. Decoding never throws: problems
 * are collected as human-readable warnings (English, no player data) and individual
 * attribute entries that are beyond saving are dropped, while their neighbours
 * decode just fine.
 *
 * @param capturedAt  informational capture timestamp exactly as stored, or null when absent
 * @param attributes  the usable entries, in capture (registry) order; duplicate keys
 *                    already resolved, last occurrence wins
 * @param warnings    everything that had to be skipped or bent during decode
 * @param rawEntries  how many attribute entries the raw payload carried, or {@code -1} when
 *                    the {@code attributes} node existed but was not a list; 0 means the
 *                    payload held no attributes at all (an empty snapshot is legal)
 */
record DecodedPayload(@Nullable String capturedAt, List<CapturedAttribute> attributes,
                      List<String> warnings, int rawEntries) {

    DecodedPayload {
        attributes = List.copyOf(attributes);
        warnings = List.copyOf(warnings);
    }
}
