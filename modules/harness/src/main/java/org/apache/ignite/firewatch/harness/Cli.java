package org.apache.ignite.firewatch.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal {@code --key value} / {@code --key=value} argument parser. */
final class Cli {
    /** */
    private final Map<String, String> opts = new LinkedHashMap<>();

    /** */
    static Cli parse(String[] args, int from) {
        Cli cli = new Cli();

        for (int i = from; i < args.length; i++) {
            String a = args[i];

            if (!a.startsWith("--"))
                throw new IllegalArgumentException("Unexpected argument: " + a);

            String key = a.substring(2);
            int eq = key.indexOf('=');

            if (eq >= 0)
                cli.opts.put(key.substring(0, eq), key.substring(eq + 1));
            else if (i + 1 < args.length && !args[i + 1].startsWith("--"))
                cli.opts.put(key, args[++i]);
            else
                cli.opts.put(key, "true");
        }

        return cli;
    }

    /** */
    String req(String key) {
        String v = opts.get(key);

        if (v == null)
            throw new IllegalArgumentException("Missing required option --" + key);

        return v;
    }

    /** */
    String get(String key, String def) {
        return opts.getOrDefault(key, def);
    }

    /** */
    boolean has(String key) {
        return opts.containsKey(key);
    }

    /** */
    int getInt(String key, int def) {
        String v = opts.get(key);

        return v == null ? def : Integer.parseInt(v);
    }
}
