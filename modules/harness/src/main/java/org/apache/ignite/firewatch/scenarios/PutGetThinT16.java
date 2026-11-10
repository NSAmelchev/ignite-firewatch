package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;

/** Main tracker: thin client with 16 threads, enough to keep the single server busy on a 4-vCPU stand. */
@Bench(id = "putget-thin-t16", version = 1,
    description = "Main tracker: thin client, 16 threads, 1 in-memory server, 100k keys x 100 B, 70% get, closed loop",
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192))
public class PutGetThinT16 extends PutGetInMemory {
}
