package org.apache.ignite.firewatch.harness.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Scenario} class and says what it runs on: the harness finds every annotated class in
 * {@code org.apache.ignite.firewatch.scenarios} and in the jar of a plugin, so adding a scenario is adding a file and
 * removing one is deleting it. Everything here is plain data: the controller reads it without Ignite on the classpath.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Bench {
    /**
     * Stable identity: jobs, history and the scenario hash refer to it, so it never changes; the class can be renamed
     * freely, and so can the {@link #title()} the UI shows.
     *
     * @return Scenario id.
     */
    String id();

    /**
     * Name shown in the UI instead of the id; change it at will, history stays with the id. Empty: the id.
     *
     * @return Display title.
     */
    String title() default "";

    /**
     * Raise when what the scenario measures changes: trends, noise floors and pooled repeats start a new series.
     *
     * @return Scenario version.
     */
    int version();

    /**
     * Short text on what the scenario measures, shown in the UI.
     *
     * @return Scenario description.
     */
    String description();

    /**
     * Server nodes of the cluster.
     *
     * @return Server nodes settings.
     */
    Servers servers() default @Servers;

    /**
     * Load generator that runs {@link Scenario#op}.
     *
     * @return Loader settings.
     */
    Loader loader();

    /**
     * Ignite modules the nodes need besides ignite-core, by directory under {@code modules/} ("indexing", "calcite"):
     * the controller builds them into a separate classpath of the commit, so other scenarios keep a core-only one.
     *
     * @return Module directory names.
     */
    String[] modules() default {};

    /**
     * Warmup time before the measurement; its results are not recorded.
     *
     * @return Warmup duration, seconds.
     */
    int warmupSec() default 30;

    /**
     * Duration of the measurement window.
     *
     * @return Measurement duration, seconds.
     */
    int measureSec() default 90;

    /**
     * Limit for the servers to start and the loader to connect.
     *
     * @return Timeout, seconds.
     */
    int startTimeoutSec() default 120;

    /**
     * Limit for {@link Scenario#setup} (cache creation, activation, preload).
     *
     * @return Timeout, seconds.
     */
    int setupTimeoutSec() default 120;
}
