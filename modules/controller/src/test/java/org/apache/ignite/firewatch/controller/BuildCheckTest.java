package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link BuildCheck}: a fake java and a fake mvn stand for those of a machine. */
class BuildCheckTest {
    /** */
    @TempDir Path tmp;

    /** */
    GitFixture origin;

    /** */
    Path jdk;

    /** */
    Config cfg;

    /** */
    @BeforeEach void setUp() throws Exception {
        Assumptions.assumeTrue(GitFixture.gitAvailable(), "git is required");
        origin = new GitFixture(tmp.resolve("origin"));
        origin.commit("c1", Map.of("pom.xml", "<project/>"));
        jdk = Files.createDirectories(tmp.resolve("jdk17/bin")).getParent();
        // as java -XshowSettings:properties -version prints it, to stderr
        script(jdk.resolve("bin/java"), "cat >&2 <<OUT\nProperty settings:\n    java.home = " + jdk + "\n"
            + "    java.vendor = Test\n    java.version = 17.0.12\n    user.dir = /x\n\nopenjdk version \"17.0.12\"\nOUT\n");
        Files.writeString(jdk.resolve("bin/javac"), "");
        cfg = new Config();
        cfg.javaExe = jdk.resolve("bin/java").toString();
        cfg.cacheDir = tmp.resolve("cache");
        cfg.dataDir = tmp.resolve("data");
    }

    /** A Maven that runs on the JAVA_HOME it was given, as it should. */
    private Path honestMaven() throws Exception {
        return script(tmp.resolve("mvn"), "echo 'Apache Maven 3.9.6 (abc)'\n"
            + "echo \"Java version: 17.0.12, vendor: Test, Inc., runtime: $JAVA_HOME\"\n");
    }

    /** */
    private BuildCheck check(List<Path> rc) {
        BuildCheck c = new BuildCheck(cfg, rc);
        c.check(origin.repo.toString(), "master");

        return c;
    }

    /** */
    private static BuildCheck.Item item(BuildCheck c, String name) {
        return c.items.stream().filter(i -> i.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    /** */
    private static Path script(Path p, String body) throws Exception {
        Files.writeString(p, "#!/bin/sh\n" + body);
        assertTrue(p.toFile().setExecutable(true));

        return p;
    }

    /** */
    @Test void aMachineThatBuildsPasses() throws Exception {
        cfg.mvnExe = honestMaven().toString();
        cfg.mavenArgs = List.of("-o");
        BuildCheck c = check(List.of());
        assertTrue(c.ok, () -> c.items.stream().map(i -> i.name + " " + i.level + " " + i.text).toList().toString());
        assertEquals(List.of("repository", "java", "javac", "maven", "git", "disk"), c.items.stream().map(i -> i.name).toList());
        assertEquals("master at " + origin.shas.get(0).substring(0, 7), item(c, "repository").text);
        assertEquals("17.0.12 (Test) · " + jdk, item(c, "java").text);
        assertEquals("3.9.6 on the same Java · -o", item(c, "maven").text);
        assertTrue(c.items.stream().allMatch(i -> i.fix == null));
        assertTrue(c.log.contains("$ JAVA_HOME=" + jdk + " MAVEN_OPTS=-Xmx1536m LC_ALL=C " + cfg.mvnExe + " -B -v"),
            "the log has the command as one would type it: " + c.log);
        assertTrue(c.log.contains("| openjdk version \"17.0.12\"") && !c.log.contains("|     user.dir = /x"),
            "of the properties only those it reads: " + c.log);
    }

    /** What the stand on 2026-11-13 had: ~/.mavenrc took Maven to Java 11, javac failed on --release 17. */
    @Test void aMavenOnAnotherJavaFailsAndNamesTheLineThatTakesItThere() throws Exception {
        cfg.mvnExe = script(tmp.resolve("mvn"), "echo 'Apache Maven 3.9.6 (abc)'\n"
            + "echo 'Java version: 11.0.24, vendor: Red Hat, Inc., runtime: /usr/lib/jvm/java-11-openjdk'\n").toString();
        Path rc = Files.writeString(tmp.resolve("mavenrc"),
            "# JAVA_HOME=/commented\nJAVA_HOME=${JAVA_HOME:-/keeps/the/given/one}\nexport JAVA_HOME=/usr/lib/jvm/java-11-openjdk\n");
        BuildCheck c = check(List.of(tmp.resolve("missing"), rc));
        assertFalse(c.ok);
        BuildCheck.Item mvn = item(c, "maven");
        assertEquals(BuildCheck.FAIL, mvn.level);
        assertEquals("3.9.6 runs on Java 11.0.24 (/usr/lib/jvm/java-11-openjdk), not on the java above", mvn.text);
        assertEquals("`" + rc + "` sets `JAVA_HOME=/usr/lib/jvm/java-11-openjdk`: remove that line, or start Firewatch "
            + "with `MAVEN_SKIP_RC=1`", mvn.fix);
        assertEquals(BuildCheck.OK, item(c, "java").level, "the java of the configuration is right");
    }

    /** */
    @Test void aJreAndAMissingMavenFail() throws Exception {
        Files.delete(jdk.resolve("bin/javac"));
        cfg.mvnExe = tmp.resolve("no-maven/bin/mvn").toString();
        BuildCheck c = check(List.of());
        assertEquals(BuildCheck.FAIL, item(c, "javac").level);
        assertTrue(item(c, "javac").fix.contains("openjdk-devel"));
        assertEquals(BuildCheck.FAIL, item(c, "maven").level);
        assertTrue(item(c, "maven").text.startsWith("cannot run " + cfg.mvnExe), item(c, "maven").text);
    }

    /** */
    @Test void anOldJavaFailsAndIsNotAskedFurther() throws Exception {
        script(jdk.resolve("bin/java"), "cat >&2 <<OUT\n    java.home = " + jdk + "\n    java.version = 11.0.2\nOUT\n");
        cfg.mvnExe = honestMaven().toString();
        BuildCheck c = check(List.of());
        assertEquals(BuildCheck.FAIL, item(c, "java").level);
        assertTrue(item(c, "java").text.endsWith(": needs 17 or newer"), item(c, "java").text);
        assertTrue(c.items.stream().noneMatch(i -> i.name.equals("javac")));
    }

    /** */
    @Test void theRepositoryIsAskedAsTheFormHasIt() throws Exception {
        cfg.mvnExe = honestMaven().toString();
        BuildCheck c = new BuildCheck(cfg, List.of());
        c.check(tmp.resolve("nowhere").toString(), "master");
        assertTrue(item(c, "repository").text.startsWith("cannot read " + tmp.resolve("nowhere") + ": fatal: "),
            item(c, "repository").text);

        c = new BuildCheck(cfg, List.of());
        c.check(origin.repo.toString(), "nope");
        assertEquals("no branch nope at " + origin.repo, item(c, "repository").text);

        c = new BuildCheck(cfg, List.of());
        c.check("not a url", "master");
        assertEquals("repo.url must be a git URL", item(c, "repository").text);
        assertFalse(c.ok);
    }

    /** */
    @Test void fakeBuildsAskNoMaven() throws Exception {
        cfg.fakeBuild = true;
        cfg.mvnExe = tmp.resolve("no-maven").toString();
        BuildCheck c = check(List.of());
        assertTrue(c.ok, "a warning only");
        assertEquals(BuildCheck.WARN, item(c, "builds").level);
        assertTrue(c.items.stream().noneMatch(i -> i.name.equals("maven") || i.name.equals("javac")));
    }

    /** */
    @Test void majorVersions() {
        assertEquals(17, BuildCheck.major("17.0.12"));
        assertEquals(8, BuildCheck.major("1.8.0_402"));
        assertEquals(21, BuildCheck.major("21-ea"));
        assertEquals(0, BuildCheck.major("unknown"));
    }
}
