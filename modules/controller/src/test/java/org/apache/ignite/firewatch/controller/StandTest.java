package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Stand}. */
class StandTest {
    /** */
    @TempDir Path tmp;

    /** */
    private static Stand.Host host(String address, String role) {
        Stand.Host h = new Stand.Host();
        h.address = address;
        h.user = "bench";
        h.role = role;
        h.javaHome = "/usr/lib/jvm/java-17";
        h.workDir = "/opt/firewatch";

        return h;
    }

    /** */
    private static Stand remote(Stand.Host... hosts) {
        Stand s = new Stand();
        s.mode = Stand.REMOTE;
        s.hosts = List.of(hosts);

        return s;
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
    private static void phase(Db db, Job j, String status) {
        Run r = new Run();
        r.jobId = j.id;
        r.phase = "S";
        r.sha = j.shaA;
        r.status = status;
        r.dir = "runs/" + j.id + "/S";
        db.insertRun(r);
    }

    /** */
    private static void refused(String fragment, Stand s) {
        String msg = assertThrows(IllegalArgumentException.class, s::validate).getMessage();
        assertTrue(msg.contains(fragment), msg);
    }

    /** */
    @Test void aNewStandIsSetUpOnce() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            assertTrue(Stand.needsSetup(db));
            new Stand().save(db);
            assertFalse(Stand.needsSetup(db));
        }
    }

    /** */
    @Test void aStandWithJobsIsLocalAsItAlwaysWas() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            db.insertJob(job());
            assertFalse(Stand.needsSetup(db));
            assertEquals(Stand.LOCAL, Stand.read(db).mode);
            assertTrue(Stand.read(db).hosts.isEmpty());
        }
    }

    /** */
    @Test void aStandThatOnlyPolledNeedsNoSetup() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            db.setSetting(Settings.WATCH_WATERMARK, "a".repeat(40));
            assertFalse(Stand.needsSetup(db));
            Stand.remember(db);
            db.setSetting(Settings.WATCH_WATERMARK, ""); // a change of the branch clears it
            assertFalse(Stand.needsSetup(db));
        }
    }

    /** */
    @Test void rememberingLeavesANewStandNew() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Stand.remember(db);
            assertTrue(Stand.needsSetup(db));
        }
    }

    /** */
    @Test void theInstanceIsDrawnOnce() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            String id = Stand.instance(db);
            assertFalse(id.isEmpty());
            assertEquals(id, Stand.instance(db));
        }
    }

    /** */
    @Test void hostsSurviveTheDatabase() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Stand.Host servers = host("10.0.0.1", Stand.SERVERS);
            servers.port = 2222;
            servers.dataAddress = "192.0.2.1";
            remote(servers, host("bench-2.lan", Stand.LOADERS)).save(db);
            Stand back = Stand.read(db);
            assertEquals(Stand.REMOTE, back.mode);
            assertEquals(2, back.hosts.size());
            assertEquals("10.0.0.1", back.hosts.get(0).address);
            assertEquals(2222, back.hosts.get(0).port);
            assertEquals("192.0.2.1", back.hosts.get(0).dataAddress);
            assertEquals(22, back.hosts.get(1).port, "the default port");

            Stand loc = new Stand();
            loc.save(db);
            assertTrue(Stand.read(db).hosts.isEmpty(), "a local stand keeps no hosts");
        }
    }

    /** */
    @Test void onlyAFinishedPhaseFreezesTheStand() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Job j = db.insertJob(job());
            phase(db, j, Run.FAILED);
            phase(db, j, Run.RUNNING);
            assertFalse(Stand.frozen(db), "hosts that fail must stay fixable");
            new Stand().save(db);

            phase(db, j, Run.OK);
            assertTrue(Stand.frozen(db));
            Stand other = remote(host("a", Stand.SERVERS), host("b", Stand.LOADERS));
            assertThrows(IllegalStateException.class, () -> other.save(db));
            assertEquals(Stand.LOCAL, Stand.read(db).mode);
        }
    }

    /** */
    @Test void aStandWithoutSizesKeepsTheNumbersOfTheScenarios() {
        Stand.Sizes s = new Stand().sizes;
        assertTrue(s.none());
        assertEquals(null, s.scale(1), "the command line of a phase stays as it was");
        assertEquals(1, s.setupTimeoutFactor());
    }

    /** */
    @Test void sizesSurviveTheDatabaseAndReachThePhase() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Stand s = new Stand();
            s.sizes.nodeMemoryMb = 4096;
            s.sizes.fillPct = 40;
            s.sizes.threadsFactor = 2;
            s.save(db);
            Stand.Sizes back = Stand.read(db).sizes;
            assertEquals("4096,40,2.0,1", back.scale(back.threadsFactor));
            assertEquals("4096,40,8.0,1", back.scale(8), "Measure load changes only the threads");
            assertEquals(22, back.setupTimeoutFactor());
        }
    }

    /** */
    @Test void sizesOutOfRangeAreRefused() {
        Stand s = new Stand();
        s.sizes.nodeMemoryMb = 100;
        refused("memory", s);
        s.sizes.nodeMemoryMb = 4096;
        s.sizes.fillPct = 0;
        refused("fill", s);
        s.sizes.fillPct = 95;
        refused("fill", s);
        s.sizes.fillPct = 40;
        s.sizes.threadsFactor = 0;
        refused("threads", s);
        s.sizes.threadsFactor = 2;
        s.validate();
    }

    /** */
    @Test void aProbeDoesNotFreezeTheStand() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Job p = job();
            p.type = Job.PROBE;
            db.insertJob(p);
            phase(db, p, Run.OK);
            assertFalse(Stand.frozen(db));
        }
    }

    /** */
    @Test void aRemoteStandNeedsBothRoles() {
        refused("at least one", remote());
        refused("at least one", remote(host("a", Stand.SERVERS)));
        refused("at least one", remote(host("a", Stand.LOADERS)));
        refused("listed twice", remote(host("a", Stand.SERVERS), host("a", Stand.LOADERS)));
        remote(host("a", Stand.SERVERS), host("b", Stand.SERVERS), host("c", Stand.LOADERS)).validate();
    }

    /** */
    @Test void nothingThatWouldBreakAnSshCommandLinePasses() {
        Stand.Host loaders = host("l", Stand.LOADERS);

        for (String bad : List.of("-oProxyCommand=x", "a b", "a;b", "$(x)", ""))
            refused("bad host address", remote(host(bad, Stand.SERVERS), loaders));

        Stand.Host h = host("a", Stand.SERVERS);
        h.user = "-l";
        refused("bad user", remote(h, loaders));
        h = host("a", Stand.SERVERS);
        h.port = 70000;
        refused("bad ssh port", remote(h, loaders));
        h = host("a", Stand.SERVERS);
        h.role = "clients";
        refused("role", remote(h, loaders));
        h = host("a", Stand.SERVERS);
        h.javaHome = "jdk17";
        refused("JDK", remote(h, loaders));
        h = host("a", Stand.SERVERS);
        h.workDir = "/";
        refused("work directory", remote(h, loaders));
        h = host("a", Stand.SERVERS);
        h.workDir = "/opt/a b";
        refused("work directory", remote(h, loaders));
        h = host("a", Stand.SERVERS);
        h.dataAddress = "a b";
        refused("data address", remote(h, loaders));
    }

    /** */
    @Test void anUnknownModeIsRefused() {
        Stand s = new Stand();
        s.mode = "cloud";
        refused("must be", s);
    }

    /** */
    @Test void theStandCannotBeChangedAsASetting() {
        Scenarios none = new Scenarios(List.of());
        assertThrows(IllegalArgumentException.class,
            () -> Settings.validate(Stand.HOSTS, "[]", none), "only save() writes it");
    }
}
