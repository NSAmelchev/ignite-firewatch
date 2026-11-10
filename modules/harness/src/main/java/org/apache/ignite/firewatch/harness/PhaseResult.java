package org.apache.ignite.firewatch.harness;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code result.json}: everything measured in one phase (one version, fresh JVMs). */
public final class PhaseResult {
    /** */
    public String harnessVersion;

    /** */
    public String scenarioName;

    /** */
    public String scenarioHash;

    /** */
    public String runId;

    /** */
    public String phase;

    /** {@code ok} or {@code failed}. */
    public String status;

    /** */
    public String error;

    /** */
    public String igniteVersion;

    /** */
    public String startedAt;

    /** */
    public String measureStartAt;

    /** */
    public int warmupSec;

    /** */
    public int measureSec;

    /** */
    public double wallSec;

    /** */
    public long connectMs;

    /** */
    public long preloadMs;

    /** Slowest server node: milliseconds inside Ignition.start(). */
    public Long serverStartMs;

    /** */
    public Stats.Summary throughput;

    /** */
    public LoaderResult.Latency latencyUs;

    /** */
    public Gc gc;

    /** */
    public Cpu cpu;

    /** */
    public Heap heap;

    /** */
    public long errors;

    /** */
    public List<NodeResult> nodes = new ArrayList<>();

    /** */
    public Env env;

    /** The host of the loader on a remote stand. */
    public String loaderHost;

    /** What every host of a remote stand said about itself before the phase, by name. */
    public Map<String, Map<String, Object>> hosts;

    /** Garbage collection of the server JVMs. */
    public static final class Gc {
        /** */
        public int count;

        /** */
        public double totalPauseMs;

        /** */
        public double maxPauseMs;

        /** */
        public double pausePct;

        /** */
        public double allocMbPerSec;
    }

    /** CPU usage. */
    public static final class Cpu {
        /** Sum over server JVMs, percent of all CPUs of the machine. */
        public double serverJvmPct;

        /** */
        public double machinePct;

        /** */
        public Double loaderJvmPct;
    }

    /** Heap usage of the server JVMs. */
    public static final class Heap {
        /** */
        public Double afterGcMbMean;

        /** */
        public Double committedMbMax;
    }

    /** Result of one server node. */
    public static final class NodeResult {
        /** */
        public int index;

        /** */
        public String host;

        /** */
        public Gc gc;

        /** */
        public Cpu cpu;

        /** */
        public Heap heap;

        /** */
        public String jfrFile;

        /** */
        public String logFile;
    }

    /** Environment of the phase. */
    public static final class Env {
        /** */
        public String jdk;

        /** */
        public String os;

        /** */
        public String arch;

        /** */
        public int cpus;

        /** */
        public long totalMemMb;

        /** */
        static Env current() {
            Env e = new Env();
            e.jdk = System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
            e.os = System.getProperty("os.name") + " " + System.getProperty("os.version");
            e.arch = System.getProperty("os.arch");
            e.cpus = Runtime.getRuntime().availableProcessors();
            e.totalMemMb = ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean())
                .getTotalMemorySize() / (1024 * 1024);

            return e;
        }
    }
}
