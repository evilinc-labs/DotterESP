package Evil.group.addon.hud;

import Evil.group.addon.AntiDotterAddon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HUD element that displays the total AutoBuilder builds completed.
 * Reads from the same stats file as AutoBuilder module.
 */
public class BuildCounterHud extends HudElement {
    public static final HudElementInfo<BuildCounterHud> INFO = new HudElementInfo<>(
        AntiDotterAddon.HUD_GROUP, 
        "build-counter", 
        "Shows total AutoBuilder builds completed.", 
        BuildCounterHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Color of the text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color of the counter value.")
        .defaultValue(new SettingColor(138, 43, 226))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show background behind the HUD.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color of the background.")
        .defaultValue(new SettingColor(0, 0, 0, 128))
        .visible(showBackground::get)
        .build()
    );

    private static final Gson GSON = new GsonBuilder().create();
    private static final String PATTERN_DIR = "Evil";
    private static final String COUNTER_FILE = "autoBuilderStats.json";

    private int cachedCount = 0;
    private long lastUpdate = 0;
    private static final long UPDATE_INTERVAL = 1000; // Update every second

    public BuildCounterHud() {
        super(INFO);
    }

    private static class AutoBuilderStatsFile {
        int version = 1;
        int totalBuildsCompleted = 0;
    }

    private int loadBuildCount() {
        try {
            Path file = FabricLoader.getInstance().getConfigDir()
                .resolve(PATTERN_DIR)
                .resolve(COUNTER_FILE);
            
            if (!Files.exists(file)) return 0;
            
            String json = Files.readString(file, StandardCharsets.UTF_8);
            AutoBuilderStatsFile data = GSON.fromJson(json, AutoBuilderStatsFile.class);
            return data != null ? data.totalBuildsCompleted : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        // Update count periodically
        long now = System.currentTimeMillis();
        if (now - lastUpdate > UPDATE_INTERVAL) {
            cachedCount = loadBuildCount();
            lastUpdate = now;
        }

        String label = "Builds: ";
        String value = String.valueOf(cachedCount);
        
        double labelWidth = renderer.textWidth(label, true);
        double valueWidth = renderer.textWidth(value, true);
        double totalWidth = labelWidth + valueWidth;
        double height = renderer.textHeight(true);
        
        // Add padding
        double padding = 4;
        setSize(totalWidth + padding * 2, height + padding * 2);

        // Render background
        if (showBackground.get()) {
            renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());
        }

        // Render text
        renderer.text(label, x + padding, y + padding, textColor.get(), true);
        renderer.text(value, x + padding + labelWidth, y + padding, valueColor.get(), true);
    }
}
