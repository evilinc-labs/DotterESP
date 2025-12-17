package Evil.group.addon.utils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class DiscordWebhook {
    private final String webhookUrl;
    private final boolean enabled;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Allowlist to prevent SSRF / arbitrary exfil endpoints
    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "discord.com",
        "discordapp.com",
        "canary.discord.com",
        "ptb.discord.com"
    );

    // Keep it small; webhook sends are not high throughput
    private static final ExecutorService WEBHOOK_EXECUTOR =
        new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100), // bounded queue prevents unbounded memory growth
            r -> {
                Thread t = new Thread(r, "DotterESP-Webhook-Thread");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy() // drop if flooded instead of lagging the game
        );

    // Simple global backoff to respect rate limits
    private static final AtomicLong NEXT_ALLOWED_SEND_MS = new AtomicLong(0);

    public DiscordWebhook(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.enabled = isValidWebhookUrl(webhookUrl);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void sendPlayerDetection(String playerName, int x, int y, int z) {
        if (!enabled) return;

        String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        String content = String.format(
            "**🎯 Dotter Player Detected**\n" +
            "**Player:** %s\n" +
            "**Coordinates:** X: %d, Y: %d, Z: %d\n" +
            "**Time:** %s",
            playerName, x, y, z, timestamp
        );

        sendMessage(content);
    }

    private void sendMessage(String content) {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        long nextOk = NEXT_ALLOWED_SEND_MS.get();
        if (now < nextOk) return; // still in backoff window

        WEBHOOK_EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                // Re-validate at send-time too (defense in depth)
                URI uri = URI.create(webhookUrl);
                if (!isAllowedDiscordWebhookUri(uri)) return;

                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(6000);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("User-Agent", "DotterESP-Webhook");
                connection.setDoOutput(true);

                String escapedContent = escapeJson(content);
                byte[] body = ("{\"content\":\"" + escapedContent + "\"}").getBytes(StandardCharsets.UTF_8);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int code = connection.getResponseCode();

                // Discord webhooks commonly return 204 No Content on success
                if (code == 429) {
                    // Rate limited: respect Retry-After (ms) if present
                    long retryMs = parseRetryAfterMs(connection);
                    long backoffUntil = System.currentTimeMillis() + Math.max(1000, retryMs);
                    NEXT_ALLOWED_SEND_MS.set(backoffUntil);
                    // logDebug("Webhook rate limited; backing off " + (backoffUntil - System.currentTimeMillis()) + "ms");
                    drain(connection);
                    return;
                }

                if (code < 200 || code >= 300) {
                    // logWarn("Discord webhook failed: HTTP " + code + " " + connection.getResponseMessage());
                    drain(connection);
                } else {
                    drain(connection);
                }

            } catch (Exception e) {
                // logWarn("Failed to send Discord webhook: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static void drain(HttpURLConnection connection) {
        try (InputStream is = (connection.getErrorStream() != null) ? connection.getErrorStream() : connection.getInputStream()) {
            if (is == null) return;
            is.readAllBytes(); // consume to allow keep-alive reuse internally
        } catch (Exception ignored) {}
    }

    private static long parseRetryAfterMs(HttpURLConnection connection) {
        try {
            String ra = connection.getHeaderField("Retry-After");
            if (ra == null) return 0;
            double seconds = Double.parseDouble(ra.trim());
            return (long) (seconds * 1000);
        } catch (Exception ignored) {
            return 0;
        }
    }


    private static boolean isAllowedDiscordWebhookUri(URI uri) {
        if (uri == null) return false;
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;

        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(host)) return false;

        String path = uri.getPath();
        return path != null && path.startsWith("/api/webhooks/");
    }

    public static boolean isValidWebhookUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            return isAllowedDiscordWebhookUri(URI.create(url.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    // Call this from addon disable/unload if you want a clean shutdown in dev
    public static void shutdown() {
        WEBHOOK_EXECUTOR.shutdownNow();
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder(str.length() + 16);
        for (int i = 0; i < str.length(); i++) {
            char ch = str.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < ' ') {
                        String hex = Integer.toHexString(ch);
                        sb.append("\\u");
                        for (int j = hex.length(); j < 4; j++) sb.append('0');
                        sb.append(hex);
                    } else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
