package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;
import org.apache.ignite.firewatch.harness.api.Servers;

/**
 * The transaction of {@link TxThin} on three servers with a backup. On two servers every node owns every key (as
 * primary or backup), so the node the client started the transaction on always holds the data; on three it often
 * holds neither key, and the two primaries with their backups make a commit across all three nodes.
 */
@Bench(id = "tx-thin-t16-3srv", version = 1,
    description = "3 in-memory servers, TRANSACTIONAL, 1 backup, FULL_SYNC: thin client, 16 threads, "
        + "PESSIMISTIC REPEATABLE_READ tx of get+put on 2 keys",
    servers = @Servers(count = 3, heapMb = 512),
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192))
public class TxThin3Srv extends TxThin {
    /** {@inheritDoc} */
    @Override protected long regionMb() {
        return 256;
    }
}
