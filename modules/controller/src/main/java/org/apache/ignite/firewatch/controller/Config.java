package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Controller configuration. Every field has a development default (run from the repository root). The properties
 * file {@link #CONFIG_FILE} sets it as {@code data.dir = ...}, an environment variable
 * {@code IGNITE_FIREWATCH_DATA_DIR} overrides the file and a system property {@code ignite.firewatch.data.dir}
 * overrides both. Settings that the UI may change at run time live in the database instead (see {@link Settings}).
 */
public final class Config {
    /** The properties file, relative to the working directory; {@code IGNITE_FIREWATCH_CONFIG} names another one. */
    static final String CONFIG_FILE = "config/ignite-firewatch.properties";

    /** The option of Maven that names its local repository. */
    static final String REPO_LOCAL = "-Dmaven.repo.local=";

    /** What the properties file says, read once. */
    private static Properties file;

    /** SQLite database and run results (valuable). */
    public Path dataDir = Path.of("data");

    /** Git mirror, Maven repository, built artifacts, build work dirs (disposable). */
    public Path cacheDir = Path.of("cache");

    /** */
    public Path harnessJar = defaultHarnessJar();

    /** */
    public String bindHost = "0.0.0.0";

    /** */
    public int port = 8090;

    /** */
    public String repoUrl = "https://github.com/apache/ignite.git";

    /** */
    public String branch = "master";

    /** */
    public String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    /** */
    public String mvnExe = "mvn";

    /** */
    public int mavenHeapMb = 1536;

    /** The settings.xml of every build of Ignite ({@code -s}): mirrors, proxies, credentials; null for the default. */
    public Path mavenSettings;

    /**
     * More arguments of every mvn call of a build, after those of the application; {@code -Dmaven.repo.local} here
     * replaces the repository of the cache, see {@link #m2Dir()}.
     */
    public List<String> mavenArgs = List.of();

    /** */
    public int buildTimeoutMin = 25;

    /** */
    public int fetchTimeoutMin = 30;

    /** */
    public int harnessHeapMb = 128;

    /**
     * The plugins this stand runs: jar names in {@link #pluginsDir}, or absolute paths, comma-separated. A jar lying in
     * the directory does nothing until it is named here; see {@link Plugins}.
     */
    public List<String> plugins = List.of();

    /** */
    public Path pluginsDir = Path.of("config/plugins");

    /** Development mode: instead of building a commit, copy release jars from {@link #fakeLibsDir}. */
    public boolean fakeBuild;

    /** */
    public Path fakeLibsDir = Path.of("modules/harness/target/dev-libs");

    /** Overrides the harness command line prefix (tests use a script instead of {@code java -jar harness.jar}). */
    public List<String> harnessCommand;

    /** Extra environment for harness processes (tests). */
    public java.util.Map<String, String> harnessEnv = java.util.Map.of();

    /** Extra seconds added to the harness' own worst-case time before a phase is considered hung. */
    public int phaseGraceSec = 120;

    /**
     * The ssh key and known_hosts of a remote stand: files, never stored in the database. Empty = what ssh finds
     * itself.
     */
    public String sshKey = "";

    /** */
    public String sshKnownHosts = "";

    /**
     * Whether ssh takes the key of a host that known_hosts lacks on first contact and adds it there
     * ({@code StrictHostKeyChecking=accept-new}); a changed key is refused either way.
     */
    public boolean sshAcceptNewHosts;

    /**
     * Tells this stand from another with the same data directory, which is what two containers have; see
     * {@link Stand#instance}.
     */
    public String instance = "";

    /**
     * Reads the config from environment variables.
     *
     * @return Config.
     */
    public static Config fromEnv() {
        Config c = new Config();
        c.dataDir = Path.of(get("DATA_DIR", c.dataDir.toString()));
        c.cacheDir = Path.of(get("CACHE_DIR", c.cacheDir.toString()));
        c.harnessJar = Path.of(get("HARNESS_JAR", c.harnessJar.toString()));
        c.bindHost = get("BIND_HOST", c.bindHost);
        c.port = Integer.parseInt(get("PORT", String.valueOf(c.port)));
        c.repoUrl = get("REPO_URL", c.repoUrl);
        c.branch = get("BRANCH", c.branch);
        c.javaExe = get("JAVA", c.javaExe);
        c.mvnExe = get("MVN", c.mvnExe);
        c.mavenHeapMb = Integer.parseInt(get("MAVEN_HEAP_MB", String.valueOf(c.mavenHeapMb)));
        c.mavenSettings = mavenSettings(get("MAVEN_SETTINGS", ""));
        c.mavenArgs = words(get("MAVEN_ARGS", ""));
        c.buildTimeoutMin = Integer.parseInt(get("BUILD_TIMEOUT_MIN", String.valueOf(c.buildTimeoutMin)));
        c.fetchTimeoutMin = Integer.parseInt(get("FETCH_TIMEOUT_MIN", String.valueOf(c.fetchTimeoutMin)));
        c.harnessHeapMb = Integer.parseInt(get("HARNESS_HEAP_MB", String.valueOf(c.harnessHeapMb)));
        c.fakeBuild = isTrue(get("FAKE_BUILD", "0"));
        c.plugins = Arrays.stream(get("PLUGINS", "").split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        c.pluginsDir = Path.of(get("PLUGINS_DIR", c.pluginsDir.toString()));
        c.fakeLibsDir = Path.of(get("FAKE_LIBS_DIR", c.fakeLibsDir.toString()));
        c.phaseGraceSec = Integer.parseInt(get("PHASE_GRACE_SEC", String.valueOf(c.phaseGraceSec)));
        c.sshKey = get("SSH_KEY", c.sshKey);
        c.sshKnownHosts = get("SSH_KNOWN_HOSTS", c.sshKnownHosts);
        c.sshAcceptNewHosts = isTrue(get("SSH_ACCEPT_NEW_HOSTS", "false"));

        return c;
    }

    /** */
    static String get(String name, String def) {
        String key = name.toLowerCase(Locale.ROOT).replace('_', '.');
        String v = override(name);

        if (v == null)
            v = file().getProperty(key);

        return v == null || v.isBlank() ? def : v.trim();
    }

    /**
     * The jars of {@link #plugins}, in their order: a name is in {@link #pluginsDir}, a path stays as it is.
     *
     * @return Jar paths.
     */
    public List<Path> pluginJars() {
        return plugins.stream().map(p -> Path.of(p).isAbsolute() ? Path.of(p) : pluginsDir.resolve(p)).toList();
    }

    /**
     * The mirror of the repository of a plugin.
     *
     * @param plugin Plugin name.
     * @return Directory of its bare repository.
     */
    public Path pluginMirrorDir(String plugin) {
        return cacheDir.resolve("plugins").resolve(plugin).resolve("mirror");
    }

    /**
     * Where the harness is when nothing says: next to the controller jar in the distribution and the image, else in
     * its module of the repository.
     *
     * @return Path of {@code harness.jar}.
     */
    static Path defaultHarnessJar() {
        try {
            Path own = Path.of(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path next = own.resolveSibling("harness.jar");

            if (Files.isRegularFile(own) && Files.isRegularFile(next))
                return next;
        }
        catch (Exception ignored) {
            // no code source: the repository default below
        }

        return Path.of("modules/harness/target/harness.jar");
    }

    /**
     * Gets the settings.xml of the builds. A relative path starts in the working directory of the application: the
     * builds run in directories of their own.
     *
     * @param value Value of {@code maven.settings}.
     * @return Absolute path, or {@code null} when it is not set.
     */
    static Path mavenSettings(String value) {
        if (value.isBlank())
            return null;

        Path p = Path.of(value.trim()).toAbsolutePath().normalize();

        if (!Files.isRegularFile(p))
            throw new IllegalStateException("maven.settings: " + p + " does not exist");

        return p;
    }

    /**
     * Splits a value into words.
     *
     * @param value Words separated by spaces.
     * @return Words.
     */
    static List<String> words(String value) {
        return value.isBlank() ? List.of() : List.of(value.trim().split("\\s+"));
    }

    /**
     * Describes the Maven of the builds for people.
     *
     * @return Command with the options of the configuration.
     */
    public String mavenText() {
        List<String> w = new ArrayList<>(List.of(mvnExe));

        if (mavenSettings != null)
            w.addAll(List.of("-s", mavenSettings.toString()));

        w.addAll(mavenArgs);

        return String.join(" ", w);
    }

    /** A system property, else an environment variable: what overrides the properties file. */
    private static String override(String name) {
        String v = System.getProperty("ignite.firewatch." + name.toLowerCase(Locale.ROOT).replace('_', '.'));

        return v != null ? v : System.getenv("IGNITE_FIREWATCH_" + name);
    }

    /** The properties file; the default one may be absent, a file named explicitly has to exist. */
    private static synchronized Properties file() {
        if (file == null) {
            String named = override("CONFIG");
            Path p = Path.of(named == null || named.isBlank() ? CONFIG_FILE : named.trim());
            Properties props = new Properties();

            if (Files.isRegularFile(p)) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    props.load(r);
                }
                catch (IOException e) {
                    throw new IllegalStateException("cannot read " + p.toAbsolutePath() + ": " + e.getMessage(), e);
                }
            }
            else if (named != null && !named.isBlank())
                throw new IllegalStateException("the config file " + p.toAbsolutePath() + " does not exist");

            file = props;
        }

        return file;
    }

    /** */
    static boolean isTrue(String v) {
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }

    /**
     * Returns the database file.
     *
     * @return Database file path.
     */
    public Path dbFile() {
        return dataDir.resolve("ignite-firewatch.db");
    }

    /**
     * The start of every harness command line.
     *
     * @param heapMb Heap size of the harness JVM, MB.
     * @return Command line prefix.
     */
    public List<String> harness(int heapMb) {
        return harnessCommand != null ? harnessCommand
            : List.of(javaExe, "-Xmx" + heapMb + "m", "-jar", harnessJar.toAbsolutePath().toString());
    }

    /**
     * Starts every run id of this stand: benchmark JVMs of another stand on the same machine are not ours to kill.
     *
     * @return Run id prefix.
     */
    public String runPrefix() {
        return Integer.toHexString((dataDir.toAbsolutePath().normalize() + instance).hashCode()) + "-";
    }

    /**
     * Returns the directory of run results.
     *
     * @return Runs directory.
     */
    public Path runsDir() {
        return dataDir.resolve("runs");
    }

    /**
     * Returns the directory of the git mirror.
     *
     * @return Mirror directory.
     */
    public Path mirrorDir() {
        return cacheDir.resolve("mirror");
    }

    /**
     * Returns the local Maven repository of the builds, where they install: the last {@code -Dmaven.repo.local} of
     * {@code maven.args}, else {@code m2} of the cache. A relative path starts in the working directory of the
     * application. A repository of {@code -Dmaven.repo.local.tail} is only read by Maven and is not this one.
     *
     * @return Maven repository directory.
     */
    public Path m2Dir() {
        for (int i = mavenArgs.size() - 1; i >= 0; i--) {
            String arg = mavenArgs.get(i);

            if (repoLocal(arg) && arg.length() > REPO_LOCAL.length())
                return Path.of(arg.substring(REPO_LOCAL.length()));
        }

        return cacheDir.resolve("m2");
    }

    /**
     * Tells whether an argument of Maven names its local repository.
     *
     * @param arg Argument.
     * @return Whether it is {@code -Dmaven.repo.local}.
     */
    static boolean repoLocal(String arg) {
        return arg.startsWith(REPO_LOCAL);
    }

    /**
     * Returns the directory of built artifacts.
     *
     * @return Artifacts directory.
     */
    public Path artifactsDir() {
        return cacheDir.resolve("artifacts");
    }

    /**
     * Returns the directory of build working copies.
     *
     * @return Work directory.
     */
    public Path workDir() {
        return cacheDir.resolve("work");
    }

    /**
     * JAVA_HOME for child processes (Maven), derived from {@link #javaExe}.
     *
     * @return Java home path.
     */
    public String javaHome() {
        Path p = Path.of(javaExe).toAbsolutePath().getParent();

        return p != null && p.getParent() != null ? p.getParent().toString() : System.getProperty("java.home");
    }

    /**
     * Returns the controller version.
     *
     * @return Implementation version, or {@code dev} if unknown.
     */
    public static String version() {
        String v = Config.class.getPackage().getImplementationVersion();

        return v == null ? "dev" : v;
    }
}
