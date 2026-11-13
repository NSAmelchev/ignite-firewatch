package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bare mirror of the upstream repository driven through the git CLI. Branches and tags are fetched on every poll,
 * pull-request heads ({@code pr/N}) on demand. Every call is synchronized: git itself tolerates concurrency, but
 * one fetch at a time is enough and keeps the log readable.
 */
public final class GitMirror {
    /** */
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    /** */
    private static final Pattern PR = Pattern.compile("(?i)^(?:pr/|pull/|#)(\\d+)$");

    /** https://github.com/OWNER/REPO/(pull/N[/commits/SHA]|commit/SHA|tree/BRANCH|releases/tag/TAG)[...] */
    private static final Pattern GITHUB = Pattern.compile(
        "(?i)^(?:https?://)?(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/(pull|commit|tree|releases/tag)/"
            + "([^?#]+?)/?(?:[?#].*)?$");

    /** */
    private static final Duration SHORT = Duration.ofMinutes(2);

    /** A progress line of git: {@code Receiving objects:  45% (123/274), 210.50 MiB | 12.00 MiB/s}, also after "remote: ". */
    private static final Pattern PROGRESS =
        Pattern.compile("^(?:remote: )?([A-Z][a-z]+(?: [a-z]+)*):\\s+(\\d+)% \\((\\d+)/(\\d+)\\)(.*)$");

    /** What is read of the output of a running fetch for its progress: git rewrites its line with carriage returns. */
    private static final int PROGRESS_TAIL_BYTES = 8192;

    /** */
    private final Path dir;

    /** */
    private volatile String url;

    /** */
    private volatile String branch;

    /** */
    private final Duration fetchTimeout;

    /** */
    private volatile String lastFetchAt;

    /** */
    private volatile String lastFetchError;

    /** */
    private volatile boolean ready;

    /** What the running fetch says, next to the mirror: the page shows its progress while the first fetch clones. */
    private final Path fetchLog;

    /** */
    private volatile String fetchStartedAt;

    /**
     * Creates a mirror.
     *
     * @param dir Directory of the bare repository.
     * @param url Remote repository url.
     * @param branch Watched branch.
     * @param fetchTimeout Timeout of a fetch.
     */
    public GitMirror(Path dir, String url, String branch, Duration fetchTimeout) {
        this.dir = dir;
        this.url = url;
        this.branch = branch;
        this.fetchTimeout = fetchTimeout;
        fetchLog = dir.resolveSibling(dir.getFileName() + "-fetch.log");
    }

    /**
     * Returns the mirror directory.
     *
     * @return Directory of the bare repository.
     */
    public Path dir() {
        return dir;
    }

    /**
     * Returns the watched branch.
     *
     * @return Branch name.
     */
    public String branch() {
        return branch;
    }

    /**
     * Returns the time of the last successful fetch.
     *
     * @return Time, or {@code null} if there was none.
     */
    public String lastFetchAt() {
        return lastFetchAt;
    }

    /**
     * Returns the error of the last failed fetch.
     *
     * @return Error message, or {@code null} if the last fetch succeeded.
     */
    public String lastFetchError() {
        return lastFetchError;
    }

    /** Creates the bare repository with explicit refspecs (heads + tags; PR heads come on demand). */
    public synchronized void ensure() throws IOException {
        if (Files.exists(dir.resolve("HEAD"))) {
            git(SHORT, "remote", "set-url", "origin", url); // the settings may have changed it since
            git(SHORT, "symbolic-ref", "HEAD", "refs/heads/" + branch);

            return;
        }

        Files.createDirectories(dir);
        git(SHORT, "init", "--bare", "--quiet", "--template=", dir.toString());
        git(SHORT, "remote", "add", "origin", url);
        git(SHORT, "config", "remote.origin.fetch", "+refs/heads/*:refs/heads/*");
        git(SHORT, "config", "--add", "remote.origin.fetch", "+refs/tags/*:refs/tags/*");
        git(SHORT, "config", "remote.origin.tagOpt", "--no-tags");
        git(SHORT, "config", "gc.auto", "0");
        git(SHORT, "symbolic-ref", "HEAD", "refs/heads/" + branch);
        Log.info("git mirror created at " + dir + " for " + url);
    }

    /**
     * Follows another repository or branch; refuses one the remote does not have. The remote may be a path on this
     * machine: a git repository or a bundle of one ({@code git bundle create x.bundle --all}).
     *
     * @param newUrl Remote repository url or path.
     * @param newBranch Branch to watch.
     */
    public synchronized void follow(String newUrl, String newBranch) throws IOException {
        ensure(); // a new stand chooses its repository before anything was cloned
        headOf(Proc.run(lsRemote(newUrl, newBranch), null, env(), SHORT), newUrl, newBranch);
        git(SHORT, "remote", "set-url", "origin", newUrl);
        git(SHORT, "symbolic-ref", "HEAD", "refs/heads/" + newBranch);
        url = newUrl;
        branch = newBranch;
        ready = false;
        Log.info("git mirror follows " + newBranch + " of " + newUrl);
    }

    /**
     * True once the watched branch exists locally (i.e. the first fetch has completed). Not synchronized on
     * purpose: the UI asks this while a fetch holds the lock, and git tolerates a concurrent read-only command.
     *
     * @return Whether the branch exists locally.
     */
    public boolean ready() {
        if (ready)
            return true;

        if (!Files.exists(dir.resolve("HEAD")))
            return false;

        try {
            ready = run(SHORT, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).ok();

            return ready;
        }
        catch (IOException e) {
            return false;
        }
    }

    /** Fetches the branches and tags of the remote; {@link #fetchProgress} tells how far it is meanwhile. */
    public synchronized void fetch() throws IOException {
        ensure();
        long t0 = System.nanoTime();
        fetchStartedAt = Db.now();

        try {
            Files.deleteIfExists(fetchLog);
            Proc.Handle h = Proc.start(List.of("git", "--git-dir=" + dir, "fetch", "--prune", "--progress", "origin"), null,
                env(), fetchLog);
            Integer rc;

            try {
                rc = h.waitFor(fetchTimeout, null);
            }
            catch (InterruptedException e) {
                h.killTree(Duration.ofSeconds(5));
                Thread.currentThread().interrupt();

                throw new IOException("git fetch interrupted");
            }

            if (rc == null) {
                h.killTree(Duration.ofSeconds(5));

                throw new IOException("git fetch timed out after " + fetchTimeout.toMinutes() + " min: " + said());
            }

            if (rc != 0)
                throw new IOException("git fetch failed (exit " + rc + "): " + said());

            lastFetchAt = Db.now();
            lastFetchError = null;
            Log.info(String.format("git fetch done in %.1f s", (System.nanoTime() - t0) / 1e9));
        }
        catch (IOException e) {
            lastFetchError = e.getMessage();

            throw e;
        }
        finally {
            fetchStartedAt = null;
        }
    }

    /**
     * Tells how far the running fetch is, from what git prints about it.
     *
     * @return {@code startedAt}, and {@code stage}, {@code pct}, {@code detail} once git says them; {@code null} when no
     *      fetch runs.
     */
    public Map<String, Object> fetchProgress() {
        String started = fetchStartedAt;

        if (started == null)
            return null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startedAt", started);
        m.putAll(progressOf(tail(fetchLog, PROGRESS_TAIL_BYTES)));

        return m;
    }

    /**
     * Reads the last progress line of what git printed.
     *
     * @param out Output of git.
     * @return {@code stage}, {@code pct} and {@code detail}; empty before git says any.
     */
    static Map<String, Object> progressOf(String out) {
        List<String> lines = Arrays.asList(out.split("[\r\n]+"));
        Map<String, Object> m = new LinkedHashMap<>();

        for (int i = lines.size() - 1; i >= 0; i--) {
            Matcher p = PROGRESS.matcher(lines.get(i).strip());

            if (p.matches()) {
                m.put("stage", p.group(1).toLowerCase());
                m.put("pct", Integer.parseInt(p.group(2)));
                String detail = p.group(5).replaceFirst("^,\\s*", "").replaceFirst(",?\\s*done\\.?$", "").strip();
                m.put("detail", detail.isEmpty() ? null : detail);

                break;
            }
        }

        return m;
    }

    /** @return What the last fetch said besides its progress: its messages and errors. */
    private String said() {
        List<String> msgs = Arrays.stream(tail(fetchLog, 64 * 1024).split("[\r\n]+")).map(String::strip)
            .filter(l -> !l.isEmpty() && !PROGRESS.matcher(l).matches() && !l.startsWith("* [") && !l.startsWith("- ["))
            .toList();

        return String.join("\n", msgs.subList(Math.max(0, msgs.size() - 5), msgs.size()));
    }

    /** @return The end of a file, empty when there is none. */
    private static String tail(Path file, int maxBytes) {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            byte[] b = new byte[(int)Math.min(len, maxBytes)];
            f.seek(len - b.length);
            f.readFully(b);

            return new String(b, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return "";
        }
    }

    /**
     * Resolves a user-supplied ref to a full commit sha: a sha or its prefix, a branch, a tag, {@code pr/N}
     * (fetched from the remote if needed), or any git revision expression such as {@code master~3}.
     */
    /**
     * Turns a GitHub link into a git ref of this mirror: {@code .../pull/13704} -> {@code pr/13704},
     * {@code .../pull/N/commits/SHA} and {@code .../commit/SHA} -> SHA, {@code .../tree/BRANCH} -> BRANCH,
     * {@code .../releases/tag/TAG} -> TAG. Anything else is returned trimmed. Links to another repository are rejected.
     *
     * @param ref Git ref or GitHub link.
     * @return Git ref.
     */
    public String normalize(String ref) {
        if (ref == null)
            return null;

        ref = ref.trim();
        Matcher m = GITHUB.matcher(ref);

        if (!m.matches())
            return ref;

        String repo = m.group(1) + "/" + m.group(2);
        String own = url.replaceFirst("(?i)^.*github\\.com[/:]", "").replaceFirst("\\.git$", "").replaceAll("/+$", "");

        if (url.toLowerCase().contains("github.com") && !repo.equalsIgnoreCase(own))
            throw new IllegalArgumentException("link points to " + repo + ", but the mirror is " + own);

        String kind = m.group(3).toLowerCase(), rest = m.group(4);

        switch (kind) {
            case "pull": {
                String[] parts = rest.split("/");

                if (!parts[0].matches("\\d+"))
                    throw new IllegalArgumentException("bad pull request link: " + ref);

                if (parts.length >= 3 && parts[1].equals("commits"))
                    return "pr/" + parts[0] + "@" + parts[2]; // a commit inside the PR: fetched with the PR

                return "pr/" + parts[0];
            }
            case "commit":
                return rest.split("/")[0];
            default: // tree, releases/tag: the rest is the branch or tag name (may contain slashes)
                return rest;
        }
    }

    /**
     * Resolves a ref to a full commit sha, fetching it when needed.
     *
     * @param ref Git ref, GitHub link or {@code pr/N@SHA}.
     * @return Commit sha.
     */
    public synchronized String resolve(String ref) throws IOException {
        if (ref == null || ref.isBlank())
            throw new IllegalArgumentException("ref is empty");

        ref = normalize(ref);
        int at = ref.indexOf('@');

        if (ref.toLowerCase().startsWith("pr/") && at > 0) { // pr/N@SHA: fetch the PR, then take the commit
            resolve(ref.substring(0, at));
            ref = ref.substring(at + 1);
        }

        Matcher pr = PR.matcher(ref);

        if (pr.matches()) {
            String n = pr.group(1);
            String loc = "refs/pull/" + n + "/head";
            git(Duration.ofMinutes(5), "fetch", "--quiet", "origin", "+" + loc + ":" + loc);
            ref = loc;
        }

        if (ref.startsWith("-"))
            throw new IllegalArgumentException("bad ref: " + ref);

        Proc.Result r = run(SHORT, "rev-parse", "--verify", "--quiet", "--end-of-options", ref + "^{commit}");

        if (!r.ok())
            throw new IllegalArgumentException("unknown ref: " + ref);

        String sha = r.out.trim();

        if (!SHA.matcher(sha).matches())
            throw new IllegalArgumentException("unexpected rev-parse output for " + ref + ": " + sha);

        return sha;
    }

    /**
     * GitHub's test merge of a pull request into its base branch ({@code refs/pull/N/merge}): returns
     * {merge sha, first parent sha}, i.e. "base branch + PR" and the base branch it was merged into.
     * GitHub keeps the ref only while the PR is open and mergeable.
     *
     * @param pr Pull request number.
     * @return Merge sha and its first parent sha.
     */
    public synchronized String[] pullMerge(int pr) throws IOException {
        String loc = "refs/pull/" + pr + "/merge";
        Proc.Result f = run(Duration.ofMinutes(5), "fetch", "--quiet", "origin", "+" + loc + ":" + loc);

        if (!f.ok())
            throw new IllegalArgumentException("PR #" + pr
                + " has no merge ref on GitHub (closed, merged or has conflicts): " + f.tail(2).trim());

        String merge = resolve(loc);
        Proc.Result p = run(SHORT, "rev-parse", "--verify", "--quiet", "--end-of-options", merge + "^1");

        if (!p.ok() || !SHA.matcher(p.out.trim()).matches())
            throw new IllegalStateException("cannot read the first parent of " + merge);

        return new String[] {merge, p.out.trim()};
    }

    /**
     * First-parent history starting at {@code ref}, newest first.
     *
     * @param ref Starting ref.
     * @param limit Maximum number of commits.
     * @return Commits, newest first.
     */
    public synchronized List<CommitInfo> log(String ref, int limit) throws IOException {
        Proc.Result r = run(SHORT, "log", "--first-parent", "-n", String.valueOf(limit),
            "--format=%H%x1f%P%x1f%an%x1f%cI%x1f%s", "--end-of-options", ref);

        if (!r.ok())
            throw new IOException("git log " + ref + " failed: " + r.tail(3).trim());

        List<CommitInfo> out = new ArrayList<>();

        for (String line : r.out.split("\n")) {
            if (line.isBlank())
                continue;

            out.add(parse(line));
        }

        return out;
    }

    /**
     * Reads one commit.
     *
     * @param sha Commit sha or ref.
     * @return The commit, or {@code null} if there is none.
     */
    public synchronized CommitInfo info(String sha) throws IOException {
        List<CommitInfo> l = log(sha, 1);

        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * First-parent commits reachable from {@code to} but not from {@code from}, oldest first.
     *
     * @param from Excluded start ref.
     * @param to Included end ref.
     * @return Commit shas, oldest first.
     */
    public synchronized List<String> revList(String from, String to) throws IOException {
        Proc.Result r = run(SHORT, "rev-list", "--first-parent", "--reverse", "--end-of-options", from + ".." + to);

        if (!r.ok())
            throw new IOException("git rev-list failed: " + r.tail(3).trim());

        List<String> out = new ArrayList<>();

        for (String line : r.out.split("\n")) {
            if (!line.isBlank())
                out.add(line.trim());
        }

        return out;
    }

    /**
     * Number of first-parent commits up to and including {@code sha}: a stable position on the branch.
     *
     * @param sha Commit sha.
     * @return Position on the branch.
     */
    public synchronized int seq(String sha) throws IOException {
        Proc.Result r = run(SHORT, "rev-list", "--count", "--first-parent", "--end-of-options", sha);

        if (!r.ok())
            throw new IOException("git rev-list --count failed: " + r.tail(3).trim());

        return Integer.parseInt(r.out.trim());
    }

    /**
     * Commits of {@code limit..head} whose change is already in {@code upstream} under another sha (cherry-picks),
     * matched by patch id ({@code git cherry}): e.g. master commits picked into a release branch before its tag.
     *
     * @param upstream Ref to look for picked changes in.
     * @param head Ref with the commits to check.
     * @param limit Ref the checked range starts after.
     * @return Shas of the picked commits.
     */
    public synchronized java.util.Set<String> cherryPicked(String upstream, String head,
        String limit) throws IOException {
        Proc.Result r = run(Duration.ofMinutes(2), "cherry", upstream, head, limit);

        if (!r.ok())
            throw new IOException("git cherry failed: " + r.tail(3).trim());

        java.util.Set<String> out = new java.util.HashSet<>();

        for (String line : r.out.split("\n")) {
            line = line.trim();

            if (line.startsWith("- "))
                out.add(line.substring(2).trim());
        }

        return out;
    }

    /**
     * Highest release tag X.Y.Z of the mirror (release candidates and other tags are ignored), or null.
     *
     * @return Release tag, or {@code null} if there is none.
     */
    public synchronized String latestReleaseTag() throws IOException {
        return latestReleaseTag("*");
    }

    /**
     * The highest X.Y.Z tag that fits {@code mask}, where {@code *} stands for anything; null when none does.
     *
     * @param mask Tag mask where {@code *} stands for anything.
     * @return Release tag, or {@code null} if none fits.
     */
    public synchronized String latestReleaseTag(String mask) throws IOException {
        Pattern fits = Pattern.compile(Arrays.stream(mask.split("\\*", -1)).map(Pattern::quote)
            .collect(java.util.stream.Collectors.joining(".*")));
        Proc.Result r = run(SHORT, "tag", "-l");

        if (!r.ok())
            throw new IOException("git tag -l failed: " + r.tail(3).trim());

        String best = null;
        int[] bestV = null;

        for (String t : r.out.split("\n")) {
            t = t.trim();

            if (!t.matches("\\d+\\.\\d+\\.\\d+") || !fits.matcher(t).matches())
                continue;

            String[] p = t.split("\\.");
            int[] v = {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};

            if (bestV == null || Arrays.compare(v, bestV) > 0) {
                best = t;
                bestV = v;
            }
        }

        return best;
    }

    /**
     * Finds the best common ancestor of two refs.
     *
     * @param a First ref.
     * @param b Second ref.
     * @return Sha of the merge base.
     */
    public synchronized String mergeBase(String a, String b) throws IOException {
        Proc.Result r = run(SHORT, "merge-base", "--end-of-options", a, b);

        if (!r.ok())
            throw new IllegalArgumentException("no merge base between " + a + " and " + b);

        return r.out.trim();
    }

    /**
     * Paths changed by a commit relative to its first parent (all paths for a root commit; empty for merges).
     *
     * @param sha Commit sha.
     * @return Changed paths.
     */
    public synchronized List<String> changedFiles(String sha) throws IOException {
        Proc.Result r = run(SHORT, "diff-tree", "--no-commit-id", "--name-only", "-r", "--root", "--end-of-options",
            sha);

        if (!r.ok())
            throw new IOException("git diff-tree failed: " + r.tail(3).trim());

        List<String> out = new ArrayList<>();

        for (String line : r.out.split("\n")) {
            if (!line.isBlank())
                out.add(line.trim());
        }

        return out;
    }

    /**
     * Checks out {@code sha} into a fresh working tree that shares the mirror's objects (fast, no copying).
     *
     * @param sha Commit sha.
     * @param dest Directory of the new working tree.
     */
    public synchronized void export(String sha, Path dest) throws IOException {
        if (Files.exists(dest))
            Util.deleteTree(dest);

        Files.createDirectories(dest.toAbsolutePath().getParent());
        git(Duration.ofMinutes(10), "clone", "--quiet", "--shared", "--no-checkout", dir.toString(), dest.toString());
        Proc.Result r = Proc.run(List.of("git", "-C", dest.toString(), "checkout", "--quiet", "--detach", sha), null,
            env(), Duration.ofMinutes(10));

        if (!r.ok())
            throw new IOException("git checkout " + sha + " failed: " + r.tail(5).trim());
    }

    /** */
    private static CommitInfo parse(String line) {
        String[] f = line.split("\u001f", -1);
        CommitInfo ci = new CommitInfo();
        ci.sha = f[0];
        String parents = f.length > 1 ? f[1].trim() : "";
        ci.parentSha = parents.isEmpty() ? null : parents.split(" ")[0];
        ci.author = f.length > 2 ? f[2] : null;
        ci.committedAt = f.length > 3 ? f[3] : null;
        ci.subject = f.length > 4 ? f[4] : "";

        return ci;
    }

    /** */
    private void git(Duration timeout, String... args) throws IOException {
        Proc.Result r = run(timeout, args);

        if (!r.ok())
            throw new IOException("git " + args[0] + (r.timedOut ? " timed out" : " failed (exit " + r.code + ")")
                + ": " + r.tail(5).trim());
    }

    /** */
    private Proc.Result run(Duration timeout, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        boolean init = args.length > 0 && args[0].equals("init");
        boolean clone = args.length > 0 && args[0].equals("clone");

        if (!init && !clone) {
            cmd.add("--git-dir=" + dir);
        }

        cmd.addAll(List.of(args));

        return Proc.run(cmd, null, env(), timeout);
    }

    /**
     * The command that asks a remote for the head of a branch. It needs no repository of its own: a stand asks before
     * anything is cloned.
     *
     * @param url Remote repository url or path.
     * @param branch Branch.
     * @return Command.
     */
    static List<String> lsRemote(String url, String branch) {
        return List.of("git", "ls-remote", "--exit-code", "--heads", url, "refs/heads/" + branch);
    }

    /**
     * Reads what {@link #lsRemote} said.
     *
     * @param ls Its result.
     * @param url Remote repository url or path.
     * @param branch Branch.
     * @return Sha of the head of the branch.
     * @throws IllegalArgumentException Saying why the branch cannot be read, in the words of git where it has some.
     */
    static String headOf(Proc.Result ls, String url, String branch) {
        if (ls.code == 2 && !ls.timedOut) // --exit-code: the remote answered, but has no such branch
            throw new IllegalArgumentException("no branch " + branch + " at " + url);

        if (!ls.ok()) {
            String why = ls.timedOut ? "timed out" : ls.out.lines().filter(l -> l.startsWith("fatal:")).findFirst()
                .orElse(ls.tail(1)).trim();

            throw new IllegalArgumentException("cannot read " + url + ": " + why);
        }

        // the output has stderr too: a warning of git may come before the line of the branch
        String ref = "refs/heads/" + branch;

        return ls.out.lines().filter(l -> l.endsWith("\t" + ref)).map(l -> l.substring(0, l.indexOf('\t')))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("cannot read " + url + ": " + ls.tail(1)));
    }

    /**
     * The environment of every git command: never a prompt for a password, messages in English.
     *
     * @return Environment.
     */
    static Map<String, String> env() {
        return Map.of("GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C");
    }
}
