package Evil.group.addon.modules;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class for sending Discord webhook messages
 */
public class DiscordWebhook {
    private final String webhookUrl;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    // Thread pool for webhook requests to prevent thread exhaustion
    private static final ExecutorService WEBHOOK_EXECUTOR = 
        Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "DotterESP-Webhook-Thread");
            t.setDaemon(true);
            return t;
        });

    public DiscordWebhook(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /**
     * Send a notification about a detected player
     * @param playerName The name of the player
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void sendPlayerDetection(String playerName, int x, int y, int z) {
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

    /**
     * Send a message to the Discord webhook
     * @param content The message content
     */
    private void sendMessage(String content) {
        // Use thread pool to avoid blocking the game and prevent thread exhaustion
        WEBHOOK_EXECUTOR.submit(() -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "DotterESP-Webhook");
                connection.setDoOutput(true);

                // Create JSON payload with proper escaping
                String escapedContent = escapeJson(content);
                String jsonPayload = String.format("{\"content\": \"%s\"}", escapedContent);

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    String errorMsg = connection.getResponseMessage();
                    System.err.println("[DotterESP] Discord webhook failed with code " 
                        + responseCode + ": " + errorMsg);
                }

                connection.disconnect();
            } catch (Exception e) {
                System.err.println("[DotterESP] Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }

    /**
     * Escape special characters for JSON strings
     * @param str The string to escape
     * @return The escaped string safe for JSON
     */
    private static String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char ch = str.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    // Control characters
                    if (ch < ' ') {
                        String hex = Integer.toHexString(ch);
                        sb.append("\\u");
                        for (int j = 0; j < 4 - hex.length(); j++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Validate if a webhook URL is properly formatted
     * @param url The webhook URL to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidWebhookUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        return url.startsWith("https://discord.com/api/webhooks/") 
            || url.startsWith("https://discordapp.com/api/webhooks/");
    }
}
