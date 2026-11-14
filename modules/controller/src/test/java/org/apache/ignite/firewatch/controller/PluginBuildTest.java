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
        assertEquals(List.of("org.springframework", "spring-core", "6.2.0"), List.of(Builder.coordinates(j)));
        Files.writeString(tmp.resolve("x.jar"), "not a jar");
        assertNull(Builder.coordinates(tmp.resolve("x.jar")));
    }

    /** A shaded jar says of every library it carries, and the one its file is named after is the jar itself. */
    @Test void aShadedJarIsTheOneItsNameSpells() throws IOException {
        Path f = tmp.resolve("ignite-core-2.20.0.jar");

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(f))) {
            for (String a : List.of("ignite-core", "ignite-commons", "ignite-nio")) {
                out.putNextEntry(new JarEntry("META-INF/maven/org.example.ignite/" + a + "/pom.properties"));
                out.write(("groupId=org.example.ignite\nartifactId=" + a + "\nversion=2.20.0\n")
                    .getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        assertEquals(List.of("org.example.ignite", "ignite-core", "2.20.0"), List.of(Builder.coordinates(f)),
            "the one of the three its name spells, not the first of them");

        Path other = tmp.resolve("nothing-like-it-1.0.jar");

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(other))) {
            for (String a : List.of("ignite-core", "ignite-commons")) {
                out.putNextEntry(new JarEntry("META-INF/maven/org.example.ignite/" + a + "/pom.properties"));
                out.write(("groupId=org.example.ignite\nartifactId=" + a + "\nversion=2.20.0\n")
                    .getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        assertNull(Builder.coordinates(other), "a name that matches none of them says nothing");
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

        assertDoesNotThrow(() -> PluginBuild.checkIgniteDeps(module, poms, List.of("calcite", "indexing"),
            Builder.GROUP));
        IOException e = assertThrows(IOException.class,
            () -> PluginBuild.checkIgniteDeps(module, poms, List.of(), Builder.GROUP));
        assertTrue(e.getMessage().contains("add \"indexing\" to modules of the scenario"), e.getMessage());
        assertFalse(e.getMessage().contains("spring"), "test scope and the own modules of the extension are fine");
        assertDoesNotThrow(() -> PluginBuild.checkIgniteDeps(module, poms, List.of(), "org.example.ignite"),
            "a build of another group: Ignite artifacts of Apache are not its own");
    }

    /** What the install of core builds with it ({@code -am}) is of the build too; the rest of the repository is not. */
    @Test void theModulesOfABuildAreThoseOfTheScenarioAndWhatCoreNeeds() throws IOException {
        String p = "<project><parent><artifactId>ignite-parent</artifactId></parent><artifactId>%s</artifactId>"
            + "<dependencies>%s</dependencies></project>";
        pom("ignite/parent", "<project><artifactId>ignite-parent</artifactId></project>");
        pom("ignite/modules/core", String.format(p, "ignite-core",
            "<dependency><artifactId>ignite-commons</artifactId></dependency>"));
        pom("ignite/modules/commons", String.format(p, "ignite-commons", ""));
        pom("ignite/modules/indexing", String.format(p, "ignite-indexing", ""));

        assertEquals(List.of("calcite", "core", "parent", "commons"),
            PluginBuild.builtModules(tmp.resolve("ignite"), "abc123+calcite"), "not indexing: core does not need it");
    }

    /** The group of the Ignite of a fork is a property of the poms of its extension, not of the dependency alone. */
    @Test void theExtensionThatNamesTheGroupOfIgniteByAPropertyIsCheckedAllTheSame() throws IOException {
        String dep = "<dependency><groupId>%s</groupId><artifactId>%s</artifactId><scope>%s</scope></dependency>";
        Path module = pom("security_plugin/modules/core",
            "<project><parent><artifactId>ignite-security-plugin</artifactId></parent>"
            + "<artifactId>security-core</artifactId><dependencies>"
            + String.format(dep, "${ignite.groupId}", "ignite-rest-http", "provided")
            + String.format(dep, "${ignite.groupId}", "ignite-spring", "compile")
            + String.format(dep, "${ignite.groupId}", "ignite-indexing", "test")
            + "</dependencies></project>");
        Path parent = pom("security_plugin",
            "<project><artifactId>ignite-security-plugin</artifactId><properties>"
            + "<ignite.groupId>org.example.ignite</ignite.groupId></properties></project>");
        List<Path> poms = List.of(module, parent);

        IOException e = assertThrows(IOException.class, () -> PluginBuild.checkIgniteDeps(module, poms,
            List.of("spring"), "org.example.ignite"));
        assertTrue(e.getMessage().contains("add \"rest-http\" to modules of the scenario"), e.getMessage());
        assertDoesNotThrow(() -> PluginBuild.checkIgniteDeps(module, poms, List.of("spring", "rest-http"),
            "org.example.ignite"));
        assertDoesNotThrow(() -> PluginBuild.checkIgniteDeps(module, poms, List.of("spring"), Builder.GROUP),
            "org.example.ignite is not the group of Apache, whose build owes the extension nothing of it");
    }
}
