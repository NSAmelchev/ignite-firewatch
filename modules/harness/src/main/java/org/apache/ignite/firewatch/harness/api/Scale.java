package org.apache.ignite.firewatch.harness.api;

/**
 * The sizes of a stand. {@link #NONE} leaves a scenario with the numbers it states, so a stand that never set them
 * keeps its
 * history comparable. A stand sets the memory of a server node, how full the data region gets and a factor for the load
 * threads:
 * the memory splits into a reserve, the heap and the region, and the number of keys and rows follows from the region.
 * Plain data, like {@link Bench}: the launcher reads it without Ignite on the classpath. It stays the same for the
 * whole life
 * of a stand, so it is not part of the scenario hash.
 */
public final class Scale {
    /** System property that hands the sizes to the JVMs of a phase. */
    public static final String PROPERTY = "ignite.firewatch.scale";

    /** */
    public static final Scale NONE = new Scale(0, 0, 1, 1);

    /** */
    private static final double HEAP_SHARE = 0.35;

    /** SQL keeps plans and rows on the heap, put/get keeps almost nothing there. */
    private static final double SQL_HEAP_SHARE = 0.5;

    /** */
    private static final int MIN_RESERVE_MB = 256;

    /** Below this a node spends its time collecting garbage instead of what is being measured. */
    private static final int MIN_HEAP_MB = 256;

    /** The node and the fill the setup timeouts of the scenarios were chosen for. */
    private static final int BASE_MEMORY_MB = 768;

    /** */
    private static final int BASE_FILL_PCT = 10;

    /** */
    public final int nodeMemoryMb;

    /** */
    public final int fillPct;

    /** */
    public final double threadsFactor;

    /** */
    public final int servers;

    /**
     * Creates the sizes of a stand.
     *
     * @param nodeMemoryMb Memory of a server node, MB; 0 leaves the scenario numbers.
     * @param fillPct How full the data region gets, percent.
     * @param threadsFactor Factor for the number of load threads.
     * @param servers Number of server nodes.
     */
    public Scale(int nodeMemoryMb, int fillPct, double threadsFactor, int servers) {
        this.nodeMemoryMb = nodeMemoryMb;
        this.fillPct = fillPct;
        this.threadsFactor = threadsFactor;
        this.servers = servers;
    }

    /**
     * {@code memory,fill,threads,servers}; null or empty is {@link #NONE}.
     *
     * @param s Text form.
     * @return Parsed sizes.
     */
    public static Scale parse(String s) {
        if (s == null || s.isBlank())
            return NONE;

        String[] p = s.split(",");

        if (p.length != 4)
            throw new IllegalArgumentException("bad scale: " + s);

        return new Scale(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Double.parseDouble(p[2]),
            Integer.parseInt(p[3]));
    }

    /**
     * The sizes of this JVM.
     *
     * @return Sizes from the {@link #PROPERTY} system property.
     */
    public static Scale current() {
        return parse(System.getProperty(PROPERTY));
    }

    /**
     * Formats the sizes as {@code memory,fill,threads,servers}.
     *
     * @return Text form, the one {@link #parse} reads.
     */
    public String format() {
        return nodeMemoryMb + "," + fillPct + "," + threadsFactor + "," + servers;
    }

    /**
     * Checks whether the sizes leave the scenario numbers as they are.
     *
     * @return {@code True} if no memory is set and the threads factor is 1.
     */
    public boolean isNone() {
        return nodeMemoryMb == 0 && threadsFactor == 1;
    }

    /**
     * Copies the sizes with another number of servers.
     *
     * @param count Number of servers.
     * @return New sizes.
     */
    public Scale forServers(int count) {
        return new Scale(nodeMemoryMb, fillPct, threadsFactor, count);
    }

    /**
     * Copies the sizes with another threads factor.
     *
     * @param factor Threads factor.
     * @return New sizes.
     */
    public Scale withThreadsFactor(double factor) {
        return new Scale(nodeMemoryMb, fillPct, factor, servers);
    }

    /** */
    private boolean sized() {
        return nodeMemoryMb > 0;
    }

    /** */
    private int usableMb() {
        return nodeMemoryMb - Math.max(MIN_RESERVE_MB, nodeMemoryMb / 10);
    }

    /**
     * Returns the heap size of a node.
     *
     * @param base Heap the scenario states, MB.
     * @param sql {@code True} for an SQL scenario.
     * @return Heap size, MB.
     */
    public int heapMb(int base, boolean sql) {
        return sized() ? Math.max(MIN_HEAP_MB, (int)(usableMb() * (sql ? SQL_HEAP_SHARE : HEAP_SHARE))) : base;
    }

    /**
     * Returns the size of the data region of a node.
     *
     * @param base Size the scenario states, MB.
     * @param sql {@code True} for an SQL scenario.
     * @return Region size, MB.
     */
    public long regionMb(long base, boolean sql) {
        return sized() ? usableMb() - heapMb(0, sql) : base;
    }

    /**
     * Keys or rows that fill the regions of all servers as asked, each stored {@code copies} times.
     *
     * @param base Number of entries the scenario states.
     * @param bytesPerEntry Size of one entry, bytes.
     * @param copies Number of copies of an entry in the cluster.
     * @param sql {@code True} for an SQL scenario.
     * @return Number of entries.
     */
    public int entries(int base, int bytesPerEntry, int copies, boolean sql) {
        if (!sized())
            return base;

        long bytes = regionMb(0, sql) * (1L << 20) * servers * fillPct / 100;

        return (int)Math.min(Integer.MAX_VALUE, bytes / (bytesPerEntry * (long)copies));
    }

    /**
     * Scales the number of load threads.
     *
     * @param base Number of threads the scenario states.
     * @return Number of threads, at least 1.
     */
    public int threads(int base) {
        return Math.max(1, (int)Math.round(base * threadsFactor));
    }

    /**
     * How much longer the preload may take than the scenario's own timeout allows.
     *
     * @return Multiplier of the setup timeout, at least 1.
     */
    public int setupTimeoutFactor() {
        return sized()
            ? Math.max(1, (nodeMemoryMb * fillPct + BASE_MEMORY_MB * BASE_FILL_PCT - 1)
                / (BASE_MEMORY_MB * BASE_FILL_PCT))
            : 1;
    }
}
