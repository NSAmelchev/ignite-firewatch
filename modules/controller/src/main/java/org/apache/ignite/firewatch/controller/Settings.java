package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run-time settings stored in the database (changeable from the UI), with their defaults. */
public final class Settings {
    /** */
    public static final String AUTORUN_ENABLED = "autorun.enabled";

    /** {@code abba-vs-parent}: compare each new commit with its first parent; {@code single}: measure it alone. */
    public static final String AUTORUN_MODE = "autorun.mode";

    /** One or several comma-separated scenarios; every new commit gets a job per scenario. */
    public static final String AUTORUN_SCENARIO = "autorun.scenario";

    /** The main tracker: auto-run and the default scenario of every page. */
    public static final String DEFAULT_SCENARIO = "putget-thin-t16";

    /** Comma-separated globs; a commit is auto-run only if it changes at least one matching path. Empty = all. */
    public static final String AUTORUN_PATH_FILTER = "autorun.pathFilter";

    /** Auto jobs are not enqueued while this many auto jobs are already waiting. */
    public static final String AUTORUN_MAX_BACKLOG = "autorun.maxBacklog";

    /** */
    public static final String WATCH_POLL_SEC = "watch.pollSec";

    /** "Closed for maintenance": the worker finishes the current job and takes no new ones until resumed. */
    public static final String WORKER_PAUSED = "worker.paused";

    /** Last commit of the watched branch the watcher has processed. */
    public static final String WATCH_WATERMARK = "watch.watermark";

    /**
     * Keep JFR recordings and leftover work directories (the bulky part of a run) only for this many most recent jobs.
     */
    public static final String RETENTION_KEEP_JFR_JOBS = "retention.keepJfrJobs";

    /** Comparisons kept with their jobs and files, newest first; 0 = all. */
    public static final String RETENTION_KEEP_COMPARISONS = "retention.keepComparisons";

    /** Ended bisections kept, newest first; their steps stay as ordinary runs; 0 = all. */
    public static final String RETENTION_KEEP_BISECTS = "retention.keepBisects";

    /** Built artifacts are evicted (least recently used first) above this size. */
    public static final String RETENTION_ARTIFACTS_GB = "retention.artifactsGb";

    /** Settings whose values are secrets: the API returns a mask and keeps the old value when the mask comes back. */
    public static final Set<String> SECRET = Set.of(Notifier.TELEGRAM_TOKEN);

    /** */
    public static final String MASK = "••••••••";

    /** */
    public static final String DEFAULT_PATH_FILTER = String.join(", ",
        "modules/core/src/main/**", "modules/commons/src/main/**", "modules/binary/**/src/main/**",
        "modules/thin-client/**/src/main/**", "modules/nio/src/main/**", "modules/unsafe/src/main/**",
        "modules/codegen/src/main/**", "pom.xml", "**/pom.xml");

    /** segments: one ABBA "head vs the previous segment end" per batch of new commits, instead of one per commit. */
    public static final Set<String> MODES = Set.of("abba-vs-parent", "single", "segments");

    /** segments mode: how old the oldest commit not measured yet may get, see {@link Freshness}. */
    public static final String AUTORUN_KEEP_DAYS = "autorun.keepDays";

    /** Internal: end of the last queued segment, i.e. the start (A) of the next one. */
    public static final String WATCH_SEGMENT_BASE = "watch.segmentBase";

    /**
     * The repository and the branch the stand follows; {@code IGNITE_FIREWATCH_REPO_URL} and
     * {@code IGNITE_FIREWATCH_BRANCH} only seed them.
     */
    public static final String REPO_URL = "repo.url";

    /** */
    public static final String REPO_BRANCH = "repo.branch";

    /**
     * What the dashboard compares master with: a tag, sha or branch, a mask like 2.18.* for the highest X.Y.Z tag that
     * fits; empty = the highest one.
     */
    public static final String RELEASE_TAG = "release.tag";

    /**
     * What turns tickets and PR numbers in commit subjects into links: lines {@code KEY=URL [PR_URL]}. The ticket goes
     * at the end of the URL, the number of a PR of that space at the end of PR_URL, by default the pull requests of
     * {@link #REPO_URL}.
     */
    public static final String TICKET_LINKS = "links.tickets";

    /** */
    public static final String DEFAULT_TICKET_LINKS = "IGNITE=https://issues.apache.org/jira/browse/";

    /**
     * Builds the default settings.
     *
     * @param cfg Controller config.
     * @return Default values by setting key.
     */
    public static Map<String, String> defaults(Config cfg) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put(REPO_URL, cfg.repoUrl);
        d.put(REPO_BRANCH, cfg.branch);
        d.put(AUTORUN_ENABLED, "false");
        d.put(AUTORUN_MODE, "abba-vs-parent");
        d.put(AUTORUN_SCENARIO, DEFAULT_SCENARIO);
        d.put(AUTORUN_PATH_FILTER, DEFAULT_PATH_FILTER);
        d.put(AUTORUN_MAX_BACKLOG, "5");
        d.put(AUTORUN_KEEP_DAYS, "1");
        d.put(RELEASE_TAG, "");
        d.put(TICKET_LINKS, DEFAULT_TICKET_LINKS);
        d.put(WATCH_POLL_SEC, "3600");
        d.put(WORKER_PAUSED, "false");
        d.put(RETENTION_KEEP_JFR_JOBS, "30");
        d.put(RETENTION_KEEP_COMPARISONS, "0");
        d.put(RETENTION_KEEP_BISECTS, "0");
        d.put(RETENTION_ARTIFACTS_GB, "3");

        for (String p : List.of(Calibrator.AA, Calibrator.MASTER)) {
            d.put(p + ".mode", Freshness.OFF);
            d.put(p + ".keepDays", "7");
            d.put(p + ".scenarios", "");
        }

        d.put(Notifier.MODE, "off");
        d.put(Notifier.TELEGRAM_TOKEN, "");
        d.put(Notifier.TELEGRAM_CHAT, "");
        d.put(Notifier.WEBHOOK_URL, "");
        d.put(Notifier.BASE_URL, "");

        return d;
    }

    /**
     * Stores the default settings that are not set yet.
     *
     * @param db Database.
     * @param cfg Controller config.
     */
    public static void seed(Db db, Config cfg) {
        defaults(cfg).forEach(db::setSettingIfAbsent);
    }

    /**
     * Validates a user-supplied setting; throws IllegalArgumentException with a readable message.
     *
     * @param key Setting key.
     * @param value Setting value.
     * @param scenarios Known scenarios.
     * @return Normalized value.
     */
    public static String validate(String key, String value, Scenarios scenarios) {
        if (value == null)
            throw new IllegalArgumentException(key + ": value is required");

        value = value.trim();

        if (key.startsWith(Plugins.PREFIX))
            return Plugins.validate(key, value);

        switch (key) {
            case AUTORUN_ENABLED:
                if (!value.equals("true") && !value.equals("false"))
                    throw new IllegalArgumentException(key + " must be true or false");

                return value;
            case AUTORUN_MODE:
                if (!MODES.contains(value))
                    throw new IllegalArgumentException(key + " must be one of " + MODES);

                return value;
            case AUTORUN_SCENARIO:
                List<String> names = scenarioList(value);

                if (names.isEmpty())
                    throw new IllegalArgumentException(key + ": at least one scenario is required");

                for (String n : names) {
                    if (scenarios.get(n) == null)
                        throw new IllegalArgumentException("unknown scenario: " + n);
                }

                return String.join(",", names);
            case AUTORUN_PATH_FILTER:
                PathFilter.parse(value);

                return value;
            case AUTORUN_MAX_BACKLOG:
            case WATCH_POLL_SEC:
            case RETENTION_KEEP_JFR_JOBS:
            case RETENTION_KEEP_COMPARISONS:
            case RETENTION_KEEP_BISECTS:
            case RETENTION_ARTIFACTS_GB:
                int n;

                try {
                    n = Integer.parseInt(value);
                }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + " must be an integer");
                }

                int min = key.equals(WATCH_POLL_SEC) ? 30 : 0;

                if (n < min)
                    throw new IllegalArgumentException(key + " must be >= " + min);

                return value;
            case REPO_URL:
                if (!value.matches("(https?://|ssh://|git@|file:/|/)\\S+"))
                    throw new IllegalArgumentException(key + " must be a git URL");

                return value;
            case REPO_BRANCH:
                if (!value.matches("[0-9A-Za-z._/][0-9A-Za-z._/-]{0,99}"))
                    throw new IllegalArgumentException(key + " must be a branch name");

                return value;
            case RELEASE_TAG:
                if (!value.isBlank() && !value.trim().matches("[0-9A-Za-z._/*-]{1,64}"))
                    throw new IllegalArgumentException(key + " must be a tag, sha, branch, mask or empty");

                return value.trim();
            case TICKET_LINKS:
                List<String> links = new ArrayList<>();

                for (String line : value.split("\\R")) {
                    if (line.isBlank())
                        continue;

                    if (!line.trim().matches("[A-Z][A-Z0-9_]*=https?://\\S+( https?://\\S+)?"))
                        throw new IllegalArgumentException("a ticket link needs a key in capitals and http(s) URLs: "
                            + line.trim());

                    links.add(line.trim());
                }

                return String.join("\n", links);
            case WATCH_WATERMARK:
                if (!value.isEmpty() && !value.matches("[0-9a-f]{40}"))
                    throw new IllegalArgumentException(key + " must be a full sha or empty");

                return value;
            case Calibrator.AA + ".mode":
            case Calibrator.MASTER + ".mode":
                if (!value.equals(Freshness.OFF) && !value.equals(Freshness.KEEP))
                    throw new IllegalArgumentException(key + ": unknown mode " + value);

                return value;
            case AUTORUN_KEEP_DAYS:
            case Calibrator.AA + ".keepDays":
            case Calibrator.MASTER + ".keepDays":
                if (!value.matches("[1-9]\\d{0,2}"))
                    throw new IllegalArgumentException(key + " must be a number of days from 1");

                return value;
            case Calibrator.AA + ".scenarios":
            case Calibrator.MASTER + ".scenarios":
                for (String name : scenarioList(value)) {
                    if (scenarios.get(name) == null)
                        throw new IllegalArgumentException("unknown scenario: " + name);
                }

                return value;
            case Calibrator.AA_LAST:
            case Calibrator.MASTER_LAST:
                return value;
            case Notifier.MODE:
                if (!Set.of("off", "regression", "all").contains(value))
                    throw new IllegalArgumentException(key + " must be off, regression or all");

                return value;
            case Notifier.TELEGRAM_TOKEN:
            case Notifier.TELEGRAM_CHAT:
                return value;
            case Notifier.WEBHOOK_URL:
            case Notifier.BASE_URL:
                if (!value.isEmpty() && !value.matches("https?://\\S+"))
                    throw new IllegalArgumentException(key + " must be an http(s) URL or empty");

                return value;
            default:
                throw new IllegalArgumentException("unknown setting: " + key);
        }
    }

    /**
     * {@code autorun.scenario} holds one or several comma-separated scenario names.
     *
     * @param value Setting value.
     * @return Distinct scenario names.
     */
    public static List<String> scenarioList(String value) {
        List<String> out = new ArrayList<>();

        if (value == null)
            return out;

        for (String n : value.split(",")) {
            n = n.trim();

            if (!n.isEmpty() && !out.contains(n))
                out.add(n);
        }

        return out;
    }

    /**
     * The first auto-run scenario: the default for API calls and UI pages that need one.
     *
     * @param db Database.
     * @return Scenario name.
     */
    public static String firstScenario(Db db) {
        List<String> l = scenarioList(db.setting(AUTORUN_SCENARIO, DEFAULT_SCENARIO));

        return l.isEmpty() ? DEFAULT_SCENARIO : l.get(0);
    }

    /** */
    private Settings() {
    }
}
