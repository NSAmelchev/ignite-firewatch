package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** A/B comparison of the hot-method profiles ({@code profile.json}) of an ABBA job. */
public final class ProfileDiff {
    /** One method of the profile diff. */
    public static final class Row {
        /** */
        public String method;

        /** */
        public double pctA;

        /** */
        public double pctB;

        /** Percentage points. */
        public double deltaPp;
    }

    /** Methods of the profile diff by self and total time. */
    public static final class Result {
        /** */
        public long samplesA;

        /** */
        public long samplesB;

        /** */
        public int phasesA;

        /** */
        public int phasesB;

        /** Rows sorted by |delta|, limited. */
        public List<Row> self = new ArrayList<>();

        /** */
        public List<Row> total = new ArrayList<>();
    }

    /**
     * Compares the hot methods of the A phases with those of the B phases.
     *
     * @param profilesA Profile.json trees of the A phases (may be empty).
     * @param profilesB Profile.json trees of the B phases (may be empty).
     * @param limit Maximum number of rows in a list.
     * @return Difference of the profiles.
     */
    public static Result diff(List<JsonNode> profilesA, List<JsonNode> profilesB, int limit) {
        Result r = new Result();
        Map<String, Long> selfA = new HashMap<>(), selfB = new HashMap<>(), totalA = new HashMap<>(),
            totalB = new HashMap<>();

        for (JsonNode p : profilesA) {
            r.samplesA += p.path("samples").asLong();
            add(selfA, p.path("self"));
            add(totalA, p.path("total"));
        }

        for (JsonNode p : profilesB) {
            r.samplesB += p.path("samples").asLong();
            add(selfB, p.path("self"));
            add(totalB, p.path("total"));
        }

        r.phasesA = profilesA.size();
        r.phasesB = profilesB.size();
        r.self = rows(selfA, selfB, r.samplesA, r.samplesB, limit);
        r.total = rows(totalA, totalB, r.samplesA, r.samplesB, limit);

        return r;
    }

    /** */
    private static void add(Map<String, Long> acc, JsonNode list) {
        for (JsonNode e : list)
            acc.merge(e.path("method").asText(), e.path("samples").asLong(), Long::sum);
    }

    /** */
    private static List<Row> rows(Map<String, Long> a, Map<String, Long> b, long samplesA, long samplesB, int limit) {
        Map<String, Row> rows = new LinkedHashMap<>();
        a.forEach((m, n) -> row(rows, m).pctA = samplesA == 0 ? 0 : 100.0 * n / samplesA);
        b.forEach((m, n) -> row(rows, m).pctB = samplesB == 0 ? 0 : 100.0 * n / samplesB);
        List<Row> out = new ArrayList<>(rows.values());

        for (Row r : out)
            r.deltaPp = r.pctB - r.pctA;

        out.sort(Comparator.comparingDouble((Row r) -> -Math.abs(r.deltaPp))
            .thenComparingDouble(r -> -(r.pctA + r.pctB)));

        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** */
    private static Row row(Map<String, Row> rows, String method) {
        return rows.computeIfAbsent(method, m -> {
            Row r = new Row();
            r.method = m;

            return r;
        });
    }

    /** */
    private ProfileDiff() {
    }
}
