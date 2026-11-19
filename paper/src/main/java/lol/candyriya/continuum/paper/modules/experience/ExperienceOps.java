package lol.candyriya.continuum.paper.modules.experience;

/**
 * Restore-side boundary between {@link ExperienceApply} (pure ordering and
 * clamping logic, unit-testable without a server) and the platform (a
 * Bukkit/Folia player). The production implementation is a thin pass-through
 * to the live entity; tests script it to record calls and drive branches.
 */
interface ExperienceOps {

    void setTotalExperience(int total);

    void setLevel(int level);

    /** Progress towards the next level, 0.0..1.0. */
    void setExp(float progress);
}
