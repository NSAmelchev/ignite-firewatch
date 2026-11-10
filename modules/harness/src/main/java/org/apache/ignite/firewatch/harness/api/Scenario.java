package org.apache.ignite.firewatch.harness.api;

import java.util.concurrent.ThreadLocalRandom;

import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;

/**
 * A benchmark scenario: one class per scenario in {@code org.apache.ignite.firewatch.scenarios}, annotated with
 * {@link Bench}.
 * The harness owns the rest: fresh JVMs per phase, discovery and ports, warmup and measure windows, latency
 * histograms, JFR and the result files. A scenario says only what is specific to it, as plain Ignite API:
 * the non-default node configuration, what to create and load, and one operation.
 * <p>
 * The harness is compiled against a release of ignite-core and runs with the jars of the commit under test,
 * so a scenario should use long-stable public API.
 */
public abstract class Scenario {
    /**
     * A server node; discovery, client connector, local host and work directory are already set. Change only what
     * differs from Ignite's defaults.
     *
     * @param cfg Server node configuration.
     * @param env Environment of the run.
     * @throws Exception If the configuration fails.
     */
    public void configureServer(IgniteConfiguration cfg, Env env) throws Exception {
    }

    /**
     * The loader's client node ({@link Client#THICK}); already in client mode with discovery set.
     *
     * @param cfg Client node configuration.
     * @param env Environment of the run.
     * @throws Exception If the configuration fails.
     */
    public void configureClient(IgniteConfiguration cfg, Env env) throws Exception {
    }

    /**
     * The loader's thin client ({@link Client#THIN}); addresses of every server are already set.
     *
     * @param cfg Thin client configuration.
     * @param env Environment of the run.
     * @throws Exception If the configuration fails.
     */
    public void configureThinClient(ClientConfiguration cfg, Env env) throws Exception {
    }

    /**
     * Once in the loader, before the load: create caches, activate a persistent cluster, preload. Its duration is the
     * 'preload' metric.
     *
     * @param s Session of the loader.
     * @throws Exception If the setup fails.
     */
    public abstract void setup(Session s) throws Exception;

    /**
     * One operation of one load thread; its latency is recorded. Called concurrently from every load thread.
     *
     * @param s Session of the loader.
     * @param rnd Random generator of the calling thread.
     * @throws Exception If the operation fails.
     */
    public abstract void op(Session s, ThreadLocalRandom rnd) throws Exception;
}
