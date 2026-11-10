package org.apache.ignite.firewatch.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The scenarios the harness carries: Java classes in the harness jar, listed once at startup with
 * {@code harness.jar list} (id, version, topology, timing and source). The controller keeps only what it needs for
 * timeouts, estimates and display; the source goes to the Scenarios page as is.
 */
public final class Scenarios {
    /** Controller view of one scenario. */
    public static final class Info {
        /** The stable id ({@code @Bench(id)}): the key of jobs and history. */
        public String name;

        /** What the UI shows ({@code @Bench(title)}, else the id); free to change. */
        public String title;

        /** */
        public int version;

        /** */
        public String hash;

        /** */
        public String description;

        /** */
        public int servers;

        /** */
        public String client;

        /** */
        public int threads;

        /** */
        public int warmupSec;

        /** */
        public int measureSec;

        /** */
        public int startTimeoutSec;

        /** */
        public int setupTimeoutSec;

        /** */
        public int rateOpsPerSec;

        /**
         * Ignite modules besides ignite-core on the nodes' classpath ("indexing", "calcite"): picks the build of a
         * commit.
         */
        public List<String> modules = List.of();

        /** Everything {@code harness.jar list} printed for this scenario, sources included. */
        @JsonIgnore public JsonNode raw;

        /**
         * Returns the warmup and measurement time together.
         *
         * @return Total seconds.
         */
        public int totalSeconds() {
            return warmupSec + measureSec;
        }

        /**
         * For tests and fakes: a scenario with the given timing and the harness defaults otherwise.
         *
         * @param name Scenario name.
         * @param warmupSec Warmup, seconds.
         * @param measureSec Measurement, seconds.
         * @return Scenario info.
         */
        public static Info of(String name, int warmupSec, int measureSec) {
            Info i = new Info();
            i.name = name;
            i.version = 1;
            i.hash = name + ":1";
            i.description = "";
            i.servers = 1;
            i.client = "thin";
            i.threads = 4;
            i.warmupSec = warmupSec;
            i.measureSec = measureSec;
            i.startTimeoutSec = 10;
            i.setupTimeoutSec = 10;

            return i;
        }
    }

    /** */
    private final List<Info> list;

    /**
     * Creates a catalog.
     *
     * @param list Scenarios.
     */
    public Scenarios(List<Info> list) {
        this.list = List.copyOf(list);
    }

    /**
     * Runs {@code harness.jar list}; an empty catalog (with a warning) if the harness cannot be run.
     *
     * @param cfg Controller config.
     * @return Scenarios catalog.
     */
    public static Scenarios fromHarness(Config cfg) {
        List<String> cmd = new ArrayList<>(cfg.harnessCommand != null ? cfg.harnessCommand
            : List.of(cfg.javaExe, "-Xmx64m", "-jar", cfg.harnessJar.toAbsolutePath().toString()));
        cmd.add("list");
        List<Info> out = new ArrayList<>();

        try {
            Proc.Result res = Proc.run(cmd, null, cfg.harnessEnv, Duration.ofSeconds(60));
            int start = res.out.indexOf('[');

            if (!res.ok() || start < 0)
                throw new IllegalStateException("harness list failed: " + res.tail(5));

            for (JsonNode n : Json.MAPPER.readTree(res.out.substring(start)))
                out.add(parse(n));
        }
        catch (Exception e) {
            Log.warn("no scenarios: " + e.getMessage());
        }

        return new Scenarios(out);
    }

    /** */
    static Info parse(JsonNode n) {
        Info i = new Info();
        i.name = n.path("name").asText();
        i.title = n.path("title").asText(i.name);
        i.version = n.path("version").asInt(1);
        i.hash = n.path("hash").asText(null);
        i.description = n.path("description").asText("");
        i.servers = n.path("servers").path("count").asInt(1);
        i.client = n.path("loader").path("client").asText("thick");
        i.threads = n.path("loader").path("threads").asInt(1);
        i.rateOpsPerSec = n.path("loader").path("rateOpsPerSec").asInt(0);
        i.warmupSec = n.path("warmupSec").asInt(30);
        i.measureSec = n.path("measureSec").asInt(90);
        i.startTimeoutSec = n.path("startTimeoutSec").asInt(120);
        i.setupTimeoutSec = n.path("setupTimeoutSec").asInt(120);
        List<String> modules = new ArrayList<>();
        n.path("modules").forEach(m -> modules.add(m.asText()));
        i.modules = List.copyOf(modules);
        i.raw = n;

        return i;
    }

    /**
     * Returns all known scenarios.
     *
     * @return Scenarios.
     */
    public List<Info> list() {
        return list;
    }

    /**
     * Finds a scenario by name.
     *
     * @param name Scenario name.
     * @return Scenario, or {@code null} if there is no such scenario.
     */
    public Info get(String name) {
        for (Info i : list)
            if (i.name.equals(name))
                return i;

        return null;
    }

    /**
     * Upper bound for one harness phase: its own worst case (servers start, the loader's timeout, 60 s stop grace per
     * server plus JFR parsing) plus a grace period, so the harness always reports its own error first.
     *
     * @param i Scenario.
     * @param graceSec Grace period, seconds.
     * @return Phase timeout.
     */
    public static Duration phaseTimeout(Info i, int graceSec) {
        int servers = Math.max(1, i.servers);

        return Duration.ofSeconds(i.startTimeoutSec + (i.startTimeoutSec + i.setupTimeoutSec + i.totalSeconds() + 90)
            + servers * 70L + graceSec);
    }
}
