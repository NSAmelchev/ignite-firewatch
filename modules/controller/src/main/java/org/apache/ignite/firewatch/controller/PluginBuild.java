package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.w3c.dom.Element;

/**
 * Builds the extension of a plugin next to Ignite, see {@link Builder.Extension}: the build of Ignite (with the modules
 * of the scenario) is installed, then the module of the extension is built against it, with what its pom wants. What the
 * extension leaves {@code provided} (Spring for spring-data-ext) comes with it: the stand is the application that
 * provides it. A library both have stays the one of Ignite.
 */
final class PluginBuild implements Builder.Extension {
    /** */
    private final Config cfg;

    /** */
    private final Plugins plugins;

    /**
     * @param cfg Configuration.
     * @param plugins Plugins.
     */
    PluginBuild(Config cfg, Plugins plugins) {
        this.cfg = cfg;
        this.plugins = plugins;
    }

    /** {@inheritDoc} */
    @Override public void build(String key, Builder.Step step) throws IOException, InterruptedException {
        String[] part = Builder.pluginOf(key);
        Plugins.Plugin p = plugins.require(part[0]);
        String extSha = part[1];
        // next to the work tree of Ignite, not inside: Maven would take its .mvn for the extension's
        Path ext = step.ignite.resolveSibling(step.ignite.getFileName() + "_ext");

        try {
            log(step.log, "export " + extSha + " of " + p.mirror.url() + " to " + ext);
            p.mirror.export(extSha, ext);
            step.checkCancelled();

            PluginPoms.Found f = PluginPoms.igniteParent(path -> {
                Path file = ext.resolve(path);

                return Files.isRegularFile(file) ? Files.readString(file) : null;
            }, p.module);

            if (!f.version().equals(step.version)) {
                Path pom = ext.resolve(f.pom());
                Files.writeString(pom, PluginPoms.withParentVersion(Files.readString(pom), step.version));
                log(step.log, f.pom() + ": " + PluginPoms.PARENT + " " + f.version() + " -> " + step.version
                    + ", the Ignite of this build");
            }

            ignitePoms(step);
            List<Path> poms = poms(ext);
            checkIgniteDeps(ext.resolve(p.module).resolve("pom.xml"), poms, Builder.modulesOf(key), step.group);
            step.emptyTestJars(ext, reactorOf(ext.resolve(p.module).resolve("pom.xml"), poms));
            // -nsu: the extension may name the snapshot repository of Apache, whose Ignite of another commit must not
            // replace the one of this build
            step.mvn("mvn install " + p.module, ext, List.of("-nsu", "-pl", p.module, "-am", "install"));

            Path deps = step.tmp.resolve("ext-libs");
            Util.deleteTree(deps);
            Files.createDirectories(deps);
            step.mvn("mvn copy-dependencies " + p.module, ext, List.of("-nsu", "-pl", p.module,
                "dependency:copy-dependencies", "-DincludeScope=compile", "-DoutputDirectory=" + deps.toAbsolutePath()));
            Path jar = moduleJar(ext.resolve(p.module).resolve("target"));
            Files.copy(jar, deps.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            merge(deps, step.libs, step.log);
        }
        finally {
            Util.deleteTree(ext);
        }
    }

    /** {@inheritDoc} */
    @Override public void fake(String key, Path libs, Path log) throws IOException {
        Plugins.Plugin p = plugins.require(Builder.pluginOf(key)[0]);
        String name = p.jar.getFileName().toString().replaceAll("\\.jar$", "");
        Path src = p.jar.resolveSibling(name + "-libs");

        if (!Files.isDirectory(src))
            throw new IOException("FAKE_BUILD: " + src + " does not exist: the build of the plugin puts the jars of "
                + "the release of its extension there");

        log(log, "FAKE_BUILD: the jars of the extension from " + src);
        merge(src, libs, log);
    }

    /**
     * {@code ignite-parent} and {@code ignite-bom} at the version of this build: the extension names the first as its
     * parent and imports the second, and the install of core puts neither into the local repository for sure.
     */
    private void ignitePoms(Builder.Step step) throws IOException, InterruptedException {
        List<String> dirs = new ArrayList<>();

        for (String[] a : new String[][] {{"parent", PluginPoms.PARENT}, {"modules/bom", "ignite-bom"}}) {
            Path pom = cfg.m2Dir().resolve(step.group.replace('.', '/')).resolve(a[1]).resolve(step.version)
                .resolve(a[1] + "-" + step.version + ".pom");

            if (!Files.exists(pom) && Files.isRegularFile(step.ignite.resolve(a[0]).resolve("pom.xml")))
                dirs.add(a[0]);
        }

        if (!dirs.isEmpty())
            step.mvn("mvn install " + String.join(", ", dirs), step.ignite, List.of("-pl", String.join(",", dirs),
                "install"));
    }

    /** Every pom of the repository of the extension. */
    private static List<Path> poms(Path ext) throws IOException {
        try (Stream<Path> s = Files.walk(ext)) {
            return s.filter(f -> f.getFileName().toString().equals("pom.xml"))
                .filter(f -> !f.toString().contains("/target/") && !f.toString().contains("/.git/"))
                .sorted().toList();
        }
    }

    /**
     * The poms Maven builds with the module ({@code -am}): the module, the modules of the repository it depends on, and
     * their parents in the repository. Their test-scope Ignite artifacts are what Maven resolves.
     *
     * @param modulePom Pom of the module.
     * @param poms Every pom of the repository.
     * @return The poms of the build.
     */
    static List<Path> reactorOf(Path modulePom, List<Path> poms) throws IOException {
        Map<String, Path> byArtifact = new HashMap<>();

        for (Path pom : poms)
            byArtifact.put(Builder.childText(Builder.parsePom(pom), "artifactId"), pom);

        Deque<Path> todo = new ArrayDeque<>(List.of(modulePom));
        Set<Path> out = new LinkedHashSet<>();

        while (!todo.isEmpty()) {
            Path pom = todo.pop();

            if (!out.add(pom))
                continue;

            Element project = Builder.parsePom(pom);

            for (Element parent : Builder.children(project, "parent")) {
                Path p = byArtifact.get(Builder.childText(parent, "artifactId"));

                if (p != null)
                    todo.add(p);
            }

            for (Element deps : Builder.children(project, "dependencies")) {
                for (Element d : Builder.children(deps, "dependency")) {
                    Path p = byArtifact.get(Builder.childText(d, "artifactId"));

                    if (p != null)
                        todo.add(p);
                }
            }
        }

        return List.copyOf(out);
    }

    /**
     * The modules of Ignite the extension needs, out of test scope, have to be those of this build: an older build of
     * the same version may lie in the local repository, and the extension would quietly run with it.
     *
     * @param modulePom Pom of the module of the extension.
     * @param poms Every pom of its repository: their artifacts are built with it.
     * @param modules Modules of Ignite this build has.
     * @param group Group of the Ignite of this build.
     */
    static void checkIgniteDeps(Path modulePom, List<Path> poms, List<String> modules, String group)
        throws IOException {
        Set<String> own = new TreeSet<>();

        for (Path pom : poms)
            own.add(Builder.childText(Builder.parsePom(pom), "artifactId"));

        Element project = Builder.parsePom(modulePom);

        for (Element deps : Builder.children(project, "dependencies")) {
            for (Element d : Builder.children(deps, "dependency")) {
                String grp = Builder.childText(d, "groupId"), a = Builder.childText(d, "artifactId");

                if ("test".equals(Builder.childText(d, "scope")) || a == null || !a.startsWith("ignite-")
                    || !(group.equals(grp) || "${project.groupId}".equals(grp)))
                    continue;

                if (a.equals("ignite-core") || own.contains(a) || modules.contains(a.substring("ignite-".length())))
                    continue;

                throw new IOException("the extension needs " + a + ", which this build does not have: add \""
                    + a.substring("ignite-".length()) + "\" to modules of the scenario");
            }
        }
    }

    /** The jar of the module in its target: not the sources, the tests or the javadoc. */
    private static Path moduleJar(Path target) throws IOException {
        try (Stream<Path> s = Files.list(target)) {
            return s.filter(f -> {
                String n = f.getFileName().toString();

                return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-tests.jar")
                    && !n.endsWith("-javadoc.jar") && !n.startsWith("original-");
            }).findFirst().orElseThrow(() -> new IOException("no jar of the module in " + target));
        }
    }

    /**
     * Adds the jars of {@code from} to {@code libs}. A library that is there in another version stays as it is: Ignite
     * runs with its own, and the log says what was left out.
     *
     * @param from Jars of the extension.
     * @param libs Libs of the build.
     * @param log Build log.
     */
    static void merge(Path from, Path libs, Path log) throws IOException {
        Map<String, String> have = new HashMap<>();

        for (Path jar : jars(libs)) {
            String[] c = Builder.coordinates(jar);

            if (c != null)
                have.put(c[0] + ":" + c[1], c[2]);
        }

        List<String> skipped = new ArrayList<>();
        int added = 0;

        for (Path jar : jars(from)) {
            Path to = libs.resolve(jar.getFileName());

            if (Files.exists(to))
                continue; // the same file: the same artifact at the same version

            String[] c = Builder.coordinates(jar);

            if (c != null) {
                String ga = c[0] + ":" + c[1], v = have.get(ga);

                if (v != null && !v.equals(c[2])) {
                    skipped.add(c[1] + " " + c[2] + " (kept " + v + ")");
                    continue;
                }

                have.put(ga, c[2]);
            }

            Files.copy(jar, to);
            added++;
        }

        log(log, "the extension added " + added + " jar(s)" + (skipped.isEmpty() ? ""
            : "; left out, a library of Ignite in another version: " + String.join(", ", skipped)));
    }

    /** */
    private static List<Path> jars(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
    }

    /** */
    private static void log(Path log, String line) throws IOException {
        Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
