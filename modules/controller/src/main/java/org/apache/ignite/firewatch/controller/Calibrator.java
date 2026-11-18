package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Recurring runs, off until switched on: the A/A of the branch head, so the noise floor follows the machine's current
 * state, and master vs release, which keeps the dashboard headline fresh. Each scenario goes on its own when it needs
 * it ({@link Freshness}). Checked by the watcher on every poll; nothing goes while the stand is closed for maintenance.
 */
public final class Calibrator {
    /** Settings prefix of the A/A; {@code .scenarios} empty = every scenario, so a new one joins by itself. */
    public static final String AA = "calibration";

    /** Settings prefix of master vs release. */
    public static final String MASTER = "master";

    /** */
    public static final String AA_LAST = "calibration.lastDate";

    /** */
    public static final String MASTER_LAST = "master.lastDate";

    /**
     * Queues what is due; returns how many jobs were queued.
     *
     * @param db Database.
     * @param jobs Job service.
     * @param branch Followed branch.
     * @return Number of queued jobs.
     */
    public static int runIfDue(Db db, JobService jobs, String branch) {
        if (Config.isTrue(db.setting(Settings.WORKER_PAUSED, "false")))
            return 0;

        Instant now = Instant.now();
        boolean idle = Freshness.idle(db, now);
        List<Job> active = db.jobs("active", Integer.MAX_VALUE);

        return calibrate(db, jobs, branch, active, now, idle) + measureMaster(db, jobs, active, now, idle);
    }

    /** */
    private static boolean on(Db db, String prefix) {
        return Freshness.KEEP.equals(db.setting(prefix + ".mode", Freshness.OFF));
    }

    /** */
    static int days(Db db, String prefix) {
        return Integer.parseInt(db.setting(prefix + ".keepDays", "7"));
    }

    /** */
    private static List<String> names(Db db, String prefix, JobService jobs) {
        List<String> chosen = Settings.scenarioList(db.setting(prefix + ".scenarios", ""));

        return chosen.isEmpty() ? jobs.scenarioNames() : chosen;
    }

    /** */
    private static long waiting(List<Job> active, String source, String scenario) {
        return active.stream().filter(j -> source.equals(j.source) && scenario.equals(j.scenario)).count();
    }

    /**
     * What the A/A needs, scenario by scenario: a scenario short of the runs a floor needs tops up at once, the others
     * go as they age.
     *
     * @param db Database.
     * @param jobs Job service.
     * @param active Running and queued jobs.
     * @param now Now.
     * @return Dues; empty while the A/A is off.
     */
    static List<Freshness.Due> aaDues(Db db, JobService jobs, List<Job> active, Instant now) {
        List<Freshness.Due> out = new ArrayList<>();

        if (!on(db, AA))
            return out;

        for (String sc : names(db, AA, jobs)) {
            long waiting = waiting(active, JobService.SRC_CALIBRATION, sc);
            List<Job> done = jobs.calibrationsOfCurrentVersion(sc);
            long missing = JobService.MIN_CALIBRATIONS - done.size() - waiting;
            Instant measured = done.isEmpty() ? null : Instant.parse(done.get(0).finishedAt);

            if (missing <= 0 && waiting > 0)
                out.add(Freshness.Due.queued(sc, measured));
            else {
                out.add(new Freshness.Due(sc, missing > 0 ? Freshness.Need.NOW
                    : Freshness.need(measured, now, days(db, AA)), measured, (int)Math.max(1, missing), true, false));
            }
        }

        return out;
    }

    /**
     * What master vs release needs, scenario by scenario: a run of the current head says all there is, however old it
     * is.
     *
     * @param db Database.
     * @param jobs Job service.
     * @param active Running and queued jobs.
     * @param now Now.
     * @param onError Told of a scenario that cannot say.
     * @return Dues; empty while it is off or there is no release to compare with.
     */
    static List<Freshness.Due> masterDues(Db db, JobService jobs, List<Job> active, Instant now,
        BiConsumer<String, Exception> onError) {
        List<Freshness.Due> out = new ArrayList<>();

        if (!on(db, MASTER))
            return out;

        for (String sc : names(db, MASTER, jobs)) {
            try {
                Job last = jobs.latestRelease(sc);
                Instant measured = last == null ? null : Instant.parse(last.finishedAt);

                if (waiting(active, JobService.SRC_RELEASE, sc) > 0) {
                    out.add(Freshness.Due.queued(sc, measured));
                    continue;
                }

                if (last == null && jobs.releaseTag() == null)
                    return out;

                boolean moved = last == null || !last.shaB.equals(jobs.head());
                out.add(new Freshness.Due(sc, moved ? Freshness.need(measured, now, days(db, MASTER))
                    : Freshness.Need.NOTHING, measured, 1, moved, false));
            }
            catch (IOException | RuntimeException e) {
                onError.accept(sc, e);
            }
        }

        return out;
    }

    /** Queues the A/A that is due. */
    private static int calibrate(Db db, JobService jobs, String branch, List<Job> active, Instant now, boolean idle) {
        int n = 0;

        for (Freshness.Due d : aaDues(db, jobs, active, now)) {
            if (!Freshness.goes(d.need(), idle))
                continue;

            try {
                for (int i = 0; i < d.jobs(); i++) {
                    jobs.calibrate(branch, d.scenario(), Freshness.priority(d.need()));
                    n++;
                }
            }
            catch (Exception e) {
                Log.warn("A/A of " + d.scenario() + " not queued: " + e.getMessage());
            }
        }

        if (n > 0) {
            db.setSetting(AA_LAST, LocalDate.now().toString());
            Log.info("A/A: " + n + " job(s) queued");
        }

        return n;
    }

    /** Queues master vs release where it is due. */
    private static int measureMaster(Db db, JobService jobs, List<Job> active, Instant now, boolean idle) {
        int n = 0;

        for (Freshness.Due d : masterDues(db, jobs, active, now,
            (sc, e) -> Log.warn("master vs release of " + sc + " not queued: " + e.getMessage()))) {
            if (!Freshness.goes(d.need(), idle))
                continue;

            try {
                jobs.queueRelease(d.scenario(), Freshness.priority(d.need()));
                n++;
            }
            catch (IOException | RuntimeException e) {
                Log.warn("master vs release of " + d.scenario() + " not queued: " + e.getMessage());
            }
        }

        if (n > 0) {
            db.setSetting(MASTER_LAST, LocalDate.now().toString());
            Log.info("master vs release: " + n + " job(s) queued");
        }

        return n;
    }

    /** */
    private Calibrator() {
    }
}
