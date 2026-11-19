package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.event.ContinuumEvent;
import lol.candyriya.continuum.api.event.ContinuumEventListener;
import lol.candyriya.continuum.api.event.EventService;
import lol.candyriya.continuum.api.event.EventSubscription;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The platform-side half of the Continuum event model.
 *
 * <p>The public api surface ({@link EventService}) is subscribe-only — it cannot
 * dispatch. This dispatcher is the minimal platform registry behind it: exact
 * class match, registration order, listener exceptions caught and logged at WARN
 * so one broken listener never breaks a sync operation or the next listener
 * (mirrors the error policy documented on the api interface).</p>
 *
 * <p>Nothing subscribes through here today by default — the point is that when
 * the public {@code ContinuumApi} facade is registered, its {@code events()}
 * can be this instance and third-party plugins' {@code subscribe} calls land in
 * the same registry the pipeline dispatches through. Until then, Continuum's own
 * pipeline fires through the same dispatcher with zero listeners, which is free.</p>
 */
public final class EventDispatcher implements EventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventDispatcher.class);

    private final ConcurrentHashMap<Class<? extends ContinuumEvent>, CopyOnWriteArrayList<ContinuumEventListener<?>>> listeners =
            new ConcurrentHashMap<>();

    @Override
    public <E extends ContinuumEvent> @NotNull EventSubscription subscribe(@NotNull Class<E> type,
                                                                           @NotNull ContinuumEventListener<E> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<ContinuumEventListener<?>> list =
                listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return new SimpleSubscription(list, listener);
    }

    /** Synchronously dispatches {@code event} to the listeners of its exact class. */
    public <E extends ContinuumEvent> void dispatch(E event) {
        Objects.requireNonNull(event, "event");
        List<ContinuumEventListener<?>> exact = listeners.get(event.getClass());
        if (exact == null || exact.isEmpty()) {
            return;
        }
        for (ContinuumEventListener<?> raw : exact) {
            @SuppressWarnings("unchecked")
            ContinuumEventListener<E> listener = (ContinuumEventListener<E>) raw;
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                // per the api contract: caught, logged with the listener and event, dispatch continues
                LOGGER.warn("event listener {} threw while handling {}; continuing with the next listener",
                        listener.getClass().getName(), event.getClass().getSimpleName(), t);
            }
        }
    }

    /** How many subscriptions exist across all event types; diagnostics. */
    public int subscriptionCount() {
        int total = 0;
        for (CopyOnWriteArrayList<ContinuumEventListener<?>> list : listeners.values()) {
            total += list.size();
        }
        return total;
    }

    private record SimpleSubscription(CopyOnWriteArrayList<ContinuumEventListener<?>> list,
                                      ContinuumEventListener<?> listener) implements EventSubscription {

        @Override
        public void unsubscribe() {
            list.remove(listener);
        }

        @Override
        public boolean isActive() {
            return list.contains(listener);
        }
    }
}
