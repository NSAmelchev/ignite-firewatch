package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

/**
 * The poms of an extension of a plugin. An extension of ignite-extensions has no version of Ignite of its own: the
 * chain of its parents ends in {@code org.apache.ignite:ignite-parent}, and the version of that parent is the Ignite it
 * builds against ({@code 2.17.0} on a release tag, {@code 2.20.0-SNAPSHOT} on master). The release pair of a plugin
 * takes that version as the tag of Ignite; a build sets it to the version of the Ignite it has just built, so the
 * extension always builds against exactly the Ignite of its pair.
 */
final class PluginPoms {
    /** */
    static final String GROUP = "org.apache.ignite";

    /** */
    static final String PARENT = "ignite-parent";

    /** How far up the chain may go. */
    private static final int MAX_DEPTH = 12;

    /** The version of the first parent block of a pom. */
    private static final Pattern PARENT_VERSION =
        Pattern.compile("(<parent>(?:(?!</parent>).)*?<version>)([^<]*)(</version>)", Pattern.DOTALL);

    /** Reads a file of the repository by its path; null when there is none. */
    interface Repo {
        /**
         * Reads a file.
         *
         * @param path Path from the root of the repository, with '/'.
         * @return Text, or {@code null}.
         */
        String read(String path) throws IOException;
    }

    /**
     * The pom whose parent is {@code ignite-parent}, and the version it names.
     *
     * @param pom Path of that pom from the root.
     * @param version Version of Ignite.
     */
    record Found(String pom, String version) {
    }

    /** */
    private PluginPoms() {
    }

    /**
     * Follows the parents from the pom of the module to {@code ignite-parent}.
     *
     * @param repo The repository.
     * @param module Directory of the module.
     * @return Where the chain meets {@code ignite-parent}.
     * @throws IOException If the chain leaves the repository or never names it.
     */
    static Found igniteParent(Repo repo, String module) throws IOException {
        String path = module.isEmpty() ? "pom.xml" : module + "/pom.xml";

        for (int i = 0; i < MAX_DEPTH; i++) {
            String text = repo.read(path);

            if (text == null)
                throw new IOException(path + " is not in the repository of the extension");

            Element project = Builder.parsePom(text, path);
            List<Element> parents = Builder.children(project, "parent");

            if (parents.isEmpty())
                break;

            Element parent = parents.get(0);

            if (GROUP.equals(Builder.childText(parent, "groupId"))
                && PARENT.equals(Builder.childText(parent, "artifactId"))) {
                String v = Builder.childText(parent, "version");

                if (v == null || v.isEmpty() || v.contains("${"))
                    throw new IOException(path + " names " + PARENT + " without a plain version: " + v);

                return new Found(path, v);
            }

            String rel = Builder.childText(parent, "relativePath");

            if (rel != null && rel.isEmpty())
                break; // the parent comes from a repository, not from this one

            path = resolve(path, rel == null ? "../pom.xml" : rel);
        }

        throw new IOException("the poms of " + module + " never name " + GROUP + ":" + PARENT
            + ": the version of Ignite the extension builds against is unknown");
    }

    /** The pom {@code rel} names from {@code from}: a directory means its pom.xml. */
    static String resolve(String from, String rel) {
        Deque<String> parts = new ArrayDeque<>();
        String dir = from.contains("/") ? from.substring(0, from.lastIndexOf('/')) : "";

        for (String s : (dir + "/" + rel).split("/")) {
            if (s.isEmpty() || s.equals("."))
                continue;

            if (s.equals("..")) {
                if (!parts.isEmpty())
                    parts.removeLast();
            }
            else
                parts.addLast(s);
        }

        String p = String.join("/", parts);

        return p.endsWith(".xml") ? p : (p.isEmpty() ? "" : p + "/") + "pom.xml";
    }

    /**
     * Sets the version of the parent block of a pom.
     *
     * @param pom Pom text.
     * @param version New version.
     * @return The text with it.
     */
    static String withParentVersion(String pom, String version) {
        Matcher m = PARENT_VERSION.matcher(pom);

        if (!m.find())
            throw new IllegalArgumentException("no parent version in the pom");

        return pom.substring(0, m.start(2)) + version + pom.substring(m.end(2));
    }
}
