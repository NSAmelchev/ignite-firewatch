package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where the stand runs. One instance of the application is one stand: {@link #LOCAL}, the default, keeps every JVM on
 * this machine, {@link #REMOTE} puts them on the {@link Host hosts} over ssh. A finished phase freezes the choice,
 * because the results of different stands must not mix: another stand is another instance with its own data directory.
 * It lives in the settings table but outside {@link Settings#validate}: only {@link #save} writes it.
 */
public final class Stand {
    /** */
    public static final String LOCAL = "local";

    /** */
    public static final String REMOTE = "remote";

    /** */
    public static final String SERVERS = "servers";

    /** */
    public static final String LOADERS = "loaders";

    /** */
    static final String MODE = "stand.mode";

    /** */
    static final String HOSTS = "stand.hosts";

    /** */
    static final String SIZES = "stand.sizes";

    /** */
    static final String CONFIGURED = "stand.configured";

    /** */
    private static final String INSTANCE = "stand.instance";

    /** The last report of Check, the fingerprints it left and the last result of Measure load. */
    static final String CHECK = "stand.check";

    /** */
    static final String FINGERPRINTS = "stand.fingerprints";

    /** */
    static final String LOAD = "stand.load";

    /** */
    static final int MIN_NODE_MEMORY_MB = 768;

    /** */
    private static final int MAX_NODE_MEMORY_MB = 4_000_000;

    /** */
    private static final int MIN_FILL_PCT = 5;

    /** */
    private static final int MAX_FILL_PCT = 80;

    /** */
    private static final double MIN_THREADS_FACTOR = 0.25;

    /** */
    private static final double MAX_THREADS_FACTOR = 64;

    /** The node and the fill the setup timeouts of the scenarios were chosen for; the same as in the harness. */
    private static final int BASE_MEMORY_MB = 768;

    /** */
    private static final int BASE_FILL_PCT = 10;

    /** These go to an ssh command line: nothing here may look like an option or leave the word. */
    private static final String ADDRESS = "[0-9A-Za-z][0-9A-Za-z.:-]{0,252}";

    /** */
    private static final String USER = "[A-Za-z_][0-9A-Za-z._-]{0,31}";

    /** */
    private static final String ABSOLUTE_PATH = "/[0-9A-Za-z._+-][0-9A-Za-z._/+-]*";

    /** A machine of a remote stand. The key and known_hosts are files given to the controller, never kept here. */
    public static final class Host {
        /** */
        public String address;

        /** */
        public String user;

        /** */
        public int port = 22;

        /** {@link #SERVERS} or {@link #LOADERS}. */
        public String role;

        /** */
        public String javaHome;

        /** */
        public String workDir;

        /** Optional: the address of a separate network for the traffic between nodes. */
        public String dataAddress;
    }

    /**
     * The sizes of the scenarios on this stand: the memory of a server node, how full its data region gets and a factor
     * for the load threads. No memory means the numbers the scenarios state, which is what every stand had before there
     * were sizes.
     */
    public static final class Sizes {
        /** */
        public int nodeMemoryMb;

        /** */
        public int fillPct;

        /** */
        public double threadsFactor = 1;

        /** */
        boolean none() {
            return nodeMemoryMb == 0 && threadsFactor == 1;
        }

        /** The {@code --scale} of a phase; null when there is nothing to say. */
        String scale(double threads) {
            return nodeMemoryMb == 0 && threads == 1 ? null : nodeMemoryMb + "," + fillPct + "," + threads + ",1";
        }

        /** */
        int setupTimeoutFactor() {
            return nodeMemoryMb == 0 ? 1
                : Math.max(1, (nodeMemoryMb * fillPct + BASE_MEMORY_MB * BASE_FILL_PCT - 1)
                    / (BASE_MEMORY_MB * BASE_FILL_PCT));
        }
    }

    /** */
    public String mode = LOCAL;

    /** */
    public List<Host> hosts = new ArrayList<>();

    /** */
    public Sizes sizes = new Sizes();

    /**
     * Reads the stand choice from the settings.
     *
     * @param db Database.
     * @return The stand.
     */
    public static Stand read(Db db) {
        Stand s = new Stand();
        s.mode = db.setting(MODE, LOCAL);
        String hosts = db.setting(HOSTS, "");

        if (!hosts.isBlank())
            s.hosts = new ArrayList<>(List.of(Json.read(hosts, Host[].class)));

        String sizes = db.setting(SIZES, "");

        if (!sizes.isBlank())
            s.sizes = Json.read(sizes, Sizes.class);

        return s;
    }

    /** The first host Check has not seen, or null: a remote stand starts from what Check found. */
    String unchecked(Db db) {
        var prints = Json.tree(db.setting(FINGERPRINTS, "{}"));

        return hosts.stream().map(h -> h.address).filter(a -> !prints.has(a)).findFirst().orElse(null);
    }

    /**
     * A new stand: nothing was chosen, queued or polled. A stand that has worked is {@link #LOCAL} as it always was.
     *
     * @param db Database.
     * @return {@code True} if the stand has to be set up.
     */
    public static boolean needsSetup(Db db) {
        return !Config.isTrue(db.setting(CONFIGURED, "false")) && db.count("jobs") == 0
            && db.setting(Settings.WATCH_WATERMARK, "").isEmpty();
    }

    /**
     * At the start: a stand that has worked stays set up, whatever happens later to its jobs and its watermark.
     *
     * @param db Database.
     */
    public static void remember(Db db) {
        if (!needsSetup(db))
            db.setSetting(CONFIGURED, "true");
    }

    /**
     * Drawn once and kept with the data: see {@link Config#instance}.
     *
     * @param db Database.
     * @return Instance id.
     */
    public static String instance(Db db) {
        String id = db.setting(INSTANCE, "");

        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            db.setSetting(INSTANCE, id);
        }

        return id;
    }

    /**
     * Checks whether the stand is frozen, that is some phase has finished already.
     *
     * @param db Database.
     * @return {@code True} if the stand has finished runs.
     */
    public static boolean frozen(Db db) {
        return db.anyPhaseFinished();
    }

    /**
     * Checks and stores the choice, which also ends the setup of a new stand.
     *
     * @param db Database.
     */
    public void save(Db db) {
        if (frozen(db))
            throw new IllegalStateException("the stand has finished runs: another stand is another instance with its "
                + "own data directory");

        validate();
        db.setSetting(MODE, mode);
        db.setSetting(HOSTS, Json.write(REMOTE.equals(mode) ? hosts : List.of()));
        db.setSetting(SIZES, Json.write(sizes));
        db.setSetting(CONFIGURED, "true");
    }

    /** */
    private void validateSizes() {
        if (sizes.threadsFactor < MIN_THREADS_FACTOR || sizes.threadsFactor > MAX_THREADS_FACTOR)
            throw new IllegalArgumentException("the threads factor must be from " + MIN_THREADS_FACTOR + " to "
                + MAX_THREADS_FACTOR);

        if (sizes.nodeMemoryMb == 0)
            return;

        if (sizes.nodeMemoryMb < MIN_NODE_MEMORY_MB || sizes.nodeMemoryMb > MAX_NODE_MEMORY_MB)
            throw new IllegalArgumentException("the memory of a node must be from " + MIN_NODE_MEMORY_MB
                + " MB, or 0 for the sizes of the scenarios");

        if (sizes.fillPct < MIN_FILL_PCT || sizes.fillPct > MAX_FILL_PCT)
            throw new IllegalArgumentException("the fill of a data region must be from " + MIN_FILL_PCT + " to "
                + MAX_FILL_PCT + " %");
    }

    /** */
    void validate() {
        if (!LOCAL.equals(mode) && !REMOTE.equals(mode))
            throw new IllegalArgumentException("the stand must be " + LOCAL + " or " + REMOTE);

        validateSizes();

        if (LOCAL.equals(mode))
            return;

        Set<String> seen = new HashSet<>();
        boolean servers = false, loaders = false;

        for (Host h : hosts) {
            if (h.address == null || !h.address.matches(ADDRESS))
                throw new IllegalArgumentException("bad host address: " + h.address);

            String name = "host " + h.address;

            if (!seen.add(h.address))
                throw new IllegalArgumentException(name + " is listed twice");

            if (h.user == null || !h.user.matches(USER))
                throw new IllegalArgumentException(name + ": bad user");

            if (h.port < 1 || h.port > 65535)
                throw new IllegalArgumentException(name + ": bad ssh port");

            if (!SERVERS.equals(h.role) && !LOADERS.equals(h.role))
                throw new IllegalArgumentException(name + ": the role must be " + SERVERS + " or " + LOADERS);

            if (h.javaHome == null || !h.javaHome.matches(ABSOLUTE_PATH))
                throw new IllegalArgumentException(name + ": the JDK must be an absolute path");

            if (h.workDir == null || !h.workDir.matches(ABSOLUTE_PATH))
                throw new IllegalArgumentException(name + ": the work directory must be an absolute path");

            if (h.dataAddress != null && !h.dataAddress.isEmpty() && !h.dataAddress.matches(ADDRESS))
                throw new IllegalArgumentException(name + ": bad data address");

            servers |= SERVERS.equals(h.role);
            loaders |= LOADERS.equals(h.role);
        }

        if (!servers || !loaders)
            throw new IllegalArgumentException("a remote stand needs at least one " + SERVERS + " host and one "
                + LOADERS + " host");
    }
}
