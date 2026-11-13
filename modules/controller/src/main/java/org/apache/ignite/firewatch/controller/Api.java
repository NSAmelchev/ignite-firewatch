package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import static org.apache.ignite.firewatch.controller.Router.Req;
import static org.apache.ignite.firewatch.controller.Router.Response;

/** REST API ({@code /api/...}) and the static UI ({@code /}). */
public final class Api {
    /** A scenario as the harness lists it: id, version, topology, timing and the source of its classes. */
    private JsonNode describe(String name) {
        Scenarios.Info info = scenarios.get(name);

        if (info == null || info.raw == null)
            throw new NoSuchElementException("no scenario " + name);

        return info.raw;
    }

    /** */
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9._-]+");

    /** */
    private static final long STARTED = System.currentTimeMillis();

    /** */
    private final Config cfg;

    /** */
    private final Db db;

    /** */
    private final JobService jobs;

    /** */
    private final JobRunner runner;

    /** */
    private final Watcher watcher;

    /** */
    private final Scenarios scenarios;

    /** */
    private final GitMirror mirror;

    /** */
    private final Notifier notifier;

    /** */
    private final Bisector bisector;

    /** */
    private final Comparisons comparisons;

    /** */
    private HttpServer server;

    /** */
    private ExecutorService pool;

    /**
     * Creates the HTTP API.
     *
     * @param cfg Controller config.
     * @param db Database.
     * @param jobs Job service.
     * @param runner Job runner.
     * @param watcher Branch watcher.
     * @param scenarios Known scenarios.
     * @param mirror Git mirror.
     * @param notifier Notifier.
     * @param bisector Bisector.
     * @param comparisons Comparisons service.
     */
    public Api(Config cfg, Db db, JobService jobs, JobRunner runner, Watcher watcher, Scenarios scenarios,
        GitMirror mirror, Notifier notifier, Bisector bisector, Comparisons comparisons) {
        this.notifier = notifier;
        this.bisector = bisector;
        this.comparisons = comparisons;
        this.cfg = cfg;
        this.db = db;
        this.jobs = jobs;
        this.runner = runner;
        this.watcher = watcher;
        this.scenarios = scenarios;
        this.mirror = mirror;
    }

    /** */
    public void start() throws IOException {
        Router r = new Router();
        r.get("/api/health", req -> health());
        r.get("/api/storage", req -> Retention.usage(db, cfg.runsDir()));
        r.get("/api/log", req -> Log.recent(req.queryInt("lines", 100)));
        r.get("/api/scenarios", req -> scenarios.list());
        r.get("/api/scenarios/{name}/describe", req -> describe(req.param("name")));
        r.get("/api/settings", req -> maskedSettings());
        r.put("/api/settings", req -> {
            JsonNode body = req.json();
            Map<String, String> applied = new LinkedHashMap<>();

            for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                String v = body.get(k).asText();

                if (Settings.SECRET.contains(k) && Settings.MASK.equals(v))
                    continue; // unchanged secret echoed back by the form

                applied.put(k, Settings.validate(k, v, scenarios));
            }

            // a new stand has no mirror to check it against yet
            if (applied.containsKey(Settings.RELEASE_TAG) && mirror.ready())
                jobs.checkRelease(applied.get(Settings.RELEASE_TAG));

            if (applied.containsKey(Settings.REPO_URL) || applied.containsKey(Settings.REPO_BRANCH))
                watcher.follow(applied.getOrDefault(Settings.REPO_URL, cfg.repoUrl),
                    applied.getOrDefault(Settings.REPO_BRANCH, cfg.branch));

            applied.forEach(db::setSetting);
            Map<String, String> logged = new LinkedHashMap<>(applied);
            Settings.SECRET.forEach(k -> logged.computeIfPresent(k, (kk, vv) -> vv.isEmpty() ? "" : Settings.MASK));
            Log.info("settings changed: " + logged);

            if (applied.containsKey(Settings.RETENTION_KEEP_COMPARISONS))
                comparisons.prune();

            if (applied.containsKey(Settings.RETENTION_KEEP_BISECTS))
                bisector.prune();

            return maskedSettings();
        });
        StandService stands = runner.stands();
        r.get("/api/stand", req -> stands.view());
        r.put("/api/stand", req -> {
            Stand s = Json.read(req.json().toString(), Stand.class);
            s.save(db);
            Log.info("stand saved: " + s.mode + ", " + s.hosts.size() + " host(s)");
            watcher.pollNow(); // the setup of a new stand held it back

            return stands.view();
        });
        r.post("/api/stand/check", req -> stands.check(Json.read(req.json().toString(), Stand.class)));
        r.get("/api/stand/check/log", req -> stands.checkLog());
        // only reads: the setup page and Settings run it whenever they open; the form may hold an unsaved repository
        r.post("/api/app/check", req -> {
            JsonNode b = req.json();

            return BuildCheck.run(cfg, Req.str(b, "url", db.setting(Settings.REPO_URL, cfg.repoUrl)),
                Req.str(b, "branch", db.setting(Settings.REPO_BRANCH, cfg.branch)));
        });
        r.post("/api/stand/measure", req -> {
            if (Stand.frozen(db))
                throw new IllegalStateException("the stand has finished runs: its load is fixed");

            return jobs.enqueueProbe();
        });
        r.post("/api/stand/sweep", req -> Map.of("ok", stands.clean()));
        r.post("/api/notify/test", req -> {
            String attempted = notifier.send("Firewatch: test message from "
                + db.setting(Notifier.BASE_URL, "the controller"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attempted", attempted);
            m.put("lastResult", notifier.lastResult());

            return m;
        });
        r.get("/api/notify/status", req -> Map.of("lastResult", String.valueOf(notifier.lastResult())));
        r.get("/api/commits/{sha}", req -> jobs.commitDetail(req.param("sha")));
        r.get("/api/commits", req -> jobs.commitsWithJobs(req.queryInt("limit", 100), req.query("scenario", null)));
        r.get("/api/search", req -> jobs.search(req.query("q", ""), req.queryInt("limit", 10)));
        r.get("/api/jobs", req -> jobs(req));
        r.post("/api/jobs", req -> {
            JsonNode b = req.json();

            return jobs.enqueue(Req.str(b, "type", Job.ABBA), Req.str(b, "refA", null), Req.str(b, "refB", null),
                Req.str(b, "scenario", Settings.firstScenario(db)), Req.integer(b, "priority", 1),
                JobService.SRC_MANUAL, Req.str(b, "label", null));
        });
        r.get("/api/jobs/{id}", req -> {
            Job j = jobs.detail(req.paramLong("id"));

            if (j != null) {
                jobs.addSubjects(List.of(j));
                jobs.addSegment(j);
            }

            return j;
        });
        r.post("/api/jobs/{id}/cancel", req -> {
            if (!jobs.cancel(req.paramLong("id")))
                throw new NoSuchElementException("no active job " + req.param("id"));

            return jobs.detail(req.paramLong("id"));
        });
        r.post("/api/jobs/{id}/retry", req -> {
            JsonNode b = req.json();

            return jobs.retry(req.paramLong("id"), b.hasNonNull("priority") ? b.get("priority").asInt() : null);
        });
        r.get("/api/estimate",
            req -> jobs.estimate(req.query("scenario", Settings.firstScenario(db)), req.query("type", Job.ABBA),
                req.queryInt("jobs", 1), List.of(req.query("shas", "").split(",")), req.queryInt("extraBuilds", 0),
                req.queryInt("priority", 1)));
        r.post("/api/jobs/{id}/split", req -> {
            JsonNode b = req.json();

            return jobs.split(req.paramLong("id"), Req.str(b, "mode", "halves"), Req.integer(b, "priority", 1));
        });
        r.get("/api/attention", req -> new Attention(db, mirror, jobs, scenarios, bisector).view());
        r.get("/api/bisects",
            req -> bisector.list(req.query("scenario", null), "true".equals(req.query("active", "false")),
                req.queryInt("limit", 20)));
        r.get("/api/bisects/{id}", req -> bisector.get(req.paramLong("id")));
        r.post("/api/bisects", req -> {
            JsonNode b = req.json();

            if (!b.hasNonNull("jobId"))
                throw new IllegalArgumentException("jobId is required");

            return bisector.start(b.get("jobId").asLong(), Req.str(b, "metric", "throughput"),
                b.hasNonNull("priority") ? b.get("priority").asInt() : null);
        });
        r.post("/api/bisects/{id}/cancel", req -> {
            JsonNode b = req.json();

            return bisector.cancel(req.paramLong("id"), b.path("stopRunning").asBoolean(true),
                b.path("deleteFinished").asBoolean(false));
        });
        r.post("/api/bisects/{id}/resume", req -> bisector.resume(req.paramLong("id")));
        r.delete("/api/bisects/{id}", req -> {
            bisector.delete(req.paramLong("id"));

            return Map.of("deleted", true);
        });
        r.post("/api/bisects/{id}/deep", req -> {
            JsonNode b = req.json();

            return bisector.deep(req.paramLong("id"), b.hasNonNull("priority") ? b.get("priority").asInt() : null);
        });
        r.delete("/api/jobs/{id}", req -> {
            if (!jobs.delete(req.paramLong("id")))
                throw new NoSuchElementException("no finished job " + req.param("id") + " (cancel it first)");

            return Map.of("deleted", req.paramLong("id"));
        });
        r.post("/api/jobs/{id}/priority", req -> {
            int p = Req.integer(req.json(), "priority", 0);

            if (!jobs.setPriority(req.paramLong("id"), p))
                throw new NoSuchElementException("no queued job " + req.param("id"));

            return jobs.detail(req.paramLong("id"));
        });
        r.get("/api/jobs/{id}/log", req -> jobLog(req.paramLong("id"), req.queryInt("lines", 80)));
        r.get("/api/jobs/{id}/series", req -> jobSeries(req.paramLong("id")));
        r.get("/api/jobs/{id}/profile", req -> jobProfile(req.paramLong("id"), req.queryInt("limit", 40)));
        r.get("/api/noise", req -> jobs.noise(req.query("scenario", Settings.firstScenario(db))));
        Downloads downloads = new Downloads(cfg, db, scenarios);
        r.get("/api/jobs/{id}/downloads", req -> downloads.of(req.paramLong("id")));
        r.get("/api/jobs/{id}/zip", req -> downloads.jobZip(req.paramLong("id"), "1".equals(req.query("jfr", "0"))));
        r.get("/api/builds/{key}/log", req -> downloads.buildLog(req.param("key")));
        r.get("/api/builds/{key}/jars", req -> downloads.buildJars(req.param("key")));
        r.get("/api/runs/{id}/series", req -> runFile(req.paramLong("id"), "series.json", true));
        r.get("/api/runs/{id}/result", req -> runFile(req.paramLong("id"), "result.json", true));
        r.get("/api/runs/{id}/files", req -> runFiles(req.paramLong("id")));
        r.get("/api/runs/{id}/files/{name}", req -> runFile(req.paramLong("id"), req.param("name"), false));
        r.get("/api/trend", req -> jobs.trend(req.query("scenario", Settings.firstScenario(db)),
            req.queryInt("limit", 60), "true".equals(req.query("refined", "false"))));
        r.get("/api/comparisons", req -> comparisons.list(req.queryInt("limit", 20)));
        r.get("/api/comparisons/{id}", req -> comparisons.get(req.paramLong("id")));
        r.post("/api/comparisons", req -> {
            JsonNode b = req.json();
            List<String> names = new ArrayList<>();
            b.path("scenarios").forEach(n -> names.add(n.asText()));

            return comparisons.create(Req.str(b, "base", null), Req.str(b, "target", null), names,
                Req.integer(b, "priority", 2));
        });
        r.post("/api/comparisons/{id}/cancel", req -> comparisons.cancel(req.paramLong("id")));
        r.delete("/api/comparisons/{id}", req -> {
            comparisons.delete(req.paramLong("id"));

            return Map.of("deleted", true);
        });
        r.get("/api/release", req -> jobs.release(req.query("scenario", Settings.firstScenario(db))));
        r.post("/api/release", req -> jobs.queueRelease(Req.str(req.json(), "scenario", Settings.firstScenario(db)),
            Req.integer(req.json(), "priority", 1)));
        r.post("/api/calibrate", req -> {
            JsonNode b = req.json();

            return jobs.calibrate(Req.str(b, "ref", null), Req.str(b, "scenario", Settings.firstScenario(db)),
                Req.integer(b, "priority", 0));
        });
        // a build with what of it can be downloaded; ?sha= the builds of one commit
        r.get("/api/builds", req -> {
            String sha = req.query("sha", null);
            List<Map<String, Object>> out = new ArrayList<>();

            for (Build b : sha == null ? db.builds(req.queryInt("limit", 50)) : db.buildsOf(sha)) {
                Map<String, Object> m = Json.MAPPER.convertValue(b, Json.MAP);
                m.putAll(downloads.build(b.sha, b));
                out.add(m);
            }

            return out;
        });
        r.post("/api/worker/pause", req -> {
            runner.setPaused(true);

            return health();
        });
        r.post("/api/worker/resume", req -> {
            runner.setPaused(false);

            return health();
        });
        r.post("/api/watch/poll", req -> {
            watcher.pollNow();

            return watcher.status();
        });
        r.post("/api/watch/pause", req -> {
            db.setSetting(Settings.AUTORUN_ENABLED, "false");

            return watcher.status();
        });
        r.post("/api/watch/resume", req -> {
            db.setSetting(Settings.AUTORUN_ENABLED, "true");

            return watcher.status();
        });
        r.post("/api/resolve", req -> {
            String ref = Req.str(req.json(), "ref", null);
            String sha = mirror.resolve(mirror.normalize(ref));
            CommitInfo ci = mirror.info(sha);

            return ci == null ? Map.of("sha", sha) : ci;
        });
        r.post("/api/kill-all", req -> Map.of("killed", StrayJvms.killAll(cfg.runPrefix())));

        server = HttpServer.create(new InetSocketAddress(cfg.bindHost, cfg.port), 16);
        pool = Executors.newFixedThreadPool(8, rn -> {
            Thread t = new Thread(rn, "http");
            t.setDaemon(true);

            return t;
        });
        server.setExecutor(pool);
        server.createContext("/api", r);
        server.createContext("/", this::serveStatic);
        server.start();
        Log.info("listening on http://" + cfg.bindHost + ":" + port());
    }

    /**
     * Returns the port the HTTP server listens on.
     *
     * @return Bound port.
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /** */
    public void stop() {
        if (server != null)
            server.stop(0);

        if (pool != null)
            pool.shutdownNow();
    }

    /** */
    private Map<String, String> maskedSettings() {
        Map<String, String> m = db.settings();

        for (String k : Settings.SECRET) {
            String v = m.get(k);

            if (v != null && !v.isEmpty())
                m.put(k, Settings.MASK);
        }

        return m;
    }

    /** */
    private Object jobs(Req req) {
        String filter = req.query("filter", "all");
        int limit = req.queryInt("limit", 50);
        List<Job> list;

        if ("active".equals(filter)) {
            // ETA is cumulative, so it is computed over the whole queue and only then cut to the requested page
            list = db.jobs(filter, Integer.MAX_VALUE);
            jobs.addEta(list); // running first, then queued in run order (priority, id)

            if (list.size() > limit)
                list = new ArrayList<>(list.subList(0, limit));
        }
        else
            list = db.jobs(filter, limit);

        jobs.addSubjects(list);
        JobService.slim(list); // list views need a summary only; GET /api/jobs/{id} returns the full comparison

        return list;
    }

    /** */
    private Map<String, Object> jobLog(long id, int lines) {
        Job j = db.job(id);

        if (j == null)
            throw new NoSuchElementException("no job " + id);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", id);
        m.put("stage", j.stage);
        m.put("file", j.logFile);
        m.put("text", j.logFile == null ? "" : Proc.tailFile(Path.of(j.logFile), lines));

        return m;
    }

    /** Per-second series of every phase of a job in one response (null where a phase has no series.json). */
    private Map<String, Object> jobSeries(long id) throws IOException {
        Job j = db.job(id);

        if (j == null)
            throw new NoSuchElementException("no job " + id);

        List<Map<String, Object>> runs = new ArrayList<>();

        for (Run r : db.runs(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("phase", r.phase);
            m.put("sha", r.sha);
            m.put("status", r.status);
            Path f = cfg.dataDir.resolve(r.dir).resolve("series.json");
            m.put("series", Files.isRegularFile(f) ? Json.readTree(f) : null);
            runs.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", id);
        out.put("type", j.type);
        out.put("runs", runs);

        return out;
    }

    /** Hot-method diff of the A and B sides (single jobs: the S phase as B against nothing). */
    private Object jobProfile(long id, int limit) throws IOException {
        Job j = db.job(id);

        if (j == null)
            throw new NoSuchElementException("no job " + id);

        List<JsonNode> a = new ArrayList<>(), b = new ArrayList<>();

        for (Run r : db.runs(id)) {
            Path f = cfg.dataDir.resolve(r.dir).resolve("profile.json");

            if (!Files.isRegularFile(f))
                continue;

            (r.phase.startsWith("A") ? a : b).add(Json.readTree(f));
        }

        ProfileDiff.Result res = ProfileDiff.diff(a, b, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", id);
        out.put("available", !a.isEmpty() || !b.isEmpty());
        out.put("diff", res);

        return out;
    }

    /** */
    private Object runFiles(long id) {
        Run r = db.run(id);

        if (r == null)
            throw new NoSuchElementException("no run " + id);

        Path dir = cfg.dataDir.resolve(r.dir);
        List<Map<String, Object>> out = new ArrayList<>();

        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>)s.sorted()::iterator) {
                    if (!Files.isRegularFile(p))
                        continue;

                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", p.getFileName().toString());
                    f.put("size", Files.size(p));
                    out.add(f);
                }
            }
            catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        return out;
    }

    /** */
    private Response runFile(long id, String name, boolean json) throws IOException {
        Run r = db.run(id);

        if (r == null)
            throw new NoSuchElementException("no run " + id);

        if (!SAFE_FILE.matcher(name).matches())
            throw new Router.HttpError(400, "bad file name");

        Path f = cfg.dataDir.resolve(r.dir).resolve(name);

        if (!Files.isRegularFile(f))
            throw new NoSuchElementException(name + " is not available for run " + id);

        String type = json || name.endsWith(".json") ? "application/json"
            : name.endsWith(".jfr") ? "application/octet-stream" : "text/plain";
        // a recording is tens of MB, a node log can be hundreds, and the controller heap is 128 MB: stream, never read
        // them whole
        Response resp = Response.file(type, f);

        if (name.endsWith(".jfr"))
            resp.headers.put("Content-Disposition", "attachment; filename=\"job" + r.jobId + "-" + r.phase + "-" + name
                + "\"");

        return resp;
    }

    /** */
    private Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", Config.version());
        m.put("now", Db.now());
        m.put("uptimeSec", (System.currentTimeMillis() - STARTED) / 1000);
        m.put("fakeBuild", cfg.fakeBuild);
        m.put("harnessJar", cfg.harnessJar.toString());
        m.put("maven", cfg.mavenText());
        Map<String, Object> worker = new LinkedHashMap<>();
        Job cur = runner.current();
        worker.put("busy", cur != null);
        worker.put("paused", runner.paused());

        if (cur != null) {
            Job fresh = db.job(cur.id);
            worker.put("job", fresh == null ? cur : fresh);
        }

        m.put("worker", worker);
        m.put("watcher", watcher.status());
        m.put("jobs", db.jobCounts());
        m.put("bisects", bisector == null ? 0 : bisector.activeCount());
        m.put("counts", Map.of("comparisons", db.count("comparisons"), "bisects", db.endedBisects()));
        m.put("bisectsDoneAt", bisector == null ? null : bisector.doneAt());
        List<Job> active = db.jobs("active", Integer.MAX_VALUE);
        jobs.addEta(active);
        Job last = active.isEmpty() ? null : active.get(active.size() - 1);
        m.put("queueDrainsAt", last == null || last.etaStartAt == null || last.etaSec == null ? null
            : java.time.Instant.parse(last.etaStartAt).plusSeconds(last.etaSec).toString());
        var os = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("cpus", Runtime.getRuntime().availableProcessors());
        sys.put("loadAvg", os.getSystemLoadAverage());
        sys.put("memTotalMb", os.getTotalMemorySize() / (1024 * 1024));
        sys.put("memFreeMb", os.getFreeMemorySize() / (1024 * 1024));
        sys.put("jvmUsedMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024));
        sys.put("dataFreeMb", freeMb(cfg.dataDir));
        sys.put("cacheFreeMb", freeMb(cfg.cacheDir));
        sys.put("artifactsMb", Util.sizeOf(cfg.artifactsDir()) / (1024 * 1024));
        sys.put("runsMb", Util.sizeOf(cfg.runsDir()) / (1024 * 1024));
        sys.put("strayJvms", StrayJvms.find(cfg.runPrefix()).size());
        sys.put("jdk", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        sys.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
            + System.getProperty("os.arch"));
        m.put("system", sys);

        return m;
    }

    /** */
    private static Long freeMb(Path p) {
        try {
            Path q = p.toAbsolutePath();

            while (q != null && !Files.exists(q))
                q = q.getParent();

            return q == null ? null : Files.getFileStore(q).getUsableSpace() / (1024 * 1024);
        }
        catch (IOException e) {
            return null;
        }
    }

    /** */
    private void serveStatic(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        if (path.equals("/") || path.isEmpty())
            path = "/index.html";

        if (path.contains("..") || !path.matches("/[A-Za-z0-9._/-]+")) {
            Router.send(ex, Response.text(404, "text/plain", "not found"));

            return;
        }

        try (InputStream in = Api.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                Router.send(ex, Response.text(404, "text/plain", "not found"));

                return;
            }

            byte[] body = in.readAllBytes();
            Response r = Response.bytes(contentType(path), body);

            if (path.startsWith("/vendor/"))
                r.headers.put("Cache-Control", "max-age=86400");

            Router.send(ex, r);
        }
    }

    /** */
    private static String contentType(String path) {
        if (path.endsWith(".html"))
            return "text/html";

        if (path.endsWith(".js"))
            return "text/javascript";

        if (path.endsWith(".css"))
            return "text/css";

        if (path.endsWith(".svg"))
            return "image/svg+xml";

        if (path.endsWith(".png"))
            return "image/png";

        if (path.endsWith(".json"))
            return "application/json";

        if (path.endsWith(".woff2"))
            return "font/woff2";

        return "application/octet-stream";
    }
}
