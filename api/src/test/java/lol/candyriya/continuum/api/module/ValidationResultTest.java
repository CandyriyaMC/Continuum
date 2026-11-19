package lol.candyriya.continuum.api.module;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidationResultTest {

    @Test
    void okHasNoReason() {
        ValidationResult ok = ValidationResult.ok();

        assertTrue(ok.valid());
        assertNull(ok.reason());
    }

    @Test
    void invalidCarriesTheReason() {
        ValidationResult invalid = ValidationResult.invalid("inventory size mismatch");

        assertFalse(invalid.valid());
        assertEquals("inventory size mismatch", invalid.reason());
    }

    @Test
    void validResultDropsAnyReason() {
        ValidationResult normalized = new ValidationResult(true, "ignored");

        assertNull(normalized.reason());
    }

    @Test
    void invalidWithoutReasonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ValidationResult(false, null));
        assertThrows(IllegalArgumentException.class, () -> new ValidationResult(false, ""));
        assertThrows(IllegalArgumentException.class, () -> new ValidationResult(false, "   "));
    }

    @Test
    void invalidFactoryRejectsBlankReasons() {
        assertThrows(IllegalArgumentException.class, () -> ValidationResult.invalid(null));
        assertThrows(IllegalArgumentException.class, () -> ValidationResult.invalid(""));
        assertThrows(IllegalArgumentException.class, () -> ValidationResult.invalid("  "));
    }
}
