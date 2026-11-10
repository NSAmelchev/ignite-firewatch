package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
    private static int days(Db db, String prefix) {
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

    /** A scenario short of the runs a floor needs tops up at once, the others go as they age. */
    private static int calibrate(Db db, JobService jobs, String branch, List<Job> active, Instant now, boolean idle) {
        if (!on(db, AA))
            return 0;

        int n = 0;

        for (String sc : names(db, AA, jobs)) {
            long waiting = waiting(active, JobService.SRC_CALIBRATION, sc);
            List<Job> done = jobs.calibrationsOfCurrentVersion(sc);
            long missing = JobService.MIN_CALIBRATIONS - done.size() - waiting;
            Freshness.Need need = missing > 0 ? Freshness.Need.NOW : waiting > 0 ? Freshness.Need.NOTHING
                : Freshness.need(Instant.parse(done.get(0).finishedAt), now, days(db, AA));

            if (!Freshness.goes(need, idle))
                continue;

            try {
                for (long i = 0; i < Math.max(1, missing); i++) {
                    jobs.calibrate(branch, sc, Freshness.priority(need));
                    n++;
                }
            }
            catch (Exception e) {
                Log.warn("A/A of " + sc + " not queued: " + e.getMessage());
            }
        }

        if (n > 0) {
            db.setSetting(AA_LAST, LocalDate.now().toString());
            Log.info("A/A: " + n + " job(s) queued");
        }

        return n;
    }

    /** A run of the current head says all there is, however old it is. */
    private static int measureMaster(Db db, JobService jobs, List<Job> active, Instant now, boolean idle) {
        if (!on(db, MASTER))
            return 0;

        int n = 0;

        for (String sc : names(db, MASTER, jobs)) {
            try {
                if (waiting(active, JobService.SRC_RELEASE, sc) > 0)
                    continue;

                Job last = jobs.latestRelease(sc);

                if (last == null && jobs.releaseTag() == null)
                    return n;

                if (last != null && last.shaB.equals(jobs.head()))
                    continue;

                Freshness.Need need = Freshness.need(last == null ? null : Instant.parse(last.finishedAt), now,
                    days(db, MASTER));

                if (Freshness.goes(need, idle)) {
                    jobs.queueRelease(sc, Freshness.priority(need));
                    n++;
                }
            }
            catch (IOException | RuntimeException e) {
                Log.warn("master vs release of " + sc + " not queued: " + e.getMessage());
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
