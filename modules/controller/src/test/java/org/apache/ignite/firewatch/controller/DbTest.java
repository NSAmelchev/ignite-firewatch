package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Db}. */
class DbTest {
    /** */
    @TempDir Path tmp;

    /** */
    private static Job job(String type, String shaA, String shaB, int prio) {
        Job j = new Job();
        j.type = type;
        j.source = "manual";
        j.scenario = "smoke";
        j.priority = prio;
        j.refA = "a";
        j.shaA = shaA;
        j.refB = shaB == null ? null : "b";
        j.shaB = shaB;
        j.label = "test";

        return j;
    }

    /** */
    @Test void settingsAndSeed() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            assertEquals("x", db.setting("k", "x"));
            db.setSetting("k", "v");
            db.setSettingIfAbsent("k", "ignored");
            assertEquals("v", db.setting("k", null));
            Settings.seed(db, new Config());
            assertEquals("false", db.setting(Settings.AUTORUN_ENABLED, null));
            assertTrue(db.settings().containsKey(Settings.AUTORUN_PATH_FILTER));
        }
    }

    /** */
    @Test void queueOrderAndLifecycle() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Job low = db.insertJob(job(Job.ABBA, "a".repeat(40), "b".repeat(40), 0));
            Job high = db.insertJob(job(Job.SINGLE, "c".repeat(40), null, 5));
            assertEquals(high.id, db.nextQueued().id, "higher priority first");
            assertTrue(db.hasActiveJob(Job.ABBA, "a".repeat(40), "b".repeat(40), "smoke"));
            assertFalse(db.hasActiveJob(Job.ABBA, "a".repeat(40), "b".repeat(40), "putget"));

            high.status = Job.RUNNING;
            high.startedAt = Db.now();
            high.stage = "phase S";
            db.updateJob(high);
            assertEquals(high.id, db.runningJob().id);
            assertEquals(low.id, db.nextQueued().id);
            List<Job> active = db.jobs("active", 10);
            assertEquals(2, active.size());
            assertEquals(Job.RUNNING, active.get(0).status, "running job listed first");

            assertTrue(db.requestCancel(high.id));
            assertTrue(db.cancelRequested(high.id));
            assertEquals(Job.RUNNING, db.job(high.id).status,
                "a running job is cancelled by the worker, not the queue");
            assertTrue(db.requestCancel(low.id));
            assertEquals(Job.CANCELLED, db.job(low.id).status, "a queued job is cancelled right away");
            assertFalse(db.requestCancel(low.id));
            assertFalse(db.requestCancel(12345));

            Run r = new Run();
            r.jobId = high.id;
            r.phase = "S";
            r.sha = high.shaA;
            r.status = Run.RUNNING;
            r.dir = "runs/" + high.id + "/S";
            db.insertRun(r);
            assertEquals(List.of(high.id), db.markInterrupted());
            assertEquals(1, db.failRunningRuns());
            assertEquals(Job.INTERRUPTED, db.job(high.id).status);
            assertEquals(Run.FAILED, db.runs(high.id).get(0).status);
            assertNull(db.runningJob());
            assertEquals(1, db.jobs("history", 10).stream().filter(j -> j.id == high.id).count());
            assertEquals(2, db.jobCounts().size());
            assertFalse(db.deleteJob(12345));
            assertTrue(db.deleteJob(high.id), "a finished (interrupted) job can be deleted");
            assertNull(db.job(high.id));
            assertTrue(db.runs(high.id).isEmpty(), "its runs go with it");
        }
    }

    /** */
    @Test void upsertCommitsInOneTransaction() throws Exception {
        try (Db db = Db.open(tmp.resolve("c.db"))) {
            List<CommitInfo> list = new java.util.ArrayList<>();

            for (int i = 0; i < 300; i++) {
                CommitInfo ci = new CommitInfo();
                ci.sha = String.format("%040d", i);
                ci.subject = "s" + i;
                ci.seq = i;
                ci.branch = "master";
                list.add(ci);
            }

            db.upsertCommits(list);
            list.get(0).subject = "changed";
            db.upsertCommits(list.subList(0, 1));
            assertEquals(300, db.commits("master", 1000).size());
            assertEquals("changed", db.commit(String.format("%040d", 0)).subject);
            db.setSetting("k", "v"); // autocommit is back on
            assertEquals("v", db.setting("k", null));
        }
    }

    /** */
    @Test void runsAndJobsForShas() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            String a = "1".repeat(40), b = "2".repeat(40), c = "3".repeat(40);
            Job abba = db.insertJob(job(Job.ABBA, a, b, 0));
            Job single = db.insertJob(job(Job.SINGLE, c, null, 0));
            Run r = new Run();
            r.jobId = abba.id;
            r.phase = "A1";
            r.sha = a;
            r.status = Run.RUNNING;
            r.dir = "runs/1/A1";
            db.insertRun(r);
            r.status = Run.OK;
            r.throughput = 12345.6;
            r.p99Us = 99.5;
            r.errors = 0L;
            db.updateRun(r);
            Run back = db.run(r.id);
            assertEquals(12345.6, back.throughput, 1e-9);
            assertEquals(99.5, back.p99Us, 1e-9);
            assertNull(back.p50Us);
            assertEquals(0L, back.errors);

            List<Job> forB = db.jobsForShas(List.of(b), null);
            assertEquals(1, forB.size());
            assertEquals(abba.id, forB.get(0).id);
            assertEquals(0, db.jobsForShas(List.of(a), null).size(), "A side of an ABBA job is not 'measured'");
            assertEquals(single.id, db.jobsForShas(List.of(c), "smoke").get(0).id);
            assertEquals(0, db.jobsForShas(List.of(c), "putget").size());
        }
    }

    /** */
    @Test void commitsKeepSeqAndBranchOnPartialUpsert() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            CommitInfo ci = new CommitInfo();
            ci.sha = "9".repeat(40);
            ci.subject = "first";
            ci.seq = 10;
            ci.branch = "master";
            db.upsertCommit(ci);
            CommitInfo partial = new CommitInfo();
            partial.sha = ci.sha;
            partial.subject = "first (edited)";
            db.upsertCommit(partial);
            CommitInfo back = db.commit(ci.sha);
            assertEquals(10, back.seq);
            assertEquals("master", back.branch);
            assertEquals("first (edited)", back.subject);
            assertEquals(1, db.commits("master", 10).size());
            assertNotNull(db.commits("master", 10).get(0).seq);
        }
    }

    /** */
    @Test void buildsUpsertAndInterrupted() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Build b = new Build();
            b.sha = "a".repeat(40);
            b.status = Build.BUILDING;
            b.startedAt = Db.now();
            db.saveBuild(b);
            assertEquals(1, db.failInterruptedBuilds());
            assertEquals(Build.FAILED, db.build(b.sha).status);
            b.status = Build.OK;
            b.sizeBytes = 17L << 20;
            db.saveBuild(b);
            assertEquals(Build.OK, db.build(b.sha).status);
            assertEquals(17L << 20, db.builds(10).get(0).sizeBytes);
            db.deleteBuild(b.sha);
            assertNull(db.build(b.sha));
        }
    }
}
