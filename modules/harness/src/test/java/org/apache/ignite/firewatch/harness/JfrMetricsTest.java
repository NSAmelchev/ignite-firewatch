package org.apache.ignite.firewatch.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link JfrMetrics}. */
class JfrMetricsTest {
    /** */
    @TempDir
    Path tmp;

    /** */
    @Test
    void parsesGcAllocationAndCpuFromARealRecording() throws Exception {
        Path file = tmp.resolve("test.jfr");

        try (Recording r = new Recording()) {
            r.enable("jdk.GarbageCollection").withThreshold(Duration.ZERO);
            r.enable("jdk.GCHeapSummary");
            r.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(100));
            r.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofMillis(100));
            r.start();
            long loadStart = System.currentTimeMillis();
            Thread.sleep(300);
            long measureStart = System.currentTimeMillis();
            List<byte[]> junk = new ArrayList<>();
            long until = System.currentTimeMillis() + 1200;

            while (System.currentTimeMillis() < until) {
                junk.add(new byte[1024 * 1024]);

                if (junk.size() > 64)
                    junk.clear();
            }

            System.gc();
            Thread.sleep(300);
            long measureEnd = System.currentTimeMillis();
            Thread.sleep(300);
            r.stop();
            r.dump(file);

            JfrMetrics.NodeMetrics m = JfrMetrics.parse(file, loadStart, measureStart, measureEnd, 4);

            assertTrue(m.gcCount >= 1, "gc count");
            assertTrue(m.totalPauseMs > 0, "gc pause");
            assertTrue(m.maxPauseMs >= m.totalPauseMs / m.gcCount, "max pause");
            assertTrue(m.allocMbPerSec > 10, "alloc rate " + m.allocMbPerSec);
            // the window is short: a busy machine gives only a couple
            assertTrue(m.cpuSamples >= 1, "cpu samples " + m.cpuSamples);
            assertTrue(m.cpuJvmPct > 0 && m.cpuJvmPct <= 100, "jvm cpu " + m.cpuJvmPct);
            assertTrue(m.cpuMachinePct >= m.cpuJvmPct - 1, "machine cpu >= jvm cpu");
            assertNotNull(m.heapAfterGcMbMean);
            assertEquals(4, m.allocMbSeries.length);
            double allocInSeries = 0;

            for (Double d : m.allocMbSeries)
                allocInSeries += d;

            assertTrue(allocInSeries > 0, "alloc series");
            assertTrue(Files.size(file) > 0);
        }
    }
}
