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
import java.util.Map;
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

    /** Where a value comes from, see {@link #describe()}. */
    static final String FILE = "file";

    /** */
    static final String ENVIRONMENT = "environment";

    /** */
    static final String PROPERTY = "system property";

    /** */
    static final String DEFAULT = "default";

    /** The keys {@link #fromEnv()} read: {value, source} by key, in that order. */
    private final Map<String, String[]> entries = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

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
        c.dataDir = Path.of(c.read("DATA_DIR", c.dataDir.toString()));
        c.cacheDir = Path.of(c.read("CACHE_DIR", c.cacheDir.toString()));
        c.harnessJar = Path.of(c.read("HARNESS_JAR", c.harnessJar.toString()));
        c.bindHost = c.read("BIND_HOST", c.bindHost);
        c.port = Integer.parseInt(c.read("PORT", String.valueOf(c.port)));
        c.repoUrl = c.read("REPO_URL", c.repoUrl);
        c.branch = c.read("BRANCH", c.branch);
        c.javaExe = c.read("JAVA", c.javaExe);
        c.mvnExe = c.read("MVN", c.mvnExe);
        c.mavenHeapMb = Integer.parseInt(c.read("MAVEN_HEAP_MB", String.valueOf(c.mavenHeapMb)));
        c.mavenSettings = mavenSettings(c.read("MAVEN_SETTINGS", ""));
        c.mavenArgs = words(c.read("MAVEN_ARGS", ""));
        c.buildTimeoutMin = Integer.parseInt(c.read("BUILD_TIMEOUT_MIN", String.valueOf(c.buildTimeoutMin)));
        c.fetchTimeoutMin = Integer.parseInt(c.read("FETCH_TIMEOUT_MIN", String.valueOf(c.fetchTimeoutMin)));
        c.harnessHeapMb = Integer.parseInt(c.read("HARNESS_HEAP_MB", String.valueOf(c.harnessHeapMb)));
        c.fakeBuild = isTrue(c.read("FAKE_BUILD", "0"));
        c.plugins = Arrays.stream(c.read("PLUGINS", "").split(",")).map(String::trim).filter(x -> !x.isEmpty())
            .toList();
        c.pluginsDir = Path.of(c.read("PLUGINS_DIR", c.pluginsDir.toString()));
        c.fakeLibsDir = Path.of(c.read("FAKE_LIBS_DIR", c.fakeLibsDir.toString()));
        c.phaseGraceSec = Integer.parseInt(c.read("PHASE_GRACE_SEC", String.valueOf(c.phaseGraceSec)));
        c.sshKey = c.read("SSH_KEY", c.sshKey);
        c.sshKnownHosts = c.read("SSH_KNOWN_HOSTS", c.sshKnownHosts);
        c.sshAcceptNewHosts = isTrue(c.read("SSH_ACCEPT_NEW_HOSTS", "false"));

        return c;
    }

    /** */
    static String get(String name, String def) {
        return resolve(name, def)[0];
    }

    /** The value of a key and where it comes from: a system property, then the environment, then the file. */
    private static String[] resolve(String name, String def) {
        String key = name.toLowerCase(Locale.ROOT).replace('_', '.');
        String prop = System.getProperty("ignite.firewatch." + key);
        String v = prop != null ? prop : System.getenv("IGNITE_FIREWATCH_" + name);
        String src = prop != null ? PROPERTY : ENVIRONMENT;

        if (v == null) {
            v = file().getProperty(key);
            src = FILE;
        }

        return v == null || v.isBlank() ? new String[] {def, DEFAULT} : new String[] {v.trim(), src};
    }

    /** Reads a key and keeps what it is and where it came from, for {@link #describe()}. */
    private String read(String name, String def) {
        String[] v = resolve(name, def);
        entries.put(name.toLowerCase(Locale.ROOT).replace('_', '.'), new String[] {v[0] == null ? "" : v[0], v[1]});

        return v[0];
    }

    /**
     * The configuration as {@link #fromEnv()} read it: every key with its value and where it comes from, the file
     * itself first. Read-only for the UI: it is changed in the file, the environment or the system properties, and a
     * restart applies it.
     *
     * @return {key, value, source} of each.
     */
    public List<Map<String, String>> describe() {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(Map.of("key", "config", "value", configFile().toAbsolutePath().toString(), "source",
            System.getProperty("ignite.firewatch.config") != null ? PROPERTY
                : System.getenv("IGNITE_FIREWATCH_CONFIG") != null ? ENVIRONMENT : DEFAULT));

        synchronized (entries) {
            entries.forEach((k, v) -> out.add(Map.of("key", k, "value", v[0], "source", v[1])));
        }

        return out;
    }

    /**
     * The repository of the extension of a plugin: {@code plugin.<name>.repo} of the configuration, else what its jar
     * says.
     *
     * @param plugin Plugin name.
     * @param def What the jar says.
     * @return Repository.
     */
    public String pluginRepo(String plugin, String def) {
        return read("PLUGIN_" + plugin.toUpperCase(Locale.ROOT) + "_REPO", def);
    }

    /**
     * The branch of the master pair of a plugin: {@code plugin.<name>.branch} of the configuration, else what its jar
     * says.
     *
     * @param plugin Plugin name.
     * @param def What the jar says.
     * @return Branch.
     */
    public String pluginBranch(String plugin, String def) {
        return read("PLUGIN_" + plugin.toUpperCase(Locale.ROOT) + "_BRANCH", def);
    }

    /**
     * Whether the value of a configuration key came from anywhere but its default.
     *
     * @param key Key, as {@link #describe()} names it.
     * @return Whether it is set.
     */
    boolean isSet(String key) {
        synchronized (entries) {
            String[] v = entries.get(key);

            return v != null && !DEFAULT.equals(v[1]);
        }
    }

    /**
     * Checks a repository: a URL or a path of git, nothing git would take for an option or a command.
     *
     * @param key Key, for the message.
     * @param url Repository.
     */
    static void checkRepoUrl(String key, String url) {
        if (url == null || !url.matches("(https?://|ssh://|git@|file:/|/)\\S+"))
            throw new IllegalArgumentException(key + " must be a git URL");
    }

    /**
     * Checks a branch name.
     *
     * @param key Key, for the message.
     * @param branch Branch.
     */
    static void checkBranch(String key, String branch) {
        if (branch == null || !branch.matches("[0-9A-Za-z._/][0-9A-Za-z._/-]{0,99}"))
            throw new IllegalArgumentException(key + " must be a branch name");
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

    /** The properties file: {@link #CONFIG_FILE}, or the one {@code IGNITE_FIREWATCH_CONFIG} names. */
    static Path configFile() {
        String named = override("CONFIG");

        return Path.of(named == null || named.isBlank() ? CONFIG_FILE : named.trim());
    }

    /** The properties file; the default one may be absent, a file named explicitly has to exist. */
    private static synchronized Properties file() {
        if (file == null) {
            String named = override("CONFIG");
            Path p = configFile();
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
