package org.apache.ignite.firewatch.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ignite.firewatch.harness.api.Scale;

/**
 * Launcher entry point. Usage:
 * <pre>
 * java -jar harness.jar run --libs DIR --scenario ID --out DIR [--run-id ID] [--phase NAME] [--quick] [--stand FILE]
 *     [--scale MEMORY_MB,FILL_PCT,THREADS_FACTOR,1] [--warmup-sec N] [--measure-sec N] [--plugin JAR]
 * java -jar harness.jar list [--plugin JAR]
 * java -jar harness.jar check (--stand FILE | --dir DIR)
 * java -jar harness.jar sweep --stand FILE
 * </pre>
 * {@code DIR} holds the Ignite jars of the build under test (ignite-core and its runtime deps).
 * {@code --stand} names the hosts of a remote stand; without it every JVM runs on this machine.
 * {@code --scale} gives the sizes of the stand; without it the scenario runs with the numbers it states.
 * {@code run} writes {@code result.json}, {@code series.json}, node and loader logs and JFR files into {@code --out}
 * and exits with 0 when the phase completed, 1 when it failed, 2 on bad arguments.
 * {@code list} prints every scenario (id, version, topology, timing and source) as JSON for the controller.
 * {@code --plugin} names the jar of a plugin: {@code list} prints what it says of itself and its scenarios,
 * {@code run} runs one of them, the jar on the classpath of every JVM before the build under test.
 */
public final class Main {
    /**
     * Runs the harness command.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        if (args.length > 0 && "list".equals(args[0])) {
            // no Ignite on the classpath here: the catalog reads only the @Bench annotations
            try {
                String plugin = Cli.parse(args, 1).get("plugin", null);
                Path jar = plugin == null ? null : Path.of(plugin);
                List<Object> all = new ArrayList<>();

                for (Catalog.Entry e : Catalog.all(jar))
                    all.add(e.describe(true));

                // a plugin: what it says of itself, then its scenarios
                Object res = jar == null ? all : Map.of("plugin", Catalog.Plugin.read(jar).describe(), "scenarios", all);
                System.out.println(Json.MAPPER.writeValueAsString(res));
                System.exit(0);
            }
            catch (Exception e) {
                System.err.println("list failed: " + e);
                System.exit(2);
            }

            return;
        }

        if (args.length > 0 && ("check".equals(args[0]) || "sweep".equals(args[0])))
            System.exit(standCommand(args[0], args));

        if (args.length == 0 || !"run".equals(args[0])) {
            usage();
            System.exit(2);
        }

        Cli cli;
        Path libs, out;
        String scenario, runId, phase;

        try {
            cli = Cli.parse(args, 1);
            libs = Path.of(cli.req("libs"));
            scenario = cli.req("scenario");
            out = Path.of(cli.req("out"));
            runId = cli.get("run-id", "run-" + Instant.now().toEpochMilli());
            phase = cli.get("phase", "S");
        }
        catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            usage();
            System.exit(2);

            return;
        }

        PhaseRunner.Options o = new PhaseRunner.Options();
        String plugin = cli.get("plugin", null);
        o.plugin = plugin == null ? null : Path.of(plugin);
        String stand = cli.get("stand", null);
        o.standFile = stand == null ? null : Path.of(stand);
        o.scale = Scale.parse(cli.get("scale", null));
        o.warmupSec = cli.has("quick") ? Integer.valueOf(2)
            : cli.has("warmup-sec") ? Integer.valueOf(cli.getInt("warmup-sec", 0)) : null;
        o.measureSec = cli.has("quick") ? Integer.valueOf(5)
            : cli.has("measure-sec") ? Integer.valueOf(cli.getInt("measure-sec", 0)) : null;
        System.exit(new PhaseRunner(libs, scenario, out, runId, phase, o).run());
    }

    /** {@code check} prints the report as JSON; {@code sweep} clears what a killed launcher left on the hosts. */
    private static int standCommand(String command, String[] args) {
        try {
            Cli cli = Cli.parse(args, 1);
            String file = cli.get("stand", null);
            StandConfig stand = file == null ? null : Json.read(Path.of(file), StandConfig.class);

            if ("check".equals(command)) {
                StandCheck c = stand == null ? StandCheck.local(Path.of(cli.req("dir")))
                    : StandCheck.remote(stand, Jvm.ownLocation(), "check");
                System.out.println(Json.CANONICAL.writeValueAsString(c)); // one line: whatever came before it stays out

                return 0;
            }

            int failed = 0;

            for (StandConfig.Host h : stand == null ? List.<StandConfig.Host>of() : stand.hosts) {
                try {
                    new RemoteHost(stand, h, "sweep").sweep();
                }
                catch (IOException e) {
                    System.out.println(h.address + ": " + e.getMessage());
                    failed++;
                }
            }

            return failed == 0 ? 0 : 1;
        }
        catch (IOException | RuntimeException e) {
            System.err.println(command + " failed: " + e);

            return 2;
        }
    }

    /** */
    private static void usage() {
        System.err.println("Firewatch harness " + Jvm.harnessVersion());
        System.err.println("usage: java -jar harness.jar run --libs DIR --scenario ID --out DIR [--run-id ID] "
            + "[--phase NAME] [--quick] [--stand FILE]");
        System.err.println("         [--scale MEMORY_MB,FILL_PCT,THREADS_FACTOR,1] [--warmup-sec N] [--measure-sec N] "
            + "[--plugin JAR]");
        System.err.println("       java -jar harness.jar list [--plugin JAR]");
        System.err.println("       java -jar harness.jar check (--stand FILE | --dir DIR)");
        System.err.println("       java -jar harness.jar sweep --stand FILE");
    }
}
