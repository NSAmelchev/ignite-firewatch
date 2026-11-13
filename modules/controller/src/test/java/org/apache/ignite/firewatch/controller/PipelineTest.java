package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end through the service layer: fake build, a shell script instead of the harness, real git mirror and DB. */
class PipelineTest {
    /**
     * Writes result.json/series.json like the harness would; B phases are slower when FAKE_THR_B is set, and
     * FAKE_THR_OF ("sha=ops ...") sets the throughput of the build of a commit.
     */
    private static final String FAKE_HARNESS = String.join("\n",
        "#!/bin/bash",
        "while [ $# -gt 0 ]; do case \"$1\" in --out) OUT=$2; shift;; --phase) PHASE=$2; shift;; "
            + "--run-id) RUN=$2; shift;; --libs) LIBS=$2; shift;; esac; shift; done",
        "mkdir -p \"$OUT\"",
        "echo \"fake harness phase=$PHASE run=$RUN\"",
        "if [ -n \"$FAKE_SLEEP\" ]; then sleep \"$FAKE_SLEEP\"; fi",
        "if [ -n \"$FAKE_FAIL_PHASE\" ] && [ \"$FAKE_FAIL_PHASE\" = \"$PHASE\" ]; then",
        "  echo '{\"status\":\"failed\",\"error\":\"boom in '$PHASE'\"}' > \"$OUT/result.json\"; exit 1; fi",
        "case \"$PHASE\" in *B*) THR=${FAKE_THR_B:-10000}; P99=${FAKE_P99_B:-100};; "
            + "*) THR=${FAKE_THR_A:-10000}; P99=${FAKE_P99_A:-100};; esac",
        "for bad in $FAKE_BAD_SHAS; do case \"$LIBS\" in *$bad*) THR=9000;; esac; done",
        "for kv in $FAKE_THR_OF; do case \"$LIBS\" in *${kv%%=*}*) THR=${kv#*=};; esac; done",
        "for kv in $FAKE_THR_PHASE; do if [ \"${kv%%=*}\" = \"$PHASE\" ]; then THR=${kv#*=}; fi; done",
        "OPS=''; P=''; T=''; for i in $(seq 0 29); do d=$(( (i * 7) % 5 - 2 )); "
            + "OPS=\"$OPS$(( THR + d * 10 )),\"; P=\"$P$(( P99 + d )),\"; T=\"$T$i,\"; done",
        "cat > \"$OUT/result.json\" <<EOF",
        "{\"status\":\"ok\",\"harnessVersion\":\"test\",\"scenarioName\":\"smoke\",\"scenarioHash\":\"deadbeef\","
            + "\"runId\":\"$RUN\",\"phase\":\"$PHASE\",",
        " \"igniteVersion\":\"2.99.0-TEST\",\"wallSec\":1.5,\"errors\":0,"
            + "\"throughput\":{\"mean\":$THR,\"stdev\":10,\"samples\":20},",
        " \"latencyUs\":{\"mean\":50,\"p50\":40,\"p90\":80,\"p99\":$P99,\"p999\":300,\"max\":900},",
        " \"gc\":{\"count\":5,\"totalPauseMs\":20,\"maxPauseMs\":6,\"pausePct\":0.1,\"allocMbPerSec\":90},",
        " \"cpu\":{\"serverJvmPct\":45.5,\"machinePct\":60,\"loaderJvmPct\":12},"
            + "\"heap\":{\"afterGcMbMean\":120,\"committedMbMax\":512},\"env\":{\"cpus\":4}}",
        "EOF",
        "cat > \"$OUT/series.json\" <<EOF",
        "{\"measureStartIndex\":10,\"t\":[${T%,}],\"ops\":[${OPS%,}],\"p99Us\":[${P%,}]}",
        "EOF",
        "echo PHASE_DONE status=ok",
        "");

    /** */
    @TempDir Path tmp;

    /** */
    GitFixture origin;

    /** */
    Config cfg;

    /** */
    Db db;

    /** */
    GitMirror mirror;

    /** */
    Scenarios scenarios;

    /** */
    Builder builder;

    /** */
    JobService jobs;

    /** */
    JobRunner runner;

    /** */
    Map<String, String> env = new HashMap<>();

    /** */
    @BeforeEach void setUp() throws Exception {
        Assumptions.assumeTrue(GitFixture.gitAvailable(), "git is required");
        origin = new GitFixture(tmp.resolve("origin"));
        origin.commit("c1", Map.of("pom.xml", "<project/>", "modules/core/src/main/A.java", "class A {}"));
        origin.commit("c2", Map.of("modules/core/src/main/A.java", "class A { int x; }"));

        cfg = new Config();
        cfg.dataDir = tmp.resolve("data");
        cfg.cacheDir = tmp.resolve("cache");
        cfg.fakeBuild = true;
        cfg.fakeLibsDir = tmp.resolve("fake-libs");
        cfg.branch = "master";
        cfg.repoUrl = origin.url();
        Files.createDirectories(cfg.fakeLibsDir);
        Files.writeString(cfg.fakeLibsDir.resolve("ignite-core-2.99.0-TEST.jar"), "not really a jar");
        Path script = tmp.resolve("fake-harness.sh");
        Files.writeString(script, FAKE_HARNESS);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        cfg.harnessCommand = List.of(script.toString());
        cfg.harnessEnv = env;
        cfg.phaseGraceSec = 5;

        db = Db.open(cfg.dbFile());
        Settings.seed(db, cfg);
        db.setSetting(Settings.AUTORUN_SCENARIO, "smoke"); // the only scenario of this test catalog
        Scenarios.Info smoke = Scenarios.Info.of("smoke", 1, 2);
        smoke.hash = "deadbeef"; // what the fake harness writes into result.json: calibrations of this version count
        scenarios = new Scenarios(List.of(smoke));
        mirror = new GitMirror(cfg.mirrorDir(), cfg.repoUrl, "master", Duration.ofMinutes(1));
        mirror.fetch();
        builder = new Builder(cfg, db, mirror);
        jobs = new JobService(cfg, db, mirror, scenarios);
        runner = new JobRunner(cfg, db, builder, scenarios, jobs, mirror);
    }

    /** */
    @AfterEach void tearDown() throws Exception {
        if (db != null)
            db.close();
    }

    /** */
    @Test void maintenancePauseAndQueueEta() throws Exception {
        assertFalse(runner.paused());
        runner.setPaused(true);
        assertTrue(runner.paused(), "closed for maintenance");
        runner.setPaused(false);
        assertFalse(runner.paused());

        jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, null);
        jobs.enqueue(Job.SINGLE, "master~1", null, "smoke", 1, null, null);
        List<Job> active = db.jobs("active", 10);
        jobs.addEta(active);
        Job first = active.get(0), second = active.get(1);
        assertNotNull(first.etaStartAt);
        assertTrue(first.etaSec > 0);
        assertTrue(java.time.Instant.parse(second.etaStartAt).isAfter(java.time.Instant.parse(first.etaStartAt)),
            "starts are cumulative");
    }

    /** */
    @Test void theHarnessGetsTheHostsOfARemoteStand() throws Exception {
        Stand stand = new Stand();
        stand.mode = Stand.REMOTE;

        for (String[] h : List.of(new String[] {"a", Stand.SERVERS}, new String[] {"b", Stand.LOADERS})) {
            Stand.Host host = new Stand.Host();
            host.address = h[0];
            host.role = h[1];
            host.user = "bench";
            host.javaHome = "/jdk";
            host.workDir = "/opt/firewatch";
            stand.hosts.add(host);
        }

        stand.save(db);
        cfg.sshKey = "/keys/id";
        db.setSetting(Stand.FINGERPRINTS, "{\"a\": {}, \"b\": {}}");

        Job j = jobs.enqueue(Job.SINGLE, "master", null, "smoke", 1, null, null);
        runner.runJob(db.nextQueued());

        Path dir = cfg.dataDir.resolve(db.runs(j.id).get(0).dir);
        assertTrue(Files.readString(dir.resolve("harness.log")).contains("--stand "
            + dir.resolve("stand.json").toAbsolutePath()));
        var file = Json.tree(Files.readString(dir.resolve("stand.json")));
        assertEquals(2, file.get("hosts").size());
        assertEquals("/keys/id", file.get("sshKey").asText());
    }

    /** */
    @Test void aRemoteHostThatDidNotPassCheckRunsNothing() throws Exception {
        Stand stand = new Stand();
        stand.mode = Stand.REMOTE;

        for (String[] h : List.of(new String[] {"a", Stand.SERVERS}, new String[] {"b", Stand.LOADERS})) {
            Stand.Host host = new Stand.Host();
            host.address = h[0];
            host.role = h[1];
            host.user = "bench";
            host.javaHome = "/jdk";
            host.workDir = "/opt/firewatch";
            stand.hosts.add(host);
        }

        stand.save(db);
        Job j = jobs.enqueue(Job.SINGLE, "master", null, "smoke", 1, null, null);
        runner.runJob(db.nextQueued());
        Job done = db.job(j.id);
        assertEquals(Job.FAILED, done.status);
        assertTrue(done.error.contains("Check"), done.error);
        assertTrue(db.runs(j.id).isEmpty(), "no phase started");
    }

    /** */
    @Test void measureLoadRaisesThreadsUntilThroughputStopsGrowing() throws Exception {
        Job j = jobs.enqueueProbe();
        assertEquals(Job.PROBE, j.type);
        assertEquals(origin.shas.get(origin.shas.size() - 1), j.shaA);
        runner.runJob(db.nextQueued());
        Job done = jobs.detail(j.id);
        assertEquals(Job.DONE, done.status, done.error);
        // the fake harness gives the same throughput every time: the second probe already shows no growth
        assertEquals(List.of("x1", "x2"), done.runs.stream().map(r -> r.phase).toList());
        assertTrue(Files.readString(cfg.dataDir.resolve(done.runs.get(1).dir).resolve("harness.log"))
            .contains("--scale 0,0,2.0,1"));
        var load = Json.tree(db.setting(Stand.LOAD, "null"));
        assertEquals(2, load.path("steps").size());
        assertEquals(1.0, load.path("threadsFactor").asDouble(), "no growth, no more threads");
        assertFalse(Stand.frozen(db), "a probe says nothing about the history");
        assertTrue(jobs.commitsWithJobs(10, "smoke").stream()
            .allMatch(c -> Json.MAPPER.valueToTree(c).path("jobs").isEmpty()), "and is not on Commits");
    }

    /** */
    @Test void aLocalStandNamesNoHosts() throws Exception {
        Job j = jobs.enqueue(Job.SINGLE, "master", null, "smoke", 1, null, null);
        runner.runJob(db.nextQueued());
        Path dir = cfg.dataDir.resolve(db.runs(j.id).get(0).dir);
        assertFalse(Files.exists(dir.resolve("stand.json")));
        assertFalse(Files.readString(dir.resolve("harness.log")).contains("--stand"));
    }

    /** */
    @Test void abbaJobRunsToCompletionWithRegression() throws Exception {
        env.put("FAKE_THR_B", "9000");
        env.put("FAKE_P99_B", "120");
        Job j = jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, null);
        assertEquals(origin.shas.get(0), j.shaA);
        assertEquals(origin.shas.get(1), j.shaB);
        assertEquals(Job.QUEUED, j.status);
        assertThrows(IllegalArgumentException.class,
            () -> jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, null),
            "duplicate is rejected");

        runner.runJob(db.nextQueued());

        Job done = jobs.detail(j.id);
        assertEquals(Job.DONE, done.status, done.error);
        assertEquals(100, done.progress);
        assertEquals("deadbeef", done.scenarioHash);
        assertEquals(Stats.REGRESSION, done.verdict);
        assertEquals(4, done.runs.size());
        assertEquals(List.of("A1", "B1", "B2", "A2"), done.runs.stream().map(r -> r.phase).toList());
        assertEquals(origin.shas.get(1), done.runs.get(1).sha);

        for (Run r : done.runs) {
            assertEquals(Run.OK, r.status, r.error);
            assertEquals("2.99.0-TEST", r.igniteVersion);
            assertTrue(Files.exists(cfg.dataDir.resolve(r.dir).resolve("result.json")));
            assertTrue(Files.exists(cfg.dataDir.resolve(r.dir).resolve("harness.log")));
        }

        assertEquals(9000, done.runs.get(1).throughput, 1e-9);
        assertEquals(-10, done.comparison.path("metrics").path("throughput").path("deltaPct").asDouble(), 0.01);
        assertEquals(20, done.comparison.path("metrics").path("p99Us").path("deltaPct").asDouble(), 0.01);
        assertEquals("default", done.comparison.path("noiseSource").asText());

        Build b = db.build(origin.shas.get(1));
        assertEquals(Build.OK, b.status);
        assertTrue(b.fake);
        assertEquals("2.99.0-TEST", b.igniteVersion);
        assertTrue(builder.cached(origin.shas.get(0)));

        // Trend and commits views see the result.
        new Watcher(cfg, db, mirror, jobs).poll();
        List<JobService.TrendPoint> trend = jobs.trend("smoke", 50);
        assertEquals(1, trend.size());
        assertEquals(origin.shas.get(1), trend.get(0).sha);
        assertEquals(9000, trend.get(0).values.get("throughput"), 1e-9);
        assertEquals(Stats.REGRESSION, trend.get(0).verdict);
        List<CommitInfo> commits = jobs.commitsWithJobs(10, "smoke");
        assertEquals(2, commits.size());
        assertEquals(1, commits.get(0).jobs.size());
        assertEquals(0, commits.get(1).jobs.size());
    }

    /** */
    @Test void singleJobAndCalibrationNoiseFloor() throws Exception {
        new Watcher(cfg, db, mirror, jobs).poll(); // the trend is built over the watched branch's history
        Job s = jobs.enqueue(Job.SINGLE, "master", null, "smoke", 0, null, null);
        runner.runJob(db.nextQueued());
        Job done = jobs.detail(s.id);
        assertEquals(Job.DONE, done.status, done.error);
        assertNull(done.verdict);
        assertEquals(1, done.runs.size());
        assertEquals("S", done.runs.get(0).phase);
        assertEquals(1, jobs.trend("smoke", 50).size());

        List<Job> cals = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            Job c = jobs.calibrate(null, "smoke", 0); // several identical A/A jobs may wait at once
            assertEquals(JobService.SRC_CALIBRATION, c.source);
            assertEquals(c.shaA, c.shaB);
            cals.add(c);
        }

        assertEquals(3, db.jobs("active", 10).size());

        for (Job c : cals) {
            runner.runJob(db.nextQueued());
            assertEquals(Job.DONE, db.job(c.id).status);
            assertEquals(Stats.NO_CHANGE, db.job(c.id).verdict);
        }

        Job abba = jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, "x");
        runner.runJob(db.nextQueued());
        Job d = db.job(abba.id);
        assertEquals("calibration", d.comparison.path("noiseSource").asText());
        assertEquals(3, d.comparison.path("calibrations").asInt());
        assertEquals(1, jobs.trend("smoke", 50).size(),
            "calibrations are not trend points; the ABBA replaces the single");
        assertEquals(Job.ABBA, jobs.trend("smoke", 50).get(0).jobType);
    }

    /** */
    @Test void failedPhaseFailsTheJob() throws Exception {
        env.put("FAKE_FAIL_PHASE", "B1");
        Job j = jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, null);
        runner.runJob(db.nextQueued());
        Job d = jobs.detail(j.id);
        assertEquals(Job.FAILED, d.status);
        assertTrue(d.error.contains("phase B1 failed"), d.error);
        assertTrue(d.error.contains("boom in B1"), d.error);
        assertEquals(2, d.runs.size(), "A2 and B2 never started");
        assertEquals(Run.FAILED, d.runs.get(1).status);
        Job retry = jobs.retry(j.id);
        assertEquals(Job.QUEUED, retry.status);
        assertEquals(j.shaB, retry.shaB);
        assertEquals(Math.max(j.priority, 1), retry.priority, "by default that of the old job, at least a manual one");
        jobs.cancel(retry.id);
        assertEquals(2, jobs.retry(j.id, 2).priority, "ahead of the queue when the dialog says so");
    }

    /** */
    @Test void cancelKillsTheRunningPhase() throws Exception {
        env.put("FAKE_SLEEP", "60");
        Job j = jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, null);
        Thread t = new Thread(() -> runner.runJob(db.nextQueued()));
        t.start();
        long deadline = System.currentTimeMillis() + 20_000;

        while (System.currentTimeMillis() < deadline) {
            Job cur = db.job(j.id);

            if (cur.stage != null && cur.stage.startsWith("phase"))
                break;

            Thread.sleep(100);
        }

        assertTrue(db.job(j.id).stage.startsWith("phase"), "job did not reach a phase: " + db.job(j.id).stage);
        assertTrue(jobs.cancel(j.id));
        t.join(15_000);
        assertTrue(!t.isAlive(), "runner did not return after cancel");
        Job d = jobs.detail(j.id);
        assertEquals(Job.CANCELLED, d.status);
        assertEquals(Run.FAILED, d.runs.get(0).status);
        assertEquals("cancelled", d.runs.get(0).error);
        assertNull(runner.current());
    }

    /** */
    @Test void shutdownInterruptsTheRunningJob() throws Exception {
        env.put("FAKE_SLEEP", "60");
        Job j = jobs.enqueue(Job.SINGLE, "master", null, "smoke", 1, null, null);
        Thread t = new Thread(() -> runner.runJob(db.nextQueued()));
        t.start();
        long deadline = System.currentTimeMillis() + 20_000;

        while (System.currentTimeMillis() < deadline
            && !(db.job(j.id).stage != null && db.job(j.id).stage.startsWith("phase")))
            Thread.sleep(100);

        Thread.sleep(500);
        runner.shutdown();
        t.join(15_000);
        assertTrue(!t.isAlive(), "runner did not return after shutdown");
        Job d = jobs.detail(j.id);
        assertEquals(Job.INTERRUPTED, d.status, d.error);
        assertEquals("controller stopped", d.runs.get(0).error);
    }

    /** */
    @Test void restartMarksRunningJobsInterrupted() throws Exception {
        Job j = jobs.enqueue(Job.SINGLE, "master", null, "smoke", 0, null, null);
        j.status = Job.RUNNING;
        j.startedAt = Db.now();
        db.updateJob(j);
        Run r = new Run();
        r.jobId = j.id;
        r.phase = "S";
        r.sha = j.shaA;
        r.status = Run.RUNNING;
        r.dir = "runs/" + j.id + "/S";
        db.insertRun(r);
        new JobRunner(cfg, db, builder, scenarios, jobs, mirror).recover();
        assertEquals(Job.INTERRUPTED, db.job(j.id).status);
        assertNotNull(db.job(j.id).error);
        assertEquals(Run.FAILED, db.runs(j.id).get(0).status);
        assertNull(db.nextQueued());
    }

    /** */
    @Test void segmentsModeQueuesOneJobPerBatchAndCanBeSplit() throws Exception {
        Watcher w = new Watcher(cfg, db, mirror, jobs);
        db.setSetting(Settings.AUTORUN_ENABLED, "true");
        db.setSetting(Settings.AUTORUN_MODE, "segments");
        w.poll();
        String start = origin.shas.get(1);
        assertEquals(start, db.setting(Settings.WATCH_WATERMARK, null));

        String c1 = origin.commit("core 1", Map.of("modules/core/src/main/S1.java", "class S1 {}"));
        origin.commit("docs", Map.of("README.md", "y"));
        w.poll();
        assertNull(db.nextQueued(), "fresh commits wait");

        String c3 = origin.commit("core 2", Map.of("modules/core/src/main/S2.java", "class S2 {}"));
        w.poll();
        assertNull(db.nextQueued(), "however many of them come");
        db.setSetting(Settings.AUTORUN_KEEP_DAYS, "0"); // commits this young would otherwise take days to get old
        w.poll();
        Job seg = db.nextQueued();
        assertNotNull(seg, "overdue: one segment");
        assertEquals(Bisector.AHEAD, seg.priority);
        assertEquals(start, seg.shaA, "segment starts at the previous end");
        assertEquals(c3, seg.shaB, "and ends at the head");
        assertTrue(seg.label.contains("(3 commits)"), seg.label);
        assertEquals(1, db.jobs("active", 10).size(), "one job for the whole batch, not one per commit");
        assertEquals(c3, db.setting(Settings.WATCH_SEGMENT_BASE, null));
        w.poll();
        assertEquals(1, db.jobs("active", 10).size(), "nothing new, nothing queued");

        List<Job> halves = jobs.split(seg.id, "halves", 2);
        assertEquals(2, halves.size());
        assertTrue(halves.stream().allMatch(h -> h.priority == 2), "the priority the dialog chose");
        assertEquals(start, halves.get(0).shaA);
        assertEquals(c1, halves.get(0).shaB, "middle of 3 commits = the first one");
        assertEquals(c1, halves.get(1).shaA);
        assertEquals(c3, halves.get(1).shaB);
        List<Job> each = jobs.split(seg.id, "commits");
        assertEquals(2, each.size(), "every commit vs its parent; c1 vs start is already queued as the first half");
    }

    /** */
    @Test void trendOnRequestKeepsTheSegmentsASplitRefined() throws Exception {
        Watcher w = new Watcher(cfg, db, mirror, jobs);
        w.poll();
        String start = origin.shas.get(1);
        String c1 = origin.commit("core 1", Map.of("modules/core/src/main/S1.java", "class S1 {}"));
        String c2 = origin.commit("core 2", Map.of("modules/core/src/main/S2.java", "class S2 {}"));
        w.poll();
        Job seg = jobs.enqueue(Job.ABBA, start, c2, "smoke", 1, null, null);
        runner.runJob(db.nextQueued());
        Job again = jobs.enqueue(Job.ABBA, start, c2, "smoke", 1, null, null);
        runner.runJob(db.nextQueued());
        Job half = jobs.enqueue(Job.ABBA, c1, c2, "smoke", 1, null, null); // the second half ends where the segment did
        runner.runJob(db.nextQueued());

        List<JobService.TrendPoint> plain = jobs.trend("smoke", 50);
        assertEquals(List.of(half.id), plain.stream().map(p -> p.jobId).toList(),
            "one point per commit: the shorter run");
        List<JobService.TrendPoint> all = jobs.trend("smoke", 50, true);
        assertEquals(List.of(half.id, again.id), all.stream().map(p -> p.jobId).toList(),
            "the latest run of the segment");
        assertNull(all.get(0).refinedBy);
        assertEquals(1, all.get(0).span);
        assertEquals(half.id, all.get(1).refinedBy);
        assertEquals(2, all.get(1).span, "the segment keeps its own baseline");
        assertEquals(List.of(seg.id, again.id), all.get(1).jobIds, "and its repeats are pooled");
    }

    /** */
    @Test void watcherQueuesOnlyNewFilteredCommits() throws Exception {
        Watcher w = new Watcher(cfg, db, mirror, jobs);
        db.setSetting(Settings.AUTORUN_ENABLED, "true");
        w.poll();
        assertEquals(origin.shas.get(1), db.setting(Settings.WATCH_WATERMARK, null),
            "first poll only sets the watermark");
        assertNull(db.nextQueued());
        assertEquals(2, db.commits("master", 10).size());
        assertEquals(2, db.commits("master", 10).get(0).seq);

        String docs = origin.commit("docs only", Map.of("README.md", "x"));
        w.poll();
        assertNull(db.nextQueued(), "filtered out by the path filter");
        assertEquals(docs, db.setting(Settings.WATCH_WATERMARK, null));

        String core = origin.commit("core change", Map.of("modules/core/src/main/B.java", "class B {}"));
        w.poll();
        Job q = db.nextQueued();
        assertNotNull(q, "core change is queued");
        assertEquals(Job.ABBA, q.type);
        assertEquals(JobService.SRC_AUTO, q.source);
        assertEquals(docs, q.shaA, "A = first parent");
        assertEquals(core, q.shaB);
        assertEquals("smoke", q.scenario);
        w.poll();
        assertEquals(1, db.jobs("active", 10).size(), "nothing new, nothing queued twice");

        db.setSetting(Settings.AUTORUN_ENABLED, "false");
        String more = origin.commit("another core change", Map.of("modules/core/src/main/C.java", "class C {}"));
        w.poll();
        assertEquals(1, db.jobs("active", 10).size(), "paused: not queued");
        assertEquals(more, db.setting(Settings.WATCH_WATERMARK, null), "but the watermark moved on");

        db.setSetting(Settings.AUTORUN_ENABLED, "true");
        db.setSetting(Settings.AUTORUN_MAX_BACKLOG, "1");
        origin.commit("blocked by backlog", Map.of("modules/core/src/main/D.java", "class D {}"));
        w.poll();
        assertEquals(1, db.jobs("active", 10).size(), "backlog limit reached");

        db.setSetting(Settings.AUTORUN_MODE, "single");
        db.setSetting(Settings.AUTORUN_MAX_BACKLOG, "5");
        String single = origin.commit("single mode", Map.of("modules/core/src/main/E.java", "class E {}"));
        w.poll();
        List<Job> active = db.jobs("active", 10);
        assertEquals(2, active.size());
        assertEquals(Job.SINGLE, active.get(1).type);
        assertEquals(single, active.get(1).shaA);
        assertTrue(w.status().get("watermark").equals(single));
    }

    /** */
    @Test void commitAlreadyInBranchIsComparedWithItsParent() throws Exception {
        String head = GitFixture.run("-C", origin.repo.toString(), "rev-parse", "master").trim();
        String parent = GitFixture.run("-C", origin.repo.toString(), "rev-parse", "master~1").trim();
        Job j = compare(null, head);
        assertEquals(head, j.shaB);
        assertEquals(parent, j.shaA, "merge-base of an in-branch commit is itself: the parent is used instead");
        assertTrue(j.label.contains("vs parent"), j.label);
    }

    /** */
    @Test void prIsComparedAsMasterVsMasterPlusPr() throws Exception {
        origin.branch("pr", origin.shas.get(0));
        origin.checkout("pr");
        String prSha = origin.commit("pr", Map.of("modules/core/src/main/P.java", "class P {}"));
        origin.checkout("master");
        String repo = origin.repo.toString();
        String masterSha = GitFixture.run("-C", repo, "rev-parse", "master").trim();
        GitFixture.git("-C", repo, "update-ref", "refs/pull/7/head", prSha);
        // what GitHub keeps for an open PR: a test merge of the PR into the current master
        GitFixture.git("-C", repo, "checkout", "--quiet", "-b", "gh-merge", "master");
        GitFixture.git("-C", repo, "-c", "user.name=gh", "-c", "user.email=gh@example.com",
            "merge", "--quiet", "--no-ff", "--no-edit", "pr");
        String mergeSha = GitFixture.run("-C", repo, "rev-parse", "HEAD").trim();
        GitFixture.git("-C", repo, "checkout", "--quiet", "master");
        GitFixture.git("-C", repo, "update-ref", "refs/pull/7/merge", mergeSha);

        Job j = compare(null, "https://github.com/apache/ignite/pull/7");
        assertEquals(mergeSha, j.shaB, "B = master + PR");
        assertEquals(masterSha, j.shaA, "A = the master commit the PR was merged into");
        assertEquals(JobService.SRC_COMPARE, j.source);
        assertTrue(j.label.startsWith("pr/7: master + PR vs master"), j.label);

        Job explicit = compare(origin.shas.get(0), "pr/7");
        assertEquals(prSha, explicit.shaB, "with an explicit baseline the PR head is measured");
        assertEquals(origin.shas.get(0), explicit.shaA);
    }

    /** */
    private Comparisons comparisons() {
        return new Comparisons(db, mirror, jobs, scenarios, new Bisector(db, mirror, jobs));
    }

    /** The only job of a comparison of one scenario. */
    private Job compare(String base, String target) throws Exception {
        return db.jobsOfComparison(comparisons().create(base, target, List.of("smoke"), 2).id).get(0);
    }

    /** */
    @Test void aComparisonQueuesOneJobPerScenarioAndReadsItsStateFromThem() throws Exception {
        scenarios = new Scenarios(List.of(scenarios.get("smoke"), Scenarios.Info.of("other", 1, 2)));
        jobs = new JobService(cfg, db, mirror, scenarios);
        runner = new JobRunner(cfg, db, builder, scenarios, jobs, mirror);
        Comparisons comparisons = comparisons();
        String a = origin.shas.get(0), b = origin.shas.get(1);
        assertThrows(IllegalArgumentException.class, () -> comparisons.create(a, b, List.of(), 2));
        assertThrows(IllegalArgumentException.class, () -> comparisons.create(a, b, List.of("smoke", "nope"), 2));

        Comparison c = comparisons.create(a, b, List.of("smoke", "other"), 2);
        assertEquals(Job.QUEUED, c.status);
        assertEquals(2, c.total);
        List<Job> list = db.jobsOfComparison(c.id);
        assertEquals(List.of("smoke", "other"), list.stream().map(j -> j.scenario).toList());
        assertTrue(list.stream().allMatch(j -> a.equals(j.shaA) && b.equals(j.shaB)
            && JobService.SRC_COMPARE.equals(j.source)));
        assertTrue(list.stream().noneMatch(JobService::isBranchHistory),
            "a comparison stays out of the branch history");
        assertThrows(IllegalArgumentException.class, () -> comparisons.create(a, b, List.of("other"), 2),
            "the same pair is already queued: nothing new is created");
        assertEquals(1, db.comparisons(10).size());

        assertThrows(IllegalArgumentException.class, () -> comparisons.delete(c.id), "active jobs are cancelled first");
        assertEquals(Job.CANCELLED, comparisons.cancel(c.id).status);
        assertTrue(db.jobsOfComparison(c.id).stream().allMatch(j -> Job.CANCELLED.equals(j.status)));

        Job again = jobs.retry(list.get(1).id);
        assertEquals(Long.valueOf(c.id), again.comparisonId, "a retry stays in the comparison");
        Comparison reopened = comparisons.get(c.id);
        assertEquals(Job.QUEUED, reopened.status);
        assertEquals(again.id, reopened.rows.get(1).job.id, "a row shows the latest job of its scenario");
        assertNotNull(reopened.doneAt);
        jobs.cancel(again.id);

        comparisons.delete(c.id);
        assertNull(db.comparison(c.id));
        assertTrue(db.jobsOfComparison(c.id).isEmpty());
    }

    /** */
    @Test void repeatsOfAScenarioArePooledInTheComparison() throws Exception {
        Comparisons comparisons = comparisons();
        Comparison c = comparisons.create(origin.shas.get(0), origin.shas.get(1), List.of("smoke"), 2);
        Job first = db.jobsOfComparison(c.id).get(0);
        runner.runJob(db.job(first.id));
        assertEquals(1, comparisons.get(c.id).rows.get(0).runs);
        assertEquals(Job.DONE, comparisons.get(c.id).status);
        assertEquals(1, comparisons.get(c.id).measured);

        Job second = jobs.retry(first.id);
        Comparison waiting = comparisons.get(c.id);
        assertEquals(Job.RUNNING, waiting.status);
        assertEquals(1, waiting.rows.get(0).runs, "the result stays on the row while one more run waits");
        runner.runJob(db.job(second.id));
        Comparison.Row row = comparisons.get(c.id).rows.get(0);
        assertEquals(2, row.runs);
        assertEquals(List.of(first.id, second.id), row.jobIds);
        assertEquals(2, row.comparison.path("runs").asInt());
        assertEquals(1, comparisons.get(c.id).total, "repeats are one scenario");
    }

    // ---------------------------------------------------------------- automatic bisection

    /** Eight more commits on top of the fixture, known to the mirror and the database. */
    private List<String> eightCommits() throws Exception {
        List<String> c = new ArrayList<>();

        for (int i = 1; i <= 8; i++)
            c.add(origin.commit("core " + i, Map.of("modules/core/src/main/B" + i + ".java", "class B" + i + " {}")));

        new Watcher(cfg, db, mirror, jobs).poll();

        return c;
    }

    /** Builds of {@code commits} from index {@code from} on run at {@code ops}; the rest at the default 10000. */
    private void throughput(List<String> commits, Object... fromAndOps) {
        StringBuilder sb = new StringBuilder();

        for (int k = 0; k < fromAndOps.length; k += 2) {
            for (int i = (Integer)fromAndOps[k]; i < commits.size(); i++)
                sb.append(commits.get(i)).append('=').append(fromAndOps[k + 1]).append(' ');
        }

        env.put("FAKE_THR_OF", sb.toString().trim());
    }

    /** */
    private Job measuredSegment(String a, String b) throws Exception {
        Job j = jobs.enqueueSegment(a, b, mirror.revList(a, b).size(), "smoke", 1, JobService.SRC_MANUAL);
        runner.runJob(db.nextQueued());

        return db.job(j.id);
    }

    /** */
    private Bisect runToTheEnd(Bisector bisector, long id) {
        for (Job q; (q = db.nextQueued()) != null; )
            runner.runJob(q);

        return bisector.get(id);
    }

    /**
     * The name of a request goes to what it queues and cancels; the steps the worker queues later carry the name of
     * the bisection, and what the stand queues by itself has none.
     */
    @Test void whoQueuedAndWhoCancelled() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);

        try {
            Who.set("alice");
            Job manual = jobs.enqueue(Job.ABBA, origin.shas.get(0), c.get(0), "smoke", 1, JobService.SRC_MANUAL, null);
            assertEquals("alice", manual.requestedBy);
            Who.set("bob");
            assertTrue(jobs.cancel(manual.id));
            assertEquals("alice", db.job(manual.id).requestedBy);
            assertEquals("bob", db.job(manual.id).cancelledBy);

            Who.set(null);
            Job root = measuredSegment(origin.shas.get(1), c.get(7));
            assertNull(root.requestedBy, "queued with no request: the stand itself");
            Who.set("carol");
            Bisector bisector = new Bisector(db, mirror, jobs);
            Bisect b = bisector.start(root.id, "throughput", null);
            assertEquals("carol", b.requestedBy);
            assertEquals("carol", b.waiting.requestedBy);
            Who.set(null); // the worker queues the next steps
            b = runToTheEnd(bisector, b.id);
            assertTrue(db.jobsOfBisect(b.id).size() > 1);
            assertTrue(db.jobsOfBisect(b.id).stream().allMatch(j -> "carol".equals(j.requestedBy)));

            Who.set("dave");
            Comparison cmp = new Comparisons(db, mirror, jobs, scenarios, bisector).create(origin.shas.get(0), c.get(7),
                List.of("smoke"), 2);
            assertEquals("dave", db.comparison(cmp.id).requestedBy);
            assertTrue(db.jobsOfComparison(cmp.id).stream().allMatch(j -> "dave".equals(j.requestedBy)));
        }
        finally {
            Who.set(null);
        }
    }

    /** */
    @Test void bisectFindsTheCommitCheckingTheOtherHalfOfAMovedOne() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000); // the 5th commit costs 10 %
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        assertEquals(Stats.REGRESSION, root.verdict);
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = bisector.start(root.id, "throughput", null);
        assertEquals(Bisect.RUNNING, b.status);
        assertEquals(Stats.REGRESSION, b.direction);
        assertNotNull(b.waiting, "the left half is queued");
        assertEquals(Bisector.AHEAD, b.waiting.priority, "ahead of ordinary manual jobs");
        assertEquals(c.get(3), b.waiting.shaB);
        assertThrows(IllegalArgumentException.class,
            () -> bisector.start(root.id, "throughput", null), "one per scenario");

        b = runToTheEnd(bisector, b.id);
        assertEquals(Bisect.FOUND, b.status, b.reason);
        assertEquals(c.get(4), b.resultSha);
        assertTrue(b.reason.contains("it explains 100 %"), b.reason);
        // a quiet left half sends the search right unmeasured; a moved one gets its right half measured too
        assertEquals(List.of("left", "left", "right", "left", "right"),
            b.state.steps.stream().map(st -> st.role).toList());
        List<Job> steps = db.jobsOfBisect(b.id);
        assertEquals(List.of(1, 2, 3, 4, 5), steps.stream().map(j -> j.bisectStep).toList());
        assertTrue(steps.stream().allMatch(j -> JobService.SRC_BISECT.equals(j.source)));
        assertEquals(c.get(4).substring(0, 10) + " vs parent",
            steps.get(3).label, "the commit is measured against its parent");
        assertTrue(b.state.steps.get(0).decision.startsWith("→ right (not measured)"), b.state.steps.get(0).decision);
        assertEquals(List.of("found"), b.state.findings.stream().map(f -> f.kind).toList());
        assertEquals(1, jobs.trend("smoke", 50).stream().filter(p -> p.sha.equals(c.get(4))).count(),
            "steps are trend points");
    }

    /** */
    @Test void bisectReusesAHalfMeasuredBefore() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Job manual = measuredSegment(origin.shas.get(1), c.get(3)); // a manual split already measured the left half
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = runToTheEnd(bisector, bisector.start(root.id, "throughput", null).id);
        assertEquals(Bisect.FOUND, b.status, b.reason);
        assertEquals(c.get(4), b.resultSha);
        assertTrue(b.state.steps.get(0).reused);
        assertEquals(manual.id, b.state.steps.get(0).jobId);
        assertEquals(4, db.jobsOfBisect(b.id).size(), "no job for the reused half");
    }

    /** */
    @Test void bisectSearchesBothHalvesWhenBothMoved() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 1, 9500, 5, 9000); // -5 % in the left half (2nd commit), -5 % more in the right one (6th)
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = runToTheEnd(bisector, bisector.start(root.id, "throughput", null).id);
        assertEquals(Bisect.FOUND, b.status, b.reason);
        assertEquals(java.util.Set.of(c.get(1), c.get(5)),
            b.state.findings.stream().filter(f -> f.kind.equals("found"))
                .map(f -> f.b).collect(java.util.stream.Collectors.toSet()));
        assertEquals(c.get(5), b.resultSha, "the larger one first");
        assertTrue(b.reason.contains("they explain"), b.reason);
        assertTrue(b.state.steps.get(1).decision.startsWith("→ both halves"), b.state.steps.get(1).decision);
    }

    /** */
    @Test void bisectRepeatsTheRangeWhenNeitherHalfMoved() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 1, 9850, 4, 9700); // -1.5 % twice: each half within the noise, the whole beyond it
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = runToTheEnd(bisector, bisector.start(root.id, "throughput", null).id);
        assertEquals(Bisect.SPREAD, b.status, b.reason);
        assertTrue(b.reason.startsWith("8 commits") && b.reason.endsWith("spread over small changes"), b.reason);
        assertEquals(List.of("left", "right", "repeat"), b.state.steps.stream().map(st -> st.role).toList());
        assertEquals(2, b.state.steps.get(2).result.runs, "the repeat is pooled with the root run");
    }

    /** */
    @Test void bisectConfirmsALastCommitCloseToTheThreshold() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9750); // -2.5 % at a 2 % threshold
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = runToTheEnd(bisector, bisector.start(root.id, "throughput", null).id);
        assertEquals(Bisect.FOUND, b.status, b.reason);
        assertEquals(c.get(4), b.resultSha);
        Bisect.Step last = b.state.steps.get(b.state.steps.size() - 1);
        assertEquals("confirm", last.role);
        assertEquals(2, last.result.runs);
    }

    /** Runs the next queued step with this throughput of the commits, for this step only. */
    private void nextStep(List<String> commits, Object... fromAndOps) {
        throughput(commits, fromAndOps);
        runner.runJob(db.nextQueued());
    }

    /**
     * A real change the search cannot follow: the root run and both halves show it, the repeat of the segment
     * does not.
     */
    private Bisect lostOnTheRepeat(Bisector bisector, List<String> c, Job root) throws Exception {
        long id = bisector.start(root.id, "throughput", null).id;
        nextStep(c, 3, 9850); // left half -1.5 %
        nextStep(c, 7, 9900); // right half -1 %: neither beats the floor
        nextStep(c, 7, 9990);

        return bisector.get(id);
    }

    /** */
    @Test void aRealChangeTheSearchCannotFollowIsInconclusive() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 7, 9750);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = lostOnTheRepeat(bisector, c, root);
        assertEquals(Bisect.INCONCLUSIVE, b.status, b.reason);
        assertTrue(b.reason.contains("deep search"), b.reason);
        assertEquals(List.of("lost"), b.state.findings.stream().map(f -> f.kind).toList());
        assertEquals(3, b.state.steps.size(), "the halves prove it: no run of the segment on top");
        assertFalse(b.active());

        b.status = Bisect.LOST; // as bisections ended before this status
        db.updateBisect(b);
        bisector.recover();
        assertEquals(Bisect.INCONCLUSIVE, db.bisect(b.id).status, "an old one gets the status too");
    }

    /**
     * A search that went right without measuring: no halves to prove the change, so the segment is measured once
     * more at {@code ops}.
     */
    private Bisect quietAllTheWayDown(int ops) throws Exception {
        List<String> c = eightCommits();
        throughput(c, 7, 9750);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        long id = bisector.start(root.id, "throughput", null).id;
        throughput(c);

        for (Job q; (q = db.nextQueued()) != null; ) {
            if (q.shaA.equals(root.shaA) && q.shaB.equals(root.shaB))
                throughput(c, 7, ops);

            runner.runJob(q);
        }

        Bisect b = bisector.get(id);
        assertEquals("root", b.state.steps.get(b.state.steps.size() - 1).role);

        return b;
    }

    /** */
    @Test void aChangeThatDoesNotShowUpAgainIsLost() throws Exception {
        Bisect b = quietAllTheWayDown(10000);
        assertEquals(Bisect.LOST, b.status, b.reason);
        assertTrue(b.reason.endsWith("was noise"), b.reason);
    }

    /** */
    @Test void aChangeThatShowsUpAgainOnTheSegmentIsInconclusive() throws Exception {
        assertEquals(Bisect.INCONCLUSIVE, quietAllTheWayDown(9750).status);
    }

    /** A bisection of {@code root} that ended without a commit, with nothing measured but the segment. */
    private Bisect inconclusive(Bisector bisector, Job root) throws Exception {
        Bisect b = bisector.start(root.id, "throughput", null);
        bisector.cancel(b.id, true, true);
        Bisect x = db.bisect(b.id);
        x.status = Bisect.INCONCLUSIVE;
        x.finishedAt = Db.now();
        x.state.open.clear();
        x.state.waitRole = null;
        db.updateBisect(x);

        return x;
    }

    /** */
    @Test void aDeepSearchFollowsTheHalfThatCarriesAChangeUnderTheFloor() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9750);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        long id = inconclusive(bisector, root).id;
        throughput(c, 4, 9850); // -1.5 % at a 2 % floor: no half gets a verdict
        Bisect started = bisector.deep(id, 1);
        assertEquals(Bisect.RUNNING, started.status);
        assertTrue(started.state.deep);
        assertEquals(1, started.priority);
        assertNull(started.finishedAt);
        assertThrows(IllegalArgumentException.class, () -> bisector.deep(id, null), "already searching");

        Bisect b = runToTheEnd(bisector, id);
        assertEquals(Bisect.FOUND, b.status, b.reason);
        assertEquals(c.get(4), b.resultSha);
        assertEquals(List.of("left", "right", "left", "right", "left", "right", "confirm", "confirm", "confirm"),
            b.state.steps.stream().map(st -> st.role).toList());
        Bisect.Step last = b.state.steps.get(b.state.steps.size() - 1);
        assertEquals(Bisector.DEEP_RUNS, last.result.runs, "a commit has to hold over all of them");
        assertEquals(Stats.NO_CHANGE, last.result.verdict);
        assertEquals(-1.5, last.result.deltaPct, 0.01);
        assertEquals(List.of("found"), b.state.findings.stream().map(f -> f.kind).toList());
        assertTrue(b.state.steps.get(1).decision.startsWith("→ right: -1.5%"), b.state.steps.get(1).decision);
        assertThrows(IllegalArgumentException.class, () -> bisector.deep(id, null), "only one deep search");
    }

    /** */
    @Test void aDeepSearchTakesTwoRunsOfACommitThatPlainlyMoved() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = runToTheEnd(bisector, bisector.deep(inconclusive(bisector, root).id, null).id);
        assertEquals(c.get(4), b.resultSha);
        assertEquals(2, b.state.steps.get(b.state.steps.size() - 1).result.runs);
    }

    /** */
    @Test void aDeepSearchSearchesBothHalvesWhenEachCarriesAPart() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 1, 9500, 5, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = runToTheEnd(bisector, bisector.deep(inconclusive(bisector, root).id, null).id);
        assertEquals(Bisect.FOUND, b.status, b.reason);
        assertEquals(java.util.Set.of(c.get(1), c.get(5)),
            b.state.findings.stream().map(f -> f.b).collect(java.util.stream.Collectors.toSet()));
    }

    /** */
    @Test void aBranchOfADeepSearchHasToHoldBeforeItIsSearched() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 7, 9750);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        long id = inconclusive(bisector, root).id;
        throughput(c);
        bisector.deep(id, null);
        nextStep(c, 3, 9750); // left half -2.5 %
        nextStep(c, 7, 9750); // right half -2.5 %: over the floor on one run, a search of both

        Bisect limited = db.bisect(id);
        limited.state.maxSteps = limited.state.steps.size(); // a guard that counted one path is used up
        db.updateBisect(limited);
        throughput(c, 2, 9750); // in truth one step, in the left half: the right half measured nothing but noise
        Bisect b = runToTheEnd(bisector, id);
        assertEquals(Bisect.FOUND, b.status, b.reason);
        assertEquals(c.get(2), b.resultSha);
        assertEquals(java.util.Set.of("found", "lost"),
            b.state.findings.stream().map(f -> f.kind).collect(java.util.stream.Collectors.toSet()));
        Bisect.Finding lost = b.state.findings.stream().filter(f -> "lost".equals(f.kind)).findFirst().orElseThrow();
        assertEquals(c.get(3), lost.a);
        assertEquals(c.get(7), lost.b);
        assertEquals(Bisector.DEEP_RUNS, lost.result.runs, "a branch is not dropped on one more run near the floor");
        assertEquals(0, lost.result.deltaPct, 0.01);
        assertEquals(3, b.state.steps.stream().filter(st -> "repeat".equals(st.role) && st.a.equals(c.get(3))).count());
        assertEquals(0, b.state.steps.stream().filter(st -> st.b.equals(c.get(5))).count(),
            "nothing searched inside it");
    }

    /** */
    @Test void aDeepSearchRunsACloseCallAgainAndTakesTheMedian() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 7, 9750);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        long id = inconclusive(bisector, root).id;
        throughput(c);
        bisector.deep(id, null);
        nextStep(c, 3, 9930); // left -0.7 %
        nextStep(c, 7, 9920); // right -0.8 %: neither carries it
        nextStep(c, 3, 9850); // left again -1.5 %: median -1.1 %, too close to -0.8 %
        nextStep(c, 7, 10000); // right again: median -0.4 %, still close
        nextStep(c, 3, 9850); // left a third time: median -1.5 %
        Bisect b = bisector.get(id);
        assertEquals(List.of("left", "right", "left", "right", "left"),
            b.state.steps.stream().map(st -> st.role).toList());
        assertEquals("a close call: one more run", b.state.steps.get(1).decision);
        assertEquals(3, b.state.steps.get(4).result.runs);
        assertEquals(3, b.state.steps.get(4).result.deltas.size(), "every run of the median");
        assertEquals(-1.5, b.state.steps.get(4).result.deltaPct, 0.01);
        assertEquals(c.get(3), b.state.open.get(0).b, "the left half carries it");
        assertEquals(4, b.state.open.get(0).commits);
        bisector.cancel(id, true, false);
    }

    /** */
    @Test void anEvenCountOfRunsHasItsMedianBetweenTheTwoInTheMiddle() {
        assertEquals(0.5, JobService.median(List.of(2.0, -1.0, 1.0, 0.0)), 1e-9);
        assertEquals(-3.0, JobService.median(List.of(-1.0, -5.0)), 1e-9);
        assertEquals(1.0, JobService.median(List.of(5.0, 1.0, -1.0)), 1e-9);
    }

    /** */
    @Test void aDeepSearchMeasuresABrokenRunAgainAndLeavesItOut() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 7, 9750);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        long id = inconclusive(bisector, root).id;
        throughput(c);
        bisector.deep(id, null);
        env.put("FAKE_THR_PHASE", "A1=9000");
        runner.runJob(db.nextQueued());
        env.remove("FAKE_THR_PHASE");
        Job left = db.jobsOfBisect(id).get(0);
        assertTrue(jobs.medianResult(left.shaA, left.shaB, "smoke", "throughput").broken, "all the pair has");
        runner.runJob(db.nextQueued()); // the right half
        Bisect b = bisector.get(id);
        assertTrue(b.state.steps.get(1).decision.startsWith("the phases of this pair disagree"),
            b.state.steps.get(1).decision);
        assertEquals(left.shaB, b.waiting.shaB, "the left half again");
        runner.runJob(db.nextQueued());
        Bisect.Result r = bisector.get(id).state.steps.get(2).result;
        assertEquals(1, r.runs);
        assertEquals(1, r.dropped);
        assertFalse(r.broken);
        assertEquals(0, r.deltaPct, 0.01);
        bisector.cancel(id, true, false);
    }

    /** */
    @Test void aDeepSearchEndsOnTheRangeThatStillMovesWhenNoCommitHolds() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 7, 9750);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        long id = inconclusive(bisector, root).id;
        throughput(c);
        bisector.deep(id, null);
        nextStep(c, 7, 10000); // left: 0
        nextStep(c, 7, 9850); // right -1.5 %: the search goes right, 4 commits
        nextStep(c, 5, 9850); // its left half -1.5 %, 2 commits
        nextStep(c); // its right half: 0
        nextStep(c, 4, 9850); // the first commit -1.5 %
        nextStep(c); // the other: 0
        throughput(c); // the commit does not hold
        Bisect b = runToTheEnd(bisector, id);
        assertEquals(Bisect.SPREAD, b.status, b.reason);
        Bisect.Finding f = b.state.findings.get(0);
        assertEquals(2, f.commits, "the shortest range that still moved, not the commit");
        assertEquals(c.get(5), f.b);
        assertTrue(f.note.contains("does not hold"), f.note);
    }

    /** */
    @Test void aDeepSearchFindsASmallStepThroughNoise() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9700);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        long id = bisector.deep(inconclusive(bisector, root).id, null).id;
        java.util.Random noise = new java.util.Random(42);
        List<String> line = new ArrayList<>(List.of(origin.shas.get(1)));
        line.addAll(c);

        for (Job q; (q = db.nextQueued()) != null; ) {
            boolean step = line.indexOf(q.shaA) <= 4 && line.indexOf(q.shaB) >= 5; // the 5th commit of eight, -2.3 %
            double pct = (step ? -2.3 : 0) + 0.9 * noise.nextGaussian();
            env.put("FAKE_THR_OF", q.shaB + "=" + Math.round(10000 * (1 + pct / 100)));
            runner.runJob(q);
        }

        Bisect b = bisector.get(id);
        assertEquals(Bisect.FOUND, b.status, b.reason);
        assertEquals(c.get(4), b.resultSha);
    }

    /** */
    @Test void theSearchBoxFindsCommitsJobsAndPullRequests() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        assertTrue(jobs.search("  ", 10).isEmpty());
        assertEquals("#/commits/" + c.get(2), jobs.search(c.get(2).substring(0, 12), 10).get(0).href, "a sha");
        assertEquals(1, jobs.search("core 3", 10).size(), "words of a subject");
        assertTrue(jobs.search("100%_", 10).isEmpty(), "% and _ are plain characters");
        assertEquals("#/commits/" + c.get(2), jobs.search("https://github.com/apache/ignite/commit/" + c.get(2), 10).get(0).href);

        assertEquals("#/jobs/" + root.id, jobs.search(String.valueOf(root.id), 10).get(0).href,
            "a number is a job first");
        assertEquals("#/jobs/" + root.id, jobs.search("#" + root.id, 10).get(0).href);
        Bisector bisector = new Bisector(db, mirror, jobs);
        long bisect = bisector.start(root.id, "throughput", null).id;
        assertTrue(jobs.search(String.valueOf(bisect), 10).stream()
            .anyMatch(h -> h.href.equals("#/bisects/" + bisect)));
        bisector.cancel(bisect, true, true);

        String pr = origin.commit("IGNITE-777 Fix it (#13719)", Map.of("modules/core/src/main/P.java", "class P {}"));
        new Watcher(cfg, db, mirror, jobs).poll();

        for (String q : List.of("#13719", "13719", "https://github.com/apache/ignite/pull/13719", "IGNITE-777", "ignite-777"))
            assertEquals("#/commits/" + pr, jobs.search(q, 10).get(0).href, q);
    }

    /** */
    @Test void historyCanLeaveOutTheStepsOfBisections() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        runToTheEnd(bisector, bisector.start(root.id, "throughput", null).id);
        assertTrue(db.jobs("history", 50).size() > 1);
        assertEquals(List.of(root.id),
            db.jobs("history-no-steps", 50).stream().map(j -> j.id).filter(id -> id == root.id).toList());
        assertTrue(db.jobs("history-no-steps", 50).stream().allMatch(j -> j.bisectId == null));
    }

    /** */
    @Test void anEndedBisectionIsDeletedAndItsStepsStay() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect running = bisector.start(root.id, "throughput", null);
        assertThrows(IllegalArgumentException.class,
            () -> bisector.delete(running.id), "an active one is cancelled first");

        Bisect b = runToTheEnd(bisector, running.id);
        List<Long> steps = b.state.steps.stream().filter(st -> !st.reused).map(st -> st.jobId).toList();
        Bisect.Result first = jobs.pairResult(b.state.steps.get(0).a, b.state.steps.get(0).b, "smoke", "throughput");
        bisector.delete(b.id);
        assertNull(db.bisect(b.id));
        assertThrows(java.util.NoSuchElementException.class, () -> bisector.delete(b.id));
        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().allMatch(id -> db.job(id) != null && db.job(id).bisectId == null), "the runs stay");
        assertNotNull(jobs.pairResult(b.state.steps.get(0).a, b.state.steps.get(0).b, "smoke", "throughput"),
            "and still count");
        assertEquals(first.deltaPct,
            jobs.pairResult(b.state.steps.get(0).a, b.state.steps.get(0).b, "smoke", "throughput").deltaPct, 1e-9);
        assertTrue(db.jobs("history-no-steps", 50).stream().noneMatch(j -> steps.contains(j.id)),
            "they do not turn into jobs people queued");
    }

    /** */
    @Test void aDeepSearchIsForABisectionThatFoundNoCommit() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        long ended = inconclusive(bisector, root).id;
        Bisect running = bisector.start(root.id, "throughput", null);
        assertThrows(IllegalArgumentException.class, () -> bisector.deep(running.id, null), "still running");
        assertThrows(IllegalArgumentException.class, () -> bisector.deep(ended, null), "one search per scenario");
        Bisect found = runToTheEnd(bisector, running.id);
        assertEquals(Bisect.FOUND, found.status);
        assertThrows(IllegalArgumentException.class, () -> bisector.deep(found.id, null));
        assertEquals(Bisect.RUNNING, bisector.deep(ended, null).status);
        assertThrows(java.util.NoSuchElementException.class, () -> bisector.deep(404, null));
    }

    /** */
    @Test void cancellingABisectRemovesItsQueuedStepAndKeepsTheFinishedOnes() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = bisector.start(root.id, "throughput", null);
        runner.runJob(db.nextQueued()); // step 1 done, step 2 queued
        Job first = db.jobsOfBisect(b.id).get(0);
        assertEquals(Job.DONE, first.status);
        assertNotNull(db.nextQueued());
        assertThrows(IllegalArgumentException.class,
            () -> jobs.delete(first.id), "a step of an active bisect cannot be deleted");

        Bisect x = bisector.cancel(b.id, true, false);
        assertEquals(Bisect.CANCELLED, x.status);
        assertNull(db.nextQueued(), "the queued step left the queue");
        assertEquals(Job.DONE, db.job(first.id).status, "finished steps stay as ordinary runs");
        assertThrows(IllegalArgumentException.class, () -> bisector.cancel(b.id, true, false));

        Bisect again = bisector.start(root.id, "throughput", null); // reuses the kept step
        assertTrue(again.state.steps.get(0).reused);
        runner.runJob(db.nextQueued());
        bisector.cancel(again.id, true, true);
        assertTrue(db.jobsOfBisect(again.id).isEmpty(), "finished and cancelled steps deleted");
        assertNull(db.nextQueued());
    }

    /** */
    @Test void aStepCancelledFromTheQueueStopsTheBisectUntilResumed() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisector bisector = new Bisector(db, mirror, jobs);
        Bisect b = bisector.start(root.id, "throughput", null);
        jobs.cancel(b.waiting.id);
        Bisect stopped = bisector.get(b.id);
        assertEquals(Bisect.STOPPED, stopped.status);
        assertTrue(stopped.reason.contains("cancelled"), stopped.reason);
        assertNull(db.nextQueued(), "nothing more is queued");

        Bisect resumed = bisector.resume(b.id);
        assertEquals(Bisect.RUNNING, resumed.status);
        assertNotNull(resumed.waiting);
        assertEquals(b.waiting.shaB, resumed.waiting.shaB, "the same step again");
        assertEquals(Bisect.FOUND, runToTheEnd(bisector, b.id).status);
    }

    /** */
    @Test void aStepInterruptedByARestartIsQueuedAgain() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        Bisect b = new Bisector(db, mirror, jobs).start(root.id, "throughput", null);
        Job step = db.job(b.waiting.id);
        step.status = Job.RUNNING; // the controller dies while it runs
        db.updateJob(step);
        db.markInterrupted();

        Bisector restarted = new Bisector(db, mirror, jobs);
        restarted.recover();
        Job again = db.nextQueued();
        assertNotNull(again, "queued again");
        assertTrue(again.id != step.id);
        assertEquals(step.shaB, again.shaB);
        assertEquals(b.id, again.bisectId);
        assertEquals(Bisect.FOUND, runToTheEnd(restarted, b.id).status);
    }

    /** */
    @Test void aComparisonIsBisectedAndHoldsItsBisections() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Bisector bisector = new Bisector(db, mirror, jobs);
        Comparisons comparisons = new Comparisons(db, mirror, jobs, scenarios, bisector);
        Comparison cmp = comparisons.create(origin.shas.get(1), c.get(7), List.of("smoke"), 2);
        Job first = db.jobsOfComparison(cmp.id).get(0);
        runner.runJob(db.job(first.id));
        Job second = jobs.retry(first.id);
        runner.runJob(db.job(second.id));
        assertEquals(8, comparisons.get(cmp.id).commits);

        Bisect cancelled = bisector.start(second.id, "throughput", null);
        assertEquals(Long.valueOf(cmp.id), cancelled.comparisonId);
        assertEquals(2, cancelled.state.root.runs, "the repeats of the scenario count, as on the comparison page");
        assertEquals(List.of(cancelled.id), comparisons.get(cmp.id).bisects.stream().map(x -> x.id).toList());
        assertEquals(Job.DONE, comparisons.get(cmp.id).status, "a comparison does not wait for its bisections");
        assertNotNull(comparisons.get(cmp.id).doneAt, "the end counts its bisections");
        assertNotNull(comparisons.list(5).get(0).doneAt);
        assertTrue(comparisons.get(cmp.id).searching);
        assertTrue(comparisons.list(5).get(0).searching);
        db.setSetting(Settings.WORKER_PAUSED, "true");
        assertNull(comparisons.get(cmp.id).doneAt, "a paused worker has no end to tell");
        db.setSetting(Settings.WORKER_PAUSED, "false");
        assertThrows(IllegalArgumentException.class,
            () -> comparisons.delete(cmp.id), "an active bisection is cancelled first");
        comparisons.cancel(cmp.id);
        assertEquals(Bisect.CANCELLED, bisector.get(cancelled.id).status);
        assertFalse(comparisons.get(cmp.id).searching);
        assertTrue(Retention.usage(db, cfg.runsDir()).get("bisects") > 0, "an ended bisection is a record to limit");
        assertTrue(Retention.usage(db, cfg.runsDir()).get("comparisons") > 0);

        Bisect b = bisector.start(second.id, "throughput", null);
        assertEquals(Bisect.FOUND, runToTheEnd(bisector, b.id).status);
        Comparison found = comparisons.get(cmp.id);
        assertEquals(c.get(4), found.bisects.get(1).resultSha);
        Bisect placed = bisector.get(b.id);
        assertEquals(0, placed.positions.get(b.shaA), "the page draws the search by these");
        assertEquals(5, placed.positions.get(c.get(4)));
        assertEquals(8, placed.positions.get(b.shaB));
        assertEquals(1, found.total, "the steps are not scenarios of the comparison");
        assertEquals(2, found.rows.get(0).runs, "nor its runs");

        comparisons.delete(cmp.id);
        assertNotNull(bisector.get(b.id), "a finished bisection outlives the comparison");
        assertNull(bisector.get(b.id).comparisonId);
    }

    /** */
    private void aged(Job j, Duration age) {
        j.finishedAt = Instant.now().minus(age).toString();
        db.updateJob(j);
    }

    /** */
    @Test void recurringRunsKeepTheirMeasurementsFresh() throws Exception {
        origin.tag("1.0.0");
        origin.commit("after the release", Map.of("modules/core/src/main/N.java", "class N {}"));
        new Watcher(cfg, db, mirror, jobs).poll();
        assertEquals(0, Calibrator.runIfDue(db, jobs, "master"), "off until switched on");

        db.setSetting("calibration.mode", Freshness.KEEP);
        db.setSetting(Settings.WORKER_PAUSED, "true");
        assertEquals(0, Calibrator.runIfDue(db, jobs, "master"), "nothing goes while the stand is closed");
        db.setSetting(Settings.WORKER_PAUSED, "false");

        assertEquals(JobService.MIN_CALIBRATIONS,
            Calibrator.runIfDue(db, jobs, "master"), "no floor of its own yet: what it lacks");
        List<Job> queued = db.jobs("active", 10);
        assertTrue(queued.stream().allMatch(j -> JobService.SRC_CALIBRATION.equals(j.source)
            && j.priority == Bisector.AHEAD));
        assertEquals(0, Calibrator.runIfDue(db, jobs, "master"), "they are on their way");

        for (Job q; (q = db.nextQueued()) != null; )
            runner.runJob(q);

        assertEquals(0, Calibrator.runIfDue(db, jobs, "master"), "fresh");

        queued.forEach(j -> aged(db.job(j.id), Duration.ofDays(8)));
        Job busy = jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, null);
        assertEquals(0, Calibrator.runIfDue(db, jobs, "master"), "stale waits for a quiet stand");
        jobs.cancel(busy.id);
        aged(db.job(busy.id), Duration.ofDays(8));
        assertEquals(1, Calibrator.runIfDue(db, jobs, "master"), "and goes on one");
        Job stale = db.nextQueued();
        assertEquals(0, stale.priority, "behind everything else");
        jobs.cancel(stale.id);
        aged(db.job(stale.id), Duration.ofDays(8));

        queued.forEach(j -> aged(db.job(j.id), Duration.ofDays(15)));
        busy = jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, null);
        assertEquals(1, Calibrator.runIfDue(db, jobs, "master"), "overdue goes on a busy stand");

        for (Job j : db.jobs("active", 10)) {
            jobs.cancel(j.id);
            aged(db.job(j.id), Duration.ofDays(1));
        }

        db.setSetting("calibration.mode", Freshness.OFF);
        db.setSetting("master.mode", Freshness.KEEP);
        assertEquals(1, Calibrator.runIfDue(db, jobs, "master"), "never measured: at once");
        assertEquals(0, Calibrator.runIfDue(db, jobs, "master"), "it is on its way");
        Job master = db.jobs("active", 1).get(0);
        runner.runJob(db.job(master.id));
        aged(db.job(master.id), Duration.ofDays(30));
        assertEquals(0, Calibrator.runIfDue(db, jobs, "master"), "nothing new on the branch: nothing to measure");

        origin.commit("one more", Map.of("modules/core/src/main/O.java", "class O {}"));
        new Watcher(cfg, db, mirror, jobs).poll();
        assertEquals(1, Calibrator.runIfDue(db, jobs, "master"), "a new head on an old measurement");
    }

    /** */
    @Test void theStandFollowsAnotherBranchOnlyWithAnEmptyQueue() throws Exception {
        origin.branch("other", origin.shas.get(0));
        Watcher watcher = new Watcher(cfg, db, mirror, jobs);
        watcher.poll();
        Job j = jobs.enqueue(Job.ABBA, "master~1", "master", "smoke", 1, null, null);
        assertThrows(IllegalArgumentException.class,
            () -> watcher.follow(cfg.repoUrl, "other"), "a queued job would lose its commits");
        jobs.cancel(j.id);
        assertThrows(IllegalArgumentException.class, () -> watcher.follow(cfg.repoUrl, "nope"));
        assertEquals("master", mirror.branch());

        watcher.follow(cfg.repoUrl, "other");
        assertEquals("other", cfg.branch);
        assertEquals("other", mirror.branch());
        assertEquals("", db.setting(Settings.WATCH_WATERMARK, "x"), "it starts from the new head");
    }

    /** */
    @Test void theLimitOfBisectionsCountsOnlyEndedOnes() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Bisector bisector = new Bisector(db, mirror, jobs);
        runToTheEnd(bisector, bisector.start(measuredSegment(origin.shas.get(1), c.get(5)).id, "throughput", null).id);
        assertEquals(1, db.endedBisects());
        Bisect active = bisector.start(measuredSegment(origin.shas.get(0), c.get(6)).id, "throughput", null);
        assertTrue(active.active());
        assertEquals(1, db.endedBisects(), "the running one is not counted");
        db.setSetting(Settings.RETENTION_KEEP_BISECTS, "1");
        assertEquals(0, bisector.prune(), "and goes nowhere, so the page has nothing to warn about");
    }

    /** */
    @Test void oldComparisonsAndBisectionsGoBeyondTheLimit() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Bisector bisector = new Bisector(db, mirror, jobs);
        Comparisons comparisons = new Comparisons(db, mirror, jobs, scenarios, bisector);
        Comparison old = comparisons.create(origin.shas.get(1), c.get(6), List.of("smoke"), 2);
        runner.runJob(db.jobsOfComparison(old.id).get(0));
        Comparison fresh = comparisons.create(origin.shas.get(1), c.get(7), List.of("smoke"), 2);
        runner.runJob(db.jobsOfComparison(fresh.id).get(0));
        assertEquals(0, comparisons.prune(), "nothing goes until a limit is set");

        Bisect first = runToTheEnd(bisector,
            bisector.start(db.jobsOfComparison(old.id).get(0).id, "throughput", null).id);
        Bisect second = runToTheEnd(bisector,
            bisector.start(measuredSegment(origin.shas.get(1), c.get(5)).id, "throughput", null).id);
        Job step = db.jobsOfBisect(first.id).get(0);
        db.setSetting(Settings.RETENTION_KEEP_BISECTS, "1");
        assertEquals(1, bisector.prune());
        assertNull(db.bisect(first.id));
        assertNotNull(db.bisect(second.id));
        assertNull(db.job(step.id).bisectId, "the steps stay as ordinary runs");

        db.setSetting(Settings.RETENTION_KEEP_COMPARISONS, "1");
        assertEquals(1, comparisons.prune());
        assertNull(db.comparison(old.id));
        assertTrue(db.jobsOfComparison(old.id).isEmpty());
        assertNotNull(db.comparison(fresh.id));

        Comparison queued = comparisons.create(origin.shas.get(1), c.get(5), List.of("smoke"), 2);
        Comparison waiting = comparisons.create(origin.shas.get(1), c.get(4), List.of("smoke"), 2);
        assertEquals(1, comparisons.prune(), "only the ended one goes");
        assertNotNull(db.comparison(queued.id), "an active comparison stays");
        assertNotNull(db.comparison(waiting.id));
        assertNull(db.comparison(fresh.id));
    }

    /** */
    @Test void attentionChecksMasterAgainstWhatWasFound() throws Exception {
        origin.tag("1.0.0");
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        Bisector bisector = new Bisector(db, mirror, jobs);
        Attention attention = new Attention(db, mirror, jobs, scenarios, bisector);
        jobs.queueRelease("smoke", 1);
        runner.runJob(db.nextQueued());
        Attention.Line line = line(attention, "throughput");
        assertEquals(Attention.UNEXPLORED, line.status);
        assertNull(jobs.releaseJob("smoke", c.get(0)), "a run against another release does not count");
        assertEquals(-10, line.deltaPct, 0.01);

        Job half = measuredSegment(c.get(2), c.get(6));
        line = line(attention, "throughput");
        assertEquals(Attention.NARROWED, line.status,
            "a run of the segment says where it happened, whatever it was started for");
        assertEquals(4, line.narrowSpan);
        assertEquals(half.id, line.jobId, "a search starts from the shortest segment");
        assertEquals(4, line.rangeCommits);
        assertNull(attention.view().rows.get(0).activeBisect);

        Bisect b = bisector.start(line.jobId, "throughput", null);
        assertEquals(Bisect.RUNNING, line(attention, "throughput").status);
        assertEquals(Long.valueOf(b.id), attention.view().rows.get(0).activeBisect,
            "a second bisection of the scenario has to wait");
        assertTrue(bisector.get(b.id).etaSec > 0);
        assertEquals(1, attention.view().rows.size());

        runToTheEnd(bisector, b.id);
        line = line(attention, "throughput");
        assertEquals(Attention.EXPLAINED, line.status, "the commit found adds up to what master shows");
        assertEquals(List.of(c.get(4)), line.commits.stream().map(f -> f.sha).toList());
        assertEquals(Long.valueOf(b.id), line.commits.get(0).bisectId);
        assertEquals(0, line.restPct, 0.5);

        String next = origin.commit("core 9", Map.of("modules/core/src/main/B9.java", "class B9 {}"));
        new Watcher(cfg, db, mirror, jobs).poll();
        env.put("FAKE_THR_OF", env.get("FAKE_THR_OF") + " " + next + "=8000");
        measuredSegment(c.get(7), next);
        Attention.Line fresh = attention.view().rows.get(0).lines.get(0);
        assertEquals(Attention.NEW, fresh.status, "newer than the master run: nothing explains it yet");
        assertEquals(next, fresh.sha);
    }

    /** */
    @Test void aLineSaysThatItsSearchWasDeepAlready() throws Exception {
        origin.tag("1.0.0");
        List<String> c = eightCommits();
        throughput(c, 4, 9750);
        Bisector bisector = new Bisector(db, mirror, jobs);
        Attention attention = new Attention(db, mirror, jobs, scenarios, bisector);
        jobs.queueRelease("smoke", 1);
        runner.runJob(db.nextQueued());
        Job root = measuredSegment(origin.shas.get(1), c.get(7));
        long id = inconclusive(bisector, root).id;
        Attention.Line line = line(attention, "throughput");
        assertEquals(Bisect.INCONCLUSIVE, line.status);
        assertFalse(line.deep, "a deep search can still start");
        bisector.deep(id, null);
        assertTrue(line(attention, "throughput").deep);
    }

    /** */
    private Attention.Line line(Attention attention, String metric) throws Exception {
        return attention.view().rows.get(0).lines.stream().filter(l -> metric.equals(l.metric))
            .findFirst().orElseThrow();
    }

    /** */
    @Test void aJobWaitsForCheckToLeaveTheHosts() throws Exception {
        assertTrue(runner.stands().tryHold());
        Job j = jobs.enqueue(Job.SINGLE, origin.shas.get(1), null, "smoke", 1, JobService.SRC_MANUAL, null);
        runner.start();

        try {
            Thread.sleep(1500);
            assertEquals(Job.QUEUED, db.job(j.id).status, "the stand is held");
            runner.stands().release();
            runner.wake();
            long deadline = System.currentTimeMillis() + 30_000;

            while (!Job.DONE.equals(db.job(j.id).status) && System.currentTimeMillis() < deadline)
                Thread.sleep(100);

            assertEquals(Job.DONE, db.job(j.id).status);
            // the hosts go back after the job is cleaned up (sweep, next bisect step, retention), a moment after DONE
            boolean held = runner.stands().tryHold();

            while (!held && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                held = runner.stands().tryHold();
            }

            assertTrue(held, "the job let go of the hosts");
            runner.stands().release();
        }
        finally {
            runner.shutdown();
        }
    }

    /** */
    @Test void runningBisectionsWaitForEachOther() throws Exception {
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        scenarios = new Scenarios(List.of(scenarios.get("smoke"), Scenarios.Info.of("other", 1, 2)));
        jobs = new JobService(cfg, db, mirror, scenarios);
        runner = new JobRunner(cfg, db, builder, scenarios, jobs, mirror);
        Bisector bisector = new Bisector(db, mirror, jobs);
        assertNull(bisector.doneAt());
        Job one = jobs.enqueueSegment(origin.shas.get(1), c.get(7), 8, "smoke", 1, JobService.SRC_MANUAL);
        runner.runJob(db.job(one.id));
        Job two = jobs.enqueueSegment(origin.shas.get(1), c.get(7), 8, "other", 1, JobService.SRC_MANUAL);
        runner.runJob(db.job(two.id));

        Bisect a = bisector.start(one.id, "throughput", null);
        long alone = bisector.get(a.id).etaSec;
        Bisect b = bisector.start(two.id, "throughput", null);
        long together = bisector.get(a.id).etaSec;
        assertTrue(together > alone, together + " vs " + alone);
        assertEquals(together, bisector.get(b.id).etaSec, 0.5 * together, "both take turns");
        assertNotNull(bisector.doneAt());
    }

    // ---------------------------------------------------------------- UI smoke

    /**
     * Every page of the UI renders against the API (node runs app.js with a stand-in for the browser): a finished
     * segment with its finished bisection, a comparison with one too, and a second bisection
     * whose step waits in the queue.
     */
    @Test void everyPageOfTheUiRendersAgainstTheApi() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node is required");
        Assumptions.assumeTrue(canListenLocally(), "this environment does not allow a local port");
        origin.tag("1.0.0");
        List<String> c = eightCommits();
        throughput(c, 4, 9000);
        jobs.queueRelease("smoke", 1);
        runner.runJob(db.nextQueued());
        Bisector bisector = new Bisector(db, mirror, jobs);
        runToTheEnd(bisector, bisector.start(measuredSegment(origin.shas.get(1), c.get(7)).id, "throughput", null).id);
        Comparisons comparisons = new Comparisons(db, mirror, jobs, scenarios, bisector);
        Comparison cmp = comparisons.create(origin.shas.get(0), c.get(7), List.of("smoke"), 2);
        runner.runJob(db.jobsOfComparison(cmp.id).get(0));
        runToTheEnd(bisector, bisector.start(db.jobsOfComparison(cmp.id).get(0).id, "throughput", null).id);
        bisector.start(measuredSegment(origin.shas.get(1), c.get(5)).id, "throughput", null);
        comparisons.create(origin.shas.get(0), c.get(6), List.of("smoke"), 2);
        cfg.bindHost = "127.0.0.1";
        cfg.port = 0;
        Api api = new Api(cfg, db, jobs, runner, new Watcher(cfg, db, mirror, jobs),
            scenarios, mirror, new Notifier(db), bisector, comparisons);
        api.start();

        try {
            String script = Path.of(getClass().getResource("/ui-smoke.mjs").toURI()).toString();
            String appJs = getClass().getResource("/web/app.js").toURI().toString();
            Proc.Result r = Proc.run(List.of("node", script, "http://127.0.0.1:" + api.port(), appJs), null, null, Duration.ofSeconds(60));
            assertTrue(r.ok(), r.out);
        }
        finally {
            api.stop();
        }
    }

    /** */
    private static boolean nodeAvailable() {
        try {
            return Proc.run(List.of("node", "--version"), null, null, Duration.ofSeconds(10)).ok();
        }
        catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** */
    private static boolean canListenLocally() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }
}
