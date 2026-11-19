package lol.candyriya.continuum.paper.modules.custom;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterCallGuardTest {

    @Test
    void delegatesCallsBeforeCancel() {
        Player delegate = FakePlayers.create();
        var cancelled = new AtomicBoolean();
        Player guarded = AdapterCallGuard.wrap(delegate, cancelled);
        assertEquals(20.0, guarded.getHealth());
        assertEquals(17, guarded.getFoodLevel());
        assertEquals(FakePlayers.FIXED_UUID, guarded.getUniqueId());
    }

    @Test
    void refusesCallsAfterCancel() {
        Player delegate = FakePlayers.create();
        var cancelled = new AtomicBoolean();
        Player guarded = AdapterCallGuard.wrap(delegate, cancelled);
        cancelled.set(true);
        assertThrows(AdapterCancelledException.class, guarded::getHealth);
        assertThrows(AdapterCancelledException.class, () -> guarded.setFoodLevel(1));
        assertThrows(AdapterCancelledException.class, guarded::getUniqueId);
    }

    @Test
    void identityMethodsSurviveCancel() {
        Player delegate = FakePlayers.create();
        var cancelled = new AtomicBoolean();
        Player guarded = AdapterCallGuard.wrap(delegate, cancelled);
        guarded.toString();
        assertEquals(System.identityHashCode(delegate), guarded.hashCode());
        assertTrue(guarded.equals(delegate), "the guard is indistinguishable from the delegate for equals");
        cancelled.set(true);
        // after cancel: identity methods still work, state access does not
        guarded.toString();
        assertEquals(System.identityHashCode(delegate), guarded.hashCode());
        assertTrue(guarded.equals(delegate));
        assertThrows(AdapterCancelledException.class, guarded::getHealth);
    }

    @Test
    void writesLandOnDelegateBeforeCancel() {
        var writes = new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
        Player delegate = FakePlayers.create(writes);
        var cancelled = new AtomicBoolean();
        Player guarded = AdapterCallGuard.wrap(delegate, cancelled);
        guarded.setFoodLevel(5);
        assertTrue(writes.containsKey("setFoodLevel"));
        assertEquals(5, writes.get("setFoodLevel")[0]);
    }

    @Test
    void guardIsNotTheDelegateAndIsStable() {
        Player delegate = FakePlayers.create();
        var cancelled = new AtomicBoolean();
        Player guarded = AdapterCallGuard.wrap(delegate, cancelled);
        assertNotEquals(delegate, guarded);
        assertSame(guarded, guarded);
    }

    @Test
    void platformExceptionsAreForwardedUnwrapped() {
        Player delegate = FakePlayers.create();
        // performCommand is unsupported by the fake and throws UnsupportedOperationException;
        // the guard must surface the underlying cause, not InvocationTargetException
        var cancelled = new AtomicBoolean();
        Player guarded = AdapterCallGuard.wrap(delegate, cancelled);
        Throwable thrown = assertThrows(UnsupportedOperationException.class,
                () -> guarded.performCommand("ping"));
        assertTrue(thrown.getMessage().contains("performCommand"));
    }
}
