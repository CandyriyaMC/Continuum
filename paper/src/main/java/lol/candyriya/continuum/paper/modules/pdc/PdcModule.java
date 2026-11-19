package lol.candyriya.continuum.paper.modules.pdc;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs the player entity's {@link PersistentDataContainer} across backends -
 * the escape hatch for plugin-set flags that are not worth a dedicated module
 * (cosmetics, RPG classes, quest markers, premium grants).
 *
 * <p><b>What travels, what does not.</b> Only entries whose stored type can be
 * carried losslessly survive the trip. The container reports its own primitive
 * storage type per key ({@code getPrimitiveStorageType}), so no blind
 * type-guessing happens. Carried: {@code BYTE}, {@code SHORT}, {@code INTEGER},
 * {@code LONG}, {@code FLOAT}, {@code DOUBLE}, {@code STRING}, {@code BYTE_ARRAY},
 * {@code INTEGER_ARRAY}, {@code LONG_ARRAY} and nested {@code TAG_CONTAINER}s
 * (serialized to bytes and back on the other side). Not carried: {@code TAG_LIST}
 * values (an empty list cannot even reveal its element type - guessing would corrupt
 * data, so they are skipped and counted), the deprecated {@code TAG_CONTAINER_ARRAY},
 * and anything the platform reports as unknown. Custom
 * {@code PersistentDataType} implementations (e.g. UUID-as-byte-array) travel fine,
 * because what is actually stored is the primitive backing - a {@code BYTE_ARRAY}.</p>
 *
 * <p><b>Booleans.</b> PDC has no native boolean; the convenience
 * {@code PersistentDataType.BOOLEAN} persists a byte. Such keys come back from
 * introspection as {@code BYTE}, travel as {@code BYTE} (0/1), and stay readable
 * as booleans on both sides - no semantic change.</p>
 *
 * <p><b>Non-finite floats.</b> {@code NaN} and {@code ±Infinity} are legal PDC
 * values but have no JSON representation - a snapshot carrying one could never be
 * read back. They are skipped at capture time (counted, key logged, value never
 * logged) and rejected at the payload model, so an unreadable snapshot is
 * unconstructable by design.</p>
 *
 * <p><b>The deny-list, inverted honestly.</b> PDC can carry secrets (auth tokens,
 * second-factor state), and blindly copying it across servers would leak them. The
 * config schema has no namespace list for this module yet, so the safe closed default
 * ships in code: everything syncs EXCEPT a small set of credential-shaped namespaces
 * ({@link PdcNamespaceDenyList}), which are skipped and logged at capture and restore
 * time (counts only, never values). This is an inversion of the usual allow-list
 * default, chosen because a closed config leaves no better option - operators who do
 * not want PDC data moved at all should disable this module entirely
 * ({@code sync.modules.pdc=false}), which is fully supported and moves nothing.</p>
 *
 * <p><b>Additive restore, never mirror.</b> Restore applies the allowed entries and
 * touches nothing else: foreign keys the target already has are left alone, and no
 * key is ever deleted. Deleting keys we do not own would be data destruction - the
 * snapshot cannot know whether an absent key means "the source deleted it" or
 * "the source never had it". The price is that stale keys accumulate on the target
 * server; that is the accepted tradeoff of add/update-only semantics, and it is the
 * only non-destructive choice.</p>
 *
 * <p><b>Size budgets.</b> A single value may not exceed
 * {@link PdcLimits#PER_KEY_BYTES} (64 KiB) - oversized values are skipped and
 * counted. The whole payload may not exceed {@link PdcLimits#TOTAL_BYTES} (1 MiB) -
 * a capture that would exceed it refuses the snapshot with a clear error instead of
 * storing half a PDC. Both limits exist because PDC bloat slows every player save.</p>
 *
 * <p><b>Item PDC is out of scope</b> - items are handled by the item pipeline. This
 * module covers the player entity only.</p>
 *
 * <p>Thread-safety: all state is read/written on the player's own thread; this class
 * keeps no mutable state of its own.</p>
 */
public final class PdcModule implements SyncModule<Player, PdcPayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdcModule.class);

    // ---------- lifecycle ----------

    @Override
    public String id() {
        return BuiltinModules.PDC;
    }

    @Override
    public int version() {
        return PdcPayload.FORMAT;
    }

    @Override
    public CompletionStage<PdcPayload> capture(Player player) {
        if (player == null) {
            throw new NullPointerException("player");
        }
        try {
            PdcAffinity.requirePlayerThread(player, "pdc capture");
            return CompletableFuture.completedFuture(doCapture(player));
        } catch (RuntimeException e) {
            // PdcCaptureException means "refused on purpose"; anything else is a platform bug - both are CAPTURE_FAILED, logged distinctly by the error level
            LOGGER.error("pdc capture failed for {}: {}", player.getUniqueId(), e.toString());
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<Void> restore(Player player, PdcPayload data) {
        if (player == null) {
            throw new NullPointerException("player");
        }
        if (data == null) {
            throw new NullPointerException("data");
        }
        try {
            PdcAffinity.requirePlayerThread(player, "pdc restore");
            doRestore(player, data);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            LOGGER.error("pdc restore failed for {}: {}", player.getUniqueId(), e.toString());
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, PdcPayload data) {
        if (player == null) {
            throw new NullPointerException("player");
        }
        if (data == null) {
            throw new NullPointerException("data");
        }
        try {
            PdcAffinity.requirePlayerThread(player, "pdc validate");
            return CompletableFuture.completedFuture(PdcValidation.validate(data));
        } catch (RuntimeException e) {
            // an off-thread validate means the platform broke the contract, not that the data is bad
            LOGGER.error("pdc validation could not run for {}: {}", player.getUniqueId(), e.toString());
            return CompletableFuture.failedFuture(e);
        }
    }

    // ---------- capture ----------

    private PdcPayload doCapture(Player player) {
        var container = player.getPersistentDataContainer();
        var limiter = PdcCaptureLimiter.defaults();
        var entries = new ArrayList<PdcEntry>();
        int denied = 0;
        int unsupported = 0;
        int oversize = 0;
        int nonFinite = 0;
        int encodeFailures = 0;

        // deterministic snapshot: same PDC state -> byte-identical payload
        var keys = new ArrayList<>(container.getKeys());
        keys.sort(Comparator.comparing(NamespacedKey::asString));

        for (NamespacedKey key : keys) {
            if (PdcNamespaceDenyList.isDenied(key.getNamespace())) {
                denied++;
                continue;
            }

            PdcValueType type;
            try {
                type = mapStorageType(container.getPrimitiveStorageType(key));
            } catch (RuntimeException e) {
                unsupported++;
                LOGGER.warn("pdc capture: cannot introspect '{}', skipping: {}", key.asString(), e.toString());
                continue;
            }
            if (type == null) {
                unsupported++;
                continue;
            }

            Object payloadValue;
            long size;
            try {
                var read = readAndEncode(container, key, type);
                if (read == null) {
                    // value vanished between introspection and read - nothing sane to sync
                    unsupported++;
                    continue;
                }
                payloadValue = read.value();
                size = read.sizeBytes();
            } catch (IOException | RuntimeException e) {
                encodeFailures++;
                LOGGER.warn("pdc capture: cannot read/encode '{}', skipping: {}", key.asString(), e.toString());
                continue;
            }

            if ((type == PdcValueType.FLOAT || type == PdcValueType.DOUBLE)
                    && !Double.isFinite(((Number) payloadValue).doubleValue())) {
                // JSON has no NaN/Infinity - storing one would produce a payload the module cannot read back
                nonFinite++;
                LOGGER.warn("pdc capture: skipping non-finite value at '{}'", key.asString());
                continue;
            }

            switch (limiter.offer(size)) {
                case CAPTURE -> entries.add(new PdcEntry(key.asString(), type, payloadValue));
                case SKIP_OVERSIZE -> oversize++;
                case ABORT_SNAPSHOT_TOO_LARGE -> throw new PdcCaptureException(
                        "PDC payload exceeds the total cap of " + PdcLimits.TOTAL_BYTES
                                + " bytes; refusing to store a partial snapshot");
            }
        }

        var report = new PdcCaptureReport(entries.size(), denied, unsupported, oversize, nonFinite, encodeFailures, limiter.totalBytes());
        LOGGER.info(
                "pdc capture for {}: examined {}, captured {}, denied {}, unsupported {}, oversize {}, non-finite {}, encode-failures {}, {} bytes",
                player.getUniqueId(), report.totalExamined(), report.captured(), report.denied(),
                report.unsupported(), report.oversize(), report.nonFinite(), report.encodeFailures(), report.totalBytes());
        return new PdcPayload(PdcPayload.FORMAT, entries);
    }

    /** Maps the introspected storage type onto a wire type, or null when not losslessly carriable. */
    // no @Nullable on the parameter: the test compile classpath does not see paper-api, and type-use
    // annotations on Bukkit-typed signatures make the class unreadable from plain javac
    private static @Nullable PdcValueType mapStorageType(PersistentDataType<?, ?> storage) {
        if (storage == null) {
            // absent value, an empty list (no element type to reveal), or something the platform does not understand
            return null;
        }
        if (storage == PersistentDataType.BYTE) return PdcValueType.BYTE;
        if (storage == PersistentDataType.SHORT) return PdcValueType.SHORT;
        if (storage == PersistentDataType.INTEGER) return PdcValueType.INTEGER;
        if (storage == PersistentDataType.LONG) return PdcValueType.LONG;
        if (storage == PersistentDataType.FLOAT) return PdcValueType.FLOAT;
        if (storage == PersistentDataType.DOUBLE) return PdcValueType.DOUBLE;
        if (storage == PersistentDataType.STRING) return PdcValueType.STRING;
        if (storage == PersistentDataType.BYTE_ARRAY) return PdcValueType.BYTE_ARRAY;
        if (storage == PersistentDataType.INTEGER_ARRAY) return PdcValueType.INTEGER_ARRAY;
        if (storage == PersistentDataType.LONG_ARRAY) return PdcValueType.LONG_ARRAY;
        if (storage == PersistentDataType.TAG_CONTAINER) return PdcValueType.TAG_CONTAINER;
        // everything else lands on null: TAG_CONTAINER_ARRAY is deprecated upstream,
        // and LIST values carry no reliable element type to trust - guessing would corrupt data
        return null;
    }

    /** The payload value and decoded footprint for one key, read exactly once. */
    private record CapturedValue(Object value, long sizeBytes) {
    }

    private static @Nullable CapturedValue readAndEncode(PersistentDataContainer container, NamespacedKey key, PdcValueType type) throws IOException {
        return switch (type) {
            case BYTE -> scalar(container.get(key, PersistentDataType.BYTE), 1);
            case SHORT -> scalar(container.get(key, PersistentDataType.SHORT), 2);
            case INTEGER -> scalar(container.get(key, PersistentDataType.INTEGER), 4);
            case LONG -> scalar(container.get(key, PersistentDataType.LONG), 8);
            case FLOAT -> scalar(container.get(key, PersistentDataType.FLOAT), 4);
            case DOUBLE -> scalar(container.get(key, PersistentDataType.DOUBLE), 8);
            case STRING -> {
                String raw = container.get(key, PersistentDataType.STRING);
                yield raw == null ? null : new CapturedValue(raw, PdcCodec.utf8Length(raw));
            }
            case BYTE_ARRAY -> {
                byte[] raw = container.get(key, PersistentDataType.BYTE_ARRAY);
                yield raw == null ? null : new CapturedValue(PdcCodec.encodeBase64(raw), raw.length);
            }
            case INTEGER_ARRAY -> {
                int[] raw = container.get(key, PersistentDataType.INTEGER_ARRAY);
                yield raw == null ? null : new CapturedValue(PdcCodec.encodeIntArray(raw), (long) raw.length * Integer.BYTES);
            }
            case LONG_ARRAY -> {
                long[] raw = container.get(key, PersistentDataType.LONG_ARRAY);
                yield raw == null ? null : new CapturedValue(PdcCodec.encodeLongArray(raw), (long) raw.length * Long.BYTES);
            }
            case TAG_CONTAINER -> {
                // the nested container serializes itself; the restore side rebuilds it with readFromBytes
                PersistentDataContainer nested = container.get(key, PersistentDataType.TAG_CONTAINER);
                if (nested == null) {
                    yield null;
                }
                byte[] bytes = nested.serializeToBytes();
                yield new CapturedValue(PdcCodec.encodeBase64(bytes), bytes.length);
            }
        };
    }

    private static @Nullable CapturedValue scalar(@Nullable Number raw, long sizeBytes) {
        // the entry constructor normalizes numbers (integral -> Long range-checked, real -> Double)
        return raw == null ? null : new CapturedValue(raw, sizeBytes);
    }

    // ---------- restore ----------

    private void doRestore(Player player, PdcPayload data) {
        var plan = PdcRestorePlanner.plan(data);
        var container = player.getPersistentDataContainer();
        int applied = 0;
        int failed = 0;

        for (PdcEntry entry : plan.actions()) {
            try {
                applyEntry(container, entry);
                applied++;
            } catch (RuntimeException e) {
                // one bad key must not stop the rest - count it and move on
                failed++;
                LOGGER.warn("pdc restore: skipping '{}': {}", entry.key(), e.toString());
            }
        }

        LOGGER.info(
                "pdc restore for {}: applied {}, skipped denied {}, illegal-key {}, undecodable {}, too-large {}, budget {}, failed {}",
                player.getUniqueId(), applied, plan.skippedDenied(), plan.skippedIllegalKey(),
                plan.skippedUndecodable(), plan.skippedTooLarge(), plan.skippedBudgetExhausted(), failed);
    }

    private static void applyEntry(PersistentDataContainer container, PdcEntry entry) {
        NamespacedKey key = keyOf(entry.key());
        switch (entry.type()) {
            case BYTE -> container.set(key, PersistentDataType.BYTE, (byte) entry.asIntegral());
            case SHORT -> container.set(key, PersistentDataType.SHORT, (short) entry.asIntegral());
            case INTEGER -> container.set(key, PersistentDataType.INTEGER, (int) entry.asIntegral());
            case LONG -> container.set(key, PersistentDataType.LONG, entry.asIntegral());
            case FLOAT -> container.set(key, PersistentDataType.FLOAT, (float) entry.asReal());
            case DOUBLE -> container.set(key, PersistentDataType.DOUBLE, entry.asReal());
            case STRING -> container.set(key, PersistentDataType.STRING, entry.asString());
            case BYTE_ARRAY -> container.set(key, PersistentDataType.BYTE_ARRAY, PdcCodec.decodeBase64(entry.asString()));
            case INTEGER_ARRAY -> container.set(key, PersistentDataType.INTEGER_ARRAY, PdcCodec.decodeIntArray(entry.asString()));
            case LONG_ARRAY -> container.set(key, PersistentDataType.LONG_ARRAY, PdcCodec.decodeLongArray(entry.asString()));
            case TAG_CONTAINER -> {
                byte[] bytes = PdcCodec.decodeBase64(entry.asString());
                PersistentDataContainer nested = container.getAdapterContext().newPersistentDataContainer();
                try {
                    nested.readFromBytes(bytes, true);
                } catch (IOException e) {
                    throw new IllegalStateException("nested container for '" + entry.key() + "' is not readable", e);
                }
                container.set(key, PersistentDataType.TAG_CONTAINER, nested);
            }
        }
    }

    private static NamespacedKey keyOf(String key) {
        int colon = key.indexOf(':');
        // safe: the restore plan only lets well-formed keys through
        return new NamespacedKey(key.substring(0, colon), key.substring(colon + 1));
    }
}
