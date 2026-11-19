package lol.candyriya.continuum.common.error;

/**
 * Machine-readable error codes shared across every Continuum component.
 *
 * <p>Codes are stable storage/wire constants: never reuse one for a different
 * failure, never re-number. Platforms are expected to map codes onto i18n
 * keys for user-facing output; the English text attached to the exception
 * itself is a fallback for logs and crash reports, not for chat.</p>
 */
public enum ErrorCode {

    /** The backing database is unreachable or refused the operation. */
    STORAGE_UNAVAILABLE("storage.unavailable"),

    /** A write lost the fencing race: a newer session owns the record. */
    FENCING_CONFLICT("storage.fencing-conflict"),

    /** Input failed validation (config, payload, arguments). */
    VALIDATION_FAILED("validation.failed"),

    /** Serialization or deserialization of a payload failed. */
    SERIALIZATION_FAILED("serialization.failed"),

    /** A schema migration could not be applied or left the document in an unknown shape. */
    MIGRATION_FAILED("serialization.migration-failed"),

    /** The messaging transport refused or lost the message. */
    MESSAGING_FAILED("messaging.failed"),

    /** A wire message failed structural validation or size limits. */
    PROTOCOL_VIOLATION("protocol.violation"),

    /** The configuration file is unreadable or violates documented constraints. */
    CONFIG_INVALID("config.invalid"),

    /** The i18n catalog or message lookup failed. */
    I18N_ERROR("i18n.error");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    /** Stable dotted code, safe to persist and match on. */
    public String code() {
        return code;
    }

    /** @return the matching code, or empty when {@code code} is not known */
    public static java.util.Optional<ErrorCode> fromCode(String code) {
        for (ErrorCode value : values()) {
            if (value.code.equals(code)) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }
}
