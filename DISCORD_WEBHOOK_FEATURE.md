# Discord Webhook Feature

## Overview
This feature adds Discord webhook notifications when a "Dotter" player (player name starting with ".") is detected.

## Configuration

### Settings
The following settings are available in the DotterEsp module:

1. **discord-webhook-enabled** (Boolean, default: false)
   - Enable or disable Discord webhook notifications
   - When disabled, no webhooks will be sent

2. **discord-webhook-url** (String, default: "")
   - The Discord webhook URL to send notifications to
   - Must be a valid Discord webhook URL starting with:
     - `https://discord.com/api/webhooks/`
     - `https://discordapp.com/api/webhooks/`
   - Only visible when webhook is enabled

## How to Use

1. Create a Discord webhook in your Discord server:
   - Go to Server Settings → Integrations → Webhooks
   - Click "New Webhook"
   - Copy the webhook URL

2. In Minecraft, open the Meteor Client GUI and navigate to the DotterEsp module

3. Enable "discord-webhook-enabled"

4. Paste your Discord webhook URL into the "discord-webhook-url" field

5. When a player with a name starting with "." is detected, you'll receive a Discord notification

## Notification Format

The Discord webhook message includes:
- **Player name**: The detected player's username
- **Coordinates**: X, Y, Z position where the player was spotted
- **Timestamp**: Date and time when the player was detected (format: yyyy-MM-dd HH:mm:ss)

Example message:
```
🎯 Dotter Player Detected
Player: .BedrockPlayer123
Coordinates: X: 1234, Y: 64, Z: -5678
Time: 2025-12-13 10:15:30
```

## Features

- **Non-blocking**: Webhook requests are sent in a separate thread to avoid lag
- **Duplicate prevention**: Each player is notified only once per appearance
- **Automatic cleanup**: When a player leaves the visible range, they're removed from the tracking list
- **Validation**: The webhook URL is validated before sending requests
- **Error handling**: Failed webhook requests are logged to the console

## Technical Details

### Files Modified/Added
- `src/main/java/Evil/group/addon/modules/DiscordWebhook.java` (new)
  - Utility class for sending Discord webhook messages
  - Handles HTTP POST requests to Discord webhook API
  - Formats messages with player information

- `src/main/java/Evil/group/addon/modules/DotterEsp.java` (modified)
  - Added webhook settings (enable/disable, URL)
  - Added webhook notification logic in the tick event handler
  - Tracks notified players to prevent duplicates

### Dependencies
- Uses standard Java libraries (java.net, java.io)
- No additional external dependencies required

## Notes

- The feature respects the existing "Bedrock player detection" logic (name starts with ".")
- Webhook requests timeout independently and won't affect game performance
- Failed webhook requests are logged but don't prevent the mod from functioning

### Known Issues / Build Notes
- This implementation assumes `StringSetting` exists in the Meteor Client API (follows the pattern of `BoolSetting`, `DoubleSetting`, etc.)
- If `StringSetting` is not available, alternative approaches:
  1. Use a different setting type if available (e.g., `TextSetting`)
  2. Implement a custom string setting
  3. Use a configuration file approach
- The repository has a pre-existing build issue with fabric-loom 1.8-SNAPSHOT that needs to be resolved independently
