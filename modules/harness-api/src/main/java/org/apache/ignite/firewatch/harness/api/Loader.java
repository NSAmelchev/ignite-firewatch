package org.apache.ignite.firewatch.harness.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** The load generator: client JVM(s) running {@link Scenario#op} in {@code threads} threads each. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Loader {
    /**
     * Kind of client the loader uses.
     *
     * @return Client kind.
     */
    Client client();

    /**
     * Load threads per client JVM.
     *
     * @return Number of threads.
     */
    int threads();

    /**
     * Client JVMs; one today, more once results of several loaders are merged.
     *
     * @return Number of client JVMs.
     */
    int count() default 1;

    /**
     * Heap size of a client JVM.
     *
     * @return Heap size in megabytes.
     */
    int heapMb() default 256;

    /**
     * Options of a client JVM.
     *
     * @return JVM options.
     */
    String[] jvmOpts() default {"-XX:+UseG1GC"};

    /**
     * 0: closed loop, each thread sends the next operation right after the previous one; otherwise an open loop at this
     * total rate.
     *
     * @return Total rate in operations per second, 0 for a closed loop.
     */
    int rateOpsPerSec() default 0;

    /**
     * Host group of the stand to run on; only "local" exists today.
     *
     * @return Host group name.
     */
    String group() default "local";
}
