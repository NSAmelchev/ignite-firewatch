package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Scenario;

/**
 * The scenarios of this harness: every class with {@link Bench} in {@code org.apache.ignite.firewatch.scenarios},
 * found by scanning the harness jar (or classes directory), so there is no list to keep in sync. Classes are loaded
 * without being initialized or linked, so the catalog works without Ignite on the classpath (the launcher and the
 * controller).
 */
public final class Catalog {
    /** */
    static final String PACKAGE = "org.apache.ignite.firewatch.scenarios";

    /** One scenario: its class and what the annotation says. */
    public static final class Entry {
        /** */
        public final Class<?> type;

        /** */
        public final Bench bench;

        /** */
        Entry(Class<?> type, Bench bench) {
            this.type = type;
            this.bench = bench;
        }

        /**
         * Returns the scenario id.
         *
         * @return Scenario id from {@link Bench#id()}.
         */
        public String id() {
            return bench.id();
        }

        /**
         * Identity of what is measured: id and version, not the source text, so comments and refactoring keep history.
         *
         * @return Hash of the id and version.
         */
        public String hash() {
            return Json.sha256(bench.id() + ":" + bench.version());
        }

        /**
         * Returns the warmup and measurement time together.
         *
         * @return Total seconds.
         */
        public int totalSeconds() {
            return bench.warmupSec() + bench.measureSec();
        }

        /**
         * A new instance; needs the Ignite jars on the classpath (server and loader JVMs).
         *
         * @return New scenario instance.
         * @throws ReflectiveOperationException If the class cannot be instantiated.
         */
        public Scenario newInstance() throws ReflectiveOperationException {
            return (Scenario)Class.forName(type.getName(), true, type.getClassLoader()).getDeclaredConstructor()
                .newInstance();
        }

        /**
         * The source files of the class and of its superclasses inside the scenarios package, most specific first.
         *
         * @return File name and text of every source.
         */
        public List<Map<String, String>> sources() {
            List<Map<String, String>> out = new ArrayList<>();

            for (Class<?> c = type; c != null && c.getName().startsWith(PACKAGE + "."); c = c.getSuperclass()) {
                String file = c.getName().replace('.', '/') + ".java";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("file", file.substring(file.lastIndexOf('/') + 1));
                m.put("text", resourceText(type.getClassLoader(), file));
                out.add(m);
            }

            return out;
        }

        /**
         * What {@code harness.jar list} prints for the controller.
         *
         * @param withSources Whether to include the source files.
         * @return Scenario description by field name.
         */
        public Map<String, Object> describe(boolean withSources) {
            Bench b = bench;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.id());
            m.put("title", b.title().isEmpty() ? b.id() : b.title());
            m.put("version", b.version());
            m.put("hash", hash());
            m.put("description", b.description());
            m.put("className", type.getSimpleName());
            Map<String, Object> srv = new LinkedHashMap<>();
            srv.put("count", b.servers().count());
            srv.put("heapMb", b.servers().heapMb());
            srv.put("jvmOpts", Arrays.asList(b.servers().jvmOpts()));
            srv.put("group", b.servers().group());
            m.put("servers", srv);
            Map<String, Object> ld = new LinkedHashMap<>();
            ld.put("client", b.loader().client().name().toLowerCase());
            ld.put("count", b.loader().count());
            ld.put("threads", b.loader().threads());
            ld.put("heapMb", b.loader().heapMb());
            ld.put("jvmOpts", Arrays.asList(b.loader().jvmOpts()));
            ld.put("rateOpsPerSec", b.loader().rateOpsPerSec());
            ld.put("group", b.loader().group());
            m.put("loader", ld);
            m.put("modules", Arrays.asList(b.modules()));
            m.put("warmupSec", b.warmupSec());
            m.put("measureSec", b.measureSec());
            m.put("startTimeoutSec", b.startTimeoutSec());
            m.put("setupTimeoutSec", b.setupTimeoutSec());

            if (withSources)
                m.put("sources", sources());

            return m;
        }
    }

    /** */
    private Catalog() {
    }

    /**
     * Every scenario, sorted by id; fails on two classes with the same id.
     *
     * @return Scenario entries.
     */
    public static List<Entry> all() {
        ClassLoader cl = Catalog.class.getClassLoader();
        List<Entry> out = new ArrayList<>();
        Map<String, String> ids = new LinkedHashMap<>();

        for (String name : classNames()) {
            Class<?> c;

            try {
                c = Class.forName(name, false, cl); // not initialized: no Ignite needed until newInstance()
            }
            catch (ClassNotFoundException | LinkageError e) {
                continue;
            }

            Bench b = c.getAnnotation(Bench.class);

            if (b == null || Modifier.isAbstract(c.getModifiers()) || !Scenario.class.isAssignableFrom(c))
                continue;

            String prev = ids.put(b.id(), c.getName());

            if (prev != null)
                throw new IllegalStateException("scenario id '" + b.id() + "' is used by " + prev + " and "
                    + c.getName());

            out.add(new Entry(c, b));
        }

        out.sort(Comparator.comparing(Entry::id));

        return out;
    }

    /**
     * Finds a scenario by id.
     *
     * @param id Scenario id.
     * @return Scenario entry.
     */
    public static Entry get(String id) {
        for (Entry e : all())
            if (e.id().equals(id))
                return e;

        throw new IllegalArgumentException("no scenario '" + id + "'; known: "
            + all().stream().map(Entry::id).toList());
    }

    /** Class names under the scenarios package in the jar or classes directory this class was loaded from. */
    private static List<String> classNames() {
        List<String> names = new ArrayList<>();
        String prefix = PACKAGE.replace('.', '/') + "/";

        try {
            Path loc = Path.of(Catalog.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            if (Files.isDirectory(loc)) {
                Path dir = loc.resolve(prefix);

                if (Files.isDirectory(dir)) {
                    try (Stream<Path> s = Files.walk(dir)) {
                        s.filter(p -> p.toString().endsWith(".class")).forEach(p ->
                            names.add(loc.relativize(p).toString().replace('\\', '/').replace('/', '.')
                                .replaceAll("\\.class$", "")));
                    }
                }
            }
            else {
                try (JarFile jar = new JarFile(loc.toFile())) {
                    jar.stream().map(e -> e.getName()).filter(n -> n.startsWith(prefix) && n.endsWith(".class"))
                        .forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
                }
            }
        }
        catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot scan for scenarios: " + e, e);
        }

        names.removeIf(n -> n.contains("$"));

        return names;
    }

    /** */
    private static String resourceText(ClassLoader cl, String path) {
        try (InputStream in = cl.getResourceAsStream(path)) {
            return in == null ? "(source not packaged: " + path + ")"
                : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return "(cannot read " + path + ": " + e + ")";
        }
    }
}
