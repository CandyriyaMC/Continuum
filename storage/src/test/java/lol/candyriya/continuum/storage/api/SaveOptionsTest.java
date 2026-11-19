package lol.candyriya.continuum.storage.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveOptionsTest {

    @Test
    void defaultsAreApiAndUnknownBackend() {
        SaveOptions options = SaveOptions.defaults();
        assertEquals(SaveOptions.REASON_API, options.reason());
        assertEquals(SaveOptions.BACKEND_UNKNOWN, options.sourceBackend());
        assertTrue(options.outbox().isEmpty());
    }

    @Test
    void ofCreatesOptionsWithoutOutbox() {
        SaveOptions options = SaveOptions.of("AUTOSAVE", "survival-1");
        assertEquals("AUTOSAVE", options.reason());
        assertEquals("survival-1", options.sourceBackend());
        assertTrue(options.outbox().isEmpty());
    }

    @Test
    void reasonMustBeNonBlankAndAtMost32() {
        assertThrows(IllegalArgumentException.class, () -> SaveOptions.of("  ", "b"));
        assertThrows(IllegalArgumentException.class, () -> SaveOptions.of("r".repeat(33), "b"));
        SaveOptions.of("r".repeat(32), "b"); // boundary is fine
    }

    @Test
    void backendMustBeNonBlankAndAtMost64() {
        assertThrows(IllegalArgumentException.class, () -> SaveOptions.of("ok", ""));
        assertThrows(IllegalArgumentException.class, () -> SaveOptions.of("ok", "b".repeat(65)));
        SaveOptions.of("ok", "b".repeat(64));
    }

    @Test
    void nullOutboxBecomesEmpty() {
        SaveOptions options = new SaveOptions("TRANSFER", "survival-1", null);
        assertTrue(options.outbox().isEmpty());
    }

    @Test
    void outboxDraftsAreCopied() {
        UUID player = UUID.randomUUID(); // not needed but keeps semantics obvious
        List<SaveOptions.OutboxDraft> drafts = new java.util.ArrayList<>();
        drafts.add(new SaveOptions.OutboxDraft("transfer.done", "{}"));
        SaveOptions options = new SaveOptions("TRANSFER", "survival-1", drafts);
        drafts.clear(); // mutating the source list must not affect the record
        assertEquals(1, options.outbox().size());
        assertThrows(UnsupportedOperationException.class, () -> options.outbox().add(
                new SaveOptions.OutboxDraft("x", "{}")));
    }

    @Test
    void draftValidatesMsgTypeAndPayload() {
        assertThrows(IllegalArgumentException.class, () -> new SaveOptions.OutboxDraft("", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new SaveOptions.OutboxDraft("x".repeat(65), "{}"));
        assertThrows(NullPointerException.class, () -> new SaveOptions.OutboxDraft("t", null));
        new SaveOptions.OutboxDraft("x".repeat(64), "{}"); // boundary is fine
    }
}
