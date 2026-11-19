package lol.candyriya.continuum.paper.modules.inventory;

import java.util.Base64;

/**
 * Strict base64 codec for the byte sections of the payload. Standard alphabet, padding
 * required, no newlines — the same flavor common's Jackson uses for byte[] fields, so the
 * wire stays homogeneous.
 *
 * <p>Snapshots are untrusted input until proven otherwise (they travel through storage and
 * back), so decoding is size-capped before it allocates: a corrupt or hostile section cannot
 * balloon memory. The cap is generous for real inventories — a full double chest of shulkers
 * lands far below it.</p>
 */
final class Base64Codec {

    /** Decoded bytes per section, ~4 MiB. Plenty for any vanilla inventory; far from peanuts for storage. */
    static final int MAX_SECTION_BYTES = 4 * 1024 * 1024;

    private Base64Codec() {
        // static access only
    }

    /** Encodes {@code bytes} as standard base64 without line wrapping. */
    static String encode(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("bytes must not be null");
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Decodes a section, refusing anything that would decode beyond {@link #MAX_SECTION_BYTES}.
     *
     * @throws InventoryStateException when the section is null, oversize or not valid base64
     */
    static byte[] decode(String section, String value) {
        if (value == null) {
            throw new InventoryStateException("section '" + section + "' is missing (null)");
        }
        // base64 stores 3 bytes per 4 chars; reject early so we never allocate for garbage
        long estimated = (value.length() / 4L) * 3L;
        if (estimated > MAX_SECTION_BYTES) {
            throw new InventoryStateException("section '" + section + "' decodes to more than "
                    + MAX_SECTION_BYTES + " bytes; refusing");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new InventoryStateException("section '" + section + "' is not valid base64: " + e.getMessage(), e);
        }
        if (decoded.length > MAX_SECTION_BYTES) {
            throw new InventoryStateException("section '" + section + "' decoded to "
                    + decoded.length + " bytes, limit is " + MAX_SECTION_BYTES);
        }
        return decoded;
    }
}
