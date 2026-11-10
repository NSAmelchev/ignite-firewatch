package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Loader;
import org.apache.ignite.firewatch.harness.api.Servers;

/**
 * {@link TxTwoKeys} from a thin client on two servers: the transaction runs on the server the client is connected to,
 * and with one backup that server owns every key (as primary or backup).
 */
@Bench(id = "tx-thin-t16-2srv", version = 1,
    description = "2 in-memory servers, TRANSACTIONAL, 1 backup, FULL_SYNC: thin client, 16 threads, "
        + "PESSIMISTIC REPEATABLE_READ tx of get+put on 2 keys",
    servers = @Servers(count = 2, heapMb = 384),
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192))
public class TxThin extends TxTwoKeys {
}
