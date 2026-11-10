package org.apache.ignite.firewatch.harness;

import org.apache.ignite.firewatch.harness.api.Scale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Scale}. */
class ScaleTest {
    /** */
    @Test void noSizesLeaveEveryNumberAsTheScenarioStatesIt() {
        Scale s = Scale.parse(null);
        assertTrue(s.isNone());
        assertEquals(512, s.heapMb(512, false));
        assertEquals(256, s.regionMb(256, true));
        assertEquals(100_000, s.entries(100_000, 250, 2, false));
        assertEquals(16, s.threads(16));
        assertEquals(1, s.setupTimeoutFactor());
    }

    /** */
    @Test void memorySplitsIntoReserveHeapAndRegion() {
        Scale s = new Scale(4096, 40, 1, 1);
        int heap = s.heapMb(512, false);
        long region = s.regionMb(256, false);
        assertEquals(4096 - 409, heap + region, "10 % stays in reserve");
        assertTrue(region > heap, "put/get lives in the region");
        assertTrue(s.heapMb(512, true) > heap, "SQL needs more heap");
        assertTrue(s.regionMb(256, true) < region);
        assertEquals(heap + region, s.heapMb(512, true) + s.regionMb(256, true), "the same memory either way");
    }

    /** */
    @Test void aSmallNodeKeepsItsReserve() {
        Scale s = new Scale(768, 40, 1, 1);
        assertEquals(768 - 256, s.heapMb(0, false) + s.regionMb(0, false));
    }

    /** */
    @Test void aSmallNodeStillGetsAHeapItCanWorkWith() {
        Scale s = new Scale(768, 40, 1, 1);
        assertEquals(256, s.heapMb(512, false),
            "a share of what is left would give 179 MB, and a node that only collects garbage");
        assertEquals(256, s.regionMb(256, false));
        assertEquals(256, s.heapMb(512, true));
    }

    /** */
    @Test void entriesFillTheRegionsOfAllServers() {
        Scale one = new Scale(4096, 40, 1, 1);
        long bytes = one.regionMb(0, false) * (1L << 20) * 40 / 100;
        assertEquals(bytes / 250, one.entries(100_000, 250, 1, false));
        assertEquals(one.entries(100_000, 250, 1, false) * 1.5, one.forServers(3).entries(100_000, 250, 2, false), 2,
            "three nodes, every key twice");
    }

    /** */
    @Test void aHugeStandDoesNotOverflow() {
        assertEquals(Integer.MAX_VALUE, new Scale(4_000_000, 80, 1, 3).entries(100_000, 1, 1, false));
    }

    /** */
    @Test void threadsScaleAndNeverReachZero() {
        Scale s = new Scale(0, 0, 2.5, 1);
        assertFalse(s.isNone());
        assertEquals(10, s.threads(4));
        assertEquals(1, new Scale(0, 0, 0.1, 1).threads(4));
        assertEquals(512, s.heapMb(512, false), "threads alone do not touch the memory");
    }

    /** */
    @Test void aLargerStandMayPreloadLonger() {
        assertEquals(1, new Scale(768, 10, 1, 1).setupTimeoutFactor());
        assertEquals(2, new Scale(768, 11, 1, 1).setupTimeoutFactor());
        assertEquals(8, new Scale(3072, 20, 1, 1).setupTimeoutFactor());
    }

    /** */
    @Test void itGoesThroughAJvmOption() {
        Scale s = Scale.parse(new Scale(4096, 40, 1.5, 3).format());
        assertEquals(4096, s.nodeMemoryMb);
        assertEquals(40, s.fillPct);
        assertEquals(1.5, s.threadsFactor);
        assertEquals(3, s.servers);
        assertThrows(IllegalArgumentException.class, () -> Scale.parse("1,2"));
    }
}
