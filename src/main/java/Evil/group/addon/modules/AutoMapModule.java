package Evil.group.addon.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import Evil.group.addon.AntiDotterAddon;
import Evil.group.addon.utils.HotbarSupply;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoMapModule extends Module {
    private final Cache<Integer, Integer> pending = CacheBuilder.newBuilder().expireAfterWrite(250, TimeUnit.MILLISECONDS).build();
    // -----------------------------
    // Frame placement settings (carried over from the original frame placer)
    // -----------------------------
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum SurfacePref { Walls, Floors, Both }

    private final Setting<Boolean> placeFrames = sgGeneral.add(new BoolSetting.Builder()
        .name("place-frames")
        .description("If disabled, this module will only run the AutoMap logic (no new frame placement).")
        .defaultValue(true)
        .build()
    );

    private final Setting<SurfacePref> surfacePref = sgGeneral.add(new EnumSetting.Builder<SurfacePref>()
        .name("surface-preference")
        .description("Prefer placing frames on walls, floors, or both.")
        .defaultValue(SurfacePref.Walls)
        .build()
    );


private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
    .name("range")
    .description("Max placement range for frames.")
    .defaultValue(4.5)
    .range(0, 7)
    .sliderRange(0, 7)
    .build()
);


    private final Setting<Integer> frameDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("frame-delay-ticks")
        .description("Delay between item frame placement attempts (ticks).")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Integer> frameDelayMs = sgGeneral.add(new IntSetting.Builder()
        .name("frame-delay-ms")
        .description("Additional delay between item frame placement attempts (ms).")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 500)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate toward the placement hit before placing.")
        .defaultValue(true)
        .build()
    );

private final Setting<Boolean> toggleFreeYaw = sgGeneral.add(new BoolSetting.Builder()
    .name("toggle-free-yaw")
    .description("If enabled, toggles FreeYaw while this module is active.")
    .defaultValue(false)
    .build()
);



private final Setting<Boolean> keepRunningNoFrames = sgGeneral.add(new BoolSetting.Builder()
    .name("keep-running-no-frames")
    .description("If enabled, the module will keep running even when you run out of frames. AutoMap will still place/replace maps in nearby frames.")
    .defaultValue(false)
    .build()
);

private final Setting<Boolean> onlyPlaceEmptyFrames = sgGeneral.add(new BoolSetting.Builder()
    .name("only-place-empty-frames")
    .description("If enabled, only place maps in empty frames. Will not break/replace existing maps.")
    .defaultValue(false)
    .build()
);

    // -----------------------------
    // Baritone Auto-Explore settings
    // -----------------------------
    private final SettingGroup sgBaritone = settings.createGroup("Baritone Explore");

    private final Setting<Boolean> enableAutoExplore = sgBaritone.add(new BoolSetting.Builder()
        .name("enable-auto-explore")
        .description("Enable Baritone random point navigation within radius.")
        .defaultValue(false)
        .onChanged(enabled -> {
            if (enabled && isActive()) {
                startBaritoneExplore();
            } else if (!enabled) {
                stopBaritoneExplore();
            }
        })
        .build()
    );

    private final Setting<Integer> exploreRadius = sgBaritone.add(new IntSetting.Builder()
        .name("explore-radius")
        .description("Maximum distance in blocks from origin to explore.")
        .defaultValue(2000)
        .min(100)
        .max(100000)
        .sliderRange(100, 10000)
        .visible(() -> enableAutoExplore.get())
        .build()
    );

    private final Setting<Integer> originX = sgBaritone.add(new IntSetting.Builder()
        .name("origin-x")
        .description("X coordinate of exploration origin.")
        .defaultValue(0)
        .range(-30000000, 30000000)
        .sliderRange(-10000, 10000)
        .visible(() -> enableAutoExplore.get())
        .build()
    );

    private final Setting<Integer> originZ = sgBaritone.add(new IntSetting.Builder()
        .name("origin-z")
        .description("Z coordinate of exploration origin.")
        .defaultValue(0)
        .range(-30000000, 30000000)
        .sliderRange(-10000, 10000)
        .visible(() -> enableAutoExplore.get())
        .build()
    );

    private final Setting<Integer> goalReachedDistance = sgBaritone.add(new IntSetting.Builder()
        .name("goal-reached-distance")
        .description("Distance threshold to consider a goal reached (blocks).")
        .defaultValue(5)
        .min(1)
        .max(50)
        .sliderRange(1, 20)
        .visible(() -> enableAutoExplore.get())
        .build()
    );

    private final Setting<Integer> checkIntervalTicks = sgBaritone.add(new IntSetting.Builder()
        .name("check-interval")
        .description("How often to check if goal is reached (in ticks).")
        .defaultValue(20)
        .min(1)
        .max(200)
        .sliderRange(1, 100)
        .visible(() -> enableAutoExplore.get())
        .build()
    );

    // Baritone state tracking
    private IBaritone baritone;
    private boolean baritoneExploreActive = false;
    private int exploreTickCounter = 0;
    private BlockPos currentTargetPos = null;
    private final Random random = new Random();

    private static final int MAX_PLACES_PER_WINDOW = 9;
    private static final long PLACE_WINDOW_MS = 300;
    private final ArrayDeque<Long> placeTimes = new ArrayDeque<>();

    private long lastFramePlaceMs = 0;
    private int ticksSinceLastPlace = 999999;

private boolean freeYawWasActive = false;
private boolean toggledFreeYaw = false;

// Hotbar restore after a successful frame->map cycle
private int restoreHotbarSlot = -1;
private boolean restoreAfterNextMapPlace = false;

private boolean warnedOutOfFrames = false;

    public AutoMapModule() {
        super(AntiDotterAddon.CATEGORY, "AutoMap", "Place maps nigga");
    }

    @EventHandler
    private void onTick(TickEvent.Pre ignored) {
        ticksSinceLastPlace++; 
        
        // Handle Baritone auto-explore if enabled
        if (enableAutoExplore.get()) {
            handleBaritoneExplore();
        }
        
                // Ensure we have a filled map in the hotbar; if not, try to pull one in from inventory.
        FindItemResult itemResult = InvUtils.findInHotbar(Items.FILLED_MAP);
        if (!itemResult.found()) {
            int supplied = HotbarSupply.ensureHotbarStack(s -> !s.isEmpty() && s.getItem() == Items.FILLED_MAP, 1, false);
            itemResult = InvUtils.findInHotbar(Items.FILLED_MAP);
            if (supplied == -1 || !itemResult.found()) {
                info("No maparts found in hotbar.");
                toggle();
                return;
            }
        }

        // If we are configured to place frames, we must have frames somewhere in inventory.
// (We still require a hotbar slot at placement-time.)
boolean haveAnyFrames = InvUtils.find(Items.ITEM_FRAME).found();
if (placeFrames.get() && !haveAnyFrames) {
    if (keepRunningNoFrames.get()) {
        if (!warnedOutOfFrames) {
            warning("Out of item frames. AutoMap will keep running, but no new frames will be placed.");
            warnedOutOfFrames = true;
        }
    } else {
        error("No item frames found in hotbar or inventory.");
        toggle();
        return;
    }
} else if (haveAnyFrames) {
    warnedOutOfFrames = false;
}

        // QOL: if we see a filled map already in a frame, try to pull that same map (same components) into the hotbar from inventory.
        preSupplyMatchingMapsNearby();

        List<Entity> entities = new ArrayList<>();
        TargetUtils.getList(entities, e -> e instanceof ItemFrameEntity i && PlayerUtils.isWithinReach(i) && pending.getIfPresent(i.getId()) == null, SortPriority.ClosestAngle, 128);

        for (Entity entity : entities) {
            ItemFrameEntity itemFrame = (ItemFrameEntity) entity;
            Vec3d blockOffset = itemFrame.getHorizontalFacing().getOpposite().getDoubleVector();
            Vec3d faceOffset = new Vec3d(blockOffset.x / 2, blockOffset.y / 2, blockOffset.z / 2);
            Vec3d blockFaceVec = Vec3d.of(itemFrame.getAttachedBlockPos()).add(0.5).add(faceOffset);

            ItemStack stack = itemFrame.getHeldItemStack();
            if (stack.isEmpty()) {
                InvUtils.swap(itemResult.slot(), true);
                mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.interactAt(itemFrame, mc.player.isSneaking(), Hand.MAIN_HAND, blockFaceVec));
                mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.interact(itemFrame, mc.player.isSneaking(), Hand.MAIN_HAND));
                mc.player.swingHand(Hand.MAIN_HAND);
                InvUtils.swapBack();
                if (restoreAfterNextMapPlace && restoreHotbarSlot >= 0) {
                    forceSelectHotbarSlot(restoreHotbarSlot);
                    restoreAfterNextMapPlace = false;
                    restoreHotbarSlot = -1;
                }
                pending.put(itemFrame.getId(), 1);
                return;
            } else if (!onlyPlaceEmptyFrames.get() && (stack.getItem() != Items.FILLED_MAP || !InvUtils.findInHotbar(item -> item.getItem() == Items.FILLED_MAP && item.getComponents().equals(itemFrame.getHeldItemStack().getComponents())).found())) {
                mc.player.swingHand(Hand.MAIN_HAND);
                mc.interactionManager.attackEntity(mc.player, itemFrame);
                pending.put(itemFrame.getId(), 1);
                return;
            }
        }
        // If we didn't act on any reachable frames this tick, try placing a new frame (midair/fly-by).
        if (placeFrames.get() && InvUtils.find(Items.ITEM_FRAME).found()) tryPlaceFrameInAir();

    }

    private void preSupplyMatchingMapsNearby() {
        if (mc.player == null || mc.world == null) return;

        List<Entity> frames = new ArrayList<>();
        TargetUtils.getList(frames,
            e -> e instanceof ItemFrameEntity i && PlayerUtils.isWithinReach(i),
            SortPriority.ClosestAngle,
            128
        );

        for (Entity e : frames) {
            ItemFrameEntity frame = (ItemFrameEntity) e;
            ItemStack held = frame.getHeldItemStack();
            if (held.isEmpty() || held.getItem() != Items.FILLED_MAP) continue;

            // Pull the exact same map (same item components) into the hotbar if it exists in inventory.
            HotbarSupply.ensureHotbarStack(s ->
                !s.isEmpty()
                    && s.getItem() == Items.FILLED_MAP
                    && s.getComponents().equals(held.getComponents()),
                1,
                false
            );
        }
    }

    private void tryPlaceFrameInAir() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.player.networkHandler == null) return;

                int prevSlot = mc.player.getInventory().selectedSlot;

        // QOL: pull item frames into hotbar from inventory if needed.
        int frameSlot = HotbarSupply.ensureHotbarStack(s -> !s.isEmpty() && s.getItem() == Items.ITEM_FRAME, 1, false);
        if (frameSlot == -1) return;
        FindItemResult framesHotbar = InvUtils.findInHotbar(Items.ITEM_FRAME);
        if (!framesHotbar.found()) return;

        long now = System.currentTimeMillis();
        if (now - lastFramePlaceMs < frameDelayMs.get()) return;
        if (!canPlaceNow(now)) return;
        if (ticksSinceLastPlace < frameDelayTicks.get()) return;

        PlacementTarget target = findBestPlacement();
        if (target == null) return;
        if (!mc.world.getBlockState(target.airPos).isReplaceable()) return;
        if (!isAirSpotFree(target.airPos)) return;


        // Force-select frame slot client+server
        forceSelectHotbarSlot(framesHotbar.slot());

        // Rotate for a cleaner click (keeps this module reliable on strict servers)
        Vec3d aim = target.hit.getPos();
        float yaw = (float) Rotations.getYaw(aim);
        float pitch = (float) Rotations.getPitch(aim);
        if (rotate.get()) Rotations.rotate(yaw, pitch, 100, true, () -> {});

        grimPlace(target.hit);

        // QOL: after we place a frame, keep the frame slot selected so we can immediately map it,
        // then restore the user's original slot right after a map is successfully placed into a frame.
        restoreHotbarSlot = prevSlot;
        restoreAfterNextMapPlace = true;

        recordPlace(now);
        lastFramePlaceMs = now;
        ticksSinceLastPlace = 0;
    }

    private static class PlacementTarget {
        final BlockHitResult hit;
        final BlockPos airPos;
        PlacementTarget(BlockHitResult hit, BlockPos airPos) { this.hit = hit; this.airPos = airPos; }
    }

    private PlacementTarget findBestPlacement() {
        if (mc.player == null || mc.world == null) return null;

        Vec3d eye = mc.player.getEyePos();
        double range = placeRange.get();
        int r = (int) Math.ceil(range);

        BlockPos base = mc.player.getBlockPos();
        PlacementTarget best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = base.add(dx, dy, dz);

                    double centerDistSq = Vec3d.ofCenter(pos).squaredDistanceTo(eye);
                    if (centerDistSq > (range + 1.5) * (range + 1.5)) continue;

                    var state = mc.world.getBlockState(pos);
                    if (state.isReplaceable()) continue;

                    Block b = state.getBlock();
                    if (isIgnoredBlock(b)) continue;

                                        for (Direction face : getCandidateFaces()) {
                        // Only attempt faces that can actually support an item frame.
                        if (!state.isSideSolidFullSquare(mc.world, pos, face)) continue;
                        BlockPos airPos = pos.offset(face);
                        if (!mc.world.getBlockState(airPos).isReplaceable()) continue;

                        double distSq = Vec3d.ofCenter(airPos).squaredDistanceTo(eye);
                        if (distSq > range * range) continue;

                        if (!isAirSpotFree(airPos)) continue;

                        Vec3d hitVec = Vec3d.ofCenter(pos).add(
                            face.getOffsetX() * 0.5,
                            face.getOffsetY() * 0.5,
                            face.getOffsetZ() * 0.5
                        );

                        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);

                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = new PlacementTarget(hit, airPos);
                        }
                    }
                }
            }
        }

        return best;
    }

    
    private Direction[] getCandidateFaces() {
        return switch (surfacePref.get()) {
            case Walls -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            case Floors -> new Direction[]{Direction.UP, Direction.DOWN};
            case Both -> Direction.values();
        };
    }

private boolean isIgnoredBlock(Block b) {
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) return true;
        if (b == Blocks.ENDER_CHEST) return true;
        if (b == Blocks.CRAFTING_TABLE) return true;
        return b instanceof ShulkerBoxBlock;
    }

    private boolean isAirSpotFree(BlockPos airPos) {
        if (mc.world == null) return false;
        Box box = new Box(airPos).expand(0.6);
        return mc.world.getEntitiesByClass(ItemFrameEntity.class, box, e -> true).isEmpty();
    }




    private void forceSelectHotbarSlot(int slot) {
        if (mc.player == null || mc.player.networkHandler == null) return;
        if (slot < 0 || slot > 8) return;

        mc.player.getInventory().selectedSlot = slot;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void grimPlace(BlockHitResult bhr) {
        if (mc.player == null || mc.player.networkHandler == null) return;

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.OFF_HAND, bhr, mc.player.currentScreenHandler.getRevision() + 2));

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private boolean canPlaceNow(long now) {
        while (!placeTimes.isEmpty() && now - placeTimes.peekFirst() > PLACE_WINDOW_MS) {
            placeTimes.pollFirst();
        }
        return placeTimes.size() < MAX_PLACES_PER_WINDOW;
    }

    private void recordPlace(long now) {
        placeTimes.addLast(now);
    }


@Override
public void onActivate() {
    info("AutoMap toggled on.");
    if (toggleFreeYaw.get()) {
        FreeYaw freeYaw = Modules.get().get(FreeYaw.class);
        freeYawWasActive = freeYaw.isActive();
        if (!freeYawWasActive) {
            freeYaw.toggle();
            toggledFreeYaw = true;
        }
    }
    
    // Initialize Baritone if auto-explore is enabled
    if (enableAutoExplore.get()) {
        baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) {
            startBaritoneExplore();
        } else {
            error("Baritone not found. Auto-explore disabled.");
        }
    }
}

@Override
public void onDeactivate() {
    info("AutoMap toggled off.");
    if (toggleFreeYaw.get() && toggledFreeYaw) {
        FreeYaw freeYaw = Modules.get().get(FreeYaw.class);
        if (freeYaw.isActive() && !freeYawWasActive) freeYaw.toggle();
    }
    toggledFreeYaw = false;
    freeYawWasActive = false;

    if (restoreAfterNextMapPlace && restoreHotbarSlot >= 0) {
        forceSelectHotbarSlot(restoreHotbarSlot);
        restoreAfterNextMapPlace = false;
        restoreHotbarSlot = -1;
    }
    
    // Stop Baritone exploration
    stopBaritoneExplore();
}

    // -----------------------------
    // Baritone Random Point Navigation Methods
    // -----------------------------
    
    private void handleBaritoneExplore() {
        if (mc.player == null || baritone == null) return;
        
        exploreTickCounter++;
        
        // Only check periodically
        if (exploreTickCounter >= checkIntervalTicks.get()) {
            exploreTickCounter = 0;
            checkAndUpdateGoal();
        }
    }
    
    private void checkAndUpdateGoal() {
        if (mc.player == null) return;
        
        BlockPos playerPos = mc.player.getBlockPos();
        
        // If no target exists or target has been reached, generate a new one
        if (currentTargetPos == null || hasReachedGoal(playerPos, currentTargetPos)) {
            if (currentTargetPos != null) {
                info("Reached target (" + currentTargetPos.getX() + ", " + currentTargetPos.getZ() + "). Selecting new destination...");
            }
            generateAndGoToRandomPoint();
        }
        
        // Check if player exceeded radius boundary - if so, generate new point
        int dx = playerPos.getX() - originX.get();
        int dz = playerPos.getZ() - originZ.get();
        double distanceSq = dx * dx + dz * dz;
        double radiusSq = exploreRadius.get() * exploreRadius.get();
        
        if (distanceSq > radiusSq) {
            int currentDist = (int)Math.sqrt(distanceSq);
            warning("Exceeded exploration radius (" + currentDist + " > " + exploreRadius.get() + "). Selecting new point within bounds...");
            generateAndGoToRandomPoint();
        }
    }
    
    private boolean hasReachedGoal(BlockPos playerPos, BlockPos targetPos) {
        if (targetPos == null) return true;
        
        // Check if we're within the goal-reached distance threshold
        int dx = playerPos.getX() - targetPos.getX();
        int dz = playerPos.getZ() - targetPos.getZ();
        double distSq = dx * dx + dz * dz;
        double thresholdSq = goalReachedDistance.get() * goalReachedDistance.get();
        
        // Also check if Baritone has stopped pathing (goal reached or stuck)
        boolean notPathing = !baritone.getPathingBehavior().isPathing();
        
        return distSq <= thresholdSq || notPathing;
    }
    
    private void generateAndGoToRandomPoint() {
        if (baritone == null) return;
        
        BlockPos randomPoint = generateRandomPointInRadius();
        currentTargetPos = randomPoint;
        
        // Cancel any existing path
        baritone.getPathingBehavior().cancelEverything();
        
        // Set new goal using CustomGoalProcess
        Goal goal = new GoalXZ(randomPoint.getX(), randomPoint.getZ());
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
        
        info("Navigating to random point: (" + randomPoint.getX() + ", " + randomPoint.getZ() + ")");
    }
    
    private BlockPos generateRandomPointInRadius() {
        int radius = exploreRadius.get();
        int originXVal = originX.get();
        int originZVal = originZ.get();
        
        // Generate random point within circle
        // Use polar coordinates to ensure uniform distribution
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = Math.sqrt(random.nextDouble()) * radius; // sqrt for uniform distribution
        
        int x = originXVal + (int)(distance * Math.cos(angle));
        int z = originZVal + (int)(distance * Math.sin(angle));
        
        // Get player's current Y level (Baritone will handle vertical navigation)
        int y = mc.player != null ? mc.player.getBlockPos().getY() : 64;
        
        return new BlockPos(x, y, z);
    }
    
    private void startBaritoneExplore() {
        if (baritone == null) {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null) {
                error("Baritone not found.");
                return;
            }
        }
        
        // Cancel any existing tasks
        baritone.getPathingBehavior().cancelEverything();
        
        // Generate first random point
        generateAndGoToRandomPoint();
        baritoneExploreActive = true;
        info("Baritone random navigation started with " + exploreRadius.get() + " block radius from (" + originX.get() + ", " + originZ.get() + ").");
    }
    
    private void stopBaritoneExplore() {
        if (baritone != null && baritoneExploreActive) {
            baritone.getPathingBehavior().cancelEverything();
            baritoneExploreActive = false;
            currentTargetPos = null;
            info("Baritone navigation stopped.");
        }
    }

}