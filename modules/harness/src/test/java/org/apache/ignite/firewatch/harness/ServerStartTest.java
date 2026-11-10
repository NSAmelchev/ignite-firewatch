package org.apache.ignite.firewatch.harness;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests of reading the server start time from a log. */
class ServerStartTest {
    /** */
    @TempDir Path dir;

    /** */
    @Test void slowestNodeStartIsTaken() throws Exception {
        Files.writeString(dir.resolve("node-0.log"),
            "noise\nHARNESS_READY node=0 startMs=1830 version=2.19.0#20261027-sha1:3f40da54\n");
        Files.writeString(dir.resolve("node-1.log"),
            "HARNESS_READY node=1 startMs=2210 version=2.19.0#20261027-sha1:3f40da54\n");
        assertEquals(2210L, PhaseRunner.maxStartMs(dir, 2));
    }

    /** */
    @Test void theReadyLineIsFoundInALogThatIsNotValidText() throws Exception {
        byte[] head = "noise \u00e9\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] ready = "[15:46:46] HARNESS_READY node=0 startMs=875 version=2.19.0-SNAPSHOT#20261103-sha1:480901f3\n"
            .getBytes();
        byte[] all = new byte[head.length + ready.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(ready, 0, all, head.length, ready.length);
        Files.write(dir.resolve("node-0.log"), all);
        assertEquals(875L, PhaseRunner.maxStartMs(dir, 1));
    }

    /** */
    @Test void oldReadyLineWithoutStartMsGivesNull() throws Exception {
        Files.writeString(dir.resolve("node-0.log"), "HARNESS_READY node=0 version=2.18.0\n");
        assertNull(PhaseRunner.maxStartMs(dir, 1));
    }
}
