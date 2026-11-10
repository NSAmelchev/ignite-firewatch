package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A throw-away upstream repository for tests, driven through the git CLI. */
final class GitFixture {
    /** */
    final Path repo;

    /** */
    final List<String> shas = new ArrayList<>();

    /** */
    GitFixture(Path dir) throws IOException {
        repo = dir;
        Files.createDirectories(repo);
        git("init", "--quiet", "--template=", "-b", "master", repo.toString());
        git("-C", repo.toString(), "config", "user.email", "t@example.com");
        git("-C", repo.toString(), "config", "user.name", "Test");
        git("-C", repo.toString(), "config", "commit.gpgsign", "false");
    }

    /** Writes the files and commits; returns the sha. */
    String commit(String subject, Map<String, String> files) throws IOException {
        for (var e : files.entrySet()) {
            Path f = repo.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }

        git("-C", repo.toString(), "add", "-A");
        git("-C", repo.toString(), "commit", "--quiet", "-m", subject);
        String sha = run("-C", repo.toString(), "rev-parse", "HEAD").trim();
        shas.add(sha);

        return sha;
    }

    /** */
    void tag(String name) throws IOException {
        git("-C", repo.toString(), "tag", name);
    }

    /** */
    void branch(String name, String at) throws IOException {
        git("-C", repo.toString(), "branch", name, at);
    }

    /** */
    void checkout(String ref) throws IOException {
        git("-C", repo.toString(), "checkout", "--quiet", ref);
    }

    /** */
    String url() {
        return repo.toUri().toString();
    }

    /** */
    static void git(String... args) throws IOException {
        run(args);
    }

    /** */
    static String run(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Proc.Result r = Proc.run(cmd, null, Map.of("LC_ALL", "C"), Duration.ofMinutes(1));

        if (!r.ok())
            throw new IOException("git " + String.join(" ", args) + " failed: " + r.out);

        return r.out;
    }

    /** */
    static boolean gitAvailable() {
        try {
            return Proc.run(List.of("git", "--version"), null, null, Duration.ofSeconds(10)).ok();
        }
        catch (IOException e) {
            return false;
        }
    }
}
