package org.apache.ignite.firewatch.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests of load error counting in {@link LoaderResult}. */
class LoadErrorsTest {
    /** */
    private static LoaderResult load(long errors, long... opsPerSecond) {
        LoaderResult r = new LoaderResult();
        r.errors = errors;
        r.firstError = errors == 0 ? null : "javax.cache.CacheException: Error while applying rule ExposeIndexRule";

        for (long ops : opsPerSecond)
            r.series.ops.add(ops);

        return r;
    }

    /** */
    @Test void aLoadWithoutErrorsMeasuresTheSpeed() {
        assertNull(PhaseRunner.tooManyErrors(load(0, 1000, 1000)));
    }

    /** */
    @Test void aRareErrorDoesNotFailThePhase() {
        assertNull(PhaseRunner.tooManyErrors(load(20, 1000, 1000)), "1 % of all operations");
    }

    /** */
    @Test void aLoadThatKeepsFailingFailsThePhase() {
        assertEquals("44,345 of 140,345 operations failed (32%), the first: javax.cache.CacheException: "
                + "Error while applying rule ExposeIndexRule",
            PhaseRunner.tooManyErrors(load(44_345, 48_000, 48_000)));
    }
}
