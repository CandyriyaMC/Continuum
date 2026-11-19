package lol.candyriya.continuum.api.module;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModuleInfoTest {

    @Test
    void aWellFormedInfoValidates() {
        ModuleInfo info = new ModuleInfo("myplugin.m", 2, false, 50, List.of("continuum.attributes"), "myplugin");

        assertEquals("myplugin.m", info.id());
        assertEquals(2, info.version());
        assertFalse(info.required());
        assertEquals(50, info.priority());
        assertEquals(List.of("continuum.attributes"), info.dependencies());
        assertEquals("myplugin", info.source());
    }

    @Test
    void malformedIdIsRejected() {
        assertThrows(InvalidModuleIdException.class,
                () -> new ModuleInfo("Bad.Id", 1, true, 100, List.of(), "x"));
    }

    @Test
    void schemaVersionBelowOneIsRejected() {
        assertThrows(InvalidModuleDefinitionException.class,
                () -> new ModuleInfo("myplugin.m", 0, true, 100, List.of(), "x"));
    }

    @Test
    void blankSourceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleInfo("myplugin.m", 1, true, 100, List.of(), "  "));
        assertThrows(NullPointerException.class,
                () -> new ModuleInfo("myplugin.m", 1, true, 100, List.of(), null));
    }

    @Test
    void selfDependencyIsRejected() {
        assertThrows(InvalidModuleDefinitionException.class,
                () -> new ModuleInfo("myplugin.m", 1, true, 100, List.of("myplugin.m"), "x"));
    }

    @Test
    void dependencyListIsCopiedAndUnmodifiable() {
        List<String> mutable = new ArrayList<>(List.of("continuum.vitals"));
        ModuleInfo info = new ModuleInfo("myplugin.m", 1, true, 100, mutable, "x");

        mutable.add("continuum.inventory");

        assertEquals(List.of("continuum.vitals"), info.dependencies());
        assertThrows(UnsupportedOperationException.class, () -> info.dependencies().add("continuum.recipes"));
    }

    @Test
    void duplicateDependencyIsRejected() {
        assertThrows(InvalidModuleDefinitionException.class,
                () -> new ModuleInfo("myplugin.m", 1, true, 100,
                        List.of("continuum.vitals", "continuum.vitals"), "x"));
    }

    @Test
    void ofBuildsFromAdapter() {
        SyncAdapter<Object, String> adapter = new SyncAdapter<>() {
            @Override
            public String id() {
                return "myplugin.cool";
            }

            @Override
            public int version() {
                return 3;
            }

            @Override
            public boolean required() {
                return false;
            }

            @Override
            public int priority() {
                return 40;
            }

            @Override
            public List<String> dependencies() {
                return List.of("continuum.inventory");
            }

            @Override
            public java.util.concurrent.CompletionStage<String> capture(Object player) {
                return java.util.concurrent.CompletableFuture.completedFuture("s");
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> restore(Object player, String data) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };

        ModuleInfo info = ModuleInfo.of(adapter, "myplugin");

        assertEquals("myplugin.cool", info.id());
        assertEquals(3, info.version());
        assertFalse(info.required());
        assertEquals(40, info.priority());
        assertEquals(List.of("continuum.inventory"), info.dependencies());
        assertEquals("myplugin", info.source());
    }

    @Test
    void ofRejectsReservedNamespace() {
        SyncAdapter<Object, String> adapter = new SyncAdapter<>() {
            @Override
            public String id() {
                return "continuum.stolen";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public java.util.concurrent.CompletionStage<String> capture(Object player) {
                return java.util.concurrent.CompletableFuture.completedFuture("s");
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> restore(Object player, String data) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };

        assertThrows(InvalidModuleIdException.class, () -> ModuleInfo.of(adapter, "x"));
    }
}
