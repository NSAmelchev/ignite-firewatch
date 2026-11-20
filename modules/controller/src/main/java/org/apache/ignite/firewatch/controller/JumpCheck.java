package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A jump between two runs of master vs release, checked by a run of its own. When a run ends it is set against the
 * previous run of its scenario: what moved between them is what the commits between the two heads did, only noisier,
 * since it is the difference of two measurements. A metric that moved beyond its threshold queues one ABBA of the two
 * heads, an ordinary run of the branch: Still open lists what it shows as new, a search starts from it, a regression
 * it shows is notified, and noise stays a run without a verdict.
 */
public final class JumpCheck {
    /** */
    private final Db db;

    /** */
    private final GitMirror mirror;

    /** */
    private final JobService jobs;

    /**
     * Creates the check and makes it follow the jobs that end.
     *
     * @param db Database.
     * @param mirror Git mirror of the watched repository.
     * @param jobs Job service to queue the checks with.
     */
    public JumpCheck(Db db, GitMirror mirror, JobService jobs) {
        this.db = db;
        this.mirror = mirror;
        this.jobs = jobs;
        jobs.onJobEnded(this::jobEnded);
    }

    /** A run of master vs release that ended: a jump since the previous one queues its check. */
    private void jobEnded(Job run) {
        if (!JobService.SRC_RELEASE.equals(run.source) || !Job.DONE.equals(run.status) || run.plugin != null)
            return;

        Job prev = jobs.previousRelease(run);

        if (prev == null)
            return;

        String jump = jump(prev.comparison, run.comparison);

        if (jump == null)
            return;

        if (db.activeJobOfPair(prev.shaB, run.shaB, run.scenario) != null
            || !db.doneJobsOfPair(prev.shaB, run.shaB, run.scenario).isEmpty())
            return; // measured or about to be: the run of the pair says it already

        try {
            int commits = mirror.revList(prev.shaB, run.shaB).size();
            Job check = jobs.enqueueSegment(prev.shaB, run.shaB, commits, run.scenario, run.priority,
                JobService.SRC_AUTO);
            Log.info("jump check: " + run.scenario + " " + jump + " over " + commits + " commits since #" + prev.id
                + ": queued #" + check.id);
        }
        catch (IOException | RuntimeException e) {
            Log.error("jump check of job #" + run.id + " failed", e);
        }
    }

    /**
     * The largest move between two runs of master vs release beyond the threshold of the later one, as "throughput
     * -4.0 %"; null when nothing moved that far.
     *
     * @param prev Comparison of the earlier run.
     * @param run Comparison of the later run.
     * @return The move, or {@code null}.
     */
    static String jump(JsonNode prev, JsonNode run) {
        if (prev == null || run == null)
            return null;

        String out = null;
        double most = 0;

        for (String m : Attention.METRICS) {
            JsonNode a = prev.path("metrics").path(m), b = run.path("metrics").path(m);

            if (!a.hasNonNull("deltaPct") || !b.hasNonNull("deltaPct"))
                continue;

            double d = jumpPct(a.get("deltaPct").asDouble(), b.get("deltaPct").asDouble());
            double over = Math.abs(d) / Math.max(b.path("thresholdPct").asDouble(), 1e-9);

            if (over > 1 && over > most) {
                most = over;
                out = m + " " + String.format(Locale.ROOT, "%+.1f", d) + " %";
            }
        }

        return out;
    }

    /**
     * What moved between two runs against the same release, %: the later level over the earlier one.
     *
     * @param prevPct Δ of the earlier run against the release, %.
     * @param runPct Δ of the later run, %.
     * @return The move, %.
     */
    static double jumpPct(double prevPct, double runPct) {
        return ((1 + runPct / 100) / (1 + prevPct / 100) - 1) * 100;
    }
}
