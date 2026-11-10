package org.apache.ignite.firewatch.controller;

/** Build of one commit: the Ignite jars in {@code libsDir} or the failure. */
public final class Build {
    /** */
    public static final String BUILDING = "building";

    /** */
    public static final String OK = "ok";

    /** */
    public static final String FAILED = "failed";

    /** */
    public String sha;

    /** */
    public String status;

    /** */
    public String startedAt;

    /** */
    public String finishedAt;

    /** */
    public Double durationSec;

    /** */
    public String libsDir;

    /** */
    public String logFile;

    /** */
    public String error;

    /** */
    public String igniteVersion;

    /** */
    public Long sizeBytes;

    /** */
    public String lastUsedAt;

    /** */
    public boolean fake;
}
