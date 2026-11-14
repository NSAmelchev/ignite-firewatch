package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Scenario;

/**
 * The scenarios of this harness: every class with {@link Bench} in {@code org.apache.ignite.firewatch.scenarios},
 * found by scanning the harness jar (or classes directory), so there is no list to keep in sync. Classes are loaded
 * without being initialized or linked, so the catalog works without Ignite on the classpath (the launcher and the
 * controller).
 * <p>
 * A plugin is a jar of scenarios of its own, written against the harness API, that runs Ignite together with an
 * extension built from another repository: {@link #PLUGIN_FILE} in it says which. Its catalog is the classes of that
 * jar; the scenarios of the harness and of a plugin never mix, so an id names a scenario within one of them.
 */
public final class Catalog {
    /** */
    static final String PACKAGE = "org.apache.ignite.firewatch.scenarios";

    /** What a plugin jar says of itself. */
    public static final String PLUGIN_FILE = "META-INF/firewatch-plugin.properties";

    /** The name of a plugin: it goes into build keys and URLs. */
    static final Pattern PLUGIN_NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    /** One class loader per plugin jar: a lookup must give the same classes every time. */
    private static final Map<Path, ClassLoader> PLUGIN_LOADERS = new ConcurrentHashMap<>();

    /**
     * A plugin: its jar and what {@link #PLUGIN_FILE} in it says. {@code repo} and {@code module} name the extension the
     * stand builds next to Ignite ({@code module} is a directory of that repository, as {@code mvn -pl} takes it);
     * {@code pkg}, when given, is the package of the scenarios, so a jar that bundles libraries is not scanned whole.
     */
    public static final class Plugin {
        /** */
        public final Path jar;

        /** */
        public final String name;

        /** */
        public final String title;

        /** */
        public final String description;

        /** */
        public final String repo;

        /** */
        public final String branch;

        /** */
        public final String module;

        /** */
        public final String pkg;

        /** */
        private Plugin(Path jar, Properties p) {
            this.jar = jar.toAbsolutePath().normalize();
            name = p.getProperty("name", "").strip();
            title = p.getProperty("title", name).strip();
            description = p.getProperty("description", "").strip();
            repo = p.getProperty("repo", "").strip();
            branch = p.getProperty("branch", "master").strip();
            module = p.getProperty("module", "").strip().replaceAll("^/+|/+$", "");
            String pk = p.getProperty("package", "").strip();
            pkg = pk.isEmpty() ? null : pk;
        }

        /**
         * Reads {@link #PLUGIN_FILE} of a jar.
         *
         * @param jar Plugin jar.
         * @return The plugin.
         * @throws IllegalArgumentException If the jar cannot be read or says too little.
         */
        public static Plugin read(Path jar) {
            Properties p = new Properties();

            try (JarFile f = new JarFile(jar.toFile())) {
                JarEntry e = f.getJarEntry(PLUGIN_FILE);

                if (e == null)
                    throw new IllegalArgumentException(jar + " is not a plugin: it has no " + PLUGIN_FILE);

                try (InputStream in = f.getInputStream(e)) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            }
            catch (IOException e) {
                throw new IllegalArgumentException("cannot read " + jar + ": " + e.getMessage(), e);
            }

            Plugin pl = new Plugin(jar, p);

            if (!PLUGIN_NAME.matcher(pl.name).matches())
                throw new IllegalArgumentException(jar + ": name '" + pl.name + "' of " + PLUGIN_FILE
                    + " has to be lowercase letters, digits and '-', a letter first");

            if (pl.repo.isEmpty() || pl.module.isEmpty())
                throw new IllegalArgumentException(jar + ": " + PLUGIN_FILE + " has to give repo and module");

            return pl;
        }

        /**
         * What {@code harness.jar list --plugin} prints of the plugin itself.
         *
         * @return Fields by name.
         */
        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("title", title);
            m.put("description", description);
            m.put("repo", repo);
            m.put("branch", branch);
            m.put("module", module);
            m.put("jar", jar.toString());

            return m;
        }
    }

    /** One scenario: its class and what the annotation says. */
    public static final class Entry {
        /** */
        public final Class<?> type;

        /** */
        public final Bench bench;

        /** The plugin of the scenario; null for one of the harness. */
        public final Plugin plugin;

        /** */
        Entry(Class<?> type, Bench bench, Plugin plugin) {
            this.type = type;
            this.bench = bench;
            this.plugin = plugin;
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
            return Json.sha256((plugin == null ? "" : plugin.name + "/") + bench.id() + ":" + bench.version());
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
         * The source files of the class and of its superclasses inside the scenarios package (for a plugin: inside its
         * jar), most specific first.
         *
         * @return File name and text of every source.
         */
        public List<Map<String, String>> sources() {
            List<Map<String, String>> out = new ArrayList<>();

            for (Class<?> c = type; c != null && ours(c); c = c.getSuperclass()) {
                String file = c.getName().replace('.', '/') + ".java";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("file", file.substring(file.lastIndexOf('/') + 1));
                m.put("text", resourceText(type.getClassLoader(), file));
                out.add(m);
            }

            return out;
        }

        /** Whether the class is of the scenarios this entry comes from: its package, or the jar of its plugin. */
        private boolean ours(Class<?> c) {
            if (plugin == null)
                return c.getName().startsWith(PACKAGE + ".");

            CodeSource cs = c.getProtectionDomain().getCodeSource();

            try {
                return cs != null && cs.getLocation() != null
                    && Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize().equals(plugin.jar);
            }
            catch (URISyntaxException | IllegalArgumentException e) {
                return false;
            }
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

            if (plugin != null)
                m.put("plugin", plugin.name);

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
     * Every scenario of the harness, sorted by id; fails on two classes with the same id.
     *
     * @return Scenario entries.
     */
    public static List<Entry> all() {
        return all(null);
    }

    /**
     * Every scenario of the harness, or of a plugin, sorted by id; fails on two classes with the same id.
     *
     * @param pluginJar Jar of a plugin; null for the harness.
     * @return Scenario entries.
     */
    public static List<Entry> all(Path pluginJar) {
        Plugin plugin = pluginJar == null ? null : Plugin.read(pluginJar);
        ClassLoader cl = plugin == null ? Catalog.class.getClassLoader() : loaderOf(plugin.jar);
        List<Entry> out = new ArrayList<>();
        Map<String, String> ids = new LinkedHashMap<>();

        for (String name : plugin == null ? classNames() : classNames(plugin)) {
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

            out.add(new Entry(c, b, plugin));
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
        return get(id, null);
    }

    /**
     * Finds a scenario by id among those of the harness, or of a plugin.
     *
     * @param id Scenario id.
     * @param pluginJar Jar of a plugin; null for the harness.
     * @return Scenario entry.
     */
    public static Entry get(String id, Path pluginJar) {
        List<Entry> all = all(pluginJar);

        for (Entry e : all)
            if (e.id().equals(id))
                return e;

        throw new IllegalArgumentException("no scenario '" + id + "'" + (pluginJar == null ? "" : " in " + pluginJar)
            + "; known: " + all.stream().map(Entry::id).toList());
    }

    /**
     * The loader of a plugin's classes. It asks the harness first: where the jar is on the classpath already (the
     * server and loader JVMs) the classes come from there, and only the launcher and {@code list} load them from the
     * jar itself.
     */
    private static ClassLoader loaderOf(Path jar) {
        return PLUGIN_LOADERS.computeIfAbsent(jar, j -> {
            try {
                return new URLClassLoader(new URL[] {j.toUri().toURL()}, Catalog.class.getClassLoader());
            }
            catch (MalformedURLException e) {
                throw new IllegalArgumentException("bad plugin jar " + j + ": " + e, e);
            }
        });
    }

    /** Class names of a plugin jar: the package it names, else the whole jar. */
    private static List<String> classNames(Plugin p) {
        List<String> names = new ArrayList<>();
        String prefix = p.pkg == null ? "" : p.pkg.replace('.', '/') + "/";

        try (JarFile jar = new JarFile(p.jar.toFile())) {
            jar.stream().map(e -> e.getName()).filter(n -> n.startsWith(prefix) && n.endsWith(".class")
                    && !n.startsWith("META-INF/") && !n.endsWith("module-info.class"))
                .forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
        }
        catch (IOException e) {
            throw new IllegalArgumentException("cannot scan " + p.jar + " for scenarios: " + e, e);
        }

        names.removeIf(n -> n.contains("$"));

        return names;
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
