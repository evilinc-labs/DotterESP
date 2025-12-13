package Evil.group.addon.modules;

import Evil.group.addon.DotterESPAddon;
import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class DotterEsp extends Module {
    // Settings 
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer + vertical line color.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    public enum AimPoint { Head, Body, Feet }

    private final Setting<AimPoint> aimPoint = sgGeneral.add(new EnumSetting.Builder<AimPoint>()
        .name("aim-point")
        .description("Where the tracer aims on the target entity.")
        .defaultValue(AimPoint.Body)
        .build()
    );

    private final Setting<Boolean> drawBoxes = sgGeneral.add(new BoolSetting.Builder()
        .name("box-outline")
        .description("Draw a 3D box outline around traced targets.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> boxColor = sgGeneral.add(new ColorSetting.Builder()
        .name("box-color")
        .description("Box outline color.")
        .defaultValue(new SettingColor(57, 255, 20, 255)) // neon green
        .visible(drawBoxes::get)
        .build()
    );

    // Debug
    private final Setting<Boolean> debugNearestEntities = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-nearest-entities")
        .description("Draw tracers to the nearest 3 entities (debug). Ignores items + projectiles.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> debugMaxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("debug-max-distance")
        .description("Max distance for debug tracers (nearest 3 entities within this range).")
        .defaultValue(128.0)
        .min(0.0)
        .sliderMin(0.0)
        .sliderMax(256.0)
        .visible(debugNearestEntities::get)
        .build()
    );

    // Notifications
    private final Setting<Boolean> notifyBedrockSeen = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-bedrock-seen")
        .description("Announce Bedrock players when they are client-visible (loaded player list).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyDebugEnterLeave = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-debug-enter-leave")
        .description("Announce debug entities entering/leaving the debug draw set (nearest 3 within range).")
        .defaultValue(true)
        .visible(debugNearestEntities::get)
        .build()
    );

    // Discord Webhook
    private final Setting<Boolean> discordWebhookEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("discord-webhook-enabled")
        .description("Send Discord webhook notifications when Bedrock players (starting with '.') are detected.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> discordWebhookUrl = sgGeneral.add(new StringSetting.Builder()
        .name("discord-webhook-url")
        .description("Discord webhook URL for notifications.")
        .defaultValue("")
        .visible(discordWebhookEnabled::get)
        .build()
    );

    public DotterEsp() {
        super(
            DotterESPAddon.CATEGORY,
            "DotterEsp",
            "Draws tracers to Bedrock players (heuristic: name starts with '.')."
        );
    }

    // Bedrock announce once per appearance
    private final Set<UUID> announcedBedrock = new HashSet<>();
    
    // Track which players were sent to Discord webhook to avoid duplicates
    private final Set<UUID> webhookNotifiedPlayers = new HashSet<>();

    // Debug: track draw set + cache info for leave messages
    private final Set<UUID> debugPrevDrawSet = new HashSet<>();
    private final List<Entity> debugDrawList = new ArrayList<>(3);
    private final Map<UUID, DebugInfo> debugInfoCache = new HashMap<>();

    private static class DebugInfo {
        final String type;
        final BlockPos pos;

        DebugInfo(String type, BlockPos pos) {
            this.type = type;
            this.pos = pos;
        }
    }

    // 
    // Reflection compat (cached)
    // 
    private static boolean REFLECT_READY = false;

    private static Field RENDERUTILS_CENTER_FIELD;  // RenderUtils.center (preferred)
    private static Field EVENT_TICKDELTA_FIELD;     // Render3DEvent.tickDelta (if exists)

    private static Method ENTITY_GET_LERPED_POS;    // Entity.getLerpedPos(float) (preferred)

    // Optional: lastX/lastY/lastZ fallback (only used via reflection)
    private static Field ENTITY_LAST_X;
    private static Field ENTITY_LAST_Y;
    private static Field ENTITY_LAST_Z;

    // Depth test compat (1.21.4 has RenderSystem.disableDepthTest; 1.21.5+ may only have GlStateManager._disableDepthTest)
    private static boolean DEPTH_REFLECT_READY = false;
    private static Method RS_DISABLE_DEPTH;
    private static Method RS_ENABLE_DEPTH;
    private static Method GSM_DISABLE_DEPTH;
    private static Method GSM_ENABLE_DEPTH;

    private static void initReflection() {
        if (REFLECT_READY) return;
        REFLECT_READY = true;

        // RenderUtils.center
        try {
            RENDERUTILS_CENTER_FIELD = RenderUtils.class.getField("center");
        } catch (Throwable ignored) {
            RENDERUTILS_CENTER_FIELD = null;
        }

        // Render3DEvent.tickDelta
        try {
            EVENT_TICKDELTA_FIELD = Render3DEvent.class.getField("tickDelta");
        } catch (Throwable ignored) {
            EVENT_TICKDELTA_FIELD = null;
        }

        // Entity.getLerpedPos(float)
        try {
            ENTITY_GET_LERPED_POS = Entity.class.getMethod("getLerpedPos", float.class);
        } catch (Throwable ignored) {
            ENTITY_GET_LERPED_POS = null;
        }

        // Optional: lastX/lastY/lastZ fallback if accessible in any target mappings
        try {
            ENTITY_LAST_X = Entity.class.getDeclaredField("lastX");
            ENTITY_LAST_X.setAccessible(true);
        } catch (Throwable ignored) {
            ENTITY_LAST_X = null;
        }

        try {
            ENTITY_LAST_Y = Entity.class.getDeclaredField("lastY");
            ENTITY_LAST_Y.setAccessible(true);
        } catch (Throwable ignored) {
            ENTITY_LAST_Y = null;
        }

        try {
            ENTITY_LAST_Z = Entity.class.getDeclaredField("lastZ");
            ENTITY_LAST_Z.setAccessible(true);
        } catch (Throwable ignored) {
            ENTITY_LAST_Z = null;
        }
    }

    private static void initDepthReflection() {
        if (DEPTH_REFLECT_READY) return;
        DEPTH_REFLECT_READY = true;

        // 1.21.4 path
        try {
            RS_DISABLE_DEPTH = RenderSystem.class.getMethod("disableDepthTest");
            RS_ENABLE_DEPTH = RenderSystem.class.getMethod("enableDepthTest");
            return;
        } catch (Throwable ignored) {}

        // 1.21.5+ path
        try {
            Class<?> gsm = Class.forName("com.mojang.blaze3d.systems.GlStateManager");
            GSM_DISABLE_DEPTH = gsm.getDeclaredMethod("_disableDepthTest");
            GSM_ENABLE_DEPTH = gsm.getDeclaredMethod("_enableDepthTest");
        } catch (Throwable ignored) {}
    }

    private static void disableDepthCompat() {
        initDepthReflection();
        try {
            if (RS_DISABLE_DEPTH != null) RS_DISABLE_DEPTH.invoke(null);
            else if (GSM_DISABLE_DEPTH != null) GSM_DISABLE_DEPTH.invoke(null);
        } catch (Throwable ignored) {}
    }

    private static void enableDepthCompat() {
        initDepthReflection();
        try {
            if (RS_ENABLE_DEPTH != null) RS_ENABLE_DEPTH.invoke(null);
            else if (GSM_ENABLE_DEPTH != null) GSM_ENABLE_DEPTH.invoke(null);
        } catch (Throwable ignored) {}
    }

    private double tickDeltaCompat(Render3DEvent event) {
        initReflection();

        if (EVENT_TICKDELTA_FIELD != null) {
            try {
                Object v = EVENT_TICKDELTA_FIELD.get(event);
                if (v instanceof Double d) return d;
                if (v instanceof Float f) return f;
            } catch (Throwable ignored) {}
        }

        // Fallback: safe default
        return 1.0;
    }

    private Vec3d tracerStartCompat() {
        initReflection();

        // 1) Meteor Tracers origin: RenderUtils.center (crosshair center)
        if (RENDERUTILS_CENTER_FIELD != null) {
            try {
                Object v = RENDERUTILS_CENTER_FIELD.get(null);
                if (v instanceof Vec3d c) return c;
            } catch (Throwable ignored) {}
        }

        // 2) Fallback: camera pos 
        return mc.gameRenderer.getCamera().getPos();
    }

    private Vec3d lerpedPosCompat(Entity e, double tickDelta) {
        initReflection();

        // Preferred entity.getLerpedPos(float)
        if (ENTITY_GET_LERPED_POS != null) {
            try {
                Object v = ENTITY_GET_LERPED_POS.invoke(e, (float) tickDelta);
                if (v instanceof Vec3d p) return p;
            } catch (Throwable ignored) {}
        }

        // Fallback to manual interpolate using lastX/lastY/lastZ if accessible
        if (ENTITY_LAST_X != null && ENTITY_LAST_Y != null && ENTITY_LAST_Z != null) {
            try {
                double lx = ((Number) ENTITY_LAST_X.get(e)).doubleValue();
                double ly = ((Number) ENTITY_LAST_Y.get(e)).doubleValue();
                double lz = ((Number) ENTITY_LAST_Z.get(e)).doubleValue();

                double x = lx + (e.getX() - lx) * tickDelta;
                double y = ly + (e.getY() - ly) * tickDelta;
                double z = lz + (e.getZ() - lz) * tickDelta;

                return new Vec3d(x, y, z);
            } catch (Throwable ignored) {}
        }

        // Last resort
        return e.getPos();
    }

    // Bedrock heuristic 
    private boolean isBedrock(AbstractClientPlayerEntity p) {
        String name = p.getGameProfile().getName();
        return name != null && !name.isEmpty() && name.charAt(0) == '.';
    }

    private boolean shouldIgnoreForDebug(Entity e) {
        return (e instanceof ItemEntity) || (e instanceof ProjectileEntity);
    }

    private boolean withinDebugDistance(Entity e) {
        double max = debugMaxDistance.get();
        if (max <= 0) return false;
        double max2 = max * max;
        return e.squaredDistanceTo(mc.player) <= max2;
    }

    private String entityTypeName(Entity e) {
        return e.getType().getName().getString();
    }
    
    // Helper method to get currently visible Bedrock players
    private List<AbstractClientPlayerEntity> getCurrentBedrockPlayers() {
        List<AbstractClientPlayerEntity> bedrockPlayers = new ArrayList<>();
        if (mc.world == null || mc.player == null) {
            return bedrockPlayers;
        }
        
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (!isBedrock(p)) continue;
            bedrockPlayers.add(p);
        }
        
        return bedrockPlayers;
    }

    // client side chat with colored [DotterEsp]
    private void chatLocal(String msg) {
        MutableText t = Text.literal("[")
            .formatted(Formatting.GRAY)
            .append(Text.literal("DotterEsp").formatted(Formatting.RED))
            .append(Text.literal("] ").formatted(Formatting.GRAY))
            .append(Text.literal(msg).formatted(Formatting.GRAY));

        mc.inGameHud.getChatHud().addMessage(t);
    }

    // Box outline using 12 edges
    private void drawBoxOutline(Render3DEvent event, Box b, SettingColor color) {
        double minX = b.minX, minY = b.minY, minZ = b.minZ;
        double maxX = b.maxX, maxY = b.maxY, maxZ = b.maxZ;

        // bottom rectangle
        event.renderer.line(minX, minY, minZ, maxX, minY, minZ, color);
        event.renderer.line(maxX, minY, minZ, maxX, minY, maxZ, color);
        event.renderer.line(maxX, minY, maxZ, minX, minY, maxZ, color);
        event.renderer.line(minX, minY, maxZ, minX, minY, minZ, color);

        // top rectangle
        event.renderer.line(minX, maxY, minZ, maxX, maxY, minZ, color);
        event.renderer.line(maxX, maxY, minZ, maxX, maxY, maxZ, color);
        event.renderer.line(maxX, maxY, maxZ, minX, maxY, maxZ, color);
        event.renderer.line(minX, maxY, maxZ, minX, maxY, minZ, color);

        // vertical edges
        event.renderer.line(minX, minY, minZ, minX, maxY, minZ, color);
        event.renderer.line(maxX, minY, minZ, maxX, maxY, minZ, color);
        event.renderer.line(maxX, minY, maxZ, maxX, maxY, maxZ, color);
        event.renderer.line(minX, minY, maxZ, minX, maxY, maxZ, color);
    }

    // announcements + maintain debug nearest 3 list
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // Get Bedrock players once and use for both notifications
        List<AbstractClientPlayerEntity> bedrockPlayers = null;
        if (notifyBedrockSeen.get() || discordWebhookEnabled.get()) {
            bedrockPlayers = getCurrentBedrockPlayers();
        }

        // Bedrock announce (client side)
        if (notifyBedrockSeen.get() && bedrockPlayers != null) {
            Set<UUID> currentlyVisible = new HashSet<>();

            for (AbstractClientPlayerEntity p : bedrockPlayers) {
                UUID id = p.getUuid();
                currentlyVisible.add(id);

                if (announcedBedrock.add(id)) {
                    BlockPos bp = p.getBlockPos();
                    chatLocal(p.getGameProfile().getName()
                        + " entered ESP Range @ "
                        + bp.getX() + " " + bp.getY() + " " + bp.getZ());
                }
            }

            announcedBedrock.retainAll(currentlyVisible);
        }

        // Discord webhook notification for Bedrock players
        if (discordWebhookEnabled.get() && bedrockPlayers != null) {
            String webhookUrl = discordWebhookUrl.get();
            
            if (webhookUrl != null && !webhookUrl.trim().isEmpty() 
                && DiscordWebhook.isValidWebhookUrl(webhookUrl)) {
                
                // Reuse webhook instance for all notifications in this tick
                DiscordWebhook webhook = new DiscordWebhook(webhookUrl);
                Set<UUID> currentlyVisible = new HashSet<>();
                
                for (AbstractClientPlayerEntity p : bedrockPlayers) {
                    UUID id = p.getUuid();
                    currentlyVisible.add(id);

                    // Send webhook only once per player appearance
                    if (webhookNotifiedPlayers.add(id)) {
                        BlockPos bp = p.getBlockPos();
                        webhook.sendPlayerDetection(
                            p.getGameProfile().getName(),
                            bp.getX(),
                            bp.getY(),
                            bp.getZ()
                        );
                    }
                }
                
                // Clean up players that are no longer visible
                webhookNotifiedPlayers.retainAll(currentlyVisible);
            }
        }

        // Debug nearest 3 within max distance
        debugDrawList.clear();

        if (!debugNearestEntities.get()) {
            debugPrevDrawSet.clear();
            debugInfoCache.clear();
            return;
        }

        List<Entity> candidates = new ArrayList<>();
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || e.isRemoved()) continue;
            if (shouldIgnoreForDebug(e)) continue;
            if (!withinDebugDistance(e)) continue;
            candidates.add(e);
        }

        candidates.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(mc.player)));
        int count = Math.min(3, candidates.size());

        Set<UUID> debugNow = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            Entity e = candidates.get(i);
            debugDrawList.add(e);

            UUID id = e.getUuid();
            debugNow.add(id);

            debugInfoCache.put(id, new DebugInfo(entityTypeName(e), e.getBlockPos()));
        }

        if (notifyDebugEnterLeave.get()) {
            for (Entity e : debugDrawList) {
                UUID id = e.getUuid();
                if (!debugPrevDrawSet.contains(id)) {
                    BlockPos bp = e.getBlockPos();
                    chatLocal("Debug caught " + entityTypeName(e)
                        + " @ " + bp.getX() + " " + bp.getY() + " " + bp.getZ());
                }
            }

            for (UUID prevId : new HashSet<>(debugPrevDrawSet)) {
                if (!debugNow.contains(prevId)) {
                    DebugInfo info = debugInfoCache.get(prevId);
                    if (info != null) {
                        chatLocal("Debug lost " + info.type
                            + " @ " + info.pos.getX() + " " + info.pos.getY() + " " + info.pos.getZ());
                    } else {
                        chatLocal("Debug lost entity " + prevId);
                    }
                }
            }
        }

        debugPrevDrawSet.clear();
        debugPrevDrawSet.addAll(debugNow);

        debugInfoCache.keySet().retainAll(debugNow);
    }

    //  tracers + lerped target positions
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.options.hudHidden) return;

        double td = tickDeltaCompat(event);

        Vec3d start = tracerStartCompat();
        final double sx = start.x;
        final double sy = start.y;
        final double sz = start.z;

        disableDepthCompat();
        try {
            // Bedrock players (no limit on counter)
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                if (!isBedrock(p)) continue;

                Vec3d pos = lerpedPosCompat(p, td);

                double x = pos.x;
                double y = pos.y;
                double z = pos.z;

                double height = p.getBoundingBox().maxY - p.getBoundingBox().minY;

                // Aim point
                if (aimPoint.get() == AimPoint.Head) y += height;
                else if (aimPoint.get() == AimPoint.Body) y += height * 0.6;

                event.renderer.line(sx, sy, sz, x, y, z, tracerColor.get());

                // Always vertical stem
                event.renderer.line(x, pos.y, z, x, pos.y + height, z, tracerColor.get());

                // Optional box outline
                if (drawBoxes.get()) {
                    Vec3d raw = p.getPos();
                    Box bb = p.getBoundingBox().offset(pos.x - raw.x, pos.y - raw.y, pos.z - raw.z);
                    drawBoxOutline(event, bb, boxColor.get());
                }
            }

            // Debug nearest 3
            if (debugNearestEntities.get()) {
                for (Entity e : debugDrawList) {
                    Vec3d pos = lerpedPosCompat(e, td);

                    double x = pos.x;
                    double y = pos.y;
                    double z = pos.z;

                    double height = e.getBoundingBox().maxY - e.getBoundingBox().minY;

                    if (aimPoint.get() == AimPoint.Head) y += height;
                    else if (aimPoint.get() == AimPoint.Body) y += height * 0.6;

                    event.renderer.line(sx, sy, sz, x, y, z, tracerColor.get());
                    event.renderer.line(x, pos.y, z, x, pos.y + height, z, tracerColor.get());

                    if (drawBoxes.get()) {
                        Vec3d raw = e.getPos();
                        Box bb = e.getBoundingBox().offset(pos.x - raw.x, pos.y - raw.y, pos.z - raw.z);
                        drawBoxOutline(event, bb, boxColor.get());
                    }
                }
            }
        } finally {
            enableDepthCompat();
        }
    }
}
