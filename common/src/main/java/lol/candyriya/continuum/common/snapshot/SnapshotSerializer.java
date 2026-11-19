package lol.candyriya.continuum.common.snapshot;

import lol.candyriya.continuum.common.config.SerializationConfig;
import lol.candyriya.continuum.common.internal.JacksonMappers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Jackson 3 (JSON) snapshot codec. Storage- and transport-agnostic: bytes in,
 * bytes out, no file or database coupling.
 *
 * <p>Wire conventions (stable, part of the storage contract): snake_case keys,
 * enums as declared constant names, {@code byte[]} as Base64 strings, instants
 * as epoch-milli numbers. Renaming anything here requires a schema bump.</p>
 *
 * <p>Reads auto-detect gzip by magic bytes, so toggling {@code gzip} in config
 * never strands old snapshots. Writes honor the configured compression. The
 * size guard applies to the stored (possibly compressed) payload on write, and
 * to the decompressed payload on read, so a config-shrinking deploy cannot be
 * used to smuggle oversized documents in through the read path.</p>
 *
 * <p>NO Java native serialization anywhere in this path.</p>
 */
public final class SnapshotSerializer {

    private static final int GZIP_MAGIC_0 = 0x1f;
    private static final int GZIP_MAGIC_1 = 0x8b;

    private final boolean gzip;
    private final int maxSnapshotBytes;
    private final JsonMapper mapper;
    private final SnapshotMigrationRunner migrations;

    public SnapshotSerializer(SerializationConfig config) {
        this(config, new SnapshotMigrationRunner(java.util.List.of()));
    }

    public SnapshotSerializer(SerializationConfig config, SnapshotMigrationRunner migrations) {
        this.gzip = config.gzip();
        this.maxSnapshotBytes = config.maxSnapshotBytes();
        this.mapper = JacksonMappers.create();
        this.migrations = migrations;
    }

    /** Serializes {@code envelope} to bytes, enforcing the configured size guard. */
    public byte[] write(SnapshotEnvelope envelope) {
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            throw new SnapshotSerializationException("failed to serialize snapshot for player " + envelope.playerId(), e);
        }
        byte[] stored = gzip ? compress(json) : json;
        if (stored.length > maxSnapshotBytes) {
            throw new SnapshotSerializationException(
                    "snapshot for player " + envelope.playerId() + " is " + stored.length
                            + " bytes, limit is " + maxSnapshotBytes + "; refusing to write");
        }
        return stored;
    }

    /**
     * Decodes and migrates {@code bytes}. Tolerates plain-JSON snapshots even
     * when gzip is currently enabled (and vice versa) via magic-byte detection.
     */
    public SnapshotEnvelope read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new SnapshotSerializationException("snapshot payload is empty");
        }
        if (bytes.length > maxSnapshotBytes) {
            throw new SnapshotSerializationException(
                    "stored snapshot is " + bytes.length + " bytes, limit is " + maxSnapshotBytes);
        }
        byte[] json = isGzip(bytes) ? decompress(bytes) : bytes;
        if (json.length > maxSnapshotBytes) {
            throw new SnapshotSerializationException(
                    "decompressed snapshot is " + json.length + " bytes, limit is " + maxSnapshotBytes);
        }
        JsonNode tree;
        try {
            tree = mapper.readTree(json);
        } catch (Exception e) {
            throw new SnapshotSerializationException("snapshot payload is not valid JSON", e);
        }
        if (tree == null || !tree.isObject()) {
            throw new SnapshotSerializationException("snapshot payload must be a JSON object");
        }
        JsonNode migrated = migrations.migrate(tree);
        SnapshotEnvelope envelope;
        try {
            envelope = mapper.treeToValue(migrated, SnapshotEnvelope.class);
        } catch (Exception e) {
            throw new SnapshotSerializationException("snapshot payload does not match the schema after migration", e);
        }
        if (envelope == null) {
            throw new SnapshotSerializationException("snapshot payload decoded to null");
        }
        return envelope;
    }

    private byte[] compress(byte[] json) {
        var out = new ByteArrayOutputStream(Math.max(64, json.length / 2));
        try (var gzipOut = new GZIPOutputStream(out)) {
            gzipOut.write(json);
        } catch (IOException e) {
            // ByteArrayOutputStream cannot throw, so this is a bug elsewhere — wrap, do not swallow
            throw new UncheckedIOException("gzip compression failed", e);
        }
        return out.toByteArray();
    }

    private byte[] decompress(byte[] bytes) {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(bytes));
             var out = new ByteArrayOutputStream(Math.max(64, bytes.length * 4))) {
            byte[] buffer = new byte[8_192];
            long total = 0;
            int read;
            // read at most maxSnapshotBytes + 1: enough to detect a breach without trusting the stream
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxSnapshotBytes) {
                    throw new SnapshotSerializationException(
                            "decompressed snapshot exceeds limit of " + maxSnapshotBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (SnapshotSerializationException e) {
            throw e;
        } catch (IOException e) {
            throw new SnapshotSerializationException("snapshot payload is not readable gzip", e);
        }
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xff) == GZIP_MAGIC_0 && (bytes[1] & 0xff) == GZIP_MAGIC_1;
    }
}
