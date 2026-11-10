package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;

/** Thick-client baseline: the loader is a client node in the topology, 4 threads. */
@Bench(id = "putget-thick-t4", version = 1,
    description = "Thick-client baseline: 4 threads, 1 in-memory server, 100k keys x 100 B, 70% get, closed loop",
    loader = @Loader(client = Client.THICK, threads = 4))
public class PutGetThick extends PutGetInMemory {
}
