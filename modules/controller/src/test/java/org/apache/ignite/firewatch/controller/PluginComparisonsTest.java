package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The list of Compare on the page of a plugin: its jobs grouped by the request that queued them. */
class PluginComparisonsTest {
    /** */
    private final List<Job> jobs = new ArrayList<>();

    /** A job of Compare: B against A, pairs as {Ignite sha, extension sha} and their names. */
    private void step(String scenario, String at, String[] a, String[] b, String nameA, String nameB) {
        Job j = new Job();
        j.id = jobs.size() + 1;
        j.scenario = scenario;
        j.createdAt = at;
        j.status = Job.DONE;
        j.shaA = a[0];
        j.extShaA = a[1];
        j.shaB = b[0];
        j.extShaB = b[1];
        j.label = "spring-data: " + nameB + " vs " + nameA;
        jobs.add(j);
    }

    /** */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> c) {
        return (List<Map<String, Object>>)c.get("scenarios");
    }

    /** One request, one side at a time over two scenarios: one item, A of the first step, B of the last. */
    @Test void theStepsOfOneRequestAreOneItem() {
        String[] rel = {"i1", "e1"}, mid = {"i2", "e1"}, mas = {"i2", "e2"};
        String t = "2026-11-14T10:00:00Z";

        for (String sc : List.of("point", "range")) {
            step(sc, t, rel, mid, "2.17.0 + ext-1.0", "master i2 + ext-1.0");
            step(sc, t, mid, mas, "master i2 + ext-1.0", "master i2 + master e2");
        }

        List<Map<String, Object>> out = PluginApi.comparisons(jobs);
        assertEquals(1, out.size());
        assertEquals("2.17.0 + ext-1.0", out.get(0).get("a"));
        assertEquals("master i2 + master e2", out.get(0).get("b"));
        assertEquals(List.of("point", "range"), rows(out.get(0)).stream().map(r -> r.get("scenario")).toList());
        Map<String, Object> point = rows(out.get(0)).get(0);
        assertEquals(1L, ((Job)point.get(PluginApi.IGNITE)).id);
        assertEquals(2L, ((Job)point.get(PluginApi.EXT)).id);
        assertNull(point.get(PluginApi.BOTH));
    }

    /** A side that does not move has no step; the same request again is an item of its own, the newest first. */
    @Test void aRequestAgainIsAnotherItem() {
        String[] a = {"i1", "e1"}, b = {"i2", "e1"};
        step("point", "2026-11-14T10:00:00Z", a, b, "2.17.0 + ext-1.0", "master i2 + ext-1.0");
        step("point", "2026-11-14T10:00:30Z", a, b, "2.17.0 + ext-1.0", "master i2 + ext-1.0");
        step("point", "2026-11-14T10:05:00Z", a, b, "2.17.0 + ext-1.0", "master i2 + ext-1.0");

        List<Map<String, Object>> out = PluginApi.comparisons(jobs);
        assertEquals(List.of(List.of(3L), List.of(2L), List.of(1L)), out.stream().map(c -> c.get("jobs")).toList());
        assertEquals("master i2 + ext-1.0", out.get(0).get("b"), "the extension did not move: B is the end of the "
            + "Ignite step");
        assertNull(rows(out.get(0)).get(0).get(PluginApi.EXT));
    }

    /** A compare of an older stand moved both sides in one job. */
    @Test void aCompareOfBothSidesAtOnceIsItsOwnStep() {
        step("point", "2026-11-14T10:00:00Z", new String[] {"i1", "e1"}, new String[] {"i2", "e2"}, "A", "B");

        Map<String, Object> c = PluginApi.comparisons(jobs).get(0);
        assertEquals(List.of("A", "B"), List.of(c.get("a"), c.get("b")));
        assertEquals(1L, ((Job)rows(c).get(0).get(PluginApi.BOTH)).id);
    }
}
