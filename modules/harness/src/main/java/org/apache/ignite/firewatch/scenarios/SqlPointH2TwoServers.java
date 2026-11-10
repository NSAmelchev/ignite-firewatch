package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;
import org.apache.ignite.firewatch.harness.api.Servers;

/**
 * {@link SqlPoint} on the H2 engine and two servers: the same schema and data without backups, so against the
 * one-server
 * twin only the distributed execution differs (partition pruning, map/reduce or exchanges between nodes).
 */
@Bench(id = "sql-h2-point-2srv", version = 1,
    description = "SQL on H2, 2 servers: thin client, 16 threads, 100k rows, 70% SELECT by primary key, 30% UPDATE by "
        + "primary key",
    modules = {"indexing", "calcite"},
    servers = @Servers(count = 2),
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192),
    setupTimeoutSec = 300)
public class SqlPointH2TwoServers extends SqlPoint {
    /** {@inheritDoc} */
    @Override protected Engine engine() {
        return Engine.H2;
    }
}
