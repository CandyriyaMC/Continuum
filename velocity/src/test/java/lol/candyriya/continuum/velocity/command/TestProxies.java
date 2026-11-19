package lol.candyriya.continuum.velocity.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.util.ProxyVersion;

import java.net.InetSocketAddress;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * Dynamic-proxy fakes for the platform surfaces the command tree touches.
 * Every unhandled method returns a benign default (empty collections, zero,
 * false, completed futures) instead of throwing, so an unexpected call reads
 * as "feature not needed here" rather than a stack trace in the test log.
 */
public final class TestProxies {

    private TestProxies() {
    }

    private static final ClassLoader CL = TestProxies.class.getClassLoader();

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<Method, Object[], Object> body) {
        InvocationHandler handler = (p, method, args) -> {
            Object handled = body.apply(method, args);
            return handled == null ? defaultValue(method.getReturnType()) : handled;
        };
        return (T) Proxy.newProxyInstance(CL, new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if (type.equals(Optional.class)) {
                return Optional.empty();
            }
            if (List.class.isAssignableFrom(type)) {
                return List.of();
            }
            if (Collection.class.isAssignableFrom(type) || java.util.Set.class.isAssignableFrom(type)) {
                return List.of();
            }
            if (Map.class.isAssignableFrom(type)) {
                return Map.of();
            }
            if (CompletableFuture.class.isAssignableFrom(type)) {
                return CompletableFuture.completedFuture(null);
            }
            if (Executor.class.isAssignableFrom(type)) {
                return Executors.newVirtualThreadPerTaskExecutor();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == double.class || type == float.class) {
            return 0;
        }
        return 0;
    }

    static ServerInfo serverInfo(String name, String address, int port) {
        return new ServerInfo(name, new InetSocketAddress(address, port));
    }

    static RegisteredServer registeredServer(String name, String address, int port) {
        ServerInfo info = serverInfo(name, address, port);
        return proxy(RegisteredServer.class, (method, args) -> switch (method.getName()) {
            case "getServerInfo" -> info;
            default -> null;
        });
    }

    static Player player(String name, UUID uuid) {
        return proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getUsername" -> name;
            case "getPermissionValue" -> Tristate.FALSE;
            case "isActive", "isOnline" -> true;
            default -> null;
        });
    }

    static PluginDescription pluginDescription() {
        return proxy(PluginDescription.class, (method, args) -> switch (method.getName()) {
            case "getId" -> "continuum";
            case "getVersion" -> Optional.of("1.0.0-test");
            case "getName" -> Optional.of("Continuum");
            default -> null;
        });
    }

    static PluginContainer pluginContainer() {
        PluginDescription description = pluginDescription();
        return proxy(PluginContainer.class, (method, args) -> switch (method.getName()) {
            case "getDescription" -> description;
            case "getExecutorService" -> Executors.newVirtualThreadPerTaskExecutor();
            default -> null;
        });
    }

    /** Event manager that swallows everything; command registration only needs register(...). */
    static EventManager eventManager() {
        return proxy(EventManager.class, (method, args) -> switch (method.getName()) {
            case "fire", "fireAndForget" -> CompletableFuture.completedFuture(null);
            default -> null;
        });
    }

    private static final class MetaState {
        private volatile List<String> aliases = List.of();
        private volatile Object plugin;
    }

    private static CommandMeta commandMeta(MetaState state) {
        return proxy(CommandMeta.class, (method, args) -> switch (method.getName()) {
            case "getAliases" -> state.aliases;
            case "getHints" -> List.of();
            case "getPlugin" -> state.plugin;
            default -> null;
        });
    }

    private static CommandMeta.Builder metaBuilder(MetaState state) {
        final CommandMeta.Builder[] holder = new CommandMeta.Builder[1];
        holder[0] = proxy(CommandMeta.Builder.class, (method, args) -> switch (method.getName()) {
            case "aliases" -> {
                state.aliases = List.of((String[]) args[0]);
                yield holder[0];
            }
            case "plugin" -> {
                state.plugin = args[0];
                yield holder[0];
            }
            case "hint" -> holder[0];
            case "build" -> commandMeta(state);
            default -> null;
        });
        return holder[0];
    }

    /**
     * Velocity's command manager fake. Records registrations so tests can
     * assert the tree actually landed; everything else is a benign default.
     */
    static CommandManager commandManager(Map<String, List<String>> registrations) {
        return proxy(CommandManager.class, (method, args) -> {
            switch (method.getName()) {
                case "metaBuilder":
                    return metaBuilder(new MetaState());
                case "register":
                    if (args != null && args.length == 2 && args[0] instanceof CommandMeta meta) {
                        registrations.computeIfAbsent(meta.getPlugin() == null ? "continuum" : "continuum",
                                key -> new CopyOnWriteArrayList<>()).addAll(meta.getAliases());
                    }
                    return null;
                case "unregister":
                    return null;
                case "getAliases": {
                    List<String> all = new java.util.ArrayList<>();
                    registrations.values().forEach(all::addAll);
                    return List.copyOf(all);
                }
                case "hasCommand": {
                    String name = (String) args[0];
                    return registrations.values().stream().anyMatch(list -> list.contains(name));
                }
                case "executeAsync", "executeImmediatelyAsync":
                    return CompletableFuture.completedFuture(true);
                case "offerSuggestions":
                    return CompletableFuture.completedFuture(List.of());
                default:
                    return null;
            }
        });
    }

    /** Recorded command-manager registrations, keyed by plugin id. */
    static Map<String, List<String>> newRegistrationRecorder() {
        return new ConcurrentHashMap<>();
    }

    static ProxyVersion proxyVersion() {
        return new ProxyVersion("Velocity", "Sound Disk", "4.3.1-test");
    }

    /**
     * A complete-enough proxy: two registered backends, the given players
     * online, the given online-mode flag. Everything the command tree asks
     * (event manager, command manager, version, configuration) answers.
     */
    static ProxyServer proxyServer(Collection<RegisteredServer> servers,
                                   Collection<Player> players,
                                   Map<String, List<String>> registrations,
                                   boolean onlineMode) {
        ProxyConfig configuration = proxy(ProxyConfig.class, (method, args) -> switch (method.getName()) {
            case "isOnlineMode" -> onlineMode;
            case "isQueryEnabled", "shouldQueryShowPlugins", "shouldPreventClientProxyConnections",
                    "isAnnounceForge" -> false;
            case "getCompressionThreshold", "getCompressionLevel", "getLoginRatelimit",
                    "getCommandRatelimit", "getTabCompleteRatelimit" -> 256;
            default -> null;
        });
        EventManager events = eventManager();
        CommandManager commands = commandManager(registrations);
        PluginManager plugins = proxy(PluginManager.class, (method, args) -> switch (method.getName()) {
            case "getPlugin" -> Optional.of(pluginContainer());
            default -> null;
        });
        List<RegisteredServer> serversCopy = List.copyOf(servers);
        List<Player> playersCopy = List.copyOf(players);
        return proxy(ProxyServer.class, (method, args) -> switch (method.getName()) {
            case "getAllServers" -> serversCopy;
            case "getServer" -> serversCopy.stream()
                    .filter(server -> server.getServerInfo().getName().equals(args[0]))
                    .findFirst();
            case "getAllPlayers" -> playersCopy;
            case "getPlayer" -> {
                Object key = args[0];
                yield playersCopy.stream().filter(player -> key instanceof UUID uuid
                                ? player.getUniqueId().equals(uuid)
                                : player.getUsername().equalsIgnoreCase(String.valueOf(key)))
                        .findFirst();
            }
            case "getPlayerCount" -> playersCopy.size();
            case "matchPlayer" -> playersCopy.stream()
                    .filter(player -> player.getUsername().toLowerCase().startsWith(String.valueOf(args[0]).toLowerCase()))
                    .toList();
            case "matchServer" -> serversCopy.stream()
                    .filter(server -> server.getServerInfo().getName().startsWith(String.valueOf(args[0])))
                    .toList();
            case "getConfiguration" -> configuration;
            case "getEventManager" -> events;
            case "getCommandManager" -> commands;
            case "getPluginManager" -> plugins;
            case "getVersion" -> proxyVersion();
            case "isShuttingDown" -> false;
            default -> null;
        });
    }

    /**
     * Splits the singleton registration recorder bucket into a flat alias list.
     * Only used to sanity-check that the root command landed in the manager.
     */
    static List<String> registeredAliases(Map<String, List<String>> registrations) {
        return registrations.values().stream().flatMap(List::stream).distinct().toList();
    }
}
