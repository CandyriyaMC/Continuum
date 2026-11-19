package lol.candyriya.continuum.paper.modules.gamemode;

/**
 * Restore-side feature switches for {@link GameModeModule}. Lets a backend
 * exclude values from synchronization without disabling the module — the
 * payload is still captured fully, the excluded writes are simply skipped.
 *
 * @param restoreGameMode     apply the game mode write
 * @param restoreFlight       apply allow-flight, flying and fly-speed writes;
 *                            for servers whose policy forbids client-side flight management
 * @param restoreMovementFlags apply gliding, sneaking and sprinting
 *                            (walk speed is always applied — see {@link GameModeApply})
 */
record GameModeRestoreConfig(boolean restoreGameMode, boolean restoreFlight, boolean restoreMovementFlags) {

    static final GameModeRestoreConfig ALL = new GameModeRestoreConfig(true, true, true);
}
