package lol.candyriya.continuum.velocity;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up the locale a player explicitly picked for Continuum.
 *
 * <p>The durable implementation lands with the player settings store (storage-backed);
 * until then the proxy has no preference to read and only client/default locales
 * are available. Keep this interface narrow: the resolver chain treats a thrown
 * exception as "no preference", not as fatal.</p>
 */
@FunctionalInterface
public interface PlayerLocaleSource {

    /** Returns the stored preference for the player, or empty when unset/unavailable. */
    Optional<String> preferredLocale(UUID playerId);

    /** No backing store wired yet; every lookup is empty. */
    static PlayerLocaleSource none() {
        return playerId -> Optional.empty();
    }
}
