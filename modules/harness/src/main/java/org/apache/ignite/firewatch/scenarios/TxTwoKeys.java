package org.apache.ignite.firewatch.scenarios;

import java.util.concurrent.ThreadLocalRandom;

import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.ClientTransaction;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.firewatch.harness.api.Session;
import org.apache.ignite.transactions.Transaction;

import static org.apache.ignite.transactions.TransactionConcurrency.PESSIMISTIC;
import static org.apache.ignite.transactions.TransactionIsolation.REPEATABLE_READ;

/**
 * Transactions over a backup: one operation = PESSIMISTIC REPEATABLE_READ transaction that reads two random keys and
 * writes both, then commits. The keys are locked in ascending order (no deadlocks), they mostly live on different
 * primaries, so lock, prepare and finish involve several nodes and the backups (FULL_SYNC). ops/s is transactions per
 * second. A thin client runs the transaction on the server it is connected to; a client node coordinates it itself.
 */
public abstract class TxTwoKeys extends PutGetInMemory {
    /** */
    private ClientCache<Integer, byte[]> thinCache;

    /** */
    private IgniteCache<Integer, byte[]> thickCache;

    /** {@inheritDoc} */
    @Override protected long regionMb() {
        return 128;
    }

    /** {@inheritDoc} */
    @Override protected CacheConfiguration<Integer, byte[]> cacheConfiguration() {
        return super.cacheConfiguration()
            .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            .setBackups(1)
            .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
    }

    /** {@inheritDoc} */
    @Override public void setup(Session s) throws Exception {
        super.setup(s); // creates the cache and preloads it
        String name = cacheConfiguration().getName();

        if (s.thin())
            thinCache = s.client().cache(name);
        else
            thickCache = s.ignite().cache(name);
    }

    /** {@inheritDoc} */
    @Override public void op(Session s, ThreadLocalRandom rnd) {
        int a = rnd.nextInt(keys), b = rnd.nextInt(keys - 1);

        if (b >= a)
            b++;

        int lo = Math.min(a, b), hi = Math.max(a, b);

        if (s.thin()) {
            try (ClientTransaction tx = s.client().transactions().txStart(PESSIMISTIC, REPEATABLE_READ)) {
                thinCache.get(lo);
                thinCache.get(hi);
                thinCache.put(lo, val);
                thinCache.put(hi, val);
                tx.commit();
            }
        }
        else {
            try (Transaction tx = s.ignite().transactions().txStart(PESSIMISTIC, REPEATABLE_READ)) {
                thickCache.get(lo);
                thickCache.get(hi);
                thickCache.put(lo, val);
                thickCache.put(hi, val);
                tx.commit();
            }
        }
    }
}
