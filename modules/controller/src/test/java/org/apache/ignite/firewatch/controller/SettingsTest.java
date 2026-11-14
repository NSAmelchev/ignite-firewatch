package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Settings}. */
class SettingsTest {
    /** */
    @TempDir Path tmp;

    /** */
    private Scenarios scenarios() {
        return new Scenarios(List.of(Scenarios.Info.of("smoke", 1, 2), Scenarios.Info.of("putget", 1, 2)));
    }

    /** */
    @Test void scenarioListAcceptsSeveralNames() throws Exception {
        Scenarios sc = scenarios();
        assertEquals("smoke,putget", Settings.validate(Settings.AUTORUN_SCENARIO, " smoke, putget ,smoke", sc));
        assertEquals(List.of("smoke", "putget"), Settings.scenarioList("smoke,putget"));
        assertThrows(IllegalArgumentException.class,
            () -> Settings.validate(Settings.AUTORUN_SCENARIO, "smoke,nope", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.AUTORUN_SCENARIO, " , ", sc));
    }

    /** The settings of a plugin: plugin.NAME.FIELD, empty for the default. */
    @Test void theSettingsOfAPluginAreValidated() {
        Scenarios sc = scenarios();
        assertEquals("ignite-spring-data-ext-3.1.0",
            Settings.validate("plugin.spring-data.release", " ignite-spring-data-ext-3.1.0 ", sc));
        assertEquals("", Settings.validate("plugin.spring-data.release", "", sc), "no release: it does not run");
        assertEquals("2.17.0", Settings.validate("plugin.spring-data.ignite", "2.17.0", sc));
        assertEquals("keep", Settings.validate("plugin.spring-data.mode", "keep", sc));
        assertEquals("3", Settings.validate("plugin.spring-data.keepDays", "3", sc));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Settings.validate("plugin.spring-data.repo",
            "https://github.com/apache/ignite-extensions.git", sc)).getMessage().contains("configuration"),
            "the repository of a plugin is the configuration's");
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("plugin.spring-data.branch", "main", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("plugin.spring-data.color", "red", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("plugin.Spring.release", "x", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("plugin.spring-data.mode", "on", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("plugin.spring-data.keepDays", "0", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("plugin.spring-data.release", "a b", sc));
    }

    /** */
    @Test void otherSettingsAreValidated() throws Exception {
        Scenarios sc = scenarios();
        assertEquals("IGNITE=https://a/browse/\nGG=http://b/",
            Settings.validate(Settings.TICKET_LINKS, " IGNITE=https://a/browse/ \n\n GG=http://b/\n", sc));
        assertEquals("", Settings.validate(Settings.TICKET_LINKS, "  ", sc), "no links at all is a choice");
        assertEquals("GG=http://b/ https://git.lan/repo/pull-requests/",
            Settings.validate(Settings.TICKET_LINKS, "GG=http://b/ https://git.lan/repo/pull-requests/", sc),
            "a space can have its own repository");
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.TICKET_LINKS, "GG=http://b/ repo", sc));
        assertThrows(IllegalArgumentException.class,
            () -> Settings.validate(Settings.TICKET_LINKS, "GG=http://b/ http://c/ http://d/", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.TICKET_LINKS, "ignite=https://a/", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.TICKET_LINKS, "IGNITE https://a/", sc));
        assertThrows(IllegalArgumentException.class,
            () -> Settings.validate(Settings.TICKET_LINKS, "IGNITE=javascript:alert(1)", sc));
        assertEquals("true", Settings.validate(Settings.AUTORUN_ENABLED, "true", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.AUTORUN_ENABLED, "yes", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.AUTORUN_MODE, "bisect", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.WATCH_POLL_SEC, "5", sc));
        assertEquals("60", Settings.validate(Settings.WATCH_POLL_SEC, "60", sc));
        assertEquals("0", Settings.validate(Settings.RETENTION_KEEP_COMPARISONS, "0", sc));
        assertEquals("keep", Settings.validate("calibration.mode", "keep", sc));
        assertEquals("off", Settings.validate("master.mode", "off", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("calibration.mode", "days", sc));
        assertEquals("7", Settings.validate("master.keepDays", "7", sc));
        assertEquals("1", Settings.validate(Settings.AUTORUN_KEEP_DAYS, "1", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("calibration.keepDays", "0", sc));
        assertThrows(IllegalArgumentException.class,
            () -> Settings.validate("master.at", "03:00", sc), "the time of a run is gone");
        assertEquals("", Settings.validate("calibration.scenarios", "", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("calibration.scenarios", "nope", sc));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> Settings.validate(Settings.REPO_URL, "https://github.com/apache/ignite.git", sc)).getMessage()
            .contains("configuration"), "the repository is the configuration's");
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.REPO_BRANCH, "master", sc));
        Config.checkRepoUrl("repo.url", "https://github.com/apache/ignite.git");
        Config.checkRepoUrl("repo.url", "/git/ignite.bundle");
        assertThrows(IllegalArgumentException.class, () -> Config.checkRepoUrl("repo.url", "ext::sh -c id"));
        Config.checkBranch("branch", "ignite-2.18");
        assertThrows(IllegalArgumentException.class, () -> Config.checkBranch("branch", "--upload-pack=x"));
        assertThrows(IllegalArgumentException.class,
            () -> Settings.validate(Settings.RETENTION_KEEP_BISECTS, "-1", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate("no.such", "x", sc));
    }

    /** */
    @Test void firstScenarioFallsBackToTheMainTracker() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            assertEquals("putget-thin-t16", Settings.firstScenario(db));
            db.setSetting(Settings.AUTORUN_SCENARIO, "putget,putget-thin");
            assertEquals("putget", Settings.firstScenario(db));
        }
    }
}
