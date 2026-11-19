package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingTest {

    private Staging stagingFor(Path server, String jarName) throws IOException {
        Files.createDirectories(server.resolve("plugins/update"));
        Path currentJar = server.resolve("plugins").resolve(jarName);
        Files.write(currentJar, "current".getBytes());
        return new Staging(server.resolve("plugins/continuum-data"), server.resolve("plugins"),
                currentJar, LoggerFactory.getLogger("test"));
    }

    @Test
    void backupCopiesTheRunningJarOutsidePlugins(@TempDir Path server) throws Exception {
        Staging staging = stagingFor(server, "continuum-paper.jar");
        Path backup = staging.backupCurrentJar();
        assertTrue(backup.startsWith(server.resolve(".continuum-backup")));
        assertFalse(backup.startsWith(server.resolve("plugins")));
        assertEquals("current", Files.readString(backup));
    }

    @Test
    void strayPartFromOurNameIsRemoved(@TempDir Path server) throws Exception {
        Staging staging = stagingFor(server, "continuum-paper.jar");
        Path part = server.resolve("plugins/update/continuum-paper.jar.part");
        Files.write(part, new byte[128]);
        Path other = server.resolve("plugins/update/unrelated-plugin.jar.part");
        Files.write(other, new byte[128]);

        staging.cleanupStrayPart();

        assertFalse(Files.exists(part));
        assertTrue(Files.exists(other));
    }

    @Test
    void stagePartIntoUpdateDirMoves(@TempDir Path server) throws Exception {
        Staging staging = stagingFor(server, "continuum-paper.jar");
        Path part = server.resolve("plugins/tmp-download.part");
        Files.write(part, "downloaded".getBytes());
        staging.stagePartIntoUpdateDir(part);

        assertFalse(Files.exists(part));
        assertEquals("downloaded", Files.readString(server.resolve("plugins/update/continuum-paper.jar")));
    }

    @Test
    void backupRefusalWhenJarMissing(@TempDir Path server) {
        Path nowhere = server.resolve("plugins/not-there.jar");
        Staging staging = new Staging(server.resolve("data"), server.resolve("plugins"),
                nowhere, LoggerFactory.getLogger("test"));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, staging::backupCurrentJar);
    }

    @Test
    void deleteBackupRefusesPathsOutsideTheBackupDir(@TempDir Path server) throws Exception {
        Staging staging = stagingFor(server, "continuum-paper.jar");
        Path outside = server.resolve("keep-me.txt");
        Files.write(outside, "important".getBytes());

        staging.deleteBackupQuietly(outside.toString());
        staging.deleteBackupQuietly("../../escape.jar");

        assertTrue(Files.exists(outside));
    }
}
