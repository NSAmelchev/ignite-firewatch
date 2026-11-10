package org.apache.ignite.firewatch.controller;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** A queued or finished benchmark job: one version ({@code single}) or an ABBA comparison ({@code abba}). */
public final class Job {
    /** */
    public static final String SINGLE = "single";

    /** */
    public static final String ABBA = "abba";

    /** Measure load: short phases with more and more threads. A service job: no history, no freezing of the stand. */
    public static final String PROBE = "probe";

    /** */
    public static final String QUEUED = "queued";

    /** */
    public static final String RUNNING = "running";

    /** */
    public static final String DONE = "done";

    /** */
    public static final String FAILED = "failed";

    /** */
    public static final String CANCELLED = "cancelled";

    /** */
    public static final String INTERRUPTED = "interrupted";

    /** */
    public long id;

    /** */
    public String type;

    /** {@code auto}, {@code manual}, {@code calibration}, {@code compare}, {@code release} or {@code bisect}. */
    public String source;

    /** */
    public String status;

    /** */
    public int priority;

    /** */
    public String scenario;

    /** */
    public String scenarioHash;

    /** */
    public String refA;

    /** */
    public String shaA;

    /** */
    public String refB;

    /** */
    public String shaB;

    /** */
    public String label;

    /** */
    public String createdAt;

    /** */
    public String startedAt;

    /** */
    public String finishedAt;

    /** */
    public String stage;

    /** */
    public int progress;

    /** */
    public String error;

    /** */
    public boolean cancelRequested;

    /** */
    public String logFile;

    /** */
    public JsonNode comparison;

    /** */
    public String verdict;

    /** A step of an automatic bisection: its id and the step number (1-based); null for every other job. */
    public Long bisectId;

    /** */
    public Integer bisectStep;

    /** A scenario of a {@link Comparison}: its id; null for every other job. */
    public Long comparisonId;

    /** Queue view only: expected start (ISO) and duration (s), estimated from recent finished jobs. */
    public String etaStartAt;

    /** List views only: commit subjects of A and B (null for refs outside the branch history, e.g. PR merges). */
    public String subjectA;

    /** */
    public String subjectB;

    /** List views: commit dates of A and B (ISO) when they are branch commits. */
    public String dateA;

    /** */
    public String dateB;

    /** Job page only: when A is an earlier commit of the branch other than B's parent, the commits A..B. */
    public Map<String, Object> segment;

    /** */
    public Long etaSec;

    /** Only on the detail view. */
    public List<Run> runs;

    /**
     * Checks whether the job is not finished.
     *
     * @return {@code True} if the job is queued or running.
     */
    public boolean active() {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }

    /**
     * Checks the job type.
     *
     * @return {@code True} if this is an ABBA job.
     */
    public boolean isAbba() {
        return ABBA.equals(type);
    }

    /**
     * Checks the job type.
     *
     * @return {@code True} if this is a probe job.
     */
    public boolean isProbe() {
        return PROBE.equals(type);
    }

    /**
     * Shortens a commit SHA.
     *
     * @param sha Full commit SHA, may be {@code null}.
     * @return First 10 characters of the SHA, or {@code null} if it is {@code null}.
     */
    public static String shortSha(String sha) {
        return sha == null ? null : sha.length() > 10 ? sha.substring(0, 10) : sha;
    }
}
