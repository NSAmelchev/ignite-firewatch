package org.apache.ignite.firewatch.controller;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which jobs are pooled as repeats of one ABBA comparison. */
class RepeatsTest {
    /** Sha of commit A. */
    private static final String A = "a".repeat(40);

    /** Sha of commit B. */
    private static final String B = "b".repeat(40);

    /** Sha of another commit. */
    private static final String OTHER = "c".repeat(40);

    /** */
    private static Job job(long id, String shaA, String shaB, String scenario,
        String status, String source, String hash) {
        Job j = new Job();
        j.id = id;
        j.type = Job.ABBA;
        j.shaA = shaA;
        j.shaB = shaB;
        j.scenario = scenario;
        j.status = status;
        j.source = source;
        j.scenarioHash = hash;

        return j;
    }

    /** */
    @Test void onlySamePairScenarioAndHashFinishedNonCalibration() {
        Job base = job(5, A, B, "putget", Job.DONE, "auto", "h1");
        List<Job> all = List.of(
            job(9, A, B, "putget", Job.DONE, "manual", "h1"),       // repeat
            job(3, A, B, "putget", Job.DONE, "auto", "h1"),         // repeat, older
            base,
            job(7, A, B, "putget", Job.FAILED, "manual", "h1"),     // not finished
            job(8, A, B, "putget", Job.QUEUED, "manual", "h1"),     // not finished
            job(6, OTHER, B, "putget", Job.DONE, "manual", "h1"),   // other baseline
            job(4, A, B, "smoke", Job.DONE, "manual", "h1"),        // other scenario
            job(2, A, B, "putget", Job.DONE, "manual", "h0"),       // scenario file changed
            job(1, B, B, "putget", Job.DONE, "calibration", "h1"),  // A/A
            job(10, A, B, "putget", Job.DONE, "compare", "h1"));    // manual debugging from the Compare tab
        List<Job> g = JobService.repeatsOf(base, all);
        assertEquals(List.of(3L, 5L, 9L), g.stream().map(j -> j.id).toList(), "oldest first");
    }

    /** */
    @Test void compareJobsHaveNoRepeats() {
        Job cmp = job(1, A, B, "putget", Job.DONE, "compare", "h1");
        assertTrue(JobService.repeatsOf(cmp, List.of(cmp, job(2, A, B, "putget", Job.DONE, "compare", "h1")))
            .isEmpty());
    }

    /** */
    @Test void listViewsGetAComparisonSummary() throws Exception {
        Job j = job(1, A, B, "putget", Job.DONE, "manual", "h1");
        j.comparison = Json.MAPPER.readTree("{\"verdict\":\"no-change\","
            + "\"phases\":[\"A1\",\"B1\",\"B2\",\"A2\"],\"metrics\":{"
            + "\"throughput\":{\"meanA\":100,\"meanB\":99,\"deltaPct\":-1,\"ciLow\":-2,\"ciHigh\":0,\"thresholdPct\":2,"
            + "\"verdict\":\"no-change\",\"values\":{\"A1\":100}},"
            + "\"p50Us\":{\"meanB\":1,\"deltaPct\":0}}}");
        JobService.slim(List.of(j));
        assertEquals("no-change", j.comparison.get("verdict").asText());
        assertEquals(-1, j.comparison.at("/metrics/throughput/deltaPct").asDouble());
        assertEquals(2, j.comparison.at("/metrics/throughput/thresholdPct").asDouble());
        assertEquals(99, j.comparison.at("/metrics/throughput/meanB").asDouble());
        assertTrue(j.comparison.at("/metrics/throughput/values").isMissingNode(), "phase values are dropped");
        assertTrue(j.comparison.at("/metrics/p50Us").isMissingNode(), "metrics outside the list columns are dropped");
        assertTrue(j.comparison.path("phases").isMissingNode());
    }

    /** */
    @Test void singleJobsAreNeverPooled() {
        Job single = job(1, B, null, "putget", Job.DONE, "manual", "h1");
        single.type = Job.SINGLE;
        assertTrue(JobService.repeatsOf(single, List.of(single)).isEmpty());
    }
}
