package org.apache.ignite.firewatch.harness;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

/** JVM-related constants shared by the launcher and the child processes. */
final class Jvm {
    /** System property that marks every JVM of a run, so stray processes can be found and killed. */
    static final String RUN_MARKER = "ignite.firewatch.run";

    /** {@code --add-opens} set Apache Ignite needs on JDK 17+ (from {@code bin/include/jvmdefaults.sh}). */
    static final List<String> ADD_OPENS = List.of(
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
        "--add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED",
        "--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
        "--add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED",
        "--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.math=ALL-UNNAMED",
        "--add-opens=java.sql/java.sql=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.time=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.logging/java.util.logging=ALL-UNNAMED",
        "--add-opens=java.management/sun.management=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED");

    /** System properties every child JVM of a run gets. */
    static List<String> systemProps(String runId) {
        return List.of(
            "-D" + RUN_MARKER + "=" + runId,
            "-DIGNITE_QUIET=true",
            "-DIGNITE_UPDATE_NOTIFIER=false",
            "-DIGNITE_PERFORMANCE_SUGGESTIONS_DISABLED=true",
            "-Djava.net.preferIPv4Stack=true");
    }

    /** */
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** The jar (or classes directory) this class was loaded from. */
    static Path ownLocation() {
        try {
            return Path.of(Jvm.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        }
        catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** */
    static String harnessVersion() {
        String v = Jvm.class.getPackage().getImplementationVersion();

        return v == null ? "dev" : v;
    }

    /**
     * Classpath for child JVMs: this harness, the jar of the plugin (null for a scenario of the harness) and every jar of
     * the build under test. The plugin comes before the build: what it bundles wins over a library of the build.
     */
    static String classpath(Path harness, Path pluginJar, Path libsDir) {
        return harness.toAbsolutePath() + File.pathSeparator
            + (pluginJar == null ? "" : pluginJar.toAbsolutePath() + File.pathSeparator)
            + libsDir.toAbsolutePath().resolve("*");
    }

    /** */
    private Jvm() {
    }
}
