package lol.candyriya.continuum.common.update;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The signed update manifest, parsed from the exact bytes the signature
 * covers. Never built from a string re-serialized in between.
 *
 * <p>Wire format (schema 1, additive only): unknown top-level keys are
 * tolerated so the service can grow fields; {@code schema} must equal
 * {@link #SCHEMA_VERSION}; {@code channel} is part of the signed payload,
 * which is what stops a dev jar from presenting itself as a release.
 * {@code build} is {@code build-<N>} on release and {@code devbuild-<N>} on
 * dev; anything else is a hard reject.</p>
 *
 * <p>{@code folia} is the manifest's claim that the build runs on Folia; a
 * Folia server refuses to stage a build without it. {@code mc.min}/{@code
 * mc.max} bound the Minecraft version range, {@code java.min} the Java major
 * version - both inclusive.</p>
 */
public record Manifest(int schema,
                       UpdateChannel channel,
                       String build,
                       String sha256,
                       String file,
                       String mcMin,
                       String mcMax,
                       int javaMin,
                       boolean folia,
                       String releasedAt) {

    /** The only schema version this client speaks. A breaking change ships as schema 2, never a redefinition. */
    public static final int SCHEMA_VERSION = 1;

    public static final Pattern BUILD_FORMAT = Pattern.compile("^(dev)?build-[0-9]+$");
    public static final Pattern SHA256_FORMAT = Pattern.compile("^[0-9a-f]{64}$");

    /** Thrown when the manifest is unusable; caught by the check path and treated as "no update this cycle". */
    public static final class ManifestException extends RuntimeException {
        public ManifestException(String message) {
            super(message);
        }

        public ManifestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Parses {@code bytes} (the exact bytes covered by the detached
     * signature). Verification must already have happened, or happen before
     * any field of the result is acted on - parsing alone proves nothing.
     */
    public static Manifest parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ManifestException("manifest is empty");
        }
        final JsonNode root;
        try {
            root = new JsonMapper().readTree(bytes);
        } catch (Exception e) {
            throw new ManifestException("manifest is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new ManifestException("manifest root must be a JSON object");
        }

        int schema = intField(root, "schema");
        if (schema != SCHEMA_VERSION) {
            throw new ManifestException("unsupported manifest schema " + schema + " (this client speaks schema " + SCHEMA_VERSION + ")");
        }

        UpdateChannel channel = UpdateChannel.fromWire(textField(root, "channel", true));
        if (channel == null) {
            throw new ManifestException("manifest channel must be 'release' or 'dev'");
        }

        String build = textField(root, "build", true);
        if (!BUILD_FORMAT.matcher(build).matches()) {
            throw new ManifestException("manifest build '" + build + "' does not match build-<N> / devbuild-<N>");
        }

        String sha256 = textField(root, "sha256", true).toLowerCase(Locale.ROOT);
        if (!SHA256_FORMAT.matcher(sha256).matches()) {
            throw new ManifestException("manifest sha256 is not a 64-char lowercase hex digest");
        }

        String file = textField(root, "file", true);

        String mcMin = "";
        String mcMax = "";
        JsonNode mc = root.get("mc");
        if (mc != null && mc.isObject()) {
            mcMin = textField(mc, "min", false);
            mcMax = textField(mc, "max", false);
        }

        int javaMin = 0;
        JsonNode java = root.get("java");
        if (java != null && java.isObject()) {
            JsonNode min = java.get("min");
            if (min != null && min.isInt()) {
                javaMin = min.intValue();
            } else if (min != null && min.isNumber()) {
                javaMin = min.numberValue().intValue();
            }
        }

        JsonNode foliaNode = root.get("folia");
        boolean folia = foliaNode != null && foliaNode.isBoolean() && foliaNode.booleanValue();
        String releasedAt = textField(root, "releasedAt", false);

        return new Manifest(schema, channel, build, sha256, file, mcMin, mcMax, javaMin, folia, releasedAt);
    }

    private static int intField(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isInt()) {
            if (node != null && node.isNumber()) {
                return node.numberValue().intValue();
            }
            throw new ManifestException("manifest field '" + name + "' must be an integer");
        }
        return node.intValue();
    }

    private static String textField(JsonNode parent, String name, boolean required) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) {
            if (required) {
                throw new ManifestException("manifest field '" + name + "' is missing");
            }
            return "";
        }
        if (!node.isString()) {
            throw new ManifestException("manifest field '" + name + "' must be a string");
        }
        String value = node.stringValue().trim();
        if (required && value.isEmpty()) {
            throw new ManifestException("manifest field '" + name + "' must not be blank");
        }
        return value;
    }
}
