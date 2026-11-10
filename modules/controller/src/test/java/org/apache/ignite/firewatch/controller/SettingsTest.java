package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("https://github.com/apache/ignite.git",
            Settings.validate(Settings.REPO_URL, "https://github.com/apache/ignite.git", sc));
        assertThrows(IllegalArgumentException.class, () -> Settings.validate(Settings.REPO_URL, "ext::sh -c id", sc));
        assertEquals("ignite-2.18", Settings.validate(Settings.REPO_BRANCH, "ignite-2.18", sc));
        assertThrows(IllegalArgumentException.class,
            () -> Settings.validate(Settings.REPO_BRANCH, "--upload-pack=x", sc));
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
