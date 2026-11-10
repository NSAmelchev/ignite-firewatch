package org.apache.ignite.firewatch.harness;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link JfrProfile}. */
class JfrProfileTest {
    /** */
    @TempDir
    Path tmp;

    /** */
    static volatile long sink;

    /**
     * A method that should dominate the samples: pure Java work, the clock is checked rarely (native frames are not
     * sampled).
     */
    static void spin(long untilMs) {
        long x = 0;

        while (System.currentTimeMillis() < untilMs) {
            for (int i = 0; i < 2_000_000; i++)
                x += Long.rotateLeft(x ^ i, 7) * 31;
        }

        sink = x;
    }

    /** */
    @Test
    void hotMethodIsOnTop() throws Exception {
        Path file = tmp.resolve("p.jfr");

        try (Recording r = new Recording()) {
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.start();
            Thread.sleep(200);
            long measureStart = System.currentTimeMillis();
            spin(measureStart + 1000);
            long measureEnd = System.currentTimeMillis();
            Thread.sleep(100);
            r.stop();
            r.dump(file);
        }

        JfrProfile.Profile p = JfrProfile.parse(file, 0, Long.MAX_VALUE);
        // The JFR sampler needs to suspend threads; some sandboxes forbid that and the recording stays empty.
        Assumptions.assumeTrue(p.samples > 0, "JFR execution sampling is unavailable in this environment");
        assertTrue(p.samples >= 20, "samples " + p.samples);
        assertTrue(!p.self.isEmpty() && !p.total.isEmpty());
        String top = p.self.get(0).method;
        assertTrue(top.contains("JfrProfileTest.spin") || top.startsWith("java.lang.System"), "top self frame: " + top);
        boolean spinInTotal = p.total.stream().anyMatch(e -> e.method.endsWith("JfrProfileTest.spin"));
        assertTrue(spinInTotal,
            "spin should be on every sampled stack: " + p.total.subList(0, Math.min(5, p.total.size())));
        JfrProfile.Profile merged = JfrProfile.merge(List.of(p, p));
        assertEquals(2 * p.samples, merged.samples);
        assertEquals(2 * p.self.get(0).samples, merged.self.get(0).samples);
    }

}
