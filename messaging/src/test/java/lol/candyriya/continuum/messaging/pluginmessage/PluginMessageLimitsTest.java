package lol.candyriya.continuum.messaging.pluginmessage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginMessageLimitsTest {

    @Test
    void defaultIs64KiBIncludingHeader() {
        assertEquals(65_536, PluginMessageLimits.DEFAULT_MAX_FRAME_BYTES);
        assertEquals(65_536 - PluginMessageProtocol.HEADER_BYTES, PluginMessageLimits.defaults().maxPayloadBytes());
    }

    @Test
    void absoluteCeilingMatchesThePlatformLimit() {
        assertEquals(1_048_576, PluginMessageLimits.ABSOLUTE_MAX_FRAME_BYTES);
        assertEquals(new PluginMessageLimits(1_048_576).maxFrameBytes(), 1_048_576);
    }

    @Test
    void tooSmallACapIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PluginMessageLimits(0));
        assertThrows(IllegalArgumentException.class, () -> new PluginMessageLimits(-1));
        assertThrows(IllegalArgumentException.class, () -> new PluginMessageLimits(63));
    }

    @Test
    void minimumCapIsAccepted() {
        assertDoesNotThrow(() -> new PluginMessageLimits(PluginMessageLimits.MIN_MAX_FRAME_BYTES));
    }

    @Test
    void tooLargeACapIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PluginMessageLimits(PluginMessageLimits.ABSOLUTE_MAX_FRAME_BYTES + 1));
    }
}
