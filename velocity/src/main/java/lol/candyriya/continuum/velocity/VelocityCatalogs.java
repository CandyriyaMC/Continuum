package lol.candyriya.continuum.velocity;

import lol.candyriya.continuum.common.config.I18nConfig;
import lol.candyriya.continuum.common.i18n.I18nException;
import lol.candyriya.continuum.common.i18n.MessageCatalog;
import lol.candyriya.continuum.common.i18n.Translator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the single {@link Translator} the proxy serves.
 *
 * <p>The message keys come from three layers, later layers win per key:</p>
 * <ol>
 *     <li>the shared catalogs bundled in the common module ({@code /locales/<locale>.yml})</li>
 *     <li>the proxy overlay bundled in this jar ({@code /velocity-locales/<locale>.yml})</li>
 *     <li>external overrides dropped into {@code <data>/locales/<locale>.yml} by the operator</li>
 * </ol>
 *
 * <p>Catalogs missing keys fall back the usual way (requested locale, then the
 * configured fallback, then en_US), so the overlay only needs the keys the
 * proxy itself introduces. External catalogs are validated at load time; a
 * broken override fails the whole build, and the reload flow keeps the
 * previous translator in that case.</p>
 */
public final class VelocityCatalogs {

    /** Same set the shared translator bundles; keep in sync with it. */
    private static final List<String> BUNDLED = List.of("en_US", "ru_RU", "de_DE", "kk_KZ", "zh_CN");

    private VelocityCatalogs() {
    }

    /**
     * Loads and merges all three layers and returns the composed translator.
     *
     * @param classLoader       class loader of the plugin (both jars are on it)
     * @param i18n              i18n section of the shared config (default and fallback locales)
     * @param externalLocalesDir {@code <data>/locales}; ignored when absent or not a directory
     * @throws I18nException when a catalog is unreadable or the result has no en_US
     */
    public static Translator build(ClassLoader classLoader, I18nConfig i18n, Path externalLocalesDir) {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();

        // layer 1: shared catalogs; en_US is mandatory, the rest are best-effort
        for (String locale : BUNDLED) {
            Map<String, String> templates = readCatalog(classLoader, "locales/" + locale + ".yml", locale);
            if (templates != null) {
                merged.put(locale, templates);
            } else if (Translator.HARD_FALLBACK_LOCALE.equals(locale)) {
                throw new I18nException("bundled en_US catalog is missing; the common jar is broken");
            }
        }

        // layer 2: proxy overlay, optional for every locale
        for (String locale : BUNDLED) {
            Map<String, String> overlay = readCatalog(classLoader, "velocity-locales/" + locale + ".yml", locale);
            if (overlay != null) {
                merged.merge(locale, overlay, VelocityCatalogs::deepMerge);
            }
        }

        // layer 3: operator overrides, one file per locale
        if (externalLocalesDir != null && Files.isDirectory(externalLocalesDir)) {
            try (Stream<Path> files = Files.list(externalLocalesDir)) {
                List<Path> paths = files.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
                for (Path path : paths) {
                    MessageCatalog external = MessageCatalog.load(path);
                    merged.merge(external.locale(), templatesOf(external), VelocityCatalogs::deepMerge);
                }
            } catch (IOException e) {
                throw new I18nException("cannot list external locales directory " + externalLocalesDir, e);
            }
        }

        Map<String, MessageCatalog> catalogs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : merged.entrySet()) {
            catalogs.put(entry.getKey(), dumpCatalog(entry.getKey(), entry.getValue()));
        }
        return Translator.of(catalogs, i18n.defaultLocale(), i18n.fallbackLocale());
    }

    private static Map<String, String> readCatalog(ClassLoader classLoader, String resource, String locale) {
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return templatesOf(MessageCatalog.load(in, locale));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read bundled catalog " + resource, e);
        }
    }

    private static Map<String, String> templatesOf(MessageCatalog catalog) {
        Map<String, String> templates = new TreeMap<>();
        for (String key : catalog.keys()) {
            templates.put(key, catalog.template(key).orElseThrow());
        }
        return templates;
    }

    private static Map<String, String> deepMerge(Map<String, String> base, Map<String, String> overlay) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(overlay);
        return merged;
    }

    private static MessageCatalog dumpCatalog(String locale, Map<String, String> templates) {
        // dotted keys -> nested map -> minimal YAML; templates round-trip exactly
        Map<String, Object> nested = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            putNested(nested, entry.getKey().split("\\."), entry.getValue());
        }
        StringBuilder yaml = new StringBuilder();
        dumpYaml(nested, 0, yaml);
        return MessageCatalog.load(new ByteArrayInputStream(yaml.toString().getBytes(StandardCharsets.UTF_8)), locale);
    }

    /** Unquoted keys are safe for our key charset ([a-z0-9_-]); quote anything else. */
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9_-]+");

    private static void dumpYaml(Map<String, Object> map, int indent, StringBuilder out) {
        String pad = "  ".repeat(indent);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = SAFE_KEY.matcher(entry.getKey()).matches() ? entry.getKey() : yamlQuote(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                out.append(pad).append(key).append(":\n");
                @SuppressWarnings("unchecked")
                Map<String, Object> childMap = (Map<String, Object>) child;
                dumpYaml(childMap, indent + 1, out);
            } else {
                out.append(pad).append(key).append(": ").append(yamlQuote((String) value)).append('\n');
            }
        }
    }

    /** YAML double-quoted scalar with the escapes snakeyaml/configurate understand. */
    private static String yamlQuote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void putNested(Map<String, Object> root, String[] parts, String value) {
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (next instanceof String) {
                throw new I18nException("locale catalog key '" + String.join(".", parts)
                        + "' collides with an existing message at '" + parts[i] + "'");
            }
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }
}
