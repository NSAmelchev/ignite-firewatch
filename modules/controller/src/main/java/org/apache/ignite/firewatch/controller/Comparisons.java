package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates {@link Comparison}s and reads their state from their jobs. */
public final class Comparisons {
    /** */
    private static final Pattern PR = Pattern.compile("(?i)^pr/(\\d+)$");

    /** */
    private final Db db;

    /** */
    private final GitMirror mirror;

    /** */
    private final JobService jobs;

    /** */
    private final Scenarios scenarios;

    /** */
    private final Bisector bisector;

    /** */
    private volatile Notifier notifier;

    /**
     * Creates the comparisons service.
     *
     * @param db Database.
     * @param mirror Git mirror.
     * @param jobs Job service.
     * @param scenarios Known scenarios.
     * @param bisector Bisector.
     */
    public Comparisons(Db db, GitMirror mirror, JobService jobs, Scenarios scenarios, Bisector bisector) {
        this.db = db;
        this.mirror = mirror;
        this.jobs = jobs;
        this.scenarios = scenarios;
        this.bisector = bisector;
        jobs.onJobEnded(this::jobEnded);
    }

    /**
     * Sets the notifier.
     *
     * @param n Notifier.
     */
    public void notifier(Notifier n) {
        notifier = n;
    }

    /**
     * Queues one ABBA job per scenario: {@code target} against {@code base}. The refs are resolved once, so every
     * scenario measures the same two commits. Without a base a PR is compared as the watched branch against the branch
     * + PR, and any other ref against its merge base with the branch.
     *
     * @param base Base ref, or {@code null} to pick it automatically.
     * @param target Target ref.
     * @param names Scenario names.
     * @param priority Job priority.
     * @return The created comparison.
     */
    public Comparison create(String base, String target, List<String> names, int priority) throws IOException {
        if (names == null || names.isEmpty())
            throw new IllegalArgumentException("choose at least one scenario");

        for (String name : names) {
            if (scenarios.get(name) == null)
                throw new IllegalArgumentException("unknown scenario: " + name);
        }

        if (!mirror.ready())
            throw new IllegalStateException("the stand is still cloning the repository: Dashboard shows how far it is");

        if (target == null || target.isBlank())
            throw new IllegalArgumentException("target ref is required");

        Comparison c = resolve(base == null || base.isBlank() ? null : mirror.normalize(base),
            mirror.normalize(target));
        c.priority = priority;
        c.requestedBy = Who.current();

        for (String name : names) {
            if (db.hasActiveJob(Job.ABBA, c.shaA, c.shaB, name))
                throw new IllegalArgumentException(name + ": an identical job is already queued or running");
        }

        db.insertComparison(c);

        for (String name : names)
            jobs.enqueueComparison(c, name);

        Log.info("comparison #" + c.id + " queued: " + c.label + ", " + names.size() + " scenario(s)");

        return view(c, activeById());
    }

    /** The two sides of a comparison; {@code target} and {@code base} are normalized refs. */
    private Comparison resolve(String base, String target) throws IOException {
        Comparison c = new Comparison();
        Matcher pr = PR.matcher(target);

        if (pr.matches() && base == null) {
            // the usual question for a PR: current master vs current master + the PR (GitHub's test merge)
            String[] mm = mirror.pullMerge(Integer.parseInt(pr.group(1)));
            c.refA = mirror.branch() + " (" + Job.shortSha(mm[1]) + ")";
            c.shaA = mm[1];
            c.refB = target + " merged into " + mirror.branch();
            c.shaB = mm[0];
            c.label = target + ": " + mirror.branch() + " + PR vs " + mirror.branch() + " " + Job.shortSha(mm[1]);

            return c;
        }

        c.refB = target.trim();
        c.shaB = mirror.resolve(target);

        if (base != null) {
            c.refA = base;
            c.shaA = mirror.resolve(base);
            c.label = c.refB + " vs " + c.refA;

            return c;
        }

        c.shaA = mirror.mergeBase("refs/heads/" + mirror.branch(), c.shaB);
        c.refA = "merge-base(" + mirror.branch() + ", " + c.refB + ")";
        c.label = c.refB + " vs merge-base " + Job.shortSha(c.shaA);

        if (c.shaA.equals(c.shaB)) {
            // B is already in the branch: its merge-base is itself (an A/A by accident); compare with the parent
            // instead
            CommitInfo ci = mirror.info(c.shaB);

            if (ci == null || ci.parentSha == null || ci.parentSha.isBlank())
                throw new IllegalArgumentException(target
                    + " has no parent to compare with: set the baseline explicitly");

            c.shaA = ci.parentSha.split(" ")[0];
            c.refA = Job.shortSha(c.shaA) + " (parent)";
            c.label = c.refB + " vs parent " + Job.shortSha(c.shaA);
        }

        return c;
    }

    /**
     * Returns a comparison with its commits and bisections.
     *
     * @param id Comparison id.
     * @return The comparison.
     */
    public Comparison get(long id) {
        Comparison c = view(require(id), activeById());
        c.commits = commits(c);
        c.bisects = bisector.ofComparison(id);
        addBisects(c, bisector.etaSecOfComparisons());

        return c;
    }

    /**
     * The stand runs one job at a time, so the bisections end after the jobs of the comparison;
     * {@link Comparison#doneAt} covers both. With the worker paused nothing moves and there is no end to tell.
     */
    private void addBisects(Comparison c, Map<Long, Long> etaSec) {
        Long sec = etaSec.get(c.id);
        c.searching = sec != null;

        if (Config.isTrue(db.setting(Settings.WORKER_PAUSED, "false")))
            c.doneAt = null;
        else if (sec != null)
            c.doneAt = (c.doneAt == null ? Instant.now() : Instant.parse(c.doneAt)).plusSeconds(sec).toString();
    }

    /** */
    private int commits(Comparison c) {
        try {
            return mirror.ready() ? mirror.revList(c.shaA, c.shaB).size() : 0;
        }
        catch (IOException e) {
            return 0; // a test merge of a PR can be gone from the mirror: nothing to bisect then
        }
    }

    /**
     * Lists the latest comparisons.
     *
     * @param limit Maximum number of comparisons.
     * @return Comparisons, newest first.
     */
    public List<Comparison> list(int limit) {
        Map<Long, Job> active = activeById();
        Map<Long, Long> bisects = bisector.etaSecOfComparisons();
        List<Comparison> out = new ArrayList<>();

        for (Comparison c : db.comparisons(limit)) {
            out.add(view(c, active));
            addBisects(c, bisects);
        }

        return out;
    }

    /**
     * Takes its queued jobs out of the queue, stops the running one and its active bisections; what is finished stays.
     *
     * @param id Comparison id.
     * @return The comparison after cancelling.
     */
    public Comparison cancel(long id) throws IOException {
        Comparison c = require(id);

        for (Bisect b : db.bisectsOfComparison(id)) {
            if (b.active())
                bisector.cancel(b.id, true, false);
        }

        List<Job> list = db.jobsOfComparison(id);
        int cancelled = 0;

        for (Job j : list) { // queued first: the worker must not pick the next one when the running one stops
            if (Job.QUEUED.equals(j.status) && jobs.cancel(j.id))
                cancelled++;
        }

        for (Job j : list) {
            if (Job.RUNNING.equals(j.status) && jobs.cancel(j.id))
                cancelled++;
        }

        Log.info("comparison #" + id + " cancelled: " + cancelled + " job(s)");

        return view(c, activeById());
    }

    /**
     * Removes an ended comparison with its jobs, their runs and files; a finished bisection keeps its own steps.
     *
     * @param id Comparison id.
     */
    public void delete(long id) throws IOException {
        require(id);
        List<Job> list = db.jobsOfComparison(id);

        for (Job j : list) {
            if (j.active())
                throw new IllegalArgumentException("comparison #" + id + " still has job #" + j.id
                    + " active: cancel it first");
        }

        for (Bisect b : db.bisectsOfComparison(id)) {
            if (b.active())
                throw new IllegalArgumentException("comparison #" + id + " still has bisect #" + b.id
                    + " active: cancel it first");
        }

        for (Job j : list)
            jobs.delete(j.id);

        db.deleteComparison(id);
        Log.info("comparison #" + id + " deleted");
    }

    /**
     * Keeps the newest comparisons up to {@link Settings#RETENTION_KEEP_COMPARISONS}; an active one stays whatever its
     * age. Returns how many went.
     *
     * @return Number of removed comparisons.
     */
    public int prune() {
        int keep = Integer.parseInt(db.setting(Settings.RETENTION_KEEP_COMPARISONS, "0"));

        if (keep <= 0)
            return 0;

        List<Comparison> all = db.comparisons(Integer.MAX_VALUE);
        int removed = 0;

        for (Comparison c : all.subList(Math.min(keep, all.size()), all.size())) {
            try {
                delete(c.id);
                removed++;
            }
            catch (IllegalArgumentException e) {
                Log.info("retention: comparison #" + c.id + " stays: " + e.getMessage());
            }
            catch (IOException e) {
                Log.warn("retention: cannot delete comparison #" + c.id + ": " + e.getMessage());
            }
        }

        return removed;
    }

    /** */
    private Comparison require(long id) {
        Comparison c = db.comparison(id);

        if (c == null)
            throw new NoSuchElementException("no comparison " + id);

        return c;
    }

    /** The active jobs of the whole queue with their ETA, which is cumulative over all of them. */
    private Map<Long, Job> activeById() {
        List<Job> active = db.jobs("active", Integer.MAX_VALUE);
        jobs.addEta(active);
        Map<Long, Job> out = new LinkedHashMap<>();

        for (Job j : active)
            out.put(j.id, j);

        return out;
    }

    /** Fills what the row does not hold from the jobs: one row per scenario in the order they were queued. */
    private Comparison view(Comparison c, Map<Long, Job> active) {
        CommitInfo a = db.commit(c.shaA), b = db.commit(c.shaB);
        c.subjectA = a == null ? null : a.subject;
        c.subjectB = b == null ? null : b.subject;
        c.dateA = a == null ? null : a.committedAt;
        c.dateB = b == null ? null : b.committedAt;
        Map<String, List<Job>> byScenario = new LinkedHashMap<>();

        for (Job j : db.jobsOfComparison(c.id))
            byScenario.computeIfAbsent(j.scenario, k -> new ArrayList<>()).add(active.getOrDefault(j.id, j));

        boolean running = false, queued = false, failed = false, cancelled = false;
        Instant end = null;
        c.rows = new ArrayList<>();

        for (Map.Entry<String, List<Job>> e : byScenario.entrySet()) {
            Comparison.Row r = row(e.getKey(), e.getValue());
            c.rows.add(r);
            running |= Job.RUNNING.equals(r.job.status);
            queued |= Job.QUEUED.equals(r.job.status);
            failed |= r.comparison == null && !r.job.active() && !Job.CANCELLED.equals(r.job.status);
            cancelled |= r.comparison == null && Job.CANCELLED.equals(r.job.status);

            if (r.job.active() && r.job.etaStartAt != null && r.job.etaSec != null) {
                Instant jobEnd = Instant.parse(r.job.etaStartAt).plusSeconds(r.job.etaSec);
                end = end == null || jobEnd.isAfter(end) ? jobEnd : end;
            }

            if (r.comparison != null) {
                c.measured++;
                c.regressions += Stats.REGRESSION.equals(r.verdict) ? 1 : 0;
                c.improvements += Stats.IMPROVEMENT.equals(r.verdict) ? 1 : 0;
                c.noChange += Stats.NO_CHANGE.equals(r.verdict) ? 1 : 0;
            }

            if (!r.job.active() && r.job.finishedAt != null
                && (c.finishedAt == null || r.job.finishedAt.compareTo(c.finishedAt) > 0))
                c.finishedAt = r.job.finishedAt;
        }

        c.total = c.rows.size();
        c.doneAt = end == null ? null : end.toString();
        c.status = running || (queued && c.measured > 0) ? Job.RUNNING : queued ? Job.QUEUED
            : failed ? Job.FAILED : cancelled ? Job.CANCELLED : Job.DONE;

        if (running || queued)
            c.finishedAt = null;

        return c;
    }

    /** {@code list}: every job of one scenario, oldest first. */
    private Comparison.Row row(String scenario, List<Job> list) {
        Comparison.Row r = new Comparison.Row();
        r.scenario = scenario;
        r.job = list.get(list.size() - 1);
        List<Job> done = new ArrayList<>();

        for (Job j : list) {
            if (Job.DONE.equals(j.status))
                done.add(j);
        }

        if (!done.isEmpty()) {
            Job last = done.get(done.size() - 1);
            List<Job> same = JobService.repeatsInComparison(last, done);
            Stats.Comparison pooled = same.size() > 1 ? jobs.pooled(same) : null;

            if (pooled != null) {
                last.comparison = Json.MAPPER.valueToTree(pooled);
                last.verdict = pooled.verdict;
            }
            else
                same = List.of(last);

            JobService.slim(same);
            r.comparison = last.comparison;
            r.verdict = last.verdict;
            r.runs = same.size();

            for (Job j : same)
                r.jobIds.add(j.id);
        }

        JobService.slim(List.of(r.job));

        return r;
    }

    /** */
    private void jobEnded(Job j) {
        if (j == null || j.comparisonId == null)
            return;

        Comparison c = db.comparison(j.comparisonId);

        if (c == null)
            return;

        for (Job x : db.jobsOfComparison(c.id)) {
            if (x.active())
                return; // a cancelled queue ends job by job: only the last one reports
        }

        Notifier n = notifier;

        if (n != null)
            n.comparisonEnded(view(c, Map.of()));

        prune();
    }
}
