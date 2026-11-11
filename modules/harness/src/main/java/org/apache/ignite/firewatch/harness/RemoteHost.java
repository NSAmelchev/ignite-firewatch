package org.apache.ignite.firewatch.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A machine of a remote stand, reached with the ssh and rsync of this machine: nothing of ours runs there between
 * phases.
 * ssh stays connected for the whole life of a JVM and carries its output into the local log, so the launcher watches a
 * remote
 * JVM the way it watches a local one; only signals have to go over a separate ssh call. The Ignite jars stay on the
 * host
 * between phases, under the build they came from; the directory of a phase goes when the phase ends.
 */
final class RemoteHost implements Host {
    /** */
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);

    /** Builds kept on a host; the core and SQL builds of a few recent commits. */
    private static final int KEEP_BUILDS = 20;

    /** ssh found no host to talk to: it says so plainly, and -v would only wait as long once more. */
    private static final List<String> NO_CONNECTION = List.of("Connection refused", "timed out", "No route to host",
        "Could not resolve hostname", "Network is unreachable");

    /** */
    private final StandConfig stand;

    /** */
    private final StandConfig.Host h;

    /** */
    private final String runId;

    /** */
    private Map<String, Object> facts;

    /** Where the commands go as they run; null keeps them quiet, as a phase does. */
    private CommandLog log;

    /** */
    RemoteHost(StandConfig stand, StandConfig.Host h, String runId) {
        this.stand = stand;
        this.h = h;
        this.runId = runId;
    }

    /**
     * Prints every command of this host and how it ended.
     *
     * @param log Command log.
     * @return {@code this}.
     */
    RemoteHost log(CommandLog log) {
        this.log = log;

        return this;
    }

    /** The separate network for the traffic between nodes, when the host has one; ssh always uses the main address. */
    @Override public String address() {
        return h.dataAddress == null || h.dataAddress.isBlank() ? h.address : h.dataAddress;
    }

    /** {@inheritDoc} */
    @Override public String dir(Path out) {
        return h.workDir + "/runs/" + runId;
    }

    /** */
    private String libsOf(Path libsDir) {
        return h.workDir + "/builds/" + libsDir.toAbsolutePath().getParent().getFileName() + "/libs";
    }

    /** The name of the host for people: ssh always uses it, whatever network the nodes talk over. */
    @Override public String name() {
        return h.address;
    }

    /** {@inheritDoc} */
    @Override public Map<String, Object> facts() {
        return facts;
    }

    /** {@inheritDoc} */
    @Override public void prepare(Path harnessJar, Path libsDir, Path out) throws IOException {
        sweep();
        String libs = libsOf(libsDir);
        run(ssh("mkdir -p " + quote(dir(out)) + " " + quote(libs)));
        run(rsync(harnessJar.toAbsolutePath().toString(), remote(h.workDir + "/harness.jar")));
        facts = readFacts(); // before the jars: a changed host fails the phase at once
        String changed = changed(stand.fingerprints.get(h.address), StandCheck.fingerprintOf(facts));

        if (changed != null)
            throw new IllegalStateException("host " + h.address + " is not the one the stand was checked on: "
                + changed);

        run(rsync("--delete", libsDir.toAbsolutePath() + "/", remote(libs + "/")));
        run(ssh(forgetOldBuilds(libs)));
        run(rsync(out.resolve("ignite-firewatch.jfc").toAbsolutePath().toString(),
            remote(dir(out) + "/ignite-firewatch.jfc")));
    }

    /** What differs from the checked host; null if nothing does, or if it was not checked at all. */
    static String changed(Map<String, String> checked, Map<String, String> now) {
        if (checked == null)
            return null;

        List<String> out = new ArrayList<>();
        checked.forEach((k, v) -> {
            if (!v.equals(now.get(k)))
                out.add(k + " was " + v + ", now " + now.get(k));
        });

        return out.isEmpty() ? null : String.join("; ", out);
    }

    /** The build in use counts as just used; the rest of the cache goes by age. */
    private String forgetOldBuilds(String libs) {
        return "touch " + quote(libs + "/..") + " && cd " + quote(h.workDir + "/builds")
            + " && ls -1t | tail -n +" + (KEEP_BUILDS + 1) + " | while read -r b; do rm -rf -- \"$b\"; done";
    }

    /** Leftovers of this stand: JVMs and phase directories of a launcher that was killed before it could clean up. */
    void sweep() throws IOException {
        if (stand.runPrefix == null || stand.runPrefix.isBlank())
            return;

        run(ssh(sweepCommand()), true);
    }

    /** Only what starts with the prefix: another stand may share the host and its work directory. */
    String sweepCommand() {
        return stopCommand("KILL", stand.runPrefix) + "; rm -rf " + quote(h.workDir + "/runs/" + stand.runPrefix) + "*";
    }

    /** ssh, rsync and pkill work, and the probe is in place. */
    void installProbe(Path harnessJar) throws IOException {
        run(ssh("mkdir -p " + quote(h.workDir)
            + " && command -v pkill >/dev/null || { echo 'pkill is missing'; exit 3; }"));
        run(rsync(harnessJar.toAbsolutePath().toString(), remote(h.workDir + "/harness.jar")));
    }

    /** */
    private String probe(String... args) {
        List<String> c = new ArrayList<>(List.of(h.javaHome + "/bin/java", "-Xmx32m", "-cp", h.workDir + "/harness.jar",
            HostProbe.class.getName()));
        c.addAll(List.of(args));

        return c.stream().map(RemoteHost::quote).collect(Collectors.joining(" "));
    }

    /** */
    Map<String, Object> readFacts() throws IOException {
        return Json.MAPPER.convertValue(json(run(ssh(probe("facts", h.workDir)))),
            new TypeReference<LinkedHashMap<String, Object>>() {
            });
    }

    /** Holds the ports open for {@code seconds}, the time the others have to try them. */
    Process listen(String ports, int seconds) throws IOException {
        List<String> cmd = ssh(probe("listen", ports, String.valueOf(seconds)));
        long started = log == null ? 0 : log.start(h.address, cmd);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder said = new StringBuilder();
        String line;

        while ((line = out.readLine()) != null) {
            if (line.contains(HostProbe.READY)) {
                if (log != null)
                    log.note(h.address, "listening, until the others have tried the ports");

                return p;
            }

            said.append(line).append('\n');
        }

        Integer rc = null;

        try {
            if (p.waitFor(5, TimeUnit.SECONDS))
                rc = p.exitValue();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        p.destroyForcibly();

        if (log != null)
            log.end(h.address, started, rc, said.toString());

        throw new IOException(said.toString().trim().replace('\n', ' '));
    }

    /** */
    void stopProbe(Process listener) {
        listener.destroyForcibly();
        signalProbe();
    }

    /** */
    private void signalProbe() {
        try {
            run(ssh("pkill -KILL -f " + quote("HostProbe[ ]listen")), true); // the brackets keep it off its own shell
        }
        catch (IOException e) {
            System.out.println("[harness] " + h.address + ": probe not stopped: " + e.getMessage());
        }
    }

    /** */
    JsonNode connect(String targets) throws IOException {
        return json(run(ssh(probe("connect", targets))));
    }

    /** The answer is the last line of the probe: ssh may print a banner before it, the JVM its warnings. */
    static JsonNode json(String output) throws IOException {
        return Json.MAPPER.readTree(output.substring(output.lastIndexOf('\n') + 1));
    }

    /** {@inheritDoc} */
    @Override public JvmProcess start(JvmProcess.Spec s) throws IOException {
        String classpath = h.workDir + "/harness.jar:" + libsOf(s.libsDir) + "/*";
        List<String> java = JvmProcess.javaCommand(s, h.javaHome + "/bin/java", classpath);
        String cmd = "mkdir -p " + quote(s.dir + "/work/tmp") + " " + quote(s.dir + "/work/jfr-" + s.label)
            + " && exec "
            + java.stream().map(RemoteHost::quote).collect(Collectors.joining(" "));
        String marker = JvmProcess.marker(s);

        return JvmProcess.start(ssh(cmd), s, new JvmProcess.Signals() {
            @Override public void term(Process p) {
                signal("TERM", marker + "( |$)");
            }

            @Override public void kill(Process p) {
                signal("KILL", marker + "( |$)");
                p.destroyForcibly();
            }
        });
    }

    /** {@inheritDoc} */
    @Override public boolean fetch(String file, Path out) {
        try {
            run(rsync(remote(dir(out) + "/" + file), out.toAbsolutePath() + "/"));

            return true;
        }
        catch (IOException e) {
            System.out.println("[harness] " + h.address + ": " + file + " not fetched: " + e.getMessage());

            return false;
        }
    }

    /** Whatever of this run is still alive on the host goes first: it may be holding files open. */
    @Override public void cleanup(Path out) {
        signal("KILL", runId + "-");

        try {
            run(ssh("rm -rf " + quote(dir(out))));
        }
        catch (IOException e) {
            System.out.println("[harness] " + h.address + ": " + dir(out) + " not removed: " + e.getMessage());
        }
    }

    /** */
    private void signal(String name, String pattern) {
        try {
            run(ssh(stopCommand(name, pattern)), true);
        }
        catch (IOException e) {
            System.out.println("[harness] " + h.address + ": SIG" + name + " not sent: " + e.getMessage());
        }
    }

    /**
     * Matches {@code -D}{@link Jvm#RUN_MARKER}{@code =}: the backslashes keep the shell that runs pkill, whose own
     * command line holds this pattern, from matching itself.
     */
    static String stopCommand(String signal, String pattern) {
        return "pkill -" + signal + " -f " + quote(Jvm.RUN_MARKER.replace(".", "\\.") + "=" + pattern);
    }

    /** */
    List<String> ssh(String command) {
        List<String> c = new ArrayList<>(sshOptions());
        c.add("-n");
        c.add(h.user + "@" + h.address);
        c.add(command);

        return c;
    }

    /** */
    private List<String> sshOptions() {
        List<String> c = new ArrayList<>(List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o",
            "ServerAliveInterval=15",
            "-o", "ServerAliveCountMax=4", "-p", String.valueOf(h.port)));

        if (stand.sshKey != null && !stand.sshKey.isBlank())
            c.addAll(List.of("-i", stand.sshKey, "-o", "IdentitiesOnly=yes"));

        boolean ownFile = stand.sshKnownHosts != null && !stand.sshKnownHosts.isBlank();

        if (ownFile)
            c.addAll(List.of("-o", "UserKnownHostsFile=" + stand.sshKnownHosts));

        // ssh keeps the first value of an option: one of them only
        if (stand.sshAcceptNewHosts)
            c.addAll(List.of("-o", "StrictHostKeyChecking=accept-new"));
        else if (ownFile)
            c.addAll(List.of("-o", "StrictHostKeyChecking=yes"));

        return c;
    }

    /** */
    List<String> rsync(String... args) {
        List<String> c = new ArrayList<>(List.of("rsync", "-a", "-e",
            sshOptions().stream().map(RemoteHost::quote).collect(Collectors.joining(" "))));
        c.addAll(List.of(args));

        return c;
    }

    /** */
    private String remote(String path) {
        return h.user + "@" + h.address + ":" + path;
    }

    /** */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** */
    private String run(List<String> command) throws IOException {
        return run(command, false);
    }

    /**
     * The output of the command. {@code anyExit}: for pkill, which exits with 1 when there was nothing to signal; ssh
     * itself exits with 255.
     */
    private String run(List<String> command, boolean anyExit) throws IOException {
        try {
            return run(command, anyExit, COMMAND_TIMEOUT, log, h.address);
        }
        catch (ExitException e) {
            if (log != null && worthExplaining(command, e))
                explain(command);

            throw e;
        }
    }

    /**
     * Whether ssh failed by itself (exit 255) while there was a host to talk to: a key, known_hosts or the config.
     *
     * @param command Command that failed.
     * @param e How it failed.
     * @return {@code True} if ssh -v can tell more.
     */
    static boolean worthExplaining(List<String> command, ExitException e) {
        return e.code == 255 && "ssh".equals(command.get(0)) && NO_CONNECTION.stream().noneMatch(e.getMessage()::contains);
    }

    /** The same ssh with -v: which files it read, which keys it offered and why the host turned it away. */
    void explain(List<String> command) {
        List<String> verbose = new ArrayList<>(command);
        verbose.add(1, "-v");
        log.note(h.address, "ssh failed by itself (exit 255), once more with -v:");

        try {
            run(verbose, true, Duration.ofMinutes(1), log, h.address);
        }
        catch (IOException ignored) {
            // the log has it
        }
    }

    /** The output goes through a file: reading a pipe would wait for the command and leave the timeout out of reach. */
    static String run(List<String> command, boolean anyExit, Duration timeout) throws IOException {
        return run(command, anyExit, timeout, null, null);
    }

    /**
     * Runs the command; {@code log} gets it and how it ended, under {@code host}.
     *
     * @param command Command.
     * @param anyExit Whether any exit code but 255 is fine.
     * @param timeout Time the command has.
     * @param log Command log, or {@code null}.
     * @param host Host the command is about, for the log.
     * @return Output of the command.
     */
    static String run(List<String> command, boolean anyExit, Duration timeout, CommandLog log, String host)
        throws IOException {
        long started = log == null ? 0 : log.start(host, command);
        Path file = Files.createTempFile("firewatch-host", ".log");
        Process p;

        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(file.toFile()).start();
        }
        catch (IOException e) {
            Files.deleteIfExists(file);

            if (log != null)
                log.end(host, started, null, e.getMessage());

            throw e;
        }

        try {
            p.getOutputStream().close();

            if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();

                if (log != null)
                    log.end(host, started, null, Files.readString(file, StandardCharsets.UTF_8));

                throw new IOException(command.get(0) + " did not finish within " + timeout.toSeconds() + " s");
            }

            String output = Files.readString(file, StandardCharsets.UTF_8).trim();
            int rc = p.exitValue();

            if (log != null)
                log.end(host, started, rc, output);

            if (rc == 255 || (rc != 0 && !anyExit))
                throw new ExitException(command.get(0) + " exited with code " + rc + ": " + output, rc);

            return output;
        }
        catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();

            throw new IOException("interrupted", e);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    /** A command that ended with a code it should not have. */
    static final class ExitException extends IOException {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        final int code;

        /** */
        ExitException(String msg, int code) {
            super(msg);
            this.code = code;
        }
    }
}
