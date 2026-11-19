package lol.candyriya.continuum.common.i18n;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One locale's message catalog, flattened from nested YAML into dotted keys:
 * {@code player: {loading: "..."}} becomes {@code player.loading}.
 *
 * <p>Every leaf must be a non-blank string; anything else is a catalog bug and
 * fails fast at load time with the offending path, instead of rendering
 * "true" into chat at 3 AM.</p>
 *
 * <p>Comments in the YAML files document each key (what it is for and which
 * arguments it takes) — the files are the reference for translators.</p>
 */
public final class MessageCatalog {

    private final String locale;
    private final Map<String, String> templates;

    private MessageCatalog(String locale, Map<String, String> templates) {
        this.locale = locale;
        this.templates = Collections.unmodifiableMap(templates);
    }

    /** Loads {@code <locale>.yml}-style file; locale is derived from the file name. */
    public static MessageCatalog load(Path file) {
        String locale = localeFromFileName(file.getFileName().toString());
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(file).build();
        try {
            return load(loader, locale, file.toString());
        } catch (ConfigurateException e) {
            throw new I18nException("cannot parse locale catalog " + file + ": " + e.getMessage(), e);
        }
    }

    /** Loads a catalog from a stream (tests, embedded resources); locale is derived from {@code locale} explicitly. */
    public static MessageCatalog load(InputStream in, String locale) {
        java.util.Objects.requireNonNull(in, "input stream must not be null");
        java.util.Objects.requireNonNull(locale, "locale must not be null");
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .source(() -> new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)))
                .build();
        try {
            return load(loader, locale, locale + ".yml");
        } catch (ConfigurateException e) {
            throw new I18nException("cannot parse locale catalog " + locale + ": " + e.getMessage(), e);
        }
    }

    private static MessageCatalog load(YamlConfigurationLoader loader, String locale, String source) throws ConfigurateException {
        CommentedConfigurationNode root = loader.load();
        var templates = new LinkedHashMap<String, String>();
        flatten(root, new StringBuilder(), templates, source);
        if (templates.isEmpty()) {
            throw new I18nException("locale catalog " + source + " is empty");
        }
        return new MessageCatalog(locale, templates);
    }

    private static void flatten(CommentedConfigurationNode node, StringBuilder path, Map<String, String> out, String source) {
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : node.childrenMap().entrySet()) {
                String segment = String.valueOf(entry.getKey());
                int length = path.length();
                if (length > 0) {
                    path.append('.');
                }
                path.append(segment);
                flatten(entry.getValue(), path, out, source);
                path.setLength(length);
            }
            return;
        }
        String key = path.toString();
        if (key.isEmpty()) {
            throw new I18nException("locale catalog " + source + " has a scalar at the root; expected a mapping");
        }
        if (node.isList()) {
            throw new I18nException("locale catalog " + source + ": key '" + key + "' must be a string, got a list");
        }
        String value = node.getString();
        if (value == null || value.isBlank()) {
            throw new I18nException("locale catalog " + source + ": key '" + key + "' must be a non-blank string");
        }
        out.put(key, value);
    }

    private static String localeFromFileName(String fileName) {
        String base = fileName.endsWith(".yml") ? fileName.substring(0, fileName.length() - 4) : fileName;
        if (base.isBlank()) {
            throw new I18nException("locale catalog file name '" + fileName + "' does not carry a locale");
        }
        return base;
    }

    public String locale() {
        return locale;
    }

    public Optional<String> template(String key) {
        return Optional.ofNullable(templates.get(key));
    }

    /** All keys present in this catalog. Unmodifiable. */
    public Set<String> keys() {
        return templates.keySet();
    }

    public int size() {
        return templates.size();
    }
}
