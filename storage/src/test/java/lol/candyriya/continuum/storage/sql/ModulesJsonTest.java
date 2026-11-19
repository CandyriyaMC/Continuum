package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.common.error.StorageException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModulesJsonTest {

    @Test
    void encodeEmitsAPlainJsonArray() {
        assertEquals("[\"vitals\",\"inventory\"]", ModulesJson.encode(List.of("vitals", "inventory")));
    }

    @Test
    void encodeOfEmptyOrNullIsAnEmptyArray() {
        assertEquals("[]", ModulesJson.encode(List.of()));
        assertEquals("[]", ModulesJson.encode(null));
    }

    @Test
    void decodeRoundTripsTheList() {
        assertEquals(List.of("vitals", "inventory"), ModulesJson.decode("[\"vitals\",\"inventory\"]"));
        assertEquals(List.of(), ModulesJson.decode("[]"));
    }

    @Test
    void decodeOfBlankOrNullIsEmpty() {
        assertTrue(ModulesJson.decode(null).isEmpty());
        assertTrue(ModulesJson.decode("").isEmpty());
        assertTrue(ModulesJson.decode("   ").isEmpty());
    }

    @Test
    void decodeRejectsGarbageWithStorageException() {
        assertThrows(StorageException.class, () -> ModulesJson.decode("not json at all"));
        assertThrows(StorageException.class, () -> ModulesJson.decode("{\"a\":1}"));
    }

    @Test
    void decodeReturnsAnUnmodifiableList() {
        List<String> decoded = ModulesJson.decode("[\"a\"]");
        assertThrows(UnsupportedOperationException.class, () -> decoded.add("b"));
    }
}
