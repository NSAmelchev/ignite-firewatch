package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds {@code ignite-core} of a commit (plus its upstream modules) and collects the runtime classpath into
 * {@code <cache>/artifacts/<key>/libs}. A scenario that needs more modules (the SQL engines) gets a build of its own:
 * the key is {@code <sha>+calcite+indexing} instead of the sha, so every other scenario keeps a core-only classpath.
 * Results are cached per key; a failed build is recorded and not retried automatically. {@code FAKE_BUILD} copies
 * release jars instead, for developing the controller without Maven.
 */
public final class Builder {
    /** */
    private static final Pattern CORE_JAR = Pattern.compile("ignite-core-(.+)\\.jar");

    /** */
    static final String OK_MARKER = "OK";

    /** */
    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9-]*");

    /**
     * The group of Apache Ignite. A build of another group (a fork, a distribution) is built all the same: the group of
     * a build is the one its ignite-core says, see {@link #coordinates}.
     */
    static final String GROUP = "org.apache.ignite";

    /** The part of a key that names the extension of a plugin: {@code @<plugin>-<sha of the extension>}. */
    private static final Pattern PLUGIN_PART = Pattern.compile("@([a-z][a-z0-9-]*)-([0-9a-f]{40})$");

    /**
     * What a plugin builds after Ignite, see {@link PluginBuild}. Ignite knows nothing of it but this: a key with
     * {@code @<plugin>-<sha>} is finished by the extension, which runs with the steps of the build of Ignite.
     */
    interface Extension {
        /**
         * Builds the extension of the key against the Ignite just installed and adds its jars to the libs.
         *
         * @param key Build key.
         * @param step The build of Ignite, to run Maven the same way.
         */
        void build(String key, Step step) throws IOException, InterruptedException;

        /**
         * FAKE_BUILD: adds the release jars of the extension to the libs instead.
         *
         * @param key Build key.
         * @param libs Libs directory.
         * @param log Build log.
         */
        void fake(String key, Path libs, Path log) throws IOException;
    }

    /** The build of Ignite as an {@link Extension} sees it: where it is, its version, and Maven the way it runs. */
    final class Step {
        /** The work tree of Ignite. */
        final Path ignite;

        /** For temporary files. */
        final Path tmp;

        /** The version Ignite was built at, the one the extension is built against. */
        final String version;

        /** The group of the Ignite of this build. */
        final String group;

        /** */
        final Path libs;

        /** */
        final Path log;

        /** */
        private final List<String> common;

        /** */
        private final Map<String, String> env;

        /** */
        private final Duration timeout;

        /** */
        private final BooleanSupplier cancelled;

        /** */
        private Step(Path ignite, Path tmp, String version, String group, Path libs, Path log, List<String> common,
            Map<String, String> env, Duration timeout, BooleanSupplier cancelled) {
            this.ignite = ignite;
            this.tmp = tmp;
            this.version = version;
            this.group = group;
            this.libs = libs;
            this.log = log;
            this.common = common;
            this.env = env;
            this.timeout = timeout;
            this.cancelled = cancelled;
        }

        /**
         * Runs Maven in {@code dir} with the options of every build, then {@code args}.
         *
         * @param name What the step does, for the log.
         * @param dir Directory.
         * @param args Goals and options.
         */
        void mvn(String name, Path dir, List<String> args) throws IOException, InterruptedException {
            List<String> cmd = new ArrayList<>(common);
            cmd.addAll(args);
            runStep(name, cmd, dir, env, log, timeout, cancelled);
        }

        /**
         * Installs an empty jar under each Ignite artifact the poms want in test scope and the local repository lacks,
         * as for the modules of Ignite: Maven resolves test scope even when it skips the tests.
         *
         * @param dir Directory to run Maven in.
         * @param poms The poms of the build.
         */
        void emptyTestJars(Path dir, List<Path> poms) throws IOException, InterruptedException {
            Builder.this.emptyTestJars(testOnlyArtifacts(poms, group), group, version, dir, tmp, common, env, log,
                cancelled);
        }

        /** Throws {@link CancelledException} if the job was cancelled. */
        void checkCancelled() {
            Builder.checkCancelled(cancelled);
        }
    }

    /** */
    private final Config cfg;

    /** */
    private final Db db;

    /** */
    private final GitMirror mirror;

    /** Start of this controller: a build that failed before it (an older stand) may be fixed now. */
    private final String startedAt = Db.now();

    /** Builds the extension of a key with a plugin part; null on a stand without plugins. */
    private volatile Extension extension;

    /**
     * Creates a builder.
     *
     * @param cfg Controller config.
     * @param db Database.
     * @param mirror Git mirror to build from.
     */
    public Builder(Config cfg, Db db, GitMirror mirror) {
        this.cfg = cfg;
        this.db = db;
        this.mirror = mirror;
    }

    /**
     * Build key of a commit and the modules a scenario needs: the sha alone for ignite-core, else
     * {@code <sha>+<module>+...}.
     *
     * @param sha Commit sha.
     * @param modules Modules the scenario needs.
     * @return Build key.
     */
    public static String key(String sha, Collection<String> modules) {
        if (modules == null || modules.isEmpty())
            return sha;

        TreeSet<String> sorted = new TreeSet<>();

        for (String m : modules) {
            if (!MODULE.matcher(m).matches())
                throw new IllegalArgumentException("not a module directory name: " + m);

            sorted.add(m);
        }

        return sha + "+" + String.join("+", sorted);
    }

    /**
     * Build key of a pair of a plugin: Ignite with the modules of the scenario, then {@code @<plugin>-<sha>} of the
     * extension built against it.
     *
     * @param sha Commit of Ignite.
     * @param modules Modules the scenario needs.
     * @param plugin Plugin name, or {@code null} for a scenario of the harness.
     * @param extSha Commit of the extension.
     * @return Build key.
     */
    public static String key(String sha, Collection<String> modules, String plugin, String extSha) {
        if (plugin == null)
            return key(sha, modules);

        if (extSha == null || !extSha.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("not a full sha of the extension: " + extSha);

        return key(sha, modules) + "@" + plugin + "-" + extSha;
    }

    /** The key without its plugin part. */
    private static String ignitePart(String key) {
        int at = key.indexOf('@');

        return at < 0 ? key : key.substring(0, at);
    }

    /** */
    static String shaOf(String key) {
        String k = ignitePart(key);
        int i = k.indexOf('+');

        return i < 0 ? k : k.substring(0, i);
    }

    /** */
    static List<String> modulesOf(String key) {
        String k = ignitePart(key);
        int i = k.indexOf('+');

        return i < 0 ? List.of() : List.of(k.substring(i + 1).split("\\+"));
    }

    /**
     * The plugin part of a key.
     *
     * @param key Build key.
     * @return {plugin, sha of the extension}, or {@code null} for a build of Ignite alone.
     */
    static String[] pluginOf(String key) {
        Matcher m = PLUGIN_PART.matcher(key);

        return m.find() ? new String[] {m.group(1), m.group(2)} : null;
    }

    /**
     * "1a2b3c4d5e", "1a2b3c4d5e+calcite+indexing" or "1a2b3c4d5e+calcite+indexing @spring-data 9f8e7d6c5b" for logs
     * and stages.
     *
     * @param key Build key.
     * @return Short label.
     */
    public static String label(String key) {
        String k = ignitePart(key);
        int i = k.indexOf('+');
        String[] p = pluginOf(key);

        return Job.shortSha(shaOf(key)) + (i < 0 ? "" : k.substring(i))
            + (p == null ? "" : " @" + p[0] + " " + Job.shortSha(p[1]));
    }

    /**
     * Sets what builds the extension of a plugin.
     *
     * @param e Extension builder.
     */
    void extension(Extension e) {
        extension = e;
    }

    /**
     * Maven's work tree: no '+' in the path, FMPP (the Calcite parser generation) reads it as an operator; no '@'
     * either, which resource filtering takes for a delimiter.
     */
    private Path workDir(String key) {
        return cfg.workDir().resolve(key.replace('+', '-').replace('@', '_'));
    }

    /**
     * Directory with the libs of a build.
     *
     * @param key Build key.
     * @return Path of the libs directory.
     */
    public Path libsDir(String key) {
        return cfg.artifactsDir().resolve(key).resolve("libs");
    }

    /**
     * True when a usable build is already cached.
     *
     * @param key Build key.
     * @return Whether the build is cached.
     */
    public boolean cached(String key) {
        Build b = db.build(key);

        return b != null && Build.OK.equals(b.status) && Files.exists(libsDir(key).resolve(OK_MARKER))
            && coreJar(libsDir(key)) != null;
    }

    /**
     * Returns the build record (status {@code ok} or {@code failed}); throws {@link CancelledException} if cancelled.
     * {@code reuseFailed}: an earlier failed build of the commit is returned as is instead of building again
     * (automatic jobs: a broken commit is not rebuilt for every scenario and every later segment); manual jobs rebuild,
     * and so does the first automatic job after a restart, since the deploy may have fixed the build.
     *
     * @param key Build key.
     * @param cancelled Tells whether the job was cancelled.
     * @param reuseFailed Whether to return an earlier failed build as is.
     * @return Build record.
     */
    public Build build(String key, BooleanSupplier cancelled, boolean reuseFailed) {
        if (reuseFailed) {
            Build f = db.build(key);

            if (f != null && Build.FAILED.equals(f.status) && f.startedAt != null
                && f.startedAt.compareTo(startedAt) >= 0) {
                Log.info("build of " + label(key) + " failed before, not rebuilt for an automatic job");

                return f;
            }
        }

        if (cached(key)) {
            Build b = db.build(key);
            b.lastUsedAt = Db.now();
            db.saveBuild(b);

            return b;
        }

        Path artifactDir = cfg.artifactsDir().resolve(key);
        Path libs = libsDir(key);
        Path log = artifactDir.resolve("build.log");
        Build b = new Build();
        b.sha = key;
        b.status = Build.BUILDING;
        b.startedAt = Db.now();
        b.libsDir = libs.toString();
        b.logFile = log.toString();
        b.fake = cfg.fakeBuild;
        db.saveBuild(b);
        long t0 = System.nanoTime();

        try {
            Util.deleteTree(artifactDir);
            Files.createDirectories(libs);

            if (cfg.fakeBuild)
                fakeBuild(key, libs, log);
            else
                realBuild(key, libs, log, cancelled);

            Path core = coreJar(libs);

            if (core == null)
                throw new IOException("no ignite-core jar in " + libs);

            Matcher m = CORE_JAR.matcher(core.getFileName().toString());
            b.igniteVersion = m.matches() ? m.group(1) : null;
            b.sizeBytes = Util.sizeOf(libs);
            Files.writeString(libs.resolve(OK_MARKER), Db.now() + "\n");
            b.status = Build.OK;
        }
        catch (CancelledException e) {
            db.deleteBuild(key);

            try {
                Util.deleteTree(artifactDir);
            }
            catch (IOException ignored) {
            }

            throw e;
        }
        catch (Exception e) {
            b.status = Build.FAILED;
            b.error = e.getMessage() == null ? e.toString() : e.getMessage();
            Log.warn("build of " + label(key) + " failed: " + b.error);
        }
        finally {
            try {
                Util.deleteTree(workDir(key));
            }
            catch (IOException ignored) {
            }
        }

        b.finishedAt = Db.now();
        b.lastUsedAt = b.finishedAt;
        b.durationSec = (System.nanoTime() - t0) / 1e9;
        db.saveBuild(b);

        if (Build.OK.equals(b.status))
            Log.info(String.format("built %s in %.0f s (%s, %s)", label(key), b.durationSec, b.igniteVersion,
                Util.humanBytes(b.sizeBytes)));

        return b;
    }

    /**
     * Release jars instead of a build: {@code fakeLibsDir}, or its {@code -sql} sibling (core + both SQL engines) with
     * modules.
     */
    private void fakeBuild(String key, Path libs, Path log) throws IOException {
        Path src = modulesOf(key).isEmpty() ? cfg.fakeLibsDir
            : cfg.fakeLibsDir.resolveSibling(cfg.fakeLibsDir.getFileName() + "-sql");

        if (!Files.isDirectory(src))
            throw new IOException("FAKE_BUILD: " + src + " does not exist (run modules/harness/dev-libs.sh)");

        try (Stream<Path> s = Files.list(src)) {
            for (Path p : (Iterable<Path>)s::iterator) {
                if (p.toString().endsWith(".jar"))
                    Files.copy(p, libs.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Files.writeString(log, "FAKE_BUILD: copied release jars from " + src + " for " + key + "\n");

        if (pluginOf(key) != null)
            extensionOf(key).fake(key, libs, log);

        try {
            Thread.sleep(500);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** */
    private void realBuild(String key, Path libs, Path log, BooleanSupplier cancelled) throws IOException,
        InterruptedException {
        String sha = shaOf(key);
        // absolute: Maven runs in this directory, so a relative cache.dir would point it at work/cache/work/...
        Path work = workDir(key).toAbsolutePath();
        Files.createDirectories(cfg.m2Dir());
        Files.writeString(log, "export " + sha + " to " + work + "\n");
        mirror.export(sha, work);
        checkCancelled(cancelled);
        Path tmp = Files.createDirectories(work.resolve(".tmp"));
        Map<String, String> env = Map.of(
            "JAVA_HOME", cfg.javaHome(),
            "MAVEN_OPTS", "-Xmx" + cfg.mavenHeapMb + "m -Djava.io.tmpdir=" + tmp,
            "LC_ALL", "C");
        List<String> common = mavenCommon(cfg);
        Duration timeout = Duration.ofMinutes(cfg.buildTimeoutMin);

        List<String> install = new ArrayList<>(common);
        install.addAll(List.of("-pl", "modules/core", "-am", "install"));
        runStep("mvn install", install, work, env, log, timeout, cancelled);

        List<String> copy = new ArrayList<>(common);
        copy.addAll(List.of("-pl", "modules/core", "dependency:copy-dependencies", "-DincludeScope=runtime",
            "-DoutputDirectory=" + libs.toAbsolutePath()));
        runStep("mvn copy-dependencies", copy, work, env, log, Duration.ofMinutes(10), cancelled);

        Path target = work.resolve("modules/core/target");
        Path core = null;
        String ver = null; // the modules are built at the version of core

        try (Stream<Path> s = Files.list(target)) {
            for (Path p : (Iterable<Path>)s::iterator) {
                String n = p.getFileName().toString();
                Matcher m = CORE_JAR.matcher(n);

                if (m.matches() && !n.contains("-sources") && !n.contains("-tests") && !n.contains("-javadoc")
                    && !n.startsWith("original-")) {
                    core = p;
                    ver = m.group(1);
                }
            }
        }

        if (core == null)
            throw new IOException("ignite-core jar not found in " + target);

        Files.copy(core, libs.resolve(core.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        String[] coords = coordinates(core);
        String grp = coords == null ? GROUP : coords[0]; // the modules and the extension are of the group of core
        Files.writeString(log, "copied " + core.getFileName() + " (" + grp + ") to " + libs + "\n",
            java.nio.file.StandardOpenOption.APPEND);

        List<String> modules = modulesOf(key);

        if (!modules.isEmpty())
            buildModules(modules, ver, grp, work, tmp, common, env, log, libs, timeout, cancelled);

        if (pluginOf(key) != null)
            extensionOf(key).build(key, new Step(work, tmp, ver, grp, libs, log, common, env, timeout, cancelled));
    }

    /** */
    private Extension extensionOf(String key) throws IOException {
        Extension e = extension;

        if (e == null)
            throw new IOException("no plugins on this stand to build " + label(key));

        return e;
    }

    /**
     * Gets the start of every mvn call of a build: the options of the application, then those of the configuration,
     * which can override them.
     *
     * @param cfg Configuration.
     * @return Command without the goals.
     */
    static List<String> mavenCommon(Config cfg) {
        List<String> c = new ArrayList<>(List.of(cfg.mvnExe, "-B", "-ntp"));

        if (cfg.mavenSettings != null)
            c.addAll(List.of("-s", cfg.mavenSettings.toString()));

        // one local repository for Maven and for the application, which looks for the jars there itself: m2Dir()
        // already answers with the one of maven.args, so the option is not repeated after it
        c.addAll(List.of(Config.REPO_LOCAL + cfg.m2Dir().toAbsolutePath(), "-Dmaven.test.skip=true", "-DskipTests",
            "-Dmaven.javadoc.skip=true", "-Dmaven.source.skip=true", "-Dcheckstyle.skip=true", "-Denforcer.skip=true"));

        for (String arg : cfg.mavenArgs) {
            if (!Config.repoLocal(arg))
                c.add(arg);
        }

        return List.copyOf(c);
    }

    /**
     * Gets the place of an Ignite artifact in the local repository of the builds.
     *
     * @param cfg Configuration.
     * @param artifactId Artifact.
     * @param version Version.
     * @param classifier Classifier, empty for the main jar.
     * @return Jar file, which may not exist.
     */
    static Path inRepo(Config cfg, String artifactId, String version, String classifier) {
        return inRepo(cfg, GROUP, artifactId, version, classifier);
    }

    /**
     * Gets the place of an artifact of a group in the local repository of the builds.
     *
     * @param cfg Configuration.
     * @param group Group.
     * @param artifactId Artifact.
     * @param version Version.
     * @param classifier Classifier, empty for the main jar.
     * @return Jar file, which may not exist.
     */
    static Path inRepo(Config cfg, String group, String artifactId, String version, String classifier) {
        return cfg.m2Dir().resolve(group.replace('.', '/')).resolve(artifactId).resolve(version)
            .resolve(artifactId + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar");
    }

    /**
     * What Maven put into a jar about itself: {groupId, artifactId, version}; null when the jar does not say, or says it
     * of several (a shaded jar).
     *
     * @param jar Jar.
     * @return Coordinates, or {@code null}.
     */
    static String[] coordinates(Path jar) {
        try (java.util.jar.JarFile f = new java.util.jar.JarFile(jar.toFile())) {
            List<java.util.jar.JarEntry> props = f.stream().filter(e -> e.getName().startsWith("META-INF/maven/")
                && e.getName().endsWith("/pom.properties")).toList();

            if (props.size() != 1)
                return null;

            java.util.Properties p = new java.util.Properties();

            try (java.io.InputStream in = f.getInputStream(props.get(0))) {
                p.load(in);
            }

            String g = p.getProperty("groupId"), a = p.getProperty("artifactId"), v = p.getProperty("version");

            return g == null || a == null || v == null ? null : new String[] {g, a, v};
        }
        catch (IOException e) {
            return null;
        }
    }

    /**
     * The extra modules of a scenario on top of the installed core: their jars and runtime dependencies into
     * {@code libs}.
     */
    private void buildModules(List<String> modules, String version, String group, Path work, Path tmp,
        List<String> common,
        Map<String, String> env, Path log, Path libs, Duration timeout, BooleanSupplier cancelled)
        throws IOException, InterruptedException {
        // -Dmaven.test.skip does not compile the tests, but Maven still resolves the test-scope dependencies of the
        // modules, and some are never built here (ignite-core:tests; ignite-clients of calcite before 2.18): an empty
        // jar under each missing name lets Maven resolve it, cheaper than building them; test scope never reaches the
        // runtime classpath copied below
        emptyTestJars(testOnlyArtifacts(work, modules, group), group, version, work, tmp, common, env, log, cancelled);

        List<String> install = new ArrayList<>(common);
        install.addAll(List.of("-pl", String.join(",", modules.stream().map(x -> "modules/" + x).toList()), "install"));
        runStep("mvn install " + String.join(", ", modules), install, work, env, log, timeout, cancelled);

        for (String module : modules) {
            List<String> copy = new ArrayList<>(common);
            copy.addAll(List.of("-pl", "modules/" + module, "dependency:copy-dependencies", "-DincludeScope=runtime",
                "-DoutputDirectory=" + libs.toAbsolutePath()));
            runStep("mvn copy-dependencies " + module, copy, work, env, log, Duration.ofMinutes(10), cancelled);
            Path jar = null;

            try (Stream<Path> s = Files.list(work.resolve("modules").resolve(module).resolve("target"))) {
                for (Path p : (Iterable<Path>)s::iterator) {
                    String n = p.getFileName().toString();

                    if (n.startsWith("ignite-") && n.endsWith("-" + version + ".jar") && !n.startsWith("original-"))
                        jar = p;
                }
            }

            if (jar == null)
                throw new IOException("jar of modules/" + module + " not found");

            Files.copy(jar, libs.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Installs an empty jar under each of {@code artifacts} the local repository lacks, at {@code version}. */
    private void emptyTestJars(List<String[]> artifacts, String group, String version, Path dir, Path tmp,
        List<String> common, Map<String, String> env, Path log, BooleanSupplier cancelled)
        throws IOException, InterruptedException {
        Path empty = tmp.resolve("empty.jar");

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(empty), new Manifest())) {
            // nothing but the manifest
        }

        for (String[] a : artifacts) {
            String artifactId = a[0], classifier = a[1];
            // never over a real jar: ignite-tools is also a dependency of surefire, which loads a class of it even
            // when the tests are skipped, and the install of core has just put it there
            if (Files.exists(inRepo(cfg, group, artifactId, version, classifier)))
                continue;

            List<String> fake = new ArrayList<>(common);
            fake.addAll(List.of("install:install-file", "-Dfile=" + empty.toAbsolutePath(),
                "-DgroupId=" + group, "-DartifactId=" + artifactId, "-Dversion=" + version,
                "-Dpackaging=jar"));

            if (!classifier.isEmpty())
                fake.add("-Dclassifier=" + classifier);

            runStep("mvn install-file (empty " + artifactId + (classifier.isEmpty() ? "" : " " + classifier) + " jar)",
                fake, dir, env, log, Duration.ofMinutes(5), cancelled);
        }
    }

    /**
     * The Ignite artifacts the modules depend on in test scope, as {artifactId, classifier} (classifier {@code tests}
     * for a test-jar, empty for a main jar), in pom order without repeats; the main jars of the modules themselves are
     * left out (Maven takes them from the reactor).
     */
    static List<String[]> testOnlyArtifacts(Path work, List<String> modules) throws IOException {
        return testOnlyArtifacts(work, modules, GROUP);
    }

    /** The same for a build of {@code group}. */
    static List<String[]> testOnlyArtifacts(Path work, List<String> modules, String group) throws IOException {
        List<Path> poms = new ArrayList<>();

        for (String module : modules)
            poms.add(work.resolve("modules").resolve(module).resolve("pom.xml"));

        return testOnlyArtifacts(poms, group);
    }

    /**
     * The Ignite artifacts the poms depend on in test scope, see {@link #testOnlyArtifacts(Path, List)}; the artifacts
     * of the poms themselves are left out.
     *
     * @param pomFiles Poms.
     * @param group Group of the Ignite of the build.
     * @return {artifactId, classifier} of each.
     */
    static List<String[]> testOnlyArtifacts(List<Path> pomFiles, String group) throws IOException {
        List<org.w3c.dom.Element> poms = new ArrayList<>();
        Set<String> reactor = new TreeSet<>();

        for (Path pom : pomFiles) {
            org.w3c.dom.Element project = parsePom(pom);
            poms.add(project);
            reactor.add(childText(project, "artifactId"));
        }

        List<String[]> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();

        for (org.w3c.dom.Element project : poms) {
            for (org.w3c.dom.Element deps : children(project, "dependencies")) {
                for (org.w3c.dom.Element d : children(deps, "dependency")) {
                    String grp = childText(d, "groupId"), artifact = childText(d, "artifactId");

                    if (!"test".equals(childText(d, "scope")) || artifact == null || !artifact.startsWith("ignite-")
                        || !(group.equals(grp) || "${project.groupId}".equals(grp)))
                        continue;

                    String classifier = childText(d, "classifier");

                    if (classifier == null)
                        classifier = "test-jar".equals(childText(d, "type")) ? "tests" : "";

                    if (classifier.isEmpty() && reactor.contains(artifact))
                        continue;

                    if (seen.add(artifact + ":" + classifier))
                        out.add(new String[] {artifact, classifier});
                }
            }
        }

        return out;
    }

    /** */
    static org.w3c.dom.Element parsePom(Path pom) throws IOException {
        return parsePom(Files.readString(pom), pom.toString());
    }

    /**
     * Parses the text of a pom.
     *
     * @param text Pom text.
     * @param name What the text is, for the error.
     * @return The project element.
     */
    static org.w3c.dom.Element parsePom(String text, String name) throws IOException {
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);

            return f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(text)))
                .getDocumentElement();
        }
        catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("cannot read " + name + ": " + e.getMessage(), e);
        }
    }

    /** */
    static List<org.w3c.dom.Element> children(org.w3c.dom.Element e, String name) {
        List<org.w3c.dom.Element> out = new ArrayList<>();

        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof org.w3c.dom.Element c && name.equals(c.getTagName()))
                out.add(c);
        }

        return out;
    }

    /** */
    static String childText(org.w3c.dom.Element e, String name) {
        List<org.w3c.dom.Element> c = children(e, name);

        return c.isEmpty() ? null : c.get(0).getTextContent().trim();
    }

    /** */
    private void runStep(String name, List<String> cmd, Path cwd, Map<String, String> env, Path log, Duration timeout,
        BooleanSupplier cancelled) throws IOException, InterruptedException {
        Files.writeString(log, "\n=== " + name + ": " + String.join(" ", cmd) + "\n",
            java.nio.file.StandardOpenOption.APPEND);
        long t0 = System.nanoTime();
        Proc.Handle h = Proc.start(cmd, cwd, env, log);
        Integer rc;

        try {
            rc = h.waitFor(timeout, cancelled);
        }
        catch (InterruptedException e) {
            // controller shutdown: never leave mvn behind; the job ends as interrupted, not failed
            h.killTree(Duration.ofSeconds(10));
            Thread.currentThread().interrupt();

            throw new CancelledException("controller stopped during " + name);
        }
        catch (RuntimeException e) {
            h.killTree(Duration.ofSeconds(10));

            throw e;
        }

        if (rc == null) {
            h.killTree(Duration.ofSeconds(10));

            if (cancelled != null && cancelled.getAsBoolean())
                throw new CancelledException("cancelled during " + name);

            throw new IOException(name + " timed out after " + timeout.toMinutes() + " min");
        }

        if (rc != 0) {
            if (cancelled != null && cancelled.getAsBoolean())
                throw new CancelledException("cancelled during " + name);

            throw new IOException(name + " failed with exit code " + rc + ": " + lastErrorLine(log));
        }

        Files.writeString(log, String.format("=== %s finished in %.0f s%n", name, (System.nanoTime() - t0) / 1e9),
            java.nio.file.StandardOpenOption.APPEND);
    }

    /** */
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean())
            throw new CancelledException("cancelled");
    }

    /** First {@code [ERROR]} line of a Maven log, else the last non-empty line. */
    static String lastErrorLine(Path log) {
        try {
            List<String> lines = Files.readAllLines(log);
            String last = "";

            for (String l : lines) {
                if (l.startsWith("[ERROR]") && !l.contains("To see the full stack trace") && !l.contains("Re-run Maven")
                    && !l.contains("[Help") && l.length() > 8)

                    return trim(l);

                if (!l.isBlank())
                    last = l;
            }

            return trim(last);
        }
        catch (IOException e) {
            return "";
        }
    }

    /** */
    private static String trim(String l) {
        return l.length() > 300 ? l.substring(0, 300) + "..." : l;
    }

    /** */
    static Path coreJar(Path libs) {
        if (!Files.isDirectory(libs))
            return null;

        try (Stream<Path> s = Files.list(libs)) {
            return s.filter(p -> CORE_JAR.matcher(p.getFileName().toString()).matches()).findFirst().orElse(null);
        }
        catch (IOException e) {
            return null;
        }
    }

    /**
     * Evicts least recently used artifacts above {@code maxBytes}, never touching {@code keep}. Returns evicted shas.
     *
     * @param maxBytes Size limit of the cache in bytes.
     * @param keep Shas that must stay.
     * @return Evicted shas.
     */
    public List<String> evict(long maxBytes, Set<String> keep) {
        List<Build> builds = db.builds(10_000);
        builds.sort(Comparator.comparing((Build b) -> b.lastUsedAt == null ? "" : b.lastUsedAt));
        long total = 0;

        for (Build b : builds)
            total += b.sizeBytes == null ? 0 : b.sizeBytes;

        List<String> evicted = new ArrayList<>();

        for (Build b : builds) {
            if (total <= maxBytes)
                break;

            if (keep.contains(shaOf(b.sha)) || Build.BUILDING.equals(b.status))
                continue;

            try {
                Util.deleteTree(cfg.artifactsDir().resolve(b.sha));
                db.deleteBuild(b.sha);
                total -= b.sizeBytes == null ? 0 : b.sizeBytes;
                evicted.add(b.sha);
            }
            catch (IOException e) {
                Log.warn("cannot evict artifacts of " + b.sha + ": " + e);
            }
        }

        if (!evicted.isEmpty())
            Log.info("evicted artifacts of " + evicted.size() + " builds");

        return evicted;
    }
}
