package org.apache.ignite.firewatch.harness.api;

/** How the loader talks to the cluster. */
public enum Client {
    /** A client node in the topology ({@code Ignition.start} with client mode). */
    THICK,
    /** The binary protocol via the client connector ({@code Ignition.startClient}). */
    THIN
}
