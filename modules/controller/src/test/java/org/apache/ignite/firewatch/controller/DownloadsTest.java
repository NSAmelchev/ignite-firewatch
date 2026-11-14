package org.apache.ignite.firewatch.controller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Downloads}. */
class DownloadsTest {
    /** */
    private static final String A = "a".repeat(40);

    /** */
    private static final String B = "b".repeat(40);

    /** */
    @TempDir Path tmp;

    /** */
    Config cfg;

    /** */
    Db db;

    /** */
    Downloads dl;

    /** */
    Job job;

    /** */
    @BeforeEach void setUp() throws Exception {
        cfg = new Config();
        cfg.dataDir = tmp.resolve("data");
        cfg.cacheDir = tmp.resolve("cache");
        db = Db.open(tmp.resolve("t.db"));
        dl = new Downloads(cfg, db, new JobService(cfg, db, null, new Scenarios(List.of(Scenarios.Info.of("smoke", 1,
            1)))));
        job = new Job();
        job.type = Job.ABBA;
        job.source = "manual";
        job.scenario = "smoke";
        job.shaA = A;
        job.shaB = B;
        job.label = "j";
        db.insertJob(job);

        for (String phase : List.of("A1", "B1")) {
            Run r = new Run();
            r.jobId = job.id;
            r.phase = phase;
            r.sha = phase.startsWith("A") ? A : B;
            r.status = Run.OK;
            r.dir = "runs/" + job.id + "/" + phase;
            db.insertRun(r);
            Path dir = Files.createDirectories(cfg.dataDir.resolve(r.dir));
            Files.writeString(dir.resolve("node-0.log"), "log of " + phase);
            Files.writeString(dir.resolve("result.json"), "{}");
            Files.writeString(dir.resolve("node-0.jfr"), "x".repeat(100));
            Files.writeString(Files.createDirectories(dir.resolve("work/node-0")).resolve("wal.bin"), "y".repeat(50));
        }

        build(A, true);
        build(B, false);
    }

    /** */
    @AfterEach void tearDown() throws Exception {
        db.close();
    }

    /** A build of A with its jars in the cache; B failed, with a log only. */
    private void build(String sha, boolean ok) throws Exception {
        Path dir = Files.createDirectories(cfg.artifactsDir().resolve(sha).resolve("libs"));
        Build b = new Build();
        b.sha = sha;
        b.status = ok ? Build.OK : Build.FAILED;
        b.startedAt = Db.now();
        b.libsDir = dir.toString();
        b.logFile = Files.writeString(dir.resolveSibling("build.log"), "mvn says " + sha.charAt(0)).toString();

        if (ok) {
            Files.writeString(dir.resolve("ignite-core-2.99.0.jar"), "core");
            Files.writeString(dir.resolve("cache-api-1.1.1.jar"), "api");
            Files.writeString(dir.resolve(Builder.OK_MARKER), "now");
            b.igniteVersion = "2.99.0";
            b.sizeBytes = 7L;
        }

        db.saveBuild(b);
    }

    /** */
    private static List<String> entries(Router.Response r) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        r.stream.write(out);
        List<String> names = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; )
                names.add(e.getName() + "=" + new String(zip.readAllBytes()));
        }

        return names;
    }

    /** */
    @SuppressWarnings("unchecked")
    @Test void theMenuSaysWhatThereIsAndHowBig() throws Exception {
        Map<String, Object> d = dl.of(job.id);
        Map<String, Object> zip = (Map<String, Object>)d.get("zip");
        long logs = "mvn says a".length() + "mvn says b".length();
        assertEquals(2 * ("log of A1".length() + 2) + logs, zip.get("bytes"), "logs, results and build logs; no work dir");
        assertEquals((long)zip.get("bytes") + 200, zip.get("jfrBytes"));
        assertEquals(2, zip.get("phases"));
        List<Map<String, Object>> builds = (List<Map<String, Object>>)d.get("builds");
        assertEquals(List.of("A", "B"), builds.stream().map(b -> b.get("side")).toList());
        assertEquals(7L, builds.get(0).get("jarsBytes"));
        assertEquals("failed", builds.get(1).get("status"));
        assertNull(builds.get(1).get("jarsBytes"), "a failed build has no jars");
        assertEquals((long)"mvn says b".length(), builds.get(1).get("logBytes"));
    }

    /** */
    @Test void theZipOfAJobHasItsPhasesAndBuildLogsButNotTheWorkOfTheNodes() throws Exception {
        Router.Response r = dl.jobZip(job.id, false);
        assertEquals("attachment; filename=\"job" + job.id + "-smoke.zip\"", r.headers.get("Content-Disposition"));
        String root = "job" + job.id + "/";
        assertEquals(List.of(root + "A1/node-0.log=log of A1", root + "A1/result.json={}", root + "B1/node-0.log=log of B1",
            root + "B1/result.json={}", root + "build-A-aaaaaaaaaa.log=mvn says a", root + "build-B-bbbbbbbbbb.log=mvn says b"),
            entries(r));
        List<String> withJfr = entries(dl.jobZip(job.id, true));
        assertTrue(withJfr.contains(root + "A1/node-0.jfr=" + "x".repeat(100)), withJfr.toString());
        assertTrue(withJfr.stream().noneMatch(e -> e.contains("/work/")));
    }

    /** */
    @Test void theJarsOfABuildAndItsLog() throws Exception {
        Router.Response jars = dl.buildJars(A);
        assertEquals("attachment; filename=\"ignite-2.99.0-aaaaaaaaaa.zip\"", jars.headers.get("Content-Disposition"));
        assertEquals(List.of("ignite-2.99.0-aaaaaaaaaa/cache-api-1.1.1.jar=api", "ignite-2.99.0-aaaaaaaaaa/ignite-core-2.99.0.jar=core"),
            entries(jars), "the jars only: no marker");
        assertEquals("mvn says b", Files.readString(dl.buildLog(B).file));

        NoSuchElementException failed = assertThrows(NoSuchElementException.class, () -> dl.buildJars(B));
        assertTrue(failed.getMessage().contains("did not build"), failed.getMessage());
        Files.delete(cfg.artifactsDir().resolve(A).resolve("libs").resolve(Builder.OK_MARKER));
        NoSuchElementException evicted = assertThrows(NoSuchElementException.class, () -> dl.buildJars(A));
        assertTrue(evicted.getMessage().contains("evicted"), evicted.getMessage());
        assertThrows(IllegalArgumentException.class, () -> dl.buildLog("../../etc"));
    }

    /** A log the database names outside the build cache is not served. */
    @Test void aLogOutsideTheCacheIsNotABuildLog() throws Exception {
        Build b = db.build(A);
        b.logFile = Files.writeString(tmp.resolve("secret.txt"), "no").toString();
        db.saveBuild(b);
        assertThrows(NoSuchElementException.class, () -> dl.buildLog(A));
        assertFalse(dl.build(A, b).get("logBytes") != null);
    }
}
