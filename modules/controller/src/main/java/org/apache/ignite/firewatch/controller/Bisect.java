package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An automatic bisection of a segment that moved a metric, run by {@link Bisector}: it follows every part of the
 * segment that moves the metric on its own, down to single commits. The steps are ordinary ABBA jobs pointing at it
 * ({@link Job#bisectId}).
 */
public final class Bisect {
    /** */
    public static final String RUNNING = "running";

    /** A step failed or was cancelled from the queue (or the step limit was reached): Resume queues it again. */
    public static final String STOPPED = "stopped";

    /** Ended with at least one commit that moves the metric on its own. */
    public static final String FOUND = "found";

    /**
     * Ended without such a commit, but with ranges that move while none of their halves does: several small changes.
     */
    public static final String SPREAD = "spread";

    /**
     * Ended without a commit, while the change of the segment showed up again: too close to the noise for this search,
     * a deep one may find it.
     */
    public static final String INCONCLUSIVE = "inconclusive";

    /** Ended with nothing that reproduced: the change of the segment was noise. */
    public static final String LOST = "lost";

    /** */
    public static final String CANCELLED = "cancelled";

    /** */
    public long id;

    /** */
    public String scenario;

    /** Key of a {@link Stats#METRICS} metric the bisection follows. */
    public String metric;

    /** What the root segment showed on the metric: {@code regression} or {@code improvement}. */
    public String direction;

    /** */
    public long rootJob;

    /** */
    public String shaA;

    /** */
    public String shaB;

    /** */
    public int priority;

    /** Who started it: its steps, queued later by the worker, are queued in that name. */
    public String requestedBy;

    /** */
    public String status;

    /** */
    public String reason;

    /** The found commit with the largest delta (all of them are in {@link State#findings}). */
    public String resultSha;

    /** */
    public String createdAt;

    /** */
    public String finishedAt;

    /** */
    public State state = new State();

    /**
     * API views only: the comparison of the root job, the step being measured, commit subjects by sha, a rough estimate
     * of what is left.
     */
    public Long comparisonId;

    /** */
    public Job waiting;

    /** */
    public Map<String, String> subjects = new LinkedHashMap<>();

    /** */
    public Integer stepsLeft;

    /** */
    public Long etaSec;

    /**
     * Only for one bisection, which the page draws: the ends of its ranges counted in commits from {@link #shaA}, and
     * these commits.
     */
    public Map<String, Integer> positions = new LinkedHashMap<>();

    /** */
    public Map<String, CommitInfo> commits = new LinkedHashMap<>();

    /**
     * Checks whether the bisection is still open.
     *
     * @return {@code True} if the bisection is running or stopped.
     */
    public boolean active() {
        return RUNNING.equals(status) || STOPPED.equals(status);
    }

    /** What the bisection knows and waits for; stored as JSON in the row. */
    public static final class State {
        /** The root segment's result the bisection started from. */
        public Result root;

        /** Ranges still to search, the one being searched first; the rest by |Δ|, largest first. */
        public List<Range> open = new ArrayList<>();

        /** What the search ended on, range by range. */
        public List<Finding> findings = new ArrayList<>();

        /** The measurement in progress (or to queue again on Resume): its role, pair and job. */
        public String waitRole;

        /** */
        public String waitA;

        /** */
        public String waitB;

        /** */
        public Long waitJob;

        /** */
        public int maxSteps;

        /** */
        public List<Step> steps = new ArrayList<>();

        /**
         * A fresh run of the whole segment, measured when a search that found nothing has no other proof that the
         * change is real.
         */
        public Result again;

        /** The deep search of {@link Bisector#deep}: {@link #steps} from {@link #deepFrom} on are its own. */
        public boolean deep;

        /** */
        public int deepFrom;

        /** The noise floor of the metric when the deep search started: what its decisions are measured against. */
        public double floorPct;
    }

    /** A range that moves the metric: the change is in the commits after {@code a} up to {@code b}. */
    public static final class Range {
        /** */
        public String a;

        /** */
        public String b;

        /** */
        public int commits;

        /** Its own result when it was measured; null when it was inferred from its quiet sibling. */
        public Result cur;

        /** Δ % its halves are compared with: the measured Δ, or the parent's Δ minus the quiet sibling's. */
        public double est;

        /** */
        public Result left;

        /** */
        public Result right;

        /** Measured once more because neither half moved (a range), or because its Δ was small (a commit). */
        public boolean repeated;

        /** Deep search: levels in a row where the search went on although neither half carried the change. */
        public int weak;

        /** Deep search: the shortest range above that still carried the change, when this one is not known to. */
        public Range solid;

        /** Deep search: a branch of a split, entered once it has shown that it holds. */
        public boolean branch;
    }

    /** One outcome of the search. */
    public static final class Finding {
        /**
         * {@code found}: a commit that moves the metric; {@code spread}: a range that moves while its halves do not;
         * {@code lost}: a range or commit that did not reproduce.
         */
        public String kind;

        /** */
        public String a;

        /** */
        public String b;

        /** */
        public int commits;

        /** */
        public Result result;

        /** */
        public String note;
    }

    /** The metric of a pair as its finished runs show it (repeats pooled). */
    public static final class Result {
        /** */
        public double deltaPct;

        /** */
        public double thresholdPct;

        /** The metric's own verdict: {@code regression}, {@code improvement} or {@code no-change}. */
        public String verdict;

        /** */
        public int runs;

        /** */
        public List<Long> jobIds = new ArrayList<>();

        /** Deep search: Δ of every run the median is taken over. */
        public List<Double> deltas = new ArrayList<>();

        /** Deep search: runs left out because their phases disagree, see {@link JobService#medianResult}. */
        public int dropped;

        /** Deep search: every run of the pair is such a run, so these are all it has. */
        public boolean broken;
    }

    /** One measurement: what was measured, its result and the decision taken from it. */
    public static final class Step {
        /** */
        public int n;

        /** {@code left}, {@code right}, {@code repeat}, {@code leaf}, {@code confirm} or {@code root}. */
        public String role;

        /** */
        public String a;

        /** */
        public String b;

        /** */
        public int commits;

        /** */
        public Long jobId;

        /** The pair had been measured before (a manual split, an earlier bisection): no job was queued. */
        public boolean reused;

        /** */
        public Result result;

        /** */
        public String decision;
    }
}
