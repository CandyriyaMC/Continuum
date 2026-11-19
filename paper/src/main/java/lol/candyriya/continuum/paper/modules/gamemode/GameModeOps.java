package lol.candyriya.continuum.paper.modules.gamemode;

/**
 * Restore-side boundary between {@link GameModeApply} (pure ordering and
 * clamping logic, unit-testable without a server) and the platform (a
 * Bukkit/Folia player). The production implementation is a thin pass-through
 * to the live entity; tests script it to record calls and drive branches.
 *
 * <p>Only primitives, booleans and strings cross the boundary — the Bukkit
 * enum name is parsed on the platform side of it.</p>
 */
interface GameModeOps {

    /**
     * Sets the game mode by enum constant name. The production adapter parses
     * and applies it; an unknown name is logged and skipped, not thrown.
     */
    void setGameMode(String gameModeName);

    void setAllowFlight(boolean allow);

    /** Live allow-flight state, read after the allow-flight write. */
    boolean allowFlight();

    void setFlying(boolean flying);

    void setFlySpeed(float speed);

    void setWalkSpeed(float speed);

    void setGliding(boolean gliding);

    void setSneaking(boolean sneaking);

    void setSprinting(boolean sprinting);
}
