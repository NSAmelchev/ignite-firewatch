package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Calibrator}. */
class CalibratorTest {
    /** */
    private static final Instant NOW = Instant.parse("2026-10-28T03:07:00Z");

    /** */
    @TempDir Path tmp;

    /** */
    private static Freshness.Need need(long hoursAgo) {
        return Freshness.need(NOW.minus(Duration.ofHours(hoursAgo)), NOW, 7);
    }

    /** */
    @Test void whatAMeasurementNeedsFollowsItsAge() {
        assertEquals(Freshness.Need.NOW, Freshness.need(null, NOW, 7), "nothing yet");
        assertEquals(Freshness.Need.NOTHING, need(0));
        assertEquals(Freshness.Need.NOTHING, need(7 * 24 - 1));
        assertEquals(Freshness.Need.WHEN_IDLE, need(7 * 24));
        assertEquals(Freshness.Need.WHEN_IDLE, need(14 * 24 - 1));
        assertEquals(Freshness.Need.NOW, need(14 * 24));
    }

    /** */
    @Test void onlyWhatIsOverdueGoesOnABusyStand() {
        assertFalse(Freshness.goes(Freshness.Need.NOTHING, true));
        assertTrue(Freshness.goes(Freshness.Need.WHEN_IDLE, true));
        assertFalse(Freshness.goes(Freshness.Need.WHEN_IDLE, false));
        assertTrue(Freshness.goes(Freshness.Need.NOW, false));
        assertEquals(0, Freshness.priority(Freshness.Need.WHEN_IDLE), "behind everything else");
        assertEquals(Bisector.AHEAD, Freshness.priority(Freshness.Need.NOW));
    }

    /** */
    @Test void theStandIsIdleSomeTimeAfterItsLastJob() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            assertTrue(Freshness.idle(db, NOW), "it has not worked at all");
            Job j = new Job();
            j.type = Job.SINGLE;
            j.source = "manual";
            j.scenario = "smoke";
            j.shaA = "a".repeat(40);
            j.label = "j";
            db.insertJob(j);
            assertFalse(Freshness.idle(db, NOW), "a job waits");
            j.status = Job.DONE;
            j.finishedAt = NOW.toString();
            db.updateJob(j);
            assertFalse(Freshness.idle(db, NOW.plus(Duration.ofHours(Freshness.IDLE_HOURS)).minusSeconds(1)),
                "it has just ended");
            assertTrue(Freshness.idle(db, NOW.plus(Duration.ofHours(Freshness.IDLE_HOURS))));
        }
    }
}
