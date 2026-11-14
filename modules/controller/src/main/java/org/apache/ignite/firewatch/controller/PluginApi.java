package org.apache.ignite.firewatch.controller;

import java.io.IOException;
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
        m.put("compares", db.pluginJobs(p.name, null, JobService.SRC_PLUGIN_COMPARE, false, HISTORY));

        return m;
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
     * Two pairs from the Compare of the page: {@code a} and {@code b} as {@code {ignite, ext}} refs. With {@code split}
     * the change goes in two steps, one side at a time: Ignite first (the extension of A on both sides), then the
     * extension (the Ignite of B on both sides); a step that changes nothing is left out.
     */
    private List<Job> compare(Plugins.Plugin p, JsonNode b) throws IOException {
        PluginSchedule.Pair a = pair(p, b.path("a")), bb = pair(p, b.path("b"));
        List<String> names = names(b);
        int priority = Router.Req.integer(b, "priority", 1);
        List<PluginSchedule.Pair[]> steps = new ArrayList<>();

        if (b.path("split").asBoolean(false)) {
            PluginSchedule.Pair mid = new PluginSchedule.Pair(bb.igniteRef(), bb.igniteSha(), a.extRef(), a.extSha());

            if (!mid.igniteSha().equals(a.igniteSha()))
                steps.add(new PluginSchedule.Pair[] {a, mid});

            if (!mid.extSha().equals(bb.extSha()))
                steps.add(new PluginSchedule.Pair[] {mid, bb});
        }
        else
            steps.add(new PluginSchedule.Pair[] {a, bb});

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
