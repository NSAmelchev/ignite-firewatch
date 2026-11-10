package org.apache.ignite.firewatch.harness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.ignite.firewatch.harness.api.Bench;

/**
 * Where every JVM of a phase runs and how they find each other. It is the same from run to run, so the layout adds no
 * noise:
 * on a remote stand server {@code i} goes to {@code servers[i % N]} in the order of the settings and the loader to the
 * first
 * loaders host. Each role also names a host group ({@code @Servers(group)}, {@code @Loader(group)}); only the group
 * "local",
 * all the stand has, exists so far. Server {@code i} listens for discovery on {@code DISCOVERY_PORT + i} and for thin
 * clients
 * on {@code THIN_PORT + i}.
 */
final class Placement {
    /** */
    static final String LOCAL = "local";

    /** */
    static final int DISCOVERY_PORT = 47500;

    /** */
    static final int THIN_PORT = 10800;

    /** */
    final List<Host> servers = new ArrayList<>();

    /** */
    final List<Host> loaders = new ArrayList<>();

    /** {@code stand}: the hosts of a remote stand, or null for this machine. */
    static Placement of(Bench b, StandConfig stand, String runId) {
        for (String g : List.of(b.servers().group(), b.loader().group()))
            if (!LOCAL.equals(g))
                throw new IllegalArgumentException("host group '" + g + "' is not part of this stand: only '" + LOCAL
                    + "' exists");

        if (b.loader().count() != 1)
            throw new IllegalArgumentException("@Loader(count = " + b.loader().count()
                + "): only one loader JVM is supported so far");

        Placement p = new Placement();

        if (stand == null) {
            for (int i = 0; i < b.servers().count(); i++)
                p.servers.add(Host.Local.INSTANCE);

            p.loaders.add(Host.Local.INSTANCE);

            return p;
        }

        List<Host> servers = hosts(stand, StandConfig.SERVERS, runId),
            loaders = hosts(stand, StandConfig.LOADERS, runId);

        for (int i = 0; i < b.servers().count(); i++)
            p.servers.add(servers.get(i % servers.size()));

        p.loaders.add(loaders.get(0));

        return p;
    }

    /** */
    private static List<Host> hosts(StandConfig stand, String role, String runId) {
        List<Host> out = new ArrayList<>();

        for (StandConfig.Host h : stand.withRole(role))
            out.add(new RemoteHost(stand, h, runId));

        if (out.isEmpty())
            throw new IllegalArgumentException("the stand has no " + role + " host");

        return out;
    }

    /** Every host of the phase once. */
    Set<Host> hosts() {
        Set<Host> all = new LinkedHashSet<>(servers);
        all.addAll(loaders);

        return all;
    }

    /** Discovery addresses of every server, as TcpDiscoveryVmIpFinder takes them. */
    String discoveryAddresses() {
        List<String> a = new ArrayList<>();

        for (int i = 0; i < servers.size(); i++)
            a.add(servers.get(i).address() + ":" + (DISCOVERY_PORT + i));

        return String.join(",", a);
    }

    /** Client connector addresses of every server, for the thin client. */
    String thinAddresses() {
        List<String> a = new ArrayList<>();

        for (int i = 0; i < servers.size(); i++)
            a.add(servers.get(i).address() + ":" + (THIN_PORT + i));

        return String.join(",", a);
    }
}
