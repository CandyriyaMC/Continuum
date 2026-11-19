package lol.candyriya.continuum.velocity.messaging;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * Channel constants for the backend &lt;-&gt; proxy pipe.
 *
 * <p>{@code continuum:main} is a wire constant, not a config knob: both sides
 * must agree on it out of the box, and letting an operator change one side
 * without the other splits the network silently. Tuning that does belong in
 * config (frame size cap) lives in {@code messaging.plugin-message.max-payload-bytes}.</p>
 */
public final class ContinuumChannels {

    /** Channel for plugin messaging between backends and the proxy. */
    public static final MinecraftChannelIdentifier MAIN = MinecraftChannelIdentifier.from("continuum:main");

    private ContinuumChannels() {
    }
}
