package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A child JVM (server node or loader) started by the launcher, with its log file. */
final class JvmProcess {
    /** What to start: main class, heap and options of the JVM. */
    static final class Spec {
        /** */
        String label;

        /** */
        String mainClass;

        /** */
        int heapMb;

        /** */
        List<String> jvmOpts = List.of();

        /** */
        String cpuset;

        /** */
        Path harnessJar;

        /** */
        Path libsDir;

        /** The phase directory on the host of this JVM: JFR, temp files and work directories go there. */
        String dir;

        /** */
        boolean jfr;

        /** */
        String runId;

        /** */
        List<String> args = List.of();

        /** */
        Path logFile;
    }

    /** How a JVM is signalled: {@code Process} reaches a local one, a remote one takes another ssh call. */
    interface Signals {
        /** */
        void term(Process p);

        /** */
        void kill(Process p);
    }

    /** */
    private static final Signals LOCAL = new Signals() {
        @Override public void term(Process p) {
            p.destroy();
        }

        @Override public void kill(Process p) {
            p.destroyForcibly();
        }
    };

    /** */
    private final Process process;

    /** */
    private final Path logFile;

    /** */
    private final String label;

    /** */
    private final Signals signals;

    /** */
    private JvmProcess(Process process, Path logFile, String label, Signals signals) {
        this.process = process;
        this.logFile = logFile;
        this.label = label;
        this.signals = signals;
    }

    /** The value of {@code -Dignite.firewatch.run}: every JVM has its own, and they all start with the run id. */
    static String marker(Spec s) {
        return s.runId + "-" + s.label;
    }

    /** The command line of the JVM, with the paths of the host it runs on. */
    static List<String> javaCommand(Spec s, String java, String classpath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-Xms" + s.heapMb + "m");
        cmd.add("-Xmx" + s.heapMb + "m");
        cmd.addAll(s.jvmOpts);
        cmd.addAll(Jvm.ADD_OPENS);
        cmd.addAll(Jvm.systemProps(marker(s)));
        // Temp files and the JFR repository live inside the run directory: works in containers and
        // sandboxes where the system temp dir is not writable, and leaves nothing behind.
        cmd.add("-Djava.io.tmpdir=" + s.dir + "/work/tmp");

        if (s.jfr) {
            cmd.add("-XX:FlightRecorderOptions=repository=" + s.dir + "/work/jfr-" + s.label);
            cmd.add("-XX:StartFlightRecording=name=ignite-firewatch,filename=" + s.dir + "/" + s.label + ".jfr"
                + ",settings=" + s.dir + "/ignite-firewatch.jfc,dumponexit=true");
        }

        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(s.mainClass);
        cmd.addAll(s.args);

        return cmd;
    }

    /** */
    static JvmProcess start(Spec s) throws IOException {
        List<String> cmd = new ArrayList<>();

        if (s.cpuset != null && !s.cpuset.isBlank()) {
            Path taskset = findOnPath("taskset");

            if (taskset != null)
                cmd.addAll(List.of(taskset.toString(), "-c", s.cpuset));
            else
                System.out.println(s.label + ": taskset not found, cpuset '" + s.cpuset + "' ignored");
        }

        cmd.addAll(javaCommand(s, Jvm.javaExecutable(), Jvm.classpath(s.harnessJar, s.libsDir)));
        Files.createDirectories(Path.of(s.dir, "work", "tmp"));

        if (s.jfr)
            Files.createDirectories(Path.of(s.dir, "work", "jfr-" + s.label));

        return start(cmd, s, LOCAL);
    }

    /** Starts {@code cmd}, whose output is the output of the JVM, wherever that JVM runs. */
    static JvmProcess start(List<String> cmd, Spec s, Signals signals) throws IOException {
        Files.createDirectories(s.logFile.toAbsolutePath().getParent());
        Files.writeString(s.logFile.resolveSibling(s.label + ".cmd"), String.join(" ", cmd) + System.lineSeparator());
        Process p = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(s.logFile.toFile())
            .start();

        return new JvmProcess(p, s.logFile, s.label, signals);
    }

    /** Waits until the log contains {@code marker}; false on timeout or if the process died first. */
    boolean awaitLogLine(String marker, Duration timeout) throws InterruptedException, IOException {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (true) {
            if (logContains(marker))
                return true;

            if (!process.isAlive())
                return logContains(marker);

            if (System.nanoTime() > deadline)
                return false;

            Thread.sleep(200);
        }
    }

    /** */
    private boolean logContains(String marker) throws IOException {
        return Files.exists(logFile) && Files.readString(logFile).contains(marker);
    }

    /** Exit code, or null if the process is still running after {@code timeout}. */
    Integer waitFor(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) ? process.exitValue() : null;
    }

    /** SIGTERM first (lets Ignite stop and JFR dump), SIGKILL after the grace period. */
    void stopGracefully(Duration grace) throws InterruptedException {
        if (!process.isAlive())
            return;

        signals.term(process);

        if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            System.out.println(label + ": did not stop within " + grace.toSeconds() + " s, killing");
            signals.kill(process);
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** */
    void kill() {
        if (process.isAlive())
            signals.kill(process);
    }

    /** */
    boolean isAlive() {
        return process.isAlive();
    }

    /** Exit code, or -1 while the process is still running. */
    int exitCode() {
        return process.isAlive() ? -1 : process.exitValue();
    }

    /** The most telling log line for an error message: the last root cause if any, else the last line. */
    String lastLogLine() {
        try {
            if (!Files.exists(logFile))
                return "";

            List<String> lines = tailLines(logFile, 64 * 1024);
            String lastNonEmpty = "";

            for (int i = lines.size() - 1; i >= 0; i--) {
                String l = lines.get(i).trim();

                if (l.isEmpty() || l.startsWith("at ") || l.startsWith("..."))
                    continue;

                if (lastNonEmpty.isEmpty())
                    lastNonEmpty = l;

                if (l.startsWith("Caused by:") || l.contains("Exception:") || l.contains("Error:"))
                    return truncate(l);
            }

            return truncate(lastNonEmpty);
        }
        catch (IOException ignored) {
            return "";
        }
    }

    /**
     * The end of a log, at most {@code maxBytes} of it: a node log can run to hundreds of MB. A line cut at the start
     * is dropped.
     */
    static List<String> tailLines(Path file, int maxBytes) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long from = Math.max(0, ch.size() - maxBytes);
            ByteBuffer buf = ByteBuffer.allocate((int)(ch.size() - from));
            ch.position(from);

            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0)
                    break;
            }

            List<String> lines = new ArrayList<>(
                new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8).lines().toList());

            if (from > 0 && !lines.isEmpty())
                lines.remove(0);

            return lines;
        }
    }

    /** */
    private static String truncate(String l) {
        return l.length() > 240 ? l.substring(0, 240) + "..." : l;
    }

    /** */
    long pid() {
        return process.pid();
    }

    /** */
    Path logFile() {
        return logFile;
    }

    /** */
    String label() {
        return label;
    }

    /** */
    private static Path findOnPath(String exe) {
        String path = System.getenv("PATH");

        if (path == null)
            return null;

        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path p = Path.of(dir, exe);

            if (Files.isExecutable(p))
                return p;
        }

        return null;
    }
}
