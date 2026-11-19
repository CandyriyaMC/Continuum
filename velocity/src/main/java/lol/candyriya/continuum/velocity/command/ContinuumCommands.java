package lol.candyriya.continuum.velocity.command;

import lol.candyriya.continuum.common.i18n.TypedArgument;
import lol.candyriya.continuum.velocity.BuildConstants;
import lol.candyriya.continuum.velocity.VelocityServices;
import lol.candyriya.continuum.velocity.VelocityMessages;
import lol.candyriya.continuum.velocity.messaging.ContinuumChannels;
import lol.candyriya.continuum.velocity.messaging.MessagingHealth;
import lol.candyriya.continuum.velocity.license.LicenseReport;

import com.velocitypowered.api.command.CommandSource;
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
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /continuum} command tree.
 *
 * <p>Everything is registered on the Cloud manager at init time - Cloud's Velocity
 * binding registers eagerly, and Velocity only accepts new commands before the
 * first connection is made. Output always goes through {@link VelocityMessages},
 * so every line is prefixed and locale-resolved.</p>
 */
public final class ContinuumCommands {

    public static final String PERMISSION_ADMIN = "continuum.admin";
    public static final String PERMISSION_LICENSE = "continuum.license";
    public static final String PERMISSION_UPDATE = "continuum.update";

    /** Where the tip jar releases will be published; used in the license-missing message. */
    private static final String RELEASES_URL = "https://github.com/CandyriyaMC/Continuum/releases";

    private static final List<HelpEntry> HELP_ENTRIES = List.of(
            new HelpEntry("/continuum", "velocity.help.desc.help"),
            new HelpEntry("/continuum version", "velocity.help.desc.version"),
            new HelpEntry("/continuum reload", "velocity.help.desc.reload"),
            new HelpEntry("/continuum status", "velocity.help.desc.status"),
            new HelpEntry("/continuum license redeem <code>", "velocity.help.desc.license-redeem"),
            new HelpEntry("/continuum license check", "velocity.help.desc.license-check"),
            new HelpEntry("/continuum license reset", "velocity.help.desc.license-reset"),
            new HelpEntry("/continuum update", "velocity.help.desc.update"));

    private record HelpEntry(String command, String descriptionKey) {
    }

    private ContinuumCommands() {
    }

    public static void register(VelocityCommandManager<CommandSource> manager,
                                VelocityServices services,
                                ProxyServer proxy,
                                Logger logger) {
        VelocityMessages messages = services.messages();
        registerExceptionHandlers(manager, services, logger);

        // bare /continuum shows the help document
        manager.command(manager.commandBuilder("continuum")
                .handler(context -> showHelp(services, context.sender())));

        manager.command(manager.commandBuilder("continuum")
                .literal("help")
                .handler(context -> showHelp(services, context.sender())));

        manager.command(manager.commandBuilder("continuum")
                .literal("version")
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
                .permission(PERMISSION_ADMIN)
                .handler(context -> showStatus(services, proxy, context.sender())));

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
                .handler(context -> messages.send(context.sender(), "velocity.update.unavailable",
                        TypedArgument.text("version", BuildConstants.VERSION))));
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
            document = appendLine(document, messages.render(locale, "velocity.help.entry",
                    TypedArgument.text("command", entry.command()),
                    TypedArgument.component("description", messages.render(locale, entry.descriptionKey()))));
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
}
