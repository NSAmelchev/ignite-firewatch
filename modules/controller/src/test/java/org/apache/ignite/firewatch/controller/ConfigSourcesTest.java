package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The configuration as the UI shows it, and the repositories it fixes: those of Ignite and of the plugins. */
class ConfigSourcesTest {
    /** */
    @TempDir Path tmp;

    /** */
    @AfterEach void clear() {
        System.clearProperty("ignite.firewatch.port");
        System.clearProperty("ignite.firewatch.plugin.spring-data.repo");
    }

    /** */
    private static Map<String, String> entry(Config c, String key) {
        return c.describe().stream().filter(e -> key.equals(e.get("key"))).findFirst().orElseThrow();
    }

    /** Every key says where its value comes from; the file of the configuration comes first. */
    @Test void everyKeySaysWhereItComesFrom() {
        System.setProperty("ignite.firewatch.port", "8123");
        Config c = Config.fromEnv();
        assertEquals(8123, c.port);
        assertEquals(Map.of("key", "port", "value", "8123", "source", Config.PROPERTY), entry(c, "port"));
        assertEquals(Config.DEFAULT, entry(c, "maven.heap.mb").get("source"));
        assertEquals("1536", entry(c, "maven.heap.mb").get("value"));
        assertEquals("config", c.describe().get(0).get("key"));
        assertTrue(c.isSet("port"));
        assertFalse(c.isSet("maven.heap.mb"));
    }

    /** The repository of a plugin is the configuration's when it is set there, else the jar's. */
    @Test void theRepositoryOfAPluginIsTheConfigurationsElseTheJars() throws Exception {
        Path jar = Files.writeString(tmp.resolve("firewatch-spring-data.jar"), "a jar the fake harness reads");
        Path list = tmp.resolve("harness.sh");
        Files.writeString(list, String.join("\n", "#!/bin/bash",
            "echo '{\"plugin\": {\"name\": \"spring-data\", \"title\": \"Spring Data\", \"module\": \"modules/x\", "
                + "\"repo\": \"https://github.com/apache/ignite-extensions.git\", \"branch\": \"master\"}, "
                + "\"scenarios\": [{\"name\": \"calcite-point\"}]}'", ""));
        Files.setPosixFilePermissions(list, PosixFilePermissions.fromString("rwxr-xr-x"));

        Config cfg = new Config();
        cfg.cacheDir = tmp.resolve("cache");
        cfg.harnessCommand = List.of(list.toString());
        cfg.plugins = List.of(jar.toString());

        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Plugins.Plugin byJar = Plugins.load(cfg, db).get("spring-data");
            assertEquals("https://github.com/apache/ignite-extensions.git", byJar.repo);
            assertEquals("master", byJar.branch);
            assertFalse(byJar.repoFromConfig);

            System.setProperty("ignite.firewatch.plugin.spring-data.repo", "/git/ignite-extensions.git");
            Plugins.Plugin byCfg = Plugins.load(cfg, db).get("spring-data");
            assertEquals("/git/ignite-extensions.git", byCfg.repo);
            assertEquals("/git/ignite-extensions.git", byCfg.mirror.url(), "the mirror follows it");
            assertTrue(byCfg.repoFromConfig);
            assertEquals(Config.PROPERTY, entry(cfg, "plugin.spring-data.repo").get("source"), "the UI shows it too");
        }
    }

    /** The settings no longer seed the repository: the database cannot override the configuration. */
    @Test void theSettingsHoldNoRepository() {
        Map<String, String> d = Settings.defaults(new Config());
        assertFalse(d.containsKey(Settings.REPO_URL));
        assertFalse(d.containsKey(Settings.REPO_BRANCH));
    }
}
