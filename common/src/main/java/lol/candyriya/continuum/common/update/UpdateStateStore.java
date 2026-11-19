package lol.candyriya.continuum.common.update;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Atomic, corruption-tolerant persistence for {@link UpdateState}.
 *
 * <p>Writes go to a sibling temp file and rename into place, so a crash
 * mid-write can never produce a half-parsed state that mails itself
 * somewhere surprising. A corrupt state file is treated as an empty one
 * (update simply re-derives everything) and renamed aside once, never
 * deleted silently.</p>
 */
public final class UpdateStateStore {

    public static final String FILE_NAME = ".update-state";

    private static final JsonMapper MAPPER = new JsonMapper();

    private final Path file;
    private final Logger logger;

    public UpdateStateStore(Path dataDirectory, Logger logger) {
        this.file = dataDirectory.resolve(FILE_NAME);
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public UpdateState read() {
        if (!Files.exists(file)) {
            return UpdateState.empty();
        }
        try {
            byte[] raw = Files.readAllBytes(file);
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new IOException("state root is not an object");
            }
            return new UpdateState(
                    text(root, "staged_build"),
                    text(root, "staged_sha256"),
                    text(root, "backup_file"),
                    text(root, "swapped_at"),
                    text(root, "pending_notified_version"),
                    longOrNull(root, "last_license_nag_epoch_day"));
        } catch (Exception e) {
            logger.warn("{} is unreadable ({}); treating it as empty - update state is re-derived on the next successful check",
                    file, abbreviate(e));
            try {
                Path aside = file.resolveSibling(FILE_NAME + ".broken-" + Instant.now().getEpochSecond());
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                logger.debug("could not move the corrupt state file aside: {}", moveFailure.toString());
            }
            return UpdateState.empty();
        }
    }

    public void write(UpdateState state) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            putOrSkip(root, "staged_build", state.stagedBuild());
            putOrSkip(root, "staged_sha256", state.stagedSha256());
            putOrSkip(root, "backup_file", state.backupFile());
            putOrSkip(root, "swapped_at", state.swappedAt());
            putOrSkip(root, "pending_notified_version", state.pendingNotifiedVersion());
            if (state.lastLicenseNagEpochDay() != null) {
                root.put("last_license_nag_epoch_day", state.lastLicenseNagEpochDay());
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.write(temp, MAPPER.writeValueAsBytes(root));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("could not persist {} ({}); the swap still applies at restart, only the memory is lost",
                    file, abbreviate(e));
        }
    }

    private static String text(JsonNode root, String name) {
        JsonNode node = root.get(name);
        return node != null && node.isString() && !node.stringValue().isBlank() ? node.stringValue() : null;
    }

    private static Long longOrNull(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.longValue();
    }

    private static void putOrSkip(ObjectNode root, String name, String value) {
        if (value != null && !value.isBlank()) {
            root.put(name, value);
        }
    }

    private static String abbreviate(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 160 ? singleLine.substring(0, 160) : singleLine;
    }
}
