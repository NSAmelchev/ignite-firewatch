package org.apache.ignite.firewatch.controller;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link ProfileDiff}. */
class ProfileDiffTest {
    /** */
    private static com.fasterxml.jackson.databind.JsonNode profile(long samples, String... methodCounts) {
        StringBuilder self = new StringBuilder();

        for (String mc : methodCounts) {
            String[] p = mc.split("=");

            if (self.length() > 0)
                self.append(',');

            self.append("{\"method\":\"").append(p[0]).append("\",\"samples\":").append(p[1]).append('}');
        }

        return Json.tree("{\"samples\":" + samples + ",\"self\":[" + self + "],\"total\":[" + self + "]}");
    }

    /** */
    @Test void sumsPhasesAndSortsByAbsoluteDelta() {
        List<com.fasterxml.jackson.databind.JsonNode> a = List.of(profile(100, "x.A.m=50", "x.B.m=30"),
            profile(100, "x.A.m=50", "x.B.m=30"));
        List<com.fasterxml.jackson.databind.JsonNode> b = List.of(profile(100, "x.A.m=40", "x.B.m=30", "x.C.m=20"),
            profile(100, "x.A.m=40", "x.B.m=30", "x.C.m=20"));
        ProfileDiff.Result r = ProfileDiff.diff(a, b, 10);
        assertEquals(200, r.samplesA);
        assertEquals(200, r.samplesB);
        assertEquals(2, r.phasesA);
        assertEquals("x.C.m", r.self.get(0).method, "new method with +20 pp first");
        assertEquals(20, r.self.get(0).deltaPp, 1e-9);
        assertEquals(0, r.self.get(0).pctA, 1e-9);
        assertEquals("x.A.m", r.self.get(1).method);
        assertEquals(-10, r.self.get(1).deltaPp, 1e-9);
        assertEquals(0, r.self.get(2).deltaPp, 1e-9);
        assertEquals(3, r.total.size());
        assertTrue(ProfileDiff.diff(List.of(), b, 2).self.size() == 2, "limit applies");
        assertEquals(0, ProfileDiff.diff(List.of(), List.of(), 5).self.size());
    }
}
