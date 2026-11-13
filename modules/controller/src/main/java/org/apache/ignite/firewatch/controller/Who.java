package org.apache.ignite.firewatch.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Who asks: the name a browser keeps for its user and sends with every request ({@link #HEADER}, URL-encoded, since a
 * header carries no Cyrillic). It is not authentication: anyone can type any name; it tells who queued and who
 * cancelled a job. The router keeps the name for the thread of a request, so whatever queues or cancels a job on that
 * thread records it; the threads of the application itself (the watcher, the worker) have none.
 */
public final class Who {
    /** The header the UI sends the name in. */
    public static final String HEADER = "X-Firewatch-User";

    /** Longest name. */
    static final int MAX = 40;

    /** A letter or a digit first, then letters, digits, spaces and {@code . _ @ -}: 2 to {@link #MAX} in all. */
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} ._@-]{1," + (MAX - 1) + "}");

    /** */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** */
    private Who() {
    }

    /**
     * Brings a name to its form: composed (NFC, so an accent typed apart is one letter), no spaces at the ends, one between
     * words; the same rule the UI checks.
     *
     * @param raw Name as typed.
     * @return The name, or {@code null} when it is not one.
     */
    public static String normalize(String raw) {
        if (raw == null)
            return null;

        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");

        return NAME.matcher(s).matches() ? s : null;
    }

    /**
     * Reads the name of the header.
     *
     * @param header Value of {@link #HEADER}, or {@code null}.
     * @return The name, or {@code null} when there is none or it is not one.
     */
    static String fromHeader(String header) {
        if (header == null)
            return null;

        try {
            return normalize(URLDecoder.decode(header, StandardCharsets.UTF_8));
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Who asks on this thread.
     *
     * @return The name, or {@code null} outside a request or when the request named nobody.
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Sets who asks on this thread.
     *
     * @param name Name, or {@code null} to clear it.
     */
    static void set(String name) {
        if (name == null)
            CURRENT.remove();
        else
            CURRENT.set(name);
    }
}
