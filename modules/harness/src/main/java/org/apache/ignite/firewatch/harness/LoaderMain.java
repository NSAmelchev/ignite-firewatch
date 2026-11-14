package org.apache.ignite.firewatch.harness;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Env;
import org.apache.ignite.firewatch.harness.api.Scale;
import org.apache.ignite.firewatch.harness.api.Scenario;
import org.apache.ignite.firewatch.harness.api.Session;

/**
 * Entry point of the loader JVM: connects as a thick client node (joins the topology) or a thin client, runs the
 * scenario's {@link Scenario#setup} (timed as 'preload'), then the {@link Workload}, and writes {@code loader.json}.
 * Args: {@code --scenario ID --out DIR --work-dir DIR --host ADDR --discovery A,B --thin A,B}
 * {@code [--warmup-sec N] [--measure-sec N]}.
 */
public final class LoaderMain {
    /**
     * Runs the loader.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        int rc = 1;

        try {
            rc = run(args);
        }
        catch (Throwable t) {
            t.printStackTrace(System.out);
        }

        System.exit(rc);
    }

    /** */
    private static int run(String[] args) throws Exception {
        Cli cli = Cli.parse(args, 0);
        String plugin = cli.get("plugin", null);
        Catalog.Entry entry = Catalog.get(cli.req("scenario"), plugin == null ? null : Path.of(plugin));
        Bench b = entry.bench;
        Scenario sc = entry.newInstance();
        Path out = Path.of(cli.req("out"));
        Path workDir = Path.of(cli.get("work-dir", out.resolve("work-loader").toString()));
        Files.createDirectories(out);
        Env env = new Env(workDir);

        LoaderResult result = new LoaderResult();
        Session ses;
        AutoCloseable closer;
        long t0 = System.nanoTime();

        if (b.loader().client() == Client.THIN) {
            ClientConfiguration cfg = IgniteConfigs.thinClient(List.of(cli.req("thin").split(",")));
            sc.configureThinClient(cfg, env);
            IgniteClient client = Ignition.startClient(cfg);
            closer = client;
            result.igniteVersion = versionFromProperties();
            ses = new Session(null, client, env);
        }
        else {
            IgniteConfiguration cfg = IgniteConfigs.loader(cli.get("host", "127.0.0.1"),
                List.of(cli.req("discovery").split(",")), workDir);
            sc.configureClient(cfg, env);
            Ignite ignite = Ignition.start(cfg);
            closer = ignite;
            long deadline = System.nanoTime() + b.startTimeoutSec() * 1_000_000_000L;

            while (ignite.cluster().forServers().nodes().size() < b.servers().count()) {
                if (System.nanoTime() > deadline) {
                    System.out.println("ERROR: only " + ignite.cluster().forServers().nodes().size() + " of "
                        + b.servers().count() + " servers joined within " + b.startTimeoutSec() + " s");

                    return 3;
                }

                Thread.sleep(100);
            }

            result.igniteVersion = String.valueOf(ignite.version());
            ses = new Session(ignite, null, env);
        }

        result.connectMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println((ses.thin() ? "thin client" : "client node") + " connected in " + result.connectMs
            + " ms, ignite " + result.igniteVersion);

        t0 = System.nanoTime();
        sc.setup(ses);
        result.preloadMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("setup (caches, preload) done in " + result.preloadMs + " ms");

        int threads = Scale.current().threads(b.loader().threads());
        LoaderResult measured = new Workload(sc, ses, threads, b.loader().rateOpsPerSec(),
            cli.getInt("warmup-sec", b.warmupSec()),
            cli.getInt("measure-sec", b.measureSec()), System.out).run();
        measured.igniteVersion = result.igniteVersion;
        measured.connectMs = result.connectMs;
        measured.preloadMs = result.preloadMs;
        Json.write(out.resolve("loader.json"), measured);
        System.out.printf("LOADER_DONE ops/s=%.0f p50=%.0fus p99=%.0fus errors=%d%n",
            measured.throughput.mean, measured.latencyUs.p50, measured.latencyUs.p99, measured.errors);

        closer.close();

        return 0;
    }

    /**
     * Version of the Ignite jars on the classpath in the same form {@code Ignite.version()} prints
     * ({@code 2.19.0-SNAPSHOT#20261026-sha1:6fb70e4c}), from {@code ignite.properties}; the thin client has no version
     * API.
     */
    static String versionFromProperties() {
        try (InputStream in = LoaderMain.class.getResourceAsStream("/ignite.properties")) {
            if (in == null)
                return "unknown";

            Properties p = new Properties();
            p.load(in);
            String ver = p.getProperty("ignite.version", "unknown");
            String build = p.getProperty("ignite.build", "0");
            String rev = p.getProperty("ignite.rev.hash", "");
            String day = new SimpleDateFormat("yyyyMMdd").format(new Date(Long.parseLong(build.trim()) * 1000));

            return ver + "#" + day + "-sha1:" + (rev.length() > 8 ? rev.substring(0, 8) : rev);
        }
        catch (Exception e) {
            return "unknown";
        }
    }
}
