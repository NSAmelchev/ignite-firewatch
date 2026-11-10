package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Extracts server-side metrics from a JFR file: GC pauses, allocation rate, CPU load and heap, both
 * aggregated over the measure window and as per-second series aligned with the loader's series.
 */
public final class JfrMetrics {
    /** */
    private static final double MB = 1024 * 1024;

    /** Metrics of one server node. */
    public static final class NodeMetrics {
        /** */
        public int gcCount;

        /** */
        public double totalPauseMs;

        /** */
        public double maxPauseMs;

        /** */
        public double pausePct;

        /** */
        public long allocBytes;

        /** */
        public double allocMbPerSec;

        /** */
        public int cpuSamples;

        /** */
        public double cpuJvmPct;

        /** */
        public double cpuMachinePct;

        /** */
        public Double heapAfterGcMbMean;

        /** */
        public Double heapCommittedMbMax;

        /** */
        public Double[] cpuJvmPctSeries;

        /** */
        public Double[] cpuMachinePctSeries;

        /** */
        public Double[] gcPauseMsSeries;

        /** */
        public Double[] allocMbSeries;

        /** */
        public Double[] heapUsedMbSeries;
    }

    /** Allocation counter of a thread at a moment. */
    private record AllocSample(long timeMs, long threadId, long allocated) {
    }

    /**
     * Reads the metrics of a server node from its recording.
     *
     * @param jfr Recording of one server node.
     * @param loadStartMs Epoch millis of series index 0 (start of warmup).
     * @param measureStartMs Epoch millis where the measure window starts.
     * @param measureEndMs Epoch millis where the measure window ends.
     * @param totalSeconds Series length (warmup + measure).
     * @return Metrics of the node.
     */
    public static NodeMetrics parse(Path jfr, long loadStartMs, long measureStartMs, long measureEndMs,
        int totalSeconds)
        throws IOException {
        NodeMetrics m = new NodeMetrics();
        double[] cpuJvmBySec = new double[totalSeconds];
        double[] cpuMachineBySec = new double[totalSeconds];
        int[] cpuCnt = new int[totalSeconds];
        double[] gcPauseBySec = new double[totalSeconds];
        double[] allocMbBySec = new double[totalSeconds];
        Double[] heapUsedBySec = new Double[totalSeconds];
        double cpuJvmTotal = 0, cpuMachineTotal = 0, heapAfterSum = 0, committedMax = -1;
        int heapAfterN = 0;
        List<AllocSample> allocs = new ArrayList<>();
        long measureMs = Math.max(1, measureEndMs - measureStartMs);

        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                long ms = e.getStartTime().toEpochMilli();
                boolean inWindow = ms >= measureStartMs && ms < measureEndMs;
                int sec = (int)Math.floorDiv(ms - loadStartMs, 1000L);
                boolean inSeries = sec >= 0 && sec < totalSeconds;

                switch (type) {
                    case "jdk.GarbageCollection" -> {
                        double pause = e.getDuration("sumOfPauses").toNanos() / 1e6;

                        if (inWindow) {
                            m.gcCount++;
                            m.totalPauseMs += pause;
                            m.maxPauseMs = Math.max(m.maxPauseMs, pause);
                        }

                        if (inSeries)
                            gcPauseBySec[sec] += pause;
                    }
                    case "jdk.GCHeapSummary" -> {
                        if ("After GC".equals(e.getString("when"))) {
                            long used = e.getLong("heapUsed");
                            RecordedObject space = e.getValue("heapSpace");
                            long committed = space == null ? -1 : space.getLong("committedSize");

                            if (inWindow) {
                                heapAfterSum += used;
                                heapAfterN++;
                                committedMax = Math.max(committedMax, committed);
                            }

                            if (inSeries)
                                heapUsedBySec[sec] = used / MB;
                        }
                    }
                    case "jdk.CPULoad" -> {
                        double jvm = e.getFloat("jvmUser") + e.getFloat("jvmSystem");
                        double machine = e.getFloat("machineTotal");

                        if (inWindow) {
                            m.cpuSamples++;
                            cpuJvmTotal += jvm;
                            cpuMachineTotal += machine;
                        }

                        if (inSeries) {
                            cpuJvmBySec[sec] += jvm;
                            cpuMachineBySec[sec] += machine;
                            cpuCnt[sec]++;
                        }
                    }
                    case "jdk.ThreadAllocationStatistics" -> {
                        RecordedThread t = e.getThread("thread");
                        allocs.add(new AllocSample(ms, t == null ? -1 : t.getId(), e.getLong("allocated")));
                    }
                    default -> {
                    }
                }
            }
        }

        // Per-thread cumulative counters: sort by time, then sum the deltas.
        allocs.sort(Comparator.comparingLong(AllocSample::timeMs));
        Map<Long, Long> last = new HashMap<>();

        for (AllocSample a : allocs) {
            Long prev = last.put(a.threadId(), a.allocated());

            if (prev == null)
                continue;

            long delta = Math.max(0, a.allocated() - prev);

            if (a.timeMs() >= measureStartMs && a.timeMs() < measureEndMs)
                m.allocBytes += delta;

            int sec = (int)Math.floorDiv(a.timeMs() - loadStartMs, 1000L);

            if (sec >= 0 && sec < totalSeconds)
                allocMbBySec[sec] += delta / MB;
        }

        m.pausePct = m.totalPauseMs / measureMs * 100;
        m.allocMbPerSec = m.allocBytes / MB / (measureMs / 1000.0);
        m.cpuJvmPct = m.cpuSamples == 0 ? 0 : cpuJvmTotal / m.cpuSamples * 100;
        m.cpuMachinePct = m.cpuSamples == 0 ? 0 : cpuMachineTotal / m.cpuSamples * 100;
        m.heapAfterGcMbMean = heapAfterN == 0 ? null : heapAfterSum / heapAfterN / MB;
        m.heapCommittedMbMax = committedMax < 0 ? null : committedMax / MB;

        m.cpuJvmPctSeries = new Double[totalSeconds];
        m.cpuMachinePctSeries = new Double[totalSeconds];
        m.gcPauseMsSeries = new Double[totalSeconds];
        m.allocMbSeries = new Double[totalSeconds];
        m.heapUsedMbSeries = heapUsedBySec;

        for (int i = 0; i < totalSeconds; i++) {
            m.cpuJvmPctSeries[i] = cpuCnt[i] == 0 ? null : cpuJvmBySec[i] / cpuCnt[i] * 100;
            m.cpuMachinePctSeries[i] = cpuCnt[i] == 0 ? null : cpuMachineBySec[i] / cpuCnt[i] * 100;
            m.gcPauseMsSeries[i] = gcPauseBySec[i];
            m.allocMbSeries[i] = allocMbBySec[i];
        }

        return m;
    }

    /** */
    private JfrMetrics() {
    }
}
