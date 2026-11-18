package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What the schedules queue next and when: auto-run, master vs release, the A/A and the plugins. It asks the rules
 * that queue them ({@link Freshness}: past its term a scenario goes once the stand has been idle for
 * {@link Freshness#IDLE_HOURS}, behind everything else; at twice its term, or never measured, ahead of the queue) and
 * takes the times of {@link Durations}. Nothing goes while the stand is closed for maintenance.
 * <p>
 * A schedule is an item: {@code kind} (autorun, master, aa, plugin), {@code title}, {@code plugin}, the scenarios that go
 * next ({@code scenarios}, {@code jobs}, {@code builds}, {@code runSec}, {@code takesSec}), {@code later} (scenarios due
 * after them), {@code dueAt} (when they are due), {@code byAt} (when they go whatever the queue holds), {@code startAt}
 * (when they should start) and {@code waitsFor}: {@code now} (they go next), {@code idle} (an idle stand),
 * {@code term} (twice their term), {@code commit} (a commit to measure), {@code queue} (they are in the queue),
 * {@code reopen} (the end of the maintenance).
 */
final class Schedule {
    /** Stands for a commit not built yet. */
    private static final String NEW = "0".repeat(40);

    /** Scenarios due within this of the first one go with it: they were measured together. */
    private static final Duration TOGETHER = Duration.ofHours(12);

    /** */
    private final Db db;

    /** */
    private final JobService jobs;

    /** */
    private final GitMirror mirror;

    /** */
    private final Watcher watcher;

    /** */
    private final Plugins plugins;

    /** */
    private final Bisector bisector;

    /** What every item needs: the queue as it is and the times. */
    private final class Now {
        /** */
        final Instant now = Instant.now();

        /** */
        final boolean paused = Config.isTrue(db.setting(Settings.WORKER_PAUSED, "false"));

        /** */
        final List<Job> active = db.jobs("active", Integer.MAX_VALUE);

        /** */
        final Durations durations = jobs.durations();

        /** Keys that are built, or that the queue builds before a schedule runs. */
        final Set<String> built = db.okBuildShas();

        /** When the running job should end: an overdue schedule goes right after it. */
        Instant aheadAt = now;

        /** When the stand should have been idle long enough. */
        Instant idleAt;

        /** */
        Now() {
            jobs.addEta(active);
            Instant busy = null;

            for (Job j : active) {
                built.addAll(JobService.buildKeys(j, jobs.scenarioOf(j)).values());

                if (j.etaStartAt == null || j.etaSec == null)
                    continue;

                Instant end = Instant.parse(j.etaStartAt).plusSeconds(j.etaSec);
                busy = busy == null || end.isAfter(busy) ? end : busy;

                if (Job.RUNNING.equals(j.status) && end.isAfter(aheadAt))
                    aheadAt = end;
            }

            String bisects = bisector == null ? null : bisector.doneAt();

            if (bisects != null && (busy == null || Instant.parse(bisects).isAfter(busy)))
                busy = Instant.parse(bisects);

            if (busy == null) { // idle since the last job ended
                List<Job> last = db.jobs("history", 1);
                busy = last.isEmpty() || last.get(0).finishedAt == null ? Instant.EPOCH
                    : Instant.parse(last.get(0).finishedAt);
            }

            Instant idle = busy.plus(Duration.ofHours(Freshness.IDLE_HOURS));
            idleAt = idle.isAfter(now) ? idle : now;
        }
    }

    /**
     * @param db Database.
     * @param jobs Job service.
     * @param mirror Mirror of Ignite.
     * @param watcher Watcher, for the segment of auto-run that waits.
     * @param plugins Plugins.
     * @param bisector Bisector, for when the bisections end; may be {@code null}.
     */
    Schedule(Db db, JobService jobs, GitMirror mirror, Watcher watcher, Plugins plugins, Bisector bisector) {
        this.db = db;
        this.jobs = jobs;
        this.mirror = mirror;
        this.watcher = watcher;
        this.plugins = plugins;
        this.bisector = bisector;
    }

    /**
     * The schedules that are on, with what they queue next.
     *
     * @return Items, auto-run first, then master vs release, the A/A and the plugins.
     */
    List<Map<String, Object>> view() {
        Now c = new Now();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> auto = autorun(c);

        if (auto != null)
            out.add(auto);

        String head = null, tag = null, tagSha = null;

        try {
            head = jobs.head();
            tag = jobs.releaseTag();
            tagSha = tag == null ? null : mirror.resolve(tag);
        }
        catch (IOException | RuntimeException ignored) {
            // the mirror is not ready: no builds are counted
        }

        String h = head, ts = tagSha;
        List<Freshness.Due> master = Calibrator.masterDues(db, jobs, c.active, c.now, (sc, e) -> { });

        if (!master.isEmpty()) {
            out.add(item(c, "master", mirror.branch() + " vs " + (tag == null ? "the release" : tag), null, master,
                Calibrator.days(db, Calibrator.MASTER), sc -> job(Job.ABBA, sc, ts, h)));
        }

        List<Freshness.Due> aa = Calibrator.aaDues(db, jobs, c.active, c.now);

        if (!aa.isEmpty()) {
            out.add(item(c, "aa", "A/A", null, aa, Calibrator.days(db, Calibrator.AA),
                sc -> job(Job.ABBA, sc, h, h)));
        }

        for (Plugins.Plugin p : plugins == null ? List.<Plugins.Plugin>of() : plugins.list()) {
            if (PluginSchedule.scheduled(plugins, p))
                out.add(plugin(c, p));
        }

        return out;
    }

    /** Auto-run: the segment that waits in the segments mode, else what each new commit costs. */
    private Map<String, Object> autorun(Now c) {
        if (!Config.isTrue(db.setting(Settings.AUTORUN_ENABLED, "false")))
            return null;

        String mode = db.setting(Settings.AUTORUN_MODE, "abba-vs-parent");
        List<String> names = Settings.scenarioList(db.setting(Settings.AUTORUN_SCENARIO, Settings.DEFAULT_SCENARIO));
        String type = "single".equals(mode) ? Job.SINGLE : Job.ABBA;
        Watcher.Segment seg = null;

        try {
            seg = watcher == null ? null : watcher.waitingSegment();
        }
        catch (IOException | RuntimeException ignored) {
            // git cannot say now: the next commit is what it waits for
        }

        Map<String, Object> m;

        if (seg == null || seg.relevant() == 0) {
            m = new LinkedHashMap<>();
            m.put("kind", "autorun");
            m.put("title", "Auto-run");
            m.put("total", names.size());
            m.put("waitsFor", "commit");
            cost(c, m, names.stream().map(n -> new Freshness.Due(n, Freshness.Need.NOTHING, null, 1, false, false))
                .toList(), n -> job(type, n, NEW, NEW)); // a new commit: a build of each module set
        }
        else {
            Watcher.Segment s = seg;
            m = item(c, "autorun", "Auto-run", null, names.stream()
                .map(n -> new Freshness.Due(n, Freshness.need(s.oldest(), c.now, watcher.keepDays()), s.oldest(), 1,
                    true, false)).toList(), watcher.keepDays(), n -> job(Job.ABBA, n, s.base(), s.head()));
            m.put("commits", s.relevant());
        }

        m.put("mode", mode);

        return m;
    }

    /** A plugin: its release pair against its master pair. */
    private Map<String, Object> plugin(Now c, Plugins.Plugin p) {
        String title = "Plugin " + p.title;

        try {
            PluginSchedule.Pairs pairs = PluginSchedule.pairs(plugins, p, mirror);
            int days = PluginSchedule.keepDays(plugins, p);
            List<Freshness.Due> dues = p.scenarios.list().stream()
                .map(sc -> PluginSchedule.due(db, p, sc, pairs, c.now, days)).toList();

            return item(c, "plugin", title, p.name, dues, days, sc -> {
                Job j = job(Job.ABBA, sc, pairs.release().igniteSha(), pairs.master().igniteSha());
                j.plugin = p.name;
                j.extShaA = pairs.release().extSha();
                j.extShaB = pairs.master().extSha();

                return j;
            });
        }
        catch (IOException | RuntimeException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "plugin");
            m.put("title", title);
            m.put("plugin", p.name);
            m.put("error", e.getMessage());

            return m;
        }
    }

    /**
     * A schedule from what its scenarios need: those due first go together, when the stand lets them.
     *
     * @param c The queue now.
     * @param kind Kind.
     * @param title Title.
     * @param plugin Plugin, or {@code null}.
     * @param dues What its scenarios need.
     * @param days Its term, days.
     * @param jobOf The job a scenario would get, for its run and builds.
     * @return Item.
     */
    private Map<String, Object> item(Now c, String kind, String title, String plugin, List<Freshness.Due> dues,
        int days, Function<String, Job> jobOf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("title", title);
        m.put("plugin", plugin);
        m.put("keepDays", days);
        m.put("total", dues.size());
        m.put("queued", dues.stream().filter(Freshness.Due::queued).count());
        List<Freshness.Due> open = dues.stream().filter(d -> !d.queued() && d.moved()).toList();

        if (open.isEmpty()) {
            List<Freshness.Due> waiting = dues.stream().filter(d -> !d.queued()).toList();
            m.put("waitsFor", waiting.isEmpty() ? "queue" : "commit"); // a commit first, closed or not
            cost(c, m, waiting, jobOf);

            return m;
        }

        Function<Freshness.Due, Instant> due = d -> d.need() == Freshness.Need.NOW || d.measured() == null ? c.now
            : d.measured().plus(Duration.ofDays(days));
        Function<Freshness.Due, Instant> by = d -> d.need() == Freshness.Need.NOW || d.measured() == null ? c.now
            : d.measured().plus(Duration.ofDays(2L * days));
        Instant first = open.stream().map(due).min(Comparator.naturalOrder()).orElse(c.now);
        List<Freshness.Due> batch = open.stream().filter(d -> !due.apply(d).isAfter(first.plus(TOGETHER))).toList();
        Instant byAt = batch.stream().map(by).min(Comparator.naturalOrder()).orElse(c.now);
        Instant start;
        String waits;

        if (c.paused) {
            start = null;
            waits = "reopen";
        }
        else if (!byAt.isAfter(c.now)) {
            start = c.aheadAt;
            waits = "now";
        }
        else {
            Instant s = first.isAfter(c.idleAt) ? first : c.idleAt;

            if (s.isBefore(byAt)) {
                start = s;
                waits = "idle";
            }
            else {
                start = byAt.isAfter(c.aheadAt) ? byAt : c.aheadAt;
                waits = "term";
            }
        }

        m.put("dueAt", first.toString());
        m.put("byAt", byAt.toString());
        m.put("startAt", start == null ? null : start.toString());
        m.put("waitsFor", waits);
        m.put("later", open.size() - batch.size());
        cost(c, m, batch, jobOf);

        return m;
    }

    /** What the scenarios cost: their runs and the builds the queue does not make before them. */
    private void cost(Now c, Map<String, Object> m, List<Freshness.Due> dues, Function<String, Job> jobOf) {
        Set<String> keys = new LinkedHashSet<>();
        double run = 0;
        double build = 0;
        int n = 0;

        for (Freshness.Due d : dues) {
            Job j = jobOf.apply(d.scenario());
            Scenarios.Info sc = jobs.scenarioOf(j);
            run += d.jobs() * c.durations.runSeconds(sc, j.plugin, j.type);
            n += d.jobs();

            for (String key : JobService.buildKeys(j, sc).values()) {
                if (!c.built.contains(key) && keys.add(key))
                    build += c.durations.buildSeconds(key);
            }
        }

        m.put("scenarios", dues.stream().map(Freshness.Due::scenario).toList());
        m.put("jobs", n);
        m.put("runSec", n == 0 ? 0 : Math.round(run / n));
        m.put("builds", keys.size());
        m.put("takesSec", Math.round(run + build));
    }

    /** A job as a schedule would queue it, for its run and builds: never saved. */
    private static Job job(String type, String scenario, String shaA, String shaB) {
        Job j = new Job();
        j.type = type;
        j.scenario = scenario;
        j.shaA = shaA;
        j.shaB = shaB;

        return j;
    }
}
