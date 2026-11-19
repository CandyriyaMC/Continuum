package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateStateStoreTest {

    @TempDir
    Path dir;

    private UpdateStateStore store() {
        return new UpdateStateStore(dir, LoggerFactory.getLogger("test"));
    }

    @Test
    void roundtripPreservesFields() {
        UpdateState state = new UpdateState("build-120", "deadbeef", "/backups/x.old",
                "2026-11-19T10:00:00Z", "build-120", 20345L);
        store().write(state);

        UpdateState read = store().read();
        assertEquals("build-120", read.stagedBuild());
        assertEquals("deadbeef", read.stagedSha256());
        assertEquals("/backups/x.old", read.backupFile());
        assertEquals("2026-11-19T10:00:00Z", read.swappedAt());
        assertEquals("build-120", read.pendingNotifiedVersion());
        assertEquals(20345L, read.lastLicenseNagEpochDay());
    }

    @Test
    void missingFileReadsAsEmpty() {
        UpdateState read = store().read();
        assertFalse(read.hasStagedUpdate());
        assertEquals(null, read.pendingNotifiedVersion());
    }

    @Test
    void corruptFileIsRepairedToEmpty() throws Exception {
        Files.writeString(dir.resolve(".update-state"), "this is { not json");
        UpdateState read = store().read();

        assertFalse(read.hasStagedUpdate());
        assertFalse(Files.exists(dir.resolve(".update-state")));
        // moved aside, not silently deleted
        try (var entries = Files.list(dir)) {
            assertTrue(entries.anyMatch(p -> p.getFileName().toString().startsWith(".update-state.broken-")));
        }
    }

    @Test
    void partialFieldsSurviveARoundtrip() {
        UpdateState state = new UpdateState(null, null, null, null, "build-121", null);
        store().write(state);
        UpdateState read = store().read();
        assertEquals("build-121", read.pendingNotifiedVersion());
        assertFalse(read.hasStagedUpdate());
    }
}
