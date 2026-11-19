package lol.candyriya.continuum.velocity.command;

import lol.candyriya.continuum.common.config.I18nConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.common.i18n.TypedArgument;
import lol.candyriya.continuum.velocity.BuildConstants;
import lol.candyriya.continuum.velocity.VelocityCatalogs;
import lol.candyriya.continuum.velocity.VelocityServices;
import lol.candyriya.continuum.velocity.VelocityText;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;

import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end-ish coverage for the {@code /continuum} tree: the real Cloud
 * manager registers the commands (with a fake Velocity command manager on the
 * far side), the real services graph backs the handlers, and a fake command
 * source captures what would reach the operator.
 */
class ContinuumCommandsTest {

    private static final UUID STEVE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ALEX_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @TempDir
    Path dataDir;

    private ProxyServer proxy;
    private VelocityServices services;
    private VelocityCommandManager<CommandSource> manager;
    private FakeCommandSource admin;
    private FakeCommandSource readonlyUser;
    private FakeCommandSource noPermissions;
    private Player steve;
    private Player alex;
    private RegisteredServer survival;

    private final Logger logger = LoggerFactory.getLogger(ContinuumCommandsTest.class);

    @BeforeEach
    void setUp() {
        survival = TestProxies.registeredServer("survival-1", "127.0.0.1", 25565);
        RegisteredServer lobby = TestProxies.registeredServer("lobby", "127.0.0.1", 25566);
        steve = TestProxies.player("Steve", STEVE_ID);
        alex = TestProxies.player("Alex", ALEX_ID);
        admin = FakeCommandSource.admin();
        readonlyUser = FakeCommandSource.readonlyOnly();
        noPermissions = FakeCommandSource.noPermissions();

        Map<String, List<String>> registrations = TestProxies.newRegistrationRecorder();
        proxy = TestProxies.proxyServer(List.of(survival, lobby), List.of(steve, alex), registrations, true);

        services = VelocityServices.create(proxy, TestProxies.pluginContainer(), logger, dataDir);
        manager = new VelocityCommandManager<>(TestProxies.pluginContainer(), proxy,
                ExecutionCoordinator.simpleCoordinator(), SenderMapper.identity());
        ContinuumCommands.register(manager, services, proxy, logger);

        // Steve is on survival; Alex is online but has no placement yet
        services.registry().playerConnected(steve, survival);
    }

    @AfterEach
    void tearDown() {
        services.close();
    }

    private String execute(CommandSource source, String commandLine) {
        // Cloud forwards the original exception to the future even after its
        // handler ran; the observer of a real proxy catches it upstream.
        manager.commandExecutor().executeCommand(source, commandLine)
                .exceptionally(throwable -> null)
                .join();
        return ((FakeCommandSource) source).allText();
    }

    // ---- tree registration ----

    @Test
    void everyCommandOnTheTreeHasAPermissionAndTheRootIsRegistered() {
        assertTrue(manager.commands().stream().allMatch(command -> command.commandPermission() != null
                && !command.commandPermission().isEmpty()
                && !command.commandPermission().permissionString().isBlank()));
        assertEquals(List.of("continuum"), manager.rootCommands().stream().distinct().toList());
        // bare + help + version + reload + status + 6 player + 2 backend + 2 module
        // + diagnostics + 3 license + update + update check + update install
        assertEquals(22, manager.commands().size());
    }

    @Test
    void adminNodeAlonePassesReadOnlyCommandsToo() {
        FakeCommandSource adminOnly = FakeCommandSource.adminOnly();
        String output = execute(adminOnly, "continuum status");
        assertTrue(output.contains("Continuum status"), output);
    }

    @Test
    void helpListsTheWholeTree() {
        String output = execute(admin, "continuum help");
        for (String command : List.of("/continuum status",
                "/continuum player <player> info",
                "/continuum player <player> save",
                "/continuum player <player> reload",
                "/continuum player <player> snapshot",
                "/continuum player <player> restore",
                "/continuum player <player> history",
                "/continuum backend list",
                "/continuum backend status <server>",
                "/continuum module list",
                "/continuum module info <module>",
                "/continuum diagnostics",
                "/continuum reload",
                "/continuum version")) {
            assertTrue(output.contains(command), "help is missing: " + command + "\n" + output);
        }
    }

    @Test
    void readOnlyNodeOpensExactlyTheReadOnlySurface() {
        assertTrue(execute(readonlyUser, "continuum status").contains("Continuum status"));
        assertTrue(execute(readonlyUser, "continuum version").contains(BuildConstants.VERSION));
        assertTrue(execute(readonlyUser, "continuum help").contains("/continuum help"));
        assertTrue(execute(readonlyUser, "continuum backend list").contains("survival-1"));
        assertTrue(execute(readonlyUser, "continuum backend status survival-1").contains("survival-1"));
        assertTrue(execute(readonlyUser, "continuum module list").contains("continuum.inventory"));
        assertTrue(execute(readonlyUser, "continuum module info inventory").contains("continuum.inventory"));
        assertTrue(execute(readonlyUser, "continuum player Steve info").contains("survival-1"));
        assertTrue(execute(readonlyUser, "continuum player Steve history").contains("history is not available in this build"));
    }

    @Test
    void readOnlyNodeIsDeniedOnSideEffectingCommands() {
        assertTrue(execute(readonlyUser, "continuum reload").contains("do not have permission"));
        assertTrue(execute(readonlyUser, "continuum diagnostics").contains("do not have permission"));
        assertTrue(execute(readonlyUser, "continuum player Steve save").contains("do not have permission"));
        assertTrue(execute(readonlyUser, "continuum player Steve reload").contains("do not have permission"));
        assertTrue(execute(readonlyUser, "continuum player Steve snapshot").contains("do not have permission"));
        assertTrue(execute(readonlyUser, "continuum player Steve restore").contains("do not have permission"));
    }

    @Test
    void noPermissionsAtAllDeniesEverythingThatIsNotPublic() {
        assertTrue(execute(noPermissions, "continuum status").contains("do not have permission"));
        assertTrue(execute(noPermissions, "continuum help").contains("do not have permission"));
        assertTrue(execute(noPermissions, "continuum license check").contains("do not have permission"));
        assertTrue(execute(noPermissions, "continuum update").contains("do not have permission"));
    }

    @Test
    void updateAndLicenseKeepTheirOwnNodes() {
        FakeCommandSource licenseOnly = new FakeCommandSource(Map.of("continuum.license", Tristate.TRUE));
        assertTrue(execute(licenseOnly, "continuum license check").contains("License"));
        assertTrue(execute(licenseOnly, "continuum update").contains("do not have permission"));
    }

    // ---- player inspection ----

    @Test
    void playerInfoRendersTrackedPlacement() {
        String output = execute(admin, "continuum player Steve info");
        assertTrue(output.contains("Steve"), output);
        assertTrue(output.contains(STEVE_ID.toString()), output);
        assertTrue(output.contains("Backend:"), output);
        assertTrue(output.contains("survival-1"), output);
        assertTrue(output.contains("Placement:"), output);
        assertTrue(output.contains("connected"), output);
        assertTrue(output.contains("Reconnect transitions"), output);
        assertTrue(output.contains("fencing token: unavailable"), output);
        assertTrue(output.contains("last snapshot: unavailable"), output);
        assertTrue(output.contains("Online mode: online"), output);
    }

    @Test
    void playerInfoForUntrackedButOnlinePlayerSaysSo() {
        String output = execute(admin, "continuum player Alex info");
        assertTrue(output.contains("No session data"), output);
        assertTrue(output.contains("Alex"), output);
    }

    @Test
    void playerInfoRendersAwaitingStateWithReason() {
        services.registry().markAwaitingBackend(STEVE_ID, "survival-1", "test kick from server");

        String output = execute(admin, "continuum player Steve info");
        assertTrue(output.contains("awaiting a backend outcome"), output);
        assertTrue(output.contains("Awaiting reason:"), output);
        assertTrue(output.contains("test kick from server"), output);
        assertTrue(output.contains("awaiting backend outcome"), output);
    }

    @Test
    void playerSaveIsRefusedWithTheHonestReason() {
        String output = execute(admin, "continuum player Steve save");
        assertTrue(output.contains("save is not available in this build"), output);
        assertTrue(output.contains("Nothing was changed"), output);
    }

    @Test
    void playerSnapshotIsRefusedWithTheHonestReason() {
        String output = execute(admin, "continuum player Steve snapshot");
        assertTrue(output.contains("snapshot is not available in this build"), output);
    }

    @Test
    void playerReloadIsRefusedWithTheHonestReason() {
        String output = execute(admin, "continuum player Steve reload");
        assertTrue(output.contains("reload is not available in this build"), output);
    }

    @Test
    void playerRestoreIsRefusedWithTheHonestReason() {
        String output = execute(admin, "continuum player Steve restore");
        assertTrue(output.contains("restore is not available in this build"), output);
    }

    @Test
    void playerHistoryIsRefusedWithTheHonestReason() {
        String output = execute(admin, "continuum player Steve history");
        assertTrue(output.contains("history is not available in this build"), output);
    }

    @Test
    void playerCommandsRejectUnknownPlayersAtTheParser() {
        String output = execute(admin, "continuum player NotARealPlayerZZZ info");
        assertTrue(output.contains("NotARealPlayerZZZ"), output);
        assertTrue(output.contains("not a valid player"), output);
    }

    // ---- backends ----

    @Test
    void backendListShowsRegisteredBackendsAndConnections() {
        String output = execute(admin, "continuum backend list");
        assertTrue(output.contains("Registered backends (2)"), output);
        assertTrue(output.contains("survival-1"), output);
        assertTrue(output.contains("lobby"), output);
        assertTrue(output.contains("1 connection(s)"), output);
    }

    @Test
    void backendStatusShowsConnectionAndFrameCounts() {
        String output = execute(admin, "continuum backend status survival-1");
        assertTrue(output.contains("survival-1"), output);
        assertTrue(output.contains("Tracked connections:"), output);
        assertTrue(output.contains("Plugin-message frames received:"), output);
    }

    @Test
    void backendStatusRejectsUnregisteredServersAtTheParser() {
        String output = execute(admin, "continuum backend status nosuchbackend");
        assertTrue(output.contains("nosuchbackend"), output);
        assertTrue(output.contains("not a valid server"), output);
    }

    @Test
    void backendListOnAnEmptyProxySaysSo() throws Exception {
        Path emptyDir = Files.createTempDirectory(dataDir, "continuum-empty-");
        Map<String, List<String>> registrations = TestProxies.newRegistrationRecorder();
        ProxyServer emptyProxy = TestProxies.proxyServer(List.of(), List.of(), registrations, true);
        try (VelocityServices emptyServices =
                     VelocityServices.create(emptyProxy, TestProxies.pluginContainer(), logger, emptyDir)) {
            var emptyManager = new VelocityCommandManager<CommandSource>(TestProxies.pluginContainer(), emptyProxy,
                    ExecutionCoordinator.simpleCoordinator(), SenderMapper.identity());
            ContinuumCommands.register(emptyManager, emptyServices, emptyProxy, logger);
            FakeCommandSource source = FakeCommandSource.admin();
            emptyManager.commandExecutor().executeCommand(source, "continuum backend list")
                    .exceptionally(throwable -> null)
                    .join();
            assertTrue(source.allText().contains("No backends registered"), source.allText());
        }
    }

    // ---- modules ----

    @Test
    void moduleListShowsAllBuiltinModulesAndConfigKeys() {
        String output = execute(admin, "continuum module list");
        assertTrue(output.contains("Built-in modules (14)"), output);
        assertTrue(output.contains("continuum.inventory"), output);
        assertTrue(output.contains("sync.modules.inventory"), output);
        assertTrue(output.contains("continuum.metadata"), output);
        assertTrue(output.contains("continuum.permissions"), output);
        assertTrue(output.contains("enabled"), output);
    }

    @Test
    void moduleInfoResolvesDottedIdAndShortKey() {
        String dotted = execute(admin, "continuum module info continuum.inventory");
        String shortKey = execute(admin, "continuum module info inventory");
        for (String output : List.of(dotted, shortKey)) {
            assertTrue(output.contains("continuum.inventory"), output);
            assertTrue(output.contains("sync.modules.inventory"), output);
            assertTrue(output.contains("effective:"), output);
            assertTrue(output.contains("Default restore order:"), output);
        }
    }

    @Test
    void moduleInfoShowsConfiglessModulesHonestly() {
        String output = execute(admin, "continuum module info metadata");
        assertTrue(output.contains("continuum.metadata"), output);
        assertTrue(output.contains("not gated by sync.modules"), output);
    }

    @Test
    void moduleInfoRejectsUnknownModules() {
        String output = execute(admin, "continuum module info continuum.unknown.thing");
        assertTrue(output.contains("Unknown module"), output);
    }

    @Test
    void moduleInfoExplainsTheCustomNamespace() {
        String output = execute(admin, "continuum module info custom");
        assertTrue(output.contains("custom.*"), output);
        assertFalse(output.contains("Unknown module"), output);
    }

    @Test
    void moduleInfoSuggestsBuiltinIds() {
        var suggestions = manager.suggestionFactory()
                .suggestImmediately(admin, "continuum module info continuum.inve");
        List<String> values = suggestions.list().stream()
                .map(org.incendo.cloud.suggestion.Suggestion::suggestion)
                .toList();
        assertTrue(values.stream().anyMatch(value -> value.contains("continuum.inventory")),
                "suggestions: " + values);
    }

    // ---- diagnostics ----

    @Test
    void diagnosticsRendersConfigAndRuntimeSnapshot() {
        String output = execute(admin, "continuum diagnostics");
        assertTrue(output.contains("Continuum diagnostics"), output);
        assertTrue(output.contains("Node:"), output);
        assertTrue(output.contains("Build:"), output);
        assertTrue(output.contains("storage:"), output);
        assertTrue(output.contains("mysql"), output);
        assertTrue(output.contains("localhost"), output);
        assertFalse(output.contains("continuum password"), output);
        assertFalse(output.contains("username"), output);
        assertTrue(output.contains("plugin messaging:"), output);
        assertTrue(output.contains("autosave:"), output);
        assertTrue(output.contains("recovery:"), output);
        assertTrue(output.contains("players tracked:"), output);
        assertTrue(output.contains("session tokens:"), output);
    }

    @Test
    void diagnosticsIsAdminOnly() {
        assertTrue(execute(readonlyUser, "continuum diagnostics").contains("do not have permission"));
    }

    // ---- lifecycle commands stay intact ----

    @Test
    void reloadStillWorksWithTheAdminNode() {
        String output = execute(admin, "continuum reload");
        assertTrue(output.contains("Configuration reloaded"), output);
    }

    @Test
    void statusStillReportsBackendsAndMessaging() {
        String output = execute(admin, "continuum status");
        assertTrue(output.contains("Continuum status"), output);
        assertTrue(output.contains("survival-1"), output);
        assertTrue(output.contains("connection(s)"), output);
    }

    @Test
    void versionPrintsTheRunningBuild() {
        String output = execute(admin, "continuum version");
        assertTrue(output.contains(BuildConstants.VERSION), output);
    }

    @Test
    void noExceptionsEscapeIntoTheConsoleForGarbageInput() {
        // empty argument, garbage tokens - the tree must answer with a usage error, never a stack trace
        String output = execute(admin, "continuum player");
        assertFalse(output.isEmpty(), output);
        String trailing = execute(admin, "continuum backend status");
        assertFalse(trailing.isEmpty(), trailing);
    }

    @Test
    void prefixIsPresentOnCommandOutput() {
        String output = execute(admin, "continuum backend list");
        Component first = admin.sent().get(0);
        assertTrue(VelocityText.plain(first).contains("[Continuum]"), VelocityText.plain(first));
    }

    // ---- MiniMessage sanity for the catalog-free path ----

    @Test
    void inCodeMessagesSurviveTheMiniMessagePipeline() {
        Translator translator = VelocityCatalogs.build(Thread.currentThread().getContextClassLoader(),
                new I18nConfig("en_US", "en_US", true), null);
        // the bracket CommandTexts prints must match the catalog prefix
        String prefix = VelocityText.plain(translator.render("en_US", "velocity.prefix"));
        assertTrue(prefix.contains("[Continuum]"), prefix);
        // unparsed placeholders cannot smuggle markup into the output
        Component rendered = CommandTexts.render("<yellow><input></yellow>",
                TypedArgument.text("input", "<red>not markup</red>"));
        assertTrue(VelocityText.plain(rendered).contains("<red>not markup</red>"),
                VelocityText.plain(rendered));
    }
}
