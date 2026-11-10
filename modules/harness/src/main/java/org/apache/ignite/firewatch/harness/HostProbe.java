package org.apache.ignite.firewatch.harness;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a host says about itself: runs there on the JDK of the stand, so a broken JDK shows up by itself, and needs no
 * tools on the
 * host and no Ignite on the classpath. {@code facts DIR} prints the machine as JSON; {@code listen PORTS SEC} holds
 * ports open and
 * tells whoever connects the time of this host; {@code connect ADDR:PORT,...} tries them and compares the clocks.
 */
public final class HostProbe {
    /** Printed by {@code listen} once every port is bound. */
    static final String READY = "PROBE_READY";

    /** */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /**
     * Runs the probe command given in the first argument.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "facts":
                System.out.println(Json.CANONICAL.writeValueAsString(facts(Path.of(args[1]))));
                break;
            case "listen":
                listen(args[1], Integer.parseInt(args[2]));
                break;
            case "connect":
                System.out.println(Json.CANONICAL.writeValueAsString(connect(args[1])));
                break;
            default:
                throw new IllegalArgumentException("unknown probe: " + args[0]);
        }
    }

    /** */
    static Map<String, Object> facts(Path dir) throws IOException {
        Files.createDirectories(dir);
        var os = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jdk", System.getProperty("java.runtime.version"));
        m.put("javaMajor", Runtime.version().feature());
        m.put("kernel", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("cpu", cpuModel());
        m.put("cpus", Runtime.getRuntime().availableProcessors());
        m.put("memMb", os.getTotalMemorySize() / (1024 * 1024));
        m.put("memFreeMb", os.getFreeMemorySize() / (1024 * 1024));
        m.put("swapMb", os.getTotalSwapSpaceSize() / (1024 * 1024));
        m.put("diskFreeMb", new File(dir.toString()).getUsableSpace() / (1024 * 1024));
        m.put("thp", selected(readLine("/sys/kernel/mm/transparent_hugepage/enabled")));
        m.put("governor", readLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"));
        m.put("foreignJvms", foreignJvms());

        return m;
    }

    /**
     * JVMs that are not part of Firewatch: the application itself and its harness are not noise of their own making.
     */
    private static List<String> foreignJvms() {
        long self = ProcessHandle.current().pid();
        List<String> out = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(p -> {
            String cmd, line;

            try {
                cmd = p.info().command().orElse("");
                line = p.info().commandLine().orElse(cmd);
            }
            catch (RuntimeException e) {
                return; // the OS may refuse to show a process of another user
            }

            if (p.pid() != self && cmd.endsWith("/java") && !line.contains("controller.jar")
                && !line.contains("harness.jar"))
                out.add(p.pid() + " " + (line.length() > 120 ? line.substring(0, 120) : line));
        });

        return out;
    }

    /** */
    private static String cpuModel() {
        try (BufferedReader r = Files.newBufferedReader(Path.of("/proc/cpuinfo"))) {
            String l;

            while ((l = r.readLine()) != null) {
                if (l.startsWith("model name"))
                    return l.substring(l.indexOf(':') + 1).trim();
            }
        }
        catch (IOException ignored) {
            // not Linux: the architecture is all there is
        }

        return System.getProperty("os.arch");
    }

    /** */
    private static String readLine(String file) {
        try {
            return Files.readString(Path.of(file)).trim();
        }
        catch (IOException e) {
            return null;
        }
    }

    /** {@code always madvise [never]} is {@code never}: the kernel marks the value in force. */
    static String selected(String line) {
        if (line == null)
            return null;

        Matcher m = Pattern.compile("\\[(.+?)]").matcher(line);

        return m.find() ? m.group(1) : line;
    }

    /** */
    private static void listen(String ports, int seconds) throws Exception {
        List<ServerSocket> sockets = new ArrayList<>();

        for (String p : ports.split(",")) {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Integer.parseInt(p)));
            sockets.add(s);
        }

        for (ServerSocket s : sockets) {
            Thread t = new Thread(() -> serve(s), "probe");
            t.setDaemon(true);
            t.start();
        }

        System.out.println(READY);
        Thread.sleep(seconds * 1000L);
        System.exit(0);
    }

    /** */
    private static void serve(ServerSocket s) {
        while (true) {
            try (Socket c = s.accept()) {
                c.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(c.getInputStream());
                in.readByte(); // the time is read at the moment of the question, not of the connection
                new DataOutputStream(c.getOutputStream()).writeLong(System.currentTimeMillis());
            }
            catch (IOException e) {
                if (s.isClosed())
                    return;
            }
        }
    }

    /**
     * For every target: whether it answered and how far its clock is ahead of this host, the time on the wire taken
     * out.
     */
    private static List<Map<String, Object>> connect(String targets) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (String target : targets.split(",")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("target", target);
            int colon = target.lastIndexOf(':');

            try (Socket c = new Socket()) {
                c.connect(new InetSocketAddress(target.substring(0, colon),
                    Integer.parseInt(target.substring(colon + 1))), CONNECT_TIMEOUT_MS);
                c.setSoTimeout(CONNECT_TIMEOUT_MS);
                c.setTcpNoDelay(true);
                long sent = System.currentTimeMillis();
                c.getOutputStream().write(1);
                c.getOutputStream().flush();
                long remote = new DataInputStream(c.getInputStream()).readLong();
                long got = System.currentTimeMillis();
                m.put("ok", true);
                m.put("clockAheadMs", remote - (sent + got) / 2);
            }
            catch (IOException e) {
                m.put("ok", false);
                m.put("error", e.toString());
            }

            out.add(m);
        }

        return out;
    }

    /** */
    private HostProbe() {
    }
}
