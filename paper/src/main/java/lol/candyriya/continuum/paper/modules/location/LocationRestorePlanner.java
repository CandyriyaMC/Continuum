package lol.candyriya.continuum.paper.modules.location;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pure restore-planning logic for the location module: no platform types, no I/O, no live
 * state — everything arrives as arguments, so the whole policy is unit-testable without a
 * server.
 *
 * <p>The three payload sections are planned independently: a snapshot may legitimately carry
 * a position in world A while its respawn point sits in world B (bed there, died here). One
 * section being unusable never blocks another.</p>
 *
 * <p>Rules, per section:</p>
 * <ol>
 *   <li>{@code POSITION}: first the mode is consulted. {@code SERVER_SPAWN} and
 *       {@code RESPAWN_ONLY} mean {@code KEEP_CURRENT} regardless of the world — the player
 *       keeps wherever the gate-time spawn policy put them. Under {@code LAST_KNOWN_LOCATION}
 *       the world rules (steps 2-4) decide. The position itself is mandatory for a usable
 *       payload, so a missing position is {@code NOTHING_TO_RESTORE} with a loud log, never a
 *       crash — the payload was either not validated (misuse) or decoded to a position-less
 *       object (corrupt snapshot, which {@code LocationValidation} also rejects).</li>
 *   <li>Excluded world (case-insensitive name match against the policy's exclude list) →
 *       {@code SKIP_EXCLUDED_WORLD}. The player stays out of a game space the operator fenced
 *       off, whatever the snapshot says.</li>
 *   <li>World not loaded on this node → {@code SKIP_UNKNOWN_WORLD}. The module never force-loads
 *       a world just to drop someone into it. The {@code SERVER_SPAWN} fallback semantics live
 *       here: the module marks the restore skipped and the gate-time spawn policy's own
 *       fallback (its configured server spawn) decides where the player lands instead.</li>
 *   <li>Same name, different dimension family (snapshot environment vs the loaded world's
 *       environment, both known) → {@code SKIP_ENVIRONMENT_MISMATCH}. This is the classic
 *       silent-corruption case: an operator re-created a world under the same name but as a
 *       different dimension family — coordinates would carry over into a space the player
 *       never stood in. If either side's environment is unknown, there is no evidence of a
 *       mismatch and the restore proceeds.</li>
 *   <li>{@code RESPAWN_POINT} and {@code LAST_DEATH_POINT}: absent from the payload →
 *       {@code NOTHING_TO_RESTORE}; otherwise the same world rules as position. Neither
 *       section is governed by the position mode — respawn data restores whenever its own
 *       rules pass.</li>
 * </ol>
 */
public final class LocationRestorePlanner {

    private LocationRestorePlanner() {
        // static entry points only
    }

    /** Which section a decision is about. */
    public enum Section {
        /** The player's position at capture time. */
        POSITION,
        /** The respawn point (bed or respawn anchor) at capture time. */
        RESPAWN_POINT,
        /** The last death point at capture time. */
        LAST_DEATH_POINT
    }

    /** What the restore path should do with one section. */
    public enum Action {
        /** The world rules pass; restore the section. */
        APPLY,
        /** Policy says the position must stay where the gate-time spawn policy put the player. Position section only. */
        KEEP_CURRENT,
        /** The section is absent from the payload (or carries no world); nothing to do. */
        NOTHING_TO_RESTORE,
        /** The snapshot world is not loaded on this node; restore skipped. */
        SKIP_UNKNOWN_WORLD,
        /** The snapshot world is on the policy's exclude list; restore skipped. */
        SKIP_EXCLUDED_WORLD,
        /** Same world name, different dimension family; restore skipped to avoid silent corruption. */
        SKIP_ENVIRONMENT_MISMATCH
    }

    /**
     * The outcome for one section.
     *
     * @param section the section this decision is about
     * @param action  what to do
     * @param world   the world the section would restore into (for the log line), or null when there is none
     */
    public record SectionDecision(@NotNull Section section, @NotNull Action action, @Nullable String world) {

        /** True when the restore path should act on the section. */
        public boolean applies() {
            return action == Action.APPLY;
        }
    }

    /** The full restore plan; one decision per section, always all three. */
    public record Plan(
            @NotNull SectionDecision position,
            @NotNull SectionDecision respawnPoint,
            @NotNull SectionDecision lastDeathPoint) {

        /** All decisions as a list, position first. */
        public @NotNull List<SectionDecision> decisions() {
            return List.of(position, respawnPoint, lastDeathPoint);
        }
    }

    /**
     * Computes the restore plan for {@code payload} under {@code policy} against the worlds
     * known to {@code lookup}.
     */
    public static @NotNull Plan plan(@NotNull LocationPayload payload,
                                     @NotNull LocationPolicy policy,
                                     @NotNull WorldLookup lookup) {
        SectionDecision position = planPosition(payload, policy, lookup);
        SectionDecision respawn = planWorldSection(Section.RESPAWN_POINT, payload.respawnPoint(), policy, lookup);
        SectionDecision death = planWorldSection(Section.LAST_DEATH_POINT, payload.lastDeathPoint(), policy, lookup);
        return new Plan(position, respawn, death);
    }

    private static SectionDecision planPosition(LocationPayload payload, LocationPolicy policy, WorldLookup lookup) {
        PositionSnapshot position = payload.position();
        if (position == null) {
            // corrupt/unvalidated payload; LocationValidation rejects this too — stay quiet, not fatal
            return new SectionDecision(Section.POSITION, Action.NOTHING_TO_RESTORE, null);
        }
        if (policy.mode() != LocationMode.LAST_KNOWN_LOCATION) {
            return new SectionDecision(Section.POSITION, Action.KEEP_CURRENT, position.world());
        }
        return worldDecision(Section.POSITION, position, policy, lookup);
    }

    private static SectionDecision planWorldSection(Section section,
                                                    PositionSnapshot snapshot,
                                                    LocationPolicy policy,
                                                    WorldLookup lookup) {
        if (snapshot == null) {
            return new SectionDecision(section, Action.NOTHING_TO_RESTORE, null);
        }
        return worldDecision(section, snapshot, policy, lookup);
    }

    /** The shared world rules — used by every section including position. */
    private static SectionDecision worldDecision(Section section,
                                                 PositionSnapshot snapshot,
                                                 LocationPolicy policy,
                                                 WorldLookup lookup) {
        if (policy.isExcluded(snapshot.world())) {
            return new SectionDecision(section, Action.SKIP_EXCLUDED_WORLD, snapshot.world());
        }
        if (!lookup.exists(snapshot.world())) {
            return new SectionDecision(section, Action.SKIP_UNKNOWN_WORLD, snapshot.world());
        }
        String targetEnvironment = lookup.environmentOf(snapshot.world());
        if (snapshot.environment() != null && targetEnvironment != null
                && !snapshot.environment().equalsIgnoreCase(targetEnvironment)) {
            return new SectionDecision(section, Action.SKIP_ENVIRONMENT_MISMATCH, snapshot.world());
        }
        return new SectionDecision(section, Action.APPLY, snapshot.world());
    }
}
