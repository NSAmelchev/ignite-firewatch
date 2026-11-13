package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What a job and its builds leave to take away: the files of the job as a zip made on the fly, the log of a build and its
 * Ignite jars. A recording is tens of MB and the heap of the controller 128 MB, so every file goes to the client as a
 * stream; the work directories of the nodes never go.
 */
public final class Downloads {
    /** A build: the sha of the commit, then the modules of its scenario. */
    static final Pattern KEY = Pattern.compile("[0-9a-f]{40}(\\+[a-z][a-z0-9-]*)*");

    /** What the nodes write their data into: big and of no use away from the stand. */
    private static final String WORK = "work";

    /** */
    private static final String ZIP = "application/zip";

    /** */
    private final Config cfg;

    /** */
    private final Db db;

    /** */
    private final Scenarios scenarios;

    /**
     * @param cfg Configuration.
     * @param db Database.
     * @param scenarios Scenarios: their modules name the builds of a job.
     */
    public Downloads(Config cfg, Db db, Scenarios scenarios) {
        this.cfg = cfg;
        this.db = db;
        this.scenarios = scenarios;
    }

    /**
     * Tells what job {@code id} has to download and how big it is.
     *
     * @param id Job id.
     * @return {@code zip}: {@code bytes} without recordings, {@code jfrBytes} with them ({@code null} when retention
     *      took them), {@code files}, {@code phases}; {@code builds}: a line per side with its log and jars.
     * @throws IOException If the files cannot be listed.
     */
    public Map<String, Object> of(long id) throws IOException {
        Job j = job(id);
        long plain = 0, jfr = 0;
        int files = 0;

        for (Path f : jobFiles(j, true)) {
            long size = size(f);

            if (jfr(f))
                jfr += size;
            else
                plain += size;

            files++;
        }

        List<Map<String, Object>> builds = new ArrayList<>();

        for (Map.Entry<String, String> side : sides(j).entrySet()) {
            String key = Builder.key(side.getValue(), modules(j));
            Map<String, Object> m = build(key, db.build(key));
            m.put("side", side.getKey());
            builds.add(m);

            if (m.get("logBytes") != null)
                plain += (Long)m.get("logBytes");
        }

        Map<String, Object> zip = new LinkedHashMap<>();
        zip.put("bytes", plain);
        zip.put("jfrBytes", jfr > 0 ? plain + jfr : null);
        zip.put("files", files);
        zip.put("phases", db.runs(id).size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", id);
        out.put("zip", zip);
        out.put("builds", builds);

        return out;
    }

    /**
     * Describes a build for the pages: whether its log and its jars can be downloaded.
     *
     * @param key Build key.
     * @param b Build, or {@code null} when the commit was never built.
     * @return {@code key}, {@code label}, {@code status}, {@code igniteVersion}, {@code logBytes} and {@code jarsBytes},
     *      the last two {@code null} when there is nothing to download.
     */
    public Map<String, Object> build(String key, Build b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", Builder.label(key));
        m.put("status", b == null ? null : b.status);
        m.put("igniteVersion", b == null ? null : b.igniteVersion);
        Path log = logOf(b);
        m.put("logBytes", log == null ? null : size(log));
        m.put("jarsBytes", jarsReady(key, b) ? b.sizeBytes : null);

        return m;
    }

    /**
     * Zips the files of a job: the phases with their logs and results, the logs of its builds, the recordings on
     * request.
     *
     * @param id Job id.
     * @param withJfr Whether the JFR recordings go too.
     * @return Response that writes the zip as it goes.
     * @throws IOException If the files cannot be listed.
     */
    public Router.Response jobZip(long id, boolean withJfr) throws IOException {
        Job j = job(id);
        String root = "job" + id;
        List<Path> files = jobFiles(j, withJfr);
        Path dir = cfg.runsDir().resolve(String.valueOf(id));
        Map<String, Path> logs = new LinkedHashMap<>();

        for (Map.Entry<String, String> side : sides(j).entrySet()) {
            String key = Builder.key(side.getValue(), modules(j));
            Path log = logOf(db.build(key));

            if (log != null)
                logs.put("build-" + side.getKey() + "-" + Builder.label(key) + ".log", log);
        }

        return Router.Response.stream(ZIP, out -> {
            try (ZipOutputStream zip = zip(out)) {
                for (Path f : files)
                    put(zip, root + "/" + dir.relativize(f).toString().replace('\\', '/'), f);

                for (Map.Entry<String, Path> e : logs.entrySet())
                    put(zip, root + "/" + e.getKey(), e.getValue());
            }
        }).attachment(root + "-" + j.scenario + (withJfr ? "-jfr" : "") + ".zip");
    }

    /**
     * The log of a build, to read in the browser.
     *
     * @param key Build key.
     * @return Response with the log.
     */
    public Router.Response buildLog(String key) {
        Path log = logOf(db.build(checked(key)));

        if (log == null)
            throw new NoSuchElementException("no build log of " + Builder.label(key));

        return Router.Response.file("text/plain", log);
    }

    /**
     * The Ignite jars of a build as a zip: what a phase puts on the classpath of its nodes.
     *
     * @param key Build key.
     * @return Response that writes the zip as it goes.
     * @throws IOException If the jars cannot be listed.
     */
    public Router.Response buildJars(String key) throws IOException {
        Build b = db.build(checked(key));

        if (!jarsReady(key, b)) {
            throw new NoSuchElementException(b == null || !Build.OK.equals(b.status) ? "no jars of " + Builder.label(key)
                + ": it did not build" : "the jars of " + Builder.label(key) + " are gone: the build cache evicted them, the "
                + "next run of the commit builds it again");
        }

        String root = "ignite-" + (b.igniteVersion == null ? "" : b.igniteVersion + "-") + Builder.label(key);
        List<Path> jars;

        try (Stream<Path> s = Files.list(Path.of(b.libsDir))) {
            jars = s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }

        return Router.Response.stream(ZIP, out -> {
            try (ZipOutputStream zip = zip(out)) {
                for (Path p : jars)
                    put(zip, root + "/" + p.getFileName(), p);
            }
        }).attachment(root + ".zip");
    }

    /** */
    private Job job(long id) {
        Job j = db.job(id);

        if (j == null)
            throw new NoSuchElementException("no job " + id);

        return j;
    }

    /** @return The commits a job builds, by side: A, and B when it is another commit. */
    private static Map<String, String> sides(Job j) {
        Map<String, String> m = new LinkedHashMap<>();

        if (j.shaA != null)
            m.put("A", j.shaA);

        if (j.isAbba() && j.shaB != null && !j.shaB.equals(j.shaA))
            m.put("B", j.shaB);

        return m;
    }

    /** */
    private List<String> modules(Job j) {
        Scenarios.Info sc = scenarios.get(j.scenario);

        return sc == null ? List.of() : sc.modules;
    }

    /** @return The files of the phases of a job, without the work directories of the nodes. */
    private List<Path> jobFiles(Job j, boolean withJfr) throws IOException {
        Path dir = cfg.runsDir().resolve(String.valueOf(j.id));

        if (!Files.isDirectory(dir))
            return List.of();

        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> !dir.relativize(p).toString().replace('\\', '/').matches("(.*/)?" + WORK + "(/.*)?"))
                .filter(Files::isRegularFile).filter(p -> withJfr || !jfr(p)).sorted().toList();
        }
    }

    /** @return The log of a build when it is still there and inside the build cache. */
    private Path logOf(Build b) {
        if (b == null || b.logFile == null)
            return null;

        Path log = Path.of(b.logFile).toAbsolutePath().normalize();

        return log.startsWith(cfg.artifactsDir().toAbsolutePath().normalize()) && Files.isRegularFile(log) ? log : null;
    }

    /** Whether the jars of a build are still in the cache, as {@link Builder#cached} has it. */
    private static boolean jarsReady(String key, Build b) {
        return b != null && Build.OK.equals(b.status) && b.libsDir != null && Files.exists(Path.of(b.libsDir, Builder.OK_MARKER));
    }

    /** */
    private static String checked(String key) {
        if (!KEY.matcher(key).matches())
            throw new IllegalArgumentException("not a build: " + key);

        return key;
    }

    /** */
    private static boolean jfr(Path p) {
        return p.getFileName().toString().endsWith(".jfr");
    }

    /** */
    private static long size(Path p) {
        try {
            return Files.size(p);
        }
        catch (IOException e) {
            return 0; // retention took it meanwhile
        }
    }

    /** Logs and JSON shrink well, recordings little: the fastest level costs the stand least. */
    private static ZipOutputStream zip(OutputStream out) {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(Deflater.BEST_SPEED);

        return zip;
    }

    /** Puts a file into the zip; one retention took meanwhile is left out. */
    private static void put(ZipOutputStream zip, String name, Path f) throws IOException {
        if (!Files.isRegularFile(f))
            return;

        zip.putNextEntry(new ZipEntry(name));

        try {
            Files.copy(f, zip);
        }
        catch (NoSuchFileException e) {
            // gone between the check and the copy: an empty entry says it was there
        }

        zip.closeEntry();
    }
}
