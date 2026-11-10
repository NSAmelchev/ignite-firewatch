package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Retention}. */
class RetentionTest {
    /** */
    @TempDir Path tmp;

    /** */
    @Test void keepsJfrOnlyForTheNewestJobs() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Path[] jfr = new Path[3];

            for (int i = 0; i < 3; i++) {
                Job j = new Job();
                j.type = Job.SINGLE;
                j.source = "manual";
                j.scenario = "smoke";
                j.shaA = "a".repeat(40);
                j.label = "j" + i;
                db.insertJob(j);
                j.status = Job.DONE;
                db.updateJob(j);
                Run r = new Run();
                r.jobId = j.id;
                r.phase = "S";
                r.sha = j.shaA;
                r.status = Run.OK;
                r.dir = "runs/" + j.id + "/S";
                db.insertRun(r);
                Path dir = Files.createDirectories(tmp.resolve(r.dir));
                jfr[i] = Files.writeString(dir.resolve("node-0.jfr"), "x".repeat(100));
                Files.writeString(dir.resolve("result.json"), "{}");
                Files.writeString(Files.createDirectories(dir.resolve("work")).resolve("wal.bin"), "y".repeat(50));
            }

            long freed = Retention.pruneHeavyFiles(db, tmp, 2);
            assertEquals(150, freed);
            assertFalse(Files.exists(jfr[0]), "oldest job loses its JFR");
            assertTrue(Files.exists(jfr[1]) && Files.exists(jfr[2]));
            assertFalse(Files.exists(jfr[0].resolveSibling("work")), "and the work directory a failed phase left");
            assertTrue(Files.exists(jfr[1].resolveSibling("work")));
            assertTrue(Files.exists(jfr[0].resolveSibling("result.json")), "results stay");
            assertEquals(0, Retention.pruneHeavyFiles(db, tmp, 2));
        }
    }

    /** */
    @Test void aFloodedLogKeepsItsStartAndItsEnd() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("runs/1/B1"));
        byte[] body = new byte[(int)Retention.LOG_LIMIT + (1 << 20)];
        java.util.Arrays.fill(body, (byte)'x');
        byte[] start = "HARNESS_READY node=0 startMs=900\n".getBytes(), end = "\nIgnite node stopped OK\n".getBytes();
        System.arraycopy(start, 0, body, 0, start.length);
        System.arraycopy(end, 0, body, body.length - end.length, end.length);
        Path log = Files.write(dir.resolve("node-0.log"), body);
        Path small = Files.writeString(dir.resolve("loader.log"), "sec 1 measure ops=10\n");
        Path jfr = Files.write(dir.resolve("node-0.jfr"), body);

        long freed = Retention.cutLogs(dir);
        String text = Files.readString(log);
        assertTrue(text.startsWith("HARNESS_READY node=0 startMs=900"), "the start stays");
        assertTrue(text.endsWith("Ignite node stopped OK\n"), "the end stays");
        assertTrue(text.contains(" cut: the log was over 20.0 MB"),
            text.substring((int)Retention.LOG_KEEP, (int)Retention.LOG_KEEP + 80));
        assertEquals(body.length - Files.size(log), freed);
        assertTrue(Files.size(log) < 2 * Retention.LOG_KEEP + 200);
        assertEquals("sec 1 measure ops=10\n", Files.readString(small), "a small log stays as it is");
        assertEquals(body.length, Files.size(jfr), "only logs are cut");
        assertEquals(0, Retention.cutLogs(dir), "once");
    }

    /** */
    @Test void usageSaysWhatTheLimitsActOn() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Path runs = tmp.resolve("runs");
            assertEquals(0L, Retention.usage(db, runs).get("runs"), "no runs yet");

            for (Long comparison : new Long[] {null, 7L}) {
                Job j = new Job();
                j.type = Job.SINGLE;
                j.source = "manual";
                j.scenario = "smoke";
                j.shaA = "a".repeat(40);
                j.label = "j";
                j.comparisonId = comparison;
                db.insertJob(j);
                Path dir = Files.createDirectories(runs.resolve(j.id + "/S"));
                Files.writeString(dir.resolve("node-0.jfr"), "x".repeat(100));
                Files.writeString(dir.resolve("result.json"), "{}");
                Files.writeString(Files.createDirectories(dir.resolve("work")).resolve("wal.bin"), "y".repeat(50));
            }

            var usage = Retention.usage(db, runs);
            assertEquals(304L, usage.get("runs"));
            assertEquals(300L, usage.get("heavy"));
            assertEquals(152L, usage.get("comparisons"), "only what goes with a comparison");
            assertEquals(0L, usage.get("bisects"));
        }
    }
}
