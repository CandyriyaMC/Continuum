package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.serialize.ScalarSerializer;
import org.spongepowered.configurate.serialize.SerializationException;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Human-friendly durations in YAML: {@code 500ms}, {@code 60s}, {@code 5m},
 * {@code 1h}, {@code 2d}, ISO-8601 ({@code PT5M}), or a bare integer meaning
 * seconds. Serializes back in the shortest exact human form.
 *
 * <p>Milliseconds are the finest unit — sub-millisecond precision is noise for
 * anything Continuum schedules.</p>
 */
public final class DurationTypeSerializer extends ScalarSerializer<Duration> {

    private static final Pattern HUMAN = Pattern.compile("^(-?)(\\d+)(ms|s|m|h|d)$");

    public DurationTypeSerializer() {
        super(Duration.class);
    }

    @Override
    public Duration deserialize(Type type, Object value) throws SerializationException {
        // YAML hands us bare numbers for things like `max-lifetime: 3600` — treat them as seconds
        if (value instanceof Number number) {
            try {
                return Duration.ofSeconds(number.longValue());
            } catch (ArithmeticException e) {
                throw new SerializationException(type, "duration amount out of range: " + value, e);
            }
        }
        if (!(value instanceof String raw)) {
            throw new SerializationException(type, "expected a duration string like '500ms', '60s', '5m', '1h', or ISO-8601 'PT5M'");
        }
        return parseHumanOrIso(raw.trim(), type);
    }

    private static Duration parseHumanOrIso(String raw, Type type) throws SerializationException {
        String lower = raw.toLowerCase(Locale.ROOT);
        var matcher = HUMAN.matcher(lower);
        if (matcher.matches()) {
            long amount;
            try {
                amount = Long.parseLong(matcher.group(2));
            } catch (NumberFormatException e) {
                throw new SerializationException(type, "duration amount out of range in '" + raw + "'", e);
            }
            Duration parsed = switch (matcher.group(3)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new AssertionError("unreachable, regex covers all units");
            };
            return "-".equals(matcher.group(1)) ? parsed.negated() : parsed;
        }
        if (lower.startsWith("pt")) {
            try {
                return Duration.parse(raw.toUpperCase(Locale.ROOT));
            } catch (java.time.format.DateTimeParseException e) {
                throw new SerializationException(type, "invalid ISO-8601 duration '" + raw + "'", e);
            }
        }
        if (lower.matches("^-?\\d+$")) {
            try {
                return Duration.ofSeconds(Long.parseLong(lower));
            } catch (NumberFormatException e) {
                throw new SerializationException(type, "duration amount out of range in '" + raw + "'", e);
            }
        }
        throw new SerializationException(type,
                "invalid duration '" + raw + "' (expected e.g. '500ms', '60s', '5m', '1h', or ISO-8601 'PT5M')");
    }

    @Override
    protected Object serialize(Duration item, Predicate<Class<?>> typeSupported) {
        return formatHuman(item);
    }

    static String formatHuman(Duration duration) {
        long millis = duration.toMillis();
        if (millis % 1000 != 0) {
            return millis + "ms";
        }
        long seconds = duration.getSeconds();
        if (seconds % 86_400 == 0 && seconds != 0) {
            return (seconds / 86_400) + "d";
        }
        if (seconds % 3_600 == 0 && seconds != 0) {
            return (seconds / 3_600) + "h";
        }
        if (seconds % 60 == 0 && seconds != 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }
}
