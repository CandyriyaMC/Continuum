package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class ModuleContractsTest {

    /**
     * A programmable adapter: every knob can be set to a good or a bad value.
     * Capture/restore behavior is irrelevant to contract validation.
     */
    static class TestAdapter implements SyncAdapter<Object, String> {
        private final String id;
        private final int version;
        private final Duration timeout;
        private final List<String> dependencies;

        TestAdapter(String id, int version, Duration timeout, List<String> dependencies) {
            this.id = id;
            this.version = version;
            this.timeout = timeout;
            this.dependencies = dependencies;
        }

        @Override
        @NotNull
        public String id() {
            return id;
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public Duration timeout() {
            return timeout;
        }

        @Override
        public List<String> dependencies() {
            return dependencies;
        }

        @Override
        @NotNull
        public CompletionStage<String> capture(@NotNull Object player) {
            return CompletableFuture.completedFuture("state");
        }

        @Override
        @NotNull
        public CompletionStage<Void> restore(@NotNull Object player, @NotNull String data) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void aWellFormedAdapterPasses() {
        TestAdapter adapter = new TestAdapter("myplugin.mymodule", 1, Duration.ofSeconds(5), List.of("continuum.attributes"));

        assertDoesNotThrow(() -> ModuleContracts.validate(adapter));
        assertEquals(List.of("continuum.attributes"), ModuleContracts.dependenciesOf(adapter));
    }

    @Test
    void aNullAdapterIsRejected() {
        assertThrows(NullPointerException.class, () -> ModuleContracts.validate(null));
    }

    @Test
    void reservedNamespaceIsRejected() {
        TestAdapter adapter = new TestAdapter("continuum.fake", 1, Duration.ofSeconds(5), List.of());

        InvalidModuleIdException thrown =
                assertThrows(InvalidModuleIdException.class, () -> ModuleContracts.validate(adapter));
        assertEquals("continuum.fake", thrown.id());
    }

    @Test
    void malformedIdIsRejected() {
        TestAdapter adapter = new TestAdapter("Not-An-Id", 1, Duration.ofSeconds(5), List.of());

        assertThrows(InvalidModuleIdException.class, () -> ModuleContracts.validate(adapter));
    }

    @Test
    void schemaVersionBelowOneIsRejected() {
        TestAdapter adapter = new TestAdapter("myplugin.m", 0, Duration.ofSeconds(5), List.of());

        InvalidModuleDefinitionException thrown =
                assertThrows(InvalidModuleDefinitionException.class, () -> ModuleContracts.validate(adapter));
        assertTrue(thrown.getMessage().contains("schema version"));
    }

    @Test
    void nonPositiveTimeoutIsRejected() {
        TestAdapter zero = new TestAdapter("myplugin.m", 1, Duration.ZERO, List.of());
        TestAdapter negative = new TestAdapter("myplugin.m", 1, Duration.ofSeconds(-1), List.of());

        assertThrows(InvalidModuleDefinitionException.class, () -> ModuleContracts.validate(zero));
        assertThrows(InvalidModuleDefinitionException.class, () -> ModuleContracts.validate(negative));
    }

    @Test
    void nullTimeoutIsRejected() {
        TestAdapter adapter = new TestAdapter("myplugin.m", 1, null, List.of());

        assertThrows(InvalidModuleDefinitionException.class, () -> ModuleContracts.validate(adapter));
    }

    @Test
    void nullDependencyListIsRejected() {
        TestAdapter adapter = new TestAdapter("myplugin.m", 1, Duration.ofSeconds(5), null);

        assertThrows(InvalidModuleDefinitionException.class, () -> ModuleContracts.validate(adapter));
    }

    @Test
    void nullDependencyElementIsRejected() {
        List<String> deps = new ArrayList<>();
        deps.add(null);
        TestAdapter adapter = new TestAdapter("myplugin.m", 1, Duration.ofSeconds(5), deps);

        InvalidModuleDefinitionException thrown =
                assertThrows(InvalidModuleDefinitionException.class, () -> ModuleContracts.validate(adapter));
        assertTrue(thrown.getMessage().contains("null"));
    }

    @Test
    void selfDependencyIsRejected() {
        TestAdapter adapter = new TestAdapter("myplugin.m", 1, Duration.ofSeconds(5), List.of("myplugin.m"));

        InvalidModuleDefinitionException thrown =
                assertThrows(InvalidModuleDefinitionException.class, () -> ModuleContracts.validate(adapter));
        assertTrue(thrown.getMessage().contains("itself"));
    }

    @Test
    void duplicateDependenciesAreRejected() {
        TestAdapter adapter = new TestAdapter("myplugin.m", 1, Duration.ofSeconds(5),
                List.of("continuum.vitals", "continuum.vitals"));

        InvalidModuleDefinitionException thrown =
                assertThrows(InvalidModuleDefinitionException.class, () -> ModuleContracts.validate(adapter));
        assertTrue(thrown.getMessage().contains("duplicate"));
    }

    @Test
    void malformedDependencyIsRejected() {
        TestAdapter adapter = new TestAdapter("myplugin.m", 1, Duration.ofSeconds(5), List.of("Bad Id"));

        assertThrows(InvalidModuleIdException.class, () -> ModuleContracts.validate(adapter));
    }

    @Test
    void dependenciesMayReferenceFirstPartyModules() {
        TestAdapter adapter = new TestAdapter("myplugin.m", 1, Duration.ofSeconds(5),
                List.of("continuum.attributes", "continuum.inventory"));

        assertDoesNotThrow(() -> ModuleContracts.validate(adapter));
    }

    @Test
    void dependenciesOfReturnsAnImmutableCopy() {
        List<String> mutable = new ArrayList<>(List.of("continuum.vitals"));
        TestAdapter adapter = new TestAdapter("myplugin.m", 1, Duration.ofSeconds(5), mutable);

        List<String> copy = ModuleContracts.dependenciesOf(adapter);
        mutable.add("continuum.inventory");

        assertEquals(List.of("continuum.vitals"), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.add("continuum.recipes"));
    }
}
