package lol.candyriya.continuum.paper.modules.attributes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationCodecTest {

    @Test
    void knownNamesExactlyMatchTheWireContract() {
        // the wire format pins these exact names — renaming one breaks stored snapshots
        assertEquals(
                java.util.Set.of("ADD_NUMBER", "ADD_SCALAR", "MULTIPLY_SCALAR_1"),
                OperationCodec.knownNames());
    }

    @Test
    void canonicalNamesRoundTrip() {
        for (String known : OperationCodec.knownNames()) {
            assertEquals(known, OperationCodec.canonical(known).orElseThrow());
        }
    }

    @Test
    void unknownNamesYieldEmptyAndAreNeverThrown() {
        assertTrue(OperationCodec.canonical("DIVIDE").isEmpty());
        assertTrue(OperationCodec.canonical("increment").isEmpty());
        assertTrue(OperationCodec.canonical("").isEmpty());
        assertTrue(OperationCodec.canonical("   ").isEmpty());
        assertTrue(OperationCodec.canonical(null).isEmpty());

        assertFalse(OperationCodec.isKnown("DIVIDE"));
        assertFalse(OperationCodec.isKnown(null));
        assertTrue(OperationCodec.isKnown("ADD_NUMBER"));
    }

    @Test
    void lookupIsLenientAboutCaseAndWhitespace() {
        assertEquals("ADD_NUMBER", OperationCodec.canonical(" add_number ").orElseThrow());
        assertEquals("MULTIPLY_SCALAR_1", OperationCodec.canonical("multiply_scalar_1").orElseThrow());
        assertEquals("ADD_SCALAR", OperationCodec.canonical("Add_Scalar").orElseThrow());
    }
}
