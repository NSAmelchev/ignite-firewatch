package org.apache.ignite.firewatch.controller;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/** Comma/newline-separated glob list; a commit passes when any changed file matches any glob (empty list = all). */
public final class PathFilter {
    /** */
    private final List<String> globs = new ArrayList<>();

    /** */
    private final List<PathMatcher> matchers = new ArrayList<>();

    /**
     * Parses a list of globs.
     *
     * @param spec Comma or line separated globs, may be {@code null}.
     * @return Filter of the globs.
     */
    public static PathFilter parse(String spec) {
        PathFilter f = new PathFilter();

        if (spec == null)
            return f;

        for (String g : spec.split("[,\\n]")) {
            g = g.trim();

            if (g.isEmpty())
                continue;

            try {
                f.matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
                f.globs.add(g);
            }
            catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("bad glob '" + g + "': " + e.getMessage());
            }
        }

        return f;
    }

    /**
     * Checks whether the filter lets every file pass.
     *
     * @return {@code True} if the filter has no globs.
     */
    public boolean isEmpty() {
        return matchers.isEmpty();
    }

    /**
     * Checks a file against the globs.
     *
     * @param file File path.
     * @return {@code True} if the filter is empty or the file matches any glob.
     */
    public boolean matches(String file) {
        if (matchers.isEmpty())
            return true;

        Path p = Path.of(file);

        for (PathMatcher m : matchers) {
            if (m.matches(p))
                return true;
        }

        return false;
    }

    /**
     * True if the filter is empty, the list is empty (unknown), or any file matches.
     *
     * @param files File paths, may be {@code null}.
     * @return {@code True} if the filter accepts the list.
     */
    public boolean matchesAny(List<String> files) {
        if (matchers.isEmpty() || files == null || files.isEmpty())
            return true;

        for (String f : files) {
            if (matches(f))
                return true;
        }

        return false;
    }

    /**
     * Returns the globs.
     *
     * @return Globs of the filter.
     */
    public List<String> globs() {
        return globs;
    }
}
