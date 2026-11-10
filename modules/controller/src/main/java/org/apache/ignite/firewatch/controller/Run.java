package org.apache.ignite.firewatch.controller;

/** One harness phase of a job (A1, B1, B2, A2 or S) with the headline metrics of its {@code result.json}. */
public final class Run {
    /** */
    public static final String RUNNING = "running";

    /** */
    public static final String OK = "ok";

    /** */
    public static final String FAILED = "failed";

    /** */
    public long id;

    /** */
    public long jobId;

    /** */
    public String phase;

    /** */
    public String sha;

    /** */
    public int seq;

    /** */
    public String status;

    /** */
    public String startedAt;

    /** */
    public String finishedAt;

    /** Relative to the data directory, e.g. {@code runs/12/A1}. */
    public String dir;

    /** */
    public String error;

    /** */
    public String igniteVersion;

    /** */
    public Double wallSec;

    /** */
    public Double throughput;

    /** */
    public Double p50Us;

    /** */
    public Double p99Us;

    /** */
    public Double p999Us;

    /** */
    public Double gcPausePct;

    /** */
    public Double gcMaxPauseMs;

    /** */
    public Double allocMbPerSec;

    /** */
    public Double serverCpuPct;

    /** */
    public Double machineCpuPct;

    /** */
    public Double heapAfterGcMb;

    /** */
    public Long errors;
}
