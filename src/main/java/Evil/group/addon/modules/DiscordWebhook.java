package Evil.group.addon.modules;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utility class for sending Discord webhook messages
 */
public class DiscordWebhook {
    private final String webhookUrl;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        // Run in a separate thread to avoid blocking the game
        new Thread(() -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "DotterESP-Webhook");
                connection.setDoOutput(true);

                // Create JSON payload
                String jsonPayload = String.format("{\"content\": \"%s\"}", 
                    content.replace("\"", "\\\"").replace("\n", "\\n"));

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    System.err.println("[DotterESP] Discord webhook failed with code: " + responseCode);
                }

                connection.disconnect();
            } catch (Exception e) {
                System.err.println("[DotterESP] Failed to send Discord webhook: " + e.getMessage());
            }
        }, "DotterESP-Webhook-Thread").start();
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
