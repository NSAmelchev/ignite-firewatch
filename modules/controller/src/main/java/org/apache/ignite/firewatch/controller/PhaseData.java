package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** What the controller needs from a phase's {@code result.json} and {@code series.json}. */
public final class PhaseData {
    /** */
    public String phase;

    /** */
    public String sha;

    /** */
    public String status;

    /** */
    public String error;

    /** */
    public String igniteVersion;

    /** */
    public String scenarioHash;

    /** */
    public String harnessVersion;

    /** */
    public Double wallSec;

    /** */
    public Long errors;

    /** Metric key → value, see {@link Stats#METRICS}. */
    public Map<String, Double> metrics = new LinkedHashMap<>();

    /** Per-second samples of the measure window (empty if series.json is missing). */
    public List<Double> opsSeries = new ArrayList<>();

    /** */
    public List<Double> p99Series = new ArrayList<>();

    /**
     * Checks the phase status.
     *
     * @return {@code True} if the phase completed.
     */
    public boolean ok() {
        return "ok".equals(status);
    }

    /**
     * Reads the result files of a phase.
     *
     * @param dir Run directory.
     * @param phase Phase name.
     * @param sha Commit SHA of the phase.
     * @return Data of the phase.
     */
    public static PhaseData read(Path dir, String phase, String sha) throws IOException {
        Path resultFile = dir.resolve("result.json");
        PhaseData d = new PhaseData();
        d.phase = phase;
        d.sha = sha;

        if (!Files.exists(resultFile)) {
            // the harness died before it could write one: its own log tells why
            String why = Proc.lastError(dir.resolve("harness.log"));
            d.status = "failed";
            d.error = why.isEmpty() ? "result.json is missing (harness did not start?)" : "the harness died: " + why;

            return d;
        }

        JsonNode r = Json.readTree(resultFile);
        d.status = r.path("status").asText("failed");
        d.error = text(r, "error");
        d.igniteVersion = text(r, "igniteVersion");
        d.scenarioHash = text(r, "scenarioHash");
        d.harnessVersion = text(r, "harnessVersion");
        d.wallSec = num(r, "wallSec");
        d.errors = r.hasNonNull("errors") ? r.get("errors").asLong() : null;

        if (!d.ok())
            return d;

        Double thr = num(r.path("throughput"), "mean");
        put(d, "throughput", thr);
        JsonNode lat = r.path("latencyUs");
        put(d, "p50Us", num(lat, "p50"));
        put(d, "p99Us", num(lat, "p99"));
        put(d, "p999Us", num(lat, "p999"));
        put(d, "maxUs", num(lat, "max"));
        JsonNode gc = r.path("gc");
        put(d, "gcPausePct", num(gc, "pausePct"));
        put(d, "gcMaxPauseMs", num(gc, "maxPauseMs"));
        Double alloc = num(gc, "allocMbPerSec");
        put(d, "allocMbPerSec", alloc);

        if (alloc != null && thr != null && thr > 0)
            put(d, "allocKbPerOp", alloc * 1024 / thr);

        JsonNode cpu = r.path("cpu");
        put(d, "serverCpuPct", num(cpu, "serverJvmPct"));
        Double srvCpu = num(cpu, "serverJvmPct");
        int cpus = r.path("env").path("cpus").asInt(0);

        if (srvCpu != null && thr != null && thr > 0 && cpus > 0)
            // CPU-microseconds of server time per operation
            put(d, "serverCpuUsPerOp", srvCpu / 100.0 * cpus * 1_000_000.0 / thr);

        put(d, "machineCpuPct", num(cpu, "machinePct"));
        put(d, "loaderCpuPct", num(cpu, "loaderJvmPct"));
        put(d, "heapAfterGcMb", num(r.path("heap"), "afterGcMbMean"));
        // inside Ignition.start(), slowest node; absent in old results
        put(d, "serverStartMs", num(r, "serverStartMs"));
        Double preload = num(r, "preloadMs");

        if (preload != null && preload > 0)
            put(d, "preloadMs", preload);

        Path seriesFile = dir.resolve("series.json");

        if (Files.exists(seriesFile)) {
            JsonNode s = Json.readTree(seriesFile);
            int from = s.path("measureStartIndex").asInt(0);
            JsonNode ops = s.path("ops");
            JsonNode p99 = s.path("p99Us");

            for (int i = from; i < ops.size(); i++) {
                if (ops.get(i) != null && !ops.get(i).isNull())
                    d.opsSeries.add(ops.get(i).asDouble());

                if (i < p99.size() && p99.get(i) != null && !p99.get(i).isNull())
                    d.p99Series.add(p99.get(i).asDouble());
            }
        }

        return d;
    }

    /**
     * Copies the phase data to a run.
     *
     * @param run Run to fill.
     */
    public void fill(Run run) {
        run.igniteVersion = igniteVersion;
        run.wallSec = wallSec;
        run.errors = errors;
        run.throughput = metrics.get("throughput");
        run.p50Us = metrics.get("p50Us");
        run.p99Us = metrics.get("p99Us");
        run.p999Us = metrics.get("p999Us");
        run.gcPausePct = metrics.get("gcPausePct");
        run.gcMaxPauseMs = metrics.get("gcMaxPauseMs");
        run.allocMbPerSec = metrics.get("allocMbPerSec");
        run.serverCpuPct = metrics.get("serverCpuPct");
        run.machineCpuPct = metrics.get("machineCpuPct");
        run.heapAfterGcMb = metrics.get("heapAfterGcMb");
    }

    /** */
    private static void put(PhaseData d, String key, Double v) {
        if (v != null)
            d.metrics.put(key, v);
    }

    /** */
    private static Double num(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);

        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    /** */
    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);

        return v == null || v.isNull() ? null : v.asText();
    }
}
