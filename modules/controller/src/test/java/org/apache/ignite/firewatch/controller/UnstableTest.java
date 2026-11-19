package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unstable runs: repeats of one commit far apart, beyond the usual spread of the scenario. */
class UnstableTest {
    /** Sha of commit A. */
    private static final String A = "a".repeat(40);

    /** Sha of commit B. */
    private static final String B = "b".repeat(40);

    /** */
    @TempDir Path tmp;

    /** */
    private Db db;

    /** */
    private JobService jobs;

    /** */
    @BeforeEach void setUp() throws Exception {
        Config cfg = new Config();
        cfg.dataDir = tmp.resolve("data");
        cfg.cacheDir = tmp.resolve("cache");
        db = Db.open(tmp.resolve("t.db"));
        jobs = new JobService(cfg, db, null, new Scenarios(List.of(Scenarios.Info.of("smoke", 1, 1))));
    }

    /** */
    @AfterEach void tearDown() throws Exception {
        db.close();
    }

    /** A comparison of one run with these ops/s; the other metrics follow the same phases. */
    private static Stats.Comparison run(double a1, double b1, double b2, double a2) {
        List<PhaseData> phases = new ArrayList<>();
        String[] names = {"A1", "B1", "B2", "A2"};
        double[] ops = {a1, b1, b2, a2};

        for (int i = 0; i < 4; i++) {
            PhaseData d = new PhaseData();
            d.phase = names[i];
            d.status = "ok";
            d.metrics.put("throughput", ops[i]);
            d.metrics.put("p99Us", 1e6 / ops[i]);
            phases.add(d);
        }

        return Stats.compare(phases, Stats.defaultNoiseFloors(), "default", 0);
    }

    /** */
    @Test void aPhaseSlowThroughoutMakesTheRunUnstable() {
        Stats.Unstable u = Stats.unstable(run(8587, 9139, 9017, 1213), 3.9);
        assertNotNull(u);
        assertEquals(List.of("A2"), u.slow);
        assertEquals(List.of("A1", "B1", "B2"), u.fast, "in the order the phases ran");
        assertEquals(8914.3 / 1213, u.factor, 0.01, "the mean of the fast phases over the slow one");
        assertEquals(3.9, u.usualPct);
        assertTrue(u.spreadPct > 100);
    }

    /** */
    @Test void aSlowHalfSplitsTheRunInHalves() {
        Stats.Unstable u = Stats.unstable(run(23213, 23423, 37872, 36869), 2.7);
        assertNotNull(u);
        assertEquals(List.of("A1", "B1"), u.slow);
        assertEquals(List.of("B2", "A2"), u.fast);
        assertEquals(1.6, u.factor, 0.05);
    }

    /** */
    @Test void aSpreadWithinFourUsualOnesOrUnderFifteenPercentIsStable() {
        Stats.Comparison c = run(12485, 12006, 12031, 10910); // 13.5 % between A1 and A2
        assertNull(Stats.unstable(c, 6.6), "4 × 6.6 % is above it");
        assertNull(Stats.unstable(c, null), "without history 15 % is the rule");
        assertNull(Stats.unstable(c, 1.0), "never below 15 %");
        assertNotNull(Stats.unstable(run(12485, 12006, 12031, 10000), null), "22 % is over 15 %");
    }

    /** */
    @Test void aPooledComparisonIsNeverMarked() {
        Stats.Comparison c = run(8587, 9139, 9017, 1213);
        c.runs = 2;
        assertNull(Stats.unstable(c, 3.9));
    }

    /** */
    @Test void theUsualSpreadIsThe95thPercentileOfTheLast30StableRuns() {
        List<Double> spreads = new ArrayList<>();

        for (int i = 0; i < 9; i++)
            spreads.add(2.0);

        assertNull(Stats.usualSpread(spreads), "9 runs are too few");
        spreads.add(3.0);
        assertEquals(3.0, Stats.usualSpread(spreads), "of 10 runs the 95th percentile is the largest");

        List<Double> window = new ArrayList<>(List.of(50.0, 50.0));

        for (int i = 0; i < 29; i++)
            window.add(2.0);

        assertEquals(2.0, Stats.usualSpread(window), "of 31 runs the oldest is out: one 50 % of 30 is under the 95th");
        window.add(50.0);
        window.add(50.0);
        assertEquals(50.0, Stats.usualSpread(window), "two of the last 30 are at it");
    }

    /** A finished ABBA job of the scenario with the comparison of {@code c}. */
    private Job done(String source, String shaA, Stats.Comparison c) {
        Job j = new Job();
        j.type = Job.ABBA;
        j.source = source;
        j.scenario = "smoke";
        j.shaA = shaA;
        j.shaB = JobService.SRC_CALIBRATION.equals(source) ? shaA : B;
        j.label = "j";
        db.insertJob(j);
        j.status = Job.DONE;
        j.comparison = Json.MAPPER.valueToTree(c);
        j.verdict = c.verdict;
        j.finishedAt = Db.now();
        db.updateJob(j);

        return j;
    }

    /** */
    @Test void theRunsFinishedBeforeTheRuleAreMarkedOnceAgainstTheRunsBeforeThem() {
        List<Job> stable = new ArrayList<>();

        for (int i = 0; i < 12; i++)
            stable.add(done("auto", A, run(9000, 9000 * (1 + 0.003 * (i % 5)), 9050, 9010)));

        Job slow = done("auto", A, run(8587, 9139, 9017, 1213));
        Job after = done("auto", A, run(9000, 9020, 9010, 9030));
        jobs.markUnstableOnce();

        assertTrue(JobService.isUnstable(db.job(slow.id)));
        assertFalse(JobService.isUnstable(db.job(after.id)));
        assertFalse(stable.stream().anyMatch(j -> JobService.isUnstable(db.job(j.id))));
        JsonNode u = db.job(slow.id).comparison.get("unstable");
        assertEquals("A2", u.get("slow").get(0).asText());
        assertTrue(u.hasNonNull("usualPct"), "12 stable runs before it give a usual spread");

        Job next = new Job();
        next.id = after.id + 1;
        next.scenario = "smoke";
        Double usual = jobs.usualSpread(next);
        assertNotNull(usual, "from the stable runs only");
        assertTrue(usual < 5, "the slow run does not raise it: " + usual);

        // once: the job runner marks the runs that finish from now on
        Job later = done("auto", A, run(8587, 9139, 9017, 1213));
        jobs.markUnstableOnce();
        assertFalse(JobService.isUnstable(db.job(later.id)));
    }

    /** */
    @Test void aStableRepeatHoldsInsteadOfAnUnstableRun() {
        Job stable = done("auto", A, run(10000, 9000, 9050, 10050));
        Job slow = done("auto", A, run(8587, 9139, 9017, 1213));
        jobs.markUnstableOnce();
        Job unstable = db.job(slow.id);
        assertTrue(JobService.isUnstable(unstable));

        List<Job> both = List.of(db.job(stable.id), unstable);
        assertEquals(List.of(stable.id), JobService.stableOf(both).stream().map(j -> j.id).toList());
        assertEquals(2, JobService.stableOf(List.of(unstable, unstable)).size(), "all unstable: all of them");

        Bisect.Result r = jobs.result(unstable, both, "throughput");
        assertEquals(List.of(stable.id), r.jobIds);
        assertEquals(Stats.REGRESSION, r.verdict, "the Δ of the stable run, not the skewed one");
    }

    /** */
    @Test void anUnstableCalibrationIsLeftOutOfTheFloor() {
        for (int i = 0; i < 3; i++)
            done(JobService.SRC_CALIBRATION, A, run(9000, 9000 * (1 - 0.01 * i), 9000 * (1 - 0.01 * i), 9000));

        Job broken = done(JobService.SRC_CALIBRATION, A, run(9179, 9081, 9055, 1264));
        jobs.markUnstableOnce();

        JobService.Noise n = jobs.noise("smoke");
        assertEquals("calibration", n.source);
        assertTrue(n.floors.get("throughput") < 5, "the floor of the stable A/A: " + n.floors.get("throughput"));
        assertEquals(4, n.calibrations.size(), "the Noise tab still lists it");
        assertTrue(n.calibrations.stream().anyMatch(c -> c.get("jobId").equals(broken.id) && c.containsKey("unstable")));
    }

    /** */
    @Test void listViewsKeepTheMark() {
        Job slow = done("auto", A, run(8587, 9139, 9017, 1213));
        jobs.markUnstableOnce();
        Job j = db.job(slow.id);
        JobService.slim(List.of(j));
        assertTrue(j.comparison.hasNonNull("unstable"));
    }
}
