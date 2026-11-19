package lol.candyriya.continuum.paper.modules.permissions;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * An immutable set of the meaningful permission-relevant context tags of a player,
 * for example {@code world=lobby} or {@code server=survival-1,world=lobby}.
 *
 * <p><b>What this is for.</b> Diagnostics. The encoded form (see {@link #encode()}) is a
 * stable, self-describing string that can be embedded in logs or snapshot diagnostics to
 * answer "under which circumstances was this decision made?" after the fact.</p>
 *
 * <p><b>What this is NOT for.</b> Not an authorization input. No module may parse the
 * encoded form (or {@link #tags()}) to make a permission or restore decision — a decision
 * goes through {@link PermissionGate}, never through this type. The set of context tags is
 * deliberately lossless only for humans and log pipelines.</p>
 *
 * <p><b>Shape.</b> Tags are stored as encoded {@code key=value} entries in one sorted set;
 * sorting is by the encoded entry, so {@link #encode()} is byte-for-byte stable for the
 * same input across runs and platforms. Keys match {@code [a-z][a-z0-9_-]{0,31}}; values
 * are free-form (any non-blank string) and are percent-encoded per RFC 3986 on the way in,
 * so a world named {@code "the end, but weird=yes"} round-trips cleanly.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public final class PermissionContext {

    /** Keys follow the same lowercase-label convention as module ids; 32 chars is plenty. */
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private static final int MAX_KEY_LENGTH = 32;

    private final SortedSet<String> tags;

    private PermissionContext(SortedSet<String> encodedTags) {
        // defensive copy + never hand out the internal set
        this.tags = Collections.unmodifiableSortedSet(new TreeSet<>(encodedTags));
    }

    /**
     * Builds a context from raw key/value pairs. Entries are sorted by their encoded
     * form regardless of iteration order.
     *
     * @param entries the raw tags; keys must match {@code [a-z][a-z0-9_-]{0,31}}, values must not be blank
     * @return the context, never null
     * @throws NullPointerException when entries (or a key/value) is null
     * @throws IllegalArgumentException when a key is malformed or a value is blank
     */
    public static @NotNull PermissionContext of(@NotNull Map<String, String> entries) {
        Objects.requireNonNull(entries, "entries");
        TreeSet<String> encoded = new TreeSet<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (key == null || !isValidKey(key)) {
                throw new IllegalArgumentException(
                        "context key must match [a-z][a-z0-9_-]{0," + (MAX_KEY_LENGTH - 1) + "}, got: " + key);
            }
            String value = Objects.requireNonNull(entry.getValue(), "context value for '" + key + "'");
            if (value.isBlank()) {
                throw new IllegalArgumentException("context value for '" + key + "' must not be blank");
            }
            encoded.add(key + "=" + encodeValue(value));
        }
        return new PermissionContext(encoded);
    }

    /**
     * The encoded {@code key=value} entries, sorted. The set is unmodifiable.
     *
     * @return the tags, never null
     */
    public @NotNull SortedSet<String> tags() {
        return tags;
    }

    /**
     * The encoded form: entries joined with {@code ,}. Stable for the same input — safe
     * to log and to embed in snapshot diagnostics. Diagnostics only; never parse this
     * back for a decision.
     *
     * @return the encoded context; the empty string for an empty context
     */
    public @NotNull String encode() {
        return String.join(",", tags);
    }

    /**
     * Strict inverse of {@link #encode()}. Package-private on purpose: the encoded form is
     * for humans and log pipelines, and decoding belongs to tests and to nothing that makes
     * decisions. The empty string decodes to an empty context; anything malformed throws.
     */
    static @NotNull PermissionContext decode(@NotNull String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isEmpty()) {
            return new PermissionContext(new TreeSet<>());
        }
        Map<String, String> decoded = new TreeMap<>();
        int start = 0;
        while (true) {
            int comma = encoded.indexOf(',', start);
            String entry = (comma < 0) ? encoded.substring(start) : encoded.substring(start, comma);
            decodeEntry(entry, decoded);
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return PermissionContext.of(decoded);
    }

    private static void decodeEntry(String entry, Map<String, String> decoded) {
        int eq = entry.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException("context entry must be 'key=value', got: " + entry);
        }
        String key = entry.substring(0, eq);
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("context key must match [a-z][a-z0-9_-]{0," + (MAX_KEY_LENGTH - 1) + "}, got: " + key);
        }
        String value = decodeValue(entry.substring(eq + 1), entry);
        if (decoded.put(key, value) != null) {
            throw new IllegalArgumentException("duplicate context key: " + key);
        }
    }

    private static boolean isValidKey(String key) {
        return key.length() <= MAX_KEY_LENGTH && KEY.matcher(key).matches();
    }

    // unreserved per RFC 3986 pass through; everything else becomes %XX over the UTF-8 bytes
    private static String encodeValue(String value) {
        var out = new StringBuilder(value.length() + 16);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((c >>> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static String decodeValue(String raw, String entry) {
        var bytes = new ByteArrayOutputStream(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char ch = raw.charAt(i);
            if (ch == '%') {
                if (i + 2 > raw.length() - 1) {
                    throw new IllegalArgumentException("truncated percent sequence in context entry: " + entry);
                }
                char hi = raw.charAt(i + 1);
                char lo = raw.charAt(i + 2);
                if (!isUpperHex(hi) || !isUpperHex(lo)) {
                    throw new IllegalArgumentException("percent sequences must use uppercase hex in context entry: " + entry);
                }
                bytes.write((Character.digit(hi, 16) << 4) | Character.digit(lo, 16));
                i += 3;
            } else if (isUnreserved(ch)) {
                bytes.write((int) ch);
                i++;
            } else {
                throw new IllegalArgumentException("unencoded character '" + ch + "' in context entry: " + entry);
            }
        }
        String value = bytes.toString(StandardCharsets.UTF_8);
        if (value.isBlank()) {
            throw new IllegalArgumentException("context value must not decode to blank in entry: " + entry);
        }
        return value;
    }

    private static boolean isUpperHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '-' || c == '_' || c == '~';
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof PermissionContext other && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return tags.hashCode();
    }

    /** Returns {@link #encode()} — the bare diagnostic form, on purpose. */
    @Override
    public String toString() {
        return encode();
    }
}
