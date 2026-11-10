package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link GitMirror}. */
class GitMirrorTest {
    /** */
    @TempDir Path tmp;

    /** */
    GitFixture origin;

    /** */
    GitMirror mirror;

    /** */
    @BeforeEach void setUp() throws Exception {
        Assumptions.assumeTrue(GitFixture.gitAvailable(), "git is required");
        origin = new GitFixture(tmp.resolve("origin"));
        origin.commit("c1 initial", Map.of("pom.xml", "<project/>", "modules/core/src/main/A.java", "class A {}"));
        origin.commit("c2 core change", Map.of("modules/core/src/main/A.java", "class A { int x; }"));
        origin.tag("v1");
        origin.commit("c3 docs", Map.of("README.md", "hi"));
        mirror = new GitMirror(tmp.resolve("mirror"), origin.url(), "master", Duration.ofMinutes(2));
        assertFalse(mirror.ready());
        mirror.fetch();
        assertTrue(mirror.ready());
    }

    /** */
    @Test void aNewStandChoosesItsRepositoryBeforeThereIsAMirror() throws Exception {
        GitMirror fresh = new GitMirror(tmp.resolve("fresh"), "https://example.invalid/other.git", "main", Duration.ofMinutes(2));
        fresh.follow(origin.url(), "master");
        fresh.fetch();
        assertTrue(fresh.ready());
        assertEquals(origin.shas.get(2), fresh.resolve("master"));
    }

    /** */
    @Test void cherryPicksIntoAReleaseAreFound() throws Exception {
        String repo = origin.repo.toString();
        // release branch from c1, c2 (a master commit) cherry-picked into it, then the release is tagged
        GitFixture.git("-C", repo, "checkout", "--quiet", "-b", "rel", origin.shas.get(0));
        GitFixture.git("-C", repo, "-c", "user.name=r", "-c", "user.email=r@example.com",
            "cherry-pick", origin.shas.get(1));
        GitFixture.git("-C", repo, "tag", "9.9.9");
        GitFixture.git("-C", repo, "checkout", "--quiet", "master");
        mirror.fetch();
        String tag = mirror.resolve("9.9.9"), head = mirror.resolve("master");
        java.util.Set<String> picked = mirror.cherryPicked(tag, head, mirror.mergeBase(tag, head));
        assertEquals(java.util.Set.of(origin.shas.get(1)), picked, "c2 is in the release as a cherry-pick, c3 is not");
    }

    /** */
    @Test void latestReleaseTagIsTheHighestXyz() throws Exception {
        origin.tag("2.9.1");
        origin.tag("2.18.0-rc1");
        origin.tag("2.17.0");
        origin.tag("2.18.0");
        origin.tag("ignite-2.19");
        mirror.fetch();
        assertEquals("2.18.0", mirror.latestReleaseTag(), "numeric order, release candidates and other tags ignored");
        assertEquals("2.9.1", mirror.latestReleaseTag("2.9.*"), "a mask narrows the choice");
        assertEquals("2.18.0", mirror.latestReleaseTag("2.18.*"), "a mask takes no release candidates either");
        origin.tag("2.19.0-rc0");
        mirror.fetch();
        assertEquals("2.18.0", mirror.latestReleaseTag(), "a candidate of the next release changes nothing");
        assertNull(mirror.latestReleaseTag("2.19.*"));
        assertNull(mirror.latestReleaseTag("3.*"));
    }

    /** */
    @Test void resolveLogSeqAndRevList() throws Exception {
        String head = origin.shas.get(2);
        assertEquals(head, mirror.resolve("master"));
        assertEquals(head, mirror.resolve("refs/heads/master"));
        assertEquals(origin.shas.get(1), mirror.resolve("v1"));
        assertEquals(origin.shas.get(1), mirror.resolve("master~1"));
        assertEquals(origin.shas.get(0), mirror.resolve(origin.shas.get(0).substring(0, 8)));
        assertThrows(IllegalArgumentException.class, () -> mirror.resolve("no-such-ref"));
        assertThrows(IllegalArgumentException.class, () -> mirror.resolve("--output=/tmp/x"));

        List<CommitInfo> log = mirror.log("master", 10);
        assertEquals(3, log.size());
        assertEquals("c3 docs", log.get(0).subject);
        assertEquals(origin.shas.get(1), log.get(0).parentSha);
        assertEquals("Test", log.get(0).author);
        assertTrue(log.get(0).committedAt.startsWith("20"));
        assertEquals(null, log.get(2).parentSha, "root commit has no parent");

        assertEquals(3, mirror.seq(head));
        assertEquals(1, mirror.seq(origin.shas.get(0)));
        assertEquals(List.of(origin.shas.get(1), origin.shas.get(2)), mirror.revList(origin.shas.get(0), head));
        assertEquals(List.of(), mirror.revList(head, head));
    }

    /** */
    @Test void changedFilesAndMergeBase() throws Exception {
        assertEquals(List.of("modules/core/src/main/A.java"), mirror.changedFiles(origin.shas.get(1)));
        assertEquals(List.of("README.md"), mirror.changedFiles(origin.shas.get(2)));
        assertEquals(2, mirror.changedFiles(origin.shas.get(0)).size(), "root commit lists all files");

        origin.branch("feature", origin.shas.get(1));
        origin.checkout("feature");
        String f = origin.commit("feature work", Map.of("modules/core/src/main/B.java", "class B {}"));
        origin.checkout("master");
        mirror.fetch();
        assertEquals(f, mirror.resolve("feature"));
        assertEquals(origin.shas.get(1), mirror.mergeBase("refs/heads/master", f));
    }

    /** */
    @Test void exportProducesAWorkingTree() throws Exception {
        Path work = tmp.resolve("work");
        mirror.export(origin.shas.get(1), work);
        assertTrue(Files.exists(work.resolve("modules/core/src/main/A.java")));
        assertFalse(Files.exists(work.resolve("README.md")), "README appears only in c3");
        assertTrue(Files.isDirectory(work.resolve(".git")), "a real checkout, plugins that read git metadata work");
        mirror.export(origin.shas.get(2), work);
        assertTrue(Files.exists(work.resolve("README.md")), "export replaces an existing directory");
    }

    /** */
    @Test void prRefsAreFetchedOnDemand() throws Exception {
        origin.branch("pr-work", origin.shas.get(0));
        origin.checkout("pr-work");
        String prSha = origin.commit("pr change", Map.of("modules/core/src/main/C.java", "class C {}"));
        origin.checkout("master");
        GitFixture.git("-C", origin.repo.toString(), "update-ref", "refs/pull/42/head", prSha);
        mirror.fetch();
        assertThrows(IllegalArgumentException.class,
            () -> mirror.resolve("refs/pull/42/head"), "pull heads are not mirrored by default");
        assertEquals(prSha, mirror.resolve("pr/42"));
        assertEquals(prSha, mirror.resolve("#42"));
        assertEquals(origin.shas.get(0), mirror.mergeBase("refs/heads/master", prSha));
    }
}
