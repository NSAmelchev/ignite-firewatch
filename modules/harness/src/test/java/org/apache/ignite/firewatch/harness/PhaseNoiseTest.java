package org.apache.ignite.firewatch.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What else ran on the hosts of a phase, and what the fingerprint of a host holds. */
class PhaseNoiseTest {
    /** */
    @TempDir Path tmp;

    /** */
    private static Map<String, Object> counters(long steal, long throttle) {
        return Map.of("cpu", List.of(100L + steal, 0L, 50L, 800L, 50L, 0L, 0L, steal), "throttle", throttle);
    }

    /** */
    @Test void aHostWithForeignLoadStealAndThrottlingIsNoisy() {
        // 400 ticks between the readings, 30 of them stolen; the machine used 40 %, the JVMs of the phase 25 %
        PhaseResult.HostNoise n = PhaseRunner.hostNoise("bench-1", 40.0, 25.0,
            Map.of("cpu", List.of(100, 0, 50, 800, 50, 0, 0, 0), "throttle", 5),
            Map.of("cpu", List.of(160, 0, 70, 1080, 60, 0, 0, 30), "throttle", 7));
        assertEquals(15.0, n.foreignCpuPct);
        assertEquals(7.5, n.stealPct);
        assertEquals(2L, n.throttled);
        assertEquals(List.of("foreign CPU 15 % on bench-1", "steal 7.5 % on bench-1", "CPU throttled on heat 2 times on bench-1"),
            PhaseRunner.noisy(List.of(n)));
    }

    /** */
    @Test void aQuietHostOrOneThatCannotTellIsNot() {
        PhaseResult.HostNoise quiet = PhaseRunner.hostNoise("this machine", 30.0, 27.5, counters(0, 3), counters(0, 3));
        assertEquals(2.5, quiet.foreignCpuPct);
        assertEquals(0.0, quiet.stealPct);
        assertEquals(0L, quiet.throttled);

        PhaseResult.HostNoise silent = PhaseRunner.hostNoise("bench-2", null, 10, null, Map.of("throttle", 1));
        assertNull(silent.foreignCpuPct);
        assertNull(silent.stealPct);
        assertNull(silent.throttled);
        assertTrue(PhaseRunner.noisy(List.of(quiet, silent)).isEmpty());

        PhaseResult.HostNoise busy = PhaseRunner.hostNoise("x", 20.0, 35.0, null, null);
        assertEquals(0.0, busy.foreignCpuPct, "the JVMs may sum a bit over the machine: no foreign load below zero");
    }

    /** The options that bear on speed stay, the boot ones go. */
    @Test void kernelOptions() {
        assertEquals("mitigations=off transparent_hugepage=madvise", HostProbe.kernelOptions(
            "BOOT_IMAGE=/vmlinuz-6.8.0-47 root=UUID=1a2b ro quiet splash transparent_hugepage=madvise mitigations=off\n"));
        assertEquals("none", HostProbe.kernelOptions("BOOT_IMAGE=/boot/vmlinuz root=/dev/sda1 ro console=ttyS0"));
        assertNull(HostProbe.kernelOptions(null));
    }

    /** */
    @Test void openVulnerabilities() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("vulnerabilities"));
        Files.writeString(dir.resolve("spectre_v2"), "Vulnerable: eIBRS with unprivileged eBPF\n");
        Files.writeString(dir.resolve("mds"), "Mitigation: Clear CPU buffers; SMT vulnerable\n");
        Files.writeString(dir.resolve("meltdown"), "Not affected\n");
        assertEquals("spectre_v2", HostProbe.vulnerable(dir));
        Files.writeString(dir.resolve("spectre_v2"), "Mitigation: Enhanced IBRS\n");
        assertEquals("none", HostProbe.vulnerable(dir));
        assertNull(HostProbe.vulnerable(tmp.resolve("missing")));
    }

    /** A fingerprint taken before the new facts existed is compared on what it has. */
    @Test void theFingerprintHoldsWhatChangesAfterAReboot() {
        Map<String, Object> f = new java.util.HashMap<>(Map.of("cpu", "Xeon", "cpus", 16, "memMb", 32_000L,
            "kernel", "Linux 6.8.0", "jdk", "17.0.10+7", "clocksource", "tsc", "smt", "on", "turbo", false,
            "kernelOptions", "none", "vulnerable", "none"));
        Map<String, String> fp = StandCheck.fingerprintOf(f);
        assertEquals("tsc", fp.get("clocksource"));
        assertEquals("off", fp.get("turbo"));
        assertEquals("none", fp.get("kernelOptions"));

        f.put("clocksource", "hpet");
        assertEquals("clocksource was tsc, now hpet", RemoteHost.changed(fp, StandCheck.fingerprintOf(f)));

        Map<String, String> old = Map.of("cpu", "Xeon", "cpus", "16", "memGb", "31", "kernel", "Linux 6.8.0",
            "jdk", "17.0.10+7");
        assertNull(RemoteHost.changed(old, StandCheck.fingerprintOf(f)), "the old fingerprint knows nothing of the clock");

        f.remove("smt");
        f.put("turbo", null);
        assertTrue(!StandCheck.fingerprintOf(f).containsKey("smt") && !StandCheck.fingerprintOf(f).containsKey("turbo"),
            "a host that does not tell leaves them out");
    }
}
