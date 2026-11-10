package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** GitHub links pasted into Compare become refs of the mirror. */
class GitMirrorLinksTest {
    /** */
    private final GitMirror m = new GitMirror(Path.of("unused"), "https://github.com/apache/ignite.git", "master", Duration.ofMinutes(1));

    /** */
    @Test void links() {
        assertEquals("pr/13704", m.normalize("https://github.com/apache/ignite/pull/13704"));
        assertEquals("pr/13704", m.normalize("  https://github.com/apache/ignite/pull/13704/files?w=1 "));
        assertEquals("pr/13704", m.normalize("github.com/apache/ignite/pull/13704/"));
        assertEquals("pr/13704@0123abcd", m.normalize("https://github.com/apache/ignite/pull/13704/commits/0123abcd"));
        assertEquals("0123abcd", m.normalize("https://github.com/apache/ignite/commit/0123abcd#diff-1"));
        assertEquals("ignite-2.18", m.normalize("https://github.com/apache/ignite/tree/ignite-2.18"));
        assertEquals("2.18.0", m.normalize("https://github.com/apache/ignite/releases/tag/2.18.0"));
    }

    /** */
    @Test void plainRefsAreKept() {
        assertEquals("pr/13704", m.normalize("pr/13704"));
        assertEquals("master~1", m.normalize(" master~1 "));
    }

    /** */
    @Test void otherRepositoryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> m.normalize("https://github.com/someone/ignite/pull/1"));
    }
}
