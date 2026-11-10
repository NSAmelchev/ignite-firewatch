package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.firewatch.harness.api.Env;
import org.apache.ignite.firewatch.harness.api.Scale;

/** {@link PutGet} on in-memory servers with a fixed default data region (Ignite's default max is 20 % of RAM). */
public abstract class PutGetInMemory extends PutGet {
    /** Default data region of every server node, initial = max. */
    protected long regionMb() {
        return 256;
    }

    /** {@inheritDoc} */
    @Override public void configureServer(IgniteConfiguration cfg, Env env) throws Exception {
        long region = Scale.current().regionMb(regionMb(), false);
        cfg.setDataStorageConfiguration(new DataStorageConfiguration()
            .setDefaultDataRegionConfiguration(new DataRegionConfiguration()
                .setInitialSize(region << 20)
                .setMaxSize(region << 20)));
    }
}
