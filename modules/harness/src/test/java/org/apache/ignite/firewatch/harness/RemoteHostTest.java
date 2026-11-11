package org.apache.ignite.firewatch.harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link RemoteHost}. */
class RemoteHostTest {
    /** */
    private static StandConfig.Host host(String address, String role) {
        StandConfig.Host h = new StandConfig.Host();
        h.address = address;
        h.user = "bench";
        h.role = role;
        h.javaHome = "/usr/lib/jvm/java-17";
        h.workDir = "/opt/firewatch";

        return h;
    }

    /** */
    private static StandConfig stand(StandConfig.Host... hosts) {
        StandConfig s = new StandConfig();
        s.hosts = List.of(hosts);

        return s;
    }

    /** */
    @Test void theDataNetworkIsForNodesAndSshStaysOnTheMainAddress() {
        StandConfig.Host h = host("10.0.0.1", StandConfig.SERVERS);
        h.dataAddress = "192.0.2.1";
        RemoteHost r = new RemoteHost(stand(h), h, "run1");
        assertEquals("192.0.2.1", r.address());
        assertTrue(r.ssh("true").contains("bench@10.0.0.1"));
        assertEquals("10.0.0.2", new RemoteHost(stand(), host("10.0.0.2", StandConfig.SERVERS), "run1").address());
    }

    /** */
    @Test void theSweepLeavesThePhasesOfOtherStands() {
        StandConfig.Host h = host("a", StandConfig.SERVERS);
        StandConfig s = stand(h);
        s.runPrefix = "1f2e-";
        String cmd = new RemoteHost(s, h, "sweep").sweepCommand();
        assertTrue(cmd.endsWith("rm -rf '/opt/firewatch/runs/1f2e-'*"), cmd);
        assertTrue(cmd.startsWith("pkill -KILL -f 'ignite\\.firewatch\\.run=1f2e-'"), cmd);
    }

    /** */
    @Test void theAnswerIsTheLastLine() throws Exception {
        assertEquals(1, RemoteHost.json("[0.012s][warning][os] a line of the JVM\nWelcome {to} [the host]\n"
            + "{\"a\":1}").path("a").asInt());
        assertEquals(2, RemoteHost.json("[{\"a\":1},{\"a\":2}]").size());
    }

    /** */
    @Test void aHungCommandEndsWithTheTimeout() {
        long t0 = System.nanoTime();
        IOException e = assertThrows(IOException.class,
            () -> RemoteHost.run(List.of("sleep", "30"), false, Duration.ofSeconds(1)));
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 10);
    }

    /** */
    @Test void aLoggedCommandShowsItsWordsHowItEndedAndWhatItSaid() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CommandLog log = new CommandLog(new PrintStream(buf, true, UTF_8));
        RemoteHost.ExitException e = assertThrows(RemoteHost.ExitException.class, () -> RemoteHost.run(
            List.of("bash", "-c", "echo said it; exit 3"), false, Duration.ofSeconds(20), log, "h1"));
        assertEquals(3, e.code);
        String out = buf.toString(UTF_8);
        assertTrue(out.contains("[check] h1 $ bash -c 'echo said it; exit 3'\n"), out);
        assertTrue(out.contains("[check] h1 → exit 3 in "), out);
        assertTrue(out.contains("[check] h1 | said it\n"), out);
    }

    /** */
    @Test void aCommandIsLoggedAsAShellTakesIt() {
        assertEquals("ssh -p 22 -o BatchMode=yes u@10.0.0.1 'echo $HOME' 'it'\\''s'",
            CommandLog.shell(List.of("ssh", "-p", "22", "-o", "BatchMode=yes", "u@10.0.0.1", "echo $HOME", "it's")));
    }

    /** */
    @Test void onlySshThatFailedByItselfWithAHostToTalkToIsExplained() {
        List<String> ssh = List.of("ssh", "-n", "u@h", "true");
        assertTrue(RemoteHost.worthExplaining(ssh, exit(255, "Host key verification failed.")));
        assertTrue(RemoteHost.worthExplaining(ssh, exit(255, "u@h: Permission denied (publickey).")));
        assertFalse(RemoteHost.worthExplaining(ssh, exit(255, "ssh: connect to host h port 22: Connection refused")),
            "it says it all, -v would wait as long once more");
        assertFalse(RemoteHost.worthExplaining(ssh, exit(255, "ssh: connect to host h port 22: Operation timed out")));
        assertFalse(RemoteHost.worthExplaining(ssh, exit(3, "pkill is missing")), "the remote command failed, not ssh");
        assertFalse(RemoteHost.worthExplaining(List.of("rsync", "-a"), exit(255, "Host key verification failed.")));
    }

    /** */
    private static RemoteHost.ExitException exit(int code, String output) {
        return new RemoteHost.ExitException("ssh exited with code " + code + ": " + output, code);
    }

    /** */
    @Test void sshIsExplainedWithV() {
        Assumptions.assumeTrue(sshInstalled(), "ssh is required");
        StandConfig.Host h = host("127.0.0.1", StandConfig.SERVERS);
        h.port = 1; // nothing listens there
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        RemoteHost r = new RemoteHost(stand(h), h, "t").log(new CommandLog(new PrintStream(buf, true, UTF_8)));
        assertThrows(IOException.class, () -> r.installProbe(Path.of("harness.jar")));
        r.explain(r.ssh("true"));
        String out = buf.toString(UTF_8);
        assertTrue(out.contains("[check] 127.0.0.1 $ ssh -o BatchMode=yes"), out);
        assertTrue(out.contains("[check] 127.0.0.1 → exit 255"), out);
        assertFalse(out.contains("ssh -v -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 "
            + "-o ServerAliveCountMax=4 -p 1 -n bench@127.0.0.1 'mkdir"), "a refused connection is not repeated");
        assertTrue(out.contains("[check] 127.0.0.1 $ ssh -v -o BatchMode=yes"), out);
        assertTrue(out.contains("| debug1:"), out);
    }

    /** */
    static boolean sshInstalled() {
        try {
            return new ProcessBuilder("ssh", "-V").redirectErrorStream(true).start().waitFor() == 0;
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    /** */
    @Test void aCommandThatLeavesAChildBehindStillReturns() throws Exception {
        assertEquals("up", RemoteHost.run(List.of("bash", "-c", "sleep 30 & echo up"), false, Duration.ofSeconds(20)));
    }

    /** */
    @Test void thePhaseLivesUnderTheWorkDirectoryOfTheHost() {
        StandConfig.Host h = host("a", StandConfig.SERVERS);
        assertEquals("/opt/firewatch/runs/job1-A1",
            new RemoteHost(stand(h), h, "job1-A1").dir(Path.of("/data/runs/1/A1")));
    }

    /** */
    @Test void sshNeverAsksAndUsesTheFilesItIsGiven() {
        StandConfig.Host h = host("a", StandConfig.SERVERS);
        h.port = 2222;
        StandConfig s = stand(h);
        List<String> plain = new RemoteHost(s, h, "r").ssh("true");
        assertTrue(plain.contains("BatchMode=yes"));
        assertEquals("2222", plain.get(plain.indexOf("-p") + 1));
        assertFalse(plain.contains("-i"), "ssh finds the key itself");

        s.sshKey = "/keys/id";
        s.sshKnownHosts = "/keys/known";
        List<String> withFiles = new RemoteHost(s, h, "r").ssh("true");
        assertEquals("/keys/id", withFiles.get(withFiles.indexOf("-i") + 1));
        assertTrue(withFiles.contains("UserKnownHostsFile=/keys/known"));
        assertTrue(withFiles.contains("StrictHostKeyChecking=yes"));
        assertEquals("true", withFiles.get(withFiles.size() - 1));
    }

    /** */
    @Test void rsyncGoesThroughTheSameSsh() {
        StandConfig.Host h = host("a", StandConfig.SERVERS);
        h.port = 2222;
        List<String> c = new RemoteHost(stand(h), h, "r").rsync("src", "dst");
        assertEquals("-e", c.get(2));
        assertTrue(c.get(3).contains("'-p' '2222'"), c.get(3));
        assertEquals(List.of("src", "dst"), c.subList(4, 6));
    }

    /** */
    @Test void aQuotedWordStaysOneWord() {
        assertEquals("'a b'", RemoteHost.quote("a b"));
        assertEquals("'it'\\''s'", RemoteHost.quote("it's"));
        assertEquals("'$(rm -rf /)'", RemoteHost.quote("$(rm -rf /)"));
    }

    /** */
    @Test void pkillDoesNotMatchTheShellThatRunsIt() {
        String c = RemoteHost.stopCommand("TERM", "job1-A1-node-0( |$)");
        assertEquals("pkill -TERM -f 'ignite\\.firewatch\\.run=job1-A1-node-0( |$)'", c);
        assertFalse(picks(c, c), "the literal text must not fit its own pattern");
    }

    /** */
    @Test void pkillPicksTheJvmsOfTheRun() {
        JvmProcess.Spec s = new JvmProcess.Spec();
        s.runId = "job1-A1";
        s.label = "node-0";
        String line = String.join(" ", Jvm.systemProps(JvmProcess.marker(s))) + " -cp harness.jar";
        assertTrue(picks(RemoteHost.stopCommand("TERM", "job1-A1-node-0( |$)"), line), line);
        assertTrue(picks(RemoteHost.stopCommand("KILL", "job1-"), line), "the cleanup of the run");
        assertFalse(picks(RemoteHost.stopCommand("TERM", "job1-A1-node-1( |$)"), line), "another node stays");
    }

    /** Whether the pkill of {@code cmd}, which takes the quoted word as a regex, picks the process of {@code line}. */
    private static boolean picks(String cmd, String line) {
        return Pattern.compile(cmd.substring(cmd.indexOf('\'') + 1, cmd.lastIndexOf('\''))).matcher(line).find();
    }

    /** */
    @Test void nodesGoToServersInOrderAndWrapAround() {
        StandConfig s = stand(host("a", StandConfig.SERVERS), host("l", StandConfig.LOADERS),
            host("b", StandConfig.SERVERS));
        org.apache.ignite.firewatch.harness.api.Bench b = Catalog.get("tx-thin-t16-3srv").bench;
        Placement p = Placement.of(b, s, "r");
        assertEquals(List.of("a", "b", "a"), p.servers.stream().map(Host::address).toList());
        assertEquals("l", p.loaders.get(0).address());
        assertEquals(3, p.hosts().size(), "every host once");
        assertEquals("a:47500,b:47501,a:47502", p.discoveryAddresses());
        assertEquals("a:10800,b:10801,a:10802", p.thinAddresses());
    }

    /** */
    @Test void aLocalPhaseStaysOnThisMachine() {
        Placement p = Placement.of(Catalog.get("tx-thin-t16-3srv").bench, null, "r");
        assertEquals(1, p.hosts().size());
        assertEquals("127.0.0.1:47500,127.0.0.1:47501,127.0.0.1:47502", p.discoveryAddresses());
    }
}
