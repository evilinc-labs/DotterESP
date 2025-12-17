package Evil.group.addon.modules;

import Evil.group.addon.DotterESPAddon;
import Evil.group.addon.utils.DiscordWebhook;
import Evil.group.addon.utils.compat.CompatReflect;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
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
        .defaultValue(new SettingColor(57, 255, 20, 255))
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
    // For leave messages, remembers last known name + position
    private final Map<UUID, DebugInfo> bedrockInfoCache = new HashMap<>();

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

    // ===== Webhook runtime cache (optional) =====
    private DiscordWebhook webhookClient;
    private String webhookClientUrl = "";

    private final Map<UUID, Long> webhookLastSentMs = new HashMap<>();
    private long webhookNextCleanupMs = 0;
    // ===== end webhook vars =====

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
        if (mc.world == null || mc.player == null) return bedrockPlayers;

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
            Set<UUID> currentlyVisible = new HashSet<>(bedrockPlayers.size());
        
            for (AbstractClientPlayerEntity p : bedrockPlayers) {
                UUID id = p.getUuid();
                currentlyVisible.add(id);
            
                // cache last known name/pos (prints something on leave)
                bedrockInfoCache.put(id, new DebugInfo(p.getGameProfile().getName(), p.getBlockPos()));
            
                // enter message (once per appearance)
                if (announcedBedrock.add(id)) {
                    BlockPos bp = p.getBlockPos();
                    chatLocal(p.getGameProfile().getName()
                        + " entered ESP Range @ "
                        + bp.getX() + " " + bp.getY() + " " + bp.getZ());
                }
            }
        
            // leave messages, anyone previously announced but not visible now
            for (UUID prevId : new HashSet<>(announcedBedrock)) {
                if (!currentlyVisible.contains(prevId)) {
                    DebugInfo info = bedrockInfoCache.get(prevId);
                    if (info != null) {
                        chatLocal(info.type
                            + " left ESP Range @ "
                            + info.pos.getX() + " " + info.pos.getY() + " " + info.pos.getZ());
                    } else {
                        chatLocal("Bedrock player left ESP Range: " + prevId);
                    }
                }
            }
        
            // keep sets/maps small and accurate
            announcedBedrock.retainAll(currentlyVisible);
            bedrockInfoCache.keySet().retainAll(currentlyVisible);
        }


        // Discord webhook notification (optional + with anti-spam)
        if (bedrockPlayers != null && discordWebhookEnabled.get()) {
            DiscordWebhook webhook = getWebhookClient();
            if (webhook == null) {
                disableWebhookRuntime();
            } else {
                final long now = System.currentTimeMillis();
                final long cooldownMs = 30_000;

                Set<UUID> currentlyVisible = new HashSet<>();

                for (AbstractClientPlayerEntity p : bedrockPlayers) {
                    UUID id = p.getUuid();
                    currentlyVisible.add(id);

                    boolean firstSeenThisAppearance = webhookNotifiedPlayers.add(id);

                    Long last = webhookLastSentMs.get(id);
                    boolean cooldownOk = (last == null) || (now - last >= cooldownMs);

                    if (firstSeenThisAppearance || cooldownOk) {
                        BlockPos bp = p.getBlockPos();
                        webhook.sendPlayerDetection(
                            p.getGameProfile().getName(),
                            bp.getX(), bp.getY(), bp.getZ()
                        );
                        webhookLastSentMs.put(id, now);
                    }
                }

                webhookNotifiedPlayers.retainAll(currentlyVisible);

                if (now >= webhookNextCleanupMs) {
                    webhookNextCleanupMs = now + 60_000;
                    webhookLastSentMs.keySet().retainAll(currentlyVisible);
                }
            }
        } else {
            disableWebhookRuntime();
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

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.options.hudHidden) return;

        // Compat: tick delta + tracer origin
        double td = CompatReflect.tickDelta(event);
        Vec3d start = CompatReflect.tracerStart(mc);

        final double sx = start.x;
        final double sy = start.y;
        final double sz = start.z;

        // Compat: depth toggle so ESP renders through blocks
        CompatReflect.disableDepthTest();
        try {
            // Bedrock players
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                if (!isBedrock(p)) continue;

                Vec3d pos = CompatReflect.lerpedPos(p, td);

                double x = pos.x;
                double y = pos.y;
                double z = pos.z;

                double height = p.getBoundingBox().maxY - p.getBoundingBox().minY;

                if (aimPoint.get() == AimPoint.Head) y += height;
                else if (aimPoint.get() == AimPoint.Body) y += height * 0.6;

                event.renderer.line(sx, sy, sz, x, y, z, tracerColor.get());
                event.renderer.line(x, pos.y, z, x, pos.y + height, z, tracerColor.get());

                if (drawBoxes.get()) {
                    Vec3d raw = p.getPos();
                    Box bb = p.getBoundingBox().offset(pos.x - raw.x, pos.y - raw.y, pos.z - raw.z);
                    drawBoxOutline(event, bb, boxColor.get());
                }
            }

            // Debug nearest 3
            if (debugNearestEntities.get()) {
                for (Entity e : debugDrawList) {
                    Vec3d pos = CompatReflect.lerpedPos(e, td);

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
            CompatReflect.enableDepthTest();
        }
    }

    private DiscordWebhook getWebhookClient() {
        if (!discordWebhookEnabled.get()) return null;

        String url = discordWebhookUrl.get();
        if (!DiscordWebhook.isValidWebhookUrl(url)) return null;

        if (webhookClient == null || !Objects.equals(webhookClientUrl, url)) {
            webhookClientUrl = url;
            webhookClient = new DiscordWebhook(url);
            webhookNotifiedPlayers.clear();
            webhookLastSentMs.clear();
        }

        return webhookClient;
    }

    private void disableWebhookRuntime() {
        webhookClient = null;
        webhookClientUrl = "";
        webhookNotifiedPlayers.clear();
        webhookLastSentMs.clear();
    }
}
