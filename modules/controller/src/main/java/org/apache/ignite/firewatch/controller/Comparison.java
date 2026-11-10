package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Two refs compared over a set of scenarios, run by {@link Comparisons}: the refs are resolved once, and every scenario
 * is an ordinary ABBA job pointing at it ({@link Job#comparisonId}). The row holds only what was asked for: its status
 * and results are always read from its jobs, so a repeat or a deleted job needs no bookkeeping.
 */
public final class Comparison {
    /** */
    public long id;

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
    public int priority;

    /** */
    public String createdAt;

    /**
     * API views only: the state of the jobs: {@code queued}, {@code running}, {@code done}, {@code failed} or
     * {@code cancelled}.
     */
    public String status;

    /** The last job that ended, once none is left in the queue. */
    public String finishedAt;

    /**
     * A bisection of it runs: {@link #status} stays that of the jobs, so that a comparison does not wait for its
     * searches.
     */
    public boolean searching;

    /** When the last queued scenario and the running bisections should be done. */
    public String doneAt;

    /** */
    public String subjectA;

    /** */
    public String subjectB;

    /** */
    public String dateA;

    /** */
    public String dateB;

    /** Scenarios with a result, of {@code total}. */
    public int measured;

    /** */
    public int total;

    /** */
    public int regressions;

    /** */
    public int improvements;

    /** */
    public int noChange;

    /** */
    public List<Row> rows = new ArrayList<>();

    /** First-parent commits from A to B, what a bisection can search; only {@link Comparisons#get} fills it. */
    public int commits;

    /** The bisections started from its jobs, oldest first; only {@link Comparisons#get} fills it. */
    public List<Bisect> bisects = new ArrayList<>();

    /** One scenario: its latest job and what its finished runs show (repeats pooled). */
    public static final class Row {
        /** */
        public String scenario;

        /** */
        public Job job;

        /** */
        public String verdict;

        /** The summary {@link JobService#slim} leaves; null until a run of the scenario is done. */
        public JsonNode comparison;

        /** */
        public int runs;

        /** */
        public List<Long> jobIds = new ArrayList<>();
    }
}
