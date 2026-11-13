package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Automatic bisection of a measured segment that moved a metric. It follows every part of the segment that moves the
 * metric on its own, the largest first: a step measures the left half of a range; when it moved, the right half too
 * (a gradual decline or a second change may sit there); a quiet left half sends the search right without measuring.
 * Each moving half is searched further, down to single commits; a range that moves while neither half does is
 * reported as spread over several small changes. Started only by the user; the steps are ordinary ABBA jobs (source
 * {@code bisect}), so the map, pooling and the commit pages see them, and pairs measured before are reused.
 *
 * <p>That search is cheap, and it takes one run per decision against the noise floor, so a change close to the floor
 * slips through it. Such a bisection ends {@link Bisect#INCONCLUSIVE}, and {@link #deep} searches the segment again on
 * the user's word: it asks which half carries the change rather than whether a half beats the floor, measures both on
 * every level and adds runs while the answer is close.
 */
public final class Bisector {
    /** Default priority of the steps: ahead of ordinary manual jobs, so a bisection does not wait behind the queue. */
    public static final int AHEAD = 2;

    /**
     * A quiet left half (no verdict) sends the search right unmeasured only when it moved at most this share of the
     * range.
     */
    static final double QUIET_SHARE = 1.0 / 3;

    /**
     * A commit is measured once more when its delta is under this many thresholds: more branches, more chances of
     * noise.
     */
    static final double CONFIRM_BELOW = 2;

    /** Deep search: runs of a half before a close call is decided anyway, and runs a commit has to stand. */
    static final int DEEP_RUNS = 4;

    /** Deep search: runs of a pair that are all broken before they are taken as they are. */
    static final int BROKEN_RUNS = 3;

    /** Deep search: levels in a row without a half that carries the change before the search gives up on the branch. */
    static final int WEAK_LEVELS = 2;

    /** What {@link #deepChoice} says: the left half carries the change. */
    static final String LEFT = "left";

    /** What {@link #deepChoice} says: the right half carries the change. */
    static final String RIGHT = "right";

    /** What {@link #deepChoice} says: each half carries a change. */
    static final String BOTH = "both";

    /** What {@link #deepChoice} says: too close to call, measure again. */
    static final String MORE = "more";

    /** */
    private static final String TOO_CLOSE = "the change is real, but too close to the noise for this search: a deep "
        + "search measures it properly";

    /** */
    private final Db db;

    /** */
    private final GitMirror mirror;

    /** */
    private final JobService jobs;

    /** */
    private volatile Notifier notifier;

    /**
     * Creates a bisector.
     *
     * @param db Database.
     * @param mirror Git mirror of the watched repository.
     * @param jobs Job service to queue steps with.
     */
    public Bisector(Db db, GitMirror mirror, JobService jobs) {
        this.db = db;
        this.mirror = mirror;
        this.jobs = jobs;
        jobs.onJobEnded(this::jobEnded);
    }

    /**
     * Sets the notifier told about bisection results.
     *
     * @param n Notifier.
     */
    public void notifier(Notifier n) {
        notifier = n;
    }

    /**
     * Starts a bisection of the finished segment job {@code jobId} along {@code metric}.
     *
     * @param jobId Id of the finished segment job.
     * @param metric Metric key; throughput when blank.
     * @param priority Priority of the queued steps, or {@code null} for the default.
     * @return The started bisection.
     */
    public synchronized Bisect start(long jobId, String metric, Integer priority) throws IOException {
        Job root = db.job(jobId);

        if (root == null)
            throw new java.util.NoSuchElementException("no job " + jobId);

        if (!Job.ABBA.equals(root.type) || !Job.DONE.equals(root.status) || root.shaA.equals(root.shaB))
            throw new IllegalArgumentException("only a finished ABBA job of two commits can be bisected");

        String m = metric == null || metric.isBlank() ? "throughput" : metric;

        if (Stats.METRICS.stream().noneMatch(d -> d.key.equals(m)))
            throw new IllegalArgumentException("unknown metric: " + m);

        List<Bisect> active = db.bisects(root.scenario, true, 1);

        if (!active.isEmpty())
            throw new IllegalArgumentException("bisect #" + active.get(0).id + " of " + root.scenario
                + " is still active: cancel it first");

        List<String> range = mirror.revList(root.shaA, root.shaB);

        if (range.size() < 2)
            throw new IllegalArgumentException("job #" + jobId + " covers " + range.size()
                + " commit(s): nothing to bisect");

        Bisect.Result r = jobs.result(root, db.doneJobsOfPair(root.shaA, root.shaB, root.scenario), m);

        if (r == null || Stats.NO_CHANGE.equals(r.verdict))
            throw new IllegalArgumentException("job #" + jobId + " shows no " + m + " verdict to follow");

        Bisect b = new Bisect();
        b.scenario = root.scenario;
        b.metric = m;
        b.direction = r.verdict;
        b.rootJob = root.id;
        b.shaA = root.shaA;
        b.shaB = root.shaB;
        b.priority = priority == null ? AHEAD : priority;
        b.requestedBy = Who.current();
        b.status = Bisect.RUNNING;
        Bisect.State s = b.state;
        s.root = r;
        s.open.add(range(root.shaA, root.shaB, range.size(), r, r.deltaPct));
        // ample for two changes found down to their commits (both halves on every level, repeats); only a guard
        s.maxSteps = 2 * (2 * levels(range.size()) + 4);
        db.insertBisect(b);
        Log.info("bisect #" + b.id + " started: " + b.metric + " " + b.direction + " " + fmt(r.deltaPct) + " over "
            + range.size() + " commits " + Job.shortSha(b.shaA) + ".." + Job.shortSha(b.shaB) + " [" + b.scenario
            + "]");
        advance(b);

        return view(b);
    }

    /**
     * Cancels a bisection: no new steps; its queued step leaves the queue, the running one is stopped too unless
     * {@code stopRunning} is false (then it finishes as an ordinary run). Finished steps stay on the map as ordinary
     * runs unless {@code deleteFinished}.
     *
     * @param id Bisection id.
     * @param stopRunning Whether to stop its running job.
     * @param deleteFinished Whether to delete its finished jobs.
     * @return The cancelled bisection.
     */
    public synchronized Bisect cancel(long id, boolean stopRunning, boolean deleteFinished) throws IOException {
        Bisect b = require(id);

        if (!b.active())
            throw new IllegalArgumentException("bisect #" + id + " is already " + b.status);

        b.status = Bisect.CANCELLED;
        b.reason = "cancelled by the user";
        b.finishedAt = Db.now();
        b.state.waitJob = null;
        db.updateBisect(b); // first: the steps cancelled below must not move it any more
        int cancelled = 0, deleted = 0;

        for (Job j : db.jobsOfBisect(id)) {
            if (Job.QUEUED.equals(j.status) || (Job.RUNNING.equals(j.status) && stopRunning)) {
                if (jobs.cancel(j.id))
                    cancelled++;
            }
        }

        if (deleteFinished) {
            for (Job j : db.jobsOfBisect(id)) {
                if (!j.active() && jobs.delete(j.id))
                    deleted++;
            }
        }

        Log.info("bisect #" + id + " cancelled: " + cancelled + " job(s) cancelled, " + deleted + " deleted");

        return view(b);
    }

    /**
     * Searches again a bisection that ended without a commit: the whole segment from the top, both halves on every
     * level, as many runs as a decision needs. It goes on in the same bisection, and what was measured before counts.
     *
     * @param id Bisection id.
     * @param priority Priority of the queued steps, or {@code null} for the default.
     * @return The bisection that goes on.
     */
    public synchronized Bisect deep(long id, Integer priority) throws IOException {
        Bisect b = require(id);
        Bisect.State s = b.state;

        if (s.deep || !List.of(Bisect.INCONCLUSIVE, Bisect.SPREAD, Bisect.LOST).contains(b.status))
            throw new IllegalArgumentException("bisect #" + id + " is " + b.status
                + (s.deep ? " after a deep search" : "") + ": no deep search for it");

        List<Bisect> active = db.bisects(b.scenario, true, 1);

        if (!active.isEmpty())
            throw new IllegalArgumentException("bisect #" + active.get(0).id + " of " + b.scenario
                + " is still active: cancel it first");

        int commits = mirror.revList(b.shaA, b.shaB).size();
        s.deep = true;
        s.deepFrom = s.steps.size();
        s.floorPct = jobs.noise(b.scenario).floors.getOrDefault(b.metric, s.root.thresholdPct);
        s.findings.clear();
        s.open.clear();
        s.open.add(range(b.shaA, b.shaB, commits, s.root, s.root.deltaPct));
        // a change on every level with its repeats and a second branch fit in: only a guard
        s.maxSteps = s.steps.size() + 6 * levels(commits) + 8;
        b.status = Bisect.RUNNING;
        b.reason = null;
        b.resultSha = null;
        b.finishedAt = null;

        if (priority != null)
            b.priority = priority;

        Log.info("bisect #" + id + ": deep search of " + commits + " commits " + Job.shortSha(b.shaA) + ".."
            + Job.shortSha(b.shaB) + " [" + b.scenario + "]");
        advance(b);

        return view(b);
    }

    /**
     * Removes an ended bisection: only the record goes, its steps stay as runs of the branch and count in later
     * searches.
     *
     * @param id Bisection id.
     */
    public synchronized void delete(long id) {
        Bisect b = require(id);

        if (b.active())
            throw new IllegalArgumentException("bisect #" + id + " is " + b.status + ": cancel it first");

        db.deleteBisect(id);
        Log.info("bisect #" + id + " deleted");
    }

    /**
     * Continues a stopped bisection: queues the step that failed (or was cancelled) again.
     *
     * @param id Bisection id.
     * @return The resumed bisection.
     */
    public synchronized Bisect resume(long id) throws IOException {
        Bisect b = require(id);

        if (!Bisect.STOPPED.equals(b.status))
            throw new IllegalArgumentException("bisect #" + id + " is " + b.status + ", not stopped");

        Bisect.State s = b.state;
        s.maxSteps = Math.max(s.maxSteps, s.steps.size() + 10);
        b.status = Bisect.RUNNING;
        b.reason = null;
        Log.info("bisect #" + id + " resumed");

        if (s.waitRole != null && s.waitJob == null)
            measure(b, s.waitRole, s.waitA, s.waitB, true);

        advance(b);

        return view(b);
    }

    /**
     * After a restart: a step job that ended while nobody listened (interrupted by the restart, or finished just
     * before it) is handled now; an interrupted one is queued again.
     */
    public synchronized void recover() {
        for (Bisect b : db.bisects(null, true, 100)) {
            if (!Bisect.RUNNING.equals(b.status) || b.state.waitJob == null)
                continue;

            Job j = db.job(b.state.waitJob);

            if (j == null || !j.active())
                step(b, j);
        }

        // bisections older than this status: lost then covered a real change the search could not follow too
        for (Bisect b : db.bisects(null, false, Integer.MAX_VALUE)) {
            if (Bisect.LOST.equals(b.status) && !b.state.deep && Boolean.TRUE.equals(reproduced(b))) {
                b.status = Bisect.INCONCLUSIVE;
                b.reason = TOO_CLOSE;
                db.updateBisect(b);
            }
        }
    }

    /**
     * Returns a bisection with its computed view.
     *
     * @param id Bisection id.
     * @return The bisection.
     */
    public Bisect get(long id) {
        Bisect b = views(List.of(require(id))).get(0);
        Bisect.State s = b.state;
        List<String> shas = new ArrayList<>(List.of(b.shaA, b.shaB));
        s.steps.forEach(x -> shas.addAll(List.of(x.a, x.b)));
        s.open.forEach(x -> shas.addAll(List.of(x.a, x.b)));
        s.findings.forEach(x -> shas.addAll(List.of(x.a, x.b)));

        if (s.waitA != null)
            shas.addAll(List.of(s.waitA, s.waitB));

        shas.forEach(sha -> b.commits.computeIfAbsent(sha, db::commit));

        try {
            List<String> range = mirror.revList(b.shaA, b.shaB);
            b.positions.put(b.shaA, 0);

            for (int i = 0; i < range.size(); i++) {
                if (shas.contains(range.get(i)))
                    b.positions.put(range.get(i), i + 1);
            }
        }
        catch (IOException e) {
            Log.warn("bisect #" + id + ": no map of the search: " + e.getMessage());
        }

        return b;
    }

    /**
     * Lists bisections, newest first.
     *
     * @param scenario Scenario name, or {@code null} for all.
     * @param activeOnly Whether to keep only the running and stopped ones.
     * @param limit Maximum number of bisections.
     * @return Bisections.
     */
    public List<Bisect> list(String scenario, boolean activeOnly, int limit) {
        return views(db.bisects(scenario, activeOnly, limit));
    }

    /**
     * The bisections started from the jobs of a comparison, oldest first.
     *
     * @param comparisonId Comparison id.
     * @return Bisections of the comparison.
     */
    public List<Bisect> ofComparison(long comparisonId) {
        return views(db.bisectsOfComparison(comparisonId));
    }

    /**
     * Keeps the newest ended bisections up to {@link Settings#RETENTION_KEEP_BISECTS}; returns how many went.
     *
     * @return Number of removed bisections.
     */
    public int prune() {
        int keep = Integer.parseInt(db.setting(Settings.RETENTION_KEEP_BISECTS, "0"));

        if (keep <= 0)
            return 0;

        List<Bisect> ended = db.bisects(null, false, Integer.MAX_VALUE).stream().filter(b -> !b.active()).toList();

        for (Bisect b : ended.subList(Math.min(keep, ended.size()), ended.size()))
            db.deleteBisect(b.id);

        int removed = Math.max(0, ended.size() - keep);

        if (removed > 0)
            Log.info("retention: " + removed + " old bisection(s) removed");

        return removed;
    }

    /**
     * When the running bisections should all be done; null when none runs.
     *
     * @return Time the bisections should be done, or {@code null} if none runs.
     */
    public String doneAt() {
        List<Bisect> running = running();

        if (running.isEmpty())
            return null;

        Map<String, Long> step = new HashMap<>();
        long sec = 0;

        for (Bisect b : running)
            sec += stepsLeft(b) * step.computeIfAbsent(b.scenario, jobs::stepSeconds);

        return java.time.Instant.now().plusSeconds(sec).toString();
    }

    /** */
    private List<Bisect> running() {
        return db.bisects(null, true, 100).stream().filter(b -> Bisect.RUNNING.equals(b.status)).toList();
    }

    /**
     * {@link #view} of each, with what is left: steps of all running bisections take turns in the queue, so each waits
     * for the others.
     */
    private List<Bisect> views(List<Bisect> list) {
        list.forEach(this::view);
        List<Bisect> running = running();
        Map<String, Long> step = new HashMap<>();

        for (Bisect b : list) {
            if (!Bisect.RUNNING.equals(b.status))
                continue;

            b.stepsLeft = stepsLeft(b);
            b.etaSec = etaSec(b, running, step);
        }

        return list;
    }

    /** */
    private long etaSec(Bisect b, List<Bisect> running, Map<String, Long> step) {
        long sec = 0, left = stepsLeft(b);

        for (Bisect o : running)
            sec += Math.min(stepsLeft(o), left) * step.computeIfAbsent(o.scenario, jobs::stepSeconds);

        return sec;
    }

    /**
     * How long the running bisections of each comparison should take: the last of them counts.
     *
     * @return Seconds to go by comparison id.
     */
    public Map<Long, Long> etaSecOfComparisons() {
        List<Bisect> running = running();
        Map<String, Long> step = new HashMap<>();
        Map<Long, Long> out = new HashMap<>();

        for (Bisect b : running) {
            Job root = db.job(b.rootJob);

            if (root != null && root.comparisonId != null)
                out.merge(root.comparisonId, etaSec(b, running, step), Math::max);
        }

        return out;
    }

    /**
     * Rough: a level is one half, often two; a commit is measured once more when it was inferred. A deep level near the
     * noise takes about four runs of its halves.
     */
    private static int stepsLeft(Bisect b) {
        int left = 0;

        for (Bisect.Range r : b.state.open)
            left += b.state.deep ? levels(r.commits) * 4 + DEEP_RUNS : levels(r.commits) * 3 / 2
                + (r.commits <= 1 && r.cur != null ? 0 : 1);

        return left;
    }

    /**
     * Counts the running and stopped bisections.
     *
     * @return Number of active bisections.
     */
    public int activeCount() {
        return db.bisects(null, true, 1000).size();
    }

    /** */
    private synchronized void jobEnded(Job j) {
        if (j == null)
            return;

        for (Bisect b : db.bisects(null, true, 100)) {
            Long w = b.state.waitJob;

            if (Bisect.RUNNING.equals(b.status) && w != null && w == j.id)
                step(b, j);
        }
    }

    /** The awaited step job {@code j} ended (null: it was deleted): record it and go on, or stop. */
    private void step(Bisect b, Job j) {
        Bisect.State s = b.state;

        try {
            s.waitJob = null;

            if (j != null && Job.DONE.equals(j.status)) {
                // the segment itself is measured for a proof that does not lean on the run that started the search
                Bisect.Result r = "root".equals(s.waitRole) ? jobs.result(j, List.of(j), b.metric)
                    : resultOf(b, s.waitA, s.waitB);

                if (r == null) {
                    stop(b, "step job #" + j.id + " has no " + b.metric + " result");

                    return;
                }

                record(b, s.waitRole, s.waitA, s.waitB, j.id, false, r);
                apply(b, s.waitRole, r);
                s.waitRole = null;
                advance(b);
            }
            else if (j != null && Job.INTERRUPTED.equals(j.status)) {
                Log.info("bisect #" + b.id + ": step job #" + j.id + " was interrupted by a restart, queued again");
                measure(b, s.waitRole, s.waitA, s.waitB, true);
                advance(b);
            }
            else
                stop(b, j == null ? "the step job was deleted"
                    : "step job #" + j.id + " " + j.status + (j.error == null ? "" : ": " + j.error));
        }
        catch (IOException | RuntimeException e) {
            Log.error("bisect #" + b.id + " failed", e);
            stop(b, "internal error: " + e.getMessage());
        }
    }

    /** Decides and measures until a step has to wait for a job or the bisection ends; saves the state. */
    private void advance(Bisect b) throws IOException {
        Bisect.State s = b.state;

        for (int guard = 0; guard < 500 && Bisect.RUNNING.equals(b.status) && s.waitJob == null; guard++) {
            if (s.open.isEmpty()) {
                finish(b);
                break;
            }

            Bisect.Range r = s.open.get(0);

            if (s.deep) {
                deepStep(b, r);
                continue;
            }

            if (r.commits <= 1) {
                String sha = Job.shortSha(r.b);

                if (r.cur == null)
                    measure(b, "leaf", r.a, r.b, false);
                else if (!moved(b, r.cur)) {
                    decide(b, sha + " alone does not move it");
                    close(b, "lost", r.cur, sha + " alone does not move " + b.metric);
                }
                else if (Math.abs(r.cur.deltaPct) < CONFIRM_BELOW * r.cur.thresholdPct && !r.repeated) {
                    decide(b, "close to the threshold: measured once more");
                    r.repeated = true;
                    measure(b, "confirm", r.a, r.b, true);
                }
                else {
                    decide(b, "found " + sha);
                    close(b, "found", r.cur, null);
                }

                continue;
            }

            List<String> commits = mirror.revList(r.a, r.b);

            if (commits.size() <= 1) { // the history moved under us (should not happen on a first-parent branch)
                r.commits = commits.size();
                continue;
            }

            String mid = commits.get(commits.size() / 2 - 1);
            int leftN = commits.size() / 2, rightN = commits.size() - leftN;

            if (r.left == null) {
                measure(b, "left", r.a, mid, false);
                continue;
            }

            boolean mL = moved(b, r.left);
            double f = Math.abs(r.est) < 1e-9 ? 0 : r.left.deltaPct / r.est;

            if (r.right == null && !mL && f <= QUIET_SHARE) {
                Bisect.Result known = jobs.pairResult(mid, r.b, b.scenario, b.metric);

                if (known != null) { // measured before: decide on it rather than on an estimate
                    decide(b, "the left half is quiet; the right half was measured before");
                    record(b, "right", mid, r.b, known.jobIds.get(known.jobIds.size() - 1), true, known);
                    r.right = known;
                    continue;
                }

                decide(b, "→ right (not measured): the left half is quiet, " + share(f) + " of the range");
                replace(b, List.of(range(mid, r.b, rightN, null, r.est - r.left.deltaPct)));
                continue;
            }

            if (r.right == null) {
                decide(b, mL ? "the left half moved: the right one is measured too" : "unclear (" + share(f)
                    + " of the range): the right half is measured too");
                measure(b, "right", mid, r.b, false);
                continue;
            }

            boolean mR = moved(b, r.right);

            if (mL || mR) {
                List<Bisect.Range> next = new ArrayList<>();

                if (mL)
                    next.add(range(r.a, mid, leftN, r.left, r.left.deltaPct));

                if (mR)
                    next.add(range(mid, r.b, rightN, r.right, r.right.deltaPct));

                decide(b, mL && mR ? "→ both halves moved: each is searched" : mL ? "→ left: only the left half moved"
                    : "→ right: only the right half moved");
                replace(b, next);
            }
            else if (!r.repeated) {
                decide(b, "neither half moved: the range is measured once more");
                r.repeated = true;
                measure(b, "repeat", r.a, r.b, true);
            }
            else if (r.cur != null && moved(b, r.cur)) {
                decide(b, "the range moves, its halves do not");
                close(b, "spread", r.cur, "moves " + show(r.cur)
                    + " while neither half does on its own: several small changes");
            }
            else {
                decide(b, "the range did not move on a repeat");
                close(b, "lost", r.cur, "did not reproduce" + (r.cur == null ? "" : ": " + show(r.cur)));
            }
        }

        db.updateBisect(b);
    }

    /**
     * What a deep search does with two measured halves, both in the direction of the segment. It compares them with
     * each other: a change sits in one half, so a half that clearly outweighs the other carries it, even when it does
     * not beat the noise floor on its own. A close call gets more runs up to {@code DEEP_RUNS}, then the larger half
     * wins, or both when each carries half a floor: then there are two changes.
     */
    static String deepChoice(double left, double right, int runs, double floor) {
        double hi = Math.max(left, right), lo = Math.min(left, right), some = floor / 2;

        if (lo >= floor)
            return BOTH;

        if (hi >= some && lo < some && hi - lo >= some)
            return left >= right ? LEFT : RIGHT;

        if (runs < DEEP_RUNS)
            return MORE;

        return lo >= some ? BOTH : left >= right ? LEFT : RIGHT;
    }

    /** One decision of the deep search on the range {@code r}: measures what it lacks, or moves on. */
    private void deepStep(Bisect b, Bisect.Range r) throws IOException {
        if (r.branch && !holds(b, r))
            return;

        if (r.commits <= 1) {
            deepCommit(b, r);

            return;
        }

        List<String> commits = mirror.revList(r.a, r.b);

        if (commits.size() <= 1) { // the history moved under us (should not happen on a first-parent branch)
            r.commits = commits.size();

            return;
        }

        String mid = commits.get(commits.size() / 2 - 1);
        int leftN = commits.size() / 2, rightN = commits.size() - leftN;

        if (r.left == null || r.right == null) {
            if (r.left == null)
                measure(b, LEFT, r.a, mid, false);
            else
                measure(b, RIGHT, mid, r.b, false);

            return;
        }

        if (brokenAgain(r.left) || brokenAgain(r.right)) {
            decide(b, "the phases of this pair disagree: it is measured again");

            if (brokenAgain(r.left))
                measure(b, LEFT, r.a, mid, true);
            else
                measure(b, RIGHT, mid, r.b, true);

            return;
        }

        double l = toward(b, r.left), x = toward(b, r.right), floor = b.state.floorPct;
        String choice = deepChoice(l, x, Math.min(r.left.runs, r.right.runs), floor);
        Bisect.Range left = range(r.a, mid, leftN, r.left, r.est), right = range(mid, r.b, rightN, r.right, r.est);

        if (MORE.equals(choice)) {
            decide(b, "a close call: one more run");

            if (r.left.runs <= r.right.runs)
                measure(b, LEFT, r.a, mid, true);
            else
                measure(b, RIGHT, mid, r.b, true);
        }
        else if (BOTH.equals(choice)) {
            decide(b, "→ both halves carry a part: each is searched");
            left.est = r.left.deltaPct;
            right.est = r.right.deltaPct;
            left.branch = true;
            right.branch = true;

            if (leftN <= 1)
                left.solid = known(r);

            if (rightN <= 1)
                right.solid = known(r);

            replace(b, List.of(left, right));
        }
        else {
            Bisect.Range next = LEFT.equals(choice) ? left : right;
            int weak = Math.max(l, x) < floor / 2 ? r.weak + 1 : 0;

            if (weak >= WEAK_LEVELS) {
                decide(b, "neither half carries it, twice in a row");
                deepEnd(b, r, "moves, but no half of it does: no single commit to find");

                return;
            }

            decide(b, "→ " + choice + (weak > 0 ? ", the larger of two quiet halves"
                : ": " + fmt(next.cur.deltaPct) + " against " + fmt(other(next, left, right).cur.deltaPct)));
            next.weak = weak;

            if (weak > 0 || next.commits <= 1) // a single commit has yet to stand up on its own
                next.solid = known(r);

            replace(b, List.of(next));
        }
    }

    /** */
    private static Bisect.Range other(Bisect.Range next, Bisect.Range left, Bisect.Range right) {
        return next == left ? right : left;
    }

    /** The shortest range known to carry the change, from {@code r} up. */
    private static Bisect.Range known(Bisect.Range r) {
        Bisect.Range k = r.solid != null ? r.solid : r;

        return range(k.a, k.b, k.commits, k.cur, k.est);
    }

    /**
     * The entry of a branch: going in takes hours, and a split may rest on one run near the floor, so the range is
     * measured until it plainly holds or plainly does not. Each branch gets steps of its own: the guard of a search
     * counts one path.
     */
    private boolean holds(Bisect b, Bisect.Range r) throws IOException {
        Bisect.State s = b.state;
        s.maxSteps = Math.max(s.maxSteps, s.steps.size() + 6 * levels(r.commits) + 8);

        if (r.commits > 1 && r.cur != null) {
            double d = toward(b, r.cur), floor = s.floorPct;

            if (d < 2 * floor && (brokenAgain(r.cur) || r.cur.runs < (d >= floor ? 2 : DEEP_RUNS))) {
                decide(b, "before going into this branch: one more run of it");
                measure(b, "repeat", r.a, r.b, true);

                return false;
            }

            if (d < floor / 2) {
                decide(b, "this branch does not hold");
                close(b, "lost", r.cur, "does not hold over " + r.cur.runs + " runs");

                return false;
            }
        }

        r.branch = false;

        return true;
    }

    /**
     * The commit of a deep search: it has to move the metric by half a floor over {@code DEEP_RUNS} runs; a large
     * change is plain after two.
     */
    private void deepCommit(Bisect b, Bisect.Range r) throws IOException {
        String sha = Job.shortSha(r.b);

        if (r.cur == null) {
            measure(b, "leaf", r.a, r.b, false);

            return;
        }

        double d = toward(b, r.cur), floor = b.state.floorPct;

        if (brokenAgain(r.cur) || r.cur.runs < (d >= 2 * floor ? 2 : DEEP_RUNS)) {
            decide(b, "the commit is measured once more");
            measure(b, "confirm", r.a, r.b, true);
        }
        else if (d >= floor / 2) {
            decide(b, "found " + sha);
            close(b, "found", r.cur, null);
        }
        else {
            decide(b, sha + " alone does not move it");
            deepEnd(b, r, sha + " was the best lead and does not hold over " + r.cur.runs
                + " runs: no single commit to find");
        }
    }

    /** The branch ends without a commit, on the shortest range known to carry the change. */
    private void deepEnd(Bisect b, Bisect.Range r, String note) {
        Bisect.Range k = known(r);
        r.a = k.a;
        r.b = k.b;
        r.commits = k.commits;
        close(b, "spread", k.cur, note);
    }

    /** A pair with nothing but broken runs is measured again, as long as that may help. */
    private static boolean brokenAgain(Bisect.Result r) {
        return r.broken && r.runs < BROKEN_RUNS;
    }

    /** Δ of {@code r} in the direction of the segment: positive when it moved the same way. */
    private static double toward(Bisect b, Bisect.Result r) {
        return r.deltaPct * Math.signum(b.state.root.deltaPct);
    }

    /**
     * Whether the change of the segment showed up again: both halves of the first level together, or a fresh run of
     * the segment, give at least half of it. Null when neither was measured.
     */
    private static Boolean reproduced(Bisect b) {
        Bisect.State s = b.state;

        if (s.root == null)
            return null;

        double seen;

        if (s.again != null)
            seen = s.again.deltaPct;
        else if (s.steps.size() >= 2 && "left".equals(s.steps.get(0).role) && b.shaA.equals(s.steps.get(0).a)
            && "right".equals(s.steps.get(1).role) && b.shaB.equals(s.steps.get(1).b))
            seen = s.steps.get(0).result.deltaPct + s.steps.get(1).result.deltaPct;
        else
            return null;

        return seen / s.root.deltaPct >= 0.5;
    }

    /** The searched range is resolved into {@code next} (its moving halves); the largest |Δ| is searched next. */
    private void replace(Bisect b, List<Bisect.Range> next) {
        List<Bisect.Range> open = b.state.open;
        open.remove(0);
        open.addAll(next);
        open.sort(Comparator.comparingDouble((Bisect.Range x) -> -Math.abs(x.est)));
    }

    /** The searched range ends the search on this branch with an outcome. */
    private void close(Bisect b, String kind, Bisect.Result r, String note) {
        Bisect.Range range = b.state.open.remove(0);

        if (b.state.deep && !b.state.open.isEmpty())
            b.state.open.get(0).branch = true; // what waited was left on what was known then

        Bisect.Finding f = new Bisect.Finding();
        f.kind = kind;
        f.a = range.a;
        f.b = range.b;
        f.commits = range.commits;
        f.result = r;
        f.note = note;
        b.state.findings.add(f);
        Log.info("bisect #" + b.id + ": " + kind + " " + Job.shortSha(f.a) + ".." + Job.shortSha(f.b) + " (" + f.commits
            + " commits)" + (r == null ? "" : " " + show(r)) + (note == null ? "" : ": " + note));
    }

    /**
     * Nothing is left to search: the outcome is the strongest finding, and how much of the segment's Δ commits explain.
     */
    private void finish(Bisect b) throws IOException {
        Bisect.State s = b.state;
        List<Bisect.Finding> found = s.findings.stream().filter(f -> "found".equals(f.kind))
            .sorted(Comparator.comparingDouble((Bisect.Finding f) -> -Math.abs(f.result.deltaPct))).toList();
        List<Bisect.Finding> spread = s.findings.stream().filter(f -> "spread".equals(f.kind)).toList();

        if (found.isEmpty() && spread.isEmpty() && !s.deep) {
            Boolean real = reproduced(b);

            if (real == null) {
                decide(b, "nothing found: the segment is measured once more, to tell a weak change from noise");
                measure(b, "root", b.shaA, b.shaB, true);

                return;
            }

            if (real) {
                end(b, Bisect.INCONCLUSIVE, TOO_CLOSE);

                return;
            }
        }

        StringBuilder sb = new StringBuilder();

        if (!found.isEmpty()) {
            b.resultSha = found.get(0).b;
            double sum = 0;

            for (Bisect.Finding f : found) {
                sb.append(sb.length() == 0 ? "" : ", ").append(Job.shortSha(f.b)).append(' ')
                    .append(fmt(f.result.deltaPct));
                sum += f.result.deltaPct;
            }

            double share = s.root == null || Math.abs(s.root.deltaPct) < 1e-9 ? 0 : sum / s.root.deltaPct;
            sb.append("; ").append(found.size() == 1 ? "it explains " : "they explain ").append(share(share))
                .append(" of the segment's ").append(fmt(s.root.deltaPct));

            if (share < 0.5)
                sb.append(": the rest is spread over small changes or noise");
        }

        for (Bisect.Finding f : spread)
            sb.append(sb.length() == 0 ? "" : "; ").append(f.commits).append(" commits ").append(Job.shortSha(f.a))
                .append("..").append(Job.shortSha(f.b)).append(' ').append(fmt(f.result.deltaPct))
                .append(" spread over small changes");

        if (sb.length() == 0)
            sb.append("nothing reproduced: the segment's change was noise");

        end(b, !found.isEmpty() ? Bisect.FOUND : !spread.isEmpty() ? Bisect.SPREAD : Bisect.LOST, sb.toString());
    }

    /** */
    private static Bisect.Range range(String a, String x, int commits, Bisect.Result cur, double est) {
        Bisect.Range r = new Bisect.Range();
        r.a = a;
        r.b = x;
        r.commits = commits;
        r.cur = cur;
        r.est = est;

        return r;
    }

    /**
     * Measures A..B: takes the finished runs of the pair when there are any (unless a fresh run is wanted), else waits
     * for an identical job already in the queue, else queues a step job.
     */
    private void measure(Bisect b, String role, String a, String x, boolean fresh) throws IOException {
        Bisect.State s = b.state;
        s.waitRole = role;
        s.waitA = a;
        s.waitB = x;

        if (!fresh) {
            Bisect.Result r = resultOf(b, a, x);

            if (r != null) {
                record(b, role, a, x, r.jobIds.get(r.jobIds.size() - 1), true, r);
                apply(b, role, r);
                s.waitRole = null;

                return;
            }
        }

        if (s.steps.size() >= s.maxSteps) {
            stop(b, "the step limit (" + s.maxSteps + ") is reached: Resume allows more");

            return;
        }

        Job q = db.activeJobOfPair(a, x, b.scenario);

        if (q == null)
            q = jobs.enqueueBisectStep(b, a, x, mirror.revList(a, x).size(), s.steps.size() + 1);

        s.waitJob = q.id;
        db.updateBisect(b);
    }

    /** */
    private Bisect.Result resultOf(Bisect b, String a, String x) {
        return b.state.deep ? jobs.medianResult(a, x, b.scenario, b.metric)
            : jobs.pairResult(a, x, b.scenario, b.metric);
    }

    /** */
    private void apply(Bisect b, String role, Bisect.Result r) {
        if ("root".equals(role)) {
            b.state.again = r;

            return;
        }

        Bisect.Range range = b.state.open.get(0);

        switch (role) {
            case "left" -> range.left = r;
            case "right" -> range.right = r;
            default -> range.cur = r; // leaf, confirm, repeat: the range itself
        }
    }

    /** */
    private void record(Bisect b, String role, String a, String x, Long jobId, boolean reused,
        Bisect.Result r) throws IOException {
        Bisect.Step st = new Bisect.Step();
        st.n = b.state.steps.size() + 1;
        st.role = role;
        st.a = a;
        st.b = x;
        st.commits = mirror.revList(a, x).size();
        st.jobId = jobId;
        st.reused = reused;
        st.result = r;
        b.state.steps.add(st);
        Log.info("bisect #" + b.id + " step " + st.n + " (" + role + (reused ? ", reused" : "") + "): "
            + Job.shortSha(x) + " vs " + Job.shortSha(a) + " " + show(r));
    }

    /** The decision taken after the last step (the root segment itself when there is none yet). */
    private void decide(Bisect b, String text) {
        List<Bisect.Step> steps = b.state.steps;

        if (!steps.isEmpty() && steps.get(steps.size() - 1).decision == null)
            steps.get(steps.size() - 1).decision = text;
    }

    /** */
    private void stop(Bisect b, String reason) {
        b.status = Bisect.STOPPED;
        b.reason = reason;
        db.updateBisect(b);
        Log.warn("bisect #" + b.id + " stopped: " + reason);
        notifyEnded(b);
    }

    /** */
    private void end(Bisect b, String status, String reason) {
        b.status = status;
        b.reason = reason;
        b.finishedAt = Db.now();
        b.state.waitRole = null;
        b.state.waitJob = null;
        db.updateBisect(b);
        Log.info("bisect #" + b.id + " " + status + ": " + reason);
        notifyEnded(b);
        prune();
    }

    /** */
    private void notifyEnded(Bisect b) {
        Notifier n = notifier;

        if (n == null)
            return;

        try {
            n.bisectEnded(b);
        }
        catch (RuntimeException e) {
            Log.warn("notify failed: " + e);
        }
    }

    /** The result moved the metric the same way as the root segment did, beyond its threshold. */
    private static boolean moved(Bisect b, Bisect.Result r) {
        return r != null && b.direction.equals(r.verdict);
    }

    /** */
    private Bisect require(long id) {
        Bisect b = db.bisect(id);

        if (b == null)
            throw new java.util.NoSuchElementException("no bisect " + id);

        return b;
    }

    /** Fills what the views show: the comparison, the step in progress, commit subjects. */
    private Bisect view(Bisect b) {
        Bisect.State s = b.state;

        if (s.waitJob != null)
            b.waiting = db.job(s.waitJob);

        Job root = db.job(b.rootJob);
        b.comparisonId = root == null ? null : root.comparisonId;

        for (String sha : new String[] {b.shaA, b.shaB, b.resultSha})
            subject(b, sha);

        s.findings.forEach(f -> subject(b, f.b));
        s.open.forEach(r -> subject(b, r.b));

        return b;
    }

    /** */
    private void subject(Bisect b, String sha) {
        if (sha == null || b.subjects.containsKey(sha))
            return;

        CommitInfo c = db.commit(sha);

        if (c != null)
            b.subjects.put(sha, c.subject);
    }

    /** */
    static int levels(int commits) {
        int n = 0;

        for (int c = Math.max(1, commits); c > 1; c = (c + 1) / 2)
            n++;

        return n;
    }

    /** */
    private static String share(double f) {
        return Math.round(f * 100) + " %";
    }

    /** */
    private static String fmt(double pct) {
        return String.format(Locale.ROOT, "%+.1f%%", pct);
    }

    /** */
    private static String show(Bisect.Result r) {
        return "Δ " + fmt(r.deltaPct) + " (threshold " + String.format(Locale.ROOT, "%.1f", r.thresholdPct) + " %, "
            + r.verdict + (r.runs > 1 ? ", " + r.runs + " runs" : "") + ")";
    }
}
