package lol.candyriya.continuum.velocity.events;

import lol.candyriya.continuum.api.event.CancellableContinuumEvent;
import lol.candyriya.continuum.api.event.ContinuumEvent;
import lol.candyriya.continuum.api.event.ContinuumEventListener;
import lol.candyriya.continuum.api.event.ContinuumFailureEvent;
import lol.candyriya.continuum.api.event.ContinuumLoadEvent;
import lol.candyriya.continuum.api.event.ContinuumModuleCaptureEvent;
import lol.candyriya.continuum.api.event.ContinuumModuleRestoreEvent;
import lol.candyriya.continuum.api.event.ContinuumRecoveryEvent;
import lol.candyriya.continuum.api.event.ContinuumRestoreEvent;
import lol.candyriya.continuum.api.event.ContinuumSaveEvent;
import lol.candyriya.continuum.api.event.ContinuumSessionCreatedEvent;
import lol.candyriya.continuum.api.event.ContinuumSessionReleasedEvent;
import lol.candyriya.continuum.api.event.ContinuumTransferEvent;
import lol.candyriya.continuum.api.event.EventService;
import lol.candyriya.continuum.api.event.EventSubscription;

import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The proxy-side event bus. One instance lives in the proxy's service graph for the
 * lifetime of the plugin; third-party listeners never construct it.
 *
 * <p><b>How third-party plugins hook in.</b> Listeners register programmatically — there
 * is no reflection scan, no annotation processing and no auto-discovery. The flow is:
 *
 * <pre>{@code
 * EventSubscription sub = ContinuumProvider.get().events()
 *         .subscribe(ContinuumTransferEvent.class, event -> {
 *             if (blockedBackends.contains(event.toBackendId())) {
 *                 event.cancel("backend under maintenance");
 *             }
 *         });
 * // later, in the plugin's shutdown hook (or never — closing the bus releases everything):
 * sub.close();
 * }</pre>
 *
 * <p>Subscriptions are scoped to the lifetime of the owning plugin; a platform that shuts
 * Continuum down also releases all subscriptions. First-party Continuum code fires events
 * through {@link ContinuumEvents}, not by calling {@link #dispatch(ContinuumEvent)} directly.</p>
 *
 * <p><b>Routing.</b> Exact class only. A subscription to one event type is never invoked
 * for another — there are no supertype or subtype dispatches, and supertype subscriptions
 * (for example {@code CancellableContinuumEvent.class} or {@code ContinuumEvent.class}) are
 * rejected with {@link IllegalArgumentException} at subscribe time.</p>
 *
 * <p><b>Dispatch order.</b> Synchronous, on the calling thread, in registration order.
 * There are no priorities and no ordering annotations; registration order is the only
 * ordering. Subscribing or unsubscribing from inside a handler is legal; the change takes
 * effect for subsequent dispatches, not for the in-flight one.</p>
 *
 * <p><b>Error isolation.</b> A listener may throw anything. The exception is caught, logged
 * at WARN together with the listener class, the faulting frame (class#method:line) and the
 * stack trace, and dispatch continues with the next listener. A throwing listener is never
 * propagated to the caller, never reaches other listeners as a failure, and is not
 * automatically unsubscribed. It also never aborts the operation the event shields.</p>
 *
 * <p><b>Cancellation (veto) semantics.</b> The three cancellable events —
 * {@link ContinuumLoadEvent}, {@link ContinuumSaveEvent} and {@link ContinuumTransferEvent} —
 * fire <em>before</em> their operation. Cancelling is sticky and idempotent: once cancelled,
 * an event cannot be un-cancelled. Cancelling does not interrupt dispatch — listeners
 * registered after the canceller still see the event and may inspect
 * {@link CancellableContinuumEvent#isCancelled()} to back off early. The veto takes effect
 * only after dispatch completes; the caller (not the bus) is responsible for honoring it:
 * a cancelled load aborts the restore and the player joins with default state, a cancelled
 * save aborts the snapshot, a cancelled transfer refuses the hand-off before anything
 * starts.</p>
 *
 * <p><b>Subscription lifecycle.</b> {@link EventSubscription#unsubscribe()} and
 * {@link EventSubscription#close()} are idempotent and safe from any thread. An in-flight
 * dispatch that has already started still delivers the event to a listener that unsubscribes
 * mid-flight; unsubscribing prevents future dispatches only. After {@link #close()} the bus
 * accepts no new subscriptions and delivers nothing; existing handles report
 * {@link EventSubscription#isActive()} false.</p>
 *
 * <p><b>Thread model.</b> The proxy has no game threads and no main thread that touches
 * player state, so every event this bus fires is async. The firing thread is whatever thread
 * completed the operation the event is about — in practice the proxy event loop for the
 * transfer path and Continuum's infrastructure executor for everything built on the session
 * and snapshot pipelines. Treat every one of them as an arbitrary thread: never assume
 * ordering against other network or game-state operations, and hop before touching any
 * platform state that is thread-affine. There is no "main thread" to hop to on a proxy;
 * use the proxy's scheduler for that. In particular:</p>
 * <ul>
 *   <li>{@link ContinuumTransferEvent} — the thread initiating the transfer: the proxy
 *       event thread for Velocity-originated transfers (for example a {@code /server}
 *       command or a plugin message); for backend-originated transfers the backend is the
 *       one firing this event instead of this bus.</li>
 *   <li>{@link ContinuumSessionCreatedEvent} / {@link ContinuumSessionReleasedEvent} — the
 *       thread completing the session transition, typically Continuum's infrastructure
 *       executor.</li>
 *   <li>{@link ContinuumRecoveryEvent} — the thread that detected the condition, typically
 *       Continuum's infrastructure executor.</li>
 *   <li>{@link ContinuumFailureEvent} — the thread that detected the failure; usually the
 *       infrastructure executor, but arbitrary in general.</li>
 *   <li>{@link ContinuumLoadEvent} / {@link ContinuumSaveEvent} / {@link ContinuumRestoreEvent}
 *       and the module events are backend-side concerns; if they are fired through this bus
 *       anyway, the thread is whatever completed the stage, never a game thread.</li>
 * </ul>
 *
 * <p>Continuum never fires events from a synchronized block. A listener that must touch
 * proxy state should be aware of its thread; heavy work belongs on the listener's own
 * executor. The dispatch thread is blocked for exactly as long as the listeners run.</p>
 *
 * <p>Mirrored by the backend implementation ({@code paper.events.PaperEventBus}); keep the
 * two in sync apart from the thread model described above.</p>
 *
 * @since 1.0.0
 */
public final class VelocityEventBus implements EventService, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VelocityEventBus.class);

    private final ConcurrentHashMap<Class<? extends ContinuumEvent>, CopyOnWriteArrayList<Subscription>> listeners
            = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public <E extends ContinuumEvent> @NotNull EventSubscription subscribe(@NotNull Class<E> type,
                                                                            @NotNull ContinuumEventListener<E> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        if (!ContinuumEvent.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("event type must be a member of the sealed ContinuumEvent "
                    + "hierarchy, got " + type.getName());
        }
        if (type.isInterface() || (type.getModifiers() & Modifier.ABSTRACT) != 0) {
            throw new IllegalArgumentException("event type must be a concrete class, got " + type.getName()
                    + "; subscriptions match exactly one class and never fire for subtypes or supertype markers");
        }
        if (closed.get()) {
            throw new IllegalStateException("the event bus is closed");
        }

        CopyOnWriteArrayList<Subscription> list = listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        Subscription subscription = new Subscription(listener, list);
        list.add(subscription);
        // lost the race with close(): take it back out instead of leaving a live handle on a dead bus
        if (closed.get() && list.remove(subscription)) {
            throw new IllegalStateException("the event bus is closed");
        }
        return subscription;
    }

    /**
     * Fires {@code event} to the listeners of its exact class, synchronously, in
     * registration order.
     *
     * <p>Listener exceptions are caught and logged at WARN; dispatch continues and this
     * method returns normally. Listeners of other event types are not invoked. Subscribing
     * or unsubscribing from inside a handler takes effect for subsequent dispatches, not for
     * this one. Cancellable events carry their veto inside them; this method does not throw
     * and does not act on {@link CancellableContinuumEvent#isCancelled()} — the caller
     * inspects the event after dispatch and honors the veto.</p>
     *
     * @param event the event to dispatch, never null
     * @throws NullPointerException when {@code event} is null
     */
    public void dispatch(@NotNull ContinuumEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed.get()) {
            LOGGER.warn("event bus is closed; dropping {}", event.getClass().getSimpleName());
            return;
        }
        CopyOnWriteArrayList<Subscription> list = listeners.get(event.getClass());
        if (list == null || list.isEmpty()) {
            return;
        }
        // snapshot up front: callbacks that (un)subscribe mid-flight don't touch this dispatch
        List<Subscription> snapshot = new ArrayList<>(list);
        for (Subscription subscription : snapshot) {
            subscription.deliver(event);
        }
    }

    /**
     * Releases every subscription and refuses new ones. Idempotent; safe from any thread.
     * After closing, {@link EventSubscription#isActive()} reports false for every handle
     * this bus issued, and {@link #dispatch(ContinuumEvent)} logs and drops events.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (CopyOnWriteArrayList<Subscription> list : listeners.values()) {
            for (Subscription subscription : list) {
                subscription.deactivate();
            }
        }
        listeners.clear();
    }

    private static void logListenerFailure(ContinuumEventListener<?> listener, ContinuumEvent event, Throwable error) {
        StackTraceElement[] trace = error.getStackTrace();
        StackTraceElement top = trace.length > 0 ? trace[0] : null;
        LOGGER.warn("continuum event listener {} threw while handling {} (at {}); continuing with the next listener",
                listener.getClass().getName(),
                event.getClass().getSimpleName(),
                top != null ? top.getClassName() + "#" + top.getMethodName() + ":" + top.getLineNumber() : "unknown",
                error);
    }

    private static final class Subscription implements EventSubscription {

        private final ContinuumEventListener<ContinuumEvent> listener;
        private final CopyOnWriteArrayList<Subscription> owner;
        private final AtomicBoolean active = new AtomicBoolean(true);

        @SuppressWarnings("unchecked")
        private Subscription(ContinuumEventListener<? extends ContinuumEvent> listener,
                             CopyOnWriteArrayList<Subscription> owner) {
            this.listener = (ContinuumEventListener<ContinuumEvent>) listener;
            this.owner = owner;
        }

        @Override
        public void unsubscribe() {
            if (active.compareAndSet(true, false)) {
                owner.remove(this);
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        private void deliver(ContinuumEvent event) {
            // no isActive() check on purpose: a dispatch that started already owns this delivery
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                logListenerFailure(listener, event, t);
            }
        }

        private void deactivate() {
            active.set(false);
        }
    }
}
