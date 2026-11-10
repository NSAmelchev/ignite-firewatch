package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Stats}. */
class StatsTest {
    /** */
    private static PhaseData phase(String name, double thr, double p99, long seed) {
        PhaseData d = new PhaseData();
        d.phase = name;
        d.status = "ok";
        d.metrics.put("throughput", thr);
        d.metrics.put("p99Us", p99);
        d.metrics.put("allocMbPerSec", 100.0);
        Random r = new Random(seed);

        for (int i = 0; i < 90; i++) {
            d.opsSeries.add(thr * (1 + 0.02 * r.nextGaussian()));
            d.p99Series.add(p99 * (1 + 0.05 * r.nextGaussian()));
        }

        return d;
    }

    /** */
    private static List<PhaseData> abba(double a1, double b1, double b2, double a2, double p99A, double p99B) {
        return List.of(phase("A1", a1, p99A, 1), phase("B1", b1, p99B, 2),
            phase("B2", b2, p99B, 3), phase("A2", a2, p99A, 4));
    }

    /** */
    @Test void regressionIsDetected() {
        Stats.Comparison c = Stats.compare(abba(10000, 9000, 9050, 10050, 100, 115),
            Stats.defaultNoiseFloors(), "default", 0);
        Stats.MetricResult thr = c.metrics.get("throughput");
        assertEquals(-9.98, thr.deltaPct, 0.2);
        assertEquals(Stats.REGRESSION, thr.verdict);
        assertNotNull(thr.ciLow);
        assertTrue(thr.ciHigh < 0, "CI upper bound " + thr.ciHigh);
        assertEquals(Stats.REGRESSION, c.metrics.get("p99Us").verdict);
        assertEquals(Stats.REGRESSION, c.verdict);
    }

    /** */
    @Test void improvementIsDetected() {
        Stats.Comparison c = Stats.compare(abba(10000, 11000, 11100, 10050, 100, 90),
            Stats.defaultNoiseFloors(), "default", 0);
        assertEquals(Stats.IMPROVEMENT, c.metrics.get("throughput").verdict);
        assertEquals(Stats.IMPROVEMENT, c.metrics.get("p99Us").verdict);
        assertEquals(Stats.IMPROVEMENT, c.verdict);
    }

    /** */
    @Test void smallDeltaIsNoChange() {
        Stats.Comparison c = Stats.compare(abba(10000, 10100, 10050, 10020, 100, 101),
            Stats.defaultNoiseFloors(), "default", 0);
        assertEquals(Stats.NO_CHANGE, c.metrics.get("throughput").verdict);
        assertEquals(Stats.NO_CHANGE, c.verdict);
        assertEquals("A1", c.phases.get(0));
        assertEquals(4, c.metrics.get("throughput").values.size());
    }

    /** */
    @Test void spreadBetweenRepeatsRaisesTheThreshold() {
        // B is 4 % below A, but A1 and A2 differ by 6 %: inconclusive.
        Stats.Comparison c = Stats.compare(abba(10300, 9600, 9600, 9700, 100, 100),
            Stats.defaultNoiseFloors(), "default", 0);
        Stats.MetricResult thr = c.metrics.get("throughput");
        assertTrue(thr.spreadAPct > 5, "spread " + thr.spreadAPct);
        assertTrue(thr.thresholdPct >= thr.spreadAPct);
        assertEquals(Stats.NO_CHANGE, thr.verdict);
    }

    /** */
    @Test void metricsWithoutSeriesUseThresholdOnly() {
        List<PhaseData> phases = abba(10000, 10000, 10000, 10000, 100, 100);

        for (PhaseData p : phases)
            p.metrics.put("allocMbPerSec", p.phase.startsWith("B") ? 120.0 : 100.0);

        Stats.Comparison c = Stats.compare(phases, Stats.defaultNoiseFloors(), "default", 0);
        Stats.MetricResult alloc = c.metrics.get("allocMbPerSec");
        assertEquals(20, alloc.deltaPct, 0.01);
        assertEquals(Stats.REGRESSION, alloc.verdict);
        assertEquals(Stats.NO_CHANGE, c.verdict, "allocation does not decide the job verdict");
    }

    /** */
    @Test void pooledRunsAverageAllPhasesAndKeepSpreadInsideRuns() {
        // two runs of the same pair on different days: the stand is 5 % faster on day 2, B is 3 % slower
        // within each run
        List<PhaseData> day1 = List.of(phase("A1", 100_000, 300, 1), phase("B1", 97_000, 300, 2),
            phase("B2", 97_000, 300, 3), phase("A2", 100_000, 300, 4));
        List<PhaseData> day2 = List.of(phase("A1", 105_000, 300, 5), phase("B1", 101_850, 300, 6),
            phase("B2", 101_850, 300, 7), phase("A2", 105_000, 300, 8));
        Stats.Comparison c = Stats.comparePooled(List.of(day1, day2), null, "default", 0);
        Stats.MetricResult thr = c.metrics.get("throughput");
        assertEquals(2, c.runs);
        assertEquals(8, thr.values.size(), "phases of both runs, suffixed by run");
        assertEquals(-3.0, thr.deltaPct, 0.01);
        assertEquals(0.0, thr.spreadAPct, 1e-9, "day-to-day drift must not count as repeat spread");
        assertEquals(Stats.REGRESSION, thr.verdict);
        assertEquals(Stats.REGRESSION, c.verdict);
    }

    /** */
    @Test void noiseFloorsFromCalibrations() {
        List<Stats.Comparison> cal = new ArrayList<>();
        double[] deltas = {0.5, -1.0, 3.5, 2.0, -4.0};

        for (double d : deltas) {
            Stats.Comparison c = new Stats.Comparison();
            Stats.MetricResult r = new Stats.MetricResult();
            r.deltaPct = d;
            c.metrics.put("throughput", r);
            cal.add(c);
        }

        Map<String, Double> floors = Stats.noiseFloors(cal);
        assertEquals(4.0, floors.get("throughput"), 1e-9, "p95 of |delta| (nearest rank) = max for 5 samples");
        assertEquals(5.0, floors.get("p99Us"), 1e-9, "no calibration data for p99: default");
        assertEquals(2.0, Stats.noiseFloors(cal.subList(0, 2)).get("throughput"), 1e-9,
            "fewer than 3 calibrations: default");
    }

    /** */
    @Test void bootstrapCiOfIdenticalSeriesCoversZero() {
        List<PhaseData> phases = abba(10000, 10000, 10000, 10000, 100, 100);
        List<List<Double>> a = List.of(phases.get(0).opsSeries, phases.get(3).opsSeries);
        List<List<Double>> b = List.of(phases.get(1).opsSeries, phases.get(2).opsSeries);
        double[] ci = Stats.bootstrapDeltaCi(a, b, 1000, 5, new Random(1));
        assertTrue(ci[0] < 0 && ci[1] > 0, "CI " + ci[0] + ".." + ci[1]);
        assertTrue(ci[1] - ci[0] < 3, "CI should be narrow for 90-sample phases: " + (ci[1] - ci[0]));
    }
}
