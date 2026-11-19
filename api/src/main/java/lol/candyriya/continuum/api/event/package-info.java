/**
 * The public event model — typed notifications about Continuum's lifecycle, dispatched
 * synchronously to subscribers registered through
 * {@link lol.candyriya.continuum.api.event.EventService}.
 *
 * <p>All event types are members of the sealed {@link lol.candyriya.continuum.api.event.ContinuumEvent}
 * hierarchy. Exactly three are cancellable — {@link lol.candyriya.continuum.api.event.ContinuumLoadEvent},
 * {@link lol.candyriya.continuum.api.event.ContinuumSaveEvent} and
 * {@link lol.candyriya.continuum.api.event.ContinuumTransferEvent} — and they are fired
 * <em>before</em> the operation they shield begins. Cancelling one vetoes the operation;
 * observers see the cancelled flag but may not un-cancel.</p>
 *
 * <p><b>This package is experimental.</b> The event set and the dispatch semantics mirror
 * the sync lifecycle as of 1.0.0 and will be extended, not broken, within the 1.x line;
 * however, individual event shapes may gain fields or be renamed before a future major
 * release. Pin builds to a specific Continuum version and re-test on upgrade.</p>
 *
 * <p><b>Threading.</b> Every event documents the thread it is fired on. Listeners are
 * dispatched synchronously on that thread, in registration order. A listener that must
 * touch platform state should do so aware of its thread; anything heavier belongs on the
 * listener's own executor. Continuum never fires events from a synchronized block and
 * never blocks the dispatcher on a listener's internals beyond the listener's own
 * execution.</p>
 *
 * <p><b>Listener error policy.</b> Exceptions thrown by listeners are caught, logged at
 * WARN with the listener's class and the event type, and never propagated to the caller,
 * to other listeners, or to the sync pipeline. A failing listener is not unsubscribed
 * automatically and must not block the operation it observes.</p>
 *
 * <p><b>Mutation rules.</b> Events are read-only for listeners, with one exception: a
 * cancellable event's {@code cancel()} may be called. Listeners must not retain event
 * references beyond their {@code onEvent} invocation, must not start threads that touch
 * mutable event state, and must not perform blocking I/O on the dispatch thread.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
package lol.candyriya.continuum.api.event;

import org.jetbrains.annotations.ApiStatus;
