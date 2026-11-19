package lol.candyriya.continuum.paper.modules.vitals;

/**
 * Restore-side boundary between {@link VitalsApply} (pure ordering and clamping
 * logic, unit-testable without a server) and the platform (a Bukkit/Folia
 * player). The production implementation is a thin pass-through to the live
 * entity; tests script it to record calls and drive branches.
 *
 * <p>Only primitive types appear here on purpose — game-specific handles stay
 * on the platform side of the boundary.</p>
 */
interface VitalsOps {

    /** Live effective max health (attribute-driven), read after the attributes module ran. */
    double maxHealth();

    /** Live max fire ticks as reported by the platform (0 or less = no bound reported). */
    int maxFireTicks();

    /** Live max freeze ticks as reported by the platform (0 or less = no bound reported). */
    int maxFreezeTicks();

    void setFoodLevel(int level);

    void setSaturation(float value);

    void setExhaustion(float value);

    void setRemainingAir(int ticks);

    void setFireTicks(int ticks);

    void setFreezeTicks(int ticks);

    void setHealth(double health);

    void setAbsorptionAmount(double amount);

    void setFallDistance(float distance);
}
