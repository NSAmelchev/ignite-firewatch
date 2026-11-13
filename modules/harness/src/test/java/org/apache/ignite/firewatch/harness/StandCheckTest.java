package org.apache.ignite.firewatch.harness;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link StandCheck}. */
class StandCheckTest {
    /** */
    @TempDir Path tmp;

    /** */
    private static Map<String, Object> goodHost() {
        Map<String, Object> f = new HashMap<>();
        f.put("jdk", "17.0.10+7");
        f.put("javaMajor", 17);
        f.put("kernel", "Linux 6.8.0");
        f.put("cpu", "Xeon");
        f.put("cpus", 16);
        f.put("memMb", 32_000L);
        f.put("memFreeMb", 30_000L);
        f.put("swapMb", 0L);
        f.put("diskFreeMb", 100_000L);
        f.put("thp", "madvise");
        f.put("governor", "performance");
        f.put("foreignJvms", List.of());

        return f;
    }

    /** */
    private static String level(Map<String, Object> facts, String item, String foreign) {
        StandCheck.HostReport r = new StandCheck.HostReport();
        r.facts = facts;
        StandCheck.judge(r, foreign);

        return r.items.stream().filter(i -> i.name.equals(item)).findFirst().orElseThrow().level;
    }

    /** */
    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> f = goodHost();
        f.put(key, value);

        return f;
    }

    /** */
    @Test void aGoodHostPasses() {
        StandCheck.HostReport r = new StandCheck.HostReport();
        r.facts = goodHost();
        StandCheck.judge(r, StandCheck.FAIL);
        assertTrue(r.items.stream().allMatch(i -> StandCheck.OK.equals(i.level)), () -> r.items.get(0).text);
    }

    /** */
    @Test void whatMakesResultsWrongFails() {
        assertEquals(StandCheck.FAIL, level(with("javaMajor", 11), "java", StandCheck.FAIL));
        assertEquals(StandCheck.FAIL, level(with("diskFreeMb", 1024L), "disk", StandCheck.FAIL));
        assertEquals(StandCheck.FAIL, level(with("memMb", 1024L), "memory", StandCheck.FAIL));
        assertEquals(StandCheck.FAIL, level(with("foreignJvms", List.of("7 java")), "jvms", StandCheck.FAIL));
    }

    /** */
    @Test void whatMakesResultsNoisierWarns() {
        assertEquals(StandCheck.WARN, level(with("swapMb", 4096L), "swap", StandCheck.FAIL));
        assertEquals(StandCheck.WARN, level(with("thp", "always"), "thp", StandCheck.FAIL));
        assertEquals(StandCheck.WARN, level(with("governor", "powersave"), "governor", StandCheck.FAIL));
        assertEquals(StandCheck.OK, level(with("governor", null), "governor", StandCheck.FAIL),
            "a VM has no governor to report");
        assertEquals(StandCheck.WARN, level(with("foreignJvms", List.of("7 java")), "jvms", StandCheck.WARN),
            "this machine may be the developer's");
    }

    /** */
    @Test void aWarningSaysHowToFixIt() {
        StandCheck.HostReport r = new StandCheck.HostReport();
        r.facts = with("thp", "always");
        StandCheck.judge(r, StandCheck.FAIL);
        StandCheck.Item thp = r.items.stream().filter(i -> i.name.equals("thp")).findFirst().orElseThrow();
        assertTrue(thp.fix.contains("madvise"), thp.fix);
        assertTrue(r.items.stream().filter(i -> StandCheck.OK.equals(i.level)).allMatch(i -> i.fix == null),
            "nothing to fix in what passed");
    }

    /** */
    @Test void clocksAreJudgedByHowFarTheyAre() {
        StandCheck.HostReport r = new StandCheck.HostReport();
        StandCheck.clock(r, "l1", 5);
        assertEquals(StandCheck.OK, r.items.get(0).level);
        StandCheck.clock(r, "l1", -5000);
        assertEquals(1, r.items.size(), "one line per loader, not per port");
        StandCheck.clock(r, "l2", -150);
        assertEquals(StandCheck.WARN, r.items.get(1).level);
        StandCheck.clock(r, "l3", 1500);
        assertEquals(StandCheck.FAIL, r.items.get(2).level);
    }

    /** */
    @Test void memoryOfAFingerprintSurvivesAReboot() {
        Map<String, String> a = StandCheck.fingerprintOf(with("memMb", 32_000L)),
            b = StandCheck.fingerprintOf(with("memMb", 32_040L));
        assertEquals(a, b);
        assertNull(RemoteHost.changed(a, b));
    }

    /** */
    @Test void aChangedHostSaysWhatChanged() {
        Map<String, String> was = StandCheck.fingerprintOf(goodHost()),
            now = StandCheck.fingerprintOf(with("kernel", "Linux 6.9.1"));
        String changed = RemoteHost.changed(was, now);
        assertTrue(changed.contains("Linux 6.8.0") && changed.contains("Linux 6.9.1"), changed);
        assertNull(RemoteHost.changed(null, now), "a host never checked has nothing to compare with");
    }

    /** */
    @Test void theKernelNamesTheValueInForce() {
        assertEquals("never", HostProbe.selected("always madvise [never]"));
        assertEquals("madvise", HostProbe.selected("always [madvise] never"));
        assertEquals("off", HostProbe.selected("off"));
        assertNull(HostProbe.selected(null));
    }

    /** */
    @Test void thisMachineCanBeChecked() throws Exception {
        StandCheck c = StandCheck.local(tmp.resolve("data"));
        assertEquals(1, c.hosts.size());
        StandCheck.HostReport r = c.hosts.get(0);
        assertTrue(((Number)r.facts.get("cpus")).intValue() > 0);
        assertTrue(((Number)r.facts.get("memMb")).longValue() > 0);
        assertEquals(String.valueOf(r.facts.get("cpus")), r.fingerprint.get("cpus"));
        assertFalse(r.items.isEmpty());
    }

    /** */
    @Test void checkSaysWhatSshIsGoingToUseAndWhatKnownHostsLacks() throws Exception {
        Assumptions.assumeTrue(RemoteHostTest.sshInstalled(), "ssh is required");
        Path known = Files.writeString(tmp.resolve("known_hosts"), "");
        StandConfig s = new StandConfig();
        s.sshKey = tmp.resolve("no-such-key").toString();
        s.sshKnownHosts = known.toString();
        StandConfig.Host h = new StandConfig.Host();
        h.address = "127.0.0.1";
        h.port = 1; // nothing listens there
        h.user = "bench";
        h.role = StandConfig.SERVERS;
        h.javaHome = "/usr/lib/jvm/java-17";
        h.workDir = "/opt/firewatch";
        s.hosts = List.of(h);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StandCheck c = StandCheck.remote(s, Path.of("harness.jar"), "check",
            new CommandLog(new PrintStream(buf, true, UTF_8)));
        assertFalse(c.ok);
        String out = buf.toString(UTF_8);
        assertTrue(out.contains("[check] · ssh key: " + s.sshKey + " is missing\n"), out);
        assertTrue(out.contains("[check] · known_hosts: " + known + ", rw"), out);
        assertTrue(out.contains("[check] 127.0.0.1 $ ssh-keygen -F '[127.0.0.1]:1' -f " + known), out);
        assertTrue(out.contains("[check] 127.0.0.1 · " + known + " has no key of [127.0.0.1]:1"), out);
        assertTrue(out.contains("[check] 127.0.0.1 → exit 255"), out);
        assertTrue(out.contains("[check] · a host known_hosts has no key of: refused"), out);
    }

    /** */
    @Test void checkSaysThatSshTakesTheKeysOfNewHosts() throws Exception {
        Assumptions.assumeTrue(RemoteHostTest.sshInstalled(), "ssh is required");
        StandConfig s = new StandConfig();
        s.sshKnownHosts = tmp.resolve("no-such-known-hosts").toString();
        s.sshAcceptNewHosts = true;
        StandConfig.Host h = new StandConfig.Host();
        h.address = "127.0.0.1";
        h.port = 1;
        h.user = "bench";
        h.role = StandConfig.SERVERS;
        h.javaHome = "/usr/lib/jvm/java-17";
        h.workDir = "/opt/firewatch";
        s.hosts = List.of(h);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StandCheck.remote(s, Path.of("harness.jar"), "check", new CommandLog(new PrintStream(buf, true, UTF_8)));
        String out = buf.toString(UTF_8);
        assertTrue(out.contains("[check] · a host known_hosts has no key of: ssh takes its key on first contact"), out);
        assertTrue(out.contains("[check] 127.0.0.1 · " + s.sshKnownHosts + " is missing: ssh takes its key on first "
            + "contact"), out);
        assertTrue(out.contains("StrictHostKeyChecking=accept-new"), out);
    }

    /** */
    @Test void aFailedHostKeyCheckSaysWhatToDo() {
        String msg = "ssh exited with code 255: Host key verification failed.";
        assertTrue(StandCheck.hostKeyHint(msg, false).contains("ssh.accept.new.hosts = true"));
        assertTrue(StandCheck.hostKeyHint(msg, true).contains("another key than known_hosts says"));
        assertEquals("Connection refused", StandCheck.hostKeyHint("Connection refused", false));
    }
}
