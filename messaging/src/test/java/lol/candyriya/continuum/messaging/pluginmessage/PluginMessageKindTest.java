package lol.candyriya.continuum.messaging.pluginmessage;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PluginMessageKindTest {

    private static final Set<String> EXPECTED_WIRE_IDS = Set.of(
            "continuum.handoff.request",
            "continuum.handoff.ready",
            "continuum.handoff.resumed",
            "continuum.locale.set",
            "continuum.ping",
            "continuum.backend.status",
            "continuum.route.result",
            "continuum.session.token",
            "continuum.pong");

    @Test
    void wireIdsMatchTheDocumentedSetExactly() {
        assertEquals(EXPECTED_WIRE_IDS, allWireIds());
    }

    @Test
    void wireIdsAreDottedLowercaseWithContinuumPrefix() {
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            assertTrue(PluginMessageConstraints.WIRE_ID_PATTERN.matcher(kind.wireId()).matches(),
                    "bad wire id: " + kind.wireId());
            assertTrue(kind.wireId().startsWith("continuum."), kind.wireId());
        }
    }

    @Test
    void wireIdsAndByteIdsAreUnique() {
        List<Integer> ids = allIds();
        List<String> wireIds = allWireIds().stream().collect(Collectors.toList());
        assertEquals(ids.size(), new HashSet<>(ids).size(), "byte id collision");
        assertEquals(wireIds.size(), new HashSet<>(wireIds).size(), "wire id collision");
    }

    @Test
    void byteIdsFitInAnUnsignedByteAndStayInRange() {
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            assertTrue(kind.id() >= 1 && kind.id() <= 255, "bad id: " + kind.id());
        }
    }

    @Test
    void directionMatchesTheSealedHierarchies() {
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            boolean assignableToBackend = BackendToProxyMessage.class.isAssignableFrom(kind.type());
            boolean assignableToProxy = ProxyToBackendMessage.class.isAssignableFrom(kind.type());
            assertNotEquals(assignableToBackend, assignableToProxy, "ambiguous hierarchy for " + kind);
            if (kind.direction() == PluginMessageDirection.BACKEND_TO_PROXY) {
                assertTrue(assignableToBackend, kind + " must bind a BackendToProxyMessage");
            } else {
                assertTrue(assignableToProxy, kind + " must bind a ProxyToBackendMessage");
            }
        }
    }

    @Test
    void everyPermittedSubtypeIsRegistered() {
        Set<Class<? extends PluginMessage>> registered = new HashSet<>();
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            registered.add(kind.type());
        }
        for (Class<?> permitted : BackendToProxyMessage.class.getPermittedSubclasses()) {
            assertTrue(registered.contains(permitted), "unregistered B2P type: " + permitted);
        }
        for (Class<?> permitted : ProxyToBackendMessage.class.getPermittedSubclasses()) {
            assertTrue(registered.contains(permitted), "unregistered P2B type: " + permitted);
        }
        assertEquals(BackendToProxyMessage.class.getPermittedSubclasses().length
                        + ProxyToBackendMessage.class.getPermittedSubclasses().length,
                registered.size());
    }

    @Test
    void fromIdRoundTripsEveryKind() {
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            assertSame(kind, PluginMessageKind.fromId(kind.id()));
        }
    }

    @Test
    void fromWireIdRoundTripsEveryKind() {
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            assertSame(kind, PluginMessageKind.fromWireId(kind.wireId()));
        }
    }

    @Test
    void fromTypeRoundTripsEveryKind() {
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            assertSame(kind, PluginMessageKind.fromType(kind.type()));
        }
    }

    @Test
    void unknownByteIdIsRejected() {
        UnknownMessageKindException e = assertThrows(UnknownMessageKindException.class,
                () -> PluginMessageKind.fromId(200));
        assertEquals(200, e.kind());
    }

    @Test
    void unknownWireIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PluginMessageKind.fromWireId("continuum.unknown.thing"));
        assertThrows(IllegalArgumentException.class, () -> PluginMessageKind.fromWireId(null));
    }

    @Test
    void unknownTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PluginMessageKind.fromType(null));
        // a permitted member of the sealed hierarchy that no kind binds to the wire
        assertThrows(IllegalArgumentException.class, () -> PluginMessageKind.fromType(BackendToProxyMessage.class));
    }

    @Test
    void directionCountsMatchTheRegistry() {
        long backendToProxy = java.util.Arrays.stream(PluginMessageKind.values())
                .filter(k -> k.direction() == PluginMessageDirection.BACKEND_TO_PROXY)
                .count();
        assertEquals(6, backendToProxy);
        assertEquals(3, PluginMessageKind.values().length - backendToProxy);
    }

    private static Set<String> allWireIds() {
        return java.util.Arrays.stream(PluginMessageKind.values())
                .map(PluginMessageKind::wireId)
                .collect(Collectors.toSet());
    }

    private static List<Integer> allIds() {
        return java.util.Arrays.stream(PluginMessageKind.values())
                .map(PluginMessageKind::id)
                .collect(Collectors.toList());
    }
}
