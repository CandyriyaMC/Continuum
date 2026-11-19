package lol.candyriya.continuum.paper.modules.permissions;

import lol.candyriya.continuum.api.module.InvalidModuleIdException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionGateTest {

    /** Records every query and answers from a fixed grant set — the PermissionSource seam. */
    private static final class FakeSource implements PermissionSource {
        private final Set<String> granted;
        private final List<String> queried = new ArrayList<>();

        private FakeSource(Set<String> granted) {
            this.granted = granted;
        }

        @Override
        public boolean hasPermission(String node) {
            queried.add(node);
            return granted.contains(node);
        }
    }

    private static FakeSource source(String... granted) {
        return new FakeSource(Set.of(granted));
    }

    @Test
    @DisplayName("the gate passes the node through verbatim and returns the source's answer")
    void passesNodeThrough() {
        var granted = source("continuum.admin");
        assertTrue(PermissionGate.hasSyncPermission(granted, "continuum.admin"));
        assertEquals(List.of("continuum.admin"), granted.queried);

        var denied = source();
        assertFalse(PermissionGate.hasSyncPermission(denied, "continuum.admin"));
        assertEquals(List.of("continuum.admin"), denied.queried);
    }

    @Test
    @DisplayName("the gate never memoises: permission engines change grants live")
    void noMemoisation() {
        var source = source("continuum.admin");
        PermissionGate.hasSyncPermission(source, "continuum.admin");
        PermissionGate.hasSyncPermission(source, "continuum.admin");
        assertEquals(List.of("continuum.admin", "continuum.admin"), source.queried);
    }

    @Test
    @DisplayName("malformed nodes are rejected before the source is ever asked")
    void nodeGrammarIsEnforced() {
        var source = source();
        var bad = List.of(
                "", "   ", "Continuum.Admin", "continuum..admin", ".continuum.admin",
                "continuum.admin.", "continuum admin", "continuum.adm in", "continu%admin",
                "continuum/=admin", "вон.node", "a." + "b".repeat(300), "-node", "node-", "_node");
        for (String node : bad) {
            assertThrows(IllegalArgumentException.class, () -> PermissionGate.hasSyncPermission(source, node),
                    "expected IAE for node: " + node);
        }
        assertTrue(source.queried.isEmpty(), "no malformed node may reach the source");
    }

    @Test
    @DisplayName("null node is an IllegalArgumentException, not an NPE from inside a provider")
    void nullNodeRejected() {
        var source = source();
        assertThrows(IllegalArgumentException.class, () -> PermissionGate.hasSyncPermission(source, null));
        assertTrue(source.queried.isEmpty());
    }

    @Test
    @DisplayName("a 256-character node is accepted, 257 is not")
    void nodeLengthBoundary() {
        // 64+64+64+61 chars in four dot-separated segments, plus 3 dots = exactly 256
        var boundary = "a".repeat(64) + "." + "b".repeat(64) + "." + "c".repeat(64) + "." + "d".repeat(61);
        assertEquals(256, boundary.length());
        var source = source(boundary);
        assertTrue(PermissionGate.hasSyncPermission(source, boundary));
        assertThrows(IllegalArgumentException.class,
                () -> PermissionGate.hasSyncPermission(source(), boundary + "d"));
        // a single segment may not exceed 64 chars even when the whole node fits
        var fatSegment = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> PermissionGate.hasSyncPermission(source(), fatSegment));
    }

    @Test
    @DisplayName("null source is rejected")
    void nullSourceRejected() {
        assertThrows(NullPointerException.class, () -> PermissionGate.hasSyncPermission((PermissionSource) null, "a.b"));
    }

    @Test
    @DisplayName("restriction nodes: insert 'restrict' after the namespace, keep the rest")
    void restrictNodes() {
        assertEquals("continuum.restrict.inventory", PermissionGate.restrictNode("continuum.inventory"));
        assertEquals("continuum.restrict.enderchest", PermissionGate.restrictNode("continuum.enderchest"));
        assertEquals("continuum.restrict.pdc", PermissionGate.restrictNode("continuum.pdc"));
        // third-party namespaces are preserved, not rewritten into continuum.*
        assertEquals("myplugin.restrict.cool_module", PermissionGate.restrictNode("myplugin.cool_module"));
        assertEquals("myplugin.restrict.deep.module", PermissionGate.restrictNode("myplugin.deep.module"));
    }

    @Test
    @DisplayName("restriction nodes require a well-formed module id")
    void restrictNodeOfInvalidId() {
        assertThrows(InvalidModuleIdException.class, () -> PermissionGate.restrictNode("inventory"));
        assertThrows(InvalidModuleIdException.class, () -> PermissionGate.restrictNode(""));
        assertThrows(InvalidModuleIdException.class, () -> PermissionGate.restrictNode("Continuum.Inventory"));
        assertThrows(InvalidModuleIdException.class, () -> PermissionGate.restrictNode(null));
    }

    @Test
    @DisplayName("allowsRestore is permissive by default and queries exactly the restriction node")
    void allowRestoreDefault() {
        var source = source();
        assertTrue(PermissionGate.allowsRestore(source, "continuum.inventory"));
        assertEquals(List.of("continuum.restrict.inventory"), source.queried);
    }

    @Test
    @DisplayName("allowsRestore flips to false when the restriction node is granted")
    void allowRestoreRestricted() {
        var source = source("continuum.restrict.inventory");
        assertFalse(PermissionGate.allowsRestore(source, "continuum.inventory"));
        // restricting one module must not leak into others
        assertTrue(PermissionGate.allowsRestore(source, "continuum.effects"));
    }

    @Test
    @DisplayName("granting a restriction node affects only that node, not near-miss spellings")
    void nearMissNodesDoNotMatch() {
        var source = source("continuum.restrict.inventories");
        assertTrue(PermissionGate.allowsRestore(source, "continuum.inventory"));
        var source2 = source("continuum.restrict.inventory2");
        assertTrue(PermissionGate.allowsRestore(source2, "continuum.inventory"));
    }

    @Test
    @DisplayName("restriction nodes derived from third-party ids are checked against the third-party namespace")
    void thirdPartyRestrictionFlow() {
        var source = source("myplugin.restrict.cool_module");
        assertFalse(PermissionGate.allowsRestore(source, "myplugin.cool_module"));
        assertTrue(PermissionGate.allowsRestore(source, "continuum.inventory"));
    }
}
