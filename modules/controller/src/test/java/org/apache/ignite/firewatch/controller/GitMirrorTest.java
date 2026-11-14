package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        assertNull(mirror.fetchProgress(), "no fetch runs");
    }

    /** What the page shows while the first fetch clones: the last line git rewrote. */
    @Test void theProgressOfAFetchIsTheLastLineOfGit() {
        Map<String, Object> p = GitMirror.progressOf("remote: Enumerating objects: 1000, done.\n"
            + "remote: Counting objects:  50% (5/10)\rremote: Counting objects: 100% (10/10), done.\n"
            + "Receiving objects:  12% (12/100), 1.00 MiB | 2.00 MiB/s\rReceiving objects:  45% (45/100), 210.50 MiB | 12.00 MiB/s\r");
        assertEquals(Map.of("stage", "receiving objects", "pct", 45, "detail", "210.50 MiB | 12.00 MiB/s"), p);
        p = GitMirror.progressOf("Receiving objects: 100% (100/100), 400 MiB | 9 MiB/s, done.\nResolving deltas: 100% (7/7), done.\n");
        assertEquals("resolving deltas", p.get("stage"));
        assertNull(p.get("detail"));
        assertTrue(GitMirror.progressOf("From /x\n * [new branch]      master     -> master\n").isEmpty(), "said nothing of its progress");
    }

    /** The repository is the configuration's: a mirror made for another one follows it after a restart. */
    @Test void aMirrorFollowsTheRepositoryItIsMadeWith() throws Exception {
        new GitMirror(tmp.resolve("fresh"), "https://example.invalid/other.git", "main", Duration.ofMinutes(2)).ensure();
        GitMirror fresh = new GitMirror(tmp.resolve("fresh"), origin.url(), "master", Duration.ofMinutes(2));
        fresh.fetch();
        assertTrue(fresh.ready());
        assertEquals(origin.shas.get(2), fresh.resolve("master"));
    }

    /** A stand without network fetches from its disk: a plain path to a repository or to a bundle of one. */
    @Test void aRepositoryOrABundleOnDiskIsASource() throws Exception {
        GitMirror fromDir = new GitMirror(tmp.resolve("from-dir"), origin.repo.toString(), "master",
            Duration.ofMinutes(2));
        fromDir.fetch();
        assertEquals(origin.shas.get(2), fromDir.resolve("master"));
        assertEquals(origin.shas.get(1), fromDir.resolve("v1"));

        Path bundle = tmp.resolve("disk/ignite.bundle");
        putBundle(bundle);
        GitMirror fromBundle = new GitMirror(tmp.resolve("from-bundle"), bundle.toString(), "master",
            Duration.ofMinutes(2));
        fromBundle.fetch();
        assertEquals(origin.shas.get(2), fromBundle.resolve("master"));

        String c4 = origin.commit("c4 core", Map.of("modules/core/src/main/A.java", "class A { int y; }"));
        GitFixture.git("-C", origin.repo.toString(), "update-ref", "refs/pull/7/head", c4);
        putBundle(bundle); // a fresh copy in place of the old one
        fromBundle.fetch();
        assertEquals(c4, fromBundle.resolve("master"));
        assertEquals(origin.shas.get(1), fromBundle.resolve("v1"));
        assertEquals(c4, fromBundle.resolve("pr/7"));
    }

    /** Writes a bundle of the whole origin next to {@code dest}, then moves it in place, as a person would. */
    private void putBundle(Path dest) throws Exception {
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        GitFixture.git("-C", origin.repo.toString(), "bundle", "create", "--quiet", part.toString(), "--all");
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
