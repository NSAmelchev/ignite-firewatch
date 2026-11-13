package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link StandService}. */
class StandServiceTest {
    /** */
    @TempDir Path tmp;

    /** */
    private static Stand.Host host(String address, String role) {
        Stand.Host h = new Stand.Host();
        h.address = address;
        h.user = "bench";
        h.role = role;
        h.javaHome = "/jdk";
        h.workDir = "/opt/firewatch";

        return h;
    }

    /** */
    private static Stand remote() {
        Stand s = new Stand();
        s.mode = Stand.REMOTE;
        s.hosts = List.of(host("a", Stand.SERVERS), host("b", Stand.LOADERS));

        return s;
    }

    /** */
    private static String report(boolean ok) {
        return "{\"ok\": " + ok + ", \"hosts\": [{\"address\": \"a\", \"role\": \"servers\", "
            + "\"facts\": {\"memMb\": 32768, \"cpus\": 16},"
            + " \"fingerprint\": {\"cpus\": \"16\"}}, "
            + "{\"address\": \"b\", \"role\": \"loaders\", \"facts\": {\"memMb\": 16384, \"cpus\": 8},"
            + " \"fingerprint\": {\"cpus\": \"8\"}}]}";
    }

    /** A harness that answers Check with {@code output}. */
    private Config config(String output) throws Exception {
        Path script = tmp.resolve("harness.sh");
        Files.writeString(script, "#!/bin/bash\necho banner\necho " + "'" + output + "'\n");
        script.toFile().setExecutable(true);
        Config c = new Config();
        c.dataDir = tmp.resolve("data");
        c.harnessCommand = List.of("bash", script.toString());

        return c;
    }

    /** */
    private static Job job() {
        Job j = new Job();
        j.type = Job.SINGLE;
        j.source = "manual";
        j.scenario = "smoke";
        j.refA = "a";
        j.shaA = "a".repeat(40);
        j.label = "test";

        return j;
    }

    /** */
    private static Scenarios threeServers() {
        Scenarios.Info i = Scenarios.Info.of("big", 1, 1);
        i.servers = 3;

        return new Scenarios(List.of(i));
    }

    /** */
    @Test void aPassingCheckKeepsTheFingerprints() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            StandService s = new StandService(config(report(true)), db, threeServers());
            Stand stand = remote();
            assertEquals("a", stand.unchecked(db), "nothing is checked yet");
            var r = s.check(stand);
            assertTrue(r.path("ok").asBoolean());
            assertTrue(r.has("recommended"));
            assertEquals(null, stand.unchecked(db));
            assertEquals("16", Json.tree(db.setting(Stand.FINGERPRINTS, "{}")).path("a").path("cpus").asText());
            assertTrue(s.view().get("check") != null);
        }
    }

    /** */
    @Test void aFailedCheckLeavesTheHostsUnchecked() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            StandService s = new StandService(config(report(false)), db, threeServers());
            Stand stand = remote();
            assertFalse(s.check(stand).path("ok").asBoolean());
            assertEquals("a", stand.unchecked(db));
        }
    }

    /** */
    @Test void aFrozenStandKeepsWhatItWasCheckedOn() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            db.setSetting(Stand.FINGERPRINTS, "{\"a\": {\"cpus\": \"4\"}, \"b\": {\"cpus\": \"4\"}}");
            Job j = db.insertJob(job());
            Run r = new Run();
            r.jobId = j.id;
            r.phase = "S";
            r.sha = j.shaA;
            r.status = Run.OK;
            r.dir = "runs/1/S";
            db.insertRun(r);
            j.status = Job.DONE;
            db.updateJob(j);
            new StandService(config(report(true)), db, threeServers()).check(remote());
            assertEquals("4", Json.tree(db.setting(Stand.FINGERPRINTS, "{}")).path("a").path("cpus").asText());
        }
    }

    /** */
    @Test void checkWaitsForAnEmptyQueueUnlessTheStandIsClosedForMaintenance() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            db.insertJob(job());
            StandService s = new StandService(config(report(true)), db, threeServers());
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.check(remote()));
            assertTrue(e.getMessage().contains("close the stand for maintenance"), e.getMessage());
            db.setSetting(Settings.WORKER_PAUSED, "true");
            assertTrue(s.check(remote()).path("ok").asBoolean(), "closed: nothing of the queue starts meanwhile");
            assertTrue(s.clean(), "and the hosts can be cleaned");
        }
    }

    /** */
    @Test void oneUserOfTheHostsAtATime() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            StandService s = new StandService(config(report(true)), db, threeServers());
            assertTrue(s.tryHold());
            assertFalse(s.tryHold(), "a job does not start during Check");
            assertThrows(IllegalStateException.class, () -> s.check(remote()), "nor Check during a job");
            assertThrows(IllegalStateException.class, s::clean);
            s.release();
            assertTrue(s.check(remote()).path("ok").asBoolean());
            assertTrue(s.clean(), "nothing to sweep on a stand that is not saved");
            assertTrue(s.tryHold(), "both let go of the hosts");
        }
    }

    /** */
    @Test void cleanHostsWaitsForAnEmptyQueue() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            db.insertJob(job());
            StandService s = new StandService(config(report(true)), db, threeServers());
            assertThrows(IllegalStateException.class, s::clean);
            assertTrue(s.tryHold(), "the refusal let go of the hosts");
        }
    }

    /** */
    @Test void onlyTheLastLineIsTheReport() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Config c = config(report(true));
            Files.writeString(Path.of(c.harnessCommand.get(1)),
                "#!/bin/bash\necho '{\"a warning\": 1'\necho '" + report(true) + "'\n");
            assertTrue(new StandService(c, db, threeServers()).check(remote()).path("ok").asBoolean());
        }
    }

    /** */
    @Test void theReportSaysWhichHostsItIsAbout() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            var r = new StandService(config(report(true)), db, threeServers()).check(remote());
            assertEquals("a", r.path("checked").path(0).path("address").asText());
            assertEquals("/opt/firewatch", r.path("checked").path(0).path("workDir").asText());
        }
    }

    /** */
    @Test void aNodeThatDoesNotFitIsSaid() throws Exception {
        String small = "{\"ok\": true, \"hosts\": [{\"address\": \"a\", \"role\": \"servers\", "
            + "\"facts\": {\"memMb\": 2048, \"cpus\": 2}, \"fingerprint\": {}},"
            + " {\"address\": \"b\", \"role\": \"loaders\", "
            + "\"facts\": {\"memMb\": 2048, \"cpus\": 2}, \"fingerprint\": {}}]}";

        try (Db db = Db.open(tmp.resolve("t.db"))) {
            StandService s = new StandService(config(small), db, threeServers());
            var r = s.check(remote());
            assertEquals(Stand.MIN_NODE_MEMORY_MB, r.path("recommended").path("nodeMemoryMb").asInt());
            assertTrue(r.path("sizesWarning").asText().contains("341 MB"), r.toString());

            StandService roomy = new StandService(config(report(true)), db, threeServers());
            assertFalse(roomy.check(remote()).has("sizesWarning"));
            Stand asked = remote();
            asked.sizes.nodeMemoryMb = 20480;
            asked.sizes.fillPct = 40;
            assertTrue(roomy.check(asked).has("sizesWarning"), "what was typed counts too");
        }
    }

    /** */
    @Test void aHarnessThatCannotRunIsAnError() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            StandService s = new StandService(config("no json here"), db, threeServers());
            assertThrows(IllegalStateException.class, () -> s.check(remote()));
            assertEquals(List.of("banner", "no json here"), s.checkLog().get("lines"), "what it said stays readable");
        }
    }

    /** */
    @Test void theLogOfCheckHasTheCommandsButNotTheReport() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Config c = config(report(true));
            StandService s = new StandService(c, db, threeServers());
            assertEquals(List.of(), s.checkLog().get("lines"), "no Check yet");
            Files.writeString(Path.of(c.harnessCommand.get(1)), "#!/bin/bash\necho '[check] a $ ssh a true'\n"
                + "echo '[check] a → exit 0 in 0.1 s'\necho '" + report(true) + "'\n");
            assertTrue(s.check(remote()).path("ok").asBoolean());
            assertEquals(List.of("a $ ssh a true", "a → exit 0 in 0.1 s"), s.checkLog().get("lines"));
            assertEquals(false, s.checkLog().get("running"));
        }
    }

    /** */
    @Test void sizesFitTheWeakestServerHostWithTheLargestScenario() {
        var report = Json.tree(report(true));
        Stand.Sizes s = StandService.recommend(remote(), report, 3);
        assertEquals((32768 - 1024) / 3 / 256 * 256, s.nodeMemoryMb, "one host carries all three nodes");
        assertEquals(40, s.fillPct);
        assertEquals(2, s.threadsFactor, "8 CPUs of the loader against the 4 the scenarios were chosen for");
    }

    /** */
    @Test void moreServerHostsCarryFewerNodesEach() {
        var report = Json.tree("{\"hosts\": [{\"role\": \"servers\", \"facts\": {\"memMb\": 32768}}, "
            + "{\"role\": \"servers\", \"facts\": {\"memMb\": 16384}},"
            + "{\"role\": \"servers\", \"facts\": {\"memMb\": 65536}}, "
            + "{\"role\": \"loaders\", \"facts\": {\"cpus\": 4}}]}");
        assertEquals((16384 - 1024) / 256 * 256, StandService.recommend(remote(), report, 3).nodeMemoryMb);
    }

    /** */
    @Test void thisMachineAlsoHoldsTheLoaderAndTheApplication() {
        var report = Json.tree("{\"hosts\": [{\"role\": \"all\", \"facts\": {\"memMb\": 8192, \"cpus\": 8}}]}");
        Stand.Sizes s = StandService.recommend(new Stand(), report, 3);
        assertEquals((8192 - 1024 - 768) / 3 / 256 * 256, s.nodeMemoryMb);
        assertEquals(2, s.threadsFactor);
    }

    /** */
    @Test void aSmallMachineGetsTheSmallestNode() {
        var report = Json.tree("{\"hosts\": [{\"role\": \"all\", \"facts\": {\"memMb\": 2048, \"cpus\": 2}}]}");
        Stand.Sizes s = StandService.recommend(new Stand(), report, 3);
        assertEquals(Stand.MIN_NODE_MEMORY_MB, s.nodeMemoryMb);
        assertEquals(1, s.threadsFactor);
    }

    /** */
    @Test void noReachableHostSuggestsNothing() {
        var report = Json.tree("{\"hosts\": [{\"role\": \"servers\", \"items\": []}]}");
        assertEquals(0, StandService.recommend(remote(), report, 3).nodeMemoryMb);
    }

    /** */
    @Test void theFileOfAPhaseSaysEverythingTheHarnessReads() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Config c = config("{}");
            c.sshKey = "/keys/id";
            c.sshAcceptNewHosts = true;
            db.setSetting(Stand.FINGERPRINTS, "{\"a\": {\"cpus\": \"16\"}}");
            Path f = tmp.resolve("x/stand.json");
            new StandService(c, db, threeServers()).write(f, remote());
            var n = Json.tree(Files.readString(f));
            assertEquals(2, n.path("hosts").size());
            assertEquals("/keys/id", n.path("sshKey").asText());
            assertTrue(n.path("sshAcceptNewHosts").asBoolean());
            assertEquals("16", n.path("fingerprints").path("a").path("cpus").asText());
            assertEquals(c.runPrefix(), n.path("runPrefix").asText());
        }
    }

    /** */
    @Test void aLocalStandHasNothingToSweep() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            assertTrue(new StandService(config("{}"), db, threeServers()).sweep());
        }
    }
}
