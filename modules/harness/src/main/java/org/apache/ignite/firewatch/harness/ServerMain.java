package org.apache.ignite.firewatch.harness;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.firewatch.harness.api.Env;
import org.apache.ignite.firewatch.harness.api.Scenario;

/**
 * Entry point of a server JVM: starts one Ignite server node of the scenario and lives until SIGTERM, when Ignite's
 * shutdown hook stops the node and JFR dumps its recording.
 * Args: {@code --scenario ID --node-index N --host ADDR --discovery A,B --work-dir DIR}.
 */
public final class ServerMain {
    /**
     * Runs the server node.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args, 0);
        Scenario sc = Catalog.get(cli.req("scenario")).newInstance();
        int idx = cli.getInt("node-index", 0);
        Path workDir = Path.of(cli.get("work-dir", "work-node-" + idx));
        IgniteConfiguration cfg = IgniteConfigs.server(idx, cli.get("host", "127.0.0.1"),
            List.of(cli.req("discovery").split(",")), workDir);
        sc.configureServer(cfg, new Env(workDir));

        long t0 = System.nanoTime();
        Ignite ignite = Ignition.start(cfg);
        long startMs = (System.nanoTime() - t0) / 1_000_000;

        // startMs = Ignition.start() only (no JVM boot); version= must stay last, it is parsed to the end of the line
        System.out.println("HARNESS_READY node=" + idx + " startMs=" + startMs + " version=" + ignite.version());
        System.out.flush();

        new CountDownLatch(1).await();
    }
}
