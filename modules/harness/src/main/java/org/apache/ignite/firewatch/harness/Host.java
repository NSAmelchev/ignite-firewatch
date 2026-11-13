package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * A machine of the stand that runs the JVMs of a phase: this one ({@link Local}) or a {@link RemoteHost}.
 * {@link Placement}
 * maps the JVMs of a scenario to them. Results are always gathered in the phase directory of this machine.
 */
interface Host {
    /** The address other nodes use to reach JVMs on this host. */
    String address();

    /** How the host is called in results. */
    default String name() {
        return address();
    }

    /** What {@link HostProbe} says about the host; null when it stays this machine. */
    default Map<String, Object> facts() {
        return null;
    }

    /**
     * The counters a phase reads before and after its load, see {@link HostProbe#counters()}.
     *
     * @return The counters, or null when the host cannot tell.
     */
    default Map<String, Object> counters() {
        return HostProbe.counters();
    }

    /** The directory of the phase on this host, for its JVMs; {@code out} is the phase directory of this machine. */
    String dir(Path out);

    /** Puts what the JVMs need on the host: the harness, the Ignite jars and the JFR settings. */
    default void prepare(Path harnessJar, Path libsDir, Path out) throws IOException {
    }

    /** */
    JvmProcess start(JvmProcess.Spec spec) throws IOException;

    /** Brings a file of {@link #dir} to {@code out}; false if it could not. */
    default boolean fetch(String file, Path out) {
        return true;
    }

    /** Leaves nothing of the phase on the host. */
    default void cleanup(Path out) {
    }

    /** This machine: child processes bound to 127.0.0.1. */
    final class Local implements Host {
        /** */
        static final Local INSTANCE = new Local();

        /** {@inheritDoc} */
        @Override public String address() {
            return "127.0.0.1";
        }

        /** {@inheritDoc} */
        @Override public String dir(Path out) {
            return out.toAbsolutePath().toString();
        }

        /** {@inheritDoc} */
        @Override public JvmProcess start(JvmProcess.Spec spec) throws IOException {
            return JvmProcess.start(spec);
        }
    }
}
