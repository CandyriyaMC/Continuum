package lol.candyriya.continuum.storage.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveResultTest {

    @Test
    void savedCarriesTheHistoryId() {
        SaveResult result = SaveResult.saved(123L);
        assertTrue(result.saved());
        assertEquals(123L, ((SaveResult.Saved) result).historySnapshotId());
    }

    @Test
    void staleTokenCarriesTheStoredToken() {
        SaveResult result = SaveResult.staleToken(41L);
        assertFalse(result.saved());
        assertEquals(41L, ((SaveResult.StaleToken) result).currentFencingToken());
    }

    @Test
    void historyIdMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new SaveResult.Saved(0));
        assertThrows(IllegalArgumentException.class, () -> new SaveResult.Saved(-1));
    }

    @Test
    void currentTokenMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> new SaveResult.StaleToken(-1));
        new SaveResult.StaleToken(0); // boundary is fine
    }

    @Test
    void resultsAreSealedAndExhaustive() {
        assertInstanceOf(SaveResult.Saved.class, SaveResult.saved(1));
        assertInstanceOf(SaveResult.StaleToken.class, SaveResult.staleToken(1));
        // sealed hierarchy: exactly these two implementations exist
        assertEquals(2, SaveResult.class.getPermittedSubclasses().length);
    }
}
