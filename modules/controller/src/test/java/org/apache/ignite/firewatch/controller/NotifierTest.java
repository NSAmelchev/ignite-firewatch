package org.apache.ignite.firewatch.controller;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Notifier}. */
class NotifierTest {
    /** */
    @TempDir Path tmp;

    /** */
    private static Job job(String status, String verdict, String source) {
        Job j = new Job();
        j.id = 42;
        j.type = Job.ABBA;
        j.source = source;
        j.status = status;
        j.verdict = verdict;
        j.label = "0ab9cc9645 vs 6fb70e4c2f";
        j.scenario = "putget";
        j.comparison = Json.tree("{\"metrics\":{\"throughput\":{\"title\":\"Throughput\",\"deltaPct\":-4.25,"
            + "\"verdict\":\"regression\"},"
            + "\"p99Us\":{\"title\":\"Latency p99\",\"deltaPct\":1.0,\"verdict\":\"no-change\"}}}");

        return j;
    }

    /** */
    @Test void decisionFollowsTheMode() {
        assertFalse(Notifier.shouldNotify("off", job(Job.DONE, Stats.REGRESSION, "auto")));
        assertTrue(Notifier.shouldNotify("regression", job(Job.DONE, Stats.REGRESSION, "manual")));
        assertFalse(Notifier.shouldNotify("regression", job(Job.DONE, Stats.NO_CHANGE, "auto")));
        assertTrue(Notifier.shouldNotify("regression", job(Job.FAILED, null, "auto")),
            "a broken auto job is worth a message");
        assertFalse(Notifier.shouldNotify("regression", job(Job.FAILED, null, "manual")));
        assertTrue(Notifier.shouldNotify("all", job(Job.DONE, Stats.NO_CHANGE, "auto")));
    }

    /** */
    @Test void messageIsCompactAndLinks() {
        String m = Notifier.message(job(Job.DONE, Stats.REGRESSION, "auto"), "http://stand:8090/");
        assertEquals("Firewatch: REGRESSION · 0ab9cc9645 vs 6fb70e4c2f [putget] · "
            + "Throughput -4.3% (regression) · Latency p99 +1.0%"
            + " · http://stand:8090/#/jobs/42", m);
        Job failed = job(Job.FAILED, null, "auto");
        failed.error = "build of 0ab9cc9645 failed: mvn install failed with exit code 1";
        assertTrue(Notifier.message(failed, "").startsWith("Firewatch: FAILED · "));
        assertTrue(Notifier.message(failed, "").contains("mvn install failed"));
        assertFalse(Notifier.message(failed, "").contains("#/jobs"), "no link without a base URL");
    }

    /** */
    @Test void sendsToConfiguredChannelsOnly() throws Exception {
        try (Db db = Db.open(tmp.resolve("t.db"))) {
            Notifier n = new Notifier(db);
            List<String> calls = new ArrayList<>();
            n.transport((url, body) -> {
                synchronized (calls) {
                    calls.add(url + " | " + body);
                }
            });
            assertEquals("no channel configured", n.send("hi"));
            db.setSetting(Notifier.TELEGRAM_TOKEN, "123:ABC");
            db.setSetting(Notifier.TELEGRAM_CHAT, "-100");
            db.setSetting(Notifier.WEBHOOK_URL, "https://hooks.example/x");
            assertEquals("telegram webhook", n.send("hello world"));
            n.shutdown();
            long deadline = System.currentTimeMillis() + 5000;

            while (System.currentTimeMillis() < deadline) {
                synchronized (calls) {
                    if (calls.size() == 2)
                        break;
                }

                Thread.sleep(20);
            }

            synchronized (calls) {
                assertEquals(2, calls.size(), calls.toString());
                assertTrue(calls.get(0).startsWith("https://api.telegram.org/bot123:ABC/sendMessage | chat_id=-100&"), calls.get(0));
                assertTrue(calls.get(0).endsWith("text=hello+world"), calls.get(0));
                assertEquals("https://hooks.example/x | {\"text\":\"hello world\"}", calls.get(1));
            }
        }
    }
}
