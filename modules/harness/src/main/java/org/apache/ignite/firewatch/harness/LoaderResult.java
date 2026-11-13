package org.apache.ignite.firewatch.harness;

import java.util.ArrayList;
import java.util.List;

import org.HdrHistogram.Histogram;

/** What the loader JVM measured in one phase; written to {@code loader.json} for the launcher. */
public final class LoaderResult {
    /** */
    public String igniteVersion;

    /** */
    public long connectMs;

    /** */
    public long preloadMs;

    /** */
    public long loadStartMs;

    /** */
    public long measureStartMs;

    /** */
    public long measureEndMs;

    /** */
    public int warmupSec;

    /** */
    public int measureSec;

    /** */
    public int measureStartIndex;

    /** */
    public long totalOps;

    /** Operations that threw, warmup included, and the first of them. */
    public long errors;

    /** */
    public String firstError;

    /** */
    public Stats.Summary throughput;

    /** */
    public Latency latencyUs;

    /** Mean process CPU of the loader JVM over the measure window, percent of all CPUs; null if unavailable. */
    public Double loaderCpuPct;

    /** Mean CPU of the whole machine of the loader over the measure window, percent; null if unavailable. */
    public Double machineCpuPct;

    /** */
    public Series series = new Series();

    /** Latency statistics in microseconds. */
    public static final class Latency {
        /** */
        public double mean;

        /** */
        public double p50;

        /** */
        public double p90;

        /** */
        public double p99;

        /** */
        public double p999;

        /** */
        public double max;

        /** */
        static Latency ofNanos(Histogram h) {
            Latency l = new Latency();

            if (h.getTotalCount() == 0)
                return l;

            l.mean = h.getMean() / 1000.0;
            l.p50 = h.getValueAtPercentile(50) / 1000.0;
            l.p90 = h.getValueAtPercentile(90) / 1000.0;
            l.p99 = h.getValueAtPercentile(99) / 1000.0;
            l.p999 = h.getValueAtPercentile(99.9) / 1000.0;
            l.max = h.getMaxValue() / 1000.0;

            return l;
        }
    }

    /** Per-second samples for the whole load (warmup included), index = seconds since loadStartMs. */
    public static final class Series {
        /** */
        public List<Integer> t = new ArrayList<>();

        /** */
        public List<Long> ops = new ArrayList<>();

        /** */
        public List<Double> p50Us = new ArrayList<>();

        /** */
        public List<Double> p99Us = new ArrayList<>();

        /** */
        public List<Double> loaderCpuPct = new ArrayList<>();
    }
}
