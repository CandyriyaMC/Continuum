package lol.candyriya.continuum.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ConfigManager.reload/current. The paths that matter:
 * missing file, broken file, self-healing, fail-closed on a dead config.
 */
class ConfigManagerTest {

    @TempDir
    Path temp;

    private ConfigManager manager() {
        return new ConfigManager(temp, LoggerFactory.getLogger("test"));
    }

    @Test
    void missingFileCreatesDefault() {
        var manager = manager();
        assertTrue(manager.reload());
        assertTrue(Files.exists(temp.resolve("config.yml")));
        assertEquals(PluginConfig.defaults().prefix(), manager.current().prefix());
        assertTrue(manager.current().welcome().enabled());
    }

    @Test
    void editedFileIsPickedUp() throws Exception {
        var manager = manager();
        assertTrue(manager.reload());
        Files.writeString(temp.resolve("config.yml"), "prefix: \"[X] \"\ndebug: true\nwelcome:\n  enabled: false\n  message: \"hi\"\n");
        assertTrue(manager.reload());
        assertEquals("[X] ", manager.current().prefix());
        assertTrue(manager.current().debug());
        assertFalse(manager.current().welcome().enabled());
    }

    @Test
    void brokenFileKeepsPreviousConfig() throws Exception {
        var manager = manager();
        assertTrue(manager.reload());
        Files.writeString(temp.resolve("config.yml"), "prefix: [unclosed\n  broken: : :\n");
        assertFalse(manager.reload());
        // the broken file must not shadow the last good config
        assertEquals(PluginConfig.defaults().prefix(), manager.current().prefix());
    }

    @Test
    void missingKeysHealThemselves() throws Exception {
        var manager = manager();
        assertTrue(manager.reload());
        Files.writeString(temp.resolve("config.yml"), "prefix: \"[Y] \"\n");
        assertTrue(manager.reload());
        assertEquals("[Y] ", manager.current().prefix());
        assertEquals(PluginConfig.defaults().welcome(), manager.current().welcome());
        // and the file itself got patched: the welcome section is back
        assertTrue(Files.readString(temp.resolve("config.yml")).contains("welcome:"));
    }

    @Test
    void legacyConfigWithoutVersionGetsStamped() throws Exception {
        var manager = manager();
        assertTrue(manager.reload());
        // a config from before versioning existed: no config-version key at all
        Files.writeString(temp.resolve("config.yml"), "prefix: \"[Z] \"\ndebug: false\nwelcome:\n  enabled: true\n  message: \"hi\"\n");
        assertTrue(manager.reload());
        // it got the current version stamped without losing the user's values
        assertTrue(Files.readString(temp.resolve("config.yml"))
                .contains("config-version: " + ConfigMigrations.CURRENT_VERSION));
        assertEquals("[Z] ", manager.current().prefix());
    }

    @Test
    void concurrentReloadsStayConsistent() throws Exception {
        var manager = manager();
        assertTrue(manager.reload());
        // 32 concurrent reloads: nobody tears, nobody loses keys
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 32; i++) {
                futures.add(pool.submit(manager::reload));
            }
            for (var f : futures) {
                assertTrue(f.get(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(PluginConfig.defaults().prefix(), manager.current().prefix());
    }
}
