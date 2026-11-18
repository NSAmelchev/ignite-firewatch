package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls the mirror, keeps the Commits table fresh and turns new commits of the watched branch into auto jobs.
 * The watermark starts at the branch head seen first (history is never queued automatically) and always
 * advances, so commits that arrive while auto-run is paused or filtered out stay runnable by hand only.
 */
public final class Watcher implements Runnable {
    /** */
    private static final int HISTORY = 1000;

    /** */
    private final Config cfg;

    /** */
    private final Db db;

    /** */
    private final GitMirror mirror;

    /** */
    private final JobService jobs;

    /** The plugins: their repositories are fetched and their pairs queued with the other recurring runs. */
    private volatile Plugins plugins;

    /** */
    private final Object lock = new Object();

    /** */
    private final Thread thread = new Thread(this, "watcher");

    /** */
    private volatile boolean running = true;

    /** */
    private volatile boolean pollRequested;

    /** */
    private volatile boolean polling;

    /** */
    private volatile String lastPollAt;

    /** */
    private volatile String lastError;

    /** */
    private volatile String head;

    /** Head whose history is already in the DB: the history is rewritten only when the branch moves. */
    private String historyHead;

    /** */
    private volatile int lastNewCommits;

    /** */
    private volatile int lastQueued;

    /**
     * Sets the plugins of the stand.
     *
     * @param p Plugins.
     */
    void plugins(Plugins p) {
        plugins = p;
    }

    /**
     * Creates the watcher.
     *
     * @param cfg Configuration.
     * @param db Database.
     * @param mirror Git mirror.
     * @param jobs Job service.
     */
    public Watcher(Config cfg, Db db, GitMirror mirror, JobService jobs) {
        this.cfg = cfg;
        this.db = db;
        this.mirror = mirror;
        this.jobs = jobs;
    }

    /** */
    public void start() {
        thread.setDaemon(true);
        thread.start();
    }

    /** */
    public void stop() {
        running = false;
        thread.interrupt();
    }

    /** */
    public void pollNow() {
        synchronized (lock) {
            pollRequested = true;
            lock.notifyAll();
        }
    }

    /** {@inheritDoc} */
    @Override public void run() {
        while (running) {
            // a new stand may still choose another repository: cloning this one would be wasted
            if (!Stand.needsSetup(db))
                pollAndSchedule();

            long pollSec = Long.parseLong(db.setting(Settings.WATCH_POLL_SEC, "3600"));
            long deadline = System.currentTimeMillis() + pollSec * 1000;

            synchronized (lock) {
                while (running && !pollRequested && System.currentTimeMillis() < deadline) {
                    try {
                        lock.wait(Math.max(100, deadline - System.currentTimeMillis()));
                    }
                    catch (InterruptedException e) {
                        if (!running)
                            return;
                    }
                }

                pollRequested = false;
            }
        }
    }

    /** */
    private void pollAndSchedule() {
        try {
            polling = true;
            poll();
            lastError = null;
        }
        catch (Exception e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            Log.warn("watcher: " + lastError);
        }
        finally {
            polling = false;
            lastPollAt = Db.now();
        }

        // separately from the poll: a failed fetch must not cost the A/A its turn
        try {
            Calibrator.runIfDue(db, jobs, cfg.branch);
        }
        catch (Exception e) {
            Log.warn("watcher: recurring runs: " + e);
        }

        Plugins p = plugins;

        if (p != null && !p.list().isEmpty()) {
            try {
                PluginSchedule.runIfDue(db, jobs, p, mirror);
            }
            catch (Exception e) {
                Log.warn("watcher: plugins: " + e);
            }
        }
    }

    /** One poll: fetch, refresh the branch history, queue new commits. Package-private for tests. */
    void poll() throws IOException {
        mirror.fetch();
        String newHead = mirror.resolve("refs/heads/" + cfg.branch);
        head = newHead;

        if (!newHead.equals(historyHead)) {
            refreshHistory(newHead);
            historyHead = newHead;
        }

        String watermark = db.setting(Settings.WATCH_WATERMARK, "");

        if (watermark.isBlank()) {
            db.setSetting(Settings.WATCH_WATERMARK, newHead);
            Log.info("watcher: watermark set to current head " + Job.shortSha(newHead)
                + " (older commits are not queued)");

            return;
        }

        if ("segments".equals(db.setting(Settings.AUTORUN_MODE, "abba-vs-parent"))) {
            if (!watermark.equals(newHead))
                lastNewCommits = mirror.revList(watermark, newHead).size();

            segments(newHead, watermark);
            db.setSetting(Settings.WATCH_WATERMARK, newHead);

            return;
        }

        if (watermark.equals(newHead))
            return;

        List<String> fresh;

        try {
            fresh = mirror.revList(watermark, newHead);
        }
        catch (IOException e) {
            Log.warn("watcher: cannot list commits after watermark " + Job.shortSha(watermark) + " (" + e.getMessage()
                + "); resetting the watermark to the head");
            db.setSetting(Settings.WATCH_WATERMARK, newHead);

            return;
        }

        lastNewCommits = fresh.size();
        boolean enabled = Config.isTrue(db.setting(Settings.AUTORUN_ENABLED, "false"));
        String mode = db.setting(Settings.AUTORUN_MODE, "abba-vs-parent");
        List<String> scenarioNames = Settings.scenarioList(db.setting(Settings.AUTORUN_SCENARIO,
            Settings.DEFAULT_SCENARIO));
        PathFilter filter = PathFilter.parse(db.setting(Settings.AUTORUN_PATH_FILTER, ""));
        int maxBacklog = Integer.parseInt(db.setting(Settings.AUTORUN_MAX_BACKLOG, "5"));
        int queued = 0;
        Log.info("watcher: " + fresh.size() + " new commit(s) on " + cfg.branch
            + (enabled ? "" : " (auto-run is off)"));

        for (String sha : fresh) {
            if (!enabled)
                continue;

            List<String> files = mirror.changedFiles(sha);

            if (!filter.matchesAny(files)) {
                Log.info("watcher: " + Job.shortSha(sha) + " skipped by the path filter (" + files.size() + " files)");
                continue;
            }

            if (queuedAutoJobs() >= maxBacklog) {
                Log.warn("watcher: " + Job.shortSha(sha) + " not queued, backlog limit " + maxBacklog + " reached");
                continue;
            }

            CommitInfo ci = mirror.info(sha);

            for (String scenario : scenarioNames) {
                try {
                    jobs.enqueueAuto(sha, ci == null ? null : ci.parentSha, mode, scenario);
                    queued++;
                }
                catch (IllegalArgumentException dup) {
                    Log.info("watcher: " + Job.shortSha(sha) + " [" + scenario + "] already queued");
                }
            }
        }

        lastQueued = queued;
        db.setSetting(Settings.WATCH_WATERMARK, newHead);
        // not in segments mode: keep the segment start at the head, so switching to segments does not sweep this
        // history
        db.setSetting(Settings.WATCH_SEGMENT_BASE, newHead);
    }

    /**
     * Segments mode: new commits are collected from the end of the last queued segment. The oldest that passes the path
     * filter says when they go ({@link Freshness}): one ABBA "head vs segment start" per auto-run scenario, and the
     * head becomes the next start. Called on every poll (also without new commits), so that a quiet branch gets its
     * turn too.
     */
    private void segments(String newHead, String watermark) throws IOException {
        lastQueued = 0;
        String base = db.setting(Settings.WATCH_SEGMENT_BASE, "");

        if (base.isBlank()) {
            base = watermark;
            db.setSetting(Settings.WATCH_SEGMENT_BASE, base);
        }

        if (base.equals(newHead))
            return;

        if (!Config.isTrue(db.setting(Settings.AUTORUN_ENABLED, "false"))) {
            // commits that arrive while auto-run is off stay manual-only: the next segment starts at the head
            db.setSetting(Settings.WATCH_SEGMENT_BASE, newHead);

            return;
        }

        Segment seg = segment(base, newHead);

        if (seg.relevant() == 0)
            return;

        java.time.Instant now = java.time.Instant.now();
        Freshness.Need need = Freshness.need(seg.oldest(), now, keepDays());

        if (!Freshness.goes(need, Freshness.idle(db, now))) {
            Log.info("watcher: segment " + Job.shortSha(base) + ".." + Job.shortSha(newHead) + " has " + seg.relevant()
                + " relevant commit(s), waiting");

            return;
        }

        int queued = 0;

        for (String scenario : Settings.scenarioList(db.setting(Settings.AUTORUN_SCENARIO,
            Settings.DEFAULT_SCENARIO))) {
            try {
                jobs.enqueueSegment(base, newHead, seg.commits(), scenario, Freshness.priority(need),
                    JobService.SRC_AUTO);
                queued++;
            }
            catch (IllegalArgumentException dup) {
                Log.info("watcher: segment " + Job.shortSha(base) + ".." + Job.shortSha(newHead) + " [" + scenario
                    + "] already queued");
            }
        }

        lastQueued = queued;
        db.setSetting(Settings.WATCH_SEGMENT_BASE, newHead);
        Log.info("watcher: queued segment " + Job.shortSha(base) + ".." + Job.shortSha(newHead) + " (" + seg.commits()
            + " commits, " + seg.relevant() + " relevant)");
    }

    /**
     * The commits from the end of the last queued segment to the head.
     *
     * @param base End of the last queued segment.
     * @param head Head of the branch.
     * @param commits Commits between them.
     * @param relevant Those the path filter of auto-run passes.
     * @param oldest When the oldest relevant one was committed; null when none is.
     */
    record Segment(String base, String head, int commits, int relevant, java.time.Instant oldest) {
    }

    /** The last segment worked out, with the path filter it went through: a poll and a page ask for the same. */
    private volatile Segment lastSegment;

    /** */
    private volatile String lastSegmentFilter;

    /** The segment from {@code base} to {@code newHead}, the oldest relevant commit first in line. */
    private Segment segment(String base, String newHead) throws IOException {
        String filterText = db.setting(Settings.AUTORUN_PATH_FILTER, "");
        Segment last = lastSegment;

        if (last != null && last.base().equals(base) && last.head().equals(newHead)
            && filterText.equals(lastSegmentFilter))
            return last;

        List<String> pending = mirror.revList(base, newHead); // oldest first
        PathFilter filter = PathFilter.parse(filterText);
        int relevant = 0;
        java.time.Instant oldest = null;

        for (String sha : pending) {
            if (!filter.matchesAny(mirror.changedFiles(sha)))
                continue;

            relevant++;

            if (relevant == 1) {
                CommitInfo ci = mirror.info(sha);
                oldest = ci == null || ci.committedAt == null ? null
                    : java.time.OffsetDateTime.parse(ci.committedAt).toInstant();
            }
        }

        Segment seg = new Segment(base, newHead, pending.size(), relevant, oldest);
        lastSegmentFilter = filterText;
        lastSegment = seg;

        return seg;
    }

    /**
     * The segment that waits for its turn in the segments mode of auto-run: nothing when auto-run is off, in another
     * mode or the head is where the last segment ended.
     *
     * @return The segment, or {@code null}.
     * @throws IOException If git fails.
     */
    Segment waitingSegment() throws IOException {
        String h = head, base = db.setting(Settings.WATCH_SEGMENT_BASE, "");

        if (h == null || base.isBlank() || base.equals(h) || !Config.isTrue(db.setting(Settings.AUTORUN_ENABLED, "false"))
            || !"segments".equals(db.setting(Settings.AUTORUN_MODE, "abba-vs-parent")))
            return null;

        return segment(base, h);
    }

    /**
     * The term of a segment: its oldest relevant commit waits this long for an idle stand, twice that at most.
     *
     * @return Days.
     */
    int keepDays() {
        return Integer.parseInt(db.setting(Settings.AUTORUN_KEEP_DAYS, "1"));
    }

    /** */
    private void refreshHistory(String head) throws IOException {
        int headSeq = mirror.seq(head);
        List<CommitInfo> log = mirror.log(head, HISTORY);

        for (int i = 0; i < log.size(); i++) {
            CommitInfo ci = log.get(i);
            ci.seq = headSeq - i;
            ci.branch = cfg.branch;
        }

        db.upsertCommits(log);
    }

    /** */
    private int queuedAutoJobs() {
        int n = 0;

        for (Job j : db.jobs("active", 1000)) {
            if (Job.QUEUED.equals(j.status) && JobService.SRC_AUTO.equals(j.source))
                n++;
        }

        return n;
    }

    /**
     * Returns the state of the watcher for the UI.
     *
     * @return Watcher state.
     */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("branch", cfg.branch);
        m.put("repoUrl", cfg.repoUrl);
        m.put("mirrorReady", mirror.ready());
        m.put("fetch", mirror.fetchProgress());
        m.put("polling", polling);
        m.put("head", head);
        m.put("watermark", db.setting(Settings.WATCH_WATERMARK, null));
        m.put("lastPollAt", lastPollAt);
        m.put("lastFetchAt", mirror.lastFetchAt());
        m.put("lastError", lastError != null ? lastError : mirror.lastFetchError());
        m.put("lastNewCommits", lastNewCommits);
        m.put("lastQueued", lastQueued);
        m.put("autorunEnabled", Config.isTrue(db.setting(Settings.AUTORUN_ENABLED, "false")));
        m.put("pollSec", db.setting(Settings.WATCH_POLL_SEC, "3600"));
        m.put("pathFilter", PathFilter.parse(db.setting(Settings.AUTORUN_PATH_FILTER, "")).globs());

        return m;
    }
}
