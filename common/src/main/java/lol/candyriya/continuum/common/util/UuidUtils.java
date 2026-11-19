package lol.candyriya.continuum.common.util;

import java.util.Optional;
import java.util.UUID;

/** UUID parsing that does not explode on user/config input. */
public final class UuidUtils {

    private UuidUtils() {
    }

    /**
     * Parses a strict UUID, returning empty instead of throwing on garbage.
     * Trimmed, so trailing newlines from config files do not fail the parse.
     */
    public static Optional<UUID> tryParse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
