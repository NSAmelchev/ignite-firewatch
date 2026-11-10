package org.apache.ignite.firewatch.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What a recurring measurement needs, from how old it is. Younger than its {@code keepDays} it needs nothing; older it
 * goes once the stand has been idle for {@link #IDLE_HOURS}, behind everything else; at twice that age it goes whatever
 * the queue holds. A busy stand is left alone, and what is really overdue still happens.
 */
final class Freshness {
    /** */
    static final String OFF = "off";

    /** */
    static final String KEEP = "keep";

    /** */
    static final long IDLE_HOURS = 2;

    /** What a metric needs, by the age of its latest result. */
    enum Need {
        /** Younger than the term: nothing to run. */
        NOTHING,

        /** Older than the term: runs once the stand has been idle. */
        WHEN_IDLE,

        /** Twice older than the term, or never measured: goes ahead of the queue. */
        NOW
    }

    /** {@code measured} is null when there is nothing yet. */
    static Need need(Instant measured, Instant now, int days) {
        if (measured == null)
            return Need.NOW;

        Duration age = Duration.between(measured, now);

        return age.compareTo(Duration.ofDays(days)) < 0 ? Need.NOTHING
            : age.compareTo(Duration.ofDays(2L * days)) < 0 ? Need.WHEN_IDLE : Need.NOW;
    }

    /** */
    static boolean goes(Need need, boolean idle) {
        return need == Need.NOW || (need == Need.WHEN_IDLE && idle);
    }

    /** Overdue goes with the bisections, older ids first, so ahead of their next steps. */
    static int priority(Need need) {
        return need == Need.NOW ? Bisector.AHEAD : 0;
    }

    /** Nothing queued or running, and the last job ended long enough ago. */
    static boolean idle(Db db, Instant now) {
        if (!db.jobs("active", 1).isEmpty())
            return false;

        List<Job> last = db.jobs("history", 1);

        return last.isEmpty() || last.get(0).finishedAt == null
            || Duration.between(Instant.parse(last.get(0).finishedAt), now)
                .compareTo(Duration.ofHours(IDLE_HOURS)) >= 0;
    }

    /** */
    private Freshness() {
    }
}
