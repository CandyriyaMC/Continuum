package lol.candyriya.continuum.velocity.update;

import lol.candyriya.continuum.common.update.Staging;
import lol.candyriya.continuum.common.update.UpdateState;
import lol.candyriya.continuum.common.update.UpdateStateStore;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The Velocity-side apply step: rename-swap the running jar.
 *
 * <p>Paper applies anything dropped into {@code plugins/update/} at boot;
 * Velocity has no such mechanism (and never shipped one), so the proxy does
 * the swap itself, as the very first thing it does at enable - before the
 * command tree, before the messaging channel, before anything observes the
 * world:</p>
 * <ol>
 *   <li>if a staged jar sits in {@code plugins/update/<jarName>}, verify it
 *       against the sha256 recorded in the state file (when present);</li>
 *   <li>rename the running jar into {@code <serverRoot>/.continuum-backup/};</li>
 *   <li>move the staged artifact into its place.</li>
 * </ol>
 *
 * <p>Velocity loads plugin classes before firing {@code ProxyInitializeEvent},
 * so the running JVM keeps executing the old build for this boot; the new
 * build takes over at the next restart. The following boot detects the
 * version change, deletes the backup and closes the loop. Any failure leaves
 * both files untouched and the proxy running its old build - fail-open,
 * always.</p>
 *
 * <p>Backups live <em>outside</em> the plugins folder on purpose: a stray
 * jar under {@code plugins/} would be loaded by Paper next to the real one.</p>
 */
final class JarSwap {

    private JarSwap() {
    }

    /**
     * Swaps a staged artifact into place when one exists. Runs before anything else at enable.
     *
     * @return true when a swap happened (the proxy still runs the old build until restart)
     */
    static boolean swapIfStaged(Path dataDirectory, Path pluginsDir, Path currentJar, Logger logger) {
        Path updateDir = pluginsDir.resolve("update");
        String jarName = currentJar != null ? currentJar.getFileName().toString() : "continuum-velocity.jar";
        Path staged = updateDir.resolve(jarName);
        if (!Files.isRegularFile(staged)) {
            return false;
        }
        if (currentJar == null || !Files.isRegularFile(currentJar)) {
            logger.warn("staged update present at {} but the running jar path is unresolvable; leaving it in place for a manual swap, proxy runs the current build",
                    staged);
            return false;
        }
        try {
            if (Files.size(staged) == 0) {
                logger.warn("staged update at {} is empty; refusing to touch a working proxy", staged);
                return false;
            }
        } catch (IOException e) {
            logger.warn("could not stat the staged artifact at {}; leaving everything untouched", staged, e);
            return false;
        }

        // verify against the recorded sha256 when the staging side saved one
        UpdateStateStore store = new UpdateStateStore(dataDirectory, logger);
        UpdateState state = store.read();
        String expected = state.stagedSha256();
        if (expected != null) {
            String actual = sha256Hex(staged, logger);
            if (actual == null) {
                logger.warn("could not hash the staged artifact at {}; refusing to swap", staged);
                return false;
            }
            if (!actual.equalsIgnoreCase(expected)) {
                logger.warn("staged artifact at {} does not match its recorded sha256; refusing to swap (move it away and re-download)",
                        staged);
                return false;
            }
        } else {
            logger.warn("staged artifact at {} has no recorded sha256 (staged by hand?); verifying structure only", staged);
            if (!looksLikeJar(staged, logger)) {
                logger.warn("staged artifact at {} is not a zip/jar; refusing to swap", staged);
                return false;
            }
        }

        Path serverRoot = pluginsDir.toAbsolutePath().normalize().getParent();
        Path backupDir = (serverRoot != null ? serverRoot : pluginsDir).resolve(".continuum-backup");
        try {
            Files.createDirectories(backupDir);
            Path backup = backupDir.resolve(jarName + "." + java.time.Instant.now().getEpochSecond() + ".old");
            Files.copy(currentJar, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                Staging.moveAtomic(staged, currentJar);
            } catch (IOException e) {
                logger.error("could not move the staged artifact onto {} ({}); restoring the backup and keeping the running build", currentJar, e.toString());
                Files.copy(backup, currentJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return false;
            }
            logger.warn("continuum update staged at {} - the new build takes over at the next restart (previous jar kept at {})",
                    currentJar, backup);
            // keep the state file, but point it at the fresh backup this swap just made -
            // the staging-time backup holds the same bytes and can go
            if (state.hasStagedUpdate()) {
                String previous = state.backupFile();
                if (previous != null && !previous.equals(backup.toString())) {
                    try {
                        Path prev = Path.of(previous).toAbsolutePath().normalize();
                        if (prev.startsWith(backupDir.toAbsolutePath().normalize())) {
                            Files.deleteIfExists(prev);
                        }
                    } catch (IOException e) {
                        logger.debug("could not retire the staging-time backup {}: {}", previous, e.toString());
                    }
                }
                store.write(state.withStaged(state.stagedBuild(), state.stagedSha256(),
                        backup.toString(), java.time.Instant.now().toString()));
            }
            // the next boot proves the swap by reporting the new version
            return true;
        } catch (IOException | RuntimeException e) {
            logger.error("update swap failed; the proxy runs its current build unchanged", e);
            return false;
        }
    }

    private static String sha256Hex(Path file, Logger logger) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException | SecurityException e) {
            logger.debug("could not hash {}: {}", file, e.toString());
            return null;
        }
    }

    private static boolean looksLikeJar(Path file, Logger logger) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            // zip magic: PK\3\4 (or empty-archive/ spanned PK\7\8 / PK\1\2)
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K';
        } catch (IOException e) {
            logger.debug("could not peek {}: {}", file, e.toString());
            return false;
        }
    }
}
