package org.apache.ignite.firewatch.controller;

import java.util.List;

/** A commit of the watched branch (or any ref the user asked about). */
public final class CommitInfo {
    /** */
    public String sha;

    /** */
    public String parentSha;

    /** */
    public String author;

    /** */
    public String committedAt;

    /** */
    public String subject;

    /** First-parent position on the watched branch (grows with history), null for other refs. */
    public Integer seq;

    /** */
    public String branch;

    /** Only on the Commits page: jobs of this commit, reduced to what the page shows (id, status, source, type). */
    public List<java.util.Map<String, Object>> jobs;
}
