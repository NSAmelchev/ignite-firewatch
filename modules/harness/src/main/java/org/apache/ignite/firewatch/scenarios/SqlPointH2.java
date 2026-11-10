package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;

/** {@link SqlPoint} on the H2 engine. */
@Bench(id = "sql-h2-point", version = 1,
    description = "SQL on H2: thin client, 16 threads, 1 server, 100k rows, 70% SELECT by primary key, 30% UPDATE by "
        + "primary key",
    modules = {"indexing", "calcite"},
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192),
    setupTimeoutSec = 300)
public class SqlPointH2 extends SqlPoint {
    /** {@inheritDoc} */
    @Override protected Engine engine() {
        return Engine.H2;
    }
}
