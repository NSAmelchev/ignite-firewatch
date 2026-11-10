package org.apache.ignite.firewatch.controller;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The state of a metric of master against what was found for it. */
class AttentionTest {
    /** */
    private static List<Attention.Found> commits(double... deltas) {
        return java.util.Arrays.stream(deltas).mapToObj(d -> {
            Attention.Found f = new Attention.Found();
            f.deltaPct = d;

            return f;
        }).toList();
    }

    /** */
    private static Bisect search(String status) {
        Bisect b = new Bisect();
        b.status = status;

        return b;
    }

    /** */
    @Test void commitsThatAddUpToTheDeltaExplainIt() {
        assertEquals(Attention.EXPLAINED, Attention.status(-9.9, 2, commits(-6.1, -3.3), null));
        assertEquals(Attention.EXPLAINED, Attention.status(-4.6, 3, commits(-5.2), null));
        assertEquals(Attention.EXPLAINED, Attention.status(-5, 3, commits(-5), search(Bisect.RUNNING)),
            "nothing is left to search");
    }

    /** */
    @Test void whatTheCommitsDoNotExplainIsLeft() {
        assertEquals(Attention.PARTIAL, Attention.status(-10, 2, commits(-6), null));
        assertEquals(Attention.PARTIAL, Attention.status(-0.5, 3, commits(-5), search(Bisect.FOUND)),
            "found, but master no longer shows it");
        assertEquals(Bisect.RUNNING, Attention.status(-10, 2, commits(-6), search(Bisect.RUNNING)));
    }

    /** */
    @Test void withoutCommitsTheLatestSearchTells() {
        assertEquals(Attention.UNEXPLORED, Attention.status(-3, 2, commits(), null));
        assertEquals(Attention.UNEXPLORED, Attention.status(-3, 2, commits(), search(Bisect.CANCELLED)));
        assertEquals(Bisect.SPREAD, Attention.status(-3, 2, commits(), search(Bisect.SPREAD)));
        assertEquals(Bisect.LOST, Attention.status(-3, 2, commits(), search(Bisect.LOST)));
        assertEquals(Bisect.INCONCLUSIVE, Attention.status(-3, 2, commits(), search(Bisect.INCONCLUSIVE)));
        assertEquals(Bisect.STOPPED, Attention.status(-3, 2, commits(), search(Bisect.STOPPED)));
    }
}
