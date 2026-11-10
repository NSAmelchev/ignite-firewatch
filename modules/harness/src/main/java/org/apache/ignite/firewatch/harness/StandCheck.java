package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Whether a stand can measure: every host on its own and, on a remote stand, every host against the others. What makes
 * results wrong fails; what makes them noisier only warns. {@code fingerprint} is what must stay the same for runs to
 * stay comparable.
 */
public final class StandCheck {
    /** */
    static final String OK = "ok";

    /** */
    static final String WARN = "warn";

    /** */
    static final String FAIL = "fail";

    /** */
    static final int MIN_JAVA = 17;

    /** */
    static final long MIN_DISK_MB = 5 * 1024;

    /** */
    static final long MIN_MEMORY_MB = 2 * 1024;

    /** */
    static final long CLOCK_WARN_MS = 100;

    /** The windows of JFR are cut by the clock of the loader, whose series are per second. */
    static final long CLOCK_FAIL_MS = 1000;

    /** Discovery, communication and the client connector. */
    static final List<Integer> IGNITE_PORTS = List.of(Placement.DISCOVERY_PORT, 47100, Placement.THIN_PORT);

    /** */
    private static final int LISTEN_SEC = 60;

    /** One finding of the check of a host. */
    public static final class Item {
        /** */
        public String name;

        /** */
        public String level;

        /** */
        public String text;

        /** */
        Item(String name, String level, String text) {
            this.name = name;
            this.level = level;
            this.text = text;
        }
    }

    /** Report of the check of one host. */
    public static final class HostReport {
        /** */
        public String address;

        /** */
        public String role;

        /** */
        public List<Item> items = new ArrayList<>();

        /** */
        public Map<String, Object> facts;

        /** */
        public Map<String, String> fingerprint;

        /** */
        void add(String name, String level, String text) {
            items.add(new Item(name, level, text));
        }
    }

    /** */
    public List<HostReport> hosts = new ArrayList<>();

    /** */
    public boolean ok;

    /** This machine. A foreign JVM is worth a warning here: it may be the developer's own. */
    static StandCheck local(Path dir) throws IOException {
        StandCheck c = new StandCheck();
        HostReport r = new HostReport();
        r.address = "this machine";
        r.role = "all";
        r.facts = HostProbe.facts(dir);
        judge(r, WARN);
        c.hosts.add(r);
        c.finish();

        return c;
    }

    /** */
    static StandCheck remote(StandConfig stand, Path harnessJar, String runId) {
        StandCheck c = new StandCheck();
        List<RemoteHost> reachable = new ArrayList<>();
        Map<RemoteHost, HostReport> reports = new LinkedHashMap<>();

        for (StandConfig.Host h : stand.hosts) {
            HostReport r = new HostReport();
            r.address = h.address;
            r.role = h.role;
            c.hosts.add(r);
            RemoteHost host = new RemoteHost(stand, h, runId);

            try {
                host.installProbe(harnessJar);
                r.add("ssh", OK, "reachable, rsync and pkill are there");
                r.facts = host.readFacts();
                judge(r, FAIL);
                reachable.add(host);
                reports.put(host, r);
            }
            catch (IOException | RuntimeException e) {
                r.add("ssh", FAIL, e.getMessage());
            }
        }

        links(reachable, reports);
        c.finish();

        return c;
    }

    /** What one host shows by itself; {@code foreign} is how bad another JVM is on this stand. */
    static void judge(HostReport r, String foreign) {
        Map<String, Object> f = r.facts;
        int java = ((Number)f.get("javaMajor")).intValue();
        r.add("java", java >= MIN_JAVA ? OK : FAIL,
            f.get("jdk") + (java >= MIN_JAVA ? "" : ": needs " + MIN_JAVA + " or newer"));
        long disk = ((Number)f.get("diskFreeMb")).longValue();
        r.add("disk", disk >= MIN_DISK_MB ? OK : FAIL,
            disk / 1024 + " GB free" + (disk >= MIN_DISK_MB ? "" : ", needs " + MIN_DISK_MB / 1024));
        long mem = ((Number)f.get("memMb")).longValue();
        r.add("memory", mem >= MIN_MEMORY_MB ? OK : FAIL, Math.round(mem / 102.4) / 10.0 + " GB, " + f.get("cpus")
            + " CPUs" + (mem >= MIN_MEMORY_MB ? "" : ": needs " + MIN_MEMORY_MB / 1024 + " GB"));
        List<?> jvms = (List<?>)f.get("foreignJvms");
        r.add("jvms", jvms.isEmpty() ? OK : foreign,
            jvms.isEmpty() ? "no other JVM" : jvms.size() + " other JVM(s): " + jvms.get(0));
        long swap = ((Number)f.get("swapMb")).longValue();
        r.add("swap", swap == 0 ? OK : WARN,
            swap == 0 ? "off" : swap / 1024 + " GB of swap: a node that touches it stops measuring what it should");
        Object thp = f.get("thp");
        r.add("thp", "always".equals(thp) ? WARN : OK,
            thp == null ? "not reported" : thp + ("always".equals(thp) ? ": latency gets spikes" : ""));
        Object governor = f.get("governor");
        r.add("governor", governor == null || "performance".equals(governor) ? OK : WARN,
            governor == null ? "not reported" : governor + ("performance".equals(governor) ? ""
                : ": the frequency follows the load, results follow it"));
        r.fingerprint = fingerprintOf(f);
    }

    /** Memory goes by whole GB: the kernel keeps a slightly different amount for itself from boot to boot. */
    static Map<String, String> fingerprintOf(Map<String, Object> f) {
        Map<String, String> fp = new LinkedHashMap<>();
        fp.put("cpu", String.valueOf(f.get("cpu")));
        fp.put("cpus", String.valueOf(f.get("cpus")));
        fp.put("memGb", String.valueOf(Math.round(((Number)f.get("memMb")).longValue() / 1024.0)));
        fp.put("kernel", String.valueOf(f.get("kernel")));
        fp.put("jdk", String.valueOf(f.get("jdk")));

        return fp;
    }

    /** Every host against the ports of every other one; the clocks count against the loaders, who cut the windows. */
    private static void links(List<RemoteHost> hosts, Map<RemoteHost, HostReport> reports) {
        if (hosts.size() < 2)
            return;

        String ports = IGNITE_PORTS.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow();

        for (RemoteHost target : hosts) {
            HostReport to = reports.get(target);
            Process listener = null;

            try {
                listener = target.listen(ports, LISTEN_SEC);
                List<String> addrs = new ArrayList<>();

                for (int p : IGNITE_PORTS)
                    addrs.add(target.address() + ":" + p);

                for (RemoteHost from : hosts) {
                    if (from != target)
                        link(from, reports.get(from), to, String.join(",", addrs));
                }
            }
            catch (IOException e) {
                to.add("ports", FAIL, "cannot open the Ignite ports " + ports + ": " + e.getMessage());
            }
            finally {
                if (listener != null)
                    target.stopProbe(listener);
            }
        }
    }

    /** */
    private static void link(RemoteHost from, HostReport fromReport, HostReport to, String targets) {
        try {
            boolean reached = true;

            for (JsonNode n : from.connect(targets)) {
                if (!n.path("ok").asBoolean()) {
                    reached = false;
                    fromReport.add("link", FAIL,
                        "cannot reach " + n.path("target").asText() + ": " + n.path("error").asText());
                }
                else if (StandConfig.LOADERS.equals(fromReport.role) && StandConfig.SERVERS.equals(to.role))
                    clock(to, fromReport.address, n.path("clockAheadMs").asLong());
            }

            if (reached)
                fromReport.add("link", OK, "reaches " + to.address);
        }
        catch (IOException e) {
            fromReport.add("link", FAIL, "cannot test the way to " + to.address + ": " + e.getMessage());
        }
    }

    /** */
    static void clock(HostReport server, String loader, long aheadMs) {
        if (server.items.stream().anyMatch(i -> i.name.equals("clock") && i.text.contains(loader)))
            return;

        long off = Math.abs(aheadMs);
        server.add("clock", off >= CLOCK_FAIL_MS ? FAIL : off >= CLOCK_WARN_MS ? WARN : OK, off + " ms from " + loader);
    }

    /** */
    private void finish() {
        ok = hosts.stream().allMatch(h -> !h.items.isEmpty() && h.items.stream().noneMatch(i -> FAIL.equals(i.level)));
    }
}
