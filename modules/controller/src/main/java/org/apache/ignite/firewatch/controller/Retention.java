package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Keeps the data volume bounded: JFR recordings (tens of MB per phase) and the work directories failed or cancelled
 * phases left (up to gigabytes with persistence) stay only for the most recent jobs,
 * {@code result.json}/{@code series.json}/logs stay forever (kilobytes). A log a node flooded, with every failed query
 * for one, keeps only its start and its end.
 */
public final class Retention {
    /** A log over this size is cut to its first and last {@link #LOG_KEEP} bytes. */
    static final long LOG_LIMIT = 20L << 20;

    /** */
    static final long LOG_KEEP = 5L << 20;

    /**
     * Cuts the oversized logs of every finished job, and deletes {@code *.jfr} and {@code work/} from the runs of all
     * but the newest {@code keepJobs}. Returns bytes freed.
     *
     * @param db Database.
     * @param dataDir Data directory.
     * @param keepJobs Number of the newest jobs to keep.
     * @return Bytes freed.
     */
    public static long pruneHeavyFiles(Db db, Path dataDir, int keepJobs) {
        long freed = 0, cut = 0;
        List<Job> history = db.jobs("history", 10_000);

        for (int i = 0; i < history.size(); i++) {
            for (Run r : db.runs(history.get(i).id)) {
                Path dir = dataDir.resolve(r.dir);

                if (!Files.isDirectory(dir))
                    continue;

                cut += cutLogs(dir);

                if (i < keepJobs)
                    continue;

                if (Files.isDirectory(dir.resolve("work"))) {
                    freed += Util.sizeOf(dir.resolve("work"));
                    dropWorkDir(dir);
                }

                try (Stream<Path> s = Files.list(dir)) {
                    for (Path p : (Iterable<Path>)s::iterator) {
                        if (p.getFileName().toString().endsWith(".jfr")) {
                            freed += Files.size(p);
                            Files.delete(p);
                        }
                    }
                }
                catch (IOException e) {
                    Log.warn("retention: cannot clean " + dir + ": " + e);
                }
            }
        }

        if (freed > 0)
            Log.info("retention: freed " + Util.humanBytes(freed) + " of JFR recordings and work directories");

        if (cut > 0)
            Log.info("retention: cut " + Util.humanBytes(cut) + " from logs over " + Util.humanBytes(LOG_LIMIT));

        return freed + cut;
    }

    /**
     * Cuts every {@code *.log} of a run directory over {@link #LOG_LIMIT} to its start and end, a note in between.
     * Returns bytes freed.
     */
    static long cutLogs(Path dir) {
        long freed = 0;

        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>)s::iterator) {
                if (!p.getFileName().toString().endsWith(".log") || !Files.isRegularFile(p))
                    continue;

                long size = Files.size(p);

                if (size <= LOG_LIMIT)
                    continue;

                Path cut = p.resolveSibling(p.getFileName() + ".cut");

                try (FileChannel in = FileChannel.open(p); FileChannel out = FileChannel.open(cut,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    copy(in, 0, LOG_KEEP, out);
                    out.write(ByteBuffer.wrap(("\n... " + Util.humanBytes(size - 2 * LOG_KEEP)
                        + " cut: the log was over " + Util.humanBytes(LOG_LIMIT) + ", its start and end stay ...\n")
                        .getBytes(StandardCharsets.UTF_8)));
                    copy(in, size - LOG_KEEP, LOG_KEEP, out);
                }

                Files.move(cut, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                freed += size - Files.size(p);
            }
        }
        catch (IOException e) {
            Log.warn("retention: cannot cut the logs of " + dir + ": " + e);
        }

        return freed;
    }

    /** */
    private static void copy(FileChannel in, long from, long count, FileChannel out) throws IOException {
        for (long done = 0; done < count; ) {
            long n = in.transferTo(from + done, count - done, out);

            if (n <= 0)
                break;

            done += n;
        }
    }

    /**
     * What the limits act on, in bytes: all the runs, their heavy files that {@link #pruneHeavyFiles} takes, the files
     * that go with the comparisons, and the records of the ended bisections. The heavy files of a comparison count
     * twice: a limit acts on them either way.
     *
     * @param db Database.
     * @param runsDir Directory of the runs.
     * @return Bytes by kind of data.
     */
    public static Map<String, Long> usage(Db db, Path runsDir) {
        Set<Long> ofComparisons = db.comparisonJobIds();
        long[] size = new long[3];

        try {
            if (Files.isDirectory(runsDir)) {
                Files.walkFileTree(runsDir, new SimpleFileVisitor<>() {
                    // runs/<job>/<phase>/...
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path rel = runsDir.relativize(file);
                        size[0] += attrs.size();

                        if (file.getFileName().toString().endsWith(".jfr")
                            || (rel.getNameCount() > 2 && "work".equals(rel.getName(2).toString())))
                            size[1] += attrs.size();

                        if (rel.getName(0).toString().matches("\\d+")
                            && ofComparisons.contains(Long.valueOf(rel.getName(0).toString())))
                            size[2] += attrs.size();

                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE; // a running job deletes its files
                    }
                });
            }
        }
        catch (IOException e) {
            Log.warn("retention: cannot size " + runsDir + ": " + e);
        }

        Map<String, Long> out = new LinkedHashMap<>();
        out.put("runs", size[0]);
        out.put("heavy", size[1]);
        out.put("comparisons", size[2]);
        out.put("bisects", db.endedBisectBytes());

        return out;
    }

    /**
     * Removes the harness work directory of a successful phase (Ignite work dirs, temp files, JFR repository).
     *
     * @param runDir Run directory.
     */
    public static void dropWorkDir(Path runDir) {
        try {
            Util.deleteTree(runDir.resolve("work"));
        }
        catch (IOException e) {
            Log.warn("cannot delete " + runDir.resolve("work") + ": " + e);
        }
    }

    /** */
    private Retention() {
    }
}
