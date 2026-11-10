package org.apache.ignite.firewatch.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Scale;

/**
 * Runs one phase: starts N server JVMs on the given Ignite jars, runs the loader JVM, stops the
 * servers (SIGTERM so JFR dumps), merges loader and JFR metrics into result.json + series.json.
 * The launcher JVM has no Ignite classes on its classpath (it reads only the scenario's {@link Bench}) and survives any
 * child failure.
 * Every JVM is started on the {@link Host} the {@link Placement} gives it, and everything is gathered and merged here.
 */
final class PhaseRunner {
    /** */
    private static final Duration SERVER_STOP_GRACE = Duration.ofSeconds(60);

    /** A phase in which a larger share of operations failed measured a failure, not the speed: it fails. */
    static final double MAX_ERROR_PCT = 1;

    /** What a phase may do differently from what its scenario states. */
    static final class Options {
        /** The hosts of a remote stand, or null for this machine. */
        Path standFile;

        /** */
        Scale scale = Scale.NONE;

        /** Null: the scenario's own. */
        Integer warmupSec;

        /** */
        Integer measureSec;
    }

    /** */
    private final Path libsDir;

    /** */
    private final String scenarioId;

    /** */
    private final Path out;

    /** */
    private final String runId;

    /** */
    private final String phase;

    /** */
    private final Options options;

    /** */
    PhaseRunner(Path libsDir, String scenarioId, Path out, String runId, String phase, Options options) {
        this.libsDir = libsDir;
        this.scenarioId = scenarioId;
        this.out = out;
        this.runId = runId;
        this.phase = phase;
        this.options = options;
    }

    /** */
    private static List<String> withScale(String[] jvmOpts, Scale scale) {
        List<String> opts = new ArrayList<>(List.of(jvmOpts));

        if (!scale.isNone())
            opts.add("-D" + Scale.PROPERTY + "=" + scale.format());

        return opts;
    }

    /** */
    int run() {
        long t0 = System.nanoTime();
        PhaseResult res = new PhaseResult();
        res.harnessVersion = Jvm.harnessVersion();
        res.runId = runId;
        res.phase = phase;
        res.startedAt = Instant.now().toString();
        res.env = PhaseResult.Env.current();

        List<JvmProcess> children = new ArrayList<>();
        Set<Host> hosts = new LinkedHashSet<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            children.forEach(JvmProcess::kill);
            hosts.forEach(h -> h.cleanup(out));
        }, "firewatch-cleanup"));

        try {
            Catalog.Entry entry = Catalog.get(scenarioId);
            Bench b = entry.bench;
            Placement placement = Placement.of(b,
                options.standFile == null ? null : Json.read(options.standFile, StandConfig.class), runId);
            Scale scale = options.scale.forServers(b.servers().count());
            boolean sql = b.modules().length > 0;
            res.scenarioName = b.id();
            res.scenarioHash = entry.hash();
            res.warmupSec = options.warmupSec != null ? options.warmupSec : b.warmupSec();
            res.measureSec = options.measureSec != null ? options.measureSec : b.measureSec();

            if (!Files.isDirectory(libsDir))
                throw new IllegalArgumentException("--libs is not a directory: " + libsDir);

            try (var jars = Files.list(libsDir)) {
                if (jars.noneMatch(p -> p.toString().endsWith(".jar")))
                    throw new IllegalArgumentException("--libs contains no jars: " + libsDir);
            }

            Files.createDirectories(out);
            Json.write(out.resolve("scenario.json"), entry.describe(false));
            Path jfc = out.resolve("ignite-firewatch.jfc");

            try (InputStream in = PhaseRunner.class.getResourceAsStream("/ignite-firewatch.jfc")) {
                if (in == null)
                    throw new IllegalStateException("ignite-firewatch.jfc resource is missing from the harness jar");

                Files.copy(in, jfc, StandardCopyOption.REPLACE_EXISTING);
            }

            Path harnessJar = Jvm.ownLocation();
            hosts.addAll(placement.hosts());

            for (Host h : hosts) {
                h.prepare(harnessJar, libsDir, out);

                if (h.facts() != null) {
                    if (res.hosts == null)
                        res.hosts = new LinkedHashMap<>();

                    res.hosts.put(h.name(), h.facts());
                }
            }

            Duration startTimeout = Duration.ofSeconds(b.startTimeoutSec());
            String discovery = placement.discoveryAddresses();

            List<JvmProcess> servers = new ArrayList<>();

            for (int i = 0; i < placement.servers.size(); i++) {
                Host host = placement.servers.get(i);
                JvmProcess.Spec s = new JvmProcess.Spec();
                s.label = "node-" + i;
                s.mainClass = ServerMain.class.getName();
                s.heapMb = scale.heapMb(b.servers().heapMb(), sql);
                s.jvmOpts = withScale(b.servers().jvmOpts(), scale);
                s.harnessJar = harnessJar;
                s.libsDir = libsDir;
                s.dir = host.dir(out);
                s.jfr = true;
                s.runId = runId;
                s.logFile = out.resolve("node-" + i + ".log");
                s.args = List.of("--scenario", b.id(), "--node-index", String.valueOf(i), "--host", host.address(),
                    "--discovery", discovery, "--work-dir", s.dir + "/work/node-" + i);
                JvmProcess p = host.start(s);
                children.add(p);
                servers.add(p);
                log("started " + s.label + " pid=" + p.pid());
            }

            for (JvmProcess p : servers) {
                if (!p.awaitLogLine("HARNESS_READY", startTimeout)) {
                    String why = p.isAlive() ? "did not become ready within " + startTimeout.toSeconds() + " s"
                        : "exited with code " + p.exitCode() + " before becoming ready";

                    throw new IllegalStateException(p.label() + " " + why + ", see " + p.logFile() + ": "
                        + p.lastLogLine());
                }

                log(p.label() + " ready");
            }

            JvmProcess.Spec l = new JvmProcess.Spec();
            l.label = "loader";
            l.mainClass = LoaderMain.class.getName();
            Host ldrHost = placement.loaders.get(0);
            l.heapMb = b.loader().heapMb();
            l.jvmOpts = withScale(b.loader().jvmOpts(), scale);
            l.harnessJar = harnessJar;
            l.libsDir = libsDir;
            l.dir = ldrHost.dir(out);
            l.runId = runId;
            l.logFile = out.resolve("loader.log");
            List<String> args = new ArrayList<>(List.of("--scenario", b.id(), "--out", l.dir,
                "--work-dir", l.dir + "/work/loader", "--host", ldrHost.address(),
                "--discovery", discovery, "--thin", placement.thinAddresses(),
                "--warmup-sec", String.valueOf(res.warmupSec), "--measure-sec", String.valueOf(res.measureSec)));
            l.args = args;

            if (options.standFile != null)
                res.loaderHost = ldrHost.name();

            JvmProcess ldr = ldrHost.start(l);
            children.add(ldr);
            log("started loader pid=" + ldr.pid() + ", warmup " + res.warmupSec + " s + measure " + res.measureSec
                + " s");

            long setupSec = (long)b.setupTimeoutSec() * scale.setupTimeoutFactor();
            Duration ldrTimeout = Duration.ofSeconds(b.startTimeoutSec() + setupSec + res.warmupSec + res.measureSec
                + 90);
            Integer rc = ldr.waitFor(ldrTimeout);

            if (rc == null) {
                ldr.kill();

                throw new IllegalStateException("loader did not finish within " + ldrTimeout.toSeconds() + " s");
            }

            if (rc != 0)
                throw new IllegalStateException("loader exited with code " + rc + ", see " + ldr.logFile() + ": "
                    + ldr.lastLogLine());

            if (!ldrHost.fetch("loader.json", out))
                throw new IllegalStateException("loader.json could not be fetched from " + ldrHost.address());

            LoaderResult lr = Json.read(out.resolve("loader.json"), LoaderResult.class);

            for (JvmProcess p : servers)
                p.stopGracefully(SERVER_STOP_GRACE);

            log("servers stopped");

            for (int i = 0; i < servers.size(); i++)
                placement.servers.get(i).fetch("node-" + i + ".jfr", out);

            fill(res, lr);
            String broken = tooManyErrors(lr);

            if (broken != null)
                throw new IllegalStateException(broken);

            res.serverStartMs = maxStartMs(out, servers.size());
            String srvVer = readyVersion(out.resolve("node-0.log"));

            if (srvVer != null)
                res.igniteVersion = srvVer;

            Series series = new Series();
            series.measureStartIndex = lr.measureStartIndex;
            series.t.addAll(lr.series.t);
            series.ops.addAll(lr.series.ops);
            series.p50Us.addAll(lr.series.p50Us);
            series.p99Us.addAll(lr.series.p99Us);
            series.loaderCpuPct.addAll(lr.series.loaderCpuPct);
            int total = res.warmupSec + res.measureSec;
            List<JfrMetrics.NodeMetrics> nodeMetrics = new ArrayList<>();
            List<JfrProfile.Profile> profiles = new ArrayList<>();

            for (int i = 0; i < servers.size(); i++) {
                Path jfr = out.resolve("node-" + i + ".jfr");
                PhaseResult.NodeResult nr = new PhaseResult.NodeResult();
                nr.index = i;
                nr.host = options.standFile == null ? null : placement.servers.get(i).name();
                nr.logFile = "node-" + i + ".log";

                if (!Files.exists(jfr)) {
                    log("WARNING: " + jfr.getFileName() + " is missing, node metrics skipped");
                    res.nodes.add(nr);
                    continue;
                }

                JfrMetrics.NodeMetrics nm = JfrMetrics.parse(jfr, lr.loadStartMs, lr.measureStartMs, lr.measureEndMs,
                    total);
                nodeMetrics.add(nm);

                try {
                    profiles.add(JfrProfile.parse(jfr, lr.measureStartMs, lr.measureEndMs));
                }
                catch (IOException | RuntimeException ex) {
                    log("WARNING: execution samples of " + jfr.getFileName() + " not parsed: " + ex);
                }

                nr.jfrFile = jfr.getFileName().toString();
                nr.gc = gcOf(nm);
                nr.cpu = new PhaseResult.Cpu();
                nr.cpu.serverJvmPct = nm.cpuJvmPct;
                nr.cpu.machinePct = nm.cpuMachinePct;
                nr.heap = new PhaseResult.Heap();
                nr.heap.afterGcMbMean = nm.heapAfterGcMbMean;
                nr.heap.committedMbMax = nm.heapCommittedMbMax;
                res.nodes.add(nr);
            }

            aggregate(res, series, nodeMetrics, lr, total);

            if (!profiles.isEmpty())
                Json.write(out.resolve("profile.json"), JfrProfile.merge(profiles));

            res.status = "ok";
            res.wallSec = (System.nanoTime() - t0) / 1e9;
            Json.write(out.resolve("result.json"), res);
            Json.write(out.resolve("series.json"), series);
            System.out.printf("PHASE_DONE status=ok ops/s=%.0f p99=%.0fus gcPause=%.1f%% alloc=%.1fMB/s "
                + "serverCpu=%.1f%% wall=%.0fs%n",
                res.throughput.mean, res.latencyUs.p99, res.gc.pausePct, res.gc.allocMbPerSec, res.cpu.serverJvmPct,
                res.wallSec);

            return 0;
        }
        catch (Exception e) {
            res.status = "failed";
            res.error = e.toString();
            res.wallSec = (System.nanoTime() - t0) / 1e9;
            log("FAILED: " + e);

            try {
                Files.createDirectories(out);
                Json.write(out.resolve("result.json"), res);
            }
            catch (IOException io) {
                log("could not write result.json: " + io);
            }

            System.out.println("PHASE_DONE status=failed error=" + e.getMessage());

            return 1;
        }
        finally {
            for (JvmProcess c : children) {
                if (c.isAlive()) {
                    log("killing " + c.label());
                    c.kill();
                }
            }

            hosts.forEach(h -> h.cleanup(out));
            hosts.clear(); // the shutdown hook has nothing left to clean
        }
    }

    /**
     * Slowest {@code startMs=} of the HARNESS_READY lines of all server nodes (time inside Ignition.start), or null.
     */
    static Long maxStartMs(Path out, int servers) {
        Long max = null;

        for (int i = 0; i < servers; i++) {
            String line = readyLine(out.resolve("node-" + i + ".log"));
            int s = line == null ? -1 : line.indexOf(" startMs=");

            if (s < 0)
                continue;

            int from = s + " startMs=".length(), to = line.indexOf(' ', from);

            try {
                long v = Long.parseLong(line.substring(from, to < 0 ? line.length() : to));
                max = max == null ? v : Math.max(max, v);
            }
            catch (NumberFormatException ignored) {
            }
        }

        return max;
    }

    /** The {@code version=...} the server printed in its HARNESS_READY line, or null. */
    private static String readyVersion(Path log) {
        String line = readyLine(log);
        int v = line == null ? -1 : line.indexOf("version=");

        return v < 0 ? null : line.substring(v + "version=".length()).trim();
    }

    /**
     * The HARNESS_READY line of a server log, read line by line up to it: a node that logs every failed query writes
     * hundreds of MB, more than the heap of the harness. Null when there is none.
     */
    static String readyLine(Path log) {
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8))) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                int i = line.indexOf("HARNESS_READY");

                if (i >= 0)
                    return line.substring(i);
            }
        }
        catch (IOException ignored) {
        }

        return null;
    }

    /**
     * Why the load of a phase says nothing about speed: more than {@link #MAX_ERROR_PCT} of its operations failed; null
     * when it is fine.
     */
    static String tooManyErrors(LoaderResult lr) {
        long ok = lr.series.ops.stream().mapToLong(Long::longValue).sum(), all = ok + lr.errors;

        if (lr.errors == 0 || lr.errors * 100.0 <= all * MAX_ERROR_PCT)
            return null;

        return String.format(Locale.ROOT, "%,d of %,d operations failed (%.0f%%), the first: %s", lr.errors, all,
            lr.errors * 100.0 / all, lr.firstError);
    }

    /** */
    private static void fill(PhaseResult res, LoaderResult lr) {
        res.igniteVersion = lr.igniteVersion;
        res.measureStartAt = Instant.ofEpochMilli(lr.measureStartMs).toString();
        res.connectMs = lr.connectMs;
        res.preloadMs = lr.preloadMs;
        res.throughput = lr.throughput;
        res.latencyUs = lr.latencyUs;
        res.errors = lr.errors;
    }

    /** */
    private static PhaseResult.Gc gcOf(JfrMetrics.NodeMetrics nm) {
        PhaseResult.Gc gc = new PhaseResult.Gc();
        gc.count = nm.gcCount;
        gc.totalPauseMs = nm.totalPauseMs;
        gc.maxPauseMs = nm.maxPauseMs;
        gc.pausePct = nm.pausePct;
        gc.allocMbPerSec = nm.allocMbPerSec;

        return gc;
    }

    /** Sums GC/alloc/heap/JVM CPU over nodes, averages machine CPU, fills the server-side series. */
    private static void aggregate(PhaseResult res, Series series, List<JfrMetrics.NodeMetrics> nodes, LoaderResult lr,
        int total) {
        res.gc = new PhaseResult.Gc();
        res.cpu = new PhaseResult.Cpu();
        res.cpu.loaderJvmPct = lr.loaderCpuPct;
        res.heap = new PhaseResult.Heap();
        double heapAfter = 0, committed = 0;
        boolean heapAfterSeen = false, committedSeen = false;

        for (JfrMetrics.NodeMetrics nm : nodes) {
            res.gc.count += nm.gcCount;
            res.gc.totalPauseMs += nm.totalPauseMs;
            res.gc.maxPauseMs = Math.max(res.gc.maxPauseMs, nm.maxPauseMs);
            res.gc.pausePct += nm.pausePct / nodes.size();
            res.gc.allocMbPerSec += nm.allocMbPerSec;
            res.cpu.serverJvmPct += nm.cpuJvmPct;
            res.cpu.machinePct += nm.cpuMachinePct / nodes.size();

            if (nm.heapAfterGcMbMean != null) {
                heapAfter += nm.heapAfterGcMbMean;
                heapAfterSeen = true;
            }

            if (nm.heapCommittedMbMax != null) {
                committed += nm.heapCommittedMbMax;
                committedSeen = true;
            }
        }

        res.heap.afterGcMbMean = heapAfterSeen ? heapAfter : null;
        res.heap.committedMbMax = committedSeen ? committed : null;

        for (int i = 0; i < total; i++) {
            Double cpuServer = null, cpuMachine = null, gcPause = null, alloc = null, heapUsed = null;
            int machineN = 0;

            for (JfrMetrics.NodeMetrics nm : nodes) {
                cpuServer = add(cpuServer, nm.cpuJvmPctSeries[i]);

                if (nm.cpuMachinePctSeries[i] != null) {
                    cpuMachine = add(cpuMachine, nm.cpuMachinePctSeries[i]);
                    machineN++;
                }

                gcPause = add(gcPause, nm.gcPauseMsSeries[i]);
                alloc = add(alloc, nm.allocMbSeries[i]);
                heapUsed = add(heapUsed, nm.heapUsedMbSeries[i]);
            }

            series.serverCpuPct.add(cpuServer);
            series.machineCpuPct.add(cpuMachine == null ? null : cpuMachine / machineN);
            series.gcPauseMs.add(gcPause);
            series.allocMb.add(alloc);
            series.heapUsedMb.add(heapUsed);
        }
    }

    /** */
    private static Double add(Double acc, Double v) {
        if (v == null)
            return acc;

        return acc == null ? v : acc + v;
    }

    /** */
    private static void log(String msg) {
        System.out.println("[harness] " + msg);
    }
}
