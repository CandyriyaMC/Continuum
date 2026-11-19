package lol.candyriya.continuum.paper.modules.custom;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic-proxy fake for {@link Player}. Unsupported methods throw
 * {@link UnsupportedOperationException} naming the method, so a test that accidentally
 * touches the platform fails loudly instead of faking success. Supported calls are
 * recorded: setters write into {@code writes} under the method name.
 *
 * <p>Equals, hashCode and toString work normally (identity), because the adapters under
 * test and the guarded player rely on them.</p>
 */
final class FakePlayers {

    static final UUID FIXED_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private FakePlayers() {
        // static access only
    }

    /** A fake with no recorded writes. */
    static Player create() {
        return create(new ConcurrentHashMap<>());
    }

    /** A fake recording setter calls into {@code writes} (key: method name, value: argument array). */
    static Player create(Map<String, Object[]> writes) {
        Objects.requireNonNull(writes, "writes must not be null");
        return (Player) Proxy.newProxyInstance(
                FakePlayers.class.getClassLoader(),
                playerInterfaces(),
                new Handler(writes));
    }

    private static Class<?>[] playerInterfaces() {
        Set<Class<?>> out = new LinkedHashSet<>();
        collectInterfaces(Player.class, out);
        return out.toArray(new Class<?>[0]);
    }

    private static void collectInterfaces(Class<?> cls, Set<Class<?>> out) {
        if (cls == null || cls == Object.class) {
            return;
        }
        if (cls.isInterface() && !out.add(cls)) {
            return;
        }
        for (Class<?> iface : cls.getInterfaces()) {
            collectInterfaces(iface, out);
        }
        collectInterfaces(cls.getSuperclass(), out);
    }

    private record Handler(Map<String, Object[]> writes) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "getUniqueId" -> {
                    return FIXED_UUID;
                }
                case "getName" -> {
                    return "test-player";
                }
                case "getHealth" -> {
                    return 20.0;
                }
                case "getFoodLevel" -> {
                    return 17;
                }
                case "getSaturation" -> {
                    return 8.0f;
                }
                case "equals" -> {
                    Object arg = args == null ? null : args[0];
                    return proxy == arg;
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "FakePlayer(test-player)";
                }
                default -> {
                    // setters record, everything else is unsupported — loudly
                    if (name.startsWith("set") && args != null && args.length == 1) {
                        writes.put(name, args.clone());
                        return null;
                    }
                    throw new UnsupportedOperationException(
                            "fake player does not support method '" + name + "'");
                }
            }
        }
    }
}
