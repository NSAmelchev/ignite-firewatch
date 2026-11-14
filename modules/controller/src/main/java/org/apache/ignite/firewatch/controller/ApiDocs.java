package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JavaType;

/**
 * What every operation of the API does: the text of the OpenAPI document ({@link OpenApi}). An operation is a route of
 * the {@link Router}; a test fails when a route has no description here or a description has no route.
 */
final class ApiDocs {
    /** What an operation does to the stand: {@code x-firewatch-effect}. */
    enum Effect {
        /** */
        READ("Reads only: nothing changes on the stand."),

        /** */
        QUEUE("Queues jobs: they run one at a time on the shared stand, after the jobs of a higher priority."),

        /** */
        CHANGE("Changes the stand, queues no job."),

        /** */
        DESTRUCTIVE("Stops or deletes work: it cannot be undone.");

        /** */
        final String about;

        /** */
        Effect(String about) {
            this.about = about;
        }
    }

    /** A query parameter. */
    static final class Param {
        /** */
        final String name;

        /** */
        final String type;

        /** */
        final String description;

        /** */
        final Object def;

        /** */
        Param(String name, String type, String description, Object def) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.def = def;
        }
    }

    /** A body or an answer: its media type and how its schema is made. */
    static final class Shape {
        /** */
        final String description;

        /** */
        final String media;

        /** */
        private final Function<Map<String, Object>, Map<String, Object>> make;

        /** */
        Shape(String description, String media, Function<Map<String, Object>, Map<String, Object>> make) {
            this.description = description;
            this.media = media;
            this.make = make;
        }

        /**
         * The schema.
         *
         * @param schemas Components: the classes it names are added there.
         * @return Schema.
         */
        Map<String, Object> schema(Map<String, Object> schemas) {
            return make.apply(schemas);
        }
    }

    /** An operation. */
    static final class Op {
        /** "METHOD pattern". */
        final String key;

        /** */
        final String id;

        /** */
        final String tag;

        /** */
        final Effect effect;

        /** */
        final String summary;

        /** */
        String description;

        /** */
        final List<Param> query = new ArrayList<>();

        /** */
        Shape body;

        /** */
        boolean bodyRequired;

        /** */
        Shape ret = any("the answer");

        /** */
        Op(String key, String id, String tag, Effect effect, String summary) {
            this.key = key;
            this.id = id;
            this.tag = tag;
            this.effect = effect;
            this.summary = summary;
        }

        /** */
        Op desc(String d) {
            description = d;

            return this;
        }

        /** */
        Op query(String name, String type, String d, Object def) {
            query.add(new Param(name, type, d, def));

            return this;
        }

        /** */
        Op body(Shape s, boolean required) {
            body = s;
            bodyRequired = required;

            return this;
        }

        /** */
        Op returns(Shape s) {
            ret = s;

            return this;
        }
    }

    /** */
    private static final String JSON = "application/json";

    /** */
    private static final String STRING = "string";

    /** */
    private static final String INTEGER = "integer";

    /** */
    private static final String BOOLEAN = "boolean";

    /** The sections of the API, in the order of the UI. */
    private static final String[][] TAGS = {
        {"Stand", "The state of the stand, its hosts and their checks, the worker"},
        {"Jobs", "The queue and the history: queue, cancel, repeat, the results of a job"},
        {"Commits", "The history of the branch and its commits, refs"},
        {"Dashboard", "The trend of the branch, what needs attention, the noise of the stand, master against the release"},
        {"Bisects", "Automatic bisections of a segment that moved"},
        {"Comparisons", "Two refs over a set of scenarios"},
        {"Plugins", "Ignite together with an extension of another repository: pairs against their release"},
        {"Files", "Logs, results, recordings and builds to download"},
        {"Settings", "Settings changed at run time, scenarios, notifications"},
    };

    /** Descriptions of the fields of the entities, by "Class.property". */
    private static final Map<String, String> FIELDS = Map.ofEntries(
        Map.entry("Job.type", "abba (A1 B1 B2 A2), single (one phase) or probe (measures the load of the stand)"),
        Map.entry("Job.source", "who queued it: auto, manual, calibration (A/A), compare, release (master vs the "
            + "release), bisect, probe, plugin, plugin-compare"),
        Map.entry("Job.status", "queued, running, done, failed, cancelled or interrupted (a restart during the job)"),
        Map.entry("Job.priority", "higher runs first; equal ones by id"),
        Map.entry("Job.scenario", "id of the scenario (of the plugin, when there is one)"),
        Map.entry("Job.shaA", "commit of Ignite of A, the baseline"),
        Map.entry("Job.shaB", "commit of Ignite of B, the target"),
        Map.entry("Job.verdict", "regression, improvement or no-change, from the metrics of the verdict against the "
            + "noise floor"),
        Map.entry("Job.comparison", "the statistics of an ABBA: per metric the means of A and B, Δ %, its CI, the "
            + "threshold and the verdict; the list of jobs carries a summary of it"),
        Map.entry("Job.plugin", "the plugin whose scenario it runs; its sides are pairs of Ignite and the extension"),
        Map.entry("Job.extShaA", "commit of the extension of A (a job of a plugin)"),
        Map.entry("Job.extShaB", "commit of the extension of B (a job of a plugin)"),
        Map.entry("Job.requestedBy", "who queued it (X-Firewatch-User); null for what the stand queued by itself"),
        Map.entry("Job.cancelledBy", "who cancelled it"),
        Map.entry("Job.etaStartAt", "the queue only: when it should start"),
        Map.entry("Job.etaSec", "the queue only: how long it should take, s"),
        Map.entry("Job.runs", "the job only: its phases"),
        Map.entry("Run.phase", "A1, B1, B2, A2, or S of a single job"),
        Map.entry("Run.sha", "the commit of Ignite of the phase"),
        Map.entry("Run.extSha", "the commit of the extension of the phase (a job of a plugin)"),
        Map.entry("Run.throughput", "ops/s over the measure window"),
        Map.entry("Run.noise", "what else ran on the hosts while it measured: its numbers may be off"),
        Map.entry("Bisect.status", "running, stopped, found, spread, inconclusive, lost or cancelled"),
        Map.entry("Build.sha", "the build key: <sha>[+modules][@plugin-<sha of the extension>]"));

    /** The parameters of the paths, by name. */
    private static final Map<String, String> PATH_PARAMS = Map.of(
        "id", "the id",
        "sha", "a sha or a ref of the branch",
        "key", "a build key: <sha>[+modules][@plugin-<sha of the extension>]",
        "name", "the name",
        "scenario", "id of the scenario");

    /** */
    private static Map<String, Op> all;

    /** */
    private ApiDocs() {
    }

    /**
     * The tags of the document.
     *
     * @return {name, description} of each.
     */
    static List<Map<String, Object>> tags() {
        List<Map<String, Object>> out = new ArrayList<>();

        for (String[] t : TAGS)
            out.add(Map.of("name", t[0], "description", t[1]));

        return out;
    }

    /**
     * A path parameter by its name.
     *
     * @param name Name.
     * @return Description.
     */
    static String pathParam(String name) {
        return PATH_PARAMS.getOrDefault(name, name);
    }

    /**
     * The description of a field of an entity.
     *
     * @param c Class.
     * @param prop Property.
     * @return Description, or {@code null}.
     */
    static String field(Class<?> c, String prop) {
        return FIELDS.get(OpenApi.nameOf(c) + "." + prop);
    }

    // ---------------------------------------------------------------- shapes

    /** An entity of the stand. */
    static Shape of(Class<?> c, String d) {
        return new Shape(d, JSON, s -> OpenApi.schemaOf(Json.MAPPER.constructType(c), s));
    }

    /** A list of entities. */
    static Shape listOf(Class<?> c, String d) {
        JavaType t = Json.MAPPER.getTypeFactory().constructCollectionType(List.class, c);

        return new Shape(d, JSON, s -> OpenApi.schemaOf(t, s));
    }

    /** An object made on the spot: its description says what it holds. */
    static Shape any(String d) {
        return new Shape(d, JSON, s -> Map.of("type", "object", "description", d));
    }

    /** A JSON object of the given fields. */
    static Shape obj(String d, Object[]... props) {
        return new Shape(d, JSON, s -> {
            Map<String, Object> p = new LinkedHashMap<>();

            for (Object[] f : props) {
                Map<String, Object> one = new LinkedHashMap<>();

                if (f[1] instanceof Shape)
                    one.putAll(((Shape)f[1]).schema(s));
                else if (((String)f[1]).endsWith("[]"))
                    one.putAll(Map.of("type", "array", "items", Map.of("type", ((String)f[1]).replace("[]", ""))));
                else
                    one.put("type", f[1]);

                one.put("description", f[2]);
                p.put((String)f[0], one);
            }

            return Map.of("type", "object", "properties", p);
        });
    }

    /** A field of {@link #obj}. */
    static Object[] f(String name, Object type, String d) {
        return new Object[] {name, type, d};
    }

    /** A file. */
    static Shape file(String media, String d) {
        return new Shape(d, media, s -> media.startsWith("text/") ? Map.of("type", STRING)
            : Map.of("type", STRING, "contentMediaType", media));
    }

    /** {@code {priority}}. */
    private static Shape priority(String d) {
        return obj("the priority", f("priority", INTEGER, d));
    }

    // ---------------------------------------------------------------- operations

    /**
     * Every operation, by "METHOD pattern".
     *
     * @return Operations.
     */
    static synchronized Map<String, Op> all() {
        if (all != null)
            return all;

        Map<String, Op> m = new LinkedHashMap<>();
        List<Op> list = new ArrayList<>();
        Shape health = any("version, now, uptimeSec; worker: busy, paused (closed for maintenance), the running job; "
            + "watcher: the repository, mirrorReady, the head, auto-run; jobs: counters by status; bisects; plugins; "
            + "queueDrainsAt; memory, disk and load of this machine");
        Shape watch = any("the watcher: branch, repoUrl, mirrorReady, fetch (progress), head, watermark, lastPollAt, "
            + "lastError, autorunEnabled, pollSec, pathFilter");
        Shape pair = obj("refs of a pair", f("ignite", STRING, "a ref of Ignite"),
            f("ext", STRING, "a ref of the extension"));

        // ---- stand
        list.add(new Op("GET /api/health", "getHealth", "Stand", Effect.READ,
            "The state of the stand: worker, watcher, queue, machine").returns(health));
        list.add(new Op("GET /api/stand", "getStand", "Stand", Effect.READ,
            "The stand: where the JVMs run, its sizes, the kept check")
            .desc("mode (local or remote), hosts, sizes of the scenarios, needsSetup, frozen (it has finished runs: "
                + "its hosts are fixed), check (the last report), load (the last measured load)")
            .returns(any("the stand")));
        list.add(new Op("PUT /api/stand", "saveStand", "Stand", Effect.CHANGE, "Saves the stand")
            .body(of(Stand.class, "the stand"), true).returns(any("the stand as GET /api/stand says it")));
        list.add(new Op("POST /api/stand/check", "checkStand", "Stand", Effect.CHANGE, "Checks the hosts of a stand")
            .desc("Every host on its own and against the others; it takes the ports of Ignite, so it runs when the queue "
                + "is empty or the stand is closed for maintenance. The report is kept.")
            .body(of(Stand.class, "the stand to check"), true).returns(any("the report: ok, and per host its items "
                + "(name, level ok|warn|fail, text, fix), facts and fingerprint")));
        list.add(new Op("GET /api/stand/check/log", "getStandCheckLog", "Stand", Effect.READ,
            "The log of the last check of the hosts").returns(any("running, lines")));
        list.add(new Op("GET /api/app/check", "getBuildCheck", "Stand", Effect.READ,
            "The last check of what builds the commits").returns(any("the report, or null: items, log, ok, at")));
        list.add(new Op("POST /api/app/check", "checkBuilds", "Stand", Effect.CHANGE,
            "Checks what builds the commits: the repository, Java, Maven, git, the disk")
            .body(obj("what to check", f("url", STRING, "repository (default: the one of the stand)"),
                f("branch", STRING, "branch (default: the one of the stand)")), false)
            .returns(any("the report: items, log, ok, at")));
        list.add(new Op("POST /api/stand/measure", "measureLoad", "Stand", Effect.QUEUE,
            "Measures the load of the stand: the main scenario with more and more threads")
            .returns(of(Job.class, "the queued job")));
        list.add(new Op("POST /api/stand/sweep", "cleanHosts", "Stand", Effect.CHANGE,
            "Takes the JVMs and directories of killed phases off the hosts").returns(obj("done", f("ok", BOOLEAN, ""))));
        list.add(new Op("POST /api/kill-all", "killAll", "Stand", Effect.DESTRUCTIVE,
            "Kills every benchmark JVM of this stand on this machine").returns(obj("done",
                f("killed", INTEGER, "how many"))));
        list.add(new Op("POST /api/worker/pause", "closeForMaintenance", "Stand", Effect.CHANGE,
            "Closes the stand for maintenance: the running job ends, queued ones wait").returns(health));
        list.add(new Op("POST /api/worker/resume", "reopenStand", "Stand", Effect.CHANGE,
            "Opens the stand again: the queue runs").returns(health));
        list.add(new Op("GET /api/storage", "getStorage", "Stand", Effect.READ,
            "What the runs, their heavy files, comparisons and bisections take, bytes").returns(any("bytes by kind")));
        list.add(new Op("GET /api/log", "getLog", "Stand", Effect.READ, "The last lines of the log of the controller")
            .query("lines", INTEGER, "how many", 100).returns(listOf(Log.Entry.class, "lines, oldest first")));
        list.add(new Op("GET /api/openapi.json", "getOpenApi", "Stand", Effect.READ, "This document")
            .returns(any("OpenAPI 3.1")));

        // ---- jobs
        list.add(new Op("GET /api/jobs", "listJobs", "Jobs", Effect.READ, "Jobs of the queue or of the history")
            .desc("active: the running job, then the queued ones in run order, with their ETA. history: finished ones, "
                + "newest first; history-no-steps leaves out the steps of bisections. all: every job, newest first.")
            .query("filter", STRING, "active, history, history-no-steps or all", "all")
            .query("limit", INTEGER, "how many", 50).returns(listOf(Job.class, "jobs")));
        list.add(new Op("POST /api/jobs", "queueJob", "Jobs", Effect.QUEUE, "Queues a job of two refs or of one")
            .body(obj("the job", f("type", STRING, "abba (default) or single"),
                f("refA", STRING, "A, the baseline: a sha, a branch, a tag or pr/N"),
                f("refB", STRING, "B, the target (abba)"),
                f("scenario", STRING, "id of the scenario (default: the first of auto-run)"),
                f("priority", INTEGER, "default 1, a manual job"), f("label", STRING, "a title")), true)
            .returns(of(Job.class, "the queued job")));
        list.add(new Op("GET /api/jobs/{id}", "getJob", "Jobs", Effect.READ,
            "A job with its phases and its comparison").returns(of(Job.class, "the job")));
        list.add(new Op("POST /api/jobs/{id}/cancel", "cancelJob", "Jobs", Effect.DESTRUCTIVE,
            "Cancels a queued job, stops a running one").returns(of(Job.class, "the job")));
        list.add(new Op("POST /api/jobs/{id}/retry", "retryJob", "Jobs", Effect.QUEUE,
            "Queues the same job again: its result is pooled with the earlier runs of the pair")
            .body(priority("default: that of the job, at least 1"), false).returns(of(Job.class, "the new job")));
        list.add(new Op("POST /api/jobs/{id}/split", "splitSegment", "Jobs", Effect.QUEUE,
            "Splits a segment of several commits")
            .body(obj("how", f("mode", STRING, "halves (A..middle and middle..B, default) or commits (every commit "
                + "against its parent)"), f("priority", INTEGER, "default 1")), false)
            .returns(listOf(Job.class, "the queued jobs")));
        list.add(new Op("DELETE /api/jobs/{id}", "deleteJob", "Jobs", Effect.DESTRUCTIVE,
            "Deletes a finished job with its files").returns(obj("done", f("deleted", INTEGER, "the id"))));
        list.add(new Op("POST /api/jobs/{id}/priority", "setJobPriority", "Jobs", Effect.CHANGE,
            "Moves a queued job in the queue").body(priority("the new priority"), true)
            .returns(of(Job.class, "the job")));
        list.add(new Op("GET /api/jobs/{id}/log", "getJobLog", "Jobs", Effect.READ, "The tail of the log of a job")
            .query("lines", INTEGER, "how many", 80).returns(any("jobId, stage, file, text")));
        list.add(new Op("GET /api/jobs/{id}/series", "getJobSeries", "Jobs", Effect.READ,
            "The per-second series of every phase").returns(any("jobId, type, runs: id, phase, sha, status, series "
                + "(t, ops, p99Us, … per second; measureStartIndex)")));
        list.add(new Op("GET /api/jobs/{id}/profile", "getJobProfile", "Jobs", Effect.READ,
            "The hot methods of A against B, from JFR").query("limit", INTEGER, "how many methods", 40)
            .returns(any("jobId, available, diff")));
        list.add(new Op("GET /api/estimate", "estimateLaunch", "Jobs", Effect.READ,
            "How long jobs to queue would take and when they would end")
            .query("scenario", STRING, "id, or several comma-separated", null)
            .query("type", STRING, "abba or single", "abba").query("jobs", INTEGER, "how many jobs", 1)
            .query("shas", STRING, "commits to build, comma-separated", "")
            .query("extraBuilds", INTEGER, "builds of refs not resolved yet", 0)
            .query("priority", INTEGER, "the priority they would have", 1)
            .returns(any("jobs, runSec, builds, buildSec, totalSec, ahead, startAt, endAt")));

        // ---- commits
        list.add(new Op("GET /api/commits", "listCommits", "Commits", Effect.READ,
            "The first-parent history of the branch with the jobs of each commit")
            .query("limit", INTEGER, "how many", 100).query("scenario", STRING, "only its jobs", null)
            .returns(listOf(CommitInfo.class, "commits, newest first")));
        list.add(new Op("GET /api/commits/{sha}", "getCommit", "Commits", Effect.READ,
            "A commit with every job that measured it and its pooled results").returns(any("the commit, its jobs "
                + "(role: target, baseline or A/A) and the pooled results per pair")));
        list.add(new Op("GET /api/search", "search", "Commits", Effect.READ,
            "Finds a sha, a ticket (IGNITE-123), a PR (#123), a job or a bisection by its number, or words of a subject")
            .query("q", STRING, "what to find", "").query("limit", INTEGER, "how many", 10)
            .returns(listOf(JobService.Hit.class, "hits")));
        list.add(new Op("POST /api/resolve", "resolveRef", "Commits", Effect.READ,
            "Resolves a ref: a sha, a branch, a tag, pr/N")
            .body(obj("the ref", f("ref", STRING, "the ref")), true)
            .returns(of(CommitInfo.class, "the commit (only its sha when it is not in the mirror's history)")));
        list.add(new Op("POST /api/watch/poll", "pollRepository", "Commits", Effect.CHANGE,
            "Fetches the repository now and queues what auto-run asks").returns(watch));
        list.add(new Op("POST /api/watch/pause", "pauseAutoRun", "Commits", Effect.CHANGE,
            "Stops queuing new commits by itself").returns(watch));
        list.add(new Op("POST /api/watch/resume", "resumeAutoRun", "Commits", Effect.CHANGE,
            "Queues new commits by itself again").returns(watch));

        // ---- dashboard
        list.add(new Op("GET /api/trend", "getTrend", "Dashboard", Effect.READ,
            "The trend of a scenario over the branch: a point per measured commit or segment")
            .query("scenario", STRING, "id (default: the first of auto-run)", null)
            .query("limit", INTEGER, "how many commits", 60)
            .query("refined", BOOLEAN, "also the segments a split replaced", false)
            .returns(listOf(JobService.TrendPoint.class, "points, oldest first")));
        list.add(new Op("GET /api/attention", "getAttention", "Dashboard", Effect.READ,
            "What needs attention: master against the release per scenario and what was found on the branch")
            .returns(any("rows per scenario with the metrics of master vs the release, their status and the commits "
                + "found; runs newer than it")));
        list.add(new Op("GET /api/noise", "getNoise", "Dashboard", Effect.READ,
            "The noise of the stand for a scenario: its A/A runs and the floors they give")
            .query("scenario", STRING, "id (default: the first of auto-run)", null)
            .returns(of(JobService.Noise.class, "the noise")));
        list.add(new Op("POST /api/calibrate", "queueCalibration", "Dashboard", Effect.QUEUE,
            "Queues an A/A run: a commit against itself, its Δ is the noise of the stand")
            .body(obj("the run", f("ref", STRING, "default: the head of the branch"),
                f("scenario", STRING, "default: the first of auto-run"), f("priority", INTEGER, "default 0")), false)
            .returns(of(Job.class, "the queued job")));
        list.add(new Op("GET /api/release", "getRelease", "Dashboard", Effect.READ,
            "Master against the release: the tag, the unreleased commits, the last run and how the commits are covered")
            .query("scenario", STRING, "id (default: the first of auto-run)", null)
            .returns(any("tag, commits since the release, the last master vs release job, coverage")));
        list.add(new Op("POST /api/release", "queueRelease", "Dashboard", Effect.QUEUE,
            "Queues master against the release").body(obj("the run",
                f("scenario", STRING, "default: the first of auto-run"), f("priority", INTEGER, "default 1")), false)
            .returns(of(Job.class, "the queued job")));

        // ---- bisects
        list.add(new Op("GET /api/bisects", "listBisects", "Bisects", Effect.READ, "Bisections, newest first")
            .query("scenario", STRING, "only of it", null).query("active", BOOLEAN, "only running ones", false)
            .query("limit", INTEGER, "how many", 20).returns(listOf(Bisect.class, "bisections")));
        list.add(new Op("GET /api/bisects/{id}", "getBisect", "Bisects", Effect.READ, "A bisection with its steps")
            .returns(of(Bisect.class, "the bisection")));
        list.add(new Op("POST /api/bisects", "startBisect", "Bisects", Effect.QUEUE,
            "Starts a bisection of a finished segment that moved a metric: one half per step, queued by itself")
            .body(obj("what to bisect", f("jobId", INTEGER, "the finished ABBA of the segment"),
                f("metric", STRING, "throughput (default), p99Us, serverCpuUsPerOp, allocKbPerOp, heapAfterGcMb"),
                f("priority", INTEGER, "of its steps")), true).returns(of(Bisect.class, "the bisection")));
        list.add(new Op("POST /api/bisects/{id}/cancel", "cancelBisect", "Bisects", Effect.DESTRUCTIVE,
            "Stops a bisection").body(obj("how", f("stopRunning", BOOLEAN, "stop its running step (default true)"),
                f("deleteFinished", BOOLEAN, "delete its finished steps (default false)")), false)
            .returns(of(Bisect.class, "the bisection")));
        list.add(new Op("POST /api/bisects/{id}/resume", "resumeBisect", "Bisects", Effect.QUEUE,
            "Resumes a stopped bisection").returns(of(Bisect.class, "the bisection")));
        list.add(new Op("POST /api/bisects/{id}/deep", "deepBisect", "Bisects", Effect.QUEUE,
            "Searches deeper: repeats what was too close to the noise").body(priority("of its steps"), false)
            .returns(of(Bisect.class, "the bisection")));
        list.add(new Op("DELETE /api/bisects/{id}", "deleteBisect", "Bisects", Effect.DESTRUCTIVE,
            "Deletes a finished bisection").returns(obj("done", f("deleted", BOOLEAN, ""))));

        // ---- comparisons
        list.add(new Op("GET /api/comparisons", "listComparisons", "Comparisons", Effect.READ,
            "Comparisons, newest first").query("limit", INTEGER, "how many", 20)
            .returns(listOf(Comparison.class, "comparisons")));
        list.add(new Op("GET /api/comparisons/{id}", "getComparison", "Comparisons", Effect.READ,
            "A comparison with its jobs").returns(of(Comparison.class, "the comparison")));
        list.add(new Op("POST /api/comparisons", "createComparison", "Comparisons", Effect.QUEUE,
            "Compares two refs over scenarios: an ABBA per scenario")
            .body(obj("what to compare", f("base", STRING, "A: a sha, a branch, a tag or pr/N"),
                f("target", STRING, "B"), f("scenarios", "string[]", "ids"), f("priority", INTEGER, "default 2")), true)
            .returns(of(Comparison.class, "the comparison")));
        list.add(new Op("POST /api/comparisons/{id}/cancel", "cancelComparison", "Comparisons", Effect.DESTRUCTIVE,
            "Cancels the jobs of a comparison").returns(of(Comparison.class, "the comparison")));
        list.add(new Op("DELETE /api/comparisons/{id}", "deleteComparison", "Comparisons", Effect.DESTRUCTIVE,
            "Deletes a comparison and its jobs").returns(obj("done", f("deleted", BOOLEAN, ""))));

        // ---- plugins
        list.add(new Op("GET /api/plugins", "listPlugins", "Plugins", Effect.READ,
            "The plugins that are on, and the jars that could not be read")
            .returns(any("plugins: name, title, module, repo, branch, settings (release, ignite, mode, keepDays), "
                + "mirror, pairs (release and master: igniteRef, igniteSha, extRef, extSha) or pairsError, scenarios; "
                + "errors")));
        list.add(new Op("GET /api/plugins/{name}", "getPlugin", "Plugins", Effect.READ,
            "A plugin: per scenario its last result against the release and its history")
            .returns(any("what GET /api/plugins says of it, and rows: scenario, active, last, failed, history (job, "
                + "igniteMoved, extMoved); compares")));
        list.add(new Op("GET /api/plugins/{name}/scenarios/{scenario}", "describePluginScenario", "Plugins",
            Effect.READ, "A scenario of a plugin as the harness lists it").returns(any("the scenario with its sources")));
        list.add(new Op("POST /api/plugins/{name}/run", "runPlugin", "Plugins", Effect.QUEUE,
            "Queues the release pair against the master pair now")
            .body(obj("what to run", f("scenarios", "string[]", "ids (default: all of the plugin)"),
                f("priority", INTEGER, "default 1")), false).returns(listOf(Job.class, "the queued jobs")));
        list.add(new Op("POST /api/plugins/{name}/compare", "comparePluginPairs", "Plugins", Effect.QUEUE,
            "Compares two pairs of Ignite and the extension")
            .desc("With split the change goes one side at a time: Ignite first (the extension of A on both sides), "
                + "then the extension (the Ignite of B on both sides).")
            .body(obj("the pairs", f("a", pair, "A"), f("b", pair, "B"),
                f("split", BOOLEAN, "one side at a time"), f("scenarios", "string[]", "ids (default: all)"),
                f("priority", INTEGER, "default 1")), true).returns(listOf(Job.class, "the queued jobs")));

        // ---- files
        list.add(new Op("GET /api/jobs/{id}/downloads", "getJobDownloads", "Files", Effect.READ,
            "What a job has to download and how big it is")
            .returns(any("zip: bytes, jfrBytes, files, phases; builds: per side its log and jars")));
        list.add(new Op("GET /api/jobs/{id}/zip", "downloadJob", "Files", Effect.READ,
            "The files of a job in a zip").query("jfr", STRING, "1: with the JFR recordings", "0")
            .returns(file("application/zip", "the zip")));
        list.add(new Op("GET /api/builds", "listBuilds", "Files", Effect.READ, "Builds, newest first")
            .query("sha", STRING, "the builds of one commit", null).query("limit", INTEGER, "how many", 50)
            .returns(listOf(Build.class, "builds with label, logBytes and jarsBytes")));
        list.add(new Op("GET /api/builds/{key}/log", "getBuildLog", "Files", Effect.READ, "The log of a build")
            .returns(file("text/plain", "the log")));
        list.add(new Op("GET /api/builds/{key}/jars", "downloadBuildJars", "Files", Effect.READ,
            "The jars of a build in a zip").returns(file("application/zip", "the zip")));
        list.add(new Op("GET /api/runs/{id}/result", "getRunResult", "Files", Effect.READ,
            "result.json of a phase").returns(any("what the harness wrote: throughput, latency, gc, cpu, heap, noise")));
        list.add(new Op("GET /api/runs/{id}/series", "getRunSeries", "Files", Effect.READ,
            "series.json of a phase").returns(any("per second: t, ops, p99Us, …; measureStartIndex")));
        list.add(new Op("GET /api/runs/{id}/files", "listRunFiles", "Files", Effect.READ, "The files of a phase")
            .returns(any("a list of name and size")));
        list.add(new Op("GET /api/runs/{id}/files/{name}", "getRunFile", "Files", Effect.READ,
            "A file of a phase: a log, a JSON, a JFR recording").returns(file("application/octet-stream",
                "the file (text/plain for a log, application/json for a JSON)")));

        // ---- settings
        list.add(new Op("GET /api/settings", "getSettings", "Settings", Effect.READ,
            "The settings changed at run time; secrets masked").returns(any("key: value")));
        list.add(new Op("PUT /api/settings", "saveSettings", "Settings", Effect.CHANGE, "Changes settings")
            .desc("Only the keys given change. Among them: autorun.enabled, autorun.mode, autorun.scenario, "
                + "autorun.pathFilter, release.tag, calibration.* and master.* (mode, keepDays, scenarios), "
                + "retention.*, notify.*, plugin.<name>.* (release, ignite, mode, keepDays, repo, branch).")
            .body(new Shape("key: value", JSON, s -> Map.of("type", "object", "additionalProperties",
                Map.of("type", STRING))), true).returns(any("every setting, secrets masked")));
        list.add(new Op("GET /api/scenarios", "listScenarios", "Settings", Effect.READ, "The scenarios of the harness")
            .returns(listOf(Scenarios.Info.class, "scenarios")));
        list.add(new Op("GET /api/scenarios/{name}/describe", "describeScenario", "Settings", Effect.READ,
            "A scenario as the harness lists it, with its sources").returns(any("id, version, topology, timings, "
                + "sources")));
        list.add(new Op("POST /api/notify/test", "testNotification", "Settings", Effect.CHANGE,
            "Sends a test message to the notification channels").returns(any("attempted, lastResult")));
        list.add(new Op("GET /api/notify/status", "getNotifyStatus", "Settings", Effect.READ,
            "How the last notification went").returns(any("lastResult")));

        for (Op op : list) {
            if (m.put(op.key, op) != null)
                throw new IllegalStateException("described twice: " + op.key);
        }

        all = m;

        return all;
    }
}
