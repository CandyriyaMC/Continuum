package lol.candyriya.continuum.paper.modules.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuumPermissionContextTest {

    @Test
    @DisplayName("default service reports the world only")
    void worldOnlyByDefault() {
        var context = ContinuumPermissionContext.assemble("lobby", null);
        assertEquals(Set.of("world=lobby"), context.tags());
        assertNull(new ContinuumPermissionContext().serverId());
    }

    @Test
    @DisplayName("configured service reports world and server, sorted")
    void worldAndServerWhenConfigured() {
        var context = ContinuumPermissionContext.assemble("lobby", "survival-1");
        assertEquals(Set.of("server=survival-1", "world=lobby"), context.tags());
        assertEquals("server=survival-1,world=lobby", context.encode());
        assertEquals("survival-1", new ContinuumPermissionContext("survival-1").serverId());
    }

    @Test
    @DisplayName("the service is deterministic: same inputs, same encoding")
    void deterministicAcrossInstances() {
        var first = ContinuumPermissionContext.assemble("arena 1", "survival-1");
        var second = ContinuumPermissionContext.assemble("arena 1", "survival-1");
        assertEquals(first, second);
        assertEquals(first.encode(), second.encode());
        assertEquals("server=survival-1,world=arena%201", first.encode());
    }

    @Test
    @DisplayName("world names with reserved characters percent-encode cleanly")
    void worldNamesAreEncoded() {
        assertEquals("world=a%2Cb", ContinuumPermissionContext.assemble("a,b", null).encode());
        assertEquals("world=arena%3D1", ContinuumPermissionContext.assemble("arena=1", null).encode());
        assertEquals("world=%D0%BC%D0%B8%D1%80", ContinuumPermissionContext.assemble("мир", null).encode());
    }

    @Test
    @DisplayName("blank world names are rejected — a world-less context is meaningless")
    void blankWorldRejected() {
        assertThrows(IllegalArgumentException.class, () -> ContinuumPermissionContext.assemble("", null));
        assertThrows(IllegalArgumentException.class, () -> ContinuumPermissionContext.assemble("   ", null));
        assertThrows(NullPointerException.class, () -> ContinuumPermissionContext.assemble(null, null));
    }

    @Test
    @DisplayName("server id must be lowercase dns-ish, at most 64 chars")
    void serverIdIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext(""));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("   "));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("Survival"));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("survival 1"));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("survival,1"));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("survival=1"));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("survival/1"));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("-survival"));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("survival-"));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumPermissionContext("a".repeat(65)));
        // exactly 64 chars is fine
        assertEquals("a".repeat(64), new ContinuumPermissionContext("a".repeat(64)).serverId());
        // dotted/underscored/dashed ids are fine
        assertEquals("survival-1.2_b", new ContinuumPermissionContext("survival-1.2_b").serverId());
        assertTrue(ContinuumPermissionContext.assemble("world", "survival-1.2_b").tags().size() == 2);
    }

    @Test
    @DisplayName("server id is re-validated at assembly time, not only at construction")
    void serverIdRevalidatedAtAssembly() {
        assertThrows(IllegalArgumentException.class, () -> ContinuumPermissionContext.assemble("lobby", "UPPER"));
    }

    // note: currentContext(Player)/encodedContext(Player) fail fast on null BEFORE touching any
    // platform state, but the platform api is compileOnly on this module's test classpath, so the
    // Player-typed overloads cannot be invoked in unit tests at all (the class file is not there).
    // The paths they delegate to are covered above; the null check itself is the first line of
    // each method. Same constraint as every other module in this project — see the existing tests.
}
