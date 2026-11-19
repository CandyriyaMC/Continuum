package lol.candyriya.continuum.common.util;

/**
 * Argument/state checks with real messages. Prefer these over bare
 * {@code assert} or JDK {@code Objects.requireNonNull} when a misconfiguration
 * or a programming error deserves a readable explosion.
 */
public final class Preconditions {

    private Preconditions() {
    }

    public static void checkArgument(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void checkState(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public static <T> T notNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
        return value;
    }

    public static String notBlank(String value, String name) {
        notNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
