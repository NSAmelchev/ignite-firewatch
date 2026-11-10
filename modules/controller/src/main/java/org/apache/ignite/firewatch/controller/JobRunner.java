package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The single worker: takes queued jobs by priority, builds the commits, runs the harness phases one after another
 * (never two measurements at once, never a build during a measurement), computes the comparison and stores it.
 */
public final class JobRunner implements Runnable {
    /** Measure load: this many times the threads of the scenario, until the throughput stops growing noticeably. */
    private static final List<Integer> PROBE_FACTORS = List.of(1, 2, 4);

    /** */
    private static final List<String> PROBE_PHASES = List.of("x1", "x2", "x4");

    /** */
    private static final double PROBE_MIN_GROWTH = 1.1;

    /** */
    private static final int PROBE_WARMUP_SEC = 15;

    /** */
    private static final int PROBE_MEASURE_SEC = 45;

    /** A remote phase also copies the jars of a new build to its hosts. */
    private static final int REMOTE_GRACE_SEC = 120;

    /** */
    private final Config cfg;

    /** */
    private final Db db;

    /** */
    private final Builder builder;

    /** */
    private final Scenarios scenarios;

    /** */
    private final JobService jobs;

    /** */
    private final GitMirror mirror;

    /** */
    private final StandService stands;

    /** */
    private volatile Notifier notifier;

    /** */
    private final Object lock = new Object();

    /** */
    private final Thread thread = new Thread(this, "job-runner");

    /** */
    private volatile boolean running = true;

    /** */
    private volatile Job current;

    /** */
    private volatile Proc.Handle currentProc;

    /**
     * Creates the job runner.
     *
     * @param cfg Controller config.
     * @param db Database.
     * @param builder Builder.
     * @param scenarios Known scenarios.
     * @param jobs Job service.
     * @param mirror Git mirror.
     */
    public JobRunner(Config cfg, Db db, Builder builder, Scenarios scenarios, JobService jobs, GitMirror mirror) {
        this.cfg = cfg;
        this.db = db;
        this.builder = builder;
        this.scenarios = scenarios;
        this.jobs = jobs;
        this.mirror = mirror;
        this.stands = new StandService(cfg, db, scenarios);
        jobs.onEnqueue(this::wake);
    }

    /** Cleans up after a previous controller instance, then starts the worker thread. */
    public void start() {
        recover();
        thread.setDaemon(false);
        thread.start();
    }

    /** */
    void recover() {
        List<Long> interrupted = db.markInterrupted();

        if (!interrupted.isEmpty())
            Log.warn("jobs interrupted by the restart: " + interrupted);

        int runs = db.failRunningRuns();

        if (runs > 0)
            Log.warn(runs + " phase(s) marked failed after the restart");

        int builds = db.failInterruptedBuilds();

        if (builds > 0)
            Log.warn(builds + " build(s) marked failed after the restart");

        StrayJvms.killAll(cfg.runPrefix());
    }

    /** */
    public void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * Sets the notifier.
     *
     * @param n Notifier.
     */
    public void notifier(Notifier n) {
        notifier = n;
    }

    /** */
    private void notifyFinished(Job job) {
        Notifier n = notifier;

        if (n == null)
            return;

        try {
            n.jobFinished(job);
        }
        catch (RuntimeException e) {
            Log.warn("notify failed: " + e);
        }
    }

    /** Stops the worker; the current job (if any) is interrupted and its processes killed. */
    public void shutdown() {
        running = false;
        wake();
        Proc.Handle h = currentProc;

        if (h != null)
            h.killTree(Duration.ofSeconds(10));

        thread.interrupt();

        try {
            thread.join(15_000);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the running job.
     *
     * @return Running job, or {@code null} if there is none.
     */
    public Job current() {
        return current;
    }

    /**
     * Returns the stand service.
     *
     * @return Stand service.
     */
    public StandService stands() {
        return stands;
    }

    /**
     * Checks whether a job is running now.
     *
     * @return {@code True} if a job is running.
     */
    public boolean busy() {
        return current != null;
    }

    /**
     * Maintenance mode: no new job is taken; the current one runs to the end.
     *
     * @return {@code True} if the maintenance mode is on.
     */
    public boolean paused() {
        return Config.isTrue(db.setting(Settings.WORKER_PAUSED, "false"));
    }

    /**
     * Turns the maintenance mode on or off.
     *
     * @param paused {@code True} to stop taking new jobs.
     */
    public void setPaused(boolean paused) {
        db.setSetting(Settings.WORKER_PAUSED, String.valueOf(paused));
        Log.info(paused ? "worker: closed for maintenance (finishing the current job, taking no new ones)"
            : "worker: resumed");

        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * On this thread: a host that does not answer holds the queue, not the start. Check or Clean hosts may be first,
     * the next phase sweeps anyway.
     */
    private void sweepAfterRestart() {
        if (!stands.tryHold())
            return;

        try {
            stands.sweep();
        }
        finally {
            stands.release();
        }
    }

    /** {@inheritDoc} */
    @Override public void run() {
        sweepAfterRestart();

        while (running) {
            Job job = null;

            try {
                if (!paused())
                    job = db.nextQueued();

                if (job != null && !stands.tryHold())
                    job = null; // Check has the hosts for now
            }
            catch (RuntimeException e) {
                Log.error("cannot read the queue", e);
            }

            if (job == null) {
                synchronized (lock) {
                    try {
                        lock.wait(5000);
                    }
                    catch (InterruptedException e) {
                        if (!running)
                            return;
                    }
                }

                continue;
            }

            try {
                runJob(job);
            }
            finally {
                stands.release();
            }
        }
    }

    /** */
    void runJob(Job job) {
        current = job;
        job.status = Job.RUNNING;
        job.startedAt = Db.now();
        job.stage = "starting";
        job.progress = 0;
        db.updateJob(job);
        Log.info("job #" + job.id + " started: " + job.label + " [" + job.scenario + "]");
        BooleanSupplier cancelled = () -> !running || db.cancelRequested(job.id);
        String runIdPrefix = cfg.runPrefix() + "job" + job.id + "-";

        try {
            Stand stand = Stand.read(db);
            String unchecked = Stand.REMOTE.equals(stand.mode) ? stand.unchecked(db) : null;

            if (unchecked != null)
                throw new JobFailed("host " + unchecked + " has not passed Check: run it in Settings, section Stand");

            Scenarios.Info sc = scenarios.get(job.scenario);

            if (sc == null)
                throw new JobFailed("scenario " + job.scenario + " no longer exists");

            List<String> shas = new ArrayList<>();
            shas.add(job.shaA);

            if (job.isAbba() && !job.shaB.equals(job.shaA))
                shas.add(job.shaB);

            List<String> phases = job.isAbba() ? List.of("A1", "B1", "B2", "A2") : job.isProbe() ? PROBE_PHASES
                : List.of("S");
            int totalUnits = shas.size() + phases.size() * 3;
            int doneUnits = 0;

            Map<String, Path> libs = new LinkedHashMap<>();
            evictArtifacts(shas);

            for (String sha : shas) {
                checkCancelled(cancelled);
                String key = Builder.key(sha, sc.modules); // SQL scenarios: a build of the commit with their modules
                boolean cached = builder.cached(key);
                stage(job, (cached ? "build (cached) " : "build ") + Builder.label(key), doneUnits * 100 / totalUnits,
                    cached ? null : builder.libsDir(key).resolveSibling("build.log"));
                boolean automatic = JobService.SRC_AUTO.equals(job.source)
                    || JobService.SRC_CALIBRATION.equals(job.source)
                    || JobService.SRC_RELEASE.equals(job.source);
                Build b = builder.build(key, cancelled, automatic);

                if (!Build.OK.equals(b.status))
                    throw new JobFailed("build of " + Builder.label(key) + " failed: " + b.error);

                libs.put(sha, Path.of(b.libsDir));
                doneUnits++;
            }

            List<PhaseData> data = new ArrayList<>();
            List<Run> probes = new ArrayList<>();

            for (int i = 0; i < phases.size(); i++) {
                checkCancelled(cancelled);
                String phase = phases.get(i);
                String sha = phase.startsWith("B") ? job.shaB : job.shaA;
                Run run = new Run();
                run.jobId = job.id;
                run.phase = phase;
                run.sha = sha;
                run.seq = i;
                run.status = Run.RUNNING;
                run.startedAt = Db.now();
                run.dir = "runs/" + job.id + "/" + phase;
                db.insertRun(run);
                stage(job, "phase " + phase + " (" + Job.shortSha(sha) + ")", doneUnits * 100 / totalUnits,
                    cfg.dataDir.resolve(run.dir).resolve("harness.log"));
                double threads = stand.sizes.threadsFactor * (job.isProbe() ? PROBE_FACTORS.get(i) : 1);
                PhaseData d = runPhase(job, run, libs.get(sha), sc, cancelled, runIdPrefix + phase, stand, threads);
                data.add(d);

                if (!Run.OK.equals(run.status))
                    throw new JobFailed("phase " + phase + " failed: " + run.error);

                doneUnits += 3;

                if (job.isProbe()) {
                    probes.add(run);

                    if (!stillGrowing(probes))
                        break;
                }
            }

            if (job.isProbe())
                keepLoad(job, stand, probes);

            if (job.isAbba()) {
                List<Stats.Comparison> cal = calibrations(job);
                boolean own = cal.size() >= JobService.MIN_CALIBRATIONS;
                Map<String, Double> noise = own ? Stats.noiseFloors(cal) : Stats.defaultNoiseFloors();
                Stats.Comparison cmp = Stats.compare(data, noise, own ? "calibration" : "default", cal.size());
                job.comparison = Json.MAPPER.valueToTree(cmp);
                job.verdict = cmp.verdict;
            }

            job.status = Job.DONE;
            job.stage = "done";
            job.progress = 100;
            job.finishedAt = Db.now();
            db.updateJob(job);
            Log.info("job #" + job.id + " done" + (job.verdict == null ? "" : ": " + job.verdict + " " + summary(job)));
            notifyFinished(job);
        }
        catch (CancelledException e) {
            finish(job, running ? Job.CANCELLED : Job.INTERRUPTED, e.getMessage());
        }
        catch (JobFailed e) {
            finish(job, Job.FAILED, e.getMessage());
        }
        catch (Throwable t) {
            Log.error("job #" + job.id + " crashed", t);
            finish(job, Job.FAILED, t.toString());
        }
        finally {
            currentProc = null;
            current = null;
            StrayJvms.killAll(runIdPrefix);
            Job ended = db.job(job.id);

            if (!Job.DONE.equals(ended.status))
                stands.sweep(); // a killed phase may have left JVMs on the hosts

            jobs.jobEnded(ended); // a bisection queues its next step

            try {
                Retention.pruneHeavyFiles(db, cfg.dataDir,
                    Integer.parseInt(db.setting(Settings.RETENTION_KEEP_JFR_JOBS, "30")));
            }
            catch (RuntimeException e) {
                Log.warn("retention failed: " + e);
            }
        }
    }

    /**
     * Whether the last probe gave noticeably more than the one before it; the next one is not worth a minute otherwise.
     */
    private static boolean stillGrowing(List<Run> probes) {
        if (probes.size() < 2)
            return true;

        return probes.get(probes.size() - 1).throughput >= probes.get(probes.size() - 2).throughput * PROBE_MIN_GROWTH;
    }

    /** */
    private void keepLoad(Job job, Stand stand, List<Run> probes) {
        List<Map<String, Object>> steps = new ArrayList<>();
        int best = 0;

        for (int i = 0; i < probes.size(); i++) {
            Run r = probes.get(i);

            if (i == 0 || r.throughput >= probes.get(i - 1).throughput * PROBE_MIN_GROWTH)
                best = i;

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("threadsFactor", stand.sizes.threadsFactor * PROBE_FACTORS.get(i));
            s.put("throughput", r.throughput);
            s.put("p99Us", r.p99Us);
            steps.add(s);
        }

        Map<String, Object> load = new LinkedHashMap<>();
        load.put("jobId", job.id);
        load.put("at", Db.now());
        load.put("steps", steps);
        load.put("threadsFactor", stand.sizes.threadsFactor * PROBE_FACTORS.get(best));
        db.setSetting(Stand.LOAD, Json.write(load));
    }

    /** */
    private void finish(Job job, String status, String error) {
        job.status = status;
        job.error = error;
        job.finishedAt = Db.now();
        db.updateJob(job);
        Log.info("job #" + job.id + " " + status + (error == null ? "" : ": " + error));

        if (Job.FAILED.equals(status))
            notifyFinished(job);
    }

    /** */
    private void stage(Job job, String stage, int progress, Path logFile) {
        job.stage = stage;
        job.progress = progress;
        job.logFile = logFile == null ? null : logFile.toString();
        db.updateJob(job);
    }

    /** */
    private PhaseData runPhase(Job job, Run run, Path libs, Scenarios.Info sc, BooleanSupplier cancelled, String runId,
        Stand stand, double threads) throws IOException, InterruptedException {
        Path dir = cfg.dataDir.resolve(run.dir);
        Files.createDirectories(dir);
        List<String> cmd = new ArrayList<>(cfg.harness(cfg.harnessHeapMb));
        cmd.addAll(List.of("run", "--libs", libs.toAbsolutePath().toString(),
            "--scenario", job.scenario,
            "--out", dir.toAbsolutePath().toString(), "--run-id", runId, "--phase", run.phase));
        boolean remote = Stand.REMOTE.equals(stand.mode);

        if (remote) {
            Path file = dir.resolve("stand.json");
            stands.write(file, stand);
            cmd.addAll(List.of("--stand", file.toAbsolutePath().toString()));
        }

        String scale = stand.sizes.scale(threads);

        if (scale != null)
            cmd.addAll(List.of("--scale", scale));

        if (job.isProbe())
            cmd.addAll(List.of("--warmup-sec", String.valueOf(PROBE_WARMUP_SEC), "--measure-sec",
                String.valueOf(PROBE_MEASURE_SEC)));

        Path log = dir.resolve("harness.log");
        Files.writeString(log, String.join(" ", cmd) + "\n");
        Duration timeout = Scenarios.phaseTimeout(sc, cfg.phaseGraceSec + (remote ? REMOTE_GRACE_SEC : 0))
            .plusSeconds((stand.sizes.setupTimeoutFactor() - 1L) * sc.setupTimeoutSec);
        Proc.Handle h = Proc.start(cmd, null, cfg.harnessEnv, log);
        currentProc = h;
        Integer rc;

        try {
            rc = h.waitFor(timeout, cancelled);
        }
        finally {
            currentProc = null;
        }

        if (rc == null) {
            h.killTree(Duration.ofSeconds(10));
            StrayJvms.killAll(runId);

            if (cancelled.getAsBoolean()) {
                run.status = Run.FAILED;
                run.error = "cancelled";
                run.finishedAt = Db.now();
                db.updateRun(run);

                throw new CancelledException("cancelled during phase " + run.phase);
            }

            run.status = Run.FAILED;
            run.error = "phase did not finish within " + timeout.toSeconds() + " s";
            run.finishedAt = Db.now();
            db.updateRun(run);
            PhaseData d = new PhaseData();
            d.phase = run.phase;
            d.sha = run.sha;
            d.status = "failed";
            d.error = run.error;

            return d;
        }

        if (cancelled.getAsBoolean()) {
            // The process was killed by shutdown() or exited just as the cancel arrived: not a failure of the phase.
            StrayJvms.killAll(runId);
            run.status = Run.FAILED;
            run.error = running ? "cancelled" : "controller stopped";
            run.finishedAt = Db.now();
            db.updateRun(run);

            throw new CancelledException((running ? "cancelled" : "controller stopped") + " during phase " + run.phase);
        }

        PhaseData d = PhaseData.read(dir, run.phase, run.sha);
        d.fill(run);
        run.finishedAt = Db.now();

        if (rc == 0 && d.ok()) {
            run.status = Run.OK;

            if (job.scenarioHash == null && d.scenarioHash != null)
                job.scenarioHash = d.scenarioHash;

            Retention.dropWorkDir(dir);
        }
        else {
            run.status = Run.FAILED;
            run.error = d.error != null ? d.error : "harness exited with code " + rc + ": " + lastLine(log);
        }

        db.updateRun(run);

        return d;
    }

    /** */
    private List<Stats.Comparison> calibrations(Job job) {
        List<Stats.Comparison> out = new ArrayList<>();

        for (Job c : jobs.calibrationsOfCurrentVersion(job.scenario)) {
            if (c.comparison == null)
                continue;

            try {
                out.add(Json.MAPPER.treeToValue(c.comparison, Stats.Comparison.class));
            }
            catch (Exception e) {
                Log.warn("cannot read comparison of job #" + c.id + ": " + e);
            }
        }

        return out;
    }

    /** */
    private void evictArtifacts(List<String> needed) {
        try {
            long gb = Long.parseLong(db.setting(Settings.RETENTION_ARTIFACTS_GB, "3"));
            Set<String> keep = jobs.shasInUse();
            keep.addAll(needed);
            builder.evict(gb * 1024L * 1024 * 1024, keep);
        }
        catch (RuntimeException e) {
            Log.warn("artifact eviction failed: " + e);
        }
    }

    /** */
    private static String summary(Job job) {
        if (job.comparison == null)
            return "";

        StringBuilder sb = new StringBuilder();

        for (String k : Stats.VERDICT_METRICS) {
            var r = job.comparison.path("metrics").path(k);

            if (r.hasNonNull("deltaPct"))
                sb.append(k).append(String.format("=%+.1f%% ", r.get("deltaPct").asDouble()));
        }

        return sb.toString().trim();
    }

    /** */
    private static String lastLine(Path log) {
        String t = Proc.tailFile(log, 1).trim();

        return t.length() > 240 ? t.substring(0, 240) + "..." : t;
    }

    /** */
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean())
            throw new CancelledException("cancelled");
    }

    /** Failure of a job phase with a message to show. */
    static final class JobFailed extends Exception {
        /** */
        JobFailed(String msg) {
            super(msg);
        }
    }
}
