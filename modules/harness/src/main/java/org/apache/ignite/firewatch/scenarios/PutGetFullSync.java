package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;
import org.apache.ignite.firewatch.harness.api.Servers;

/**
 * The main tracker's load on two servers with a backup: every put goes primary → backup and waits for both
 * (FULL_SYNC), so communication messages and their serialization are on the hot path, unlike on one server.
 * Nodes share the machine: the network is loopback, the cost measured is the code.
 */
@Bench(id = "putget-thin-t16-2srv-fullsync", version = 1,
    description = "2 in-memory servers, ATOMIC, 1 backup, FULL_SYNC: thin client, 16 threads, 100k keys x 100 B, 70% "
        + "get",
    servers = @Servers(count = 2, heapMb = 384),
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192))
public class PutGetFullSync extends PutGetInMemory {
    /** {@inheritDoc} */
    @Override protected long regionMb() {
        return 128;
    }

    /** {@inheritDoc} */
    @Override protected CacheConfiguration<Integer, byte[]> cacheConfiguration() {
        return super.cacheConfiguration()
            .setBackups(1)
            .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
    }
}
