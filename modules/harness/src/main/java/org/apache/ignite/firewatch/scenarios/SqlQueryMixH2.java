package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;

/** {@link SqlQueryMix} on the H2 engine. */
@Bench(id = "sql-h2-query", version = 1,
    description = "SQL on H2: thin client, 16 threads, 1 server, 100k persons: index range, GROUP BY, colocated join, "
        + "a third each",
    modules = {"indexing", "calcite"},
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192),
    setupTimeoutSec = 300)
public class SqlQueryMixH2 extends SqlQueryMix {
    /** {@inheritDoc} */
    @Override protected Engine engine() {
        return Engine.H2;
    }
}
