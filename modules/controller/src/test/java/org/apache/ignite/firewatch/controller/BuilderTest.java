package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Builder}. */
class BuilderTest {
    /** */
    @TempDir Path tmp;

    /** */
    @Test void theConfigAddsItsSettingsAndArgumentsToEveryMavenCall() throws Exception {
        Config c = new Config();
        assertFalse(Builder.mavenCommon(c).contains("-s"), "the settings.xml of Maven itself by default");
        Path settings = Files.writeString(tmp.resolve("settings.xml"), "<settings/>");
        c.mavenSettings = Config.mavenSettings(settings.toString());
        c.mavenArgs = Config.words("  -o   -Dmaven.repo.local=/other ");
        List<String> cmd = Builder.mavenCommon(c);
        assertEquals(List.of(c.mvnExe, "-B", "-ntp", "-s", settings.toAbsolutePath().toString()), cmd.subList(0, 5));
        assertEquals(List.of("-o", "-Dmaven.repo.local=/other"), cmd.subList(cmd.size() - 2, cmd.size()),
            "the last ones, so they can override");
        assertEquals(c.mvnExe + " -s " + settings.toAbsolutePath() + " -o -Dmaven.repo.local=/other", c.mavenText());
        assertThrows(IllegalStateException.class, () -> Config.mavenSettings(tmp.resolve("missing.xml").toString()),
            "a wrong path stops the start, not every build");
        assertNull(Config.mavenSettings(" "));
        assertEquals(List.of(), Config.words(""));
    }

    /** */
    @Test void keyIsTheShaOrTheShaWithSortedModules() {
        String sha = "1a2b3c4d5e6f7a8b9c0d1a2b3c4d5e6f7a8b9c0d";
        assertEquals(sha, Builder.key(sha, List.of()));
        assertEquals(sha + "+calcite+indexing", Builder.key(sha, List.of("indexing", "calcite")));
        assertEquals(sha, Builder.shaOf(sha + "+calcite+indexing"));
        assertEquals(List.of("calcite", "indexing"), Builder.modulesOf(sha + "+calcite+indexing"));
        assertEquals(List.of(), Builder.modulesOf(sha));
        assertEquals("1a2b3c4d5e+calcite+indexing", Builder.label(sha + "+calcite+indexing"));
        assertThrows(IllegalArgumentException.class, () -> Builder.key(sha, List.of("../core")));
    }

    /** */
    @Test void testScopeIgniteArtifactsOfTheModulesAreListedForEmptyJars() throws Exception {
        // the poms of a March 2026 commit: calcite still needs ignite-clients in its tests
        String dep = "<dependency><groupId>%s</groupId><artifactId>%s</artifactId>%s<scope>%s</scope></dependency>";
        Files.createDirectories(tmp.resolve("modules/calcite"));
        Files.writeString(tmp.resolve("modules/calcite/pom.xml"),
            "<project><artifactId>ignite-calcite</artifactId><dependencies>"
            + String.format(dep, "${project.groupId}", "ignite-core", "", "compile")
            + String.format(dep, "${project.groupId}", "ignite-indexing", "", "test")
            + String.format(dep, "${project.groupId}", "ignite-core", "<type>test-jar</type>", "test")
            + String.format(dep, "${project.groupId}", "ignite-tools", "", "test")
            + String.format(dep, "${project.groupId}", "ignite-clients", "", "test")
            + String.format(dep, "junit", "junit", "", "test")
            + "</dependencies><profiles><profile><dependencies>"
            + String.format(dep, "${project.groupId}", "ignite-spring", "", "test")
            + "</dependencies></profile></profiles></project>");
        Files.createDirectories(tmp.resolve("modules/indexing"));
        Files.writeString(tmp.resolve("modules/indexing/pom.xml"),
            "<project><artifactId>ignite-indexing</artifactId><dependencies>"
            + String.format(dep, "org.apache.ignite", "ignite-core", "<type>test-jar</type>", "test")
            + String.format(dep, "org.apache.ignite", "ignite-tools", "", "test")
            + "</dependencies></project>");
        List<String> got = Builder.testOnlyArtifacts(tmp, List.of("calcite", "indexing"))
            .stream().map(a -> a[0] + ":" + a[1]).toList();
        assertEquals(List.of("ignite-core:tests", "ignite-tools:", "ignite-clients:"), got,
            "built modules come from the reactor; profiles and other groups are left alone");
    }

    /** */
    @Test void modulesGetABuildOfTheirOwnThatEvictionKeepsWithTheCommit() throws Exception {
        Config cfg = new Config();
        cfg.cacheDir = tmp.resolve("cache");
        cfg.fakeBuild = true;
        cfg.fakeLibsDir = tmp.resolve("dev-libs");
        Files.createDirectories(cfg.fakeLibsDir);
        Files.writeString(cfg.fakeLibsDir.resolve("ignite-core-2.99.0-TEST.jar"), "core");
        Path sql = Files.createDirectories(tmp.resolve("dev-libs-sql"));
        Files.writeString(sql.resolve("ignite-core-2.99.0-TEST.jar"), "core");
        Files.writeString(sql.resolve("ignite-calcite-2.99.0-TEST.jar"), "calcite");

        try (Db db = Db.open(tmp.resolve("b.db"))) {
            Builder builder = new Builder(cfg, db, null);
            String sha = "abcdef0123456789abcdef0123456789abcdef01";
            String key = Builder.key(sha, List.of("calcite", "indexing"));
            assertEquals(Build.OK, builder.build(sha, null, false).status);
            assertFalse(builder.cached(key), "the core-only build does not serve a scenario with modules");
            assertEquals(Build.OK, builder.build(key, null, false).status);
            assertTrue(builder.cached(key));
            assertTrue(Files.exists(builder.libsDir(key).resolve("ignite-calcite-2.99.0-TEST.jar")));
            assertFalse(Files.exists(builder.libsDir(sha).resolve("ignite-calcite-2.99.0-TEST.jar")));

            assertEquals(List.of(), builder.evict(0, Set.of(sha)), "both builds belong to a commit in use");
            assertEquals(2, builder.evict(0, Set.of()).size());
            assertFalse(Files.exists(builder.libsDir(key)));
        }
    }

    /** */
    @Test void mavenGetsAnAbsoluteTmpDirWhenTheCacheDirIsRelative() throws Exception {
        Assumptions.assumeTrue(GitFixture.gitAvailable(), "git is required");
        GitFixture origin = new GitFixture(tmp.resolve("origin"));
        String sha = origin.commit("c1", Map.of("pom.xml", "<project/>"));
        GitMirror mirror = new GitMirror(tmp.resolve("mirror"), origin.url(), "master", Duration.ofMinutes(2));
        mirror.fetch();

        // Maven runs in the work directory of the build, not in that of the application
        Path mvn = Files.writeString(tmp.resolve("mvn"), "#!/bin/sh\n"
            + "for o in $MAVEN_OPTS; do case $o in -Djava.io.tmpdir=*) d=${o#-Djava.io.tmpdir=} ;; esac; done\n"
            + "echo \"tmpdir $d\"\n"
            + "[ -d \"$d\" ] && echo 'tmpdir exists'\n"
            + "exit 1\n");
        assertTrue(mvn.toFile().setExecutable(true));
        Config cfg = new Config();
        cfg.cacheDir = Path.of("").toAbsolutePath().relativize(tmp.resolve("cache").toAbsolutePath());
        cfg.mvnExe = mvn.toString();

        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Build b = new Builder(cfg, db, mirror).build(sha, null, false);
            assertEquals(Build.FAILED, b.status, "the fake mvn fails the build");
            String log = Files.readString(Path.of(b.logFile));
            assertTrue(log.contains("tmpdir /") && log.contains("tmpdir exists"), log);
        }
    }

    /** */
    @Test void aBuildThatFailedBeforeTheRestartIsRetriedOnceForAutomaticJobs() throws Exception {
        Config cfg = new Config();
        cfg.cacheDir = tmp.resolve("cache");
        cfg.fakeBuild = true;
        cfg.fakeLibsDir = Files.createDirectories(tmp.resolve("dev-libs"));
        Files.writeString(cfg.fakeLibsDir.resolve("ignite-core-2.99.0-TEST.jar"), "core");

        try (Db db = Db.open(tmp.resolve("f.db"))) {
            String sha = "abcdef0123456789abcdef0123456789abcdef01";
            Build old = new Build();
            old.sha = sha;
            old.status = Build.FAILED;
            old.startedAt = "2026-01-01T00:00:00Z";
            db.saveBuild(old);
            Builder builder = new Builder(cfg, db, null);
            assertEquals(Build.OK, builder.build(sha, null, true).status, "failed under an older controller: rebuilt");

            Build fresh = db.build(sha);
            fresh.status = Build.FAILED;
            fresh.startedAt = Db.now();
            db.saveBuild(fresh);
            assertEquals(Build.FAILED, builder.build(sha, null, true).status, "failed under this one: reused");
            assertEquals(Build.OK, builder.build(sha, null, false).status, "manual jobs always rebuild");
        }
    }
}
