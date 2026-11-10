package org.apache.ignite.firewatch.harness;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
