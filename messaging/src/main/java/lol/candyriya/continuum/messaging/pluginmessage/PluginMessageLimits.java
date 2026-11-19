package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.util.Preconditions;

/**
 * Size limits for frames. The cap is enforced on the raw byte array before
 * anything else happens — no allocation is made on behalf of an over-cap
 * frame beyond the array the platform already handed us.
 *
 * <p>{@link #DEFAULT_MAX_FRAME_BYTES} (64 KiB) is deliberately far below the
 * 1 MiB hard limit Minecraft imposes on plugin messages: Continuum control
 * traffic is small JSON, and a big frame here means something is broken or
 * hostile. Config may raise or lower the cap, but never above
 * {@link #ABSOLUTE_MAX_FRAME_BYTES}.</p>
 *
 * @param maxFrameBytes total frame cap in bytes, including the
 *                      {@value PluginMessageProtocol#HEADER_BYTES}-byte header
 */
public record PluginMessageLimits(int maxFrameBytes) {

    /** Default frame cap: 64 KiB, header included. */
    public static final int DEFAULT_MAX_FRAME_BYTES = 65_536;

    /** Smallest accepted cap: big enough for any minimal frame, small enough to catch nonsense. */
    public static final int MIN_MAX_FRAME_BYTES = 64;

    /** Hard ceiling, matching the 1 MiB plugin-message limit on both platforms. */
    public static final int ABSOLUTE_MAX_FRAME_BYTES = 1_048_576;

    public PluginMessageLimits {
        Preconditions.checkArgument(
                maxFrameBytes >= MIN_MAX_FRAME_BYTES && maxFrameBytes <= ABSOLUTE_MAX_FRAME_BYTES,
                "maxFrameBytes must be within [" + MIN_MAX_FRAME_BYTES + ", " + ABSOLUTE_MAX_FRAME_BYTES
                        + "], got " + maxFrameBytes);
    }

    /** The default limits: 64 KiB frame cap. */
    public static PluginMessageLimits defaults() {
        return new PluginMessageLimits(DEFAULT_MAX_FRAME_BYTES);
    }

    /** Capacity left for the payload JSON, in bytes, after the header. */
    public int maxPayloadBytes() {
        return maxFrameBytes - PluginMessageProtocol.HEADER_BYTES;
    }
}
