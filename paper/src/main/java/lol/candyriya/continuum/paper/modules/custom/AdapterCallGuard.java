package lol.candyriya.continuum.paper.modules.custom;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A pass-through wrapper around the player handle that refuses every call once
 * {@code cancelled} flips to true. Continuum hands this to adapters instead of the raw
 * player; when an adapter misses its deadline, Continuum flips the flag and the adapter's
 * late calls throw {@link AdapterCancelledException}.
 *
 * <p><b>Honest limits.</b> The guard covers exactly what the adapter does through the
 * handle it was given. It cannot see references obtained <em>before</em> the deadline
 * (an inventory handle, a location captured earlier, an entity it looked up) — those
 * remain live and mutable. The primary safety guarantee is still the platform contract:
 * adapter code runs on the player's platform thread and must return promptly. The guard
 * is the second line, not the first.</p>
 *
 * <p>{@code equals}, {@code hashCode} and {@code toString} always delegate: they reveal
 * no state and must not break logging once the guard trips. Everything else throws after
 * the flip. Calls before the flip are forwarded verbatim, including platform exceptions
 * (unwrapped).</p>
 *
 * <p>The wrapped player's interface hierarchy is collected at wrap time, so the guard works
 * for real platform players and for test doubles alike.</p>
 */
final class AdapterCallGuard {

    private AdapterCallGuard() {
        // static access only
    }

    /** Wraps {@code delegate}; the returned player type is the same interface the adapter sees. */
    static Player wrap(Player delegate, AtomicBoolean cancelled) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(cancelled, "cancelled must not be null");
        Class<?>[] interfaces = allInterfaces(delegate.getClass());
        return (Player) Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                interfaces,
                new GuardHandler(delegate, cancelled));
    }

    private static Class<?>[] allInterfaces(Class<?> cls) {
        Set<Class<?>> out = new LinkedHashSet<>();
        collectInterfaces(cls, out);
        return out.toArray(new Class<?>[0]);
    }

    private static void collectInterfaces(Class<?> cls, Set<Class<?>> out) {
        if (cls == null || cls == Object.class) {
            return;
        }
        if (cls.isInterface() && !out.add(cls)) {
            // already visited
            return;
        }
        for (Class<?> iface : cls.getInterfaces()) {
            collectInterfaces(iface, out);
        }
        collectInterfaces(cls.getSuperclass(), out);
    }

    private record GuardHandler(Player delegate, AtomicBoolean cancelled) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("equals")) {
                Object arg = args == null ? null : args[0];
                return proxy == arg || delegate == arg || delegate.equals(arg);
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(delegate);
            }
            if (name.equals("toString")) {
                // do not touch the delegate: its toString may hit platform state we no longer trust
                return "AdapterCallGuard(player=" + System.identityHashCode(delegate) + ")";
            }
            if (cancelled.get()) {
                throw new AdapterCancelledException(
                        "adapter call '" + name + "' refused: the adapter missed its deadline");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
