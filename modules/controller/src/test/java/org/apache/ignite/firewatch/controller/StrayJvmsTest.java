package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tests of {@link StrayJvms}. */
class StrayJvmsTest {
    /** */
    private static Config stand(String dataDir) {
        Config c = new Config();
        c.dataDir = Path.of(dataDir);

        return c;
    }

    /** */
    @Test void twoContainersHaveTheSameDataDirAndOneInstanceEach() {
        Config one = stand("/data"), two = stand("/data");
        one.instance = "a";
        two.instance = "b";
        assertNotEquals(one.runPrefix(), two.runPrefix());
    }

    /** */
    @Test void theSameDataDirIsTheSameStand() {
        assertEquals(stand("/stands/a").runPrefix(), stand("/stands/b/../a").runPrefix());
        assertNotEquals(stand("/stands/a").runPrefix(), stand("/stands/b").runPrefix());
    }

    /** */
    @Test void aStandSeesOnlyItsOwnJvms() throws Exception {
        Config mine = stand("/stands/a"), other = stand("/stands/b");
        // a compound command: bash stays alive with the marker on its command line instead of becoming sleep
        Process p = new ProcessBuilder("bash", "-c", "sleep 30; true",
            StrayJvms.MARKER + other.runPrefix() + "job1-A1").start();

        try {
            assumeTrue(p.toHandle().info().commandLine().isPresent(), "the OS hides command lines here");
            assertTrue(StrayJvms.find(mine.runPrefix()).isEmpty());
            assertEquals(List.of(p.pid()), StrayJvms.find(other.runPrefix()).stream().map(ProcessHandle::pid).toList());
        }
        finally {
            p.destroyForcibly();
        }
    }
}
