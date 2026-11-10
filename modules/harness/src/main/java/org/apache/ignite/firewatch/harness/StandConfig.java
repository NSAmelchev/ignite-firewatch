package org.apache.ignite.firewatch.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The hosts of a remote stand, as the controller hands them to a phase ({@code --stand FILE}). */
final class StandConfig {
    /** */
    static final String SERVERS = "servers";

    /** */
    static final String LOADERS = "loaders";

    /** Host of the stand. */
    static final class Host {
        /** */
        public String address;

        /** */
        public String user;

        /** */
        public int port = 22;

        /** */
        public String role;

        /** */
        public String javaHome;

        /** */
        public String workDir;

        /** */
        public String dataAddress;
    }

    /** */
    public List<Host> hosts = new ArrayList<>();

    /** Files on this machine; empty = what ssh finds itself. */
    public String sshKey = "";

    /** */
    public String sshKnownHosts = "";

    /**
     * What every host looked like when it was checked, by address: runs on a changed host say nothing about the
     * history.
     */
    public Map<String, Map<String, String>> fingerprints = new HashMap<>();

    /** Starts the run ids of this stand: what a sweep looks for on the hosts. */
    public String runPrefix = "";

    /** */
    List<Host> withRole(String role) {
        return hosts.stream().filter(h -> role.equals(h.role)).toList();
    }
}
