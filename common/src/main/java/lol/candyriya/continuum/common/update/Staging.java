package lol.candyriya.continuum.common.update;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * File layout for staging: {@code <plugins>/update/} for the staged jar,
 * {@code <serverRoot>/.continuum-backup/} for pre-swap backups, and the
 * plugin's data directory for state.
 *
 * <p>Backups deliberately live <em>outside</em> {@code plugins/} - a stray
 * jar in {@code plugins/update/} would be picked up by Paper as a second
 * copy of the plugin. Nothing in this class touches files it did not name:
 * cleanup only ever removes {@code <our jar name>.part} and, on a clean
 * boot, the exact backup file recorded in the state.</p>
 */
public final class Staging {

    private final Path dataDirectory;
    private final Path updateDir;
    private final Path backupDir;
    private final Path currentJarPath;
    private final Logger logger;

    public Staging(Path dataDirectory, Path pluginsDir, Path currentJarPath, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.updateDir = pluginsDir.resolve("update");
        Path serverRoot = pluginsDir.toAbsolutePath().normalize().getParent();
        this.backupDir = (serverRoot != null ? serverRoot : pluginsDir).resolve(".continuum-backup");
        this.currentJarPath = currentJarPath;
        this.logger = logger;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path updateDir() {
        return updateDir;
    }

    public Path backupDir() {
        return backupDir;
    }

    /** May be null in dev environments - callers degrade to "cannot back up/swap" instead of exploding. */
    public Path currentJarPath() {
        return currentJarPath;
    }

    /** File name the running plugin jar is (or would be) installed as; our staged artifact and backups follow it. */
    public String currentJarFileName() {
        return currentJarPath != null ? currentJarPath.getFileName().toString() : "continuum.jar";
    }

    public Path destinationInUpdateDir() {
        return updateDir.resolve(currentJarFileName());
    }

    /** Removes a stale partial download of our own artifact; never touches other plugins' files. */
    public void cleanupStrayPart() {
        try {
            Files.deleteIfExists(updateDir.resolve(currentJarFileName() + ".part"));
        } catch (IOException e) {
            logger.debug("could not remove a stale .part file: {}", e.toString());
        }
        // rename-to-backup leftovers from a crashed apply: only ours, only older than a day
        if (!Files.isDirectory(backupDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(backupDir)) {
            long cutoff = Instant.now().getEpochSecond() - 24 * 3600;
            entries.filter(p -> p.getFileName().toString().startsWith(currentJarFileName() + "."))
                    .filter(p -> p.getFileName().toString().endsWith(".old"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() / 1000 < cutoff;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            logger.info("expired backup {} from a long-forgotten swap - removed", p);
                        } catch (IOException e) {
                            logger.debug("could not expire backup {}: {}", p, e.toString());
                        }
                    });
        } catch (IOException e) {
            logger.debug("could not list {}: {}", backupDir, e.toString());
        }
    }

    /**
     * Copies the running jar into {@code .continuum-backup/}. Called before
     * anything is moved into {@code plugins/update/}; without the copy the
     * swap would be unrecoverable.
     *
     * @return the backup file path
     * @throws IOException when the copy fails - callers abort staging on this
     */
    public Path backupCurrentJar() throws IOException {
        if (currentJarPath == null || !Files.isRegularFile(currentJarPath)) {
            throw new IOException("current plugin jar not found at " + currentJarPath + "; refusing to stage unverifiably");
        }
        Files.createDirectories(backupDir);
        String backupName = currentJarFileName() + "." + Instant.now().getEpochSecond() + ".old";
        Path target = backupDir.resolve(backupName);
        Files.copy(currentJarPath, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Moves a verified {@code .part} into {@code plugins/update/} under the running jar's name. */
    public void stagePartIntoUpdateDir(Path part) throws IOException {
        Files.createDirectories(updateDir);
        Path target = destinationInUpdateDir();
        moveAtomic(part, target);
    }

    /** Deletes a backup recorded in the state; missing files are fine. */
    public void deleteBackupQuietly(String backupFile) {
        if (backupFile == null || backupFile.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(backupFile);
            // refuse to resolve anything outside the backup dir - state is data we wrote, not a command
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(backupDir.toAbsolutePath().normalize())) {
                logger.warn("backup file {} is outside {}; refusing to delete it", normalized, backupDir);
                return;
            }
            Files.deleteIfExists(normalized);
        } catch (RuntimeException | IOException e) {
            logger.debug("could not delete backup {}: {}", backupFile, e.toString());
        }
    }

    /** Moves {@code from} onto {@code to} atomically when the fs allows it. */
    public static void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
