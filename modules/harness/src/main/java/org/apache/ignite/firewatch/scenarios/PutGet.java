package org.apache.ignite.firewatch.scenarios;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.ClientCacheConfiguration;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.firewatch.harness.api.Scale;
import org.apache.ignite.firewatch.harness.api.Scenario;
import org.apache.ignite.firewatch.harness.api.Session;

/**
 * Key-value put/get over one cache: {@link #KEYS} Integer keys with {@link #VALUE_BYTES}-byte values, each operation
 * takes a uniformly random key and does get with probability {@link #GET_RATIO}, otherwise put of the same value.
 * Subclasses change the cluster (persistence, SSL) through the configure methods. A stand with its own sizes
 * ({@link Scale}) gets as many keys as fill its data regions.
 */
public abstract class PutGet extends Scenario {
    /** */
    static final int KEYS = 100_000;

    /** */
    static final int VALUE_BYTES = 100;

    /** */
    static final double GET_RATIO = 0.7;

    /** A key with its value and overhead in the data region. */
    private static final int ENTRY_BYTES = 250;

    /** */
    protected final byte[] val = new byte[VALUE_BYTES];

    /** */
    protected int keys = KEYS;

    /** */
    private Kv cache;

    /** Everything else is Ignite's default: PARTITIONED, ATOMIC, no backups. */
    protected CacheConfiguration<Integer, byte[]> cacheConfiguration() {
        return new CacheConfiguration<>("putget");
    }

    /** {@inheritDoc} */
    @Override public void setup(Session s) throws Exception {
        new Random(42).nextBytes(val);
        keys = Scale.current().entries(KEYS, ENTRY_BYTES, 1 + cacheConfiguration().getBackups(), false);
        cache = s.thin() ? Kv.thin(s.client(), cacheConfiguration()) : Kv.thick(s.ignite(), cacheConfiguration());
        cache.preload(keys, val); // thin client: putAll in batches of 1000; client node: data streamer
    }

    /** {@inheritDoc} */
    @Override public void op(Session s, ThreadLocalRandom rnd) {
        int key = rnd.nextInt(keys);

        if (rnd.nextDouble() < GET_RATIO)
            cache.get(key);
        else
            cache.put(key, val);
    }

    /** The cache over a thin client or a client node: the same three operations. */
    interface Kv {
        /** */
        byte[] get(int key);

        /** */
        void put(int key, byte[] val);

        /** */
        void preload(int keys, byte[] val) throws Exception;

        /** */
        static Kv thin(IgniteClient client, CacheConfiguration<Integer, byte[]> ccfg) {
            // the same cache for a thin client: only what is set explicitly, the rest stays at Ignite's defaults
            ClientCacheConfiguration cfg = new ClientCacheConfiguration().setName(ccfg.getName())
                .setBackups(ccfg.getBackups());

            if (ccfg.getCacheMode() != null)
                cfg.setCacheMode(ccfg.getCacheMode());

            if (ccfg.getAtomicityMode() != null)
                cfg.setAtomicityMode(ccfg.getAtomicityMode());

            if (ccfg.getWriteSynchronizationMode() != null)
                cfg.setWriteSynchronizationMode(ccfg.getWriteSynchronizationMode());

            ClientCache<Integer, byte[]> c = client.getOrCreateCache(cfg);

            return new Kv() {
                @Override public byte[] get(int key) {
                    return c.get(key);
                }

                @Override public void put(int key, byte[] val) {
                    c.put(key, val);
                }

                @Override public void preload(int keys, byte[] val) {
                    Map<Integer, byte[]> batch = new HashMap<>();

                    for (int k = 0; k < keys; k++) {
                        batch.put(k, val);

                        if (batch.size() == 1000 || k == keys - 1) {
                            c.putAll(batch);
                            batch.clear();
                        }
                    }
                }
            };
        }

        /** */
        static Kv thick(Ignite ignite, CacheConfiguration<Integer, byte[]> ccfg) {
            IgniteCache<Integer, byte[]> c = ignite.getOrCreateCache(ccfg);

            return new Kv() {
                @Override public byte[] get(int key) {
                    return c.get(key);
                }

                @Override public void put(int key, byte[] val) {
                    c.put(key, val);
                }

                @Override public void preload(int keys, byte[] val) {
                    try (IgniteDataStreamer<Integer, byte[]> streamer = ignite.dataStreamer(ccfg.getName())) {
                        streamer.allowOverwrite(false);

                        for (int k = 0; k < keys; k++)
                            streamer.addData(k, val);
                    }
                }
            };
        }
    }
}
