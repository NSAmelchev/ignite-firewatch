package org.apache.ignite.firewatch.scenarios;

import org.apache.ignite.client.SslMode;
import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Env;
import org.apache.ignite.firewatch.harness.api.Loader;
import org.apache.ignite.ssl.SslContextFactory;

/**
 * The main tracker's load over TLS: SSL on discovery and communication and on the client connector, so every thin
 * client request is encrypted. Self-signed test certificate shipped in the harness jar (ssl/node.jks, ssl/trust.jks).
 */
@Bench(id = "putget-thin-t16-ssl", version = 1,
    description = "Main tracker over TLS: thin client, 16 threads, 1 in-memory server, SSL on connector and discovery",
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192))
public class PutGetSsl extends PutGetInMemory {
    /** */
    private static final char[] PASSWORD = "benchpass".toCharArray();

    /** {@inheritDoc} */
    @Override public void configureServer(IgniteConfiguration cfg, Env env) throws Exception {
        super.configureServer(cfg, env);
        SslContextFactory ssl = new SslContextFactory();
        ssl.setKeyStoreFilePath(env.resource("ssl/node.jks"));
        ssl.setKeyStorePassword(PASSWORD);
        ssl.setTrustStoreFilePath(env.resource("ssl/trust.jks"));
        ssl.setTrustStorePassword(PASSWORD);
        cfg.setSslContextFactory(ssl);
        cfg.getClientConnectorConfiguration()
            .setSslEnabled(true)
            .setUseIgniteSslContextFactory(true);
    }

    /** {@inheritDoc} */
    @Override public void configureThinClient(ClientConfiguration cfg, Env env) throws Exception {
        cfg.setSslMode(SslMode.REQUIRED)
            .setSslClientCertificateKeyStorePath(env.resource("ssl/node.jks"))
            .setSslClientCertificateKeyStorePassword("benchpass")
            .setSslTrustCertificateKeyStorePath(env.resource("ssl/trust.jks"))
            .setSslTrustCertificateKeyStorePassword("benchpass");
    }
}
