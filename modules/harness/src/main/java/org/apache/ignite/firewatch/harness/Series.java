package org.apache.ignite.firewatch.harness;

import java.util.ArrayList;
import java.util.List;

/** {@code series.json}: per-second samples of the whole phase; index = seconds since load start. */
public final class Series {
    /** */
    public int measureStartIndex;

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

    /** Sum over server JVMs. */
    public List<Double> serverCpuPct = new ArrayList<>();

    /** */
    public List<Double> machineCpuPct = new ArrayList<>();

    /** */
    public List<Double> gcPauseMs = new ArrayList<>();

    /** */
    public List<Double> allocMb = new ArrayList<>();

    /** */
    public List<Double> heapUsedMb = new ArrayList<>();
}
