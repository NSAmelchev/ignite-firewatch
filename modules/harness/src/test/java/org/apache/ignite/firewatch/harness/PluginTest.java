package org.apache.ignite.firewatch.harness;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scenarios of a plugin: a jar of its own, compiled against the harness API, found and run like the harness's. */
class PluginTest {
    /** A scenario as a plugin writes it: another package, not on the classpath of the harness. */
    private static final String SOURCE = String.join("\n",
        "package org.example.fw;",
        "",
        "import java.util.concurrent.ThreadLocalRandom;",
        "import org.apache.ignite.firewatch.harness.api.Bench;",
        "import org.apache.ignite.firewatch.harness.api.Client;",
        "import org.apache.ignite.firewatch.harness.api.Loader;",
        "import org.apache.ignite.firewatch.harness.api.Scenario;",
        "import org.apache.ignite.firewatch.harness.api.Session;",
        "",
        "@Bench(id = \"calcite-point\", version = 2, description = \"a scenario of a plugin\",",
        "    modules = {\"indexing\", \"calcite\"}, loader = @Loader(client = Client.THIN, threads = 4))",
        "public class PluginPoint extends Scenario {",
        "    @Override public void setup(Session s) {",
        "    }",
        "",
        "    @Override public void op(Session s, ThreadLocalRandom rnd) {",
        "    }",
        "}",
        "");

    /** */
    private static final String DESCRIPTOR = String.join("\n",
        "name=spring-data",
        "title=Spring Data",
        "repo=https://github.com/apache/ignite-extensions.git",
        "module=/modules/spring-data-ext/spring-data/",
        "");

    /** */
    @TempDir Path tmp;

    /** Compiles {@link #SOURCE} and packs it, its source and {@code descriptor} into a jar. */
    private Path pluginJar(String descriptor) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/org/example/fw")).resolve("PluginPoint.java");
        Files.writeString(src, SOURCE);
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", classes.toString(),
            src.toString());
        assertEquals(0, rc, "the scenario of the plugin compiles against the harness API");

        Path jar = tmp.resolve("firewatch-spring-data.jar");

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (descriptor != null)
                put(out, Catalog.PLUGIN_FILE, descriptor.getBytes(StandardCharsets.UTF_8));

            put(out, "org/example/fw/PluginPoint.java", Files.readAllBytes(src));

            try (Stream<Path> s = Files.walk(classes)) {
                for (Path p : s.filter(Files::isRegularFile).toList())
                    put(out, classes.relativize(p).toString().replace(File.separatorChar, '/'), Files.readAllBytes(p));
            }
        }

        return jar;
    }

    /** */
    private static void put(JarOutputStream out, String name, byte[] data) throws Exception {
        out.putNextEntry(new JarEntry(name));
        ((OutputStream)out).write(data);
        out.closeEntry();
    }

    /** */
    @Test void theScenariosOfAPluginComeFromItsJarOnly() throws Exception {
        Path jar = pluginJar(DESCRIPTOR);
        List<Catalog.Entry> all = Catalog.all(jar);
        assertEquals(List.of("calcite-point"), all.stream().map(Catalog.Entry::id).toList());

        Catalog.Entry e = all.get(0);
        assertEquals("spring-data", e.plugin.name);
        assertEquals("modules/spring-data-ext/spring-data", e.plugin.module, "the slashes of the ends go");
        assertEquals("master", e.plugin.branch, "the default branch");
        assertEquals(Json.sha256("spring-data/calcite-point:2"), e.hash(), "the plugin is part of what is measured");
        assertEquals("spring-data", e.describe(false).get("plugin"));
        assertEquals(List.of("indexing", "calcite"), e.describe(false).get("modules"));

        List<Map<String, String>> src = e.sources();
        assertEquals(List.of("PluginPoint.java"), src.stream().map(m -> m.get("file")).toList());
        assertTrue(src.get(0).get("text").contains("a scenario of a plugin"), "the source from the jar of the plugin");

        assertEquals("calcite-point", Catalog.get("calcite-point", jar).id());
        assertThrows(IllegalArgumentException.class, () -> Catalog.get("putget-thin-t16", jar),
            "a scenario of the harness is not one of the plugin");
        assertThrows(IllegalArgumentException.class, () -> Catalog.get("calcite-point"),
            "nor is a scenario of the plugin one of the harness");
        assertNull(Catalog.get("putget-thin-t16").plugin);
    }

    /** */
    @Test void aJarIsAPluginOnlyWhenItSaysEnough() throws Exception {
        Path none = pluginJar(null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Catalog.Plugin.read(none)).getMessage()
            .contains(Catalog.PLUGIN_FILE));

        Files.delete(none);
        Path badName = pluginJar(DESCRIPTOR.replace("name=spring-data", "name=Spring Data"));
        assertThrows(IllegalArgumentException.class, () -> Catalog.Plugin.read(badName));

        Files.delete(badName);
        Path noModule = pluginJar(DESCRIPTOR.replaceAll("module=.*", ""));
        assertThrows(IllegalArgumentException.class, () -> Catalog.Plugin.read(noModule));
    }

    /** The jar of the plugin goes before the build: what it bundles wins over a library of the build. */
    @Test void theJarOfThePluginIsOnTheClasspathOfEveryJvm() {
        Path harness = Path.of("/h/harness.jar"), plugin = Path.of("/p/plugin.jar"), libs = Path.of("/b/libs");
        String sep = File.pathSeparator;
        assertEquals("/h/harness.jar" + sep + "/p/plugin.jar" + sep + "/b/libs" + File.separator + "*",
            Jvm.classpath(harness, plugin, libs));
        assertEquals("/h/harness.jar" + sep + "/b/libs" + File.separator + "*", Jvm.classpath(harness, null, libs));
    }
}
