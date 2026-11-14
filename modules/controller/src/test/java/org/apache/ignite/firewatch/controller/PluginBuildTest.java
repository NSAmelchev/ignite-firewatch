package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link PluginBuild}: what the extension adds to the libs of Ignite, and what it may need. */
class PluginBuildTest {
    /** */
    @TempDir Path tmp;

    /** A jar as Maven builds it: with its pom.properties. */
    private static Path jar(Path dir, String group, String artifact, String version) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(artifact + "-" + version + ".jar");

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(f))) {
            out.putNextEntry(new JarEntry("META-INF/maven/" + group + "/" + artifact + "/pom.properties"));
            out.write(("groupId=" + group + "\nartifactId=" + artifact + "\nversion=" + version + "\n")
                .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        return f;
    }

    /** A library of Ignite stays: the extension gets its own only where Ignite has none. */
    @Test void theLibrariesOfIgniteWinOverThoseOfTheExtension() throws IOException {
        Path libs = tmp.resolve("libs"), ext = tmp.resolve("ext"), log = tmp.resolve("build.log");
        jar(libs, "org.apache.ignite", "ignite-core", "2.20.0-SNAPSHOT");
        jar(libs, "com.fasterxml.jackson.core", "jackson-databind", "2.17.2");
        jar(ext, "org.apache.ignite", "ignite-core", "2.20.0-SNAPSHOT"); // the same file
        jar(ext, "com.fasterxml.jackson.core", "jackson-databind", "2.19.0");
        jar(ext, "org.springframework", "spring-core", "6.2.0");
        jar(ext, "org.apache.ignite", "ignite-spring-data-ext", "3.2.0-SNAPSHOT");
        Files.writeString(ext.resolve("no-pom.jar"), "a jar that says nothing of itself");

        PluginBuild.merge(ext, libs, log);

        try (var s = Files.list(libs)) {
            assertEquals(List.of("ignite-core-2.20.0-SNAPSHOT.jar", "ignite-spring-data-ext-3.2.0-SNAPSHOT.jar",
                "jackson-databind-2.17.2.jar", "no-pom.jar", "spring-core-6.2.0.jar"),
                s.map(p -> p.getFileName().toString()).sorted().toList());
        }

        String said = Files.readString(log);
        assertTrue(said.contains("added 3 jar(s)"), said);
        assertTrue(said.contains("jackson-databind 2.19.0 (kept 2.17.2)"), said);
    }

    /** */
    @Test void aJarSaysWhatItIsWhenMavenBuiltIt() throws IOException {
        Path j = jar(tmp, "org.springframework", "spring-core", "6.2.0");
        assertEquals(List.of("org.springframework", "spring-core", "6.2.0"), List.of(PluginBuild.coordinates(j)));
        Files.writeString(tmp.resolve("x.jar"), "not a jar");
        assertNull(PluginBuild.coordinates(tmp.resolve("x.jar")));
    }

    /** Maven builds the module with what it needs of its repository, and their parents: their test scope counts. */
    @Test void theBuildOfTheModuleIsItsReactorNotTheWholeRepository() throws IOException {
        String p = "<project><parent><artifactId>%s</artifactId></parent><artifactId>%s</artifactId><dependencies>%s"
            + "</dependencies></project>";
        String dep = "<dependency><artifactId>%s</artifactId></dependency>";
        Path module = pom("modules/x/module", String.format(p, "x-parent", "x-module", String.format(dep, "commons")));
        Path parent = pom("modules/x", String.format(p, "ext-internal", "x-parent", ""));
        Path internal = pom("parent-internal", String.format(p, "ignite-parent", "ext-internal", ""));
        Path commons = pom("modules/commons", String.format(p, "ext-internal", "commons", ""));
        Path other = pom("modules/kafka", String.format(p, "ext-internal", "kafka", String.format(dep, "commons")));

        assertEquals(List.of(module, parent, commons, internal),
            PluginBuild.reactorOf(module, List.of(commons, internal, other, parent, module)));
    }

    /** */
    private Path pom(String dir, String text) throws IOException {
        Path f = Files.createDirectories(tmp.resolve(dir)).resolve("pom.xml");
        Files.writeString(f, text);

        return f;
    }

    /** A module of Ignite the extension needs has to be in the build: an older one may lie in the repository. */
    @Test void theModulesOfIgniteTheExtensionNeedsMustBeBuilt() throws IOException {
        String dep = "<dependency><groupId>%s</groupId><artifactId>%s</artifactId><scope>%s</scope></dependency>";
        Path module = Files.createDirectories(tmp.resolve("modules/spring-data-ext/spring-data")).resolve("pom.xml");
        Files.writeString(module, "<project><artifactId>ignite-spring-data-ext</artifactId><dependencies>"
            + String.format(dep, "org.apache.ignite", "ignite-core", "provided")
            + String.format(dep, "org.apache.ignite", "ignite-indexing", "provided")
            + String.format(dep, "org.apache.ignite", "ignite-spring-data-commons", "compile")
            + String.format(dep, "org.apache.ignite", "ignite-spring", "test")
            + String.format(dep, "org.springframework", "spring-context", "provided")
            + "</dependencies></project>");
        Path commons = Files.createDirectories(tmp.resolve("modules/spring-data-commons")).resolve("pom.xml");
        Files.writeString(commons, "<project><artifactId>ignite-spring-data-commons</artifactId></project>");
        List<Path> poms = List.of(module, commons);

        assertDoesNotThrow(() -> PluginBuild.checkIgniteDeps(module, poms, List.of("calcite", "indexing")));
        IOException e = assertThrows(IOException.class, () -> PluginBuild.checkIgniteDeps(module, poms, List.of()));
        assertTrue(e.getMessage().contains("add \"indexing\" to modules of the scenario"), e.getMessage());
        assertFalse(e.getMessage().contains("spring"), "test scope and the own modules of the extension are fine");
    }
}
