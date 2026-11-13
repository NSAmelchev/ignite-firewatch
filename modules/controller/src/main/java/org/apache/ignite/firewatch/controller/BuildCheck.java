package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether this machine can build what the stand measures: the repository, the Java and the Maven of the configuration,
 * git and the disk under the cache. The commands only read, so the page runs it whenever it opens. Every command goes
 * to the log as Check of the hosts prints its own: {@code $ command}, {@code → exit 0 in 0.3 s}, {@code | output},
 * {@code · note}.
 */
public final class BuildCheck {
    /** */
    static final String OK = "ok";

    /** */
    static final String WARN = "warn";

    /** */
    static final String FAIL = "fail";

    /** Where the last report is kept, so a page shows it without asking again. */
    static final String SETTING = "app.check";

    /** Ignite master compiles with {@code --release 17}. */
    static final int MIN_JAVA = 17;

    /** Below this a build of core does not fit next to the mirror and the Maven repository. */
    static final long MIN_DISK_MB = 5 * 1024;

    /** Below this the builds of a few weeks do not fit. */
    static final long LOW_DISK_MB = 20 * 1024;

    /** Where a setting of the builds is changed. */
    private static final String CONFIG = "config/ignite-firewatch.properties";

    /** */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** Output lines kept of one command. */
    private static final int MAX_LINES = 60;

    /** What a shell takes as it is; anything else goes into single quotes. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=,@+%^-]+");

    /** */
    private static final Pattern MAVEN_VER = Pattern.compile("^Apache Maven (\\S+)", Pattern.MULTILINE);

    /** {@code Java version: 17.0.12, vendor: Red Hat, Inc., runtime: /usr/lib/jvm/...}. */
    private static final Pattern MAVEN_JAVA = Pattern.compile("^Java version: ([^,\\s]+),.*runtime: (.+)$", Pattern.MULTILINE);

    /** */
    private static final Pattern JAVA_SHOWN = Pattern.compile("\\s+java\\.(home|version|vendor) = .*");

    /** */
    private static final Pattern JAVA_HOME_LINE = Pattern.compile("^\\s*(?:export\\s+)?JAVA_HOME=(.*)$", Pattern.MULTILINE);

    /** One finding. */
    public static final class Item {
        /** */
        public String name;

        /** */
        public String level;

        /** */
        public String text;

        /** How to fix it, {@code code} in back quotes; {@code null} when there is nothing to fix. */
        public String fix;

        /** */
        Item(String name, String level, String text, String fix) {
            this.name = name;
            this.level = level;
            this.text = text;
            this.fix = fix;
        }
    }

    /** */
    public boolean ok;

    /** */
    public List<Item> items = new ArrayList<>();

    /** The commands it ran and what they said. */
    public List<String> log = new ArrayList<>();

    /** */
    public String at;

    /** */
    private final Config cfg;

    /** The files the start script of Maven reads after the environment it was given. */
    private final List<Path> mavenRc;

    /** */
    BuildCheck(Config cfg, List<Path> mavenRc) {
        this.cfg = cfg;
        this.mavenRc = mavenRc;
    }

    /**
     * Checks the builds of this machine.
     *
     * @param cfg Configuration.
     * @param url Repository to check, as the setting {@code repo.url} takes it.
     * @param branch Branch to check.
     * @return Report.
     */
    public static BuildCheck run(Config cfg, String url, String branch) {
        String home = System.getProperty("user.home");
        BuildCheck c = new BuildCheck(cfg, List.of(Path.of("/usr/local/etc/mavenrc"), Path.of("/etc/mavenrc"),
            Path.of(home, ".mavenrc"), Path.of("/etc/java/maven.conf")));
        c.check(url, branch);

        return c;
    }

    /** */
    void check(String url, String branch) {
        repository(url, branch);
        String javaHome = java();

        if (cfg.fakeBuild)
            add("builds", WARN, "fake: the release jars of " + cfg.fakeLibsDir + " stand for every commit", null);
        else {
            javac(javaHome);
            maven(javaHome);
        }

        git();
        disk();
        ok = items.stream().noneMatch(i -> FAIL.equals(i.level));
        at = Db.now();
    }

    /** */
    private void repository(String url, String branch) {
        try {
            Settings.validate(Settings.REPO_URL, url, null);
            Settings.validate(Settings.REPO_BRANCH, branch, null);
            String sha = GitMirror.headOf(exec(GitMirror.lsRemote(url, branch), Map.of(), null), url, branch);
            add("repository", OK, branch + " at " + sha.substring(0, Math.min(7, sha.length())), null);
        }
        catch (IllegalArgumentException e) {
            add("repository", FAIL, e.getMessage(), null);
        }
        catch (IOException e) {
            add("repository", FAIL, "git does not run: " + e.getMessage(), null);
        }
    }

    /** @return Home of the Java of the configuration as that Java sees it, or {@code null} when it does not run. */
    private String java() {
        String fix = "`java` of " + CONFIG + ": the full path of the java of a JDK " + MIN_JAVA + " or newer";
        Proc.Result r;

        try {
            // of all the properties the log keeps those it is read for, and what -version says
            r = exec(List.of(cfg.javaExe, "-XshowSettings:properties", "-version"), Map.of(), null,
                l -> JAVA_SHOWN.matcher(l).matches() || !l.isBlank() && !l.startsWith(" ") && !l.startsWith("Property settings"));
        }
        catch (IOException e) {
            add("java", FAIL, "cannot run " + cfg.javaExe + ": " + e.getMessage(), fix);

            return null;
        }

        Map<String, String> props = new LinkedHashMap<>();
        r.out.lines().map(String::strip).filter(l -> l.contains(" = "))
            .forEach(l -> props.putIfAbsent(l.substring(0, l.indexOf(" = ")), l.substring(l.indexOf(" = ") + 3)));
        String ver = props.get("java.version"), home = props.get("java.home");

        if (!r.ok() || ver == null || home == null) {
            add("java", FAIL, cfg.javaExe + " does not run: " + r.tail(1).strip(), fix);

            return null;
        }

        String text = ver + (props.containsKey("java.vendor") ? " (" + props.get("java.vendor") + ")" : "") + " · " + home;

        if (major(ver) < MIN_JAVA) {
            add("java", FAIL, text + ": needs " + MIN_JAVA + " or newer", fix);

            return null;
        }

        add("java", OK, text, null);

        return home;
    }

    /** */
    private void javac(String javaHome) {
        if (javaHome == null)
            return;

        if (Files.isRegularFile(Path.of(javaHome, "bin", "javac")))
            add("javac", OK, "in the same JDK", null);
        else {
            add("javac", FAIL, "none in " + javaHome + ": a JRE, and Maven compiles with the Java it runs on",
                "install the JDK of this Java (on RHEL `java-" + MIN_JAVA + "-openjdk-devel`), or point `java` of "
                    + CONFIG + " at a JDK");
        }
    }

    /** Maven with the environment of a build: whether it starts and on which Java. */
    private void maven(String javaHome) {
        Map<String, String> env = new LinkedHashMap<>(); // the order of the command in the log
        env.put("JAVA_HOME", cfg.javaHome());
        env.put("MAVEN_OPTS", "-Xmx" + cfg.mavenHeapMb + "m");
        env.put("LC_ALL", "C");
        Proc.Result r;

        try {
            Files.createDirectories(cfg.cacheDir);
            // the cache, not the working directory: the .mvn of a checkout there would change what it says
            r = exec(List.of(cfg.mvnExe, "-B", "-v"), env, cfg.cacheDir.toAbsolutePath());
        }
        catch (IOException e) {
            add("maven", FAIL, "cannot run " + cfg.mvnExe + ": " + e.getMessage(),
                "`mvn` of " + CONFIG + ": the path of bin/mvn of Maven");

            return;
        }

        Matcher m = MAVEN_VER.matcher(r.out);
        String ver = m.find() ? m.group(1) : "Maven";

        if (!r.ok()) {
            String why = r.out.lines().filter(l -> !l.isBlank()).findFirst().orElse("exit " + r.code).strip();
            add("maven", FAIL, "does not start: " + why, why.contains("JAVA_HOME")
                ? "`java` of " + CONFIG + " has to be a path inside a JDK: Maven gets " + cfg.javaHome()
                    + " as JAVA_HOME from it"
                : null);

            return;
        }

        Matcher j = MAVEN_JAVA.matcher(r.out);

        if (!j.find()) {
            add("maven", WARN, ver + ", but it did not say its Java", null);

            return;
        }

        String javaVer = j.group(1), runtime = j.group(2).strip();
        String args = cfg.mavenText().substring(cfg.mvnExe.length()).strip();
        String extra = args.isEmpty() ? "" : " · " + args;

        if (major(javaVer) < MIN_JAVA)
            add("maven", FAIL, ver + " runs on Java " + javaVer + " (" + runtime + "), not on the java above", rcFix());
        else if (javaHome != null && !same(runtime, javaHome))
            add("maven", WARN, ver + " runs on another Java: " + javaVer + " (" + runtime + ")" + extra, rcFix());
        else
            add("maven", OK, ver + " on the same Java" + extra, null);
    }

    /** What makes Maven leave the JAVA_HOME it was given: a line of a file its start script reads. */
    private String rcFix() {
        for (Path p : mavenRc) {
            try {
                if (!Files.isRegularFile(p))
                    continue;

                Matcher m = JAVA_HOME_LINE.matcher(Files.readString(p));

                while (m.find()) {
                    String val = m.group(1).strip();

                    if (val.contains("$JAVA_HOME") || val.contains("${JAVA_HOME"))
                        continue; // keeps the one it was given

                    note("Maven reads " + p + ": JAVA_HOME=" + val);

                    return "`" + p + "` sets `JAVA_HOME=" + val + "`: remove that line"
                        + (p.endsWith("maven.conf") ? " or point it at a JDK " + MIN_JAVA
                        : ", or start Firewatch with `MAVEN_SKIP_RC=1`");
                }
            }
            catch (IOException ignored) {
                // unreadable: not the one
            }
        }

        return "the start script of Maven sets JAVA_HOME itself: look into `" + cfg.mvnExe + "`";
    }

    /** */
    private void git() {
        try {
            Proc.Result r = exec(List.of("git", "--version"), Map.of(), null);

            if (r.ok())
                add("git", OK, r.out.strip().replaceFirst("^git version ", ""), null);
            else
                add("git", FAIL, "does not run: " + r.tail(1).strip(), null);
        }
        catch (IOException e) {
            add("git", FAIL, "not found: " + e.getMessage(), "install git");
        }
    }

    /** Free space under the cache and, when it is elsewhere, under the data. */
    private void disk() {
        List<FileStore> seen = new ArrayList<>();

        for (Path dir : List.of(cfg.cacheDir, cfg.dataDir)) {
            Path abs = dir.toAbsolutePath().normalize();

            try {
                Path existing = abs;

                while (existing != null && !Files.exists(existing))
                    existing = existing.getParent();

                if (existing == null)
                    continue;

                FileStore store = Files.getFileStore(existing);

                if (seen.contains(store))
                    continue;

                seen.add(store);
                long free = store.getUsableSpace() / (1024 * 1024);
                String text = free / 1024 + " GB free under " + abs;

                if (free < MIN_DISK_MB)
                    add("disk", FAIL, text + ": needs " + MIN_DISK_MB / 1024, null);
                else if (free < LOW_DISK_MB)
                    add("disk", WARN, text + ": the builds of a few weeks need " + LOW_DISK_MB / 1024, null);
                else
                    add("disk", OK, text, null);
            }
            catch (IOException e) {
                add("disk", WARN, "cannot tell the free space under " + abs + ": " + e.getMessage(), null);
            }
        }
    }

    /** Runs a command into the log. */
    private Proc.Result exec(List<String> cmd, Map<String, String> env, Path cwd) throws IOException {
        return exec(cmd, env, cwd, l -> true);
    }

    /** Runs a command into the log, which keeps the lines of its output that {@code shown} takes. */
    private Proc.Result exec(List<String> cmd, Map<String, String> env, Path cwd, Predicate<String> shown)
        throws IOException {
        StringBuilder line = new StringBuilder("$ ");
        env.forEach((k, v) -> line.append(k).append('=').append(shell(List.of(v))).append(' '));
        log.add(line.append(shell(cmd)).toString());
        long t0 = System.nanoTime();
        Map<String, String> all = new LinkedHashMap<>(GitMirror.env());
        all.putAll(env);

        try {
            Proc.Result r = Proc.run(cmd, cwd, all, TIMEOUT);
            String took = String.format("%.1f s", (System.nanoTime() - t0) / 1e9);
            log.add("→ " + (r.timedOut ? "stopped after " + took : "exit " + r.code + " in " + took));
            List<String> lines = r.out.lines().filter(shown).limit(MAX_LINES).toList();
            lines.forEach(l -> log.add("| " + l));
            long left = r.out.lines().count() - lines.size();

            if (left > 0)
                note(left + " more lines of its output left out");

            return r;
        }
        catch (IOException e) {
            log.add("→ did not start: " + e.getMessage());

            throw e;
        }
    }

    /** */
    private void note(String text) {
        log.add("· " + text);
    }

    /** */
    private void add(String name, String level, String text, String fix) {
        items.add(new Item(name, level, text, fix));
    }

    /** @return Whether both paths are one directory, links followed. */
    private static boolean same(String a, String b) {
        try {
            return Path.of(a).toRealPath().equals(Path.of(b).toRealPath());
        }
        catch (IOException | RuntimeException e) {
            return Objects.equals(a, b);
        }
    }

    /** @return Feature version of a Java version string: 17 of {@code 17.0.12}, 8 of {@code 1.8.0_402}. */
    static int major(String ver) {
        Matcher m = Pattern.compile("^(?:1\\.)?(\\d+)").matcher(ver);

        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** @return The words as a shell takes them. */
    static String shell(List<String> words) {
        StringBuilder sb = new StringBuilder();

        for (String w : words) {
            if (sb.length() > 0)
                sb.append(' ');

            sb.append(PLAIN.matcher(w).matches() ? w : quote(w));
        }

        return sb.toString();
    }

    /** */
    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
