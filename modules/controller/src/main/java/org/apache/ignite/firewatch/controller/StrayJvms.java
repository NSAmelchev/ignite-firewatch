package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Finds and kills benchmark JVMs by the {@code -Dignite.firewatch.run=} marker the harness puts on every child. */
public final class StrayJvms {
    /** */
    public static final String MARKER = "-Dignite.firewatch.run=";

    /**
     * Finds the benchmark JVMs.
     *
     * @param runIdPrefix Run id prefix, or {@code null} for all runs.
     * @return Marked JVMs.
     */
    public static List<ProcessHandle> find(String runIdPrefix) {
        return ProcessHandle.allProcesses()
            .filter(ph -> {
                String cmd;

                try {
                    cmd = ph.info().commandLine().orElse("");
                }
                catch (RuntimeException e) {
                    return false; // not ours: the OS refuses to show the command line of other users' processes
                }

                int i = cmd.indexOf(MARKER);

                if (i < 0)
                    return false;

                return runIdPrefix == null || cmd.startsWith(runIdPrefix, i + MARKER.length());
            })
            .collect(Collectors.toList());
    }

    /**
     * Kills every marked JVM (optionally only those whose run id starts with {@code runIdPrefix}); returns pids.
     *
     * @param runIdPrefix Run id prefix, or {@code null} for all runs.
     * @return Pids of the killed JVMs.
     */
    public static List<Long> killAll(String runIdPrefix) {
        List<Long> pids = new ArrayList<>();

        for (ProcessHandle ph : find(runIdPrefix)) {
            pids.add(ph.pid());
            ph.destroyForcibly();
        }

        if (!pids.isEmpty())
            Log.warn("killed stray benchmark JVMs: " + pids);

        return pids;
    }

    /** */
    private StrayJvms() {
    }
}
