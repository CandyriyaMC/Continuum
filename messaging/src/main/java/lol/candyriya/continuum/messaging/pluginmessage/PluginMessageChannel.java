package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * The channel name every Continuum frame travels on, and the registration
 * contract for both platforms.
 *
 * <p>Velocity requires the channel in {@code namespace:path} form, lowercase,
 * matching {@code [a-z0-9_.-]} for the namespace and {@code [a-z0-9/._-]} for
 * the path; {@code continuum:main} satisfies that. On Paper the same string
 * is a valid namespaced key. {@code minecraft:}-prefixed channels are
 * reserved and silently rewritten by the platform — never use one.</p>
 *
 * <h2>Registering on Velocity</h2>
 * <ol>
 *   <li>Register outgoing plugin messages for the channel:
 *       <code>server.getChannelRegistrar().register(MinecraftChannelIdentifier.from(MAIN))</code></li>
 *   <li>In the {@code PluginMessageEvent} handler, call
 *       {@code event.setResult(PluginMessageEvent.ForwardResult.handled())}
 *       <b>before</b> looking at the bytes. Doing it early is what stops
 *       client-sent copies of this channel from being forwarded to backends;
 *       doing it late is how player bytes reach a server that trusts them.</li>
 *   <li>Decode only when <code>event.getSource() instanceof
 *       ServerConnection</code>. Any other source (a player, another plugin)
 *       is not a backend and must be dropped after step 2 — never parse it,
 *       never forward it, never answer it. This check is not optional and not
 *       implicit in the codec; the codec cannot see the event.</li>
 * </ol>
 *
 * <h2>Registering on Paper (and Folia)</h2>
 * <ol>
 *   <li><code>getServer().getMessenger().registerOutgoingPluginChannel(plugin, MAIN)</code></li>
 *   <li><code>getServer().getMessenger().registerIncomingPluginChannel(plugin, MAIN, listener)</code></li>
 *   <li>Inside the listener, treat <b>every</b> frame as hostile input. Paper
 *       hands you messages from the proxy and from any client that typed
 *       matching bytes on this channel; there is no source parameter to
 *       discriminate. Authenticity comes from the proxy's forwarding secret
 *       and from refusing to act on frames that do not match an in-flight
 *       correlation id — never from the bytes themselves.</li>
 *   <li>Replies (route result, session token, pong) must carry the correlation
 *       id of the request/report that triggered them; drop any such frame
 *       that matches no pending entry.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@link PluginMessageCodec} and {@link PluginMessageValidator} are
 *       stateless and thread-safe. Decode on the event thread, but do no
 *       blocking I/O there — hand decoded messages off to your own executors.</li>
 *   <li>{@link PendingRequests} is thread-safe; its timeout sweeper runs on
 *       daemon scheduler threads. Futures complete on whichever thread calls
 *       {@code complete}/{@code fail} — keep completion off the main thread
 *       if listeners do heavy work.</li>
 *   <li>Never call platform APIs that must run on a specific region/main
 *       scheduler from inside these classes; they carry no platform references
 *       on purpose.</li>
 * </ul>
 */
public final class PluginMessageChannel {

    /** The namespaced channel both platforms register: {@code continuum:main}. */
    public static final String MAIN = "continuum:main";

    private PluginMessageChannel() {
    }
}
