package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The section of the plugins: {@code /api/plugins/*}. A stand without plugins answers with an empty list, and the UI
 * shows no section.
 */
final class PluginApi {
    /** Runs a plugin page shows in its history. */
    private static final int HISTORY = 30;

    /** Tags of the extension a page offers for its release. */
    private static final int TAGS = 15;

    /** A step of Compare that moves Ignite: the extension of A on both sides. */
    static final String IGNITE = "ignite";

    /** A step of Compare that moves the extension: the Ignite of B on both sides. */
    static final String EXT = "ext";

    /** A compare that moves both sides at once, as an older stand queued it. */
    static final String BOTH = "both";

    /** How far apart the jobs of one request of Compare are queued at most. */
    private static final Duration ONE_REQUEST = Duration.ofMinutes(1);

    /** */
    private final Config cfg;

    /** */
    private final Db db;

    /** */
    private final Plugins plugins;

    /** */
    private final JobService jobs;

    /** */
    private final GitMirror ignite;

    /**
     * @param cfg Configuration.
     * @param db Database.
     * @param plugins Plugins.
     * @param jobs Jobs.
     * @param ignite Mirror of Ignite.
     */
    PluginApi(Config cfg, Db db, Plugins plugins, JobService jobs, GitMirror ignite) {
        this.cfg = cfg;
        this.db = db;
        this.plugins = plugins;
        this.jobs = jobs;
        this.ignite = ignite;
    }

    /**
     * Registers the routes.
     *
     * @param r Router.
     */
    void routes(Router r) {
        r.get("/api/plugins", req -> {
            List<Map<String, Object>> list = new ArrayList<>();

            for (Plugins.Plugin p : plugins.list())
                list.add(view(p));

            return Map.of("plugins", list, "errors", plugins.errors());
        });
        r.get("/api/plugins/{name}", req -> overview(plugins.require(req.param("name"))));
        r.get("/api/plugins/{name}/scenarios/{scenario}", req -> {
            Scenarios.Info sc = plugins.require(req.param("name")).scenarios.get(req.param("scenario"));

            if (sc == null)
                throw new java.util.NoSuchElementException("no scenario " + req.param("scenario"));

            return sc.raw;
        });
        r.post("/api/plugins/{name}/run", req -> {
            JsonNode b = req.json();

            return PluginSchedule.runNow(plugins, jobs, ignite, req.param("name"), names(b),
                Router.Req.integer(b, "priority", 1));
        });
        r.post("/api/plugins/{name}/compare", req -> compare(plugins.require(req.param("name")), req.json()));
    }

    /** {@code scenarios} of a body: empty for all. */
    private static List<String> names(JsonNode b) {
        List<String> out = new ArrayList<>();
        b.path("scenarios").forEach(n -> out.add(n.asText()));

        return out;
    }

    /** What the list and the page say of a plugin: the jar, the settings, the mirror and the pairs now. */
    private Map<String, Object> view(Plugins.Plugin p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name);
        m.put("title", p.title);
        m.put("description", p.description);
        m.put("module", p.module);
        m.put("repo", plugins.repo(p));
        m.put("branch", plugins.branch(p));
        m.put("repoFromConfig", p.repoFromConfig); // else the jar's: either way fixed until a restart
        m.put("defaultRepo", p.defaultRepo);
        m.put("defaultBranch", p.defaultBranch);
        m.put("jar", p.jar.getFileName().toString());
        m.put("igniteRepo", cfg.repoUrl);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("release", plugins.setting(p, Plugins.RELEASE, ""));
        s.put("ignite", plugins.setting(p, Plugins.IGNITE, ""));
        s.put("mode", plugins.setting(p, Plugins.MODE, Freshness.KEEP));
        s.put("keepDays", Integer.parseInt(plugins.setting(p, Plugins.KEEP_DAYS,
            String.valueOf(PluginSchedule.KEEP_DAYS))));
        m.put("settings", s);
        Map<String, Object> mir = new LinkedHashMap<>();
        mir.put("ready", p.mirror.ready());
        mir.put("lastFetchAt", p.mirror.lastFetchAt());
        mir.put("lastFetchError", p.mirror.lastFetchError());
        m.put("mirror", mir);

        try {
            m.put("pairs", PluginSchedule.pairs(plugins, p, ignite));
        }
        catch (IOException | RuntimeException e) {
            m.put("pairsError", e.getMessage());
        }

        m.put("scenarios", p.scenarios.list());

        return m;
    }

    /**
     * The page of a plugin: per scenario its last result against the release, a run in the queue, a failure since, and
     * its history with how far each side moved from the run before.
     */
    private Map<String, Object> overview(Plugins.Plugin p) {
        Map<String, Object> m = view(p);
        List<Map<String, Object>> rows = new ArrayList<>();

        for (Scenarios.Info sc : p.scenarios.list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scenario", sc.name);
            List<Job> all = db.pluginJobs(p.name, sc.name, null, false, 50);
            row.put("active", all.stream().filter(Job::active).toList());
            List<Job> done = db.pluginJobs(p.name, sc.name, JobService.SRC_PLUGIN, true, HISTORY);
            Job last = done.isEmpty() ? null : done.get(0);
            row.put("last", last);
            // a pair that does not build or run since the last result: incompatible until something moves
            row.put("failed", all.stream().filter(j -> JobService.SRC_PLUGIN.equals(j.source)
                && Job.FAILED.equals(j.status) && (last == null || j.id > last.id)).findFirst().orElse(null));
            List<Map<String, Object>> hist = new ArrayList<>();

            for (int i = 0; i < done.size(); i++) {
                Job j = done.get(i), prev = i + 1 < done.size() ? done.get(i + 1) : null;
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("job", j);
                h.put("igniteMoved", prev == null ? null : moved(ignite, prev.shaB, j.shaB));
                h.put("extMoved", prev == null ? null : moved(p.mirror, prev.extShaB, j.extShaB));
                hist.add(h);
            }

            row.put("history", hist);
            rows.add(row);
        }

        m.put("rows", rows);
        m.put("comparisons", comparisons(db.pluginJobs(p.name, null, JobService.SRC_PLUGIN_COMPARE, false,
            4 * HISTORY)));
        m.put("tags", tags(p));

        return m;
    }

    /** Recent tags of the extension for the release to choose: those of its module when the names tell. */
    private static List<String> tags(Plugins.Plugin p) {
        if (!p.mirror.ready())
            return List.of();

        try {
            List<String> all = p.mirror.recentTags(500);
            // modules/spring-data-ext/spring-data: the tags of ignite-extensions are ignite-spring-data-ext-X.Y.Z
            String dir = p.module.replaceFirst("^modules/", "").replaceFirst("/.*", "");
            List<String> own = all.stream().filter(t -> t.contains(dir)).toList();

            return (own.isEmpty() ? all : own).stream().limit(TAGS).toList();
        }
        catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The jobs of Compare grouped by the request that queued them, the newest first: its two pairs and, per scenario,
     * the job of each step. A step moves one side: {@link #IGNITE} (the extension the same on both sides) or
     * {@link #EXT} (the Ignite the same); a compare of an older stand moves {@link #BOTH} in one job.
     *
     * @param jobs Jobs of Compare.
     * @return {a, b, createdAt, status, jobs, scenarios: [{scenario, ignite, ext, both}]} of each request.
     */
    static List<Map<String, Object>> comparisons(List<Job> jobs) {
        List<Request> out = new ArrayList<>();

        for (Job j : jobs.stream().sorted(java.util.Comparator.comparingLong((Job x) -> x.id)).toList()) {
            String side = !j.shaA.equals(j.shaB) && j.extShaA.equals(j.extShaB) ? IGNITE
                : j.shaA.equals(j.shaB) ? EXT : BOTH;
            Request r = null;

            for (int i = out.size() - 1; i >= 0 && r == null; i--)
                r = out.get(i).fits(j, side) ? out.get(i) : null;

            if (r == null)
                out.add(r = new Request(j));

            r.add(j, side);
        }

        List<Map<String, Object>> res = new ArrayList<>();

        for (int i = out.size() - 1; i >= 0; i--)
            res.add(out.get(i).view());

        return res;
    }

    /** One request of Compare: what is known of its pairs so far, and its jobs. */
    private static final class Request {
        /** The Ignite of A, as far as the steps tell. */
        private String aIgnite;

        /** The extension of A. */
        private String aExt;

        /** The Ignite of B. */
        private String bIgnite;

        /** The extension of B. */
        private String bExt;

        /** When its first job was queued. */
        private final Instant first;

        /** Jobs by scenario and side. */
        private final Map<String, Map<String, Job>> steps = new LinkedHashMap<>();

        /** */
        private final List<Job> jobs = new ArrayList<>();

        /** */
        Request(Job j) {
            first = Instant.parse(j.createdAt);
        }

        /** Whether the job is a step of this request: queued with it, a scenario and side not yet taken, its pairs. */
        boolean fits(Job j, String side) {
            if (Duration.between(first, Instant.parse(j.createdAt)).compareTo(ONE_REQUEST) > 0
                || steps.getOrDefault(j.scenario, Map.of()).containsKey(side))
                return false;

            return switch (side) {
                case IGNITE -> same(aIgnite, j.shaA) && same(aExt, j.extShaA) && same(bIgnite, j.shaB);
                case EXT -> same(bIgnite, j.shaA) && same(aExt, j.extShaA) && same(bExt, j.extShaB);
                default -> same(aIgnite, j.shaA) && same(aExt, j.extShaA) && same(bIgnite, j.shaB)
                    && same(bExt, j.extShaB);
            };
        }

        /** */
        void add(Job j, String side) {
            if (!EXT.equals(side))
                aIgnite = j.shaA;

            if (!IGNITE.equals(side))
                bExt = j.extShaB;

            aExt = j.extShaA;
            bIgnite = j.shaB;
            steps.computeIfAbsent(j.scenario, k -> new LinkedHashMap<>()).put(side, j);
            jobs.add(j);
        }

        /** */
        Map<String, Object> view() {
            Job firstStep = null, lastStep = null;
            List<Map<String, Object>> rows = new ArrayList<>();

            for (Map.Entry<String, Map<String, Job>> e : steps.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scenario", e.getKey());

                for (String side : List.of(IGNITE, EXT, BOTH))
                    row.put(side, e.getValue().get(side));

                rows.add(row);
                Map<String, Job> s = e.getValue();
                firstStep = firstStep != null ? firstStep : s.getOrDefault(IGNITE, s.getOrDefault(BOTH, s.get(EXT)));
                lastStep = lastStep != null ? lastStep : s.getOrDefault(EXT, s.getOrDefault(BOTH, s.get(IGNITE)));
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", sides(firstStep)[1]); // a step that changes nothing is not queued: A of the first, B of the last
            m.put("b", sides(lastStep)[0]);
            m.put("createdAt", jobs.get(0).createdAt);
            m.put("status", jobs.stream().anyMatch(j -> Job.RUNNING.equals(j.status)) ? Job.RUNNING
                : jobs.stream().anyMatch(Job::active) ? Job.QUEUED
                : jobs.stream().anyMatch(j -> Job.FAILED.equals(j.status)) ? Job.FAILED : Job.DONE);
            m.put("jobs", jobs.stream().map(j -> j.id).toList());
            m.put("scenarios", rows);

            return m;
        }
    }

    /** {B, A} of the label of a job of a plugin, "name: B vs A" (see PluginSchedule#queue). */
    private static String[] sides(Job j) {
        String l = j.label == null ? "" : j.label;
        int vs = l.indexOf(" vs "), colon = l.indexOf(": ");

        if (vs < 0)
            return new String[] {Job.shortSha(j.shaB), Job.shortSha(j.shaA)};

        return new String[] {l.substring(colon < 0 || colon > vs ? 0 : colon + 2, vs), l.substring(vs + 4)};
    }

    /** {@code null} is not known yet: anything fits it. */
    private static boolean same(String known, String sha) {
        return known == null || known.equals(sha);
    }

    /** Commits of the branch from one run to the next; null when the mirror cannot tell. */
    private static Integer moved(GitMirror mirror, String from, String to) {
        if (from == null || to == null)
            return null;

        if (from.equals(to))
            return 0;

        try {
            return mirror.revList(from, to).size();
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Two pairs from the Compare of the page: {@code a} and {@code b} as {@code {ignite, ext}} refs. The change goes
     * one side at a time, so the result says which side moved: Ignite first (the extension of A on both sides), then
     * the extension (the Ignite of B on both sides); a step that changes nothing is left out.
     */
    private List<Job> compare(Plugins.Plugin p, JsonNode b) throws IOException {
        PluginSchedule.Pair a = pair(p, b.path("a")), bb = pair(p, b.path("b"));
        List<String> names = names(b);
        int priority = Router.Req.integer(b, "priority", 1);
        List<PluginSchedule.Pair[]> steps = new ArrayList<>();
        PluginSchedule.Pair mid = new PluginSchedule.Pair(bb.igniteRef(), bb.igniteSha(), a.extRef(), a.extSha());

        if (!mid.igniteSha().equals(a.igniteSha()))
            steps.add(new PluginSchedule.Pair[] {a, mid});

        if (!mid.extSha().equals(bb.extSha()))
            steps.add(new PluginSchedule.Pair[] {mid, bb});

        if (steps.isEmpty())
            throw new IllegalArgumentException("the two pairs are the same");

        List<Job> out = new ArrayList<>();

        for (Scenarios.Info sc : p.scenarios.list()) {
            if (!names.isEmpty() && !names.contains(sc.name))
                continue;

            for (PluginSchedule.Pair[] s : steps)
                out.add(PluginSchedule.queue(jobs, p, sc, s[0], s[1], JobService.SRC_PLUGIN_COMPARE, priority));
        }

        if (out.isEmpty())
            throw new IllegalArgumentException("no scenario of " + p.name + " chosen");

        return out;
    }

    /** {@code {ignite, ext}} refs to a pair. */
    private PluginSchedule.Pair pair(Plugins.Plugin p, JsonNode n) throws IOException {
        String ig = Router.Req.str(n, "ignite", "").trim(), ex = Router.Req.str(n, "ext", "").trim();

        if (ig.isEmpty() || ex.isEmpty())
            throw new IllegalArgumentException("a pair needs a ref of Ignite and a ref of the extension");

        if (!ignite.ready() || !p.mirror.ready())
            throw new IllegalStateException("the repositories are not fetched yet");

        return new PluginSchedule.Pair(ig, ignite.resolve(ignite.normalize(ig)), ex, p.mirror.resolve(p.mirror.normalize(ex)));
    }
}
