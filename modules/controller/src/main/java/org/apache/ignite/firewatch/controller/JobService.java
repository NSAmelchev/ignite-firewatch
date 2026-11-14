package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** Creates and manages jobs: resolves refs, validates scenarios, deduplicates, builds the Commits and trend views. */
public final class JobService {
    /** */
    public static final String SRC_AUTO = "auto";

    /** */
    public static final String SRC_MANUAL = "manual";

    /** */
    public static final String SRC_CALIBRATION = "calibration";

    /** Manual debugging from the Compare tab (any two refs, PRs): never part of the branch history views. */
    public static final String SRC_COMPARE = "compare";

    /** master against the last release tag (the dashboard headline); not part of the branch history views. */
    public static final String SRC_RELEASE = "release";

    /** A scenario has its own noise floor from this many A/A on. */
    public static final int MIN_CALIBRATIONS = 3;

    /**
     * A step of an automatic bisection ({@link Bisector}): an ordinary segment or commit-vs-parent run of the branch.
     */
    public static final String SRC_BISECT = "bisect";

    /** Measure load: see {@link Job#PROBE}. */
    public static final String SRC_PROBE = "probe";

    /** A plugin: its release pair against its master pair, see {@link PluginSchedule}. */
    public static final String SRC_PLUGIN = "plugin";

    /** A plugin: two pairs from the Compare of its page. */
    public static final String SRC_PLUGIN_COMPARE = "plugin-compare";

    /**
     * A run whose phases of one side differ by more than this many noise floors has a broken phase. Ordinary phases of
     * a transaction scenario reach 3.4 % at a 2 % floor, the broken ones start at 6 %; the median copes with the
     * former.
     */
    static final double BROKEN_SPREAD = 2.5;

    /** True for jobs that describe the branch itself: shown in trends, Commits and pooled as repeats. */
    static boolean isBranchHistory(Job j) {
        return j.plugin == null && (Job.ABBA.equals(j.type) || Job.SINGLE.equals(j.type))
            && !SRC_CALIBRATION.equals(j.source) && !SRC_COMPARE.equals(j.source) && !SRC_RELEASE.equals(j.source);
    }

    /** */
    private final Config cfg;

    /** */
    private volatile Plugins plugins;

    /** */
    private final Db db;

    /** */
    private final GitMirror mirror;

    /** */
    private final Scenarios scenarios;

    /** */
    private volatile Runnable onEnqueue = () -> { };

    /** */
    private final List<java.util.function.Consumer<Job>> onJobEnded = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Creates the service.
     *
     * @param cfg Configuration.
     * @param db Database.
     * @param mirror Git mirror of the repository.
     * @param scenarios Scenario registry.
     */
    public JobService(Config cfg, Db db, GitMirror mirror, Scenarios scenarios) {
        this.cfg = cfg;
        this.db = db;
        this.mirror = mirror;
        this.scenarios = scenarios;
    }

    /**
     * Sets the plugins of the stand: their scenarios name the jobs of plugins.
     *
     * @param p Plugins.
     */
    void plugins(Plugins p) {
        plugins = p;
    }

    /**
     * The scenario of a job: of the harness, or of its plugin.
     *
     * @param j Job.
     * @return Scenario, or {@code null} when it no longer exists.
     */
    public Scenarios.Info scenarioOf(Job j) {
        if (j.plugin == null)
            return scenarios.get(j.scenario);

        Plugins p = plugins;

        return p == null ? null : p.scenario(j.plugin, j.scenario);
    }

    /**
     * The build of each side of a job, by side ("A", "B"): B only when it is another build than A. A job of a plugin
     * builds pairs, so its B is another build when either commit differs.
     *
     * @param j Job.
     * @param sc Its scenario, or {@code null}.
     * @return Build keys by side.
     */
    static Map<String, String> buildKeys(Job j, Scenarios.Info sc) {
        List<String> mods = sc == null ? List.of() : sc.modules;
        Map<String, String> m = new LinkedHashMap<>();

        if (j.shaA != null)
            m.put("A", Builder.key(j.shaA, mods, j.plugin, j.extShaA));

        if (j.isAbba() && j.shaB != null && !(j.shaB.equals(j.shaA) && java.util.Objects.equals(j.extShaB, j.extShaA)))
            m.put("B", Builder.key(j.shaB, mods, j.plugin, j.extShaB));

        return m;
    }

    /**
     * Queues a job made elsewhere: a plugin queues its pairs this way, with the checks of every job.
     *
     * @param j Job.
     * @return Queued job.
     */
    Job queue(Job j) {
        return insert(j);
    }

    /**
     * Sets the callback run after a job is queued.
     *
     * @param r Callback.
     */
    public void onEnqueue(Runnable r) {
        onEnqueue = r;
    }

    /**
     * Called with every job that left the queue for good: done, failed, cancelled or interrupted.
     *
     * @param c Listener.
     */
    public void onJobEnded(java.util.function.Consumer<Job> c) {
        onJobEnded.add(c);
    }

    /** */
    void jobEnded(Job j) {
        for (java.util.function.Consumer<Job> c : onJobEnded) {
            try {
                c.accept(j);
            }
            catch (RuntimeException e) {
                Log.error("job #" + j.id + ": end listener failed", e);
            }
        }
    }

    /**
     * Gets the database.
     *
     * @return Database.
     */
    public Db db() {
        return db;
    }

    /**
     * Manual job from the UI: {@code single} measures refA; {@code abba} compares refB (target) against refA
     * (baseline).
     *
     * @param type Job type: {@code single} or {@code abba}.
     * @param refA Baseline ref.
     * @param refB Target ref, used by {@code abba} only.
     * @param scenario Scenario name.
     * @param priority Queue priority.
     * @param source Job source; manual if {@code null}.
     * @param label Job label; generated if blank.
     * @return Queued job.
     */
    public Job enqueue(String type, String refA, String refB, String scenario, int priority, String source,
        String label)
        throws IOException {
        if (!Job.SINGLE.equals(type) && !Job.ABBA.equals(type))
            throw new IllegalArgumentException("type must be single or abba");

        if (scenarios.get(scenario) == null)
            throw new IllegalArgumentException("unknown scenario: " + scenario);

        requireMirror();
        Job j = new Job();
        j.type = type;
        j.source = source == null ? SRC_MANUAL : source;
        j.scenario = scenario;
        j.priority = priority;
        j.refA = refA == null ? null : refA.trim();

        if (j.refA == null || j.refA.isEmpty())
            throw new IllegalArgumentException(type.equals(Job.ABBA) ? "baseline ref (A) is required"
                : "ref is required");

        j.shaA = mirror.resolve(j.refA);

        if (Job.ABBA.equals(type)) {
            j.refB = refB == null ? null : refB.trim();

            if (j.refB == null || j.refB.isEmpty())
                throw new IllegalArgumentException("target ref (B) is required");

            j.shaB = mirror.resolve(j.refB);
        }

        j.label = label != null && !label.isBlank() ? label : defaultLabel(j);

        return insert(j);
    }

    /**
     * Auto-run job for a new commit of the watched branch.
     *
     * @param sha Commit sha.
     * @param parentSha Parent commit sha, or {@code null} if there is none.
     * @param mode Run mode: {@code single} measures the commit alone, otherwise it is compared with the parent.
     * @param scenario Scenario name.
     * @return Queued job.
     */
    public Job enqueueAuto(String sha, String parentSha, String mode, String scenario) {
        Job j = new Job();
        j.source = SRC_AUTO;
        j.scenario = scenario;
        j.priority = 0;

        if ("single".equals(mode) || parentSha == null) {
            j.type = Job.SINGLE;
            j.refA = Job.shortSha(sha);
            j.shaA = sha;
        }
        else {
            j.type = Job.ABBA;
            j.refA = Job.shortSha(parentSha) + " (parent)";
            j.shaA = parentSha;
            j.refB = Job.shortSha(sha);
            j.shaB = sha;
        }

        j.label = defaultLabel(j);

        return insert(j);
    }

    /**
     * A segment of the branch: ABBA of {@code end} against {@code start}, covering {@code commits} first-parent
     * commits.
     *
     * @param start Sha of the segment start.
     * @param end Sha of the segment end.
     * @param commits Number of commits in the segment.
     * @param scenario Scenario name.
     * @param priority Queue priority.
     * @param source Job source.
     * @return Queued job.
     */
    public Job enqueueSegment(String start, String end, int commits, String scenario, int priority, String source) {
        Job j = new Job();
        j.type = Job.ABBA;
        j.source = source;
        j.scenario = scenario;
        j.priority = priority;
        j.refA = Job.shortSha(start) + " (segment start)";
        j.shaA = start;
        j.refB = Job.shortSha(end);
        j.shaB = end;
        j.label = Job.shortSha(end) + " vs " + Job.shortSha(start) + " (" + commits + " commits)";

        return insert(j);
    }

    /**
     * A bisection step: ABBA of {@code end} against {@code start}; a commit against its parent is labelled as such.
     *
     * @param b Bisection.
     * @param start Sha of the step start.
     * @param end Sha of the step end.
     * @param commits Number of commits in the step.
     * @param step Step number.
     * @return Queued job.
     */
    public Job enqueueBisectStep(Bisect b, String start, String end, int commits, int step) throws IOException {
        Job j = new Job();
        j.type = Job.ABBA;
        j.source = SRC_BISECT;
        j.scenario = b.scenario;
        j.priority = b.priority;
        j.shaA = start;
        j.shaB = end;
        j.refB = Job.shortSha(end);
        CommitInfo ci = commits == 1 ? mirror.info(end) : null;

        if (ci != null && ci.parentSha != null && ci.parentSha.split(" ")[0].equals(start)) {
            j.refA = Job.shortSha(start) + " (parent)";
            j.label = Job.shortSha(end) + " vs parent";
        }
        else {
            j.refA = Job.shortSha(start) + " (segment start)";
            j.label = Job.shortSha(end) + " vs " + Job.shortSha(start) + " (" + commits + " commits)";
        }

        j.bisectId = b.id;
        j.bisectStep = step;
        j.requestedBy = b.requestedBy; // queued by the worker, in the name of who started the bisection

        return insert(j);
    }

    /**
     * One scenario of a comparison: ABBA of its two commits.
     *
     * @param c Comparison.
     * @param scenario Scenario name.
     * @return Queued job.
     */
    public Job enqueueComparison(Comparison c, String scenario) {
        Job j = new Job();
        j.type = Job.ABBA;
        j.source = SRC_COMPARE;
        j.scenario = scenario;
        j.priority = c.priority;
        j.refA = c.refA;
        j.shaA = c.shaA;
        j.refB = c.refB;
        j.shaB = c.shaB;
        j.label = c.label;
        j.comparisonId = c.id;
        j.requestedBy = c.requestedBy;

        return insert(j);
    }

    /**
     * The metric of A..B over its finished branch runs (the latest one with its repeats, pooled); null if there are
     * none.
     *
     * @param shaA Baseline sha.
     * @param shaB Target sha.
     * @param scenario Scenario name.
     * @param metric Metric name.
     * @return Metric result, or {@code null} if there are no runs.
     */
    public Bisect.Result pairResult(String shaA, String shaB, String scenario, String metric) {
        List<Job> done = new ArrayList<>();

        for (Job j : db.doneJobsOfPair(shaA, shaB, scenario)) {
            if (isBranchHistory(j))
                done.add(j);
        }

        return done.isEmpty() ? null : result(done.get(done.size() - 1), done, metric);
    }

    /** One answer of the search box: what it is, a line about it and its page. */
    public static final class Hit {
        /** */
        public String kind;

        /** */
        public String title;

        /** */
        public String note;

        /** */
        public String href;

        /** */
        Hit(String kind, String title, String note, String href) {
            this.kind = kind;
            this.title = title;
            this.note = note;
            this.href = href;
        }
    }

    /**
     * The search box: a number is a job, a bisection, a comparison or a PR (with {@code #} or a GitHub link too);
     * anything else, a sha, a ticket or words, is looked up in the commits of the history.
     *
     * @param query Search text.
     * @param limit Maximum number of hits.
     * @return Hits.
     */
    public List<Hit> search(String query, int limit) {
        String q = query == null ? "" : query.trim();
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("/(pull|commit)/([0-9a-fA-F]+)").matcher(q);

        if (link.find())
            q = ("pull".equals(link.group(1)) ? "#" : "") + link.group(2);

        List<Hit> out = new ArrayList<>();

        if (q.isEmpty())
            return out;

        String digits = q.startsWith("#") ? q.substring(1) : q;

        if (digits.matches("[0-9]{1,9}")) {
            long n = Long.parseLong(digits);
            Job j = db.job(n);

            if (j != null)
                out.add(new Hit("job", "job #" + n, j.label + " · " + j.scenario + " · " + j.status, "#/jobs/" + n));

            Bisect b = db.bisect(n);

            if (b != null)
                out.add(new Hit("bisect", "bisect #" + n, b.scenario + " · " + b.metric + " · " + b.status, "#/bisects/"
                    + n));

            Comparison c = db.comparison(n);

            if (c != null)
                out.add(new Hit("comparison", "comparison #" + n, c.label, "#/comparisons/" + n));

            q = "#" + digits; // PRs close the subjects of their squashed commits: "... (#13719)"
        }

        for (CommitInfo c : db.searchCommits(q, limit)) {
            if (out.size() < limit)
                out.add(new Hit("commit", Job.shortSha(c.sha), c.subject, "#/commits/" + c.sha));
        }

        return out;
    }

    /**
     * The metric of A..B for a deep search: the median Δ over its finished branch runs, so one bad run does not move
     * it. A run whose phases disagree by more than {@link #BROKEN_SPREAD} noise floors says little and is left out,
     * unless it is all the pair has. Null if there are no runs.
     *
     * @param shaA Baseline sha.
     * @param shaB Target sha.
     * @param scenario Scenario name.
     * @param metric Metric name.
     * @return Metric result, or {@code null} if there are no runs.
     */
    public Bisect.Result medianResult(String shaA, String shaB, String scenario, String metric) {
        List<Job> done = new ArrayList<>();

        for (Job j : db.doneJobsOfPair(shaA, shaB, scenario)) {
            if (isBranchHistory(j))
                done.add(j);
        }

        if (done.isEmpty())
            return null;

        List<JsonNode> sound = new ArrayList<>(), all = new ArrayList<>();
        List<Job> soundJobs = new ArrayList<>(), allJobs = new ArrayList<>();

        for (Job j : repeatsOf(done.get(done.size() - 1), done)) {
            JsonNode m = j.comparison == null ? null : j.comparison.path("metrics").get(metric);

            if (m == null || !m.hasNonNull("deltaPct"))
                continue;

            all.add(m);
            allJobs.add(j);

            if (Math.max(m.path("spreadAPct").asDouble(), m.path("spreadBPct").asDouble())
                <= BROKEN_SPREAD * m.path("noisePct").asDouble()) {
                sound.add(m);
                soundJobs.add(j);
            }
        }

        if (all.isEmpty())
            return null;

        boolean broken = sound.isEmpty();
        List<JsonNode> used = broken ? all : sound;
        Bisect.Result r = new Bisect.Result();
        r.deltas = new ArrayList<>(used.stream().map(m -> m.get("deltaPct").asDouble()).toList());
        r.deltaPct = median(r.deltas);
        JsonNode m = used.get(0);
        r.thresholdPct = m.path("noisePct").asDouble();
        boolean worse = m.path("higherBetter").asBoolean() ? r.deltaPct < 0 : r.deltaPct > 0;
        r.verdict = Math.abs(r.deltaPct) < r.thresholdPct ? Stats.NO_CHANGE : worse ? Stats.REGRESSION
            : Stats.IMPROVEMENT;
        r.runs = used.size();
        r.dropped = all.size() - sound.size();
        r.broken = broken;

        for (Job j : broken ? allJobs : soundJobs)
            r.jobIds.add(j.id);

        return r;
    }

    /**
     * The metric of {@code j}, pooled with its repeats among {@code candidates}; null when the job has no such delta.
     *
     * @param j Job.
     * @param candidates Jobs that may be repeats of {@code j}.
     * @param metric Metric name.
     * @return Metric result, or {@code null} if the job has no such delta.
     */
    public Bisect.Result result(Job j, List<Job> candidates, String metric) {
        JsonNode cmp = j.comparison;
        List<Job> grp = j.comparisonId == null ? repeatsOf(j, candidates) : repeatsInComparison(j, candidates);

        if (grp.size() > 1) {
            Stats.Comparison p = pooled(grp);

            if (p != null)
                cmp = Json.MAPPER.valueToTree(p);
        }
        else
            grp = List.of(j);

        JsonNode m = cmp == null ? null : cmp.path("metrics").get(metric);

        if (m == null || !m.hasNonNull("deltaPct"))
            return null;

        Bisect.Result r = new Bisect.Result();
        r.deltaPct = m.get("deltaPct").asDouble();
        r.thresholdPct = m.path("thresholdPct").asDouble();
        r.verdict = m.path("verdict").asText(Stats.NO_CHANGE);
        r.runs = grp.size();

        for (Job g : grp)
            r.jobIds.add(g.id);

        return r;
    }

    /**
     * Rough length of one bisection step: a run of the scenario plus a build of the new middle commit.
     *
     * @param scenario Scenario name.
     * @return Seconds.
     */
    public long stepSeconds(String scenario) {
        return Math.round(runSeconds(scenario, Job.ABBA) + buildSeconds());
    }

    /**
     * Splits a measured segment A..B: {@code halves} queues A..M and M..B (M = middle first-parent commit),
     * {@code commits} queues every commit of the segment against its parent. Never queued automatically.
     *
     * @param id Id of the measured job.
     * @param mode Split mode: {@code halves} or {@code commits}.
     * @return Queued jobs.
     */
    public List<Job> split(long id, String mode) throws IOException {
        return split(id, mode, 1);
    }

    /**
     * Splits a measured segment, see {@link #split(long, String)}.
     *
     * @param id Id of the measured job.
     * @param mode Split mode: {@code halves} or {@code commits}.
     * @param priority Queue priority of the new jobs.
     * @return Queued jobs.
     */
    public List<Job> split(long id, String mode, int priority) throws IOException {
        Job s = db.job(id);

        if (s == null)
            throw new java.util.NoSuchElementException("no job " + id);

        if (!Job.ABBA.equals(s.type))
            throw new IllegalArgumentException("only an ABBA job can be split");

        requireMirror();
        List<String> range = mirror.revList(s.shaA, s.shaB); // commits after A up to B, oldest first

        if (range.size() < 2)
            throw new IllegalArgumentException("job #" + id + " covers a single commit: nothing to split");

        List<Job> out = new ArrayList<>();

        if ("commits".equals(mode)) {
            for (String c : range) {
                CommitInfo ci = mirror.info(c);
                String parent = ci == null || ci.parentSha == null ? null : ci.parentSha.split(" ")[0];

                if (parent == null)
                    continue;

                try {
                    out.add(enqueue(Job.ABBA, parent, c, s.scenario, priority, SRC_MANUAL, Job.shortSha(c) + " vs parent"));
                }
                catch (IllegalArgumentException dup) {
                    // already queued
                }
            }
        }
        else {
            String mid = range.get(range.size() / 2 - 1);
            int left = range.size() / 2, right = range.size() - left;

            try {
                out.add(enqueueSegment(s.shaA, mid, left, s.scenario, priority, SRC_MANUAL));
            }
            catch (IllegalArgumentException dup) {
                // already queued
            }

            try {
                out.add(enqueueSegment(mid, s.shaB, right, s.scenario, priority, SRC_MANUAL));
            }
            catch (IllegalArgumentException dup) {
                // already queued
            }
        }

        Log.info("job #" + id + " split (" + mode + "): " + out.size() + " job(s) queued");

        return out;
    }

    /**
     * What the dashboard compares master with, from the setting: empty or a mask pick the highest X.Y.Z tag that fits,
     * anything else is that ref.
     *
     * @return Release ref, or {@code null} if no tag fits.
     */
    public String releaseTag() throws IOException {
        String t = db.setting(Settings.RELEASE_TAG, "").trim();

        if (t.isEmpty() || t.contains("*"))
            return mirror.latestReleaseTag(t.isEmpty() ? "*" : t);

        return t.matches("[0-9a-f]{40}") ? Job.shortSha(t) : t;
    }

    /**
     * Refuses a value of the setting that leads nowhere: a mask without a tag, or a ref the mirror does not know.
     *
     * @param value Setting value.
     */
    public void checkRelease(String value) throws IOException {
        requireMirror();

        if (value.contains("*")) {
            if (mirror.latestReleaseTag(value) == null)
                throw new IllegalArgumentException(value + " fits no X.Y.Z tag");
        }
        else if (!value.isBlank())
            mirror.resolve(value);
    }

    /**
     * Measure load on the head of the branch: the main tracker with more and more threads, see {@link Job#PROBE}.
     *
     * @return Queued job.
     */
    public Job enqueueProbe() throws IOException {
        requireMirror();
        String head = mirror.resolve("refs/heads/" + mirror.branch());
        Job j = new Job();
        j.type = Job.PROBE;
        j.source = SRC_PROBE;
        j.scenario = Settings.firstScenario(db);
        j.priority = 3;
        j.refA = mirror.branch();
        j.shaA = head;
        j.label = "Measure load on " + Job.shortSha(head);

        return insert(j);
    }

    /**
     * The latest finished "master vs release" run of the scenario against {@code shaA}: a run against another release
     * says nothing about this one.
     *
     * @param scenario Scenario name.
     * @param shaA Sha of the release.
     * @return Job, or {@code null} if there is none.
     */
    public Job releaseJob(String scenario, String shaA) {
        return db.doneJobs(scenario, Job.ABBA, SRC_RELEASE, 200).stream().filter(j -> shaA.equals(j.shaA)).findFirst()
            .orElse(null);
    }

    /**
     * The last finished master vs release run of the scenario; null without one or without a release to compare with.
     *
     * @param scenario Scenario name.
     * @return Job, or {@code null} if there is none.
     */
    public Job latestRelease(String scenario) throws IOException {
        requireMirror();
        String tag = releaseTag();

        return tag == null ? null : releaseJob(scenario, mirror.resolve(tag));
    }

    /**
     * Resolves the head of the watched branch.
     *
     * @return Sha of the branch head.
     */
    public String head() throws IOException {
        return mirror.resolve("refs/heads/" + mirror.branch());
    }

    /**
     * Every scenario the harness carries: the nightly A/A and the release headline cover all of them.
     *
     * @return Scenario names.
     */
    public List<String> scenarioNames() {
        return scenarios.list().stream().map(s -> s.name).toList();
    }

    /**
     * ABBA: the release tag (A) against the head of the branch (B).
     *
     * @param scenario Scenario name.
     * @param priority Queue priority.
     * @return Queued job.
     */
    public Job queueRelease(String scenario, int priority) throws IOException {
        if (scenarios.get(scenario) == null)
            throw new IllegalArgumentException("unknown scenario: " + scenario);

        requireMirror();
        String tag = releaseTag();

        if (tag == null)
            throw new IllegalArgumentException("no release tag X.Y.Z in the repository");

        String head = mirror.resolve("refs/heads/" + mirror.branch());
        Job j = new Job();
        j.type = Job.ABBA;
        j.source = SRC_RELEASE;
        j.scenario = scenario;
        j.priority = priority;
        j.refA = tag;
        j.shaA = mirror.resolve(tag);
        j.refB = mirror.branch();
        j.shaB = head;
        j.label = mirror.branch() + " " + Job.shortSha(head) + " vs " + tag;

        return insert(j);
    }

    /**
     * Dashboard headline: what master gained since the last release — the tag, the unreleased first-parent commits
     * (since the branch point of the tag), the latest "master vs tag" result and how the unreleased commits are covered
     * by runs (one by one, inside segments, not at all).
     *
     * @param scenario Scenario name.
     * @return Headline data.
     */
    public Map<String, Object> release(String scenario) throws IOException {
        requireMirror();
        Map<String, Object> out = new LinkedHashMap<>();
        String tag = releaseTag();
        out.put("tag", tag);
        out.put("scenario", scenario);

        if (tag == null)
            return out;

        String tagSha = mirror.resolve(tag), head = mirror.resolve("refs/heads/" + mirror.branch());
        String bp = mirror.mergeBase(tagSha, head);
        List<String> sinceBranch = mirror.revList(bp, head);
        // the release is the code users run: master commits cherry-picked into it before the tag are not "unreleased"
        java.util.Set<String> picked = cherryPickedCached(tagSha, head, bp);
        List<String> unreleased = new ArrayList<>();

        for (String c : sinceBranch)
            if (!picked.contains(c))
                unreleased.add(c);

        out.put("cherryPicked", new ArrayList<>(picked));
        CommitInfo tagInfo = mirror.info(tagSha), first = unreleased.isEmpty() ? null : db.commit(unreleased.get(0));
        out.put("tagSha", tagSha);
        out.put("tagDate", tagInfo == null ? null : tagInfo.committedAt);
        out.put("branchPoint", bp);
        out.put("head", head);
        out.put("unreleased", unreleased.size());
        out.put("fromDate", first == null ? null : first.committedAt);
        CommitInfo headInfo = db.commit(head);
        out.put("headDate", headInfo == null ? null : headInfo.committedAt);

        Job j = releaseJob(scenario, tagSha);

        if (j != null) {
            slim(List.of(j));
            out.put("job", j);
            out.put("behind", head.equals(j.shaB) ? 0 : mirror.revList(j.shaB, head).size());
        }

        for (Job a : db.jobs("active", 5000)) {
            if (SRC_RELEASE.equals(a.source) && scenario.equals(a.scenario)) {
                out.put("active", a);
                break;
            }
        }
        // coverage of the unreleased commits (cherry-picks excluded) by the branch history runs, counted by commit
        // number
        java.util.Set<Integer> unrelSeq = new java.util.HashSet<>();
        java.util.Set<String> unrel = new java.util.HashSet<>(unreleased);

        for (CommitInfo c : db.commits(cfg.branch, Math.min(5000, sinceBranch.size() + 5)))
            if (unrel.contains(c.sha) && c.seq != null)
                unrelSeq.add(c.seq);

        java.util.Set<Integer> single = new java.util.HashSet<>(), segment = new java.util.HashSet<>();

        for (TrendPoint p : trend(scenario, Math.min(5000, sinceBranch.size() + 50))) {
            if (p.seq == null)
                continue;

            if (p.span <= 1)
                single.add(p.seq);
            else if (p.baseSeq != null)
                for (int s = p.baseSeq + 1; s <= p.seq; s++)
                    segment.add(s);
        }

        single.retainAll(unrelSeq);
        segment.retainAll(unrelSeq);
        segment.removeAll(single);
        Map<String, Object> cov = new LinkedHashMap<>();
        cov.put("single", single.size());
        cov.put("segment", segment.size());
        cov.put("none", Math.max(0, unreleased.size() - single.size() - segment.size()));
        out.put("coverage", cov);

        return out;
    }

    /** */
    private final Map<String, java.util.Set<String>> cherryCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** git cherry is ~1 s on the Ignite history: cached per (tag, head). */
    private java.util.Set<String> cherryPickedCached(String tagSha, String head, String bp) throws IOException {
        String key = tagSha + ".." + head;
        java.util.Set<String> v = cherryCache.get(key);

        if (v == null) {
            v = mirror.cherryPicked(tagSha, head, bp);

            if (cherryCache.size() > 20)
                cherryCache.clear();

            cherryCache.put(key, v);
        }

        return v;
    }

    /**
     * A/A calibration: the same build on both sides; its deltas estimate the noise floor.
     *
     * @param ref Ref to measure on both sides.
     * @param scenario Scenario name.
     * @param priority Queue priority.
     * @return Queued job.
     */
    public Job calibrate(String ref, String scenario, int priority) throws IOException {
        if (scenarios.get(scenario) == null)
            throw new IllegalArgumentException("unknown scenario: " + scenario);

        requireMirror();
        String r = ref == null || ref.isBlank() ? mirror.branch() : ref.trim();
        String sha = mirror.resolve(r);
        Job j = new Job();
        j.type = Job.ABBA;
        j.source = SRC_CALIBRATION;
        j.scenario = scenario;
        j.priority = priority;
        j.refA = r;
        j.shaA = sha;
        j.refB = r;
        j.shaB = sha;
        j.label = "A/A " + r + " " + Job.shortSha(sha);

        return insert(j);
    }

    /** */
    private Job insert(Job j) {
        if (j.requestedBy == null)
            j.requestedBy = Who.current(); // the request that queues it; none for what the stand queues by itself

        rememberCommit(j.shaA);

        if (j.shaB != null && !j.shaB.equals(j.shaA))
            rememberCommit(j.shaB);

        // check and insert under one lock: the watcher thread and an HTTP request may queue the same job at once.
        // Repeated A/A runs are the point of calibration, so only regular jobs are deduplicated.

        synchronized (insertLock) {
            if (!SRC_CALIBRATION.equals(j.source) && (j.plugin == null
                ? db.hasActiveJob(j.type, j.shaA, j.shaB, j.scenario)
                : db.activePluginJob(j.plugin, j.scenario, j.shaA, j.extShaA, j.shaB, j.extShaB) != null))
                throw new IllegalArgumentException("an identical job is already queued or running");

            db.insertJob(j);
        }

        Log.info("job #" + j.id + " queued: " + j.type + " " + j.label + " [" + j.scenario + "] source=" + j.source);
        onEnqueue.run();

        return j;
    }

    /** */
    private void rememberCommit(String sha) {
        try {
            if (db.commit(sha) == null) {
                CommitInfo ci = mirror.info(sha);

                if (ci != null)
                    db.upsertCommit(ci);
            }
        }
        catch (IOException e) {
            Log.warn("cannot read commit " + sha + ": " + e.getMessage());
        }
    }

    /** */
    private void requireMirror() {
        if (!mirror.ready())
            throw new IllegalStateException("the stand is still cloning the repository: Dashboard shows how far it is");
    }

    /** */
    static String defaultLabel(Job j) {
        if (Job.SINGLE.equals(j.type))
            return Job.shortSha(j.shaA);

        if (j.shaA.equals(j.shaB))
            return "A/A " + Job.shortSha(j.shaA);

        return Job.shortSha(j.shaB) + " vs " + Job.shortSha(j.shaA);
    }

    /**
     * Requests cancellation of a job.
     *
     * @param id Job id.
     * @return {@code True} if the cancellation was requested.
     */
    public boolean cancel(long id) {
        boolean ok = db.requestCancel(id, Who.current());

        if (ok) {
            Log.info("job #" + id + " cancel requested");
            Job j = db.job(id);

            if (j != null && Job.CANCELLED.equals(j.status))
                jobEnded(j); // a queued job ends right here; a running one ends in the worker
        }

        return ok;
    }

    /**
     * Queues a new job with the parameters of a finished one.
     *
     * @param id Id of the job to repeat.
     * @return Queued job.
     */
    public Job retry(long id) {
        return retry(id, null);
    }

    /**
     * Queues a new job with the parameters of a finished one.
     *
     * @param id Id of the job to repeat.
     * @param priority Queue priority, or {@code null} for that of the old job, at least that of a manual one.
     * @return Queued job.
     */
    public Job retry(long id, Integer priority) {
        Job old = db.job(id);

        if (old == null)
            throw new java.util.NoSuchElementException("no job " + id);

        if (old.active())
            throw new IllegalArgumentException("job " + id + " is still active");

        Job j = new Job();
        j.type = old.type;
        j.source = SRC_AUTO.equals(old.source) ? SRC_MANUAL : old.source; // a retried compare stays a compare
        j.scenario = old.scenario;
        j.priority = priority != null ? priority : Math.max(old.priority, 1);
        j.refA = old.refA;
        j.shaA = old.shaA;
        j.refB = old.refB;
        j.shaB = old.shaB;
        j.label = old.label;
        j.comparisonId = old.comparisonId;
        j.plugin = old.plugin;
        j.extShaA = old.extShaA;
        j.extShaB = old.extShaB;

        return insert(j);
    }

    /**
     * Removes a finished job with its runs and its directory under {@code runs/}.
     *
     * @param id Job id.
     * @return {@code True} if the job was deleted.
     */
    public boolean delete(long id) throws IOException {
        Job j = db.job(id);

        if (j != null && j.bisectId != null) {
            Bisect b = db.bisect(j.bisectId);

            if (b != null && b.active())
                throw new IllegalArgumentException("job #" + id + " is step " + j.bisectStep + " of active bisect #"
                    + b.id + ": cancel the bisect first");
        }

        if (!db.deleteJob(id))
            return false;

        Util.deleteTree(cfg.runsDir().resolve(Long.toString(id)));
        Log.info("job " + id + " deleted");

        return true;
    }

    /**
     * Changes the queue priority of a job.
     *
     * @param id Job id.
     * @param priority New priority.
     * @return {@code True} if the priority was changed.
     */
    public boolean setPriority(long id, int priority) {
        return db.setPriority(id, priority);
    }

    /**
     * Gets a job.
     *
     * @param id Job id.
     * @return Job, or {@code null} if there is none.
     */
    public Job detail(long id) {
        Job j = db.job(id);

        if (j == null)
            throw new java.util.NoSuchElementException("no job " + id);

        j.runs = db.runs(id);

        return j;
    }

    /** Commits of the watched branch, newest first, each with the jobs that measured it. */
    /**
     * Finished non-calibration ABBA jobs measuring the same pair as {@code j} (same A, B, scenario file), oldest first.
     */
    static List<Job> repeatsOf(Job j, List<Job> candidates) {
        List<Job> out = new ArrayList<>();

        if (!Job.ABBA.equals(j.type) || !isBranchHistory(j))
            return out;

        for (Job x : candidates) {
            if (Job.ABBA.equals(x.type) && Job.DONE.equals(x.status) && isBranchHistory(x)
                && j.shaA.equals(x.shaA) && j.shaB.equals(x.shaB) && j.scenario.equals(x.scenario)
                && java.util.Objects.equals(j.scenarioHash, x.scenarioHash))
                out.add(x);
        }

        out.sort(java.util.Comparator.comparingLong(x -> x.id));

        return out;
    }

    /**
     * Finished runs of the scenario of {@code j} in its comparison (same scenario version), in the order of
     * {@code candidates}.
     */
    static List<Job> repeatsInComparison(Job j, List<Job> candidates) {
        List<Job> out = new ArrayList<>();

        for (Job x : candidates) {
            if (j.comparisonId.equals(x.comparisonId) && Job.DONE.equals(x.status) && j.scenario.equals(x.scenario)
                && java.util.Objects.equals(j.scenarioHash, x.scenarioHash))
                out.add(x);
        }

        return out;
    }

    /** */
    static final int POOLED_CACHE_SIZE = 500;

    /** LRU of pooled comparisons; keys change with every new calibration, so old entries must be evicted. */
    private final Map<String, Stats.Comparison> pooledCache = java.util.Collections.synchronizedMap(
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Stats.Comparison> e) {
                return size() > POOLED_CACHE_SIZE;
            }
        });

    /**
     * Pooled comparison of several runs of one pair (cached by job ids and noise floors); null if phases are missing.
     */
    Stats.Comparison pooled(List<Job> group) {
        Noise n = noise(group.get(0).scenario);
        StringBuilder key = new StringBuilder();

        for (Job g : group)
            key.append(g.id).append(',');

        key.append(n.floors);
        String k = key.toString();

        if (pooledCache.containsKey(k))
            return pooledCache.get(k);

        Stats.Comparison computed = computePooled(group, n);

        if (computed != null)
            pooledCache.put(k, computed);

        return computed;
    }

    /** */
    private Stats.Comparison computePooled(List<Job> group, Noise n) {
        List<List<PhaseData>> runs = new ArrayList<>();

        for (Job g : group) {
            List<PhaseData> phases = new ArrayList<>();

            for (Run r : db.runs(g.id)) {
                if (!Run.OK.equals(r.status))
                    continue;

                try {
                    PhaseData d = PhaseData.read(cfg.dataDir.resolve(r.dir), r.phase, r.sha);

                    if (d.ok())
                        phases.add(d);
                }
                catch (IOException e) {
                    Log.warn("pooled: cannot read " + r.dir + ": " + e.getMessage());
                }
            }

            if (phases.size() == 4)
                runs.add(phases);
        }

        if (runs.size() < 2)
            return null;

        return Stats.comparePooled(runs, n.floors, n.source, n.calibrations.size());
    }

    /** Metrics the list views (Queue history, Compare) show; the job page still gets the full comparison. */
    static final List<String> LIST_METRICS = List.of("throughput", "p99Us", "serverCpuUsPerOp", "allocKbPerOp",
        "heapAfterGcMb", "serverStartMs", "preloadMs");

    /** */
    private static final List<String> LIST_FIELDS = List.of("meanB", "deltaPct", "verdict", "thresholdPct",
        "spreadAPct", "spreadBPct");

    /**
     * Replaces the full comparison (≈6 KB: every metric with phase values, CI, spreads) by a summary for list views:
     * the job verdict and, for {@link #LIST_METRICS}, B, Δ, threshold, the metric verdict and the phase spreads (≈1
     * KB).
     *
     * @param list Jobs to summarize in place.
     */
    public static void slim(List<Job> list) {
        for (Job j : list) {
            if (j.comparison == null || !j.comparison.isObject())
                continue;

            com.fasterxml.jackson.databind.node.ObjectNode s = Json.MAPPER.createObjectNode();

            if (j.comparison.has("verdict"))
                s.set("verdict", j.comparison.get("verdict"));

            if (j.comparison.has("runs"))
                s.set("runs", j.comparison.get("runs"));

            com.fasterxml.jackson.databind.node.ObjectNode ms = s.putObject("metrics");
            JsonNode all = j.comparison.path("metrics");

            for (String k : LIST_METRICS) {
                JsonNode r = all.get(k);

                if (r == null)
                    continue;

                com.fasterxml.jackson.databind.node.ObjectNode o = ms.putObject(k);

                for (String f : LIST_FIELDS)
                    if (r.has(f))
                        o.set(f, r.get(f));
            }

            j.comparison = s;
        }
    }

    /**
     * Fills {@link Job#subjectA}/{@link Job#subjectB} from the commit history (one lookup per distinct sha).
     *
     * @param list Jobs to fill.
     */
    public void addSubjects(List<Job> list) {
        Map<String, Optional<CommitInfo>> cache = new HashMap<>();
        java.util.function.Function<String, CommitInfo> info = sha -> sha == null ? null
            : cache.computeIfAbsent(sha, k -> Optional.ofNullable(db.commit(k))).orElse(null);

        for (Job j : list) {
            CommitInfo a = info.apply(j.shaA), b = info.apply(j.shaB);
            j.subjectA = a == null ? null : a.subject;
            j.subjectB = b == null ? null : b.subject;
            j.dateA = a == null ? null : a.committedAt;
            j.dateB = b == null ? null : b.committedAt;
        }
    }

    /**
     * Job page: for an ABBA whose A is an earlier branch commit other than B's parent, the segment A..B —
     * its size, dates and every commit (oldest first) with the commit's own latest result vs its parent, if any.
     *
     * @param j Job to fill.
     */
    public void addSegment(Job j) throws IOException {
        if (j.plugin != null || !Job.ABBA.equals(j.type) || j.shaA == null || j.shaB == null || j.shaA.equals(j.shaB))
            return; // the pairs of a plugin are no segment of the branch: both sides moved

        CommitInfo a = db.commit(j.shaA), b = db.commit(j.shaB);

        if (b == null || b.seq == null)
            return;

        boolean aOnBranch = a != null && a.seq != null && cfg.branch.equals(a.branch);

        if (aOnBranch && (a.seq >= b.seq || b.seq - a.seq < 2))
            return;
        // A off the branch (a release tag): the segment is what B has and A has not, i.e. the branch since the branch
        // point

        List<String> range = mirror.revList(j.shaA, j.shaB);

        if (range.size() < 2)
            return;

        Map<String, Job> own = new HashMap<>();

        for (Job x : db.jobsForShas(range, j.scenario)) {
            if (Job.DONE.equals(x.status) && isBranchHistory(x) && x.id != j.id) {
                CommitInfo xb = db.commit(x.shaB);
                String parent = xb == null || xb.parentSha == null ? null : xb.parentSha.split(" ")[0];

                if (j.shaA != null && java.util.Objects.equals(x.shaA, parent)
                    && (own.get(x.shaB) == null || own.get(x.shaB).id < x.id))
                    own.put(x.shaB, x);
            }
        }

        List<Map<String, Object>> commits = new ArrayList<>();

        for (String sha : range) {
            CommitInfo ci = db.commit(sha);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sha", sha);
            m.put("seq", ci == null ? null : ci.seq);
            m.put("subject", ci == null ? null : ci.subject);
            m.put("author", ci == null ? null : ci.author);
            m.put("committedAt", ci == null ? null : ci.committedAt);
            Job r = own.get(sha);

            if (r != null) {
                JsonNode t = r.comparison == null ? null : r.comparison.path("metrics").path("throughput");
                m.put("jobId", r.id);
                m.put("verdict", r.verdict);
                m.put("throughputDeltaPct", t == null || !t.hasNonNull("deltaPct") ? null
                    : t.get("deltaPct").asDouble());
            }

            commits.add(m);
        }

        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("count", range.size());
        seg.put("fromSha", j.shaA);
        seg.put("fromDate", a == null ? null : a.committedAt);
        seg.put("toSha", j.shaB);
        seg.put("toDate", b.committedAt);
        seg.put("commits", commits);
        j.segment = seg;
    }

    /**
     * Fills {@link Job#etaStartAt}/{@link Job#etaSec} for the running and queued jobs (already in queue order):
     * duration = median of the last finished jobs of the same scenario and type, plus the median build time for every
     * sha that is neither built nor built by an earlier job in the queue; starts are cumulative from now.
     *
     * @param active Running and queued jobs, in queue order.
     */
    public void addEta(List<Job> active) {
        java.time.Instant t = java.time.Instant.now();
        double build = buildSeconds();
        Map<String, Double> runCache = new HashMap<>();
        java.util.Set<String> built = db.okBuildShas(); // one query instead of one per queued sha

        for (Job j : active) {
            Scenarios.Info sc = scenarioOf(j);
            double run = runCache.computeIfAbsent(j.plugin + "|" + j.scenario + "|" + j.type,
                k -> j.plugin == null ? runSeconds(j.scenario, j.type) : pluginRunSeconds(j, sc));
            double secs = run;

            for (String key : buildKeys(j, sc).values()) {
                // build keys: a SQL scenario needs its own build of a commit, a plugin one of the pair
                if (!built.add(key))
                    continue;

                secs += build; // not built yet: this job (or an earlier one in the queue) builds it
            }

            if (Job.RUNNING.equals(j.status) && j.startedAt != null) {
                double elapsed = java.time.Duration.between(java.time.Instant.parse(j.startedAt),
                    java.time.Instant.now()).toSeconds();
                j.etaStartAt = j.startedAt;
                j.etaSec = Math.round(secs);
                t = java.time.Instant.now().plusSeconds(Math.max(30, Math.round(secs - elapsed)));
                continue;
            }

            j.etaStartAt = t.toString();
            j.etaSec = Math.round(secs);
            t = t.plusSeconds(Math.round(secs));
        }
    }

    /** Median build time of the last successful builds (60 s before the first one). */
    private double buildSeconds() {
        List<Double> secs = new ArrayList<>();

        for (Build b : db.builds(30))
            if (Build.OK.equals(b.status) && b.durationSec != null)
                secs.add(b.durationSec);

        return secs.isEmpty() ? 60 : median(secs);
    }

    /**
     * Median duration of the last finished jobs of this scenario and type; from the scenario timings before the first
     * one.
     */
    private double runSeconds(String scenario, String type) {
        List<Double> secs = new ArrayList<>();

        for (Job d : db.doneJobs(scenario, type, null, 15)) {
            if (d.startedAt != null && d.finishedAt != null)
                secs.add((double)java.time.Duration.between(java.time.Instant.parse(d.startedAt),
                    java.time.Instant.parse(d.finishedAt)).toSeconds());
        }

        Scenarios.Info sc = scenarios.get(scenario);
        double fallback = sc == null ? 600 : (sc.totalSeconds() + 45) * (Job.ABBA.equals(type) ? 4 : 1);

        return secs.isEmpty() ? fallback : median(secs);
    }

    /** The same for a scenario of a plugin, from the finished jobs of that plugin. */
    private double pluginRunSeconds(Job j, Scenarios.Info sc) {
        List<Double> secs = new ArrayList<>();

        for (Job d : db.pluginJobs(j.plugin, j.scenario, null, true, 15)) {
            if (d.startedAt != null && d.finishedAt != null && j.type.equals(d.type))
                secs.add((double)java.time.Duration.between(java.time.Instant.parse(d.startedAt),
                    java.time.Instant.parse(d.finishedAt)).toSeconds());
        }

        double fallback = sc == null ? 600 : (sc.totalSeconds() + 45) * (Job.ABBA.equals(j.type) ? 4 : 1);

        return secs.isEmpty() ? fallback : median(secs);
    }

    /**
     * What queuing {@code jobs} new jobs would cost, for the launch confirmation: run time from recent jobs, builds of
     * the given shas that are not built yet (plus {@code extraBuilds} for refs the caller cannot resolve), and the
     * start after the active jobs of the same or higher priority. {@code scenario} may be a comma-separated list: the
     * run time is their mean, and every module set they need is built.
     *
     * @param scenario Scenario name, or a comma-separated list of them.
     * @param type Job type.
     * @param jobs Number of jobs to queue.
     * @param shas Shas the jobs need built.
     * @param extraBuilds Number of builds for refs the caller cannot resolve.
     * @param priority Queue priority.
     * @return Estimate of the run time, builds and start.
     */
    public Map<String, Object> estimate(String scenario, String type, int jobs, List<String> shas, int extraBuilds,
        int priority) {
        List<Scenarios.Info> set = new ArrayList<>();

        for (String name : scenario.split(",")) {
            Scenarios.Info sc = scenarios.get(name.trim());

            if (sc == null)
                throw new IllegalArgumentException("unknown scenario: " + name);

            set.add(sc);
        }

        java.util.Set<String> built = db.okBuildShas(); // build keys: a SQL scenario needs its own build of a commit
        List<Job> active = db.jobs("active", Integer.MAX_VALUE);
        addEta(active);
        java.time.Instant start = java.time.Instant.now();
        int ahead = 0;

        for (Job j : active) {
            if (!Job.RUNNING.equals(j.status) && j.priority < priority)
                continue;

            ahead++;

            if (j.etaStartAt != null && j.etaSec != null) {
                java.time.Instant end = java.time.Instant.parse(j.etaStartAt).plusSeconds(j.etaSec);

                if (end.isAfter(start))
                    start = end;
            }

            built.addAll(buildKeys(j, scenarioOf(j)).values()); // the queue builds these before our jobs run
        }

        long unbuilt = 0;

        for (List<String> mods : set.stream().map(s -> s.modules).distinct().toList()) {
            unbuilt += shas.stream().filter(s -> s != null && !s.isBlank()).distinct()
                .filter(s -> !built.contains(Builder.key(s, mods))).count() + Math.max(0, extraBuilds);
        }

        double run = set.stream().mapToDouble(s -> runSeconds(s.name, type)).average().orElse(0),
            build = buildSeconds();
        long total = Math.round(run * Math.max(1, jobs) + build * unbuilt);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobs", Math.max(1, jobs));
        m.put("runSec", Math.round(run));
        m.put("builds", unbuilt);
        m.put("buildSec", Math.round(build));
        m.put("totalSec", total);
        m.put("ahead", ahead);
        m.put("startAt", start.toString());
        m.put("endAt", start.plusSeconds(total).toString());

        return m;
    }

    /** */
    static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        java.util.Collections.sort(s);
        int mid = s.size() / 2;

        return s.size() % 2 == 1 ? s.get(mid) : (s.get(mid - 1) + s.get(mid)) / 2;
    }

    /**
     * One commit with every job that measured it (as target, baseline or A/A), newest first.
     *
     * @param ref Commit ref.
     * @return Commit with its jobs.
     */
    public Map<String, Object> commitDetail(String ref) throws IOException {
        requireMirror();
        String sha = mirror.resolve(ref);
        CommitInfo ci = db.commit(sha);

        if (ci == null)
            ci = mirror.info(sha);

        List<Map<String, Object>> list = new ArrayList<>();

        for (Job j : db.jobsTouching(sha)) {
            if (SRC_COMPARE.equals(j.source) || SRC_RELEASE.equals(j.source) || j.isProbe())
                continue; // manual debugging, the release headline and service probes live elsewhere

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", sha.equals(j.shaA) && sha.equals(j.shaB) ? "A/A"
                : sha.equals(j.shaB) || Job.SINGLE.equals(j.type) ? "target" : "baseline");
            m.put("job", j);
            list.add(m);
        }
        // pooled results per repeated pair where this commit is B
        List<Map<String, Object>> pooledList = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Job> all = db.jobsTouching(sha);

        for (Job j : all) {
            if (!sha.equals(j.shaB) || sha.equals(j.shaA))
                continue;

            List<Job> grp = repeatsOf(j, all);

            if (grp.size() < 2 || !seen.add(j.shaA + "|" + j.scenario + "|" + j.scenarioHash))
                continue;

            Stats.Comparison cmp = pooled(grp);

            if (cmp == null)
                continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenario", j.scenario);
            m.put("shaA", j.shaA);
            List<Long> ids = new ArrayList<>();

            for (Job g : grp)
                ids.add(g.id);

            m.put("jobIds", ids);
            m.put("comparison", cmp);
            pooledList.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sha", sha);
        out.put("commit", ci);
        out.put("pooled", pooledList);
        out.put("jobs", list);

        return out;
    }

    /**
     * Gets the latest commits of the branch that have a job for the scenario.
     *
     * @param limit Maximum number of commits.
     * @param scenario Scenario name.
     * @return Commits.
     */
    public List<CommitInfo> commitsWithJobs(int limit, String scenario) {
        List<CommitInfo> commits = db.commits(cfg.branch, limit);
        List<String> shas = new ArrayList<>();

        for (CommitInfo c : commits)
            shas.add(c.sha);

        Map<String, List<Job>> bySha = new HashMap<>();

        for (Job j : db.jobsForShas(shas, scenario)) {
            if (SRC_COMPARE.equals(j.source) || SRC_RELEASE.equals(j.source))
                continue;

            String key = Job.SINGLE.equals(j.type) ? j.shaA : j.shaB;
            bySha.computeIfAbsent(key, k -> new ArrayList<>()).add(j);
        }

        // the Commits page takes metrics from the trend and only id/status/source/type from these jobs;
        // it shows runs of the commit alone, so a segment ending here (A is not the parent) stays on Timeline
        for (CommitInfo c : commits) {
            List<Map<String, Object>> brief = new ArrayList<>();

            for (Job j : bySha.getOrDefault(c.sha, List.of())) {
                if (Job.ABBA.equals(j.type) && !j.shaA.equals(c.sha) && !j.shaA.equals(c.parentSha))
                    continue;

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", j.id);
                m.put("status", j.status);
                m.put("source", j.source);
                m.put("type", j.type);
                m.put("scenario", j.scenario);
                brief.add(m);
            }

            c.jobs = brief;
        }

        return commits;
    }

    /** First-parent position of a commit of the watched branch (from the window, else from the commits table). */
    private Integer seqOfBranchCommit(String sha, Map<String, Integer> window) {
        if (sha == null)
            return null;

        Integer s = window.get(sha);

        if (s != null)
            return s;

        CommitInfo ci = db.commit(sha);

        if (ci != null && cfg.branch.equals(ci.branch))
            return ci.seq;
        // a commit off the branch (a release tag after a split of "master vs 2.18.0"): its branch point stands for it

        String bp = branchPointCache.computeIfAbsent(sha, k -> {
            try {
                return mirror.ready() ? mirror.mergeBase(k, "refs/heads/" + mirror.branch()) : "";
            }
            catch (IOException | RuntimeException e) {
                return "";
            }
        });

        if (bp == null || bp.isEmpty() || bp.equals(sha))
            return null;

        s = window.get(bp);

        if (s != null)
            return s;

        CommitInfo b = db.commit(bp);

        return b == null || !cfg.branch.equals(b.branch) ? null : b.seq;
    }

    /** */
    private final Object insertLock = new Object();

    /** */
    private final Map<String, String> branchPointCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Point of the trend: one commit of the branch with its measured values. */
    public static final class TrendPoint {
        /** */
        public String sha;

        /** */
        public Integer seq;

        /** */
        public String subject;

        /** */
        public String author;

        /** */
        public String committedAt;

        /** */
        public long jobId;

        /** */
        public String jobType;

        /** */
        public String jobSource;

        /** */
        public String verdict;

        /** */
        public String finishedAt;

        /** Baseline of the point: the parent for a single commit, the previous segment end for a segment. */
        public String baseSha;

        /** */
        public Integer baseSeq;

        /** Commits covered by the point: 1 for "commit vs parent", N for a segment. */
        public int span = 1;

        /** ABBA runs pooled into the values (1 = just {@link #jobId}). */
        public int runs = 1;

        /**
         * Only in {@code trend(.., refined = true)}: the job of the point of record of this commit when a shorter run
         * ending here replaced this one (a split halved the segment); null for the point of record itself.
         */
        public Long refinedBy;

        /** */
        public List<Long> jobIds = new ArrayList<>();

        /** Metric → value of this commit (mean of its B phases, or the single phase). */
        public Map<String, Double> values = new LinkedHashMap<>();

        /** Metric → delta % against the baseline of the job (ABBA only). */
        public Map<String, Double> deltaPct = new LinkedHashMap<>();

        /** */
        public Map<String, String> metricVerdict = new LinkedHashMap<>();

        /** Per metric: the job's threshold (max of the A/A noise floor and the spread between repeats), in %. */
        public Map<String, Double> thresholdPct = new LinkedHashMap<>();

        /** Per metric: min and max over all phases of the run(s), i.e. how stable the measurement was. */
        public Map<String, Double> phaseMin = new LinkedHashMap<>();

        /** */
        public Map<String, Double> phaseMax = new LinkedHashMap<>();

        /** Per metric with per-second series (throughput, p99): 95 % bootstrap CI of Δ, in %. */
        public Map<String, Double> ciLow = new LinkedHashMap<>();

        /** */
        public Map<String, Double> ciHigh = new LinkedHashMap<>();
    }

    /**
     * One point per commit of the watched branch (oldest first) that has a finished job for the scenario.
     *
     * @param scenario Scenario name.
     * @param limit Maximum number of commits.
     * @return Trend points.
     */
    public List<TrendPoint> trend(String scenario, int limit) {
        return trend(scenario, limit, false);
    }

    /**
     * Like {@link #trend(String, int)}; with {@code refined} the commit's other baselines follow its point: the latest
     * run of each, repeats pooled, marked with {@link TrendPoint#refinedBy}. A split keeps the half that ends where the
     * segment ended, so without them the segment map would lose every parent of a bisection. Level charts and coverage
     * need one value per commit and ask without them.
     *
     * @param scenario Scenario name.
     * @param limit Maximum number of commits.
     * @param refined Whether the other baselines of a commit follow its point.
     * @return Trend points.
     */
    public List<TrendPoint> trend(String scenario, int limit, boolean refined) {
        List<CommitInfo> commits = db.commits(cfg.branch, limit);
        List<String> shas = new ArrayList<>();

        for (CommitInfo c : commits)
            shas.add(c.sha);

        Map<String, Job> best = new HashMap<>();
        Map<String, List<Job>> doneBySha = new HashMap<>();
        Map<String, Integer> seqOf = new HashMap<>();

        for (CommitInfo c : commits)
            seqOf.put(c.sha, c.seq);

        Map<String, Integer> bestSpan = new HashMap<>();

        for (Job j : db.jobsForShas(shas, scenario)) {
            if (!Job.DONE.equals(j.status) || !isBranchHistory(j))
                continue;
            // a trend point is "the commit vs an earlier commit of the branch": its parent, or the start of a segment

            int span = 1;

            if (Job.ABBA.equals(j.type)) {
                Integer a = seqOfBranchCommit(j.shaA, seqOf), b = seqOf.get(j.shaB);

                if (a == null || b == null || a >= b)
                    continue;

                span = b - a;
            }

            doneBySha.computeIfAbsent(Job.SINGLE.equals(j.type) ? j.shaA : j.shaB, k -> new ArrayList<>()).add(j);
            // the most precise point wins (a run vs the parent before a segment ending here), then the latest one
            String key = Job.SINGLE.equals(j.type) ? j.shaA : j.shaB;
            Job cur = best.get(key);
            Integer curSpan = bestSpan.get(key);

            if (cur == null || span < curSpan || (span == curSpan && j.id > cur.id)) {
                best.put(key, j);
                bestSpan.put(key, span);
            }
        }

        List<TrendPoint> out = new ArrayList<>();

        for (int i = commits.size() - 1; i >= 0; i--) {
            CommitInfo c = commits.get(i);
            Job j = best.get(c.sha);

            if (j == null)
                continue;

            List<Job> done = doneBySha.getOrDefault(c.sha, List.of());
            out.add(point(c, j, done, seqOf));

            if (!refined)
                continue;
            // the latest run of every other baseline of the commit: the larger segments a split refined

            Map<String, Job> others = new LinkedHashMap<>();

            for (Job o : done) {
                Job prev = others.get(o.shaA);

                if (Job.ABBA.equals(o.type) && !o.shaA.equals(j.shaA) && (prev == null || o.id > prev.id))
                    others.put(o.shaA, o);
            }

            for (Job o : others.values()) {
                TrendPoint p = point(c, o, done, seqOf);
                p.refinedBy = j.id;
                out.add(p);
            }
        }

        return out;
    }

    /** The trend point of commit {@code c} measured by {@code j}, pooled with its repeats among {@code done}. */
    private TrendPoint point(CommitInfo c, Job j, List<Job> done, Map<String, Integer> seqOf) {
        TrendPoint p = new TrendPoint();
        p.sha = c.sha;
        p.seq = c.seq;
        p.subject = c.subject;
        p.author = c.author;
        p.committedAt = c.committedAt;
        p.jobId = j.id;
        p.jobType = j.type;
        p.jobSource = j.source;
        p.verdict = j.verdict;
        p.finishedAt = j.finishedAt;
        p.jobIds.add(j.id);

        if (Job.ABBA.equals(j.type)) {
            p.baseSha = j.shaA;
            p.baseSeq = seqOfBranchCommit(j.shaA, seqOf);

            if (p.baseSeq != null && c.seq != null)
                p.span = c.seq - p.baseSeq;
        }

        JsonNode cmpNode = j.comparison;
        List<Job> grp = repeatsOf(j, done);

        if (grp.size() > 1) {
            Stats.Comparison pooled = pooled(grp);

            if (pooled != null) {
                cmpNode = Json.MAPPER.valueToTree(pooled);
                p.verdict = pooled.verdict;
                p.runs = grp.size();
                p.jobIds.clear();

                for (Job g : grp)
                    p.jobIds.add(g.id);
            }
        }

        if (cmpNode != null) {
            JsonNode metrics = cmpNode.path("metrics");

            for (Stats.MetricDef m : Stats.METRICS) {
                JsonNode r = metrics.get(m.key);

                if (r == null)
                    continue;

                if (r.hasNonNull("meanB"))
                    p.values.put(m.key, r.get("meanB").asDouble());

                if (r.hasNonNull("deltaPct"))
                    p.deltaPct.put(m.key, r.get("deltaPct").asDouble());

                if (r.hasNonNull("verdict"))
                    p.metricVerdict.put(m.key, r.get("verdict").asText());

                if (r.hasNonNull("thresholdPct"))
                    p.thresholdPct.put(m.key, r.get("thresholdPct").asDouble());

                if (r.hasNonNull("ciLow") && r.hasNonNull("ciHigh")) {
                    p.ciLow.put(m.key, r.get("ciLow").asDouble());
                    p.ciHigh.put(m.key, r.get("ciHigh").asDouble());
                }

                double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;

                for (var it = r.path("values").fields(); it.hasNext(); ) {
                    JsonNode v = it.next().getValue();

                    if (v.isNumber()) {
                        mn = Math.min(mn, v.asDouble());
                        mx = Math.max(mx, v.asDouble());
                    }
                }

                if (mn <= mx) {
                    p.phaseMin.put(m.key, mn);
                    p.phaseMax.put(m.key, mx);
                }
            }
        }
        else {
            for (Run r : db.runs(j.id)) {
                if (!Run.OK.equals(r.status))
                    continue;

                putIf(p.values, "throughput", r.throughput);
                putIf(p.values, "p50Us", r.p50Us);
                putIf(p.values, "p99Us", r.p99Us);
                putIf(p.values, "p999Us", r.p999Us);
                putIf(p.values, "gcPausePct", r.gcPausePct);
                putIf(p.values, "gcMaxPauseMs", r.gcMaxPauseMs);
                putIf(p.values, "allocMbPerSec", r.allocMbPerSec);
                putIf(p.values, "serverCpuPct", r.serverCpuPct);
                putIf(p.values, "machineCpuPct", r.machineCpuPct);
                putIf(p.values, "heapAfterGcMb", r.heapAfterGcMb);

                if (r.allocMbPerSec != null && r.throughput != null && r.throughput > 0)
                    p.values.put("allocKbPerOp", r.allocMbPerSec * 1024 / r.throughput);
            }
        }

        return p;
    }

    /** */
    private static void putIf(Map<String, Double> m, String k, Double v) {
        if (v != null)
            m.put(k, v);
    }

    /** Noise floors of a scenario with the calibrations they come from. */
    public static final class Noise {
        /** */
        public String scenario;

        /** */
        public String source;

        /** */
        public Map<String, Double> floors;

        /** */
        public List<Map<String, Object>> calibrations = new ArrayList<>();
    }

    /**
     * The last 10 finished A/A of the scenario as it is now: calibrations of an older version (hash) measured other
     * code.
     *
     * @param scenario Scenario name.
     * @return Calibration jobs.
     */
    public List<Job> calibrationsOfCurrentVersion(String scenario) {
        Scenarios.Info info = scenarios.get(scenario);
        List<Job> out = new ArrayList<>();

        for (Job c : db.doneJobs(scenario, Job.ABBA, SRC_CALIBRATION, 100)) {
            if (info != null && info.hash != null && c.scenarioHash != null && !info.hash.equals(c.scenarioHash))
                continue;

            out.add(c);

            if (out.size() == 10)
                break;
        }

        return out;
    }

    /**
     * Gets the noise floors of a scenario: from its calibrations, or the defaults if there are too few.
     *
     * @param scenario Scenario name.
     * @return Noise floors.
     */
    public Noise noise(String scenario) {
        Noise n = new Noise();
        n.scenario = scenario;
        List<Stats.Comparison> cmps = new ArrayList<>();

        for (Job c : calibrationsOfCurrentVersion(scenario)) {
            if (c.comparison == null)
                continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", c.id);
            row.put("finishedAt", c.finishedAt);
            row.put("sha", c.shaA);
            row.put("label", c.label);
            Map<String, Double> deltas = new LinkedHashMap<>();
            JsonNode metrics = c.comparison.path("metrics");

            for (Stats.MetricDef m : Stats.METRICS) {
                JsonNode r = metrics.get(m.key);

                if (r != null && r.hasNonNull("deltaPct"))
                    deltas.put(m.key, r.get("deltaPct").asDouble());
            }

            row.put("deltaPct", deltas);
            n.calibrations.add(row);

            try {
                cmps.add(Json.MAPPER.treeToValue(c.comparison, Stats.Comparison.class));
            }
            catch (Exception e) {
                Log.warn("cannot read comparison of job #" + c.id + ": " + e);
            }
        }

        n.source = cmps.size() >= MIN_CALIBRATIONS ? "calibration" : "default";
        n.floors = cmps.size() >= MIN_CALIBRATIONS ? Stats.noiseFloors(cmps) : Stats.defaultNoiseFloors();

        return n;
    }

    /**
     * Shas that queued or running jobs still need (never evicted from the artifact cache).
     *
     * @return Shas.
     */
    public Set<String> shasInUse() {
        Set<String> s = new java.util.HashSet<>();

        for (Job j : db.jobs("active", 1000)) {
            s.add(j.shaA);

            if (j.shaB != null)
                s.add(j.shaB);
        }

        return s;
    }
}
