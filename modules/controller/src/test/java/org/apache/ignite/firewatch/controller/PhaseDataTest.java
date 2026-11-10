package org.apache.ignite.firewatch.controller;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    /** */
    @Test void aHarnessThatNeverStartedSaysSo() throws Exception {
        Files.writeString(dir.resolve("harness.log"), "java -jar harness.jar run --phase A1\n");
        assertEquals("result.json is missing (harness did not start?)",
            PhaseData.read(dir, "A1", "a".repeat(40)).error);
    }
}
