package org.apache.ignite.firewatch.harness.api;

import org.apache.ignite.Ignite;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.cluster.ClusterState;

/** The loader's connection to the cluster: a thick client node or a thin client, as {@link Loader#client()} says. */
public final class Session {
    /** */
    private final Ignite ignite;

    /** */
    private final IgniteClient client;

    /** */
    private final Env env;

    /**
     * Creates the session.
     *
     * @param ignite Client node, or {@code null} for a thin client.
     * @param client Thin client, or {@code null} for a client node.
     * @param env Environment of the node.
     */
    public Session(Ignite ignite, IgniteClient client, Env env) {
        this.ignite = ignite;
        this.client = client;
        this.env = env;
    }

    /**
     * Checks the client kind.
     *
     * @return {@code True} if the client is thin.
     */
    public boolean thin() {
        return client != null;
    }

    /**
     * The client node; null for a thin client.
     *
     * @return Client node.
     */
    public Ignite ignite() {
        return ignite;
    }

    /**
     * The thin client; null for a client node.
     *
     * @return Thin client.
     */
    public IgniteClient client() {
        return client;
    }

    /**
     * Returns the environment of the node.
     *
     * @return Environment.
     */
    public Env env() {
        return env;
    }

    /**
     * Activates the cluster: needed once per phase when persistence is on (every phase starts from an empty work
     * directory).
     */
    public void activate() {
        if (client != null)
            client.cluster().state(ClusterState.ACTIVE);
        else
            ignite.cluster().state(ClusterState.ACTIVE);
    }
}
