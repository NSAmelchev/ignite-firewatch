package org.apache.ignite.firewatch.controller;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sends a short message about a finished job to Telegram and/or a generic JSON webhook (Slack-compatible
 * {@code {"text": ...}}), according to the {@code notify.*} settings. Delivery is asynchronous and best-effort.
 */
public final class Notifier {
    /** */
    public static final String MODE = "notify.mode";

    /** */
    public static final String TELEGRAM_TOKEN = "notify.telegramToken";

    /** */
    public static final String TELEGRAM_CHAT = "notify.telegramChatId";

    /** */
    public static final String WEBHOOK_URL = "notify.webhookUrl";

    /** */
    public static final String BASE_URL = "notify.baseUrl";

    /** */
    private final Db db;

    /** */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notifier");
        t.setDaemon(true);

        return t;
    });

    /** */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Replaceable transport (tests): (url, body) → delivers one HTTP POST. */
    private volatile BiConsumer<String, String> transport = this::post;

    /** */
    private volatile String lastResult;

    /**
     * Creates the notifier.
     *
     * @param db Database with the notification settings.
     */
    public Notifier(Db db) {
        this.db = db;
    }

    /** */
    void transport(BiConsumer<String, String> t) {
        transport = t;
    }

    /**
     * Returns the outcome of the last send.
     *
     * @return What the last send attempted, or {@code null} if nothing was sent.
     */
    public String lastResult() {
        return lastResult;
    }

    /**
     * A bisection ended or stopped: one message whatever the mode, unless notifications are off.
     *
     * @param b Ended bisection.
     */
    public void bisectEnded(Bisect b) {
        if ("off".equals(db.setting(MODE, "off")))
            return;

        String base = db.setting(BASE_URL, "");
        send("Firewatch: bisect #" + b.id + " " + b.status.toUpperCase() + " · " + b.scenario + ", " + b.metric
            + (b.reason == null ? "" : " · " + b.reason)
            + (base == null || base.isBlank() ? "" : " · " + base.replaceAll("/+$", "") + "/#/bisects/" + b.id));
    }

    /**
     * No job of a comparison is left in the queue: one message whatever the mode, unless notifications are off.
     *
     * @param c Ended comparison.
     */
    public void comparisonEnded(Comparison c) {
        if ("off".equals(db.setting(MODE, "off")))
            return;

        String base = db.setting(BASE_URL, "");
        send("Firewatch: comparison #" + c.id + " " + c.status.toUpperCase() + " · " + c.label + " · " + c.measured
            + " of " + c.total + " scenarios: " + c.regressions + " regressions, " + c.improvements
            + " improvements, " + c.noChange + " no change"
            + (base == null || base.isBlank() ? "" : " · " + base.replaceAll("/+$", "") + "/#/comparisons/" + c.id));
    }

    /**
     * Decides from the settings and the job outcome whether to notify, and sends if so.
     *
     * @param job Finished job.
     */
    public void jobFinished(Job job) {
        String mode = db.setting(MODE, "off");

        if (!shouldNotify(mode, job))
            return;

        send(message(job, db.setting(BASE_URL, "")));
    }

    /** {@code off}; {@code regression}: regressions and failed auto jobs; {@code all}: every finished job. */
    static boolean shouldNotify(String mode, Job job) {
        if (mode == null || mode.equals("off"))
            return false;

        if (JobService.SRC_BISECT.equals(job.source) || job.comparisonId != null)
            return false; // bisections and comparisons report once, when they end

        if (job.plugin != null)
            return false; // a plugin is looked at on its page, its pairs are not the branch

        if (mode.equals("all"))
            return true;

        boolean regression = Stats.REGRESSION.equals(job.verdict);
        boolean brokenAuto = JobService.SRC_AUTO.equals(job.source) && Job.FAILED.equals(job.status);

        return regression || brokenAuto;
    }

    /** */
    static String message(Job job, String baseUrl) {
        StringBuilder sb = new StringBuilder("Firewatch: ");

        if (Job.DONE.equals(job.status))
            sb.append(job.verdict == null ? "done" : job.verdict.toUpperCase());
        else
            sb.append(job.status.toUpperCase());

        sb.append(" · ").append(job.label).append(" [").append(job.scenario).append("]");

        if (job.comparison != null) {
            JsonNode metrics = job.comparison.path("metrics");

            for (String k : Stats.VERDICT_METRICS) {
                JsonNode m = metrics.get(k);

                if (m != null && m.hasNonNull("deltaPct"))
                    sb.append(" · ").append(m.path("title").asText(k)).append(' ')
                        .append(String.format("%+.1f%%", m.get("deltaPct").asDouble()))
                        .append(Stats.NO_CHANGE.equals(m.path("verdict").asText()) ? ""
                            : " (" + m.path("verdict").asText() + ")");
            }
        }

        if (job.error != null)
            sb.append(" · ").append(job.error.length() > 160 ? job.error.substring(0, 160) + "…" : job.error);

        if (baseUrl != null && !baseUrl.isBlank())
            sb.append(" · ").append(baseUrl.replaceAll("/+$", "")).append("/#/jobs/").append(job.id);

        return sb.toString();
    }

    /**
     * Sends {@code text} to every configured channel; returns what was attempted (for the UI test button).
     *
     * @param text Message text.
     * @return Channels that were attempted.
     */
    public String send(String text) {
        String token = db.setting(TELEGRAM_TOKEN, "").trim();
        String chat = db.setting(TELEGRAM_CHAT, "").trim();
        String hook = db.setting(WEBHOOK_URL, "").trim();
        StringBuilder attempted = new StringBuilder();

        if (!token.isEmpty() && !chat.isEmpty()) {
            attempted.append("telegram ");
            String url = "https://api.telegram.org/bot" + token + "/sendMessage";
            String body = "chat_id=" + URLEncoder.encode(chat, StandardCharsets.UTF_8)
                + "&disable_web_page_preview=true&text="
                + URLEncoder.encode(text, StandardCharsets.UTF_8);
            pool.execute(() -> transport.accept(url, body));
        }

        if (!hook.isEmpty()) {
            attempted.append("webhook ");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            String body = Json.write(payload);
            pool.execute(() -> transport.accept(hook, body));
        }

        if (attempted.length() == 0)
            return "no channel configured";

        return attempted.toString().trim();
    }

    /** */
    private void post(String url, String body) {
        boolean form = body.startsWith("chat_id=");

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String target = url.contains("api.telegram.org") ? "telegram" : "webhook";

            if (resp.statusCode() / 100 == 2)
                lastResult = Db.now() + " " + target + " ok";
            else {
                lastResult = Db.now() + " " + target + " HTTP " + resp.statusCode();
                Log.warn("notify: " + target + " answered HTTP " + resp.statusCode() + ": "
                    + Proc.tail(resp.body(), 2).trim());
            }
        }
        catch (Exception e) {
            lastResult = Db.now() + " failed: " + e;
            Log.warn("notify: delivery failed: " + e);
        }
    }

    /** */
    public void shutdown() {
        pool.shutdown();
    }
}
