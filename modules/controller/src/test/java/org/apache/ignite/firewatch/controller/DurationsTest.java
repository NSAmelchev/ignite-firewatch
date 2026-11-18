package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests of {@link Durations}. */
class DurationsTest {
    /** */
    @TempDir Path tmp;

    /** Start of the history of a test. */
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    /** */
    private int seq;

    /**
     * A finished ABBA job: {@code buildSec} before its first phase, then {@code measureSec} of phases.
     */
    private void done(Db db, Scenarios.Info sc, String hash, double buildSec, double measureSec) {
        Instant start = T0.plusSeconds(3600L * seq++);
        Instant first = start.plusMillis(Math.round(buildSec * 1000));
        Job j = new Job();
        j.type = Job.ABBA;
        j.source = "manual";
        j.scenario = sc.name;
        j.priority = 0;
        j.refA = "a";
        j.shaA = "a".repeat(40);
        j.refB = "b";
        j.shaB = "b".repeat(40);
        j.label = "test";
        j = db.insertJob(j);
        j.status = Job.DONE;
        j.scenarioHash = hash;
        j.startedAt = start.toString();
        j.finishedAt = first.plusMillis(Math.round(measureSec * 1000)).toString();
        db.updateJob(j);

        for (int i = 0; i < 4; i++) {
            Run r = new Run();
            r.jobId = j.id;
            r.phase = List.of("A1", "B1", "B2", "A2").get(i);
            r.sha = i == 1 || i == 2 ? j.shaB : j.shaA;
            r.seq = i;
            r.status = Run.OK;
            r.startedAt = first.plusSeconds(i * Math.round(measureSec / 4)).toString();
            r.dir = "runs/" + j.id + "/" + r.phase;
            db.insertRun(r);
        }
    }

    /** */
    private static void build(Db db, String key, double sec, boolean fake) {
        Build b = new Build();
        b.sha = key;
        b.status = Build.OK;
        b.startedAt = T0.toString();
        b.finishedAt = T0.plusSeconds(Math.round(sec)).toString();
        b.durationSec = sec;
        b.fake = fake;
        db.saveBuild(b);
    }

    /** */
    private static Durations durations(Db db, Map<String, Scenarios.Info> scenarios) {
        return new Durations(db, (plugin, name) -> plugin == null ? scenarios.get(name) : null);
    }

    /** */
    @Test void aRunLeavesOutTheBuildsOfItsJob() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Scenarios.Info sc = Scenarios.Info.of("putget", 30, 90);
            done(db, sc, sc.hash, 600, 500); // a cold build of ten minutes
            done(db, sc, sc.hash, 0, 490);
            done(db, sc, sc.hash, 30, 510);

            assertEquals(500, durations(db, Map.of(sc.name, sc)).runSeconds(sc, null, Job.ABBA), 0.5,
                "the median of the measuring, the builds are counted apart");
        }
    }

    /** */
    @Test void aNewVersionOfAScenarioLeavesTheOldOnesOutOnceItRanEnough() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Scenarios.Info sc = Scenarios.Info.of("putget", 30, 90);

            for (int i = 0; i < 5; i++)
                done(db, sc, "putget:0", 0, 900); // the old version measured longer

            Durations d = durations(db, Map.of(sc.name, sc));
            assertEquals(900, d.runSeconds(sc, null, Job.ABBA), 0.5);

            done(db, sc, sc.hash, 0, 500);
            done(db, sc, sc.hash, 0, 500);
            assertEquals(900, durations(db, Map.of(sc.name, sc)).runSeconds(sc, null, Job.ABBA), 0.5,
                "two runs of the new version are not enough yet");

            done(db, sc, sc.hash, 0, 500);
            assertEquals(500, durations(db, Map.of(sc.name, sc)).runSeconds(sc, null, Job.ABBA), 0.5);
            assertEquals(900, d.runSeconds(sc, null, Job.ABBA), 0.5, "an instance keeps what it read");
        }
    }

    /** */
    @Test void aScenarioWithoutRunsTakesWhatAPhaseAddsOnThisStand() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Scenarios.Info one = Scenarios.Info.of("one", 30, 90);
            Scenarios.Info two = Scenarios.Info.of("two", 30, 90);
            two.servers = 2;
            Scenarios.Info fresh = Scenarios.Info.of("fresh", 60, 60);
            fresh.servers = 2;
            Scenarios.Info fresh3 = Scenarios.Info.of("fresh3", 60, 60);
            fresh3.servers = 3;
            Map<String, Scenarios.Info> all = Map.of("one", one, "two", two, "fresh", fresh, "fresh3", fresh3);

            assertEquals(4 * (120 + Durations.PHASE_OVERHEAD), durations(db, all).runSeconds(fresh, null, Job.ABBA),
                0.5, "nothing measured yet: the default");

            done(db, one, one.hash, 0, 4 * 125); // 5 s a phase
            done(db, two, two.hash, 0, 4 * 140); // 20 s a phase

            Durations d = durations(db, all);
            assertEquals(4 * 140, d.runSeconds(fresh, null, Job.ABBA), 0.5, "as the scenarios with as many servers");
            assertEquals(4 * (120 + 12.5), d.runSeconds(fresh3, null, Job.ABBA), 0.5, "else as all of them");
            assertEquals(140, d.runSeconds(fresh, null, Job.SINGLE), 0.5, "a phase of a single job");
        }
    }

    /** */
    @Test void aBuildTakesAsTheLastOfItsKind() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Scenarios.Info core = Scenarios.Info.of("core", 30, 90);
            Scenarios.Info sql = Scenarios.Info.of("sql", 30, 90);
            sql.modules = List.of("indexing", "calcite");
            Durations empty = durations(db, Map.of());
            assertEquals(Durations.BUILD, empty.buildSeconds(core, null), 0.5, "nothing built yet: the default");

            build(db, "a".repeat(40), 25, false);
            build(db, "b".repeat(40), 27, false);
            build(db, "a".repeat(40) + "+calcite+indexing", 36, false);
            build(db, "c".repeat(40) + "+calcite+indexing@spring-data-" + "d".repeat(40), 150, false);
            build(db, "e".repeat(40), 1, true); // a fake build of a stand that also builds for real

            Durations d = durations(db, Map.of());
            assertEquals(26, d.buildSeconds(core, null), 0.5);
            assertEquals(36, d.buildSeconds(sql, null), 0.5);
            assertEquals(150, d.buildSeconds(sql, "spring-data"), 0.5);
            assertEquals(36, d.buildSeconds("f".repeat(40) + "+calcite+indexing"), 0.5);
            assertEquals(31.5, d.buildSeconds(core, "other"), 0.5, "a kind never built: as all the builds");
            assertEquals("+calcite+indexing@spring-data",
                Durations.kind("c".repeat(40) + "+calcite+indexing@spring-data-" + "d".repeat(40)));
        }
    }
}
