package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

/** Small file-system helpers. */
public final class Util {
    /**
     * Deletes a directory with everything in it.
     *
     * @param root Directory or file to delete.
     */
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root))
            return;

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);

                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Sums the sizes of the files in a tree.
     *
     * @param root Directory or file.
     * @return Size in bytes.
     */
    public static long sizeOf(Path root) {
        AtomicLong total = new AtomicLong();

        if (!Files.exists(root))
            return 0;

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total.addAndGet(attrs.size());

                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException ignored) {
        }

        return total.get();
    }

    /**
     * Formats a size for people.
     *
     * @param b Size in bytes.
     * @return Size with a unit.
     */
    public static String humanBytes(long b) {
        if (b < 1024)
            return b + " B";

        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;

        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }

        return String.format("%.1f %s", v, u[i]);
    }

    /** */
    private Util() {
    }
}
