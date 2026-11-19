package lol.candyriya.continuum.velocity.command;

import lol.candyriya.continuum.common.config.DatabaseType;
import lol.candyriya.continuum.common.config.KnownModules;
import lol.candyriya.continuum.common.i18n.TypedArgument;
import lol.candyriya.continuum.velocity.BuildConstants;
import lol.candyriya.continuum.velocity.VelocityServices;
import lol.candyriya.continuum.velocity.VelocityMessages;
import lol.candyriya.continuum.velocity.messaging.ContinuumChannels;
import lol.candyriya.continuum.velocity.messaging.MessagingHealth;
import lol.candyriya.continuum.velocity.license.LicenseReport;
import lol.candyriya.continuum.velocity.update.UpdateMessages;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import net.kyori.adventure.text.Component;

import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.CommandExecutionException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.NoPermissionException;
import org.incendo.cloud.exception.handling.ExceptionHandler;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.permission.Permission;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.incendo.cloud.velocity.parser.PlayerParser;
import org.incendo.cloud.velocity.parser.ServerParser;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code /continuum} command tree.
 *
 * <p>Everything is registered on the Cloud manager at init time - Cloud's Velocity
 * binding registers eagerly, and Velocity only accepts new commands before the
 * first connection is made. Output goes through {@link VelocityMessages} for the
 * shared catalog keys and through {@link CommandTexts} for the proxy-native
 * strings, so every line is prefixed.</p>
 *
 * <p>Two permission nodes gate the tree: {@code continuum.admin} for operations
 * with side effects, and {@code continuum.readonly} for the read-only surface.
 * Holding {@code continuum.admin} also passes the read-only checks, so a full
 * operator node is enough for the whole tree. The backend-acting player
 * commands run through {@link PlayerCommandService}; the default wiring
 * refuses them with the precise reason until the backend command channel is
 * connected.</p>
 */
public final class ContinuumCommands {

    public static final String PERMISSION_ADMIN = "continuum.admin";
    public static final String PERMISSION_READONLY = "continuum.readonly";
    public static final String PERMISSION_LICENSE = "continuum.license";
    public static final String PERMISSION_UPDATE = "continuum.update";

    /** Read-only commands: holding either node is enough. */
    private static final Permission READ_OR_ADMIN = Permission.anyOf(
            Permission.permission(PERMISSION_READONLY),
            Permission.permission(PERMISSION_ADMIN));

    /** Where the tip jar releases will be published; used in the license-missing message. */
    private static final String RELEASES_URL = "https://github.com/CandyriyaMC/Continuum/releases";

    private static final List<HelpEntry> HELP_ENTRIES = List.of(
            new HelpEntry("/continuum", "Show this help.", PERMISSION_READONLY),
            new HelpEntry("/continuum help", "Show this help.", PERMISSION_READONLY),
            new HelpEntry("/continuum version", "Show the running Continuum and Velocity versions.", PERMISSION_READONLY),
            new HelpEntry("/continuum reload", "Re-read and validate the configuration, then reload the locales.", PERMISSION_ADMIN),
            new HelpEntry("/continuum status", "Show registered backends, current connections and messaging health.", PERMISSION_READONLY),
            new HelpEntry("/continuum player <player> info", "Show live session state for a player on this node.", PERMISSION_READONLY),
            new HelpEntry("/continuum player <player> save", "Capture and store the player's state now.", PERMISSION_ADMIN),
            new HelpEntry("/continuum player <player> reload", "Re-read the player's stored state into the live session.", PERMISSION_ADMIN),
            new HelpEntry("/continuum player <player> snapshot", "Capture a restore point of the player's state.", PERMISSION_ADMIN),
            new HelpEntry("/continuum player <player> restore", "Restore the player's state from a snapshot. Destructive.", PERMISSION_ADMIN),
            new HelpEntry("/continuum player <player> history", "List the player's stored snapshots.", PERMISSION_READONLY),
            new HelpEntry("/continuum backend list", "List configured backends with live connection counts.", PERMISSION_READONLY),
            new HelpEntry("/continuum backend status <server>", "Show live detail for one backend.", PERMISSION_READONLY),
            new HelpEntry("/continuum module list", "List the built-in synchronization modules and their toggles.", PERMISSION_READONLY),
            new HelpEntry("/continuum module info <module>", "Show detailed information for one module.", PERMISSION_READONLY),
            new HelpEntry("/continuum diagnostics", "Show proxy diagnostics: config summary, channel health, counts.", PERMISSION_ADMIN),
            new HelpEntry("/continuum license redeem <code>", "Redeem a license code.", PERMISSION_LICENSE),
            new HelpEntry("/continuum license check", "Show the current license state.", PERMISSION_LICENSE),
            new HelpEntry("/continuum license reset", "Clear locally registered license state.", PERMISSION_LICENSE),
            new HelpEntry("/continuum update", "Check whether a Continuum update is available.", PERMISSION_UPDATE));

    /** A help row: the command path, one English line, the node it needs. */
    private record HelpEntry(String command, String description, String permission) {
    }

    private ContinuumCommands() {
    }

    public static void register(VelocityCommandManager<CommandSource> manager,
                                VelocityServices services,
                                ProxyServer proxy,
                                Logger logger) {
        VelocityMessages messages = services.messages();
        registerExceptionHandlers(manager, services, logger);

        PlayerInspectionService inspection = new RegistryPlayerInspectionService(services.registry());
        PlayerCommandService playerCommands = new UnavailablePlayerCommandService();

        // bare /continuum shows the help document
        manager.command(manager.commandBuilder("continuum")
                .permission(READ_OR_ADMIN)
                .handler(context -> showHelp(services, context.sender())));

        manager.command(manager.commandBuilder("continuum")
                .literal("help")
                .permission(READ_OR_ADMIN)
                .handler(context -> showHelp(services, context.sender())));

        manager.command(manager.commandBuilder("continuum")
                .literal("version")
                .permission(READ_OR_ADMIN)
                .handler(context -> messages.send(context.sender(), "velocity.version",
                        TypedArgument.text("version", BuildConstants.VERSION),
                        TypedArgument.text("velocity", proxy.getVersion().getVersion()))));

        manager.command(manager.commandBuilder("continuum")
                .literal("reload")
                .permission(PERMISSION_ADMIN)
                .handler(context -> {
                    boolean applied = services.reload();
                    messages.send(context.sender(), applied ? "admin.reload.success" : "admin.reload.failed");
                }));

        manager.command(manager.commandBuilder("continuum")
                .literal("status")
                .permission(READ_OR_ADMIN)
                .handler(context -> showStatus(services, proxy, context.sender())));

        manager.command(manager.commandBuilder("continuum")
                .literal("player")
                .required("player", PlayerParser.playerParser())
                .literal("info")
                .permission(READ_OR_ADMIN)
                .handler(context -> showPlayerInfo(services, proxy, context.sender(),
                        context.<Player>optional("player").orElseThrow(), inspection, logger)));

        manager.command(manager.commandBuilder("continuum")
                .literal("player")
                .required("player", PlayerParser.playerParser())
                .literal("save")
                .permission(PERMISSION_ADMIN)
                .handler(context -> showPlayerAction(context.sender(), playerCommands, logger,
                        context.<Player>optional("player").orElseThrow(), PlayerCommandService.PlayerCommand.SAVE)));

        manager.command(manager.commandBuilder("continuum")
                .literal("player")
                .required("player", PlayerParser.playerParser())
                .literal("reload")
                .permission(PERMISSION_ADMIN)
                .handler(context -> showPlayerAction(context.sender(), playerCommands, logger,
                        context.<Player>optional("player").orElseThrow(), PlayerCommandService.PlayerCommand.RELOAD)));

        manager.command(manager.commandBuilder("continuum")
                .literal("player")
                .required("player", PlayerParser.playerParser())
                .literal("snapshot")
                .permission(PERMISSION_ADMIN)
                .handler(context -> showPlayerAction(context.sender(), playerCommands, logger,
                        context.<Player>optional("player").orElseThrow(), PlayerCommandService.PlayerCommand.SNAPSHOT)));

        manager.command(manager.commandBuilder("continuum")
                .literal("player")
                .required("player", PlayerParser.playerParser())
                .literal("restore")
                .permission(PERMISSION_ADMIN)
                .handler(context -> showPlayerAction(context.sender(), playerCommands, logger,
                        context.<Player>optional("player").orElseThrow(), PlayerCommandService.PlayerCommand.RESTORE)));

        manager.command(manager.commandBuilder("continuum")
                .literal("player")
                .required("player", PlayerParser.playerParser())
                .literal("history")
                .permission(READ_OR_ADMIN)
                .handler(context -> showPlayerAction(context.sender(), playerCommands, logger,
                        context.<Player>optional("player").orElseThrow(), PlayerCommandService.PlayerCommand.HISTORY)));

        manager.command(manager.commandBuilder("continuum")
                .literal("backend")
                .literal("list")
                .permission(READ_OR_ADMIN)
                .handler(context -> showBackendList(services, proxy, context.sender())));

        manager.command(manager.commandBuilder("continuum")
                .literal("backend")
                .literal("status")
                .permission(READ_OR_ADMIN)
                .required("server", ServerParser.serverParser())
                .handler(context -> showBackendStatus(services, context.sender(),
                        context.<RegisteredServer>optional("server").orElseThrow())));

        manager.command(manager.commandBuilder("continuum")
                .literal("module")
                .literal("list")
                .permission(READ_OR_ADMIN)
                .handler(context -> showModuleList(services, context.sender())));

        manager.command(manager.commandBuilder("continuum")
                .literal("module")
                .literal("info")
                .permission(READ_OR_ADMIN)
                .required("module", StringParser.stringParser(),
                        SuggestionProvider.suggestingStrings(ModuleCatalog.builtinIds()))
                .handler(context -> showModuleInfo(services, context.sender(),
                        context.<String>optional("module").orElse(""))));

        manager.command(manager.commandBuilder("continuum")
                .literal("diagnostics")
                .permission(PERMISSION_ADMIN)
                .handler(context -> showDiagnostics(services, proxy, context.sender())));

        manager.command(manager.commandBuilder("continuum")
                .literal("license")
                .literal("redeem")
                .permission(PERMISSION_LICENSE)
                .required("code", StringParser.greedyStringParser())
                .handler(context -> {
                    String code = context.<String>optional("code").orElse("");
                    reportLicense(services, context.sender(), services.licenseService().redeem(code));
                }));

        manager.command(manager.commandBuilder("continuum")
                .literal("license")
                .literal("check")
                .permission(PERMISSION_LICENSE)
                .handler(context -> reportLicense(services, context.sender(), services.licenseService().check())));

        manager.command(manager.commandBuilder("continuum")
                .literal("license")
                .literal("reset")
                .permission(PERMISSION_LICENSE)
                .handler(context -> reportLicense(services, context.sender(), services.licenseService().reset())));

        manager.command(manager.commandBuilder("continuum")
                .literal("update")
                .permission(PERMISSION_UPDATE)
                .handler(context -> runUpdate(services, logger, context.sender(), false)));

        manager.command(manager.commandBuilder("continuum")
                .literal("update")
                .literal("check")
                .permission(PERMISSION_UPDATE)
                .handler(context -> runUpdate(services, logger, context.sender(), false)));

        manager.command(manager.commandBuilder("continuum")
                .literal("update")
                .literal("install")
                .permission(PERMISSION_UPDATE)
                .handler(context -> runUpdate(services, logger, context.sender(), true)));
    }

    private static void runUpdate(VelocityServices services, Logger logger, CommandSource source, boolean install) {
        var updater = services.updateService();
        if (updater == null) {
            source.sendMessage(services.messages().prefixed(
                    net.kyori.adventure.text.Component.text(
                            "The update subsystem is not available in this build.",
                            net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
            return;
        }
        source.sendMessage(UpdateMessages.started(services.messages(), install));
        var future = install ? updater.installNow() : updater.checkNow();
        future.whenComplete((snapshot, error) -> {
            if (error != null || snapshot == null) {
                // the updater never lets this happen; if it does, the answer stays honest
                logger.error("update {} failed to produce a result", install ? "install" : "check", error);
                source.sendMessage(services.messages().prefixed(
                        net.kyori.adventure.text.Component.text("The update check failed; the proxy keeps running.",
                                net.kyori.adventure.text.format.NamedTextColor.RED)));
                return;
            }
            source.sendMessage(UpdateMessages.outcome(services.messages(), snapshot, install));
        });
    }

    private static void registerExceptionHandlers(VelocityCommandManager<CommandSource> manager,
                                                  VelocityServices services,
                                                  Logger logger) {
        VelocityMessages messages = services.messages();
        var exceptions = manager.exceptionController();
        exceptions.clearHandlers();

        ExceptionHandler<CommandSource, NoPermissionException> noPermission = exceptionContext ->
                messages.send(exceptionContext.context().sender(), "velocity.error.no-permission");
        exceptions.registerHandler(NoPermissionException.class, noPermission);

        ExceptionHandler<CommandSource, InvalidSyntaxException> badSyntax = exceptionContext ->
                messages.send(exceptionContext.context().sender(), "velocity.error.usage",
                        TypedArgument.text("usage", exceptionContext.exception().correctSyntax()));
        exceptions.registerHandler(InvalidSyntaxException.class, badSyntax);

        ExceptionHandler<CommandSource, ArgumentParseException> badArgument = exceptionContext -> {
            Throwable cause = exceptionContext.exception().getCause();
            messages.send(exceptionContext.context().sender(), "velocity.error.argument",
                    TypedArgument.text("argument", cause == null ? "input" : cause.getMessage()));
        };
        exceptions.registerHandler(ArgumentParseException.class, badArgument);

        ExceptionHandler<CommandSource, CommandExecutionException> executionFailed = exceptionContext -> {
            Throwable cause = exceptionContext.exception().getCause();
            logger.error("command execution failed", cause == null ? exceptionContext.exception() : cause);
            messages.send(exceptionContext.context().sender(), "velocity.error.internal");
        };
        exceptions.registerHandler(CommandExecutionException.class, executionFailed);

        // last-resort handler: something in our own code blew up
        ExceptionHandler<CommandSource, Throwable> internal = exceptionContext -> {
            logger.error("unhandled error while running a command", exceptionContext.exception());
            messages.send(exceptionContext.context().sender(), "velocity.error.internal");
        };
        exceptions.registerHandler(Throwable.class, internal);
    }

    private static void showHelp(VelocityServices services, CommandSource source) {
        VelocityMessages messages = services.messages();
        String locale = messages.localeFor(source);

        Component document = messages.render(locale, "velocity.help.header",
                TypedArgument.text("version", BuildConstants.VERSION));
        for (HelpEntry entry : HELP_ENTRIES) {
            document = appendLine(document, CommandTexts.render(CommandTexts.HELP_ENTRY,
                    TypedArgument.text("command", entry.command()),
                    TypedArgument.text("description", entry.description()),
                    TypedArgument.text("permission", entry.permission())));
        }
        source.sendMessage(messages.prefixed(document));
    }

    private static void showStatus(VelocityServices services, ProxyServer proxy, CommandSource source) {
        VelocityMessages messages = services.messages();
        String locale = messages.localeFor(source);
        var config = services.config();
        var counts = services.registry().connectionsByBackend();

        List<RegisteredServer> backends = proxy.getAllServers().stream()
                .sorted(Comparator.comparing(server -> server.getServerInfo().getName()))
                .toList();

        Component document = messages.render(locale, "velocity.status.header");
        document = appendLine(document, messages.render(locale, "velocity.status.node",
                TypedArgument.text("network", config.network().id()),
                TypedArgument.text("server", config.network().serverId()),
                TypedArgument.text("role", config.network().role().name().toLowerCase(Locale.ROOT))));

        document = appendLine(document, messages.render(locale, "velocity.status.backends-header",
                TypedArgument.number("count", backends.size())));
        if (backends.isEmpty()) {
            document = appendLine(document, messages.render(locale, "velocity.status.backend-empty"));
        }
        for (RegisteredServer server : backends) {
            ServerInfo info = server.getServerInfo();
            document = appendLine(document, messages.render(locale, "velocity.status.backend-entry",
                    TypedArgument.text("name", info.getName()),
                    TypedArgument.text("address", hostAddress(info.getAddress())),
                    TypedArgument.number("connections", counts.getOrDefault(info.getName(), 0))));
        }

        document = appendLine(document, messages.render(locale, "velocity.status.online",
                TypedArgument.number("count", services.registry().onlineCount())));
        document = appendLine(document, messages.render(locale, "velocity.status.awaiting",
                TypedArgument.number("count", services.registry().awaitingBackendCount())));
        document = appendLine(document, messages.render(locale, "velocity.status.debounce",
                TypedArgument.number("seconds", services.localConfig().reconnectDebounceSeconds())));

        document = appendLine(document, messages.render(locale, "velocity.status.messaging-header"));
        boolean messagingEnabled = config.messaging().pluginMessage().enabled();
        int maxPayload = config.messaging().pluginMessage().maxPayloadBytes();
        MessagingHealth health = services.router().health(messagingEnabled, ContinuumChannels.MAIN.getId(), maxPayload);

        document = appendLine(document, messages.render(locale, "velocity.status.messaging-channel",
                TypedArgument.text("channel", health.channel()),
                TypedArgument.component("state", messages.render(locale,
                        messagingEnabled ? "velocity.status.messaging-enabled" : "velocity.status.messaging-disabled")),
                TypedArgument.number("max", maxPayload)));

        Component last = health.lastFrameAt()
                .map(instant -> messages.render(locale, "velocity.status.last-ago",
                        TypedArgument.number("seconds", Math.max(0, Duration.between(instant, Instant.now()).toSeconds()))))
                .orElse(messages.render(locale, "velocity.status.messaging-never"));
        document = appendLine(document, messages.render(locale, "velocity.status.messaging-traffic",
                TypedArgument.number("count", health.acceptedFrames()),
                TypedArgument.component("last", last)));

        source.sendMessage(messages.prefixed(document));
    }

    private static void showPlayerInfo(VelocityServices services, ProxyServer proxy,
                                       CommandSource source, Player player,
                                       PlayerInspectionService inspection, Logger logger) {
        Optional<PlayerInspectionService.PlayerSessionView> view = inspection.inspect(player.getUniqueId());
        if (view.isEmpty()) {
            CommandTexts.send(source, CommandTexts.render(CommandTexts.PLAYER_INFO_NO_DATA,
                    TypedArgument.text("player", player.getUsername())));
            return;
        }
        PlayerInspectionService.PlayerSessionView session = view.get();
        long debounce = services.localConfig().reconnectDebounceSeconds();
        boolean awaiting = session.awaitingBackend();

        List<Component> lines = new ArrayList<>();
        lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_HEADER,
                TypedArgument.text("player", player.getUsername()),
                TypedArgument.text("uuid", player.getUniqueId().toString())));
        if (awaiting) {
            long awaitingSeconds = Math.max(0, Duration.between(session.connectedAt(), Instant.now()).toSeconds());
            lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_BACKEND_AWAITING,
                    TypedArgument.number("seconds", awaitingSeconds)));
            if (!session.awaitingReason().isBlank()) {
                lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_AWAITING_REASON,
                        TypedArgument.text("reason", session.awaitingReason())));
            }
        } else {
            lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_BACKEND,
                    TypedArgument.text("backend", session.backendName())));
        }
        lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_PLACEMENT,
                TypedArgument.text("state", awaiting ? "awaiting backend outcome" : "connected")));

        String iso = session.connectedAt().toString();
        String ago = humanDuration(Duration.between(session.connectedAt(), Instant.now()));
        lines.add(CommandTexts.render(awaiting ? CommandTexts.PLAYER_INFO_AWAITING_SINCE
                        : CommandTexts.PLAYER_INFO_SINCE,
                TypedArgument.text("iso", iso), TypedArgument.text("ago", ago)));
        lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_TRANSITIONS,
                TypedArgument.number("count", session.transitionsInWindow()),
                TypedArgument.number("window", debounce)));

        lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_STORAGE_HEAD));
        lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_FENCING));
        lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_SNAPSHOT));
        lines.add(CommandTexts.render(CommandTexts.PLAYER_INFO_ONLINE_MODE,
                TypedArgument.text("mode", proxy.getConfiguration().isOnlineMode()
                        ? CommandTexts.PLAYER_INFO_ONLINE_MODE_ONLINE
                        : CommandTexts.PLAYER_INFO_ONLINE_MODE_OFFLINE)));

        CommandTexts.sendDocument(source, CommandTexts.document(lines.remove(0), lines));
        logger.debug("player info for {}: backend={} awaiting={}", player.getUniqueId(),
                session.backendName(), awaiting);
    }

    private static void showPlayerAction(CommandSource source, PlayerCommandService playerCommands,
                                         Logger logger, Player player,
                                         PlayerCommandService.PlayerCommand command) {
        PlayerCommandService.PlayerCommandResult result =
                playerCommands.execute(command, player.getUniqueId(), player.getUsername());
        if (result.executed()) {
            CommandTexts.send(source, CommandTexts.render(CommandTexts.PLAYER_ACTION_DONE,
                    TypedArgument.text("command", command.name().toLowerCase(Locale.ROOT)),
                    TypedArgument.text("player", player.getUsername()),
                    TypedArgument.text("detail", result.detail())));
            return;
        }
        logger.info("player command '{}' refused for {} ({}): {}", command, player.getUsername(), player.getUniqueId(), result.detail());
        CommandTexts.send(source, CommandTexts.render(CommandTexts.PLAYER_ACTION_UNAVAILABLE,
                TypedArgument.text("command", command.name().toLowerCase(Locale.ROOT)),
                TypedArgument.text("detail", result.detail())));
    }

    private static void showBackendList(VelocityServices services, ProxyServer proxy, CommandSource source) {
        List<RegisteredServer> backends = proxy.getAllServers().stream()
                .sorted(Comparator.comparing(server -> server.getServerInfo().getName()))
                .toList();
        if (backends.isEmpty()) {
            CommandTexts.sendDocument(source, CommandTexts.render(CommandTexts.BACKEND_LIST_EMPTY));
            return;
        }
        var config = services.config();
        var counts = services.registry().connectionsByBackend();
        boolean messagingEnabled = config.messaging().pluginMessage().enabled();
        int maxPayload = config.messaging().pluginMessage().maxPayloadBytes();
        MessagingHealth health = services.router().health(messagingEnabled, ContinuumChannels.MAIN.getId(), maxPayload);

        List<Component> rows = new ArrayList<>();
        for (RegisteredServer server : backends) {
            ServerInfo info = server.getServerInfo();
            rows.add(CommandTexts.render(CommandTexts.BACKEND_LIST_ENTRY,
                    TypedArgument.text("name", info.getName()),
                    TypedArgument.text("address", hostAddress(info.getAddress())),
                    TypedArgument.number("connections", counts.getOrDefault(info.getName(), 0)),
                    TypedArgument.number("frames", health.framesByBackend().getOrDefault(info.getName(), 0L))));
        }
        CommandTexts.sendDocument(source, CommandTexts.document(
                CommandTexts.render(CommandTexts.BACKEND_LIST_HEADER, TypedArgument.number("count", backends.size())),
                rows));
    }

    private static void showBackendStatus(VelocityServices services,
                                          CommandSource source, RegisteredServer server) {
        var config = services.config();
        String name = server.getServerInfo().getName();
        int connections = services.registry().connectionsByBackend().getOrDefault(name, 0);
        boolean messagingEnabled = config.messaging().pluginMessage().enabled();
        int maxPayload = config.messaging().pluginMessage().maxPayloadBytes();
        MessagingHealth health = services.router().health(messagingEnabled, ContinuumChannels.MAIN.getId(), maxPayload);

        CommandTexts.sendDocument(source, CommandTexts.document(
                CommandTexts.render(CommandTexts.BACKEND_STATUS, TypedArgument.text("name", name)),
                List.of(
                        CommandTexts.render(CommandTexts.BACKEND_STATUS_ADDRESS,
                                TypedArgument.text("address", hostAddress(server.getServerInfo().getAddress()))),
                        CommandTexts.render(CommandTexts.BACKEND_STATUS_CONNECTIONS,
                                TypedArgument.number("connections", connections)),
                        CommandTexts.render(CommandTexts.BACKEND_STATUS_FRAMES,
                                TypedArgument.number("frames", health.framesByBackend().getOrDefault(name, 0L))))));
    }

    private static void showModuleList(VelocityServices services, CommandSource source) {
        var toggles = services.config().sync().modulesWithDefaults();
        List<Component> rows = new ArrayList<>();
        for (ModuleCatalog.BuiltinModule module : ModuleCatalog.builtins()) {
            String state;
            String configKey;
            if (module.configKey() != null) {
                state = toggles.getOrDefault(module.configKey(), true) ? "enabled" : "disabled";
                configKey = "sync.modules." + module.configKey();
            } else {
                state = "always on";
                configKey = "no config toggle";
            }
            rows.add(CommandTexts.render(CommandTexts.MODULE_LIST_ENTRY,
                    TypedArgument.text("id", module.id()),
                    TypedArgument.text("state", state),
                    TypedArgument.text("config", configKey)));
        }
        rows.add(CommandTexts.render(CommandTexts.MODULE_LIST_FOOTER));
        rows.add(CommandTexts.render(CommandTexts.MODULE_NAMESPACE_NOTE));
        CommandTexts.sendDocument(source, CommandTexts.document(
                CommandTexts.render(CommandTexts.MODULE_LIST_HEADER,
                        TypedArgument.number("count", ModuleCatalog.builtins().size())),
                rows));
    }

    private static void showModuleInfo(VelocityServices services, CommandSource source, String query) {
        if (ModuleCatalog.isCustomNamespace(query)) {
            CommandTexts.sendDocument(source, CommandTexts.document(
                    CommandTexts.render(CommandTexts.MODULE_INFO_CUSTOM),
                    List.of(CommandTexts.render(CommandTexts.MODULE_INFO_CUSTOM_DETAIL))));
            return;
        }
        Optional<ModuleCatalog.BuiltinModule> resolved = ModuleCatalog.resolve(query);
        if (resolved.isEmpty()) {
            CommandTexts.send(source, CommandTexts.render(CommandTexts.MODULE_INFO_UNKNOWN,
                    TypedArgument.text("query", query)));
            return;
        }
        ModuleCatalog.BuiltinModule module = resolved.get();
        List<Component> lines = new ArrayList<>();
        lines.add(CommandTexts.render(CommandTexts.MODULE_INFO_SOURCE,
                TypedArgument.text("source", "continuum (first-party)")));
        lines.add(CommandTexts.render(CommandTexts.MODULE_INFO_NAMESPACE));
        if (module.configKey() != null) {
            var toggles = services.config().sync().modulesWithDefaults();
            String state = toggles.getOrDefault(module.configKey(), true) ? "enabled" : "disabled";
            lines.add(CommandTexts.render(CommandTexts.MODULE_INFO_CONFIG,
                    TypedArgument.text("config", "sync.modules." + module.configKey()),
                    TypedArgument.text("state", state)));
            int position = KnownModules.DEFAULT_RESTORE_ORDER.indexOf(module.configKey());
            if (position >= 0) {
                lines.add(CommandTexts.render(CommandTexts.MODULE_INFO_ORDER,
                        TypedArgument.number("position", position + 1),
                        TypedArgument.number("total", KnownModules.DEFAULT_RESTORE_ORDER.size())));
            } else {
                lines.add(CommandTexts.render(CommandTexts.MODULE_INFO_ORDER_NONE));
            }
        } else {
            lines.add(CommandTexts.render(CommandTexts.MODULE_INFO_CONFIG_NONE));
            lines.add(CommandTexts.render(CommandTexts.MODULE_INFO_ORDER_NONE));
        }
        lines.add(CommandTexts.render(CommandTexts.MODULE_INFO_SCHEMA));
        CommandTexts.sendDocument(source, CommandTexts.document(
                CommandTexts.render(CommandTexts.MODULE_INFO_HEADER,
                        TypedArgument.text("id", module.id())),
                lines));
    }

    private static void showDiagnostics(VelocityServices services, ProxyServer proxy, CommandSource source) {
        var config = services.config();
        var localConfig = services.localConfig();

        List<Component> lines = new ArrayList<>();
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_NODE,
                TypedArgument.text("server", config.network().serverId()),
                TypedArgument.text("network", config.network().id()),
                TypedArgument.text("role", config.network().role().name().toLowerCase(Locale.ROOT))));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_BUILD,
                TypedArgument.text("continuum", BuildConstants.VERSION),
                TypedArgument.text("velocity", proxy.getVersion().getVersion())));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_DEBUG,
                TypedArgument.text("debug", config.debug() ? "on" : "off")));

        var database = config.database();
        DatabaseType type = database.type();
        String storageDetail;
        if (type == DatabaseType.MONGODB) {
            String db = database.mongodb().database();
            storageDetail = (db == null || db.isBlank()) ? "not configured" : "database " + db;
        } else {
            var mysql = database.mysql();
            if (mysql.host() == null || mysql.host().isBlank()) {
                storageDetail = "not configured";
            } else {
                storageDetail = mysql.host() + ":" + mysql.port() + "/" + mysql.database();
            }
        }
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_STORAGE,
                TypedArgument.text("type", type.name().toLowerCase(Locale.ROOT)),
                TypedArgument.text("detail", storageDetail)));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_STORAGE_PROXY));

        var messaging = config.messaging();
        if (messaging.rabbitmq() != null) {
            lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_RABBITMQ,
                    TypedArgument.text("enabled", messaging.rabbitmq().enabled() ? "enabled" : "disabled")));
        } else {
            lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_RABBITMQ,
                    TypedArgument.text("enabled", "not configured")));
        }
        if (messaging.pluginMessage() != null) {
            boolean enabled = messaging.pluginMessage().enabled();
            lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_MESSAGING,
                    TypedArgument.text("enabled", enabled ? "enabled" : "disabled"),
                    TypedArgument.text("channel", messaging.pluginMessage().channel()),
                    TypedArgument.number("max", messaging.pluginMessage().maxPayloadBytes())));
        } else {
            lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_MESSAGING,
                    TypedArgument.text("enabled", "not configured"),
                    TypedArgument.text("channel", "-"),
                    TypedArgument.number("max", 0)));
        }

        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_I18N,
                TypedArgument.text("locale", config.i18n().defaultLocale()),
                TypedArgument.text("fallback", config.i18n().fallbackLocale()),
                TypedArgument.text("client", config.i18n().useClientLocale() ? "yes" : "no")));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_AUTOSAVE,
                TypedArgument.text("enabled", config.sync().autosave().enabled() ? "enabled" : "disabled"),
                TypedArgument.number("interval", config.sync().autosave().interval().toSeconds())));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_TRANSFER,
                TypedArgument.text("require_snapshot", config.sync().transfer().requireSnapshot() ? "on" : "off"),
                TypedArgument.text("restore_before_play", config.sync().transfer().restoreBeforePlay() ? "on" : "off")));
        var recovery = config.sync().recovery();
        String restoreOrder = recovery.restoreOrder().isEmpty()
                ? "default"
                : "custom (" + recovery.restoreOrder().size() + " entries)";
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_RECOVERY,
                TypedArgument.text("enabled", recovery.enabled() ? "enabled" : "disabled"),
                TypedArgument.number("interval", recovery.interval().toSeconds()),
                TypedArgument.text("order", restoreOrder)));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_SERIALIZATION,
                TypedArgument.text("gzip", config.serialization().gzip() ? "on" : "off"),
                TypedArgument.number("max_bytes", config.serialization().maxSnapshotBytes())));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_DEBOUNCE,
                TypedArgument.number("seconds", localConfig.reconnectDebounceSeconds())));

        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_RUNTIME_HEADER));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_BACKENDS,
                TypedArgument.number("count", proxy.getAllServers().size())));
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_TRACKED,
                TypedArgument.number("online", services.registry().onlineCount()),
                TypedArgument.number("awaiting", services.registry().awaitingBackendCount())));

        boolean messagingEnabled = messaging.pluginMessage() != null && messaging.pluginMessage().enabled();
        int maxPayload = messaging.pluginMessage() != null ? messaging.pluginMessage().maxPayloadBytes() : 0;
        MessagingHealth health = services.router().health(messagingEnabled, ContinuumChannels.MAIN.getId(), maxPayload);
        String last = health.lastFrameAt()
                .map(instant -> humanDuration(Duration.between(instant, Instant.now())) + " ago")
                .orElse(CommandTexts.DIAGNOSTICS_TRAFFIC_NEVER);
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_TRAFFIC,
                TypedArgument.number("total", health.acceptedFrames()),
                TypedArgument.text("last", last)));
        if (health.framesByBackend().isEmpty()) {
            lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_TRAFFIC_EMPTY));
        } else {
            health.framesByBackend().forEach((backend, frames) ->
                    lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_TRAFFIC_ENTRY,
                            TypedArgument.text("name", backend),
                            TypedArgument.number("frames", frames))));
        }
        lines.add(CommandTexts.render(CommandTexts.DIAGNOSTICS_TOKENS));

        CommandTexts.sendDocument(source, CommandTexts.document(
                CommandTexts.render(CommandTexts.DIAGNOSTICS_HEADER), lines));
    }

    private static void reportLicense(VelocityServices services, CommandSource source, LicenseReport report) {
        VelocityMessages messages = services.messages();
        switch (report.state()) {
            case VALID -> messages.send(source, "license.active");
            case MISSING -> messages.send(source, "license.missing", TypedArgument.text("link", RELEASES_URL));
            case EXPIRED -> messages.send(source, "license.expired");
            case VERSION_MISMATCH -> messages.send(source, "license.version-mismatch",
                    TypedArgument.text("version", BuildConstants.VERSION));
            case NOT_CONFIGURED -> messages.send(source, "velocity.license.unavailable");
            case INVALID_CODE -> messages.send(source, "velocity.license.invalid-code");
            case ERROR -> messages.send(source, "velocity.license.error");
        }
    }

    private static Component appendLine(Component document, Component line) {
        return document.append(Component.newline()).append(line);
    }

    private static String hostAddress(InetSocketAddress address) {
        String host = address.getHostString();
        if (host == null) {
            // resolved-address socket with no name; print the literal address
            host = String.valueOf(address.getAddress());
        }
        return host + ":" + address.getPort();
    }

    /** Largest unit only, e.g. "42s", "5m", "3h", "2d"; sub-second rounds to 0s. */
    private static String humanDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }
}
