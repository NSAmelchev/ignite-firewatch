package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
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

        /** How to fix it, {@code code} in back quotes; {@code null} when there is nothing to fix. */
        public String fix;

        /** */
        Item(String name, String level, String text, String fix) {
            this.name = name;
            this.level = level;
            this.text = text;
            this.fix = fix;
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
            add(name, level, text, null);
        }

        /** */
        void add(String name, String level, String text, String fix) {
            items.add(new Item(name, level, text, OK.equals(level) ? null : fix));
        }
    }

    /** */
    public List<HostReport> hosts = new ArrayList<>();

    /** */
    public boolean ok;

    /** This machine. A foreign JVM is worth a warning here: it may be the developer's own. */
    static StandCheck local(Path dir) throws IOException {
        new CommandLog(System.out).note(null, "this machine: the probe runs inside Check, no ssh");
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

    /** Every command goes to the output as it runs: see {@link CommandLog}. */
    static StandCheck remote(StandConfig stand, Path harnessJar, String runId) {
        return remote(stand, harnessJar, runId, new CommandLog(System.out));
    }

    /** */
    static StandCheck remote(StandConfig stand, Path harnessJar, String runId, CommandLog log) {
        StandCheck c = new StandCheck();
        List<RemoteHost> reachable = new ArrayList<>();
        Map<RemoteHost, HostReport> reports = new LinkedHashMap<>();
        sshSetup(stand, log);

        for (StandConfig.Host h : stand.hosts) {
            HostReport r = new HostReport();
            r.address = h.address;
            r.role = h.role;
            c.hosts.add(r);
            RemoteHost host = new RemoteHost(stand, h, runId).log(log);
            knownHost(stand, h, log);

            try {
                host.installProbe(harnessJar);
                r.add("ssh", OK, "reachable, rsync and pkill are there");
                r.facts = host.readFacts();
                judge(r, FAIL);
                reachable.add(host);
                reports.put(host, r);
            }
            catch (IOException | RuntimeException e) {
                r.add("ssh", FAIL, hostKeyHint(e.getMessage(), stand.sshAcceptNewHosts));
            }
        }

        links(reachable, reports);
        c.finish();

        return c;
    }

    /** What ssh is going to use: the files are given to the application, and a wrong one is what fails most. */
    private static void sshSetup(StandConfig stand, CommandLog log) {
        String home = System.getProperty("user.home");
        log.note(null, "commands run as " + System.getProperty("user.name")
            + " on the machine of the application (in Docker: inside its container)");
        log.note(null, "ssh key: " + (blank(stand.sshKey)
            ? "not set, ssh offers the keys of " + home + "/.ssh as its config says" : describe(stand.sshKey)));
        log.note(null, "known_hosts: " + (blank(stand.sshKnownHosts)
            ? "not set, ssh reads " + home + "/.ssh/known_hosts"
            : describe(stand.sshKnownHosts) + "; only this file counts"));
        log.note(null, "a host known_hosts has no key of: " + (stand.sshAcceptNewHosts
            ? "ssh takes its key on first contact and adds it there (ssh.accept.new.hosts)"
            : "refused (ssh.accept.new.hosts = true takes its key on first contact)"));

        try {
            RemoteHost.run(List.of("ssh", "-V"), true, Duration.ofSeconds(10), log, null);
        }
        catch (IOException ignored) {
            // the log has it
        }
    }

    /** Whether known_hosts has the host as ssh looks it up: "Host key verification failed" is the lack of it. */
    private static void knownHost(StandConfig stand, StandConfig.Host h, CommandLog log) {
        String file = blank(stand.sshKnownHosts) ? System.getProperty("user.home") + "/.ssh/known_hosts"
            : stand.sshKnownHosts;

        String then = stand.sshAcceptNewHosts ? "ssh takes its key on first contact"
            : "ssh refuses the host until it has its key";

        if (!Files.exists(Path.of(file))) {
            log.note(h.address, file + " is missing: " + then);

            return;
        }

        if (!Files.isReadable(Path.of(file)))
            return;

        String name = h.port == 22 ? h.address : "[" + h.address + "]:" + h.port;

        try {
            String found = RemoteHost.run(List.of("ssh-keygen", "-F", name, "-f", file), true, Duration.ofSeconds(10),
                log, h.address);

            if (found.isBlank())
                log.note(h.address, file + " has no key of " + name + ": " + then);
        }
        catch (IOException ignored) {
            // the log has it
        }
    }

    /**
     * What "Host key verification failed" means here: ssh gives no reason of its own.
     *
     * @param message Error of a host.
     * @param acceptNew Whether ssh takes the keys of new hosts.
     * @return The message, with the reason when it is about the key of the host.
     */
    static String hostKeyHint(String message, boolean acceptNew) {
        if (message == null || !message.contains("Host key verification failed"))
            return message;

        return message + (acceptNew ? " (the host has another key than known_hosts says)"
            : " (known_hosts has no key of the host or another one: add it with ssh-keyscan, or set "
                + "ssh.accept.new.hosts = true to take the keys of new hosts)");
    }

    /** A file given to ssh: whether it is there for the user of the application. */
    static String describe(String path) {
        Path p = Path.of(path);

        if (!Files.exists(p))
            return path + " is missing";

        if (!Files.isReadable(p))
            return path + " is not readable by this user";

        try {
            return path + ", " + PosixFilePermissions.toString(Files.getPosixFilePermissions(p)) + ", "
                + Files.size(p) + " bytes";
        }
        catch (IOException | UnsupportedOperationException e) {
            return path + ", readable";
        }
    }

    /** */
    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
            jvms.isEmpty() ? "no other JVM" : jvms.size() + " other JVM(s): " + jvms.get(0),
            "stop them; what a killed phase left goes with Clean hosts");
        long swap = ((Number)f.get("swapMb")).longValue();
        r.add("swap", swap == 0 ? OK : WARN,
            swap == 0 ? "off" : swap / 1024 + " GB of swap: a node that touches it stops measuring what it should",
            "`sudo swapoff -a`, and drop the swap of /etc/fstab to keep it off");
        Object thp = f.get("thp");
        r.add("thp", "always".equals(thp) ? WARN : OK,
            thp == null ? "not reported" : thp + ("always".equals(thp) ? ": latency gets spikes" : ""),
            "`echo madvise | sudo tee /sys/kernel/mm/transparent_hugepage/enabled`; the kernel option "
                + "`transparent_hugepage=madvise` keeps it after a reboot");
        Object governor = f.get("governor");
        r.add("governor", governor == null || "performance".equals(governor) ? OK : WARN,
            governor == null ? "not reported" : governor + ("performance".equals(governor) ? ""
                : ": the frequency follows the load, results follow it"),
            "`sudo cpupower frequency-set -g performance`");
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
        server.add("clock", off >= CLOCK_FAIL_MS ? FAIL : off >= CLOCK_WARN_MS ? WARN : OK, off + " ms from " + loader,
            "keep the clocks in sync: chrony or ntpd on every host");
    }

    /** */
    private void finish() {
        ok = hosts.stream().allMatch(h -> !h.items.isEmpty() && h.items.stream().noneMatch(i -> FAIL.equals(i.level)));
    }
}
