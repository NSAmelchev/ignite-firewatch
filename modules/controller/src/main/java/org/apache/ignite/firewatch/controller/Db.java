package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite storage. One connection, every public method synchronized: SQLite has a single writer anyway and the
 * controller's load is a few queries per second at most.
 */
public final class Db implements AutoCloseable {
    /** */
    private static final int SCHEMA_VERSION = 3;

    /** */
    private final Connection c;

    /** */
    private Db(Connection c) {
        this.c = c;
    }

    /**
     * Opens the database file, creating it and its schema if needed.
     *
     * @param file Database file.
     * @return Open database.
     */
    public static Db open(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        catch (Exception e) {
            throw new SQLException("cannot create " + file.getParent(), e);
        }

        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());

        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
        }

        Db db = new Db(c);
        db.migrate();

        return db;
    }

    /** */
    private void migrate() throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            int v = 0;

            try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
                if (rs.next())
                    v = rs.getInt(1);
            }

            if (v < 1) {
                st.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS commits (sha TEXT PRIMARY KEY, parent_sha TEXT, author TEXT, "
                    + "committed_at TEXT, subject TEXT, seq INTEGER, branch TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS commits_branch_seq ON commits(branch, seq)");
                st.execute("CREATE TABLE IF NOT EXISTS builds (sha TEXT PRIMARY KEY, status TEXT NOT NULL, started_at "
                    + "TEXT, finished_at TEXT, duration_sec REAL, libs_dir TEXT, log_file TEXT, error TEXT, "
                    + "ignite_version TEXT, size_bytes INTEGER, last_used_at TEXT, fake INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE TABLE IF NOT EXISTS jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, "
                    + "source TEXT NOT NULL, status TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 0, scenario TEXT "
                    + "NOT NULL, scenario_hash TEXT, ref_a TEXT, sha_a TEXT, ref_b TEXT, sha_b TEXT, label TEXT, "
                    + "created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT, stage TEXT, progress INTEGER NOT "
                    + "NULL DEFAULT 0, error TEXT, cancel_requested INTEGER NOT NULL DEFAULT 0, log_file TEXT, "
                    + "comparison_json TEXT, verdict TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS jobs_status ON jobs(status, priority, id)");
                st.execute("CREATE INDEX IF NOT EXISTS jobs_sha_a ON jobs(sha_a)");
                st.execute("CREATE INDEX IF NOT EXISTS jobs_sha_b ON jobs(sha_b)");
                st.execute("CREATE TABLE IF NOT EXISTS runs (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "job_id INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE, phase TEXT NOT NULL, sha TEXT "
                    + "NOT NULL, seq INTEGER NOT NULL, status TEXT NOT NULL, started_at TEXT, finished_at TEXT, dir "
                    + "TEXT NOT NULL, error TEXT, ignite_version TEXT, wall_sec REAL, throughput REAL, p50_us REAL, "
                    + "p99_us REAL, p999_us REAL, gc_pause_pct REAL, gc_max_pause_ms REAL, alloc_mb_per_sec REAL, "
                    + "server_cpu_pct REAL, machine_cpu_pct REAL, heap_after_gc_mb REAL, errors INTEGER)");
                st.execute("CREATE INDEX IF NOT EXISTS runs_job ON runs(job_id, seq)");
            }

            if (v < 2) {
                // automatic bisection: the state lives in its row, the steps are ordinary jobs pointing at it
                st.execute("CREATE TABLE IF NOT EXISTS bisects (id INTEGER PRIMARY KEY AUTOINCREMENT, scenario TEXT "
                    + "NOT NULL, metric TEXT NOT NULL, direction TEXT NOT NULL, root_job INTEGER NOT NULL, sha_a TEXT "
                    + "NOT NULL, sha_b TEXT NOT NULL, priority INTEGER NOT NULL, status TEXT NOT NULL, reason TEXT, "
                    + "result_sha TEXT, created_at TEXT NOT NULL, finished_at TEXT, state_json TEXT NOT NULL)");
                st.execute("ALTER TABLE jobs ADD COLUMN bisect_id INTEGER");
                st.execute("ALTER TABLE jobs ADD COLUMN bisect_step INTEGER");
                st.execute("CREATE INDEX IF NOT EXISTS jobs_bisect ON jobs(bisect_id)");
            }

            if (v < 3) {
                // two refs over a set of scenarios: the row is the question, the state is in its jobs
                st.execute("CREATE TABLE IF NOT EXISTS comparisons (id INTEGER PRIMARY KEY AUTOINCREMENT, ref_a TEXT, "
                    + "sha_a TEXT NOT NULL, ref_b TEXT, sha_b TEXT NOT NULL, label TEXT, priority INTEGER NOT NULL, "
                    + "created_at TEXT NOT NULL)");
                st.execute("ALTER TABLE jobs ADD COLUMN comparison_id INTEGER");
                st.execute("CREATE INDEX IF NOT EXISTS jobs_comparison ON jobs(comparison_id)");
            }

            if (v < SCHEMA_VERSION) {
                st.execute("DELETE FROM schema_version");
                st.execute("INSERT INTO schema_version VALUES (" + SCHEMA_VERSION + ")");
            }
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized void close() throws SQLException {
        c.close();
    }

    /**
     * Returns the current time.
     *
     * @return Current time in ISO-8601 format.
     */
    public static String now() {
        return Instant.now().toString();
    }

    // ---------------------------------------------------------------- settings

    /**
     * Reads a setting.
     *
     * @param key Setting key.
     * @param def Value to return if the setting is not set.
     * @return Setting value, or the default.
     */
    public synchronized String setting(String key, String def) {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            ps.setString(1, key);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : def;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Writes a setting.
     *
     * @param key Setting key.
     * @param value Setting value.
     */
    public synchronized void setSetting(String key, String value) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Writes a setting unless it is already set.
     *
     * @param key Setting key.
     * @param value Setting value.
     */
    public synchronized void setSettingIfAbsent(String key, String value) {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO settings(key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads all settings.
     *
     * @return Setting values by key.
     */
    public synchronized Map<String, String> settings() {
        Map<String, String> m = new LinkedHashMap<>();

        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT key, value FROM settings ORDER "
            + "BY key")) {
            while (rs.next())
                m.put(rs.getString(1), rs.getString(2));

            return m;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    // ---------------------------------------------------------------- commits

    /**
     * Upserts many commits in one transaction (one fsync instead of one per row).
     *
     * @param list Commits.
     */
    public synchronized void upsertCommits(List<CommitInfo> list) {
        try {
            c.setAutoCommit(false);

            try {
                for (CommitInfo ci : list)
                    upsertCommit(ci);

                c.commit();
            }
            catch (RuntimeException e) {
                c.rollback();

                throw e;
            }
            finally {
                c.setAutoCommit(true);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Inserts a commit or updates it if it exists.
     *
     * @param ci Commit.
     */
    public synchronized void upsertCommit(CommitInfo ci) {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO commits(sha, parent_sha, author, committed_at, "
            + "subject, seq, branch) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(sha) DO UPDATE SET parent_sha = "
            + "excluded.parent_sha, author = excluded.author, committed_at = excluded.committed_at, subject = "
            + "excluded.subject, seq = COALESCE(excluded.seq, commits.seq), branch = COALESCE(excluded.branch, "
            + "commits.branch)")) {
            ps.setString(1, ci.sha);
            ps.setString(2, ci.parentSha);
            ps.setString(3, ci.author);
            ps.setString(4, ci.committedAt);
            ps.setString(5, ci.subject);

            if (ci.seq == null)
                ps.setNull(6, java.sql.Types.INTEGER);
            else
                ps.setInt(6, ci.seq);

            ps.setString(7, ci.branch);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads the history of a branch, newest first.
     *
     * @param branch Branch name.
     * @param limit Maximum number of commits.
     * @return Commits.
     */
    public synchronized List<CommitInfo> commits(String branch, int limit) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM commits WHERE branch = ? AND seq IS NOT NULL ORDER BY seq DESC LIMIT ?")) {
            ps.setString(1, branch);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<CommitInfo> out = new ArrayList<>();

                while (rs.next())
                    out.add(commitOf(rs));

                return out;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads a commit.
     *
     * @param sha Commit sha.
     * @return The commit, or {@code null} if there is none.
     */
    public synchronized CommitInfo commit(String sha) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM commits WHERE sha = ?")) {
            ps.setString(1, sha);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? commitOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Commits of the history whose sha starts with {@code text} or whose subject holds it, newest first.
     *
     * @param text Sha prefix or subject text.
     * @param limit Maximum number of commits.
     * @return Matching commits.
     */
    public synchronized List<CommitInfo> searchCommits(String text, int limit) {
        String like = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM commits WHERE sha LIKE ? ESCAPE '\\' OR subject "
            + "LIKE ? ESCAPE '\\' ORDER BY seq IS NULL, seq DESC LIMIT ?")) {
            ps.setString(1, like + "%");
            ps.setString(2, "%" + like + "%");
            ps.setInt(3, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<CommitInfo> out = new ArrayList<>();

                while (rs.next())
                    out.add(commitOf(rs));

                return out;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** */
    private static CommitInfo commitOf(ResultSet rs) throws SQLException {
        CommitInfo ci = new CommitInfo();
        ci.sha = rs.getString("sha");
        ci.parentSha = rs.getString("parent_sha");
        ci.author = rs.getString("author");
        ci.committedAt = rs.getString("committed_at");
        ci.subject = rs.getString("subject");
        int seq = rs.getInt("seq");
        ci.seq = rs.wasNull() ? null : seq;
        ci.branch = rs.getString("branch");

        return ci;
    }

    // ---------------------------------------------------------------- builds

    /**
     * Shas with a successful build record (artifacts may still have been evicted by LRU since).
     *
     * @return Shas of successful builds.
     */
    public synchronized java.util.Set<String> okBuildShas() {
        java.util.Set<String> out = new java.util.HashSet<>();

        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT sha FROM builds WHERE status = "
            + "'ok'")) {
            while (rs.next())
                out.add(rs.getString(1));
        }
        catch (SQLException e) {
            throw wrap(e);
        }

        return out;
    }

    /**
     * Reads a build record.
     *
     * @param sha Build key.
     * @return The build, or {@code null} if there is none.
     */
    public synchronized Build build(String sha) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM builds WHERE sha = ?")) {
            ps.setString(1, sha);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? buildOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads build records, newest first.
     *
     * @param limit Maximum number of builds.
     * @return Builds.
     */
    public synchronized List<Build> builds(int limit) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM builds ORDER BY started_at DESC LIMIT ?")) {
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<Build> out = new ArrayList<>();

                while (rs.next())
                    out.add(buildOf(rs));

                return out;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Inserts a build record or updates it if it exists.
     *
     * @param b Build.
     */
    public synchronized void saveBuild(Build b) {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO builds(sha, status, started_at, finished_at, "
            + "duration_sec, libs_dir, log_file, error, ignite_version, size_bytes, last_used_at, fake) VALUES (?, ?, "
            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(sha) DO UPDATE SET status = excluded.status, started_at = "
            + "excluded.started_at, finished_at = excluded.finished_at, duration_sec = excluded.duration_sec, libs_dir "
            + "= excluded.libs_dir, log_file = excluded.log_file, error = excluded.error, ignite_version = "
            + "excluded.ignite_version, size_bytes = excluded.size_bytes, last_used_at = excluded.last_used_at, fake = "
            + "excluded.fake")) {
            ps.setString(1, b.sha);
            ps.setString(2, b.status);
            ps.setString(3, b.startedAt);
            ps.setString(4, b.finishedAt);
            setDouble(ps, 5, b.durationSec);
            ps.setString(6, b.libsDir);
            ps.setString(7, b.logFile);
            ps.setString(8, b.error);
            ps.setString(9, b.igniteVersion);
            setLong(ps, 10, b.sizeBytes);
            ps.setString(11, b.lastUsedAt);
            ps.setInt(12, b.fake ? 1 : 0);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Deletes a build record.
     *
     * @param sha Build key.
     */
    public synchronized void deleteBuild(String sha) {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM builds WHERE sha = ?")) {
            ps.setString(1, sha);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Builds left in {@code building} by a crashed controller.
     *
     * @return Number of builds marked failed.
     */
    public synchronized int failInterruptedBuilds() {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE builds SET status = ?, error = 'controller restarted during the build', finished_at = ? WHERE "
            + "status = ?")) {
            ps.setString(1, Build.FAILED);
            ps.setString(2, now());
            ps.setString(3, Build.BUILDING);

            return ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** */
    private static Build buildOf(ResultSet rs) throws SQLException {
        Build b = new Build();
        b.sha = rs.getString("sha");
        b.status = rs.getString("status");
        b.startedAt = rs.getString("started_at");
        b.finishedAt = rs.getString("finished_at");
        b.durationSec = getDouble(rs, "duration_sec");
        b.libsDir = rs.getString("libs_dir");
        b.logFile = rs.getString("log_file");
        b.error = rs.getString("error");
        b.igniteVersion = rs.getString("ignite_version");
        b.sizeBytes = getLong(rs, "size_bytes");
        b.lastUsedAt = rs.getString("last_used_at");
        b.fake = rs.getInt("fake") != 0;

        return b;
    }

    // ---------------------------------------------------------------- jobs

    /**
     * Inserts a new job.
     *
     * @param j Job.
     * @return The job with its id.
     */
    public synchronized Job insertJob(Job j) {
        if (j.createdAt == null)
            j.createdAt = now();

        if (j.status == null)
            j.status = Job.QUEUED;

        try (PreparedStatement ps = c.prepareStatement("INSERT INTO jobs(type, source, status, priority, scenario, "
            + "scenario_hash, ref_a, sha_a, ref_b, sha_b, label, created_at, stage, progress, bisect_id, bisect_step, "
            + "comparison_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, j.type);
            ps.setString(2, j.source);
            ps.setString(3, j.status);
            ps.setInt(4, j.priority);
            ps.setString(5, j.scenario);
            ps.setString(6, j.scenarioHash);
            ps.setString(7, j.refA);
            ps.setString(8, j.shaA);
            ps.setString(9, j.refB);
            ps.setString(10, j.shaB);
            ps.setString(11, j.label);
            ps.setString(12, j.createdAt);
            ps.setString(13, j.stage);
            setLong(ps, 14, j.bisectId);
            setLong(ps, 15, j.bisectStep == null ? null : (long)j.bisectStep);
            setLong(ps, 16, j.comparisonId);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                j.id = rs.getLong(1);
            }

            return j;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Writes the state of a job.
     *
     * @param j Job.
     */
    public synchronized void updateJob(Job j) {
        try (PreparedStatement ps = c.prepareStatement("UPDATE jobs SET status = ?, priority = ?, scenario_hash = ?, "
            + "started_at = ?, finished_at = ?, stage = ?, progress = ?, error = ?, log_file = ?, comparison_json = ?, "
            + "verdict = ?, label = ? WHERE id = ?")) {
            ps.setString(1, j.status);
            ps.setInt(2, j.priority);
            ps.setString(3, j.scenarioHash);
            ps.setString(4, j.startedAt);
            ps.setString(5, j.finishedAt);
            ps.setString(6, j.stage);
            ps.setInt(7, j.progress);
            ps.setString(8, j.error);
            ps.setString(9, j.logFile);
            ps.setString(10, j.comparison == null ? null : j.comparison.toString());
            ps.setString(11, j.verdict);
            ps.setString(12, j.label);
            ps.setLong(13, j.id);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads a job.
     *
     * @param id Job id.
     * @return The job, or {@code null} if there is none.
     */
    public synchronized Job job(long id) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM jobs WHERE id = ?")) {
            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? jobOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Queue order: running first, then queued by priority (high first) and id; or history (newest first).
     *
     * @param filter Queue filter, or history otherwise.
     * @param limit Maximum number of jobs.
     * @return Jobs.
     */
    public synchronized List<Job> jobs(String filter, int limit) {
        String sql;

        if ("active".equals(filter))
            sql = "SELECT * FROM jobs WHERE status IN ('running', 'queued') ORDER BY status = 'running' DESC, priority "
                + "DESC, id ASC LIMIT ?";
        else if ("history".equals(filter))
            sql = "SELECT * FROM jobs WHERE status NOT IN ('running', 'queued') ORDER BY id DESC LIMIT ?";
        else if ("history-no-steps".equals(filter)) // what people queued: without the steps of bisections
            sql = "SELECT * FROM jobs WHERE status NOT IN ('running', 'queued') AND source <> 'bisect' ORDER BY id "
                + "DESC LIMIT ?";
        else
            sql = "SELECT * FROM jobs ORDER BY id DESC LIMIT ?";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                return jobsOf(rs);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads the queued job to run next: the highest priority, then the oldest.
     *
     * @return The job, or {@code null} if none is queued.
     */
    public synchronized Job nextQueued() {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM jobs WHERE status = 'queued' ORDER BY priority DESC, id ASC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? jobOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads the running job.
     *
     * @return The job, or {@code null} if none runs.
     */
    public synchronized Job runningJob() {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM jobs WHERE status = 'running' ORDER BY id LIMIT "
            + "1")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? jobOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Marks a job for cancellation; a queued job is cancelled right away. Returns false if there is nothing to cancel.
     *
     * @param id Job id.
     * @return Whether there was something to cancel.
     */
    public synchronized boolean requestCancel(long id) {
        try (PreparedStatement q = c.prepareStatement("UPDATE jobs SET status = 'cancelled', finished_at = ?, "
            + "cancel_requested = 1 WHERE id = ? AND status = 'queued'");
             PreparedStatement r = c.prepareStatement("UPDATE jobs SET cancel_requested = 1 WHERE id = ? AND status = "
                 + "'running'")) {
            q.setString(1, now());
            q.setLong(2, id);

            if (q.executeUpdate() > 0)
                return true;

            r.setLong(1, id);

            return r.executeUpdate() > 0;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Tells whether cancellation of a job is requested.
     *
     * @param id Job id.
     * @return Whether cancellation is requested.
     */
    public synchronized boolean cancelRequested(long id) {
        try (PreparedStatement ps = c.prepareStatement("SELECT cancel_requested FROM jobs WHERE id = ?")) {
            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Deletes a finished job and (by cascade) its runs; false if it does not exist or is still queued/running.
     *
     * @param id Job id.
     * @return Whether the job was deleted.
     */
    public synchronized boolean deleteJob(long id) {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM jobs WHERE id = ? AND status NOT IN ('queued', "
            + "'running')")) {
            ps.setLong(1, id);

            return ps.executeUpdate() > 0;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Changes the priority of a queued job.
     *
     * @param id Job id.
     * @param priority New priority.
     * @return Whether the job was queued and changed.
     */
    public synchronized boolean setPriority(long id, int priority) {
        try (PreparedStatement ps = c.prepareStatement("UPDATE jobs SET priority = ? WHERE id = ? AND status = "
            + "'queued'")) {
            ps.setInt(1, priority);
            ps.setLong(2, id);

            return ps.executeUpdate() > 0;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Jobs left {@code running} by a crashed or restarted controller.
     *
     * @return Ids of the interrupted jobs.
     */
    public synchronized List<Long> markInterrupted() {
        List<Long> ids = new ArrayList<>();

        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM jobs WHERE status = "
            + "'running'")) {
            while (rs.next())
                ids.add(rs.getLong(1));
        }
        catch (SQLException e) {
            throw wrap(e);
        }

        if (ids.isEmpty())
            return ids;

        try (PreparedStatement ps = c.prepareStatement("UPDATE jobs SET status = 'interrupted', finished_at = ?, "
            + "error = COALESCE(error, 'controller restarted while the job was running') WHERE status = 'running'")) {
            ps.setString(1, now());
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }

        return ids;
    }

    /**
     * Tells whether a queued or running job of exactly this kind exists.
     *
     * @param type Job type.
     * @param shaA Sha of A.
     * @param shaB Sha of B, or {@code null}.
     * @param scenario Scenario name.
     * @return Whether such a job exists.
     */
    public synchronized boolean hasActiveJob(String type, String shaA, String shaB, String scenario) {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM jobs WHERE status IN ('queued', 'running') AND "
            + "type = ? AND sha_a = ? AND COALESCE(sha_b, '') = COALESCE(?, '') AND scenario = ? LIMIT 1")) {
            ps.setString(1, type);
            ps.setString(2, shaA);
            ps.setString(3, shaB);
            ps.setString(4, scenario);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Jobs that measured any of the given commits (as B of an ABBA job or as the version of a single job).
     *
     * @param sha Commit sha.
     * @return Jobs, newest first.
     */
    public synchronized List<Job> jobsTouching(String sha) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM jobs WHERE sha_a = ? OR sha_b = ? ORDER BY id "
            + "DESC")) {
            ps.setString(1, sha);
            ps.setString(2, sha);

            try (ResultSet rs = ps.executeQuery()) {
                List<Job> out = new ArrayList<>();

                while (rs.next())
                    out.add(jobOf(rs));

                return out;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads the jobs that measured any of the commits, newest first.
     *
     * @param shas Commit shas.
     * @param scenario Scenario name, or {@code null} for all.
     * @return Jobs.
     */
    public synchronized List<Job> jobsForShas(Collection<String> shas, String scenario) {
        if (shas.isEmpty())
            return List.of();

        String marks = String.join(",", java.util.Collections.nCopies(shas.size(), "?"));
        String sql = "SELECT * FROM jobs WHERE (sha_b IN (" + marks + ") OR (type = 'single' AND sha_a IN (" + marks
            + ")))" + (scenario == null ? "" : " AND scenario = ?") + " ORDER BY id DESC";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;

            for (String s : shas)
                ps.setString(i++, s);

            for (String s : shas)
                ps.setString(i++, s);

            if (scenario != null)
                ps.setString(i, scenario);

            try (ResultSet rs = ps.executeQuery()) {
                return jobsOf(rs);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Finished jobs of a scenario, newest first; {@code type} and {@code source} are optional filters.
     *
     * @param scenario Scenario name.
     * @param type Job type, or {@code null} for all.
     * @param source Job source, or {@code null} for all.
     * @param limit Maximum number of jobs.
     * @return Finished jobs.
     */
    public synchronized List<Job> doneJobs(String scenario, String type, String source, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM jobs WHERE status = 'done'");
        List<Object> args = new ArrayList<>();

        if (scenario != null) {
            sql.append(" AND scenario = ?");
            args.add(scenario);
        }

        if (type != null) {
            sql.append(" AND type = ?");
            args.add(type);
        }

        if (source != null) {
            sql.append(" AND source = ?");
            args.add(source);
        }

        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);

        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++)
                ps.setObject(i + 1, args.get(i));

            try (ResultSet rs = ps.executeQuery()) {
                return jobsOf(rs);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Rows of a table, for the limits on the Settings page.
     *
     * @param table Table name.
     * @return Number of rows.
     */
    public synchronized int count(String table) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.getInt(1);
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Whether a phase has finished: from then on the stand is part of the history. The probes of Measure load are not.
     *
     * @return Whether a phase has finished.
     */
    public synchronized boolean anyPhaseFinished() {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM runs r JOIN jobs j ON j.id = r.job_id WHERE r.status = ? AND j.type <> ? LIMIT 1")) {
            ps.setString(1, Run.OK);
            ps.setString(2, Job.PROBE);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** Ended bisections: the only ones the limit on the Settings page applies to. */
    /**
     * The jobs that go with their comparison.
     *
     * @return Job ids.
     */
    public synchronized java.util.Set<Long> comparisonJobIds() {
        java.util.Set<Long> out = new java.util.HashSet<>();

        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM jobs WHERE "
            + "comparison_id IS NOT NULL")) {
            while (rs.next())
                out.add(rs.getLong(1));

            return out;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * About what the ended bisections take in the database: the state is nearly all of a row.
     *
     * @return Size in bytes.
     */
    public synchronized long endedBisectBytes() {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(LENGTH(state_json)), 0) FROM bisects WHERE "
            + "status NOT IN (?, ?)")) {
            ps.setString(1, Bisect.RUNNING);
            ps.setString(2, Bisect.STOPPED);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.getLong(1);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Counts the ended bisections: all but the running and stopped ones.
     *
     * @return Number of ended bisections.
     */
    public synchronized int endedBisects() {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bisects WHERE status NOT IN (?, ?)")) {
            ps.setString(1, Bisect.RUNNING);
            ps.setString(2, Bisect.STOPPED);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.getInt(1);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Counts the jobs by status.
     *
     * @return Number of jobs by status.
     */
    public synchronized Map<String, Integer> jobCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();

        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT status, COUNT(*) FROM jobs GROUP BY status")) {
            while (rs.next())
                m.put(rs.getString(1), rs.getInt(2));

            return m;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** */
    private static List<Job> jobsOf(ResultSet rs) throws SQLException {
        List<Job> out = new ArrayList<>();

        while (rs.next())
            out.add(jobOf(rs));

        return out;
    }

    /** */
    private static Job jobOf(ResultSet rs) throws SQLException {
        Job j = new Job();
        j.id = rs.getLong("id");
        j.type = rs.getString("type");
        j.source = rs.getString("source");
        j.status = rs.getString("status");
        j.priority = rs.getInt("priority");
        j.scenario = rs.getString("scenario");
        j.scenarioHash = rs.getString("scenario_hash");
        j.refA = rs.getString("ref_a");
        j.shaA = rs.getString("sha_a");
        j.refB = rs.getString("ref_b");
        j.shaB = rs.getString("sha_b");
        j.label = rs.getString("label");
        j.createdAt = rs.getString("created_at");
        j.startedAt = rs.getString("started_at");
        j.finishedAt = rs.getString("finished_at");
        j.stage = rs.getString("stage");
        j.progress = rs.getInt("progress");
        j.error = rs.getString("error");
        j.cancelRequested = rs.getInt("cancel_requested") != 0;
        j.logFile = rs.getString("log_file");
        j.comparison = Json.tree(rs.getString("comparison_json"));
        j.verdict = rs.getString("verdict");
        j.bisectId = getLong(rs, "bisect_id");
        Long step = getLong(rs, "bisect_step");
        j.bisectStep = step == null ? null : step.intValue();
        j.comparisonId = getLong(rs, "comparison_id");

        return j;
    }

    /**
     * Every job queued by a bisection, oldest first.
     *
     * @param bisectId Bisection id.
     * @return Jobs.
     */
    public synchronized List<Job> jobsOfBisect(long bisectId) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM jobs WHERE bisect_id = ? ORDER BY id")) {
            ps.setLong(1, bisectId);

            try (ResultSet rs = ps.executeQuery()) {
                return jobsOf(rs);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Every job of a comparison, oldest first.
     *
     * @param comparisonId Comparison id.
     * @return Jobs.
     */
    public synchronized List<Job> jobsOfComparison(long comparisonId) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM jobs WHERE comparison_id = ? ORDER BY id")) {
            ps.setLong(1, comparisonId);

            try (ResultSet rs = ps.executeQuery()) {
                return jobsOf(rs);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Finished ABBA jobs of exactly this pair and scenario, oldest first.
     *
     * @param shaA Sha of A.
     * @param shaB Sha of B.
     * @param scenario Scenario name.
     * @return Finished jobs.
     */
    public synchronized List<Job> doneJobsOfPair(String shaA, String shaB, String scenario) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM jobs WHERE status = 'done' AND type = 'abba' AND sha_a = ? AND sha_b = ? AND scenario = ? "
            + "ORDER BY id")) {
            ps.setString(1, shaA);
            ps.setString(2, shaB);
            ps.setString(3, scenario);

            try (ResultSet rs = ps.executeQuery()) {
                return jobsOf(rs);
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * The queued or running job of exactly this pair, if any.
     *
     * @param shaA Sha of A.
     * @param shaB Sha of B.
     * @param scenario Scenario name.
     * @return The job, or {@code null} if there is none.
     */
    public synchronized Job activeJobOfPair(String shaA, String shaB, String scenario) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM jobs WHERE status IN ('queued', 'running') AND "
            + "type = 'abba' AND sha_a = ? AND sha_b = ? AND scenario = ? ORDER BY id LIMIT 1")) {
            ps.setString(1, shaA);
            ps.setString(2, shaB);
            ps.setString(3, scenario);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? jobOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    // ---------------------------------------------------------------- bisects

    /**
     * Inserts a new bisection.
     *
     * @param b Bisection.
     * @return The bisection with its id.
     */
    public synchronized Bisect insertBisect(Bisect b) {
        if (b.createdAt == null)
            b.createdAt = now();

        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bisects(scenario, metric, direction, root_job, "
            + "sha_a, sha_b, priority, status, reason, result_sha, created_at, finished_at, state_json) VALUES (?, ?, "
            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, b.scenario);
            ps.setString(2, b.metric);
            ps.setString(3, b.direction);
            ps.setLong(4, b.rootJob);
            ps.setString(5, b.shaA);
            ps.setString(6, b.shaB);
            ps.setInt(7, b.priority);
            ps.setString(8, b.status);
            ps.setString(9, b.reason);
            ps.setString(10, b.resultSha);
            ps.setString(11, b.createdAt);
            ps.setString(12, b.finishedAt);
            ps.setString(13, Json.write(b.state));
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                b.id = rs.getLong(1);
            }

            return b;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Writes the state of a bisection.
     *
     * @param b Bisection.
     */
    public synchronized void updateBisect(Bisect b) {
        try (PreparedStatement ps = c.prepareStatement("UPDATE bisects SET priority = ?, status = ?, reason = ?, "
            + "result_sha = ?, finished_at = ?, state_json = ? WHERE id = ?")) {
            ps.setInt(1, b.priority);
            ps.setString(2, b.status);
            ps.setString(3, b.reason);
            ps.setString(4, b.resultSha);
            ps.setString(5, b.finishedAt);
            ps.setString(6, Json.write(b.state));
            ps.setLong(7, b.id);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads a bisection.
     *
     * @param id Bisection id.
     * @return The bisection, or {@code null} if there is none.
     */
    public synchronized Bisect bisect(long id) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM bisects WHERE id = ?")) {
            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? bisectOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Newest first; {@code scenario} is optional, {@code activeOnly} keeps the running and stopped ones.
     *
     * @param scenario Scenario name, or {@code null} for all.
     * @param activeOnly Whether to keep only the running and stopped ones.
     * @param limit Maximum number of bisections.
     * @return Bisections.
     */
    public synchronized List<Bisect> bisects(String scenario, boolean activeOnly, int limit) {
        String sql = "SELECT * FROM bisects WHERE 1 = 1" + (scenario == null ? "" : " AND scenario = ?")
            + (activeOnly ? " AND status IN ('running', 'stopped')" : "") + " ORDER BY id DESC LIMIT ?";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;

            if (scenario != null)
                ps.setString(i++, scenario);

            ps.setInt(i, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<Bisect> out = new ArrayList<>();

                while (rs.next())
                    out.add(bisectOf(rs));

                return out;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Removes a bisection; its steps stay as ordinary runs of the branch.
     *
     * @param id Bisection id.
     */
    public synchronized void deleteBisect(long id) {
        try (PreparedStatement steps = c.prepareStatement("UPDATE jobs SET bisect_id = NULL, bisect_step = NULL WHERE "
            + "bisect_id = ?");
            PreparedStatement ps = c.prepareStatement("DELETE FROM bisects WHERE id = ?")) {
            steps.setLong(1, id);
            steps.executeUpdate();
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * The bisections started from the jobs of a comparison, oldest first.
     *
     * @param comparisonId Comparison id.
     * @return Bisections.
     */
    public synchronized List<Bisect> bisectsOfComparison(long comparisonId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM bisects WHERE root_job IN (SELECT id FROM jobs WHERE comparison_id = ?) ORDER BY id")) {
            ps.setLong(1, comparisonId);

            try (ResultSet rs = ps.executeQuery()) {
                List<Bisect> out = new ArrayList<>();

                while (rs.next())
                    out.add(bisectOf(rs));

                return out;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** */
    private static Bisect bisectOf(ResultSet rs) throws SQLException {
        Bisect b = new Bisect();
        b.id = rs.getLong("id");
        b.scenario = rs.getString("scenario");
        b.metric = rs.getString("metric");
        b.direction = rs.getString("direction");
        b.rootJob = rs.getLong("root_job");
        b.shaA = rs.getString("sha_a");
        b.shaB = rs.getString("sha_b");
        b.priority = rs.getInt("priority");
        b.status = rs.getString("status");
        b.reason = rs.getString("reason");
        b.resultSha = rs.getString("result_sha");
        b.createdAt = rs.getString("created_at");
        b.finishedAt = rs.getString("finished_at");
        b.state = Json.read(rs.getString("state_json"), Bisect.State.class);

        return b;
    }

    // ---------------------------------------------------------------- comparisons

    /**
     * Inserts a new comparison.
     *
     * @param x Comparison.
     * @return The comparison with its id.
     */
    public synchronized Comparison insertComparison(Comparison x) {
        if (x.createdAt == null)
            x.createdAt = now();

        try (PreparedStatement ps = c.prepareStatement("INSERT INTO comparisons(ref_a, sha_a, ref_b, sha_b, label, "
            + "priority, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, x.refA);
            ps.setString(2, x.shaA);
            ps.setString(3, x.refB);
            ps.setString(4, x.shaB);
            ps.setString(5, x.label);
            ps.setInt(6, x.priority);
            ps.setString(7, x.createdAt);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                x.id = rs.getLong(1);
            }

            return x;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads a comparison.
     *
     * @param id Comparison id.
     * @return The comparison, or {@code null} if there is none.
     */
    public synchronized Comparison comparison(long id) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM comparisons WHERE id = ?")) {
            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? comparisonOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Newest first.
     *
     * @param limit Maximum number of comparisons.
     * @return Comparisons.
     */
    public synchronized List<Comparison> comparisons(int limit) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM comparisons ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<Comparison> out = new ArrayList<>();

                while (rs.next())
                    out.add(comparisonOf(rs));

                return out;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Deletes a comparison.
     *
     * @param id Comparison id.
     */
    public synchronized void deleteComparison(long id) {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM comparisons WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** */
    private static Comparison comparisonOf(ResultSet rs) throws SQLException {
        Comparison x = new Comparison();
        x.id = rs.getLong("id");
        x.refA = rs.getString("ref_a");
        x.shaA = rs.getString("sha_a");
        x.refB = rs.getString("ref_b");
        x.shaB = rs.getString("sha_b");
        x.label = rs.getString("label");
        x.priority = rs.getInt("priority");
        x.createdAt = rs.getString("created_at");

        return x;
    }

    // ---------------------------------------------------------------- runs

    /**
     * Inserts a new run.
     *
     * @param r Run.
     * @return The run with its id.
     */
    public synchronized Run insertRun(Run r) {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO runs(job_id, phase, sha, seq, status, started_at, "
            + "dir) VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, r.jobId);
            ps.setString(2, r.phase);
            ps.setString(3, r.sha);
            ps.setInt(4, r.seq);
            ps.setString(5, r.status);
            ps.setString(6, r.startedAt);
            ps.setString(7, r.dir);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                r.id = rs.getLong(1);
            }

            return r;
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Writes the state of a run.
     *
     * @param r Run.
     */
    public synchronized void updateRun(Run r) {
        try (PreparedStatement ps = c.prepareStatement("UPDATE runs SET status = ?, finished_at = ?, error = ?, "
            + "ignite_version = ?, wall_sec = ?, throughput = ?, p50_us = ?, p99_us = ?, p999_us = ?, gc_pause_pct = "
            + "?, gc_max_pause_ms = ?, alloc_mb_per_sec = ?, server_cpu_pct = ?, machine_cpu_pct = ?, heap_after_gc_mb "
            + "= ?, errors = ? WHERE id = ?")) {
            ps.setString(1, r.status);
            ps.setString(2, r.finishedAt);
            ps.setString(3, r.error);
            ps.setString(4, r.igniteVersion);
            setDouble(ps, 5, r.wallSec);
            setDouble(ps, 6, r.throughput);
            setDouble(ps, 7, r.p50Us);
            setDouble(ps, 8, r.p99Us);
            setDouble(ps, 9, r.p999Us);
            setDouble(ps, 10, r.gcPausePct);
            setDouble(ps, 11, r.gcMaxPauseMs);
            setDouble(ps, 12, r.allocMbPerSec);
            setDouble(ps, 13, r.serverCpuPct);
            setDouble(ps, 14, r.machineCpuPct);
            setDouble(ps, 15, r.heapAfterGcMb);
            setLong(ps, 16, r.errors);
            ps.setLong(17, r.id);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads the runs of a job in order.
     *
     * @param jobId Job id.
     * @return Runs.
     */
    public synchronized List<Run> runs(long jobId) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM runs WHERE job_id = ? ORDER BY seq")) {
            ps.setLong(1, jobId);

            try (ResultSet rs = ps.executeQuery()) {
                List<Run> out = new ArrayList<>();

                while (rs.next())
                    out.add(runOf(rs));

                return out;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Reads a run.
     *
     * @param id Run id.
     * @return The run, or {@code null} if there is none.
     */
    public synchronized Run run(long id) {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM runs WHERE id = ?")) {
            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? runOf(rs) : null;
            }
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /**
     * Marks the runs left running by a restarted controller as failed.
     *
     * @return Number of runs marked failed.
     */
    public synchronized int failRunningRuns() {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE runs SET status = 'failed', finished_at = ?, error = 'controller restarted during the phase' WHERE "
            + "status = 'running'")) {
            ps.setString(1, now());

            return ps.executeUpdate();
        }
        catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** */
    private static Run runOf(ResultSet rs) throws SQLException {
        Run r = new Run();
        r.id = rs.getLong("id");
        r.jobId = rs.getLong("job_id");
        r.phase = rs.getString("phase");
        r.sha = rs.getString("sha");
        r.seq = rs.getInt("seq");
        r.status = rs.getString("status");
        r.startedAt = rs.getString("started_at");
        r.finishedAt = rs.getString("finished_at");
        r.dir = rs.getString("dir");
        r.error = rs.getString("error");
        r.igniteVersion = rs.getString("ignite_version");
        r.wallSec = getDouble(rs, "wall_sec");
        r.throughput = getDouble(rs, "throughput");
        r.p50Us = getDouble(rs, "p50_us");
        r.p99Us = getDouble(rs, "p99_us");
        r.p999Us = getDouble(rs, "p999_us");
        r.gcPausePct = getDouble(rs, "gc_pause_pct");
        r.gcMaxPauseMs = getDouble(rs, "gc_max_pause_ms");
        r.allocMbPerSec = getDouble(rs, "alloc_mb_per_sec");
        r.serverCpuPct = getDouble(rs, "server_cpu_pct");
        r.machineCpuPct = getDouble(rs, "machine_cpu_pct");
        r.heapAfterGcMb = getDouble(rs, "heap_after_gc_mb");
        r.errors = getLong(rs, "errors");

        return r;
    }

    // ---------------------------------------------------------------- helpers

    /** */
    private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null)
            ps.setNull(i, java.sql.Types.REAL);
        else
            ps.setDouble(i, v);
    }

    /** */
    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null)
            ps.setNull(i, java.sql.Types.INTEGER);
        else
            ps.setLong(i, v);
    }

    /** */
    private static Double getDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);

        return rs.wasNull() ? null : v;
    }

    /** */
    private static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);

        return rs.wasNull() ? null : v;
    }

    /** */
    private static IllegalStateException wrap(SQLException e) {
        return new IllegalStateException("SQLite: " + e.getMessage(), e);
    }
}
