package lol.candyriya.continuum.paper.modules.location;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The restore decision matrix. Fixture worlds are plain strings on purpose — the planner
 * never touches a live server, which is the whole point of the WorldLookup seam.
 *
 * <p>Fixture shorthand: {@code exists("survival")} makes the world loaded with environment
 * {@code NORMAL}; {@code env("nether", "NETHER")} overrides the environment; worlds never
 * mentioned answer {@code exists=false} and {@code environmentOf=null}.</p>
 */
class LocationRestorePlannerTest {

    private static final PositionSnapshot SURVIVAL = new PositionSnapshot("survival", "NORMAL", 100.5, 64.0, -877.25, 90.0f, 0.0f);
    private static final PositionSnapshot RESPAWN = new PositionSnapshot("survival", "NORMAL", 90.0, 65.0, -800.0, 0.0f, 0.0f);
    private static final PositionSnapshot DEATH = new PositionSnapshot("nether", "NETHER", 10.0, 64.0, 20.0, 0.0f, 0.0f);

    private static LocationPayload payload(PositionSnapshot position, PositionSnapshot respawn, PositionSnapshot death) {
        return new LocationPayload(LocationPayload.FORMAT, 1_763_456_789_000L, position, respawn, death);
    }

    private static LocationPolicy policy(LocationMode mode, List<String> excludeWorlds) {
        return new LocationPolicy(mode, excludeWorlds, FallbackMode.SERVER_SPAWN);
    }

    // ---------- position: mode gating ----------

    @Test
    void lastKnownLocationModeAppliesWhenWorldIsLoadedAndAllowed() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.position().action());
        assertEquals("survival", plan.position().world());
    }

    @Test
    void serverSpawnModeKeepsPositionRegardlessOfWorld() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.SERVER_SPAWN, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.KEEP_CURRENT, plan.position().action());
        // the snapshot's world still rides on the decision for the log line
        assertEquals("survival", plan.position().world());
    }

    @Test
    void serverSpawnModeKeepsPositionEvenForUnknownWorlds() {
        var lookup = new FixtureWorldLookup(); // nothing exists
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.SERVER_SPAWN, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.KEEP_CURRENT, plan.position().action());
    }

    @Test
    void respawnOnlyModeKeepsPositionButAppliesRespawnData() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, RESPAWN, null), policy(LocationMode.RESPAWN_ONLY, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.KEEP_CURRENT, plan.position().action());
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.respawnPoint().action());
    }

    // ---------- shared world rules ----------

    @Test
    void unknownWorldSkipsRestoreWithASkipDecision() {
        var lookup = new FixtureWorldLookup(); // world not loaded
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.SKIP_UNKNOWN_WORLD, plan.position().action());
        assertEquals("survival", plan.position().world());
    }

    @Test
    void excludedWorldSkipsRestoreEvenWhenTheWorldExists() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of("Survival")), lookup);
        assertEquals(LocationRestorePlanner.Action.SKIP_EXCLUDED_WORLD, plan.position().action());
    }

    @Test
    void exclusionIsCaseInsensitive() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of("SURVIVAL")), lookup);
        assertEquals(LocationRestorePlanner.Action.SKIP_EXCLUDED_WORLD, plan.position().action());
    }

    @Test
    void sameNameWorldOfDifferentDimensionFamilyIsRefused() {
        // the player stood in NORMAL 'survival'; this node has a NETHER world named 'survival' — different game space
        var lookup = new FixtureWorldLookup().exists("survival", "NETHER");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.SKIP_ENVIRONMENT_MISMATCH, plan.position().action());
    }

    @Test
    void environmentComparisonIsCaseInsensitive() {
        var lookup = new FixtureWorldLookup().exists("survival", "normal");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.position().action());
    }

    @Test
    void payloadWithoutEnvironmentRestoresDespiteUnknownTargetEnvironment() {
        var snapshot = new PositionSnapshot("survival", null, 1.0, 64.0, 1.0, 0.0f, 0.0f);
        var lookup = new FixtureWorldLookup().exists("survival", null);
        var plan = LocationRestorePlanner.plan(payload(snapshot, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.position().action());
    }

    @Test
    void knownTargetEnvironmentAgainstNullPayloadEnvironmentRestores() {
        var snapshot = new PositionSnapshot("survival", null, 1.0, 64.0, 1.0, 0.0f, 0.0f);
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(snapshot, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.position().action());
    }

    // ---------- respawn point / last death point ----------

    @Test
    void absentSectionsAreNothingToRestore() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.NOTHING_TO_RESTORE, plan.respawnPoint().action());
        assertEquals(LocationRestorePlanner.Action.NOTHING_TO_RESTORE, plan.lastDeathPoint().action());
    }

    @Test
    void respawnPointRestoresWhenItsWorldIsUsable() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, RESPAWN, null), policy(LocationMode.SERVER_SPAWN, List.of()), lookup);
        // SERVER_SPAWN keeps position but respawn data still restores
        assertEquals(LocationRestorePlanner.Action.KEEP_CURRENT, plan.position().action());
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.respawnPoint().action());
    }

    @Test
    void respawnPointInExcludedWorldIsSkipped() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, RESPAWN, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of("survival")), lookup);
        assertEquals(LocationRestorePlanner.Action.SKIP_EXCLUDED_WORLD, plan.respawnPoint().action());
    }

    @Test
    void respawnPointInUnknownWorldIsSkipped() {
        var lookup = new FixtureWorldLookup(); // nothing loaded
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, RESPAWN, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.SKIP_UNKNOWN_WORLD, plan.respawnPoint().action());
    }

    @Test
    void lastDeathPointFollowsItsOwnWorldRules() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL").exists("nether", "NETHER");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, DEATH), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.position().action());
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.lastDeathPoint().action());
        assertEquals("nether", plan.lastDeathPoint().world());
    }

    @Test
    void lastDeathPointInDifferentDimensionFamilyIsSkipped() {
        // snapshot says NETHER 'nether', this node has NORMAL 'nether' — refuse to carry the point over
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL").exists("nether", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, DEATH), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.SKIP_ENVIRONMENT_MISMATCH, plan.lastDeathPoint().action());
    }

    @Test
    void sectionsFailIndependently() {
        // position world exists, respawn world excluded: position applies, respawn skips
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var respawnElsewhere = new PositionSnapshot("other", "NORMAL", 1.0, 64.0, 1.0, 0.0f, 0.0f);
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, respawnElsewhere, null),
                policy(LocationMode.LAST_KNOWN_LOCATION, List.of("other")), lookup);
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.position().action());
        assertEquals(LocationRestorePlanner.Action.SKIP_EXCLUDED_WORLD, plan.respawnPoint().action());
    }

    @Test
    void modeNeverGatesRespawnDataRestore() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL").exists("nether", "NETHER");
        for (LocationMode mode : LocationMode.values()) {
            var plan = LocationRestorePlanner.plan(payload(SURVIVAL, RESPAWN, DEATH), policy(mode, List.of()), lookup);
            assertEquals(LocationRestorePlanner.Action.APPLY, plan.respawnPoint().action(), "mode " + mode);
            assertEquals(LocationRestorePlanner.Action.APPLY, plan.lastDeathPoint().action(), "mode " + mode);
        }
    }

    @Test
    void fixtureEnvironmentIsCaseInsensitivelyComparedByThePlanner() {
        var lookup = new FixtureWorldLookup().exists("survival", "Normal");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, null, null), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        assertEquals(LocationRestorePlanner.Action.APPLY, plan.position().action());
    }

    @Test
    void decisionsHelperListsAllThreeSections() {
        var lookup = new FixtureWorldLookup().exists("survival", "NORMAL");
        var plan = LocationRestorePlanner.plan(payload(SURVIVAL, RESPAWN, DEATH), policy(LocationMode.LAST_KNOWN_LOCATION, List.of()), lookup);
        var sections = plan.decisions().stream().map(LocationRestorePlanner.SectionDecision::section).toList();
        assertEquals(List.of(LocationRestorePlanner.Section.POSITION,
                        LocationRestorePlanner.Section.RESPAWN_POINT,
                        LocationRestorePlanner.Section.LAST_DEATH_POINT),
                sections);
    }

    @Test
    void fallbackModeIsEchoedThroughThePolicy() {
        // FALLBACK semantics: the module does not teleport anywhere itself; the gate-time policy owns placement
        var policy = LocationPolicy.defaults();
        assertEquals(FallbackMode.SERVER_SPAWN, policy.fallbackMode());
    }

    /** Fixture lookup: worlds appear only when explicitly registered. */
    private static final class FixtureWorldLookup implements WorldLookup {

        private final Set<String> worlds = new java.util.HashSet<>();
        private final Map<String, String> environments = new HashMap<>();

        FixtureWorldLookup exists(String world, String environment) {
            worlds.add(world);
            if (environment != null) {
                environments.put(world, environment);
            } else {
                environments.remove(world);
            }
            return this;
        }

        @Override
        public boolean exists(String worldName) {
            return worlds.contains(worldName);
        }

        @Override
        public String environmentOf(String worldName) {
            return environments.get(worldName);
        }
    }
}
