package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;

/** {@link SqlPoint} on the Calcite engine. */
@Bench(id = "sql-calcite-point", version = 1,
    description = "SQL on Calcite: thin client, 16 threads, 1 server, 100k rows, 70% SELECT by primary key, 30% UPDATE "
        + "by primary key",
    modules = {"indexing", "calcite"},
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192),
    setupTimeoutSec = 300)
public class SqlPointCalcite extends SqlPoint {
    /** {@inheritDoc} */
    @Override protected Engine engine() {
        return Engine.CALCITE;
    }
}
