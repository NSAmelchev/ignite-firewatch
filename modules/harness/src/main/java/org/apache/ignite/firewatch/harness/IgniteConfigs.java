package org.apache.ignite.firewatch.harness;

import java.nio.file.Path;
import java.util.List;

import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.configuration.ClientConnectorConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;

/**
 * The part of every node configuration the harness owns: names, discovery, the client connector, the work
 * directory. Scenarios then change what they need ({@code Scenario.configureServer} etc.). Child JVMs only.
 */
final class IgniteConfigs {
    /**
     * Server {@code idx}: discovery on {@code host:DISCOVERY_PORT + idx}, client connector on {@code THIN_PORT + idx}.
     */
    static IgniteConfiguration server(int idx, String host, List<String> discovery, Path workDir) {
        IgniteConfiguration cfg = base("server-" + idx, false, host, discovery, workDir);
        TcpDiscoverySpi disco = (TcpDiscoverySpi)cfg.getDiscoverySpi();
        disco.setLocalPort(Placement.DISCOVERY_PORT + idx);
        disco.setLocalPortRange(0);
        cfg.setClientConnectorConfiguration(new ClientConnectorConfiguration()
            .setHost(host)
            .setPort(Placement.THIN_PORT + idx)
            .setPortRange(0));

        return cfg;
    }

    /** */
    static IgniteConfiguration loader(String host, List<String> discovery, Path workDir) {
        return base("loader", true, host, discovery, workDir);
    }

    /** */
    static ClientConfiguration thinClient(List<String> addresses) {
        return new ClientConfiguration()
            .setAddresses(addresses.toArray(new String[0]))
            .setPartitionAwarenessEnabled(true)
            .setTimeout(60_000);
    }

    /** */
    private static IgniteConfiguration base(String name, boolean client, String host, List<String> discovery,
        Path workDir) {
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(discovery);

        return new IgniteConfiguration()
            .setIgniteInstanceName(name)
            .setConsistentId(name)
            .setClientMode(client)
            .setLocalHost(host)
            .setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(ipFinder))
            .setMetricsLogFrequency(0)
            .setPeerClassLoadingEnabled(false)
            .setWorkDirectory(workDir.toAbsolutePath().toString());
    }

    /** */
    private IgniteConfigs() {
    }
}
