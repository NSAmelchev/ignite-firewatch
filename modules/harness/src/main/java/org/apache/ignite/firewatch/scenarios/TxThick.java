package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;
import org.apache.ignite.firewatch.harness.api.Servers;

/**
 * {@link TxTwoKeys} from a client node on two servers: the client coordinates the transaction and owns no data, so
 * every lock, prepare and finish goes from it to the primaries (the near transaction path), unlike {@link TxThin}.
 */
@Bench(id = "tx-thick-t16-2srv", version = 1,
    description = "2 in-memory servers, TRANSACTIONAL, 1 backup, FULL_SYNC: client node, 16 threads, "
        + "PESSIMISTIC REPEATABLE_READ tx of get+put on 2 keys",
    servers = @Servers(count = 2, heapMb = 384),
    loader = @Loader(client = Client.THICK, threads = 16, heapMb = 384))
public class TxThick extends TxTwoKeys {
}
