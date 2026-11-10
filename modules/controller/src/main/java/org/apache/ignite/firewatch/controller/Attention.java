package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Needs attention on the dashboard: what the latest "master vs release" run of every scenario shows against what was
 * found on the branch. A metric is explained when the commits measured against their parents add up to its Δ, so
 * nothing else moved; otherwise it is being searched or waits for a search. A run newer than that master run is listed
 * as new.
 */
public final class Attention {
    /** */
    public static final String UNEXPLORED = "unexplored";

    /** */
    public static final String PARTIAL = "partial";

    /** */
    public static final String NARROWED = "narrowed";

    /** */
    public static final String EXPLAINED = "explained";

    /** */
    public static final String NEW = "new";

    /** What is worth a look first. */
    private static final List<String> ORDER = List.of(NEW, UNEXPLORED, NARROWED, PARTIAL, Bisect.STOPPED,
        Bisect.RUNNING, Bisect.INCONCLUSIVE, Bisect.SPREAD, Bisect.LOST, EXPLAINED);

    /** */
    static final List<String> METRICS = List.of("throughput", "p99Us", "serverCpuUsPerOp", "allocKbPerOp");

    /** Attention rows of the current release tag. */
    public static final class View {
        /** */
        public String tag;

        /** */
        public List<Row> rows = new ArrayList<>();
    }

    /** One scenario, its lines from the most worrying one on. */
    public static final class Row {
        /** */
        public String scenario;

        /**
         * A running or stopped bisection of the scenario, of the branch or of a comparison: a second one has to wait
         * for it.
         */
        public Long activeBisect;

        /** */
        public List<Line> lines = new ArrayList<>();
    }

    /** One metric of the master run, or one new run of the branch. */
    public static final class Line {
        /** */
        public String metric;

        /** */
        public String status;

        /** */
        public String verdict;

        /** */
        public double deltaPct;

        /** */
        public double thresholdPct;

        /** What the commits found do not explain. */
        public double restPct;

        /** How far the phases of the master run lie apart, to tell a signal from noise. */
        public double spreadAPct;

        /** */
        public double spreadBPct;

        /** */
        public List<Found> commits = new ArrayList<>();

        /** The job a bisection of this line starts from and the commits it would search. */
        public long jobId;

        /** */
        public int rangeCommits;

        /** */
        public Long bisectId;

        /** */
        public Long etaSec;

        /** Its bisection was a deep search already: no second one. */
        public boolean deep;

        /**
         * The shortest segment that moved the metric the way master did, if any: a search of another metric leaves
         * these too.
         */
        public String narrowBase;

        /** */
        public String narrowSha;

        /** */
        public int narrowSpan;

        /** */
        public double narrowPct;

        /** A new run: its first commit's parent, its last commit and how many it covers. */
        public String baseSha;

        /** */
        public String sha;

        /** */
        public int span;
    }

    /** A commit measured against its parent that moved the metric. */
    public static final class Found {
        /** */
        public String sha;

        /** */
        public String subject;

        /** */
        public double deltaPct;

        /** */
        public Long bisectId;
    }

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

    /**
     * Creates the attention view over the given services.
     *
     * @param db Database.
     * @param mirror Git mirror.
     * @param jobs Job service.
     * @param scenarios Scenario catalog.
     * @param bisector Bisector.
     */
    public Attention(Db db, GitMirror mirror, JobService jobs, Scenarios scenarios, Bisector bisector) {
        this.db = db;
        this.mirror = mirror;
        this.jobs = jobs;
        this.scenarios = scenarios;
        this.bisector = bisector;
    }

    /**
     * Builds the attention view for the current release tag.
     *
     * @return Attention view.
     */
    public View view() throws IOException {
        View v = new View();

        if (!mirror.ready())
            return v;

        v.tag = jobs.releaseTag();

        if (v.tag == null)
            return v;

        String head = mirror.resolve("refs/heads/" + mirror.branch()), release = mirror.resolve(v.tag);
        int from = seq(mirror.mergeBase(release, head), 0), to = seq(head, from);
        // a bisection of a comparison searches another range: it says nothing about master
        List<Bisect> all = bisector.list(null, false, 200);
        List<Bisect> bisects = all.stream().filter(b -> b.comparisonId == null && seq(b.shaB, 0) > from).toList();

        for (Scenarios.Info s : scenarios.list()) {
            Row r = row(s.name, release, from, to - from + 50, bisects);

            if (r.lines.isEmpty())
                continue;

            all.stream().filter(b -> b.active() && s.name.equals(b.scenario)).findFirst()
                .ifPresent(b -> r.activeBisect = b.id);
            v.rows.add(r);
        }

        v.rows.sort(Comparator.comparingInt((Row r) -> rank(r.lines.get(0))));

        return v;
    }

    /** */
    private Row row(String scenario, String release, int from, int limit, List<Bisect> bisects) {
        Row r = new Row();
        r.scenario = scenario;
        List<Bisect> own = bisects.stream().filter(b -> scenario.equals(b.scenario)).toList();
        Job master = jobs.releaseJob(scenario, release);
        int upTo = master == null ? from : seq(master.shaB, from);
        List<JobService.TrendPoint> points = jobs.trend(scenario, limit, true); // with the segments a split refined

        if (master != null && master.comparison != null) {
            for (String m : METRICS) {
                JsonNode x = master.comparison.path("metrics").get(m);

                if (x != null && x.hasNonNull("deltaPct"))
                    line(x, m, master, points, own, from, upTo).ifPresent(r.lines::add);
            }
        }

        for (JobService.TrendPoint p : points) {
            if (p.seq != null && p.seq > upTo && p.refinedBy == null)
                r.lines.addAll(fresh(p));
        }

        r.lines.sort(Comparator.comparingInt(Attention::rank));

        return r;
    }

    /** The metric of the master run, when it moved or a bisection found something in it. */
    private java.util.Optional<Line> line(JsonNode x, String metric, Job master, List<JobService.TrendPoint> points,
        List<Bisect> own, int from, int upTo) {
        List<Bisect> searches = own.stream().filter(b -> metric.equals(b.metric)).toList(); // newest first
        String verdict = x.path("verdict").asText(Stats.NO_CHANGE);

        if (!moved(verdict) && searches.stream().noneMatch(b -> Bisect.FOUND.equals(b.status)))
            return java.util.Optional.empty();

        Line l = new Line();
        l.metric = metric;
        l.verdict = verdict;
        l.deltaPct = x.get("deltaPct").asDouble();
        l.thresholdPct = x.path("thresholdPct").asDouble();
        l.spreadAPct = x.path("spreadAPct").asDouble();
        l.spreadBPct = x.path("spreadBPct").asDouble();
        l.jobId = master.id;
        l.rangeCommits = upTo - from;
        l.commits = found(metric, points, own, searches, from, upTo);
        l.restPct = l.deltaPct - l.commits.stream().mapToDouble(c -> c.deltaPct).sum();
        Bisect search = searches.isEmpty() ? null : searches.get(0);
        l.status = status(l.deltaPct, l.thresholdPct, l.commits, search);
        JobService.TrendPoint narrow = narrowest(points, metric, l.deltaPct, from, upTo);

        if (narrow != null) {
            l.narrowBase = narrow.baseSha;
            l.narrowSha = narrow.sha;
            l.narrowSpan = narrow.span;
            l.narrowPct = narrow.deltaPct.get(metric);

            if (UNEXPLORED.equals(l.status)) {
                l.status = NARROWED;
                l.jobId = narrow.jobId; // a search from here costs a few steps instead of the whole range
                l.rangeCommits = narrow.span;
            }
        }

        if (search != null) {
            l.bisectId = search.id;
            l.etaSec = search.etaSec;
            l.deep = search.state.deep;
        }

        return java.util.Optional.of(l);
    }

    /**
     * Commits vs parent between the release and the master run that moved the metric, plus what bisections found,
     * largest first.
     */
    private List<Found> found(String metric, List<JobService.TrendPoint> points, List<Bisect> own,
        List<Bisect> searches, int from, int upTo) {
        Map<String, Found> out = new LinkedHashMap<>();

        for (JobService.TrendPoint p : points) {
            if (p.span == 1 && p.seq != null && p.seq > from && p.seq <= upTo && moved(p.metricVerdict.get(metric)))
                out.put(p.sha, commit(p.sha, p.subject, p.deltaPct.get(metric)));
        }

        for (Bisect b : searches) {
            for (Bisect.Finding f : b.state.findings) {
                int s = seq(f.b, 0);

                if ("found".equals(f.kind) && s > from && s <= upTo)
                    out.computeIfAbsent(f.b, k -> commit(f.b, b.subjects.get(f.b), f.result.deltaPct));
            }
        }

        for (Bisect b : own) {
            for (Bisect.Finding f : b.state.findings) {
                if ("found".equals(f.kind) && out.containsKey(f.b))
                    out.get(f.b).bisectId = b.id;
            }
        }

        List<Found> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingDouble((Found c) -> -Math.abs(c.deltaPct)));

        return list;
    }

    /**
     * The shortest measured segment of the range that moved the metric the way {@code delta} did, when it is a real
     * part of the range.
     */
    private static JobService.TrendPoint narrowest(List<JobService.TrendPoint> points, String metric, double delta,
        int from, int upTo) {
        JobService.TrendPoint best = null;

        for (JobService.TrendPoint p : points) {
            Double d = p.deltaPct.get(metric);

            if (p.span > 1 && p.span * 4 <= (upTo - from) * 3 && p.seq != null && p.seq > from && p.seq <= upTo
                && d != null && d * delta > 0 && moved(p.metricVerdict.get(metric))
                && (best == null || p.span < best.span))
                best = p;
        }

        return best;
    }

    /** */
    private static Found commit(String sha, String subject, Double delta) {
        Found c = new Found();
        c.sha = sha;
        c.subject = subject;
        c.deltaPct = delta == null ? 0 : delta;

        return c;
    }

    /** The state of a metric that moved by {@code delta}: {@code search} is its latest bisection, if any. */
    static String status(double delta, double threshold, List<Found> commits, Bisect search) {
        double rest = delta - commits.stream().mapToDouble(c -> c.deltaPct).sum();

        if (!commits.isEmpty() && Math.abs(rest) <= threshold)
            return EXPLAINED;

        if (search != null && search.active())
            return search.status;

        if (!commits.isEmpty())
            return PARTIAL;

        return search != null && (Bisect.INCONCLUSIVE.equals(search.status) || Bisect.SPREAD.equals(search.status)
            || Bisect.LOST.equals(search.status))
            ? search.status : UNEXPLORED;
    }

    /** A run of the branch newer than the master run: a line per metric that moved. */
    private static List<Line> fresh(JobService.TrendPoint p) {
        List<Line> out = new ArrayList<>();

        for (String m : METRICS) {
            if (!moved(p.metricVerdict.get(m)))
                continue;

            Line l = new Line();
            l.metric = m;
            l.status = NEW;
            l.verdict = p.metricVerdict.get(m);
            l.deltaPct = p.deltaPct.getOrDefault(m, 0.0);
            l.thresholdPct = p.thresholdPct.getOrDefault(m, 0.0);
            l.jobId = p.jobId;
            l.rangeCommits = p.span;
            l.baseSha = p.baseSha;
            l.sha = p.sha;
            l.span = p.span;
            out.add(l);
        }

        return out;
    }

    /** */
    private static boolean moved(String verdict) {
        return Stats.REGRESSION.equals(verdict) || Stats.IMPROVEMENT.equals(verdict);
    }

    /** */
    private static int rank(Line l) {
        return ORDER.indexOf(l.status);
    }

    /** */
    private int seq(String sha, int fallback) {
        CommitInfo c = db.commit(sha);

        return c == null || c.seq == null ? fallback : c.seq;
    }
}
