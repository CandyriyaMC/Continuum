package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.internal.JacksonMappers;
import lol.candyriya.continuum.common.util.Preconditions;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * Fail-fast encoder/decoder for the {@code continuum:main} channel.
 *
 * <p>Layout (see {@link PluginMessageProtocol}): magic {@code "ctmm"},
 * version byte, kind byte, 16-byte correlation UUID (MSB then LSB,
 * big-endian), big-endian int payload length, UTF-8 JSON body. Field widths
 * are fixed; there is no varint, no tag soup, no schema negotiation.</p>
 *
 * <p><b>Provenance is not checked here.</b> The codec cannot see the event
 * the frame arrived in, so it cannot tell a backend from a player spraying
 * bytes on the channel. Callers must check the source first (see
 * {@link PluginMessageChannel}); the codec only guarantees that whatever
 * reaches it is a well-formed, in-contract frame.</p>
 *
 * <p>The codec is stateless and thread-safe. All limits are enforced on the
 * raw frame before a single byte of payload is interpreted.</p>
 */
public final class PluginMessageCodec {

    private static final JsonMapper MAPPER = JacksonMappers.create();

    private final PluginMessageLimits limits;

    /** Codec with the default 64 KiB frame cap. */
    public PluginMessageCodec() {
        this(PluginMessageLimits.defaults());
    }

    public PluginMessageCodec(PluginMessageLimits limits) {
        this.limits = Preconditions.notNull(limits, "limits");
    }

    /** The limits this codec enforces. */
    public PluginMessageLimits limits() {
        return limits;
    }

    /**
     * Binds a frame to its typed record.
     *
     * @param frame    raw bytes as received; must not be null
     * @param expected the direction this endpoint is allowed to hear; the frame's
     *                 kind direction must match — a crossed-wire frame dies with
     *                 {@link DirectionMismatchException}
     * @return the message and its envelope data
     * @throws FrameTooLargeException              when the frame exceeds the configured cap
     * @throws TruncatedFrameException             when the frame ends before the declared payload
     * @throws InvalidMagicException               when the frame does not start with {@code "ctmm"}
     * @throws UnsupportedProtocolVersionException when the version byte is not supported
     * @throws UnknownMessageKindException         when the kind byte is not registered
     * @throws InvalidPayloadLengthException       when the declared length is negative
     * @throws TrailingBytesException              when the frame has bytes beyond the declared payload
     * @throws InvalidUtf8Exception                when the payload is not valid UTF-8
     * @throws MalformedPayloadException           when the payload is not JSON for the kind's record
     * @throws PluginMessageValidationException    when a field violates its format rules, or the correlation id is nil
     * @throws DirectionMismatchException          when the kind belongs to the opposite direction
     */
    public DecodedPluginMessage decode(byte[] frame, PluginMessageDirection expected) {
        Preconditions.notNull(frame, "frame");
        Preconditions.notNull(expected, "expected");

        if (frame.length > limits.maxFrameBytes()) {
            throw new FrameTooLargeException(frame.length, limits.maxFrameBytes());
        }
        if (frame.length < PluginMessageProtocol.HEADER_BYTES) {
            throw new TruncatedFrameException(PluginMessageProtocol.HEADER_BYTES, frame.length);
        }
        if (!PluginMessageProtocol.hasMagic(frame)) {
            throw new InvalidMagicException(PluginMessageProtocol.magicOf(frame));
        }

        int version = frame[PluginMessageProtocol.OFFSET_VERSION] & 0xff;
        if (!PluginMessageProtocol.isSupported(version)) {
            throw new UnsupportedProtocolVersionException(version);
        }

        int kindByte = frame[PluginMessageProtocol.OFFSET_KIND] & 0xff;
        PluginMessageKind kind = PluginMessageKind.fromId(kindByte); // throws UnknownMessageKindException

        if (kind.direction() != expected) {
            throw new DirectionMismatchException(expected, kind.direction());
        }

        UUID correlation = PluginMessageValidator.readCorrelation(frame);
        if (PluginMessageConstraints.isNilUuid(correlation)) {
            throw new PluginMessageValidationException(List.of("correlation id must not be nil"));
        }

        int declared = PluginMessageValidator.readInt(frame, PluginMessageProtocol.OFFSET_PAYLOAD_LENGTH);
        int available = frame.length - PluginMessageProtocol.HEADER_BYTES;
        if (declared < 0) {
            throw new InvalidPayloadLengthException(declared);
        }
        if (declared > available) {
            throw new TruncatedFrameException(PluginMessageProtocol.HEADER_BYTES + declared, frame.length);
        }
        if (declared < available) {
            throw new TrailingBytesException(available - declared);
        }

        byte[] payload = frameSlice(frame, declared);
        if (!PluginMessageValidator.isWellFormedUtf8(payload)) {
            throw new InvalidUtf8Exception();
        }

        PluginMessage message;
        try {
            message = MAPPER.readValue(payload, kind.type());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MalformedPayloadException(kind.wireId(), e.getMessage(), e);
        }

        List<String> violations = PluginMessageValidator.semanticViolations(message);
        if (!violations.isEmpty()) {
            throw new PluginMessageValidationException(violations);
        }

        return new DecodedPluginMessage(message, correlation, version);
    }

    /**
     * Validates {@code message} and frames it for the wire.
     *
     * <p>When {@code correlationId} belongs to an in-flight request this is a
     * reply; when it is freshly generated this is a new request or report.
     * Replies MUST reuse the id of the message that triggered them — that
     * pairing is what lets a receiver match a response to its request, and
     * there is no other authentication on this channel.</p>
     *
     * @throws PluginMessageValidationException when the message violates its format rules,
     *                                          or the correlation id is nil
     * @throws FrameTooLargeException           when the serialized frame exceeds the configured cap
     * @throws MalformedPayloadException        when the payload cannot be serialized
     */
    public byte[] encode(PluginMessage message, UUID correlationId) {
        Preconditions.notNull(message, "message");
        Preconditions.notNull(correlationId, "correlationId");

        if (PluginMessageConstraints.isNilUuid(correlationId)) {
            throw new PluginMessageValidationException(List.of("correlation id must not be nil"));
        }

        List<String> violations = PluginMessageValidator.semanticViolations(message);
        if (!violations.isEmpty()) {
            throw new PluginMessageValidationException(violations);
        }

        PluginMessageKind kind = PluginMessageKind.fromType(message.getClass());

        final byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(message);
        } catch (JacksonException e) {
            throw new MalformedPayloadException(kind.wireId(), "failed to serialize: " + e.getMessage(), e);
        }

        int frameSize = PluginMessageProtocol.HEADER_BYTES + payload.length;
        if (frameSize > limits.maxFrameBytes()) {
            throw new FrameTooLargeException(frameSize, limits.maxFrameBytes());
        }

        byte[] frame = new byte[frameSize];
        System.arraycopy(PluginMessageProtocol.MAGIC, 0, frame, 0, PluginMessageProtocol.MAGIC.length);
        frame[PluginMessageProtocol.OFFSET_VERSION] = (byte) PluginMessageProtocol.CURRENT_VERSION;
        frame[PluginMessageProtocol.OFFSET_KIND] = (byte) kind.id();
        writeLong(frame, PluginMessageProtocol.OFFSET_CORRELATION_MSB, correlationId.getMostSignificantBits());
        writeLong(frame, PluginMessageProtocol.OFFSET_CORRELATION_LSB, correlationId.getLeastSignificantBits());
        writeInt(frame, PluginMessageProtocol.OFFSET_PAYLOAD_LENGTH, payload.length);
        System.arraycopy(payload, 0, frame, PluginMessageProtocol.OFFSET_PAYLOAD, payload.length);
        return frame;
    }

    private byte[] frameSlice(byte[] frame, int declared) {
        byte[] payload = new byte[declared];
        System.arraycopy(frame, PluginMessageProtocol.OFFSET_PAYLOAD, payload, 0, declared);
        return payload;
    }

    private static void writeLong(byte[] frame, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            frame[offset + i] = (byte) (value & 0xff);
            value >>>= 8;
        }
    }

    private static void writeInt(byte[] frame, int offset, int value) {
        frame[offset] = (byte) ((value >>> 24) & 0xff);
        frame[offset + 1] = (byte) ((value >>> 16) & 0xff);
        frame[offset + 2] = (byte) ((value >>> 8) & 0xff);
        frame[offset + 3] = (byte) (value & 0xff);
    }
}
