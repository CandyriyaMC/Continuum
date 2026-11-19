package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default implementations on the contract interfaces are behavioral promises, not
 * stubs — pin them down here.
 */
class SyncContractDefaultsTest {

    private static SyncAdapter<Object, String> minimalAdapter() {
        return new SyncAdapter<>() {
            @Override
            public String id() {
                return "testplugin.testmodule";
            }

            @Override
            public int version() {
                return 7;
            }

            @Override
            public CompletionStage<String> capture(@NotNull Object player) {
                return CompletableFuture.completedFuture("captured");
            }

            @Override
            public CompletionStage<Void> restore(@NotNull Object player, @NotNull String data) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static SyncAdapter<Object, String> optionalAdapter() {
        SyncAdapter<Object, String> minimal = minimalAdapter();
        return new SyncAdapter<>() {
            @Override
            public String id() {
                return minimal.id();
            }

            @Override
            public int version() {
                return minimal.version();
            }

            @Override
            public boolean required() {
                return false;
            }

            @Override
            public CompletionStage<String> capture(@NotNull Object player) {
                return minimal.capture(player);
            }

            @Override
            public CompletionStage<Void> restore(@NotNull Object player, @NotNull String data) {
                return minimal.restore(player, data);
            }
        };
    }

    @Test
    void requiredDefaultsToTrue() {
        assertTrue(minimalAdapter().required());
    }

    @Test
    void timeoutDefaultsToFiveSeconds() {
        assertEquals(Duration.ofSeconds(5), minimalAdapter().timeout());
    }

    @Test
    void priorityDefaultsTo100() {
        assertEquals(100, minimalAdapter().priority());
    }

    @Test
    void dependenciesDefaultToEmpty() {
        assertTrue(minimalAdapter().dependencies().isEmpty());
    }

    @Test
    void failurePolicyDefaultsToAbortForRequiredAdapters() {
        assertEquals(FailurePolicy.ABORT, minimalAdapter().failurePolicy());
    }

    @Test
    void failurePolicyDefaultsToSkipForOptionalAdapters() {
        assertEquals(FailurePolicy.SKIP, optionalAdapter().failurePolicy());
    }

    @Test
    void validateDefaultsToOk() throws Exception {
        ValidationResult result = minimalAdapter().validate(new Object(), "anything").toCompletableFuture().get();

        assertTrue(result.valid());
        assertNull(result.reason());
    }

    @Test
    void migrateDefaultCompletesExceptionallyWithModuleContext() {
        var future = minimalAdapter().migrate(3, "{\"old\":true}").toCompletableFuture();

        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        AdapterMigrationException cause = assertInstanceOf(AdapterMigrationException.class, thrown.getCause());

        assertEquals("testplugin.testmodule", cause.moduleId());
        assertEquals(3, cause.fromVersion());
    }

    @Test
    void migrateDefaultFailsImmediatelyAndCarriesTheVersion() {
        var future = minimalAdapter().migrate(1, "{}").toCompletableFuture();

        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        AdapterMigrationException cause = assertInstanceOf(AdapterMigrationException.class, thrown.getCause());

        assertTrue(cause.getMessage().contains("1"));
        assertTrue(cause.getMessage().contains("testplugin.testmodule"));
    }

    @Test
    void modulesExposeConstants() {
        assertEquals(100, SyncModule.DEFAULT_PRIORITY);
        assertEquals(Duration.ofSeconds(5), SyncAdapter.DEFAULT_TIMEOUT);
    }
}
