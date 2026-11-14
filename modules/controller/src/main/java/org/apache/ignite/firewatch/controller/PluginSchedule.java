package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The runs of plugins: the release pair against the master pair, kept fresh like master against the release of Ignite
 * ({@link Freshness}, a day by default), and the pairs queued by hand. A pair is Ignite and the extension next to it:
 * <ul>
 * <li>the release pair: the extension at the release set on the page of the plugin, and the Ignite its pom builds
 * against (or the one set there);</li>
 * <li>the master pair: the head of the branch of Ignite and the head of the branch of the extension.</li>
 * </ul>
 * Both sides of a pair move, so a result says how the two masters do together, not which commit moved them: nothing is
 * bisected, the Compare of the plugin narrows it down by hand.
 */
final class PluginSchedule {
    /** The term of a plugin when its setting says nothing: its masters move every day. */
    static final int KEEP_DAYS = 1;

    /** A pair: a ref and commit of Ignite, a ref and commit of the extension. */
    record Pair(String igniteRef, String igniteSha, String extRef, String extSha) {
    }

    /**
     * The two pairs of a plugin now.
     *
     * @param release The release pair.
     * @param master The master pair.
     */
    record Pairs(Pair release, Pair master) {
    }

    /** */
    private PluginSchedule() {
    }

    /**
     * The release pair and the master pair of a plugin, from its mirrors as they are.
     *
     * @param plugins Plugins.
     * @param p Plugin.
     * @param ignite Mirror of Ignite.
     * @return The pairs.
     * @throws IOException If a ref is unknown or the release is not set.
     */
    static Pairs pairs(Plugins plugins, Plugins.Plugin p, GitMirror ignite) throws IOException {
        String rel = plugins.release(p);

        if (rel == null)
            throw new IllegalStateException("the release of " + p.name + " is not set: set it on the page of the plugin");

        if (!p.mirror.ready() || !ignite.ready())
            throw new IllegalStateException("the repositories are not fetched yet");

        String extRel = p.mirror.resolve(p.mirror.normalize(rel));
        String igniteRef = plugins.setting(p, Plugins.IGNITE, "");

        if (igniteRef.isEmpty()) {
            igniteRef = PluginPoms.igniteParent(path -> p.mirror.show(extRel, path), p.module).version();

            if (igniteRef.contains("SNAPSHOT"))
                throw new IllegalStateException(rel + " of " + p.name + " builds against Ignite " + igniteRef
                    + ", which is no release: set the Ignite of the release on the page of the plugin");
        }

        String branch = plugins.branch(p);

        return new Pairs(new Pair(igniteRef, ignite.resolve(ignite.normalize(igniteRef)), rel, extRel),
            new Pair(ignite.branch(), ignite.resolve("refs/heads/" + ignite.branch()), branch,
                p.mirror.resolve("refs/heads/" + branch)));
    }

    /**
     * Fetches the repositories of the plugins and queues the pairs that are due. Like the other recurring runs it does
     * nothing while the stand is closed for maintenance.
     *
     * @param db Database.
     * @param jobs Jobs.
     * @param plugins Plugins.
     * @param ignite Mirror of Ignite.
     * @return Number of queued jobs.
     */
    static int runIfDue(Db db, JobService jobs, Plugins plugins, GitMirror ignite) {
        int n = 0;

        for (Plugins.Plugin p : plugins.list()) {
            try {
                p.mirror.ensure();
                p.mirror.fetch();
            }
            catch (IOException | RuntimeException e) {
                Log.warn("plugin " + p.name + ": fetch failed: " + e.getMessage());
                continue;
            }

            if (Config.isTrue(db.setting(Settings.WORKER_PAUSED, "false")) || plugins.release(p) == null
                || Freshness.OFF.equals(plugins.setting(p, Plugins.MODE, Freshness.KEEP)))
                continue;

            try {
                Pairs pairs = pairs(plugins, p, ignite);
                Instant now = Instant.now();
                boolean idle = Freshness.idle(db, now);
                int days = Integer.parseInt(plugins.setting(p, Plugins.KEEP_DAYS, String.valueOf(KEEP_DAYS)));

                for (Scenarios.Info sc : p.scenarios.list()) {
                    Freshness.Need need = need(db, p, sc, pairs, now, days);

                    if (Freshness.goes(need, idle)) {
                        queue(jobs, p, sc, pairs.release(), pairs.master(), JobService.SRC_PLUGIN,
                            Freshness.priority(need));
                        n++;
                    }
                }
            }
            catch (IOException | RuntimeException e) {
                Log.warn("plugin " + p.name + ": " + e.getMessage());
            }
        }

        if (n > 0)
            Log.info("plugins: " + n + " job(s) queued");

        return n;
    }

    /**
     * What a scenario needs: nothing while its run waits, nothing when neither master moved since its last run or since
     * the last failure of the same pairs (an extension that does not build waits for a change), else by its age.
     */
    private static Freshness.Need need(Db db, Plugins.Plugin p, Scenarios.Info sc, Pairs pairs, Instant now,
        int days) {
        List<Job> recent = db.pluginJobs(p.name, sc.name, JobService.SRC_PLUGIN, false, 10);

        if (recent.stream().anyMatch(Job::active))
            return Freshness.Need.NOTHING;

        Job last = recent.stream().filter(j -> Job.DONE.equals(j.status)).findFirst().orElse(null);
        Job latest = recent.isEmpty() ? null : recent.get(0);

        if (latest != null && same(latest, pairs) && (Job.DONE.equals(latest.status) || Job.FAILED.equals(latest.status)))
            return Freshness.Need.NOTHING;

        return Freshness.need(last == null || last.finishedAt == null ? null : Instant.parse(last.finishedAt), now,
            days);
    }

    /** Whether the job measured exactly these pairs. */
    static boolean same(Job j, Pairs pairs) {
        return pairs.release().igniteSha().equals(j.shaA) && pairs.release().extSha().equals(j.extShaA)
            && pairs.master().igniteSha().equals(j.shaB) && pairs.master().extSha().equals(j.extShaB);
    }

    /**
     * Queues an ABBA of two pairs.
     *
     * @param jobs Jobs.
     * @param p Plugin.
     * @param sc Scenario of the plugin.
     * @param a Pair A.
     * @param b Pair B.
     * @param source {@link JobService#SRC_PLUGIN} or {@link JobService#SRC_PLUGIN_COMPARE}.
     * @param priority Queue priority.
     * @return The queued job.
     */
    static Job queue(JobService jobs, Plugins.Plugin p, Scenarios.Info sc, Pair a, Pair b, String source,
        int priority) {
        Job j = new Job();
        j.type = Job.ABBA;
        j.source = source;
        j.plugin = p.name;
        j.scenario = sc.name;
        j.scenarioHash = sc.hash;
        j.priority = priority;
        j.refA = a.igniteRef();
        j.shaA = a.igniteSha();
        j.extShaA = a.extSha();
        j.refB = b.igniteRef();
        j.shaB = b.igniteSha();
        j.extShaB = b.extSha();
        j.label = p.name + ": " + side(b) + " vs " + side(a);

        return jobs.queue(j);
    }

    /** "2.17.0 + ignite-spring-data-ext-3.1.0", "master 1a2b3c4d5e + master 9f8e7d6c5b". */
    private static String side(Pair p) {
        return ref(p.igniteRef(), p.igniteSha()) + " + " + ref(p.extRef(), p.extSha());
    }

    /** */
    private static String ref(String ref, String sha) {
        if (ref == null || ref.isEmpty() || ref.equals(sha))
            return Job.shortSha(sha);

        return ref.matches("[0-9a-f]{7,40}") ? Job.shortSha(sha) : ref.matches("\\d+\\.\\d+.*") || ref.contains("-")
            ? ref : ref + " " + Job.shortSha(sha);
    }

    /**
     * Queues the release pair against the master pair now, for every scenario or the given ones.
     *
     * @param plugins Plugins.
     * @param jobs Jobs.
     * @param ignite Mirror of Ignite.
     * @param name Plugin name.
     * @param scenarios Scenario names; empty: all of the plugin.
     * @param priority Queue priority.
     * @return Queued jobs.
     */
    static List<Job> runNow(Plugins plugins, JobService jobs, GitMirror ignite, String name, List<String> scenarios,
        int priority) throws IOException {
        Plugins.Plugin p = plugins.require(name);
        Pairs pairs = pairs(plugins, p, ignite);
        List<Job> out = new ArrayList<>();

        for (Scenarios.Info sc : p.scenarios.list()) {
            if (scenarios.isEmpty() || scenarios.contains(sc.name))
                out.add(queue(jobs, p, sc, pairs.release(), pairs.master(), JobService.SRC_PLUGIN, priority));
        }

        return out;
    }
}
