/**
 * Plugin-message transport: the channel between backends and the Velocity proxy.
 *
 * <p>Channel: {@value PluginMessageChannel#MAIN}. Every frame is hostile
 * network input — validation is the point of this package, not politeness.</p>
 *
 * <h2>Wire format</h2>
 * <pre>
 * offset  size  field
 * 0       4     magic, ASCII "ctmm" (0x63 0x74 0x6D 0x6D)
 * 4       1     protocol version (1)
 * 5       1     message kind, see {@link PluginMessageKind}
 * 6       8     correlation id, MSB of the UUID
 * 14      8     correlation id, LSB of the UUID
 * 22      4     payload length in bytes, big-endian
 * 26      N     payload: UTF-8 JSON for the kind's record
 * </pre>
 *
 * <h2>The receiving contract, on both platforms</h2>
 * <ol>
 *   <li><b>Source check first.</b> On Velocity: decode only when
 *       <code>event.getSource() instanceof ServerConnection</code>. Anything
 *       else is not a backend — drop it after setting the event result, never
 *       parse it, never reply to it. On Paper: there is no source parameter;
 *       the only discriminant is an in-flight correlation id, so treat every
 *       frame as suspect.</li>
 *   <li><b>Mark the event handled before parsing.</b> Velocity:
 *       <code>event.setResult(ForwardResult.handled())</code> before touching
 *       the bytes — unconditionally, so player-sent copies of this channel
 *       never reach backends. This is the single most important line of the
 *       handler.</li>
 *   <li><b>Cap the frame before parsing.</b> The configured limit
 *       (default 64 KiB) rejects oversize input without interpreting a
 *       single payload byte.</li>
 *   <li><b>Never trust, always validate.</b> Use {@link PluginMessageCodec}
 *       and let it throw, or {@link PluginMessageValidator} when you need a
 *       full error report. A frame that fails validation is dropped and
 *       logged with the connection context — never "best-effort parsed".</li>
 *   <li><b>Never answer a player-sourced frame.</b> A reply on this channel
 *       is itself attack surface; replying to hostile input turns the proxy
 *       into an oracle.</li>
 * </ol>
 *
 * <h2>Trust model</h2>
 * <p>Plugin messages are not authenticated on their own. Authenticity comes
 * from: modern forwarding with a forwarding secret (checked at login, not per
 * message), the proxy refusing to forward player-sent copies of this channel,
 * and the correlation ids both sides match replies against. Defense in depth:
 * run the proxy on a known network, keep backends unreachable from outside it,
 * keep the channel set minimal.</p>
 *
 * <h2>Direction discipline</h2>
 * <p>The two hierarchies are sealed separately. Backends emit
 * {@link BackendToProxyMessage}; the proxy emits {@link ProxyToBackendMessage},
 * and only as a reply to an in-flight backend message, carrying that message's
 * correlation id. The direction of a kind is fixed by {@link PluginMessageKind}
 * and enforced on decode with {@code expected} — a crossed wire fails loudly.</p>
 *
 * <h2>Threading</h2>
 * <p>Decoders and validators are stateless and safe on any thread. The
 * platform event threads this runs on must not block on I/O; hand decoded
 * messages to your own executors, scheduler or region threads as appropriate
 * for the action they trigger.</p>
 */
package lol.candyriya.continuum.messaging.pluginmessage;
