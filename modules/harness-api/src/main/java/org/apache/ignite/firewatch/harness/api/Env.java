package org.apache.ignite.firewatch.harness.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Where a node or the loader runs: its work directory and files the scenario ships with the harness jar. */
public final class Env {
    /** */
    private final Path workDir;

    /**
     * Creates the environment.
     *
     * @param workDir Work directory of the node.
     */
    public Env(Path workDir) {
        this.workDir = workDir;
    }

    /**
     * The node's own directory inside the phase directory; Ignite's work directory (persistence, WAL) lives here.
     *
     * @return Work directory.
     */
    public Path workDir() {
        return workDir;
    }

    /**
     * A classpath resource of the harness jar (e.g. {@code ssl/server.jks}) copied into the work directory; its
     * absolute path.
     *
     * @param name Resource name.
     * @return Absolute path of the copy.
     */
    public String resource(String name) throws IOException {
        Path target = workDir.resolve("resources").resolve(name);

        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());

            try (InputStream in = Env.class.getResourceAsStream("/" + name)) {
                if (in == null)
                    throw new IOException("resource " + name + " is not in the harness jar");

                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return target.toAbsolutePath().toString();
    }
}
