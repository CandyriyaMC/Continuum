package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.internal.JacksonMappers;
import lol.candyriya.continuum.common.util.Preconditions;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Validation for the {@code continuum:main} channel, in two flavours.
 *
 * <h2>Fail-fast (hot path)</h2>
 * {@link PluginMessageCodec#decode(byte[], PluginMessageDirection)} throws the
 * first typed exception it hits. That is what message handlers call.
 *
 * <h2>Collect-all (diagnostics, tooling, tests)</h2>
 * {@link #validate(byte[])}, {@link #validate(byte[], PluginMessageDirection)}
 * and {@link #validate(PluginMessage)} gather <i>every</i> problem they can
 * find and return them in detection order. An empty list means the frame is
 * good. Nothing is truncated and nothing throws for bad input — an invalid
 * frame is data here, not an accident.
 *
 * <p>Both flavours run the same rules and produce the same exception types, so
 * behaviour never diverges between "validated" and "decoded".</p>
 */
public final class PluginMessageValidator {

    private static final JsonMapper MAPPER = JacksonMappers.create();

    private final PluginMessageLimits limits;

    public PluginMessageValidator() {
        this(PluginMessageLimits.defaults());
    }

    public PluginMessageValidator(PluginMessageLimits limits) {
        this.limits = Preconditions.notNull(limits, "limits");
    }

    /**
     * Validates a raw frame without expecting a particular direction — useful
     * for diagnostics. Handlers should prefer the direction-expecting
     * overload.
     *
     * @return all problems found, in detection order; empty when the frame is valid
     */
    public List<PluginMessageException> validate(byte[] frame) {
        return validate(frame, null);
    }

    /**
     * Validates a raw frame, additionally expecting the frame's kind to belong
     * to {@code expected}. A frame from the other direction is reported as a
     * {@link DirectionMismatchException}.
     *
     * @return all problems found, in detection order; empty when the frame is valid
     */
    public List<PluginMessageException> validate(byte[] frame, PluginMessageDirection expected) {
        Preconditions.notNull(frame, "frame");
        List<PluginMessageException> errors = new ArrayList<>();

        if (frame.length > limits.maxFrameBytes()) {
            errors.add(new FrameTooLargeException(frame.length, limits.maxFrameBytes()));
            return errors; // nothing else can be trusted on an over-cap frame
        }

        if (!PluginMessageProtocol.hasMagic(frame)) {
            errors.add(new InvalidMagicException(PluginMessageProtocol.magicOf(frame)));
        }
        if (frame.length < PluginMessageProtocol.HEADER_BYTES) {
            // we can still say something precise about the magic, but not about any field past the size
            errors.add(new TruncatedFrameException(PluginMessageProtocol.HEADER_BYTES, frame.length));
            return errors;
        }

        int version = frame[PluginMessageProtocol.OFFSET_VERSION] & 0xff;
        if (!PluginMessageProtocol.isSupported(version)) {
            errors.add(new UnsupportedProtocolVersionException(version));
        }

        int kindByte = frame[PluginMessageProtocol.OFFSET_KIND] & 0xff;
        PluginMessageKind kind = null;
        try {
            kind = PluginMessageKind.fromId(kindByte);
        } catch (UnknownMessageKindException e) {
            errors.add(e);
        }

        if (expected != null && kind != null && kind.direction() != expected) {
            errors.add(new DirectionMismatchException(expected, kind.direction()));
        }

        UUID correlation = readCorrelation(frame);
        if (PluginMessageConstraints.isNilUuid(correlation)) {
            errors.add(new PluginMessageValidationException(List.of("correlation id must not be nil")));
        }

        int declared = readInt(frame, PluginMessageProtocol.OFFSET_PAYLOAD_LENGTH);
        int available = frame.length - PluginMessageProtocol.HEADER_BYTES;
        byte[] payload = null;
        if (declared < 0) {
            errors.add(new InvalidPayloadLengthException(declared));
        } else if (declared > available) {
            errors.add(new TruncatedFrameException(PluginMessageProtocol.HEADER_BYTES + declared, frame.length));
        } else {
            if (declared < available) {
                errors.add(new TrailingBytesException(available - declared));
            }
            payload = new byte[declared];
            System.arraycopy(frame, PluginMessageProtocol.OFFSET_PAYLOAD, payload, 0, declared);
        }

        if (payload != null && !isWellFormedUtf8(payload)) {
            errors.add(new InvalidUtf8Exception());
            payload = null; // binding on top of broken UTF-8 would only pile on noise
        }

        if (payload != null && kind != null) {
            final PluginMessageKind resolvedKind = kind;
            PluginMessage message = null;
            try {
                message = MAPPER.readValue(payload, resolvedKind.type());
            } catch (JacksonException | IllegalArgumentException e) {
                errors.add(new MalformedPayloadException(resolvedKind.wireId(), e.getMessage(), e));
            }
            if (message != null) {
                errors.addAll(validate(message));
            }
        }

        return errors;
    }

    /**
     * Validates an already-bound message against the format rules of its kind.
     * Useful before encoding; the codec runs this on every encode.
     *
     * @return all violations found, in detection order; empty when the message is valid.
     *         When non-empty, the list contains exactly one
     *         {@link PluginMessageValidationException} carrying every violation.
     */
    public List<PluginMessageException> validate(PluginMessage message) {
        Preconditions.notNull(message, "message");
        List<String> violations = semanticViolations(message);
        if (violations.isEmpty()) {
            return List.of();
        }
        return List.of(new PluginMessageValidationException(violations));
    }

    /**
     * The raw violation strings for a message, in detection order. Empty when
     * the message is valid. Shared by encode, decode and both validate forms
     * so there is exactly one implementation of the rules.
     */
    static List<String> semanticViolations(PluginMessage message) {
        List<String> violations = new ArrayList<>();
        switch (message) {
            case HandoffRequestMessage m -> {
                checkPlayer(m.player(), violations, "player");
                checkServerName(m.targetServer(), violations, "targetServer");
                checkLogText(m.reason(), violations, "reason");
            }
            case HandoffReadyMessage m -> {
                checkPlayer(m.player(), violations, "player");
                checkServerName(m.server(), violations, "server");
                checkLogText(m.detail(), violations, "detail");
            }
            case HandoffResumedMessage m -> {
                checkPlayer(m.player(), violations, "player");
                checkServerName(m.server(), violations, "server");
                checkLogText(m.detail(), violations, "detail");
            }
            case LocaleSetMessage m -> {
                checkPlayer(m.player(), violations, "player");
                if (!PluginMessageConstraints.isValidLocale(m.locale())) {
                    violations.add("locale must be a BCP 47-ish tag of at most "
                            + PluginMessageConstraints.MAX_LOCALE + " characters, got '" + m.locale() + "'");
                }
            }
            case PingMessage m -> checkInstant(m.sentAt(), violations, "sentAt");
            case PongMessage m -> checkInstant(m.sentAt(), violations, "sentAt");
            case BackendStatusMessage m -> {
                checkServerName(m.server(), violations, "server");
                if (m.playerCount() < 0 || m.playerCount() > PluginMessageConstraints.MAX_PLAYER_COUNT) {
                    violations.add("playerCount must be within [0, " + PluginMessageConstraints.MAX_PLAYER_COUNT
                            + "], got " + m.playerCount());
                }
                if (m.maxPlayers() < 1 || m.maxPlayers() > PluginMessageConstraints.MAX_PLAYER_COUNT) {
                    violations.add("maxPlayers must be within [1, " + PluginMessageConstraints.MAX_PLAYER_COUNT
                            + "], got " + m.maxPlayers());
                }
                if (m.playerCount() > m.maxPlayers()) {
                    violations.add("playerCount (" + m.playerCount() + ") exceeds maxPlayers (" + m.maxPlayers() + ")");
                }
                checkLogText(m.detail(), violations, "detail");
            }
            case SessionTokenMessage m -> {
                checkPlayer(m.player(), violations, "player");
                if (m.token() == null || !PluginMessageConstraints.isPrintableAsciiToken(m.token())
                        || m.token().length() > PluginMessageConstraints.MAX_TOKEN) {
                    violations.add("token must be non-blank printable ASCII of at most "
                            + PluginMessageConstraints.MAX_TOKEN + " characters, got "
                            + (m.token() == null ? "null" : m.token().length() + " chars"));
                }
                checkInstant(m.expiresAt(), violations, "expiresAt");
            }
            case RouteResultMessage m -> {
                checkPlayer(m.player(), violations, "player");
                if (m.outcome() == RouteOutcome.ALLOWED) {
                    if (!PluginMessageConstraints.isValidServerName(m.targetServer())) {
                        violations.add("targetServer must be a valid server name when outcome is ALLOWED, got '"
                                + m.targetServer() + "'");
                    }
                } else if (!m.targetServer().isEmpty() && !PluginMessageConstraints.isValidServerName(m.targetServer())) {
                    violations.add("targetServer must be a valid server name or empty when outcome is " + m.outcome()
                            + ", got '" + m.targetServer() + "'");
                }
                checkLogText(m.reason(), violations, "reason");
            }
        }
        return violations;
    }

    /** Shared nil-check + format checks for the player uuid fields. */
    private static void checkPlayer(UUID player, List<String> violations, String field) {
        if (player == null) {
            violations.add(field + " must not be null");
        } else if (PluginMessageConstraints.isNilUuid(player)) {
            violations.add(field + " must not be the nil UUID");
        }
    }

    private static void checkServerName(String name, List<String> violations, String field) {
        if (!PluginMessageConstraints.isValidServerName(name)) {
            violations.add(field + " must match ^[a-z0-9][a-z0-9_.-]{0,63}$, got '" + name + "'");
        }
    }

    private static void checkLogText(String text, List<String> violations, String field) {
        if (text == null) {
            violations.add(field + " must not be null (use \"\" for no context)");
        } else {
            if (text.length() > PluginMessageConstraints.MAX_LOG_TEXT) {
                violations.add(field + " must be at most " + PluginMessageConstraints.MAX_LOG_TEXT
                        + " characters, got " + text.length());
            }
            if (!PluginMessageConstraints.hasNoControlChars(text)) {
                violations.add(field + " must not contain control characters");
            }
        }
    }

    private static void checkInstant(Instant instant, List<String> violations, String field) {
        if (instant == null) {
            violations.add(field + " must not be null");
        } else if (!PluginMessageConstraints.isSaneInstant(instant.toEpochMilli())) {
            violations.add(field + " must be within [1970-01-01T00:00:00Z, 2100-01-01T00:00:00Z), got " + instant);
        }
    }

    static UUID readCorrelation(byte[] frame) {
        return new UUID(readLong(frame, PluginMessageProtocol.OFFSET_CORRELATION_MSB),
                readLong(frame, PluginMessageProtocol.OFFSET_CORRELATION_LSB));
    }

    static long readLong(byte[] frame, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (frame[offset + i] & 0xffL);
        }
        return value;
    }

    static int readInt(byte[] frame, int offset) {
        return ((frame[offset] & 0xff) << 24)
                | ((frame[offset + 1] & 0xff) << 16)
                | ((frame[offset + 2] & 0xff) << 8)
                | (frame[offset + 3] & 0xff);
    }

    /** Strict UTF-8 check; the JSON parser never sees bytes we have not vetted. */
    static boolean isWellFormedUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
