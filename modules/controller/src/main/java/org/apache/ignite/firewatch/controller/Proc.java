package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** External processes: short commands with captured output, long ones with a log file and cancellation. */
public final class Proc {
    /** Outcome of a finished command. */
    public static final class Result {
        /** */
        public int code;

        /** */
        public String out;

        /** */
        public boolean timedOut;

        /**
         * Checks that the command exited with code 0 in time.
         *
         * @return {@code True} if the command succeeded.
         */
        public boolean ok() {
            return code == 0 && !timedOut;
        }

        /**
         * Gets the last lines of the output.
         *
         * @param lines Number of lines.
         * @return Last lines.
         */
        public String tail(int lines) {
            return Proc.tail(out, lines);
        }
    }

    /**
     * Runs a command to completion, capturing stdout+stderr; kills it on timeout.
     *
     * @param cmd Command with arguments.
     * @param cwd Working directory, or {@code null} for the current one.
     * @param env Extra environment variables, or {@code null}.
     * @param timeout Time to wait for the command.
     * @return Result of the command.
     */
    public static Result run(List<String> cmd, Path cwd, Map<String, String> env, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);

        if (cwd != null)
            pb.directory(cwd.toFile());

        if (env != null)
            pb.environment().putAll(env);

        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        p.getOutputStream().close();
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;

                while ((n = in.read(buf)) > 0) {
                    synchronized (sb) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            }
            catch (IOException ignored) {
            }
        }, "proc-reader");
        reader.setDaemon(true);
        reader.start();
        Result r = new Result();

        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                r.timedOut = true;
                killTree(p.toHandle(), Duration.ofSeconds(5));
            }

            reader.join(5000);
            r.code = p.isAlive() ? -1 : p.exitValue();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(p.toHandle(), Duration.ofSeconds(2));
            r.code = -1;
        }

        synchronized (sb) {
            r.out = sb.toString();
        }

        return r;
    }

    /**
     * Starts a long-running command with stdout+stderr appended to {@code logFile}.
     *
     * @param cmd Command with arguments.
     * @param cwd Working directory, or {@code null} for the current one.
     * @param env Extra environment variables, or {@code null}.
     * @param logFile File the output is appended to.
     * @return Handle of the started process.
     */
    public static Handle start(List<String> cmd, Path cwd, Map<String, String> env, Path logFile) throws IOException {
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        if (cwd != null)
            pb.directory(cwd.toFile());

        if (env != null)
            pb.environment().putAll(env);

        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        return new Handle(pb.start(), logFile);
    }

    /** Handle of a started process with its log file. */
    public static final class Handle {
        /** */
        private final Process process;

        /** */
        private final Path logFile;

        /** */
        Handle(Process process, Path logFile) {
            this.process = process;
            this.logFile = logFile;
        }

        /**
         * Waits for exit. Returns the exit code, or null when the timeout elapsed or {@code cancelled}
         * became true (the caller decides what to do with the still-running process).
         *
         * @param timeout Time to wait for exit.
         * @param cancelled Cancellation flag, or {@code null}.
         * @return Exit code, or {@code null} on timeout or cancellation.
         */
        public Integer waitFor(Duration timeout, BooleanSupplier cancelled) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();

            while (true) {
                if (process.waitFor(1, TimeUnit.SECONDS))
                    return process.exitValue();

                if (System.nanoTime() > deadline || (cancelled != null && cancelled.getAsBoolean()))
                    return null;
            }
        }

        /**
         * SIGTERM to the process, then SIGKILL to it and every descendant still alive after the grace period.
         *
         * @param grace Time to wait between SIGTERM and SIGKILL.
         */
        public void killTree(Duration grace) {
            Proc.killTree(process.toHandle(), grace);
        }

        /**
         * Checks that the process is running.
         *
         * @return {@code True} if the process is alive.
         */
        public boolean alive() {
            return process.isAlive();
        }

        /**
         * Gets the process id.
         *
         * @return Process id.
         */
        public long pid() {
            return process.pid();
        }

        /**
         * Gets the log file of the process.
         *
         * @return Log file.
         */
        public Path logFile() {
            return logFile;
        }
    }

    /** */
    static void killTree(ProcessHandle root, Duration grace) {
        List<ProcessHandle> kids = root.descendants().collect(Collectors.toList());
        root.destroy();

        try {
            root.onExit().get(grace.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (Exception ignored) {
        }

        root.destroyForcibly();

        for (ProcessHandle k : kids)
            k.destroyForcibly();
    }

    /**
     * Gets the last lines of a text.
     *
     * @param text Text, may be {@code null}.
     * @param lines Number of lines.
     * @return Last lines, each ended with a newline; empty for {@code null}.
     */
    public static String tail(String text, int lines) {
        if (text == null)
            return "";

        String[] all = text.split("\n");
        int from = Math.max(0, all.length - lines);
        StringBuilder sb = new StringBuilder();

        for (int i = from; i < all.length; i++)
            sb.append(all[i]).append('\n');

        return sb.toString();
    }

    /**
     * Gets the last lines of a file.
     *
     * @param file File, may be {@code null}.
     * @param lines Number of lines.
     * @return Last lines; empty if there is no file.
     */
    public static String tailFile(Path file, int lines) {
        try {
            if (file == null || !Files.exists(file))
                return "";

            List<String> all = tailLines(file, 256 * 1024);

            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        }
        catch (IOException e) {
            return "(cannot read " + file + ": " + e.getMessage() + ")";
        }
    }

    /**
     * The line of a log that says why a JVM ended: the last root cause, exception or error near its end; empty when
     * there is none.
     *
     * @param file Log file, may be {@code null}.
     * @return Error line, or an empty string.
     */
    public static String lastError(Path file) {
        try {
            if (file == null || !Files.exists(file))
                return "";

            List<String> lines = tailLines(file, 64 * 1024);

            for (int i = lines.size() - 1; i >= 0; i--) {
                String l = lines.get(i).trim();

                if (l.startsWith("Caused by:") || l.contains("Exception:") || l.contains("Error:"))
                    return l.length() > 240 ? l.substring(0, 240) + "..." : l;
            }
        }
        catch (IOException ignored) {
        }

        return "";
    }

    /**
     * The end of a text file, at most {@code maxBytes} of it: a log can run to hundreds of MB. A line cut at the start
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

            List<String> lines = new ArrayList<>(new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8)
                .lines().toList());

            if (from > 0 && !lines.isEmpty())
                lines.remove(0);

            return lines;
        }
    }

    /** */
    private Proc() {
    }
}
