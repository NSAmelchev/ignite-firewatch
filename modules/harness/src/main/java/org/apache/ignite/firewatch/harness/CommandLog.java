package org.apache.ignite.firewatch.harness;

import java.io.PrintStream;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What Check does on the hosts, printed as it goes: every command as it could be typed into a shell, then how it ended
 * and what it said. The controller shows these lines to whoever runs Check, so "ssh does not work" turns into the reason
 * ssh gives. A line is {@link #PREFIX}, the host (none for this machine) and one of: {@code $ command},
 * {@code → exit 0 in 0.3 s}, {@code | output} or {@code · note}.
 */
final class CommandLog {
    /** Starts every line of the log: the controller tells them from the rest of the output by it. */
    static final String PREFIX = "[check] ";

    /** Output lines kept of one command: ssh -v says what matters within them. */
    static final int MAX_LINES = 120;

    /** A line is cut beyond this: the facts of a host are one line of JSON. */
    static final int MAX_LINE = 2000;

    /** What a shell takes as it is; anything else goes into single quotes. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=,@+%^-]+");

    /** */
    private final PrintStream out;

    /** */
    CommandLog(PrintStream out) {
        this.out = out;
    }

    /**
     * Prints the command before it runs.
     *
     * @param host Host the command is about, or {@code null} for this machine.
     * @param cmd Command.
     * @return Start time for {@link #end}.
     */
    long start(String host, List<String> cmd) {
        line(host, "$ " + shell(cmd));

        return System.nanoTime();
    }

    /**
     * Prints how the command ended and what it said.
     *
     * @param host Host the command is about, or {@code null} for this machine.
     * @param started What {@link #start} returned.
     * @param exit Exit code, or {@code null} when the command did not end by itself.
     * @param output Output of the command.
     */
    void end(String host, long started, Integer exit, String output) {
        String took = String.format("%.1f s", (System.nanoTime() - started) / 1e9);
        line(host, "→ " + (exit == null ? "stopped after " + took : "exit " + exit + " in " + took));
        output(host, output);
    }

    /**
     * Prints a line of its own: what Check knows without running anything.
     *
     * @param host Host the note is about, or {@code null}.
     * @param text Note.
     */
    void note(String host, String text) {
        line(host, "· " + text);
    }

    /** */
    private synchronized void output(String host, String output) {
        if (output == null || output.isBlank())
            return;

        List<String> lines = output.strip().lines().toList();

        for (String l : lines.subList(0, Math.min(lines.size(), MAX_LINES)))
            line(host, "| " + (l.length() > MAX_LINE ? l.substring(0, MAX_LINE) + "…" : l));

        if (lines.size() > MAX_LINES)
            line(host, "| … " + (lines.size() - MAX_LINES) + " more lines");
    }

    /** */
    private synchronized void line(String host, String text) {
        out.println(PREFIX + (host == null || host.isEmpty() ? "" : host + " ") + text);
        out.flush();
    }

    /**
     * Gets the command as a shell would take it.
     *
     * @param cmd Command.
     * @return Words joined by spaces, quoted where needed.
     */
    static String shell(List<String> cmd) {
        return cmd.stream().map(w -> PLAIN.matcher(w).matches() ? w : RemoteHost.quote(w)).collect(Collectors.joining(" "));
    }
}
