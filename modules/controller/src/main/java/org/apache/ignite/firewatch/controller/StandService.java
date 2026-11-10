package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the application does with its {@link Stand}: Check, the sizes it suggests, the file a phase gets, the sweep of
 * the hosts.
 */
public final class StandService {
    /** */
    private static final Duration CHECK_TIMEOUT = Duration.ofMinutes(5);

    /** */
    private static final Duration SWEEP_TIMEOUT = Duration.ofMinutes(2);

    /** What a host keeps for itself besides the JVMs of a phase. */
    private static final int OS_RESERVE_MB = 1024;

    /** On a local stand the loader and the application share the machine with the nodes. */
    private static final int LOCAL_RESERVE_MB = 768;

    /** */
    private static final int MEMORY_STEP_MB = 256;

    /** */
    private static final int RECOMMENDED_FILL_PCT = 40;

    /** The load of a scenario was chosen for this many CPUs on the loader. */
    private static final int BASE_LOADER_CPUS = 4;

    /** */
    private final Config cfg;

    /** */
    private final Db db;

    /** */
    private final Scenarios scenarios;

    /** A job, Check and Clean hosts take the ports and the JVMs of the hosts from each other: one at a time. */
    private final Semaphore hold = new Semaphore(1);

    /**
     * Creates the stand service.
     *
     * @param cfg Configuration.
     * @param db Database.
     * @param scenarios Scenario catalog.
     */
    public StandService(Config cfg, Db db, Scenarios scenarios) {
        this.cfg = cfg;
        this.db = db;
        this.scenarios = scenarios;
    }

    /** */
    boolean tryHold() {
        return hold.tryAcquire();
    }

    /** */
    void release() {
        hold.release();
    }

    /**
     * Builds the stand view for the UI.
     *
     * @return Stand view.
     */
    public Map<String, Object> view() {
        Stand s = Stand.read(db);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode);
        m.put("hosts", s.hosts);
        m.put("sizes", s.sizes);
        m.put("needsSetup", Stand.needsSetup(db));
        m.put("frozen", Stand.frozen(db));
        m.put("sshKey", cfg.sshKey);
        m.put("sshKnownHosts", cfg.sshKnownHosts);
        m.put("check", Json.tree(db.setting(Stand.CHECK, "null")));
        m.put("load", Json.tree(db.setting(Stand.LOAD, "null")));

        return m;
    }

    /**
     * Runs Check on {@code s}, which need not be saved yet, and keeps the report. A passing check of a stand that is
     * not frozen also keeps the fingerprints: from then on a phase compares its hosts with them.
     *
     * @param s Stand to check.
     * @return Report of the check.
     */
    public JsonNode check(Stand s) throws IOException {
        s.validate();

        if (!tryHold())
            throw new IllegalStateException("a job or another Check is using the hosts");

        try {
            if (!db.jobs("active", 1).isEmpty())
                throw new IllegalStateException("the queue is not empty: Check takes the ports of Ignite on the hosts");

            return runCheck(s);
        }
        finally {
            release();
        }
    }

    /** */
    private JsonNode runCheck(Stand s) throws IOException {
        boolean remote = Stand.REMOTE.equals(s.mode);
        List<String> cmd = new ArrayList<>(cfg.harness(64));
        cmd.add("check");
        Path file = cfg.dataDir.resolve("check-stand.json");

        if (remote) {
            write(file, s);
            cmd.addAll(List.of("--stand", file.toAbsolutePath().toString()));
        }
        else
            cmd.addAll(List.of("--dir", cfg.dataDir.toAbsolutePath().toString()));

        Files.createDirectories(cfg.dataDir);
        Proc.Result r = Proc.run(cmd, null, cfg.harnessEnv, CHECK_TIMEOUT);
        Files.deleteIfExists(file);
        String answer = r.out.strip();
        answer = answer.substring(answer.lastIndexOf('\n') + 1);

        if (!r.ok() || !answer.startsWith("{"))
            throw new IllegalStateException("Check could not run: " + r.tail(3));

        ObjectNode report = (ObjectNode)Json.tree(answer);
        Stand.Sizes recommended = recommend(s, report, maxServers());
        report.set("recommended", Json.MAPPER.valueToTree(recommended));
        report.set("checked", Json.MAPPER.valueToTree(remote ? s.hosts : List.of()));
        String tight = tight(s.sizes.nodeMemoryMb > 0 ? s.sizes.nodeMemoryMb : recommended.nodeMemoryMb,
            roomForNode(s, report, maxServers()));

        if (tight != null)
            report.put("sizesWarning", tight);

        report.put("at", Db.now());
        db.setSetting(Stand.CHECK, report.toString());

        if (remote && report.path("ok").asBoolean() && !Stand.frozen(db)) {
            ObjectNode prints = Json.MAPPER.createObjectNode();
            report.path("hosts").forEach(h -> prints.set(h.path("address").asText(), h.path("fingerprint")));
            db.setSetting(Stand.FINGERPRINTS, prints.toString());
        }

        return report;
    }

    /** */
    private int maxServers() {
        return scenarios.list().stream().mapToInt(i -> i.servers).max().orElse(1);
    }

    /** What a node has with the largest scenario on the weakest host; nothing when no server host answered. */
    private static OptionalLong roomForNode(Stand s, JsonNode report, int maxServers) {
        long memMb = Long.MAX_VALUE;
        int serverHosts = 0;

        for (JsonNode h : report.path("hosts")) {
            JsonNode f = h.path("facts");

            if (f.isMissingNode() || f.isNull() || Stand.LOADERS.equals(h.path("role").asText()))
                continue;

            serverHosts++;
            memMb = Math.min(memMb, f.path("memMb").asLong());
        }

        if (serverHosts == 0)
            return OptionalLong.empty();

        int nodesOnAHost = (int)Math.ceil((double)maxServers / serverHosts);

        return OptionalLong.of((memMb - OS_RESERVE_MB - (Stand.REMOTE.equals(s.mode) ? 0 : LOCAL_RESERVE_MB))
            / nodesOnAHost);
    }

    /** The warning when a node of {@code nodeMemoryMb} does not fit, or null. */
    static String tight(int nodeMemoryMb, OptionalLong room) {
        if (nodeMemoryMb == 0 || room.isEmpty() || nodeMemoryMb <= room.getAsLong())
            return null;

        return nodeMemoryMb + " MB a node does not fit: the largest scenario leaves " + Math.max(0, room.getAsLong())
            + " MB for one on the weakest host";
    }

    /**
     * Sizes that fit the weakest host with the largest scenario on it, if any do; threads follow the CPUs of the
     * loader.
     */
    static Stand.Sizes recommend(Stand s, JsonNode report, int maxServers) {
        Stand.Sizes out = new Stand.Sizes();
        OptionalLong room = roomForNode(s, report, maxServers);

        if (room.isEmpty())
            return out;

        int ldrCpus = Integer.MAX_VALUE;

        for (JsonNode h : report.path("hosts")) {
            JsonNode f = h.path("facts");

            if (!f.isMissingNode() && !f.isNull() && !Stand.SERVERS.equals(h.path("role").asText()))
                ldrCpus = Math.min(ldrCpus, f.path("cpus").asInt());
        }

        out.nodeMemoryMb = (int)Math.max(Stand.MIN_NODE_MEMORY_MB, room.getAsLong() / MEMORY_STEP_MB * MEMORY_STEP_MB);
        out.fillPct = RECOMMENDED_FILL_PCT;
        out.threadsFactor = ldrCpus == Integer.MAX_VALUE ? 1 : Math.max(1, ldrCpus / BASE_LOADER_CPUS);

        return out;
    }

    /** The file a harness command reads. */
    void write(Path file, Stand s) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hosts", s.hosts);
        m.put("sshKey", cfg.sshKey);
        m.put("sshKnownHosts", cfg.sshKnownHosts);
        m.put("fingerprints", Json.tree(db.setting(Stand.FINGERPRINTS, "{}")));
        m.put("runPrefix", cfg.runPrefix());
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, Json.write(m));
    }

    /**
     * Clean hosts: only with no job about, a sweep stops the JVMs of this stand.
     *
     * @return {@code True} if there was nothing to report.
     */
    public boolean clean() {
        if (!tryHold())
            throw new IllegalStateException("a job or Check is using the hosts");

        try {
            if (!db.jobs("active", 1).isEmpty())
                throw new IllegalStateException("the queue is not empty: its jobs are on the hosts");

            return sweep();
        }
        finally {
            release();
        }
    }

    /**
     * Clears what a killed phase left on the hosts of a remote stand; true when there was nothing to report.
     *
     * @return {@code True} if there was nothing to report.
     */
    public boolean sweep() {
        Stand s = Stand.read(db);

        if (!Stand.REMOTE.equals(s.mode))
            return true;

        Path file = cfg.dataDir.resolve("sweep-stand.json");

        try {
            write(file, s);
            List<String> cmd = new ArrayList<>(cfg.harness(64));
            cmd.addAll(List.of("sweep", "--stand", file.toAbsolutePath().toString()));
            Proc.Result r = Proc.run(cmd, null, cfg.harnessEnv, SWEEP_TIMEOUT);

            if (!r.ok())
                Log.warn("sweep of the hosts: " + r.tail(3));

            return r.ok();
        }
        catch (IOException e) {
            Log.warn("sweep of the hosts: " + e);

            return false;
        }
        finally {
            try {
                Files.deleteIfExists(file);
            }
            catch (IOException e) {
                Log.warn("cannot delete " + file + ": " + e);
            }
        }
    }
}
