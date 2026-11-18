package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * How long things take on this stand, from what it did: the measuring of a scenario and the builds of a kind. Every
 * estimate (the queue, a launch, a bisection step, the schedules) comes from here.
 * <ul>
 * <li>A run is the median of the last finished jobs of the scenario and type, each from the start of its first phase
 * to its end: the builds the job made are not in it, they are counted on their own. When enough of them ran the
 * current version of the scenario, only those count.</li>
 * <li>Before the first run of a scenario: its phases, each its warmup and measuring plus what a phase adds on this
 * stand (start of the JVMs, setup, stop), learned from the scenarios with as many servers.</li>
 * <li>A build is the median of the last builds of its kind: Ignite alone, with the modules of a SQL scenario, or with
 * the extension of a plugin.</li>
 * </ul>
 * An instance remembers what it read: make one per answer.
 */
final class Durations {
    /** Finished jobs a run is the median of. */
    static final int RUNS = 15;

    /** Jobs of the current version of a scenario that are enough to leave the older versions out. */
    static final int SAME_VERSION = 3;

    /** Builds of a kind a build is the median of. */
    static final int BUILDS = 10;

    /** Builds read to find those of every kind. */
    static final int BUILDS_READ = 300;

    /** Jobs what a phase adds is learned from. */
    static final int OVERHEAD_JOBS = 200;

    /** Seconds a phase adds to its warmup and measuring before the stand has measured anything. */
    static final double PHASE_OVERHEAD = 45;

    /** Seconds of a build before the stand has built anything. */
    static final double BUILD = 60;

    /** A sha that stands for any in a build key: the kind of a build is its key without its shas. */
    private static final String ANY_SHA = "0".repeat(40);

    /**
     * How long a finished job took.
     *
     * @param scenario Scenario name.
     * @param hash Hash of the scenario version it ran.
     * @param type Job type.
     * @param plugin Plugin of the scenario, or {@code null}.
     * @param measureSec Seconds from the start of the first phase to the end.
     * @param buildSec Seconds before the first phase: the builds of the job.
     * @param phases Phases it ran.
     */
    record Timing(String scenario, String hash, String type, String plugin, double measureSec, double buildSec,
        int phases) {
    }

    /** */
    private final Db db;

    /** Scenario by plugin (null for Ignite) and name; null when it is gone. */
    private final BiFunction<String, String, Scenarios.Info> scenario;

    /** */
    private final Map<String, Double> runs = new HashMap<>();

    /** Seconds a phase adds, by the number of servers; -1 for any number. */
    private Map<Integer, Double> overhead;

    /** Durations of the last builds by kind, newest first; "*" for all. */
    private Map<String, List<Double>> builds;

    /**
     * @param db Database.
     * @param scenario Scenario by plugin (null for Ignite) and name.
     */
    Durations(Db db, BiFunction<String, String, Scenarios.Info> scenario) {
        this.db = db;
        this.scenario = scenario;
    }

    /**
     * Seconds of a job of the scenario, without its builds.
     *
     * @param sc Scenario, or {@code null} when it is unknown.
     * @param plugin Plugin of the scenario, or {@code null}.
     * @param type Job type.
     * @return Seconds.
     */
    double runSeconds(Scenarios.Info sc, String plugin, String type) {
        if (sc == null)
            return phases(type) * (120 + PHASE_OVERHEAD);

        return runs.computeIfAbsent(plugin + "|" + sc.name + "|" + type, k -> {
            List<Timing> all = db.timings(sc.name, plugin, type, RUNS);

            if (all.isEmpty())
                return phases(type) * (sc.totalSeconds() + overhead(sc.servers));

            List<Timing> same = all.stream().filter(t -> Objects.equals(t.hash(), sc.hash)).toList();

            return JobService.median((same.size() >= SAME_VERSION ? same : all).stream().map(Timing::measureSec)
                .toList());
        });
    }

    /**
     * Seconds of a build.
     *
     * @param key Build key.
     * @return Seconds.
     */
    double buildSeconds(String key) {
        if (builds == null)
            builds = readBuilds();

        List<Double> same = builds.get(kind(key));

        if (same != null && !same.isEmpty())
            return JobService.median(same);

        List<Double> all = builds.get("*");

        return all == null || all.isEmpty() ? BUILD : JobService.median(all.subList(0, Math.min(all.size(), 30)));
    }

    /**
     * Seconds of a build of a commit for the scenario.
     *
     * @param sc Scenario.
     * @param plugin Plugin of the scenario, or {@code null}.
     * @return Seconds.
     */
    double buildSeconds(Scenarios.Info sc, String plugin) {
        return buildSeconds(Builder.key(ANY_SHA, sc == null ? List.of() : sc.modules, plugin,
            plugin == null ? null : ANY_SHA));
    }

    /**
     * The kind of a build: its key without the shas, as {@code +calcite+indexing@spring-data}.
     *
     * @param key Build key.
     * @return Kind; empty for Ignite alone.
     */
    static String kind(String key) {
        return key.replaceFirst("^[0-9a-f]{40}", "").replaceFirst("-[0-9a-f]{40}$", "");
    }

    /**
     * Phases of a job type.
     *
     * @param type Job type.
     * @return Number of phases.
     */
    static int phases(String type) {
        return Job.ABBA.equals(type) ? 4 : Job.PROBE.equals(type) ? 3 : 1;
    }

    /** What a phase adds to its warmup and measuring on this stand, with that many servers. */
    private double overhead(int servers) {
        if (overhead == null) {
            Map<Integer, List<Double>> by = new HashMap<>();

            for (Timing t : db.timings(null, null, null, OVERHEAD_JOBS)) {
                Scenarios.Info sc = scenario.apply(null, t.scenario());

                if (sc == null || t.phases() == 0 || !Objects.equals(t.hash(), sc.hash))
                    continue;

                double add = Math.max(0, t.measureSec() / t.phases() - sc.totalSeconds());
                by.computeIfAbsent(sc.servers, s -> new ArrayList<>()).add(add);
                by.computeIfAbsent(-1, s -> new ArrayList<>()).add(add);
            }

            overhead = new HashMap<>();
            by.forEach((s, xs) -> overhead.put(s, JobService.median(xs)));
        }

        return overhead.getOrDefault(servers, overhead.getOrDefault(-1, PHASE_OVERHEAD));
    }

    /** The last builds that went well, by kind: real ones, fake ones only on a stand that has no other. */
    private Map<String, List<Double>> readBuilds() {
        List<Build> ok = db.builds(BUILDS_READ).stream()
            .filter(b -> Build.OK.equals(b.status) && b.durationSec != null).toList();
        boolean real = ok.stream().anyMatch(b -> !b.fake);
        Map<String, List<Double>> out = new HashMap<>();

        for (Build b : ok) {
            if (real && b.fake)
                continue;

            List<Double> kind = out.computeIfAbsent(kind(b.sha), k -> new ArrayList<>());

            if (kind.size() < BUILDS)
                kind.add(b.durationSec);

            out.computeIfAbsent("*", k -> new ArrayList<>()).add(b.durationSec);
        }

        return out;
    }
}
