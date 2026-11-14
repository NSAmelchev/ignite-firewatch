package org.apache.ignite.firewatch.harness.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Server nodes of a scenario: each one is a fresh JVM per phase. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Servers {
    /**
     * Number of server nodes.
     *
     * @return Number of server nodes.
     */
    int count() default 1;

    /**
     * Heap size of a server JVM.
     *
     * @return Heap size in megabytes.
     */
    int heapMb() default 512;

    /**
     * Options of a server JVM.
     *
     * @return JVM options.
     */
    String[] jvmOpts() default {"-XX:+UseG1GC", "-XX:+AlwaysPreTouch"};

    /**
     * Host group of the stand to run on; only "local" exists today (see {@code Placement}).
     *
     * @return Host group name.
     */
    String group() default "local";
}
