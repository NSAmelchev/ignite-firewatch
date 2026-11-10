package org.apache.ignite.firewatch.harness;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests of {@link Stats}. */
class StatsTest {
    /** */
    @Test
    void summary() {
        Stats.Summary s = Stats.summary(List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0));
        assertEquals(5.0, s.mean, 1e-9);
        assertEquals(2.138, s.stdev, 1e-3);
        assertEquals(2.0, s.min);
        assertEquals(9.0, s.max);
        assertEquals(40.0, s.total);
        assertEquals(8, s.samples);
    }

    /** */
    @Test
    void emptyAndSingle() {
        Stats.Summary e = Stats.summary(List.of());
        assertEquals(0, e.samples);
        assertEquals(0.0, e.mean);
        Stats.Summary one = Stats.summary(List.of(3.0));
        assertEquals(3.0, one.mean);
        assertEquals(0.0, one.stdev);
    }
}
