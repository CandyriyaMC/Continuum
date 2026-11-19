package lol.candyriya.continuum.paper.modules.location;

/**
 * What the module does with the saved <em>position</em> on restore.
 *
 * <p>The respawn point and the last death point are not governed by this enum — they
 * restore whenever their own world rules pass (see the module javadoc). Position is the
 * only section with a mode.</p>
 *
 * <p>Deliberately no {@code DISABLED} constant: disabling the module is what
 * {@code sync.modules.location: false} in the shared config does — the module is then not
 * registered at all and captures/restores nothing. A second, module-local disable flag
 * would be a duplicate source of truth.</p>
 */
public enum LocationMode {

    /** Teleport the restored player to the saved position (subject to the world rules in {@code LocationPolicy}). */
    LAST_KNOWN_LOCATION,

    /**
     * Do not touch the position — the player keeps wherever the gate-time spawn policy put
     * them. Respawn data still restores. This is the "spawn-only network" mode.
     */
    SERVER_SPAWN,

    /**
     * Do not touch the position, restore only respawn data (respawn point + last death
     * point). For networks that want bed/anchor survival across backends but want every
     * join to land at the world spawn.
     */
    RESPAWN_ONLY
}
