package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link PhaseData}. */
class PhaseDataTest {
    /** */
    @TempDir Path dir;

    /** */
    @Test void aHarnessThatDiedSaysWhy() throws Exception {
        Files.writeString(dir.resolve("harness.log"), """
            java -jar harness.jar run --phase B1
            [harness] servers stopped
            Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
            \tat java.base/java.util.Arrays.copyOf(Arrays.java:3537)
            \tat org.apache.ignite.firewatch.harness.Main.main(Main.java:73)
            """);
        PhaseData d = PhaseData.read(dir, "B1", "b".repeat(40));
        assertFalse(d.ok());
        assertEquals("the harness died: Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space",
            d.error);
    }

    /** What else ran on the hosts goes to the run, so the job page can say it. */
    @Test void aNoisyPhaseSaysWhy() throws Exception {
        Files.writeString(dir.resolve("result.json"), "{\"status\": \"ok\", \"throughput\": {\"mean\": 1000.0},"
            + " \"noisy\": [\"foreign CPU 23 % on this machine\", \"steal 2.5 % on bench-1\"]}");
        PhaseData d = PhaseData.read(dir, "B1", "b".repeat(40));
        assertTrue(d.ok());
        Run r = new Run();
        d.fill(r);
        assertEquals("foreign CPU 23 % on this machine; steal 2.5 % on bench-1", r.noise);

        Files.writeString(dir.resolve("result.json"), "{\"status\": \"ok\", \"throughput\": {\"mean\": 1000.0}, \"noisy\": []}");
        Run quiet = new Run();
        PhaseData.read(dir, "B1", "b".repeat(40)).fill(quiet);
        assertNull(quiet.noise, "a quiet phase, or a result of an older harness");
    }

    /** */
    @Test void aHarnessThatNeverStartedSaysSo() throws Exception {
        Files.writeString(dir.resolve("harness.log"), "java -jar harness.jar run --phase A1\n");
        assertEquals("result.json is missing (harness did not start?)",
            PhaseData.read(dir, "A1", "a".repeat(40)).error);
    }
}
