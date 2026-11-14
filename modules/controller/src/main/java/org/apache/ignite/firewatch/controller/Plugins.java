package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The plugins of this stand. A plugin is a jar of scenarios that run Ignite together with an extension of another
 * repository (ignite-extensions, say): the jar says which repository and which module, the stand builds that module next
 * to every commit of Ignite it measures. {@link Config#plugins} names the jars that are on: a jar lying in the directory
 * does nothing. The harness reads a jar ({@code list --plugin}); this class keeps what it said, the mirror of the
 * repository of the extension and the settings of the plugin ({@code plugin.<name>.<field>}).
 * <p>
 * Plugins stay apart from the rest: their jobs measure pairs (Ignite + extension), are never bisected and never part of
 * the history of the branch; they have their own section in the UI.
 */
public final class Plugins {
    /** Settings of a plugin: {@code plugin.<name>.<field>}. */
    static final String PREFIX = "plugin.";

    /** Ref of the extension the master pair is compared with; empty: the plugin does not run by itself. */
    static final String RELEASE = "release";

    /** Ref of Ignite next to the release of the extension; empty: the version of Ignite its pom builds against. */
    static final String IGNITE = "ignite";

    /** Repository of the extension; empty: the one of the jar. */
    static final String REPO = "repo";

    /** Branch of the extension the master pair takes; empty: the one of the jar. */
    static final String BRANCH = "branch";

    /** {@code keep} or {@code off}, as the other recurring runs, see {@link Freshness}. */
    static final String MODE = "mode";

    /** */
    static final String KEEP_DAYS = "keepDays";

    /** */
    private static final Set<String> FIELDS = Set.of(RELEASE, IGNITE, REPO, BRANCH, MODE, KEEP_DAYS);

    /** One plugin: its jar, what the jar says, its scenarios and the mirror of its extension. */
    public static final class Plugin {
        /** */
        public String name;

        /** */
        public String title;

        /** */
        public String description;

        /** A directory of the repository of the extension, as {@code mvn -pl} takes it. */
        public String module;

        /** What the jar says. */
        public String defaultRepo;

        /** */
        public String defaultBranch;

        /** */
        @JsonIgnore public Path jar;

        /** */
        @JsonIgnore public Scenarios scenarios;

        /** */
        @JsonIgnore GitMirror mirror;
    }

    /** */
    private final Map<String, Plugin> byName = new LinkedHashMap<>();

    /** The jars that are named but could not be read. */
    private final List<String> errors = new ArrayList<>();

    /** */
    private final Db db;

    /** */
    private Plugins(Db db) {
        this.db = db;
    }

    /**
     * No plugins: a stand whose configuration names none.
     *
     * @param db Database.
     * @return An empty set.
     */
    static Plugins none(Db db) {
        return new Plugins(db);
    }

    /**
     * For tests and fakes: plugins as given, their mirrors set.
     *
     * @param db Database.
     * @param list Plugins.
     * @return The set.
     */
    static Plugins of(Db db, List<Plugin> list) {
        Plugins out = new Plugins(db);
        list.forEach(p -> out.byName.put(p.name, p));

        return out;
    }

    /**
     * Reads the jars the configuration names. One that cannot be read is an error of its own: the stand starts all the
     * same.
     *
     * @param cfg Configuration.
     * @param db Database.
     * @return The plugins.
     */
    public static Plugins load(Config cfg, Db db) {
        Plugins out = new Plugins(db);

        for (Path jar : cfg.pluginJars()) {
            try {
                Plugin p = read(cfg, jar);

                if (out.byName.containsKey(p.name))
                    throw new IllegalArgumentException("plugin " + p.name + " is named twice, here and by "
                        + out.byName.get(p.name).jar);

                p.mirror = new GitMirror(cfg.pluginMirrorDir(p.name), out.repo(p), out.branch(p),
                    Duration.ofMinutes(cfg.fetchTimeoutMin));
                out.byName.put(p.name, p);
                Log.info("plugin " + p.name + ": " + p.scenarios.list().stream().map(s -> s.name).toList() + " of "
                    + jar);
            }
            catch (Exception e) {
                String msg = jar + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                out.errors.add(msg);
                Log.warn("plugin not loaded: " + msg);
            }
        }

        return out;
    }

    /** {@code harness list --plugin}: what the jar says of itself and its scenarios. */
    private static Plugin read(Config cfg, Path jar) throws IOException {
        if (!Files.isRegularFile(jar))
            throw new IOException("no such file");

        List<String> cmd = new ArrayList<>(cfg.harnessCommand != null ? cfg.harnessCommand
            : List.of(cfg.javaExe, "-Xmx64m", "-jar", cfg.harnessJar.toAbsolutePath().toString()));
        cmd.addAll(List.of("list", "--plugin", jar.toAbsolutePath().toString()));
        Proc.Result res = Proc.run(cmd, null, cfg.harnessEnv, Duration.ofSeconds(60));
        int start = res.out.indexOf('{');

        if (!res.ok() || start < 0)
            throw new IOException("harness list failed: " + res.tail(3).trim());

        JsonNode n = Json.MAPPER.readTree(res.out.substring(start));
        JsonNode d = n.path("plugin");
        Plugin p = new Plugin();
        p.name = d.path("name").asText();
        p.title = d.path("title").asText(p.name);
        p.description = d.path("description").asText("");
        p.module = d.path("module").asText();
        p.defaultRepo = d.path("repo").asText();
        p.defaultBranch = d.path("branch").asText("master");
        p.jar = jar;
        List<Scenarios.Info> list = new ArrayList<>();
        n.path("scenarios").forEach(s -> list.add(Scenarios.parse(s)));

        if (list.isEmpty())
            throw new IOException("the jar has no scenarios");

        p.scenarios = new Scenarios(list);

        return p;
    }

    /**
     * Every plugin, in the order of the configuration.
     *
     * @return Plugins.
     */
    public List<Plugin> list() {
        return List.copyOf(byName.values());
    }

    /**
     * Finds a plugin.
     *
     * @param name Plugin name.
     * @return The plugin, or {@code null}.
     */
    public Plugin get(String name) {
        return name == null ? null : byName.get(name);
    }

    /**
     * Finds a plugin, or fails.
     *
     * @param name Plugin name.
     * @return The plugin.
     */
    Plugin require(String name) {
        Plugin p = get(name);

        if (p == null)
            throw new java.util.NoSuchElementException("no plugin " + name);

        return p;
    }

    /**
     * A scenario of a plugin.
     *
     * @param plugin Plugin name.
     * @param name Scenario name.
     * @return The scenario, or {@code null}.
     */
    public Scenarios.Info scenario(String plugin, String name) {
        Plugin p = get(plugin);

        return p == null ? null : p.scenarios.get(name);
    }

    /**
     * The jars that are named and could not be read.
     *
     * @return Errors.
     */
    public List<String> errors() {
        return List.copyOf(errors);
    }

    /**
     * Key of a setting of a plugin.
     *
     * @param plugin Plugin name.
     * @param field Field.
     * @return Setting key.
     */
    static String key(String plugin, String field) {
        return PREFIX + plugin + "." + field;
    }

    /** A setting of the plugin, its default when unset. */
    String setting(Plugin p, String field, String def) {
        String v = db.setting(key(p.name, field), "");

        return v.isBlank() ? def : v.trim();
    }

    /** The repository in force. */
    String repo(Plugin p) {
        return setting(p, REPO, p.defaultRepo);
    }

    /** The branch in force. */
    String branch(Plugin p) {
        return setting(p, BRANCH, p.defaultBranch);
    }

    /** Ref of the release of the extension; null when it is not set. */
    String release(Plugin p) {
        String v = setting(p, RELEASE, "");

        return v.isEmpty() ? null : v;
    }

    /**
     * Checks the form of a setting of a plugin, as {@link Settings#validate} does for the others; whether the plugin
     * and its refs exist is {@link #check}.
     *
     * @param key Setting key.
     * @param value Value.
     * @return Normalized value.
     */
    static String validate(String key, String value) {
        String rest = key.substring(PREFIX.length());
        int dot = rest.lastIndexOf('.');
        String name = dot < 0 ? "" : rest.substring(0, dot), field = dot < 0 ? rest : rest.substring(dot + 1);

        if (!name.matches("[a-z][a-z0-9-]{0,39}") || !FIELDS.contains(field))
            throw new IllegalArgumentException("unknown setting: " + key);

        String v = value.trim();

        if (v.isEmpty())
            return v; // the default

        switch (field) {
            case RELEASE:
            case IGNITE:
                if (!v.matches("[0-9A-Za-z._/-]{1,100}"))
                    throw new IllegalArgumentException(key + " must be a tag, a branch or a sha");

                return v;
            case REPO:
                if (!v.matches("(https?://|ssh://|git@|file:/|/)\\S+"))
                    throw new IllegalArgumentException(key + " must be a git URL");

                return v;
            case BRANCH:
                if (!v.matches("[0-9A-Za-z._/][0-9A-Za-z._/-]{0,99}"))
                    throw new IllegalArgumentException(key + " must be a branch name");

                return v;
            case MODE:
                if (!v.equals(Freshness.OFF) && !v.equals(Freshness.KEEP))
                    throw new IllegalArgumentException(key + ": unknown mode " + v);

                return v;
            default: // KEEP_DAYS
                if (!v.matches("[1-9]\\d{0,2}"))
                    throw new IllegalArgumentException(key + " must be a number of days from 1");

                return v;
        }
    }

    /**
     * Checks a setting against the plugin and the repositories: the plugin is on this stand, a ref is known. A mirror
     * that has not been fetched yet takes any ref: the first run will tell.
     *
     * @param key Setting key.
     * @param value Validated value.
     * @param ignite Mirror of Ignite.
     */
    void check(String key, String value, GitMirror ignite) throws IOException {
        String rest = key.substring(PREFIX.length());
        Plugin p = require(rest.substring(0, rest.lastIndexOf('.')));
        String field = rest.substring(rest.lastIndexOf('.') + 1);

        if (value.isEmpty())
            return;

        if (RELEASE.equals(field) && p.mirror.ready())
            p.mirror.resolve(p.mirror.normalize(value));
        else if (IGNITE.equals(field) && ignite.ready())
            ignite.resolve(ignite.normalize(value));
    }

    /**
     * After the settings are saved: a new repository or branch is followed from now on.
     *
     * @param key Setting key.
     */
    void applied(String key) throws IOException {
        String rest = key.substring(PREFIX.length());
        Plugin p = get(rest.substring(0, rest.lastIndexOf('.')));
        String field = rest.substring(rest.lastIndexOf('.') + 1);

        if (p != null && (REPO.equals(field) || BRANCH.equals(field)))
            p.mirror.follow(repo(p), branch(p));
    }
}
