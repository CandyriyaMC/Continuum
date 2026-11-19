package lol.candyriya.continuum.velocity.update;

import lol.candyriya.continuum.common.update.UpdateState;
import lol.candyriya.continuum.common.update.UpdateStateStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarSwapTest {

    @TempDir
    Path server;

    private record Layout(Path dataDirectory, Path pluginsDir, Path currentJar, Path updateDir, Path backupDir) {
    }

    private Layout layout() throws IOException {
        Path pluginsDir = Files.createDirectories(server.resolve("plugins"));
        Path dataDirectory = Files.createDirectories(pluginsDir.resolve("continuum"));
        Path currentJar = pluginsDir.resolve("continuum-velocity.jar");
        Files.write(currentJar, "running build bytes".getBytes());
        Path updateDir = Files.createDirectories(pluginsDir.resolve("update"));
        Path backupDir = server.resolve(".continuum-backup");
        return new Layout(dataDirectory, pluginsDir, currentJar, updateDir, backupDir);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private UpdateStateStore store(Layout layout) {
        return new UpdateStateStore(layout.dataDirectory(), LoggerFactory.getLogger("test"));
    }

    @Test
    void swapMovesTheStagedJarAndBacksUpTheOldOne() throws Exception {
        Layout layout = layout();
        byte[] stagedBytes = "staged build 120 bytes".getBytes();
        Files.write(layout.updateDir().resolve("continuum-velocity.jar"), stagedBytes);
        store(layout).write(new UpdateState("build-120", sha256Hex(stagedBytes), null,
                "2026-11-19T00:00:00Z", null, null));

        boolean swapped = JarSwap.swapIfStaged(layout.dataDirectory(), layout.pluginsDir(),
                layout.currentJar(), LoggerFactory.getLogger("test"));

        assertTrue(swapped);
        assertEquals("staged build 120 bytes", Files.readString(layout.currentJar()));
        // backup is outside plugins/, holds the pre-swap jar
        assertFalse(layout.backupDir().startsWith(layout.pluginsDir()));
        try (var entries = Files.list(layout.backupDir())) {
            Path backup = entries.findFirst().orElseThrow();
            assertEquals("running build bytes", Files.readString(backup));
        }
        // the update folder is empty again
        try (var entries = Files.list(layout.updateDir())) {
            assertTrue(entries.findAny().isEmpty());
        }
        // the state now points at the fresh backup
        assertTrue(store(layout).read().backupFile().contains(".continuum-backup"));
    }

    @Test
    void shaMismatchRefusesTheSwap() throws Exception {
        Layout layout = layout();
        Files.write(layout.updateDir().resolve("continuum-velocity.jar"), "untrusted bytes".getBytes());
        store(layout).write(new UpdateState("build-120", "f".repeat(64), null,
                "2026-11-19T00:00:00Z", null, null));

        boolean swapped = JarSwap.swapIfStaged(layout.dataDirectory(), layout.pluginsDir(),
                layout.currentJar(), LoggerFactory.getLogger("test"));

        assertFalse(swapped);
        assertEquals("running build bytes", Files.readString(layout.currentJar()));
        assertTrue(Files.exists(layout.updateDir().resolve("continuum-velocity.jar")));
    }

    @Test
    void emptyStagedFileIsRefused() throws Exception {
        Layout layout = layout();
        Files.write(layout.updateDir().resolve("continuum-velocity.jar"), new byte[0]);

        boolean swapped = JarSwap.swapIfStaged(layout.dataDirectory(), layout.pluginsDir(),
                layout.currentJar(), LoggerFactory.getLogger("test"));

        assertFalse(swapped);
        assertEquals("running build bytes", Files.readString(layout.currentJar()));
    }

    @Test
    void nothingStagedIsANoOp() throws Exception {
        Layout layout = layout();

        boolean swapped = JarSwap.swapIfStaged(layout.dataDirectory(), layout.pluginsDir(),
                layout.currentJar(), LoggerFactory.getLogger("test"));

        assertFalse(swapped);
        assertEquals("running build bytes", Files.readString(layout.currentJar()));
    }

    @Test
    void handStagedValidJarSwapsWithStructureCheckOnly() throws Exception {
        Layout layout = layout();
        // minimal zip: PK\3\4 magic + filler, no state file present
        byte[] zip = new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00, 0x00, 0x00};
        Files.write(layout.updateDir().resolve("continuum-velocity.jar"), zip);

        boolean swapped = JarSwap.swapIfStaged(layout.dataDirectory(), layout.pluginsDir(),
                layout.currentJar(), LoggerFactory.getLogger("test"));

        assertTrue(swapped);
        assertEquals(zip.length, Files.size(layout.currentJar()));
    }

    @Test
    void missingRunningJarStaysUntouched() throws Exception {
        Layout layout = layout();
        Files.write(layout.updateDir().resolve("continuum-velocity.jar"), "staged".getBytes());
        Files.delete(layout.currentJar());

        boolean swapped = JarSwap.swapIfStaged(layout.dataDirectory(), layout.pluginsDir(),
                layout.currentJar(), LoggerFactory.getLogger("test"));

        assertFalse(swapped);
        assertTrue(Files.exists(layout.updateDir().resolve("continuum-velocity.jar")));
    }

    @Test
    void stagingTimeBackupIsRetiredOnSwap() throws Exception {
        Layout layout = layout();
        byte[] stagedBytes = "staged build 120 bytes".getBytes();
        Files.write(layout.updateDir().resolve("continuum-velocity.jar"), stagedBytes);
        Files.createDirectories(layout.backupDir());
        Path stagingBackup = layout.backupDir().resolve("continuum-velocity.jar.111.old");
        Files.write(stagingBackup, "staging-time backup".getBytes());
        store(layout).write(new UpdateState("build-120", sha256Hex(stagedBytes),
                stagingBackup.toString(), "2026-11-19T00:00:00Z", null, null));

        boolean swapped = JarSwap.swapIfStaged(layout.dataDirectory(), layout.pluginsDir(),
                layout.currentJar(), LoggerFactory.getLogger("test"));

        assertTrue(swapped);
        // replaced by the fresh swap-time backup; the stale one is gone
        assertFalse(Files.exists(stagingBackup));
        try (var entries = Files.list(layout.backupDir())) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void nonZipHandStagedFileIsRefused() throws Exception {
        Layout layout = layout();
        Files.write(layout.updateDir().resolve("continuum-velocity.jar"), "definitely not a zip".getBytes());

        boolean swapped = JarSwap.swapIfStaged(layout.dataDirectory(), layout.pluginsDir(),
                layout.currentJar(), LoggerFactory.getLogger("test"));

        assertFalse(swapped);
        assertEquals("running build bytes", Files.readString(layout.currentJar()));
    }
}
