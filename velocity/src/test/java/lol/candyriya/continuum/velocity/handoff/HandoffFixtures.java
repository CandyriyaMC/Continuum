package lol.candyriya.continuum.velocity.handoff;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import net.kyori.adventure.text.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Scripted fakes for the proxy surface the handoff path touches.
 *
 * <p>Dynamic proxies over the real Velocity interfaces, so the coordinator can
 * only call what the interface declares. Everything a test does not script is an
 * inert default (empty collections, null references, false flags).</p>
 */
final class HandoffFixtures {

    private HandoffFixtures() {
    }

    static ServerInfo serverInfo(String name, int port) {
        return new ServerInfo(name, new InetSocketAddress("127.0.0.1", port));
    }

    static RegisteredServer registeredServer(String name, int port) {
        ServerInfo info = serverInfo(name, port);
        return proxy(RegisteredServer.class, (method, args) -> switch (method.getName()) {
            case "getServerInfo" -> info;
            case "getPlayersConnected" -> List.of();
            case "toString" -> "RegisteredServer[" + name + "]";
            default -> defaultValue(method.getReturnType());
        });
    }

    static ConnectionRequestBuilder.Result connectResult(RegisteredServer attempted,
                                                         ConnectionRequestBuilder.Status status) {
        return proxy(ConnectionRequestBuilder.Result.class, (method, args) -> switch (method.getName()) {
            case "getStatus" -> status;
            // the real default impl is getStatus() == SUCCESS; dynamic proxies skip interface defaults
            case "isSuccessful" -> status == ConnectionRequestBuilder.Status.SUCCESS;
            case "getReasonComponent" -> Optional.empty();
            case "getAttemptedConnection" -> attempted;
            default -> defaultValue(method.getReturnType());
        });
    }

    static ConnectionRequestBuilder connectionRequest(RegisteredServer target,
                                                      ConnectionRequestBuilder.Status connectStatus) {
        return proxy(ConnectionRequestBuilder.class, (method, args) -> switch (method.getName()) {
            case "getServer" -> target;
            case "connect" -> CompletableFuture.completedFuture(connectResult(target, connectStatus));
            case "connectWithIndication" -> CompletableFuture.completedFuture(connectStatus == ConnectionRequestBuilder.Status.SUCCESS);
            default -> defaultValue(method.getReturnType());
        });
    }

    static <T> T proxy(Class<T> type, BiFunction<Method, Object[], Object> body) {
        InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxyInstance);
            case "equals" -> proxyInstance == (args != null ? args[0] : null);
            case "toString" -> type.getSimpleName() + "@" + System.identityHashCode(proxyInstance);
            default -> body.apply(method, args == null ? new Object[0] : args);
        };
        @SuppressWarnings("unchecked")
        T instance = (T) Proxy.newProxyInstance(HandoffFixtures.class.getClassLoader(), new Class<?>[]{type}, handler);
        return instance;
    }

    static Object defaultValue(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == boolean.class) {
                return false;
            }
            if (type == void.class) {
                return null;
            }
            return 0;
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == CompletableFuture.class) {
            return new CompletableFuture<>();
        }
        if (type == List.class || type == java.util.Collection.class) {
            return List.of();
        }
        return null;
    }

    /** Counts every counter incremented; gauges are ignored. */
    static final class CountingMetrics implements lol.candyriya.continuum.velocity.Metrics {
        private final Map<String, Integer> counters = new ConcurrentHashMap<>();

        int count(String counter) {
            return counters.getOrDefault(counter, 0);
        }

        @Override
        public void increment(String counter) {
            counters.merge(counter, 1, Integer::sum);
        }

        @Override
        public void gauge(String gauge, double value) {
            // gauges are not under test here
        }
    }

    /** A player scripted onto {@code currentBackend} (null = not on any backend). */
    static final class FakePlayer {

        final String name;
        final UUID uuid;
        final RegisteredServer currentBackend;
        final List<Component> sentMessages = new ArrayList<>();
        volatile Component disconnectReason;
        volatile ConnectionRequestBuilder lastRequest;
        volatile int connectionRequestCount;
        ConnectionRequestBuilder.Status connectStatus = ConnectionRequestBuilder.Status.SUCCESS;
        private Player proxyInstance;

        FakePlayer(String name, UUID uuid, RegisteredServer currentBackend) {
            this.name = name;
            this.uuid = uuid;
            this.currentBackend = currentBackend;
        }

        Player asProxy() {
            if (proxyInstance == null) {
                proxyInstance = proxy(Player.class, (method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> name;
                    case "isActive", "isOnline" -> true;
                    case "getCurrentServer" -> currentBackend == null
                            ? Optional.empty()
                            : Optional.of(connectionTo(currentBackend));
                    case "createConnectionRequest" -> {
                        RegisteredServer target = (RegisteredServer) args[0];
                        lastRequest = connectionRequest(target, connectStatus);
                        connectionRequestCount++;
                        yield lastRequest;
                    }
                    case "disconnect" -> {
                        disconnectReason = (Component) args[0];
                        yield null;
                    }
                    case "sendMessage" -> {
                        sentMessages.add((Component) args[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
            }
            return proxyInstance;
        }

        FakeConnection connectionTo(RegisteredServer backend) {
            return new FakeConnection(this, backend, true);
        }
    }

    /** A backend-side connection to a player, bound to a backend server. */
    static final class FakeConnection implements ServerConnection {

        private final FakePlayer player;
        private final RegisteredServer backend;
        private final boolean sendResult;

        final List<byte[]> sentPayloads = new ArrayList<>();
        volatile ChannelIdentifier lastChannel;

        FakeConnection(FakePlayer player, RegisteredServer backend, boolean sendResult) {
            this.player = player;
            this.backend = backend;
            this.sendResult = sendResult;
        }

        @Override
        public RegisteredServer getServer() {
            return backend;
        }

        @Override
        public Optional<RegisteredServer> getPreviousServer() {
            return Optional.empty();
        }

        @Override
        public ServerInfo getServerInfo() {
            return backend.getServerInfo();
        }

        @Override
        public boolean isClientLoaded() {
            return true;
        }

        @Override
        public Player getPlayer() {
            return player.asProxy();
        }

        @Override
        public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
            lastChannel = identifier;
            sentPayloads.add(data);
            return sendResult;
        }

        @Override
        public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) {
            return sendPluginMessage(identifier, new byte[]{0});
        }
    }
}
