package org.apache.ignite.firewatch.harness;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.apache.ignite.firewatch.harness.api.Scenario;
import org.apache.ignite.firewatch.harness.api.Session;

/**
 * Runs the scenario's {@link Scenario#op} in N threads with per-second HdrHistogram sampling. Closed loop by default;
 * with a rate each thread paces itself to its share of it and latency is measured from the intended start time,
 * so coordinated omission does not hide stalls. Runs in the loader JVM.
 */
final class Workload {
    /** */
    private static final long SECOND_NS = 1_000_000_000L;

    /** */
    private final Scenario sc;

    /** */
    private final Session session;

    /** */
    private final int threads;

    /** */
    private final int rateOpsPerSec;

    /** */
    private final int warmupSec;

    /** */
    private final int measureSec;

    /** */
    private final PrintStream log;

    /** */
    private final LongAdder errors = new LongAdder();

    /** */
    private final LongAdder errorsLogged = new LongAdder();

    /** */
    private final AtomicReference<String> firstError = new AtomicReference<>();

    /** */
    private volatile boolean stop;

    /** */
    Workload(Scenario sc, Session session, int threads, int rateOpsPerSec, int warmupSec, int measureSec,
        PrintStream log) {
        this.sc = sc;
        this.session = session;
        this.threads = threads;
        this.rateOpsPerSec = rateOpsPerSec;
        this.warmupSec = warmupSec;
        this.measureSec = measureSec;
        this.log = log;
    }

    /** */
    LoaderResult run() throws InterruptedException {
        int warmup = warmupSec;
        int total = warmupSec + measureSec;

        Recorder[] recorders = new Recorder[threads];
        Histogram[] intervals = new Histogram[threads];
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Recorder rec = recorders[i] = new Recorder(3);
            Thread t = new Thread(() -> {
                try {
                    go.await();
                }
                catch (InterruptedException e) {
                    return;
                }

                if (rateOpsPerSec > 0)
                    rateLoop(rec);
                else
                    closedLoop(rec);
            }, "load-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }

        com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();

        LoaderResult r = new LoaderResult();
        r.warmupSec = warmup;
        r.measureSec = measureSec;
        r.measureStartIndex = warmup;
        Histogram second = new Histogram(3);
        Histogram measured = new Histogram(3);
        List<Double> measuredOps = new ArrayList<>();
        double ldrCpuSum = 0;
        int ldrCpuN = 0;
        double machineSum = 0;
        int machineN = 0;

        long tick = System.nanoTime();
        r.loadStartMs = System.currentTimeMillis();

        if (warmup == 0)
            r.measureStartMs = r.loadStartMs;

        go.countDown();

        for (int s = 0; s < total; s++) {
            tick += SECOND_NS;
            sleepUntil(tick, false);
            long nowMs = System.currentTimeMillis();

            second.reset();

            for (int i = 0; i < threads; i++) {
                intervals[i] = recorders[i].getIntervalHistogram(intervals[i]);
                second.add(intervals[i]);
            }

            boolean measuring = s >= warmup;
            long ops = second.getTotalCount();
            double p50 = second.getValueAtPercentile(50) / 1000.0;
            double p99 = second.getValueAtPercentile(99) / 1000.0;
            double cpu = os.getProcessCpuLoad() * 100;
            double machine = os.getCpuLoad() * 100;

            r.series.t.add(s);
            r.series.ops.add(ops);
            // a second without a single completed operation (a stall) has no latency: 0 would read as the best one
            r.series.p50Us.add(ops == 0 ? null : p50);
            r.series.p99Us.add(ops == 0 ? null : p99);
            r.series.loaderCpuPct.add(cpu < 0 ? null : cpu);

            if (measuring) {
                measured.add(second);
                measuredOps.add((double)ops);

                if (cpu >= 0) {
                    ldrCpuSum += cpu;
                    ldrCpuN++;
                }

                if (machine >= 0) {
                    machineSum += machine;
                    machineN++;
                }
            }

            if (s == warmup - 1)
                r.measureStartMs = nowMs;

            if (s == total - 1)
                r.measureEndMs = nowMs;

            log.printf("sec %3d %-7s ops=%d p50=%.0fus p99=%.0fus errors=%d%n",
                s + 1, measuring ? "measure" : "warmup", ops, p50, p99, errors.sum());
        }

        stop = true;

        for (Thread t : workers)
            t.join(10_000);

        r.totalOps = measured.getTotalCount();
        r.errors = errors.sum();
        r.firstError = firstError.get();
        r.throughput = Stats.summary(measuredOps);
        r.latencyUs = LoaderResult.Latency.ofNanos(measured);
        r.loaderCpuPct = ldrCpuN == 0 ? null : ldrCpuSum / ldrCpuN;
        r.machineCpuPct = machineN == 0 ? null : machineSum / machineN;

        return r;
    }

    /** */
    private void closedLoop(Recorder rec) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        while (!stop) {
            long t = System.nanoTime();

            if (op(rnd))
                rec.recordValue(System.nanoTime() - t);
        }
    }

    /** */
    private void rateLoop(Recorder rec) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long intervalNs = SECOND_NS * threads / rateOpsPerSec;
        long next = System.nanoTime();

        while (!stop) {
            next += intervalNs;
            sleepUntil(next, true);

            if (op(rnd))
                rec.recordValue(Math.max(0, System.nanoTime() - next));
        }
    }

    /** */
    private boolean op(ThreadLocalRandom rnd) {
        try {
            sc.op(session, rnd);

            return true;
        }
        catch (Exception e) {
            errors.increment();
            String text = String.valueOf(e);
            firstError.compareAndSet(null, text.length() > 400 ? text.substring(0, 400) + "..." : text);

            if (errorsLogged.sum() < 10) {
                errorsLogged.increment();
                log.println("operation failed: " + e);
            }

            LockSupport.parkNanos(10_000_000L);

            return false;
        }
    }

    /** */
    private static void sleepUntil(long deadlineNs, boolean spinTail) {
        long rem;

        while ((rem = deadlineNs - System.nanoTime()) > 0) {
            if (!spinTail || rem > 200_000)
                LockSupport.parkNanos(spinTail ? rem - 100_000 : rem);
            else
                Thread.onSpinWait();
        }
    }
}
