package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.time.Duration;

/** Controller entry point: wires storage, git mirror, builder, worker, watcher and the HTTP API. */
public final class Main {
    /**
     * Starts the controller.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) throws Exception {
        Config cfg = Config.fromEnv();
        Files.createDirectories(cfg.dataDir);
        Files.createDirectories(cfg.cacheDir);
        Files.createDirectories(cfg.runsDir());
        Log.info("Firewatch controller " + Config.version() + " data=" + cfg.dataDir.toAbsolutePath() + " cache="
            + cfg.cacheDir.toAbsolutePath() + (cfg.fakeBuild ? " FAKE_BUILD" : ""));

        if (!Files.isRegularFile(cfg.harnessJar) && cfg.harnessCommand == null)
            Log.warn("harness jar not found at " + cfg.harnessJar + "; phases will fail until it exists");

        Db db = Db.open(cfg.dbFile());
        Settings.seed(db, cfg);
        Stand.remember(db);
        cfg.instance = Stand.instance(db);
        cfg.repoUrl = db.setting(Settings.REPO_URL, cfg.repoUrl);
        cfg.branch = db.setting(Settings.REPO_BRANCH, cfg.branch);
        Scenarios scenarios = Scenarios.fromHarness(cfg);
        Log.info("scenarios: " + scenarios.list().stream().map(s -> s.name + " v" + s.version).toList());
        GitMirror mirror = new GitMirror(cfg.mirrorDir(), cfg.repoUrl, cfg.branch,
            Duration.ofMinutes(cfg.fetchTimeoutMin));
        Builder builder = new Builder(cfg, db, mirror);
        JobService jobs = new JobService(cfg, db, mirror, scenarios);
        JobRunner runner = new JobRunner(cfg, db, builder, scenarios, jobs, mirror);
        Watcher watcher = new Watcher(cfg, db, mirror, jobs);
        Bisector bisector = new Bisector(db, mirror, jobs);
        Comparisons comparisons = new Comparisons(db, mirror, jobs, scenarios, bisector);
        Notifier notifier = new Notifier(db);
        runner.notifier(notifier);
        bisector.notifier(notifier);
        comparisons.notifier(notifier);
        Api api = new Api(cfg, db, jobs, runner, watcher, scenarios, mirror, notifier, bisector, comparisons);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("shutting down");
            api.stop();
            watcher.stop();
            runner.shutdown();
            notifier.shutdown();

            try {
                db.close();
            }
            catch (Exception ignored) {
            }
        }, "shutdown"));

        api.start();
        runner.start();
        bisector.recover(); // a step interrupted by the restart is queued again
        watcher.start();
    }
}
