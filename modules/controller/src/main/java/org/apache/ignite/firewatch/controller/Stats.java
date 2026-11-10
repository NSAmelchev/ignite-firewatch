package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ABBA statistics. For every metric A = mean(A1, A2), B = mean(B1, B2), delta = B/A - 1. The verdict needs the
 * delta to exceed a threshold (the larger of the A/A noise floor and the spread between the two repeats of a side)
 * and, where per-second samples exist, a bootstrap 95 % CI of the delta that excludes zero.
 */
public final class Stats {
    /** */
    public static final String REGRESSION = "regression";

    /** */
    public static final String IMPROVEMENT = "improvement";

    /** */
    public static final String NO_CHANGE = "no-change";

    /** Definition of a compared metric. */
    public static final class MetricDef {
        /** */
        public final String key;

        /** */
        public final String title;

        /** */
        public final String unit;

        /** */
        public final boolean higherBetter;

        /** */
        public final double defaultNoisePct;

        /** Which per-second series backs the bootstrap CI: {@code ops}, {@code p99} or null. */
        public final String series;

        /** */
        MetricDef(String key, String title, String unit, boolean higherBetter, double defaultNoisePct, String series) {
            this.key = key;
            this.title = title;
            this.unit = unit;
            this.higherBetter = higherBetter;
            this.defaultNoisePct = defaultNoisePct;
            this.series = series;
        }
    }

    /** */
    public static final List<MetricDef> METRICS = List.of(
        new MetricDef("throughput", "Throughput", "ops/s", true, 2, "ops"),
        new MetricDef("p50Us", "Latency p50", "µs", false, 3, null),
        new MetricDef("p99Us", "Latency p99", "µs", false, 5, "p99"),
        new MetricDef("p999Us", "Latency p99.9", "µs", false, 10, null),
        new MetricDef("maxUs", "Latency max", "µs", false, 50, null),
        new MetricDef("gcPausePct", "GC pause", "%", false, 15, null),
        new MetricDef("gcMaxPauseMs", "GC max pause", "ms", false, 30, null),
        new MetricDef("allocMbPerSec", "Allocation rate", "MB/s", false, 3, null),
        new MetricDef("allocKbPerOp", "Allocation per op", "KB/op", false, 3, null),
        new MetricDef("serverCpuPct", "Server JVM CPU", "%", false, 4, null),
        new MetricDef("serverCpuUsPerOp", "Server CPU per op", "µs", false, 4, null),
        new MetricDef("machineCpuPct", "Machine CPU", "%", false, 4, null),
        new MetricDef("loaderCpuPct", "Loader JVM CPU", "%", false, 5, null),
        new MetricDef("heapAfterGcMb", "Heap after GC", "MB", false, 10, null),
        new MetricDef("serverStartMs", "Server start", "ms", false, 10, null),
        new MetricDef("preloadMs", "Preload", "ms", false, 10, null));

    /** Metrics that decide the job verdict: what the client sees and what the server pays per operation. */
    public static final List<String> VERDICT_METRICS = List.of("throughput", "p99Us", "serverCpuUsPerOp");

    /** Comparison result of one metric. */
    public static final class MetricResult {
        /** */
        public String title;

        /** */
        public String unit;

        /** */
        public boolean higherBetter;

        /** */
        public Map<String, Double> values = new LinkedHashMap<>();

        /** */
        public Double meanA;

        /** */
        public Double meanB;

        /** */
        public Double deltaPct;

        /** */
        public Double ciLow;

        /** */
        public Double ciHigh;

        /** */
        public Double spreadAPct;

        /** */
        public Double spreadBPct;

        /** */
        public double noisePct;

        /** */
        public double thresholdPct;

        /** */
        public String verdict;
    }

    /** Verdict and per-metric results of an A/B comparison. */
    public static final class Comparison {
        /** */
        public String verdict;

        /** */
        public List<String> phases = new ArrayList<>();

        /** */
        public Map<String, MetricResult> metrics = new LinkedHashMap<>();

        /** */
        public String noiseSource;

        /** */
        public int calibrations;

        /** Number of ABBA runs pooled into this comparison (1 for a single job). */
        public int runs = 1;

        /** */
        public String note;
    }

    /**
     * Compares the phases of a single ABBA run.
     *
     * @param phases Phases of the run.
     * @param noiseFloors Noise floor percent by metric key.
     * @param noiseSource Where the noise floors come from.
     * @param calibrations Number of calibrations behind the noise floors.
     * @return Comparison.
     */
    public static Comparison compare(List<PhaseData> phases, Map<String, Double> noiseFloors, String noiseSource,
        int calibrations) {
        return comparePooled(List.of(phases), noiseFloors, noiseSource, calibrations);
    }

    /**
     * Pools the phases of several ABBA runs of the same pair: A = mean of all A phases, B = mean of all B phases.
     * Every run contributes A and B measured close in time, so drift of the stand between runs still cancels.
     * The repeat spread is the largest spread inside one run (spread across runs would add day-to-day drift);
     * the noise floor stays the single-run floor. With several runs phase names get a "·k" suffix.
     *
     * @param runs Phases of every ABBA run.
     * @param noiseFloors Noise floor percent by metric key.
     * @param noiseSource Where the noise floors come from.
     * @param calibrations Number of calibrations behind the noise floors.
     * @return Pooled comparison.
     */
    public static Comparison comparePooled(List<List<PhaseData>> runs, Map<String, Double> noiseFloors,
        String noiseSource, int calibrations) {
        Comparison c = new Comparison();
        c.noiseSource = noiseSource;
        c.calibrations = calibrations;
        c.runs = runs.size();
        List<PhaseData> phases = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> runOf = new ArrayList<>();

        for (int g = 0; g < runs.size(); g++) {
            for (PhaseData p : runs.get(g)) {
                phases.add(p);
                names.add(runs.size() > 1 ? p.phase + "\u00b7" + (g + 1) : p.phase);
                runOf.add(g);
            }
        }

        c.phases.addAll(names);
        Random rnd = new Random(42);
        boolean anyRegression = false, anyImprovement = false;

        for (MetricDef m : METRICS) {
            MetricResult r = new MetricResult();
            r.title = m.title;
            r.unit = m.unit;
            r.higherBetter = m.higherBetter;
            List<Double> a = new ArrayList<>(), b = new ArrayList<>();
            List<List<Double>> aSeries = new ArrayList<>(), bSeries = new ArrayList<>();
            List<List<Double>> aByRun = new ArrayList<>(), bByRun = new ArrayList<>();

            for (int g = 0; g < runs.size(); g++) {
                aByRun.add(new ArrayList<>());
                bByRun.add(new ArrayList<>());
            }

            for (int i = 0; i < phases.size(); i++) {
                PhaseData p = phases.get(i);
                Double v = p.metrics.get(m.key);
                r.values.put(names.get(i), v);

                if (v == null)
                    continue;

                boolean sideA = p.phase.startsWith("A");
                (sideA ? a : b).add(v);
                (sideA ? aByRun : bByRun).get(runOf.get(i)).add(v);

                if (m.series != null)
                    (sideA ? aSeries : bSeries).add("ops".equals(m.series) ? p.opsSeries : p.p99Series);
            }

            if (a.isEmpty() || b.isEmpty())
                continue;

            r.meanA = mean(a);
            r.meanB = mean(b);
            r.spreadAPct = maxSpreadPct(aByRun);
            r.spreadBPct = maxSpreadPct(bByRun);
            r.noisePct = noiseFloors != null && noiseFloors.containsKey(m.key) ? noiseFloors.get(m.key)
                : m.defaultNoisePct;
            r.thresholdPct = Math.max(r.noisePct, Math.max(nz(r.spreadAPct), nz(r.spreadBPct)));

            if (r.meanA != 0) {
                r.deltaPct = (r.meanB / r.meanA - 1) * 100;

                if (m.series != null && allNonEmpty(aSeries) && allNonEmpty(bSeries)) {
                    double[] ci = bootstrapDeltaCi(aSeries, bSeries, 2000, 5, rnd);
                    r.ciLow = ci[0];
                    r.ciHigh = ci[1];
                }

                r.verdict = verdict(r.deltaPct, r.ciLow, r.ciHigh, r.thresholdPct, m.higherBetter);
            }
            else
                r.verdict = NO_CHANGE;

            if (VERDICT_METRICS.contains(m.key)) {
                anyRegression |= REGRESSION.equals(r.verdict);
                anyImprovement |= IMPROVEMENT.equals(r.verdict);
            }

            c.metrics.put(m.key, r);
        }

        c.verdict = anyRegression ? REGRESSION : anyImprovement ? IMPROVEMENT : NO_CHANGE;
        c.note = "verdict from " + String.join(" and ", VERDICT_METRICS)
            + "; threshold = max(A/A noise floor, spread between repeats)";

        return c;
    }

    /** */
    static String verdict(double deltaPct, Double ciLow, Double ciHigh, double thresholdPct, boolean higherBetter) {
        boolean worse, better;

        if (higherBetter) {
            worse = deltaPct < -thresholdPct && (ciHigh == null || ciHigh < 0);
            better = deltaPct > thresholdPct && (ciLow == null || ciLow > 0);
        }
        else {
            worse = deltaPct > thresholdPct && (ciLow == null || ciLow > 0);
            better = deltaPct < -thresholdPct && (ciHigh == null || ciHigh < 0);
        }

        return worse ? REGRESSION : better ? IMPROVEMENT : NO_CHANGE;
    }

    /**
     * Noise floor per metric from A/A calibration comparisons: the 95th percentile of |delta| over the given
     * comparisons, never below the metric's default. With fewer than 3 calibrations the defaults are used.
     *
     * @param calibrations A/A calibration comparisons.
     * @return Noise floor percent by metric key.
     */
    public static Map<String, Double> noiseFloors(List<Comparison> calibrations) {
        Map<String, Double> out = new LinkedHashMap<>();

        for (MetricDef m : METRICS) {
            List<Double> deltas = new ArrayList<>();

            for (Comparison c : calibrations) {
                MetricResult r = c.metrics.get(m.key);

                if (r != null && r.deltaPct != null)
                    deltas.add(Math.abs(r.deltaPct));
            }

            double floor = m.defaultNoisePct;

            if (deltas.size() >= 3)
                floor = Math.max(floor, percentile(deltas, 95));

            out.put(m.key, floor);
        }

        return out;
    }

    /**
     * Returns the default noise floor of every metric.
     *
     * @return Default noise floor percent by metric key.
     */
    public static Map<String, Double> defaultNoiseFloors() {
        Map<String, Double> out = new LinkedHashMap<>();

        for (MetricDef m : METRICS)
            out.put(m.key, m.defaultNoisePct);

        return out;
    }

    /** Moving-block bootstrap of delta% = mean(B phases)/mean(A phases) - 1; returns {2.5th, 97.5th} percentiles. */
    static double[] bootstrapDeltaCi(List<List<Double>> aSeries, List<List<Double>> bSeries, int iters, int block,
        Random rnd) {
        double[] deltas = new double[iters];

        for (int i = 0; i < iters; i++) {
            double a = 0, b = 0;

            for (List<Double> s : aSeries)
                a += blockResampleMean(s, block, rnd) / aSeries.size();

            for (List<Double> s : bSeries)
                b += blockResampleMean(s, block, rnd) / bSeries.size();

            deltas[i] = a == 0 ? 0 : (b / a - 1) * 100;
        }

        java.util.Arrays.sort(deltas);

        return new double[] {deltas[(int)Math.floor(iters * 0.025)],
            deltas[Math.min(iters - 1, (int)Math.ceil(iters * 0.975) - 1)]};
    }

    /** */
    private static double blockResampleMean(List<Double> s, int block, Random rnd) {
        int n = s.size();

        if (n == 1)
            return s.get(0);

        int L = Math.min(block, n);
        double sum = 0;
        int taken = 0;

        while (taken < n) {
            int start = rnd.nextInt(n - L + 1);

            for (int k = 0; k < L && taken < n; k++, taken++)
                sum += s.get(start + k);
        }

        return sum / n;
    }

    /** */
    static double percentile(List<Double> xs, double p) {
        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        int idx = (int)Math.ceil(p / 100.0 * sorted.size()) - 1;

        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    /** */
    static double mean(List<Double> xs) {
        double s = 0;

        for (double x : xs)
            s += x;

        return s / xs.size();
    }

    /** */
    static Double maxSpreadPct(List<List<Double>> byRun) {
        double max = 0;

        for (List<Double> xs : byRun)
            max = Math.max(max, nz(spreadPct(xs)));

        return max;
    }

    /** Spread between repeats of one side as percent of their mean (0 for a single value). */
    static Double spreadPct(List<Double> xs) {
        if (xs.size() < 2)
            return 0.0;

        double min = Collections.min(xs), max = Collections.max(xs), m = mean(xs);

        return m == 0 ? 0.0 : (max - min) / Math.abs(m) * 100;
    }

    /** */
    private static boolean allNonEmpty(List<List<Double>> series) {
        if (series.isEmpty())
            return false;

        for (List<Double> s : series) {
            if (s == null || s.isEmpty())
                return false;
        }

        return true;
    }

    /** */
    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    /** */
    private Stats() {
    }
}
