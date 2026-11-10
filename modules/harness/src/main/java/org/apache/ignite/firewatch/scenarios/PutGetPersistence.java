package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Env;
import org.apache.ignite.firewatch.harness.api.Loader;
import org.apache.ignite.firewatch.harness.api.Scale;
import org.apache.ignite.firewatch.harness.api.Session;

/**
 * The main tracker's load on a persistent cluster: every put goes through the WAL and checkpoints write pages to disk.
 * WAL mode, segments and checkpointing are Ignite's defaults. Each phase starts from an empty work directory,
 * so the cluster is activated in setup.
 */
@Bench(id = "putget-thin-t16-pds", version = 1,
    description = "Main tracker with native persistence: thin client, 16 threads, 1 server, WAL and checkpoints at "
        + "defaults",
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192),
    setupTimeoutSec = 180)
public class PutGetPersistence extends PutGet {
    /** {@inheritDoc} */
    @Override public void configureServer(IgniteConfiguration cfg, Env env) {
        long region = Scale.current().regionMb(256, false);
        cfg.setDataStorageConfiguration(new DataStorageConfiguration()
            .setDefaultDataRegionConfiguration(new DataRegionConfiguration()
                .setInitialSize(region << 20)
                .setMaxSize(region << 20)
                .setPersistenceEnabled(true)));
    }

    /** {@inheritDoc} */
    @Override public void setup(Session s) throws Exception {
        s.activate();
        super.setup(s);
    }
}
