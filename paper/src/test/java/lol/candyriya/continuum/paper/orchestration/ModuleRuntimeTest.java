package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.common.config.ConfigLoader;
import lol.candyriya.continuum.common.config.ContinuumConfig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRuntimeTest {

    private static ContinuumConfig configWithModules(String modulesBlock, String restoreOrder) {
        String template = """
                debug: false
                network:
                  id: test-network
                  server-id: test-node
                  role: backend
                database:
                  type: mysql
                  mysql:
                    host: localhost
                    port: 3306
                    database: continuum
                    username: continuum
                    password: ""
                    pool:
                      maximum-pool-size: 10
                      minimum-idle: 2
                      connection-timeout: 5s
                      max-lifetime: 30m
                messaging:
                  rabbitmq:
                    enabled: true
                    uri: "amqp://localhost:5672/"
                    exchange: continuum.events
                    retry:
                      max-attempts: 5
                      initial-backoff: 200ms
                      max-backoff: 10s
                  plugin-message:
                    enabled: true
                    channel: continuum:main
                    max-payload-bytes: 65536
                sync:
                  autosave:
                    enabled: true
                    interval: 60s
                  modules:
@MODULES@
                  transfer:
                    require-snapshot: true
                    restore-before-play: true
                  recovery:
                    enabled: true
                    interval: 30s
                    restore-order: [@RESTORE_ORDER@]
                i18n:
                  default-locale: en_US
                  fallback-locale: en_US
                  use-client-locale: true
                serialization:
                  gzip: false
                  max-snapshot-bytes: 8388608
                """;
        String yaml = template
                .replace("@MODULES@", indent(modulesBlock.stripTrailing(), 20))
                .replace("@RESTORE_ORDER@", restoreOrder);
        return ConfigLoader.load(yaml);
    }

    /** Indents every non-blank line by {@code spaces}. */
    private static String indent(String block, int spaces) {
        String prefix = " ".repeat(spaces);
        StringBuilder out = new StringBuilder(block.length() + spaces * 16);
        for (String line : block.lines().toList()) {
            if (!line.isBlank()) {
                out.append(prefix).append(line.stripTrailing()).append('\n');
            }
        }
        return out.toString();
    }

    private static ContinuumConfig defaultConfig() {
        return configWithModules("""
                        inventory: true
                        ender-chest: true
                        vitals: true
                        experience: true
                        effects: true
                        attributes: true
                        location: true
                        gamemode: true
                        advancements: true
                        statistics: true
                        recipes: true
                        pdc: true
                        custom: true
                        """, "");
    }

    @Test
    void defaultPlanOrderMatchesTheDocumentedBuiltInOrder() {
        ModuleRuntime runtime = ModuleRuntime.builtins(defaultConfig());

        List<String> order = runtime.planOrder().stream().map(ModuleRuntime.RegisteredModule::id).toList();

        assertEquals(List.of(
                BuiltinModules.ATTRIBUTES,
                BuiltinModules.GAMEMODE,
                BuiltinModules.INVENTORY,
                BuiltinModules.ENDERCHEST,
                BuiltinModules.VITALS,
                BuiltinModules.EFFECTS,
                BuiltinModules.EXPERIENCE,
                BuiltinModules.ADVANCEMENTS,
                BuiltinModules.RECIPES,
                BuiltinModules.STATISTICS,
                BuiltinModules.LOCATION), order);
        // location LAST, always
        assertEquals(BuiltinModules.LOCATION, order.getLast());
    }

    @Test
    void vitalsRestoresAfterItsAttributesDependency() {
        ModuleRuntime runtime = ModuleRuntime.builtins(defaultConfig());

        List<String> order = runtime.planOrder().stream().map(ModuleRuntime.RegisteredModule::id).toList();

        assertTrue(order.indexOf(BuiltinModules.ATTRIBUTES) < order.indexOf(BuiltinModules.VITALS));
    }

    @Test
    void toggledOffModulesAreExcludedFromThePlan() {
        ContinuumConfig config = configWithModules("""
                        inventory: false
                        ender-chest: false
                        vitals: true
                        experience: true
                        effects: true
                        attributes: true
                        location: true
                        gamemode: true
                        advancements: true
                        statistics: true
                        recipes: true
                        pdc: true
                        custom: true
                        """, "");

        ModuleRuntime runtime = ModuleRuntime.builtins(config);

        List<String> order = runtime.planOrder().stream().map(ModuleRuntime.RegisteredModule::id).toList();
        assertFalse(order.contains(BuiltinModules.INVENTORY));
        assertFalse(order.contains(BuiltinModules.ENDERCHEST));
        assertTrue(order.contains(BuiltinModules.VITALS));
        assertTrue(order.contains(BuiltinModules.ATTRIBUTES));
    }

    @Test
    void enderChestToggleUsesTheKebabCaseConfigKey() {
        ContinuumConfig config = configWithModules("""
                        inventory: true
                        ender-chest: false
                        vitals: true
                        experience: true
                        effects: true
                        attributes: true
                        location: true
                        gamemode: true
                        advancements: true
                        statistics: true
                        recipes: true
                        pdc: true
                        custom: true
                        """, "");

        ModuleRuntime runtime = ModuleRuntime.builtins(config);

        assertFalse(runtime.isEnabled(BuiltinModules.ENDERCHEST));
        assertTrue(runtime.isEnabled(BuiltinModules.INVENTORY));
    }

    @Test
    void configuredRestoreOrderOverridesDefaultPlacementButNotDependencies() {
        ContinuumConfig config = configWithModules("""
                        inventory: true
                        ender-chest: true
                        vitals: true
                        experience: true
                        effects: true
                        attributes: true
                        location: true
                        gamemode: true
                        advancements: true
                        statistics: true
                        recipes: true
                        pdc: true
                        custom: true
                        """, "recipes, attributes");

        ModuleRuntime runtime = ModuleRuntime.builtins(config);

        List<String> order = runtime.planOrder().stream().map(ModuleRuntime.RegisteredModule::id).toList();
        assertEquals(BuiltinModules.RECIPES, order.getFirst());
        assertTrue(order.indexOf(BuiltinModules.ATTRIBUTES) < order.indexOf(BuiltinModules.VITALS),
                "dependency order wins over the configured list");
    }

    @Test
    void loadBearingModulesAreRequiredAndEveryoneElseIsIsolated() {
        ModuleRuntime runtime = ModuleRuntime.builtins(defaultConfig());

        assertEquals(FailurePolicy.ABORT, runtime.byId(BuiltinModules.ATTRIBUTES).failurePolicy());
        assertEquals(FailurePolicy.ABORT, runtime.byId(BuiltinModules.INVENTORY).failurePolicy());
        assertEquals(FailurePolicy.ABORT, runtime.byId(BuiltinModules.VITALS).failurePolicy());
        assertEquals(FailurePolicy.SKIP, runtime.byId(BuiltinModules.RECIPES).failurePolicy());
        assertEquals(FailurePolicy.SKIP, runtime.byId(BuiltinModules.LOCATION).failurePolicy());
        assertEquals(FailurePolicy.SKIP, runtime.byId(BuiltinModules.EFFECTS).failurePolicy());
    }

    @Test
    void pdcHasNoFirstPartyModuleSoTheConfigToggleIsInertButAccepted() {
        ContinuumConfig config = defaultConfig();
        ModuleRuntime runtime = ModuleRuntime.builtins(config);

        // accepted at load, no module to enable — documented behaviour
        assertFalse(runtime.isEnabled(BuiltinModules.PDC));
        assertEquals(11, runtime.all().size());
    }
}
