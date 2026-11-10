package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Hot methods of a phase from {@code jdk.ExecutionSample}: how many samples in the measure window had a method
 * on top of the stack ({@code self}) or anywhere in it ({@code total}). Written to {@code profile.json}; the
 * controller diffs the A and B sides of an ABBA job.
 */
public final class JfrProfile {
    /** Frames kept per list; more only inflates the file without adding signal. */
    static final int TOP = 200;

    /** Number of samples of one method. */
    public static final class Entry {
        /** */
        public String method;

        /** */
        public long samples;

        /** */
        Entry(String method, long samples) {
            this.method = method;
            this.samples = samples;
        }

        /** */
        public Entry() {
        }
    }

    /** Sample counts of the hottest methods of one node or phase. */
    public static final class Profile {
        /** */
        public long samples;

        /** */
        public List<Entry> self = new ArrayList<>();

        /** */
        public List<Entry> total = new ArrayList<>();
    }

    /**
     * Builds a profile from the execution samples of a JFR recording taken in the measurement window.
     *
     * @param jfr JFR recording file.
     * @param measureStartMs Start of the measurement, epoch milliseconds.
     * @param measureEndMs End of the measurement, epoch milliseconds.
     * @return Profile of the window.
     * @throws IOException If the recording cannot be read.
     */
    public static Profile parse(Path jfr, long measureStartMs, long measureEndMs) throws IOException {
        Map<String, long[]> self = new HashMap<>();
        Map<String, long[]> total = new HashMap<>();
        long samples = 0;
        Set<String> seen = new HashSet<>();

        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();

                if (!"jdk.ExecutionSample".equals(e.getEventType().getName()))
                    continue;

                long ms = e.getStartTime().toEpochMilli();

                if (ms < measureStartMs || ms >= measureEndMs)
                    continue;

                RecordedStackTrace st = e.getStackTrace();

                if (st == null || st.getFrames().isEmpty())
                    continue;

                samples++;
                List<RecordedFrame> frames = st.getFrames();
                self.computeIfAbsent(name(frames.get(0).getMethod()), k -> new long[1])[0]++;
                seen.clear();

                for (RecordedFrame f : frames) {
                    String n = name(f.getMethod());

                    if (seen.add(n))
                        total.computeIfAbsent(n, k -> new long[1])[0]++;
                }
            }
        }

        Profile p = new Profile();
        p.samples = samples;
        p.self = top(self);
        p.total = top(total);

        return p;
    }

    /**
     * Sums profiles of several nodes (same phase).
     *
     * @param parts Profiles of the nodes.
     * @return Merged profile.
     */
    public static Profile merge(List<Profile> parts) {
        Map<String, long[]> self = new HashMap<>(), total = new HashMap<>();
        Profile out = new Profile();

        for (Profile p : parts) {
            out.samples += p.samples;

            for (Entry e : p.self)
                self.computeIfAbsent(e.method, k -> new long[1])[0] += e.samples;

            for (Entry e : p.total)
                total.computeIfAbsent(e.method, k -> new long[1])[0] += e.samples;
        }

        out.self = top(self);
        out.total = top(total);

        return out;
    }

    /** */
    private static List<Entry> top(Map<String, long[]> counts) {
        List<Entry> all = new ArrayList<>(counts.size());
        counts.forEach((k, v) -> all.add(new Entry(k, v[0])));
        all.sort((a, b) -> Long.compare(b.samples, a.samples));

        return all.size() > TOP ? new ArrayList<>(all.subList(0, TOP)) : all;
    }

    /** */
    static String name(RecordedMethod m) {
        if (m == null)
            return "?";

        String type = m.getType() == null ? "?" : m.getType().getName();

        return type + "." + m.getName();
    }

    /** */
    private JfrProfile() {
    }
}
