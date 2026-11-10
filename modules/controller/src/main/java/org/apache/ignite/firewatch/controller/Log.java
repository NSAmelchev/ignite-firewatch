package org.apache.ignite.firewatch.controller;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Minimal logger: stdout plus an in-memory ring the UI shows on the Health page. */
public final class Log {
    /** One line of the in-memory log. */
    public static final class Entry {
        /** */
        public String at;

        /** */
        public String level;

        /** */
        public String msg;
    }

    /** */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** */
    private static final Deque<Entry> RING = new ArrayDeque<>();

    /** */
    private static final int RING_SIZE = 300;

    /**
     * Logs a message with INFO level.
     *
     * @param msg Message.
     */
    public static void info(String msg) {
        log("INFO", msg);
    }

    /**
     * Logs a message with WARN level.
     *
     * @param msg Message.
     */
    public static void warn(String msg) {
        log("WARN", msg);
    }

    /**
     * Logs a message with ERROR level and the stack trace of the cause.
     *
     * @param msg Message.
     * @param t Cause, may be {@code null}.
     */
    public static void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();

        if (t != null)
            t.printStackTrace(new PrintWriter(sw));

        log("ERROR", msg + (t == null ? "" : ": " + t + "\n" + sw));
    }

    /** */
    private static void log(String level, String msg) {
        Entry e = new Entry();
        e.at = LocalDateTime.now().format(FMT);
        e.level = level;
        e.msg = msg;

        synchronized (RING) {
            RING.addLast(e);

            if (RING.size() > RING_SIZE)
                RING.removeFirst();
        }

        System.out.println(e.at + " " + level + " " + msg);
    }

    /**
     * Returns the latest log entries.
     *
     * @param n Maximum number of entries.
     * @return The latest entries, oldest first.
     */
    public static List<Entry> recent(int n) {
        synchronized (RING) {
            List<Entry> all = new ArrayList<>(RING);

            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    /** */
    private Log() {
    }
}
