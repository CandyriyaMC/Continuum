package lol.candyriya.continuum.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContinuumProviderTest {

    private final FakeContinuumApi first = new FakeContinuumApi("1.0.0");
    private final FakeContinuumApi second = new FakeContinuumApi("1.0.1");

    @AfterEach
    void clear() {
        // provider state is JVM-global; leave nothing behind for other tests
        try {
            ContinuumProvider.unregister(first);
        } catch (Exception ignored) {
            // no-op: unregister is tolerant by design
        }
        try {
            ContinuumProvider.unregister(second);
        } catch (Exception ignored) {
            // no-op
        }
    }

    @Test
    void getBeforeRegistrationThrowsUnavailable() {
        assertThrows(ContinuumUnavailableException.class, ContinuumProvider::get);
        assertTrue(ContinuumProvider.tryGet().isEmpty());
        assertFalse(ContinuumProvider.isAvailable());
    }

    @Test
    void registerMakesApiAvailable() {
        ContinuumProvider.register(first);

        assertSame(first, ContinuumProvider.get());
        assertSame(first, ContinuumProvider.tryGet().orElseThrow());
        assertTrue(ContinuumProvider.isAvailable());
        assertEquals("1.0.0", ContinuumProvider.get().version());
    }

    @Test
    void registerIsIdempotentForTheSameInstance() {
        ContinuumProvider.register(first);
        ContinuumProvider.register(first);
        ContinuumProvider.register(first);

        assertSame(first, ContinuumProvider.get());
    }

    @Test
    void registerOfDifferentImplementationIsRefused() {
        ContinuumProvider.register(first);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> ContinuumProvider.register(second));

        assertTrue(thrown.getMessage().contains("already provided"));
        assertSame(first, ContinuumProvider.get());
    }

    @Test
    void unregisterIgnoresTheWrongInstance() {
        ContinuumProvider.register(first);

        // a stale platform shutting down must not wipe the newer registration
        ContinuumProvider.unregister(second);

        assertSame(first, ContinuumProvider.get());
    }

    @Test
    void unregisterOfRegisteredInstanceClearsIt() {
        ContinuumProvider.register(first);
        ContinuumProvider.unregister(first);

        assertFalse(ContinuumProvider.isAvailable());
        assertThrows(ContinuumUnavailableException.class, ContinuumProvider::get);
        assertTrue(ContinuumProvider.tryGet().isEmpty());
    }

    @Test
    void registerNullIsRejected() {
        assertThrows(NullPointerException.class, () -> ContinuumProvider.register(null));
        assertThrows(NullPointerException.class, () -> ContinuumProvider.unregister(null));
    }

    @Test
    void reRegisterAfterUnregisterWorks() {
        ContinuumProvider.register(first);
        ContinuumProvider.unregister(first);
        ContinuumProvider.register(second);

        assertSame(second, ContinuumProvider.get());
    }
}
