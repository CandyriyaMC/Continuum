package lol.candyriya.continuum.api.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRegistrationTest {

    private static ModuleRegistration fake() {
        return new ModuleRegistration() {
            private volatile boolean active = true;

            @Override
            public String id() {
                return "testplugin.testmodule";
            }

            @Override
            public void unregister() {
                active = false;
            }

            @Override
            public boolean isActive() {
                return active;
            }
        };
    }

    @Test
    void startsActive() {
        assertTrue(fake().isActive());
    }

    @Test
    void unregisterDeactivatesIt() {
        ModuleRegistration registration = fake();

        registration.unregister();

        assertFalse(registration.isActive());
    }

    @Test
    void unregisterIsIdempotent() {
        ModuleRegistration registration = fake();

        registration.unregister();
        registration.unregister();

        assertFalse(registration.isActive());
    }

    @Test
    void closeDelegatesToUnregister() {
        ModuleRegistration registration = fake();

        registration.close();

        assertFalse(registration.isActive());
        // never throws, may be called again
        registration.close();
    }

    @Test
    void handleExposesTheModuleId() {
        assertEquals("testplugin.testmodule", fake().id());
    }
}
