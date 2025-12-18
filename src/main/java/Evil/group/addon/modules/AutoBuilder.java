package Evil.group.addon.modules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

// these are needed to save the pattern to file
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import Evil.group.addon.AntiDotterAddon;
import Evil.group.addon.utils.HotbarSupply;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.FreeLook;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
//import net.minecraft.text.MutableText;
//import net.minecraft.util.Formatting;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoBuilder extends Module {
    public enum BuildMode {
        Vertical,
        Horizontal
    }
    public enum VerticalAnchor {
        InFront,
        Behind
    } 

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<BuildMode> buildMode = sgGeneral.add(new EnumSetting.Builder<BuildMode>()
        .name("build-mode")
        .description("Vertical builds a wall, Horizontal builds on the ground.")
        .defaultValue(BuildMode.Vertical)
        .build()
    );


    private final Setting<VerticalAnchor> verticalAnchor = sgGeneral.add(new EnumSetting.Builder<VerticalAnchor>()
        .name("vertical-anchor")
        .description("Where to place the vertical wall relative to you.")
        .defaultValue(VerticalAnchor.InFront)
        .visible(() -> buildMode.get() == BuildMode.Vertical)
        .build()
    );

    private final Setting<Block> blockToUse = sgGeneral.add(new BlockSetting.Builder()
        .name("block")
        .description("Block to use for building.")
        .defaultValue(Blocks.OBSIDIAN)
        .build()
    );

    private final Setting<Integer> delayMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ms")
        .description("Delay between block placements in milliseconds.")
        .defaultValue(50)
        .min(0)
        .sliderRange(0, 500)
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Max placement range.")
        .defaultValue(4.5)
        .range(0, 7)
        .sliderRange(0, 7)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to face the block when placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Place blocks in air without support (Grim bypass).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> floating = sgGeneral.add(new BoolSetting.Builder()
        .name("floating")
        .description("Slow down time to float in place while building.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Double> floatTimerScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("float-timer-scale")
        .description("How slow to run client ticks while building. Lower = more 'float'.")
        .defaultValue(0.01)
        .range(0.01, 1.0)
        .sliderRange(0.01, 1.0)
        .visible(floating::get)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Automatically disable when all blocks are placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoOrientation = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-orientation")
        .description("Build faces the direction you're looking at when activated.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> offsetY = sgGeneral.add(new IntSetting.Builder()
        .name("offset-y")
        .description("Y offset from player (applies to both Vertical + Horizontal).")
        .defaultValue(0)
        .sliderRange(-3, 3)
        .build()
    );
    // Replenish logic
    private final Setting<Boolean> autoReplenish = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-replenish")
        .description("Refill the hotbar block stack from inventory when it gets low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> replenishThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("replenish-threshold")
        .description("When the selected build block stack is at or below this count, refill from inventory.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 64)
        .visible(autoReplenish::get)
        .build()
    );
    // end replenish logic
    private final Setting<Boolean> useFreeLook =
        settings.getDefaultGroup().add(
            new BoolSetting.Builder()
                .name("freelook")
                .description("Enable FreeLook while module is active.")
                .defaultValue(true)
                .build()
        );

    // Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render preview.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(138, 43, 226, 50))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(138, 43, 226, 255))
        .build()
    );
    private final Setting<Boolean> savePatternToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("save-pattern-to-file")
        .description("Save current 5x5 pattern to config/Evil/autoBuilder.json when enabling the module.")
        .defaultValue(false)
        .build()
    );

    // Gson instance for JSON serialization
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PATTERN_DIR = "Evil";
    private static final String PATTERN_FILE = "autoBuilder.json";

    // 5x5 Grid - vertical: X is horizontal, Y is vertical (row 0 = top)
    private final boolean[][] grid = new boolean[5][5];
    private long lastPlaceTime = 0;
    private int currentIndex = 0;
    private Direction buildDirection = Direction.NORTH;

    // ---- Placement rate-limit (2b has this hard limit) ----
    private static final int MAX_PLACES_PER_WINDOW = 9;
    private static final long PLACE_WINDOW_MS = 300;    // 2b limit: 9 blocks per 300ms

    // Snapshot placement plan at activation so moving doesn't shift the pattern.
    private List<BlockPos> plannedPositions = List.of();
    private BlockPos activationPlayerPos = null;
    private Direction activationFacing = Direction.NORTH;


    private final ArrayDeque<Long> placeTimes = new ArrayDeque<>();

    public AutoBuilder() {
        super(AntiDotterAddon.CATEGORY, "auto-builder", "Builds 5x5 patterns vertically or horizontally. Made for 2b2t.");
        // Load pattern once at module creation (if file exists)
        tryLoadPatternOnInit();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WTable table = theme.table();
        list.add(table);

        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                final int r = row;
                final int c = col;
                WCheckbox checkbox = table.add(theme.checkbox(grid[r][c])).widget();
                checkbox.action = () -> grid[r][c] = checkbox.checked;
            }
            table.row();
        }

        return list;
    }
    private static class AutoBuilderPatternFile {
        int version = 1;
        boolean[][] grid = new boolean[5][5];
    }

    private static Path getPatternFilePath() {
        return FabricLoader.getInstance().getConfigDir()
            .resolve(PATTERN_DIR)
            .resolve(PATTERN_FILE);
    }

    private void loadPatternFromFile(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);

            AutoBuilderPatternFile data = GSON.fromJson(json, AutoBuilderPatternFile.class);
            if (data == null || data.grid == null) return;

            // Defensive copy (survive malformed JSON or wrong dimensions)
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < 5; c++) {
                    boolean val =
                        r < data.grid.length &&
                        data.grid[r] != null &&
                        c < data.grid[r].length &&
                        data.grid[r][c];
                    grid[r][c] = val;
                }
            }
        } catch (JsonSyntaxException ignored) {
            // Bad JSON: ignore and keep defaults
        } catch (Throwable ignored) {
            // IO/permissions/etc: ignore (don't crash addon)
        }
    }

    /** Save only once on enable, if the setting is on. */
    private void savePatternToFile() {
        try {
            Path file = getPatternFilePath();
            Files.createDirectories(file.getParent());
        
            AutoBuilderPatternFile data = new AutoBuilderPatternFile();
            for (int r = 0; r < 5; r++) {
                System.arraycopy(grid[r], 0, data.grid[r], 0, 5);
            }
        
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // non-fatal
        }
    }

    @Override
    public void onActivate() {
        lastPlaceTime = 0;
        currentIndex = 0;

        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }
        if (useFreeLook.get()) { 
            // enable freelook
            FreeLook freeLook = Modules.get().get(FreeLook.class);
            if (freeLook != null && !freeLook.isActive()) {
                freeLook.toggle();
            }
        }
        // 1) Resolve facing FIRST (so snapshot uses the right direction)
        if (autoOrientation.get()) {
            buildDirection = mc.player.getHorizontalFacing();
        }

        // 2) Snapshot origin + facing ONCE
        activationPlayerPos = mc.player.getBlockPos();
        activationFacing = buildDirection;

        // 3) Build the plan ONCE
        plannedPositions = computeBlocksToPlace(activationPlayerPos, activationFacing);

        // 4) Validate pattern
        int need = plannedPositions.size();
        if (need == 0) {
            warnAutoBuilder("No pattern selected (grid is empty).");
            toggle();
            return;
        }

        // 5) Validate hotbar materials ONCE
        if (!hotbarHasEnoughBuildBlocksFor(need)) {
            warnAutoBuilder("Not enough blocks in hotbar for this pattern. Add more and re-enable.");
            toggle();
            return;
        }

        // 6) Save once on enable (optional)
        if (savePatternToFile.get()) {
            savePatternToFile();
        }

        // 7) Floating (timer slow) while building
        if (floating.get()) {
            Timer timer = Modules.get().get(Timer.class);
            if (timer != null) timer.setOverride(floatTimerScale.get());
        }
    }


    @Override
    public void onDeactivate() {
        Timer timer = Modules.get().get(Timer.class);
        if (timer != null) {
            timer.setOverride(Timer.OFF);
        }
        if (useFreeLook.get()) {
            // disable freelook
            FreeLook freeLook = Modules.get().get(FreeLook.class);
            if (freeLook != null && freeLook.isActive()) {
                freeLook.toggle();
            }
        }
    }
    // attempting to move onto an ontick method to prevent placing issues
    //@EventHandler
    //private void onTick(TickEvent.Post event) {
    //    if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
//
    //    // Drive placement from tick
    //    tryPlace();
//
    //    // Immediate auto-disable when done
    //    if (autoDisable.get()) {
    //        List<BlockPos> positions = plannedPositions;
    //        if (!positions.isEmpty() && allBlocksPlaced(positions)) {
    //            Timer timer = Modules.get().get(Timer.class);
    //            if (timer != null) timer.setOverride(Timer.OFF);
    //            toggle();
    //        }
    //    }
    //}
    ///*
    @EventHandler
    // attempting to use onTick to autoDisable
    private void onTick(TickEvent.Post event) {
        if (!autoDisable.get()) return;
        if (mc.player == null || mc.world == null) return;

        List<BlockPos> positions = plannedPositions; 

        if (positions == null || positions.isEmpty()) return;

        if (allBlocksPlaced(positions)) {
            Timer timer = Modules.get().get(Timer.class);
            if (timer != null) timer.setOverride(Timer.OFF);
            toggle();
        }
    }
    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // Try placing on every move event for faster placement
        tryPlace();
        
        // Auto-disable check
        if (autoDisable.get()) {
            List<BlockPos> positions = plannedPositions;
            if (!positions.isEmpty() && allBlocksPlaced(positions)) {
                Timer timer = Modules.get().get(Timer.class);
                if (timer != null) timer.setOverride(Timer.OFF);
                toggle();
            }
        }
    }
        //*/

    private void tryPlace() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPlaceTime < delayMs.get()) return;
        if (!canPlaceNow(now)) return;  // for 9 block/300ms rate limit

        List<BlockPos> positions = plannedPositions;
        if (positions.isEmpty()) return;

        // Find next block that needs placing
        while (currentIndex < positions.size()) {
            BlockPos pos = positions.get(currentIndex);

            if (mc.world.getBlockState(pos).isReplaceable() && isInRange(pos)) {
                FindItemResult block = findBlock();
                if (block.found()) {
                    // Temporarily disable timer for placement (including rotation)
                    Timer timer = Modules.get().get(Timer.class);
                    boolean wasFloating = floating.get() && timer != null;
                    if (wasFloating) {
                        timer.setOverride(Timer.OFF);
                    }
                    // Make sure we have the block in hotbar + refill if low
                    if (autoReplenish.get()) {
                        HotbarSupply.ensureHotbarStack(this::isBuildBlock, replenishThreshold.get(), false);
                    }
                    // Swap to the block
                    InvUtils.swap(block.slot(), false);
                    
                    // Place directly without rotation callback delay
                    if (rotate.get()) {
                        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 100, true, () -> {});
                    }
                    placeBlockAt(pos);
                    recordPlace(now);
                    
                    // Re-enable timer after placement
                    if (wasFloating) {
                        timer.setOverride(floatTimerScale.get());
                    }
                    
                    lastPlaceTime = now;  // 300ms rate limiter
                    currentIndex++;

                    // attempt auto-disable check
                    maybeAutoDisableNow();
                    return; // Only place 1 block per cycle
                }
            }
            currentIndex++;
        }

        if (currentIndex >= positions.size()) {
            currentIndex = 0;
        }
    }

    private boolean allBlocksPlaced(List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (mc.world.getBlockState(pos).isReplaceable()) {
                return false;
            }
        }
        return true;
    }
    private void placeBlockAt(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null || mc.world == null) return;

        // If the target isn't replaceable, nothing to do.
        if (!mc.world.getBlockState(pos).isReplaceable()) return;

        // Find a solid neighbor to click against (preferred), otherwise airplace if enabled.
        Direction clickedSide = Direction.UP;
        BlockPos clickPos = null;

        Direction[] dirs = { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        for (Direction dir : dirs) {
            BlockPos neighbor = pos.offset(dir);
            if (!mc.world.getBlockState(neighbor).isReplaceable()) {
                // Click on the solid neighbor's face pointing toward our target.
                clickPos = neighbor;
                clickedSide = dir.getOpposite();
                break;
            }
        }

        if (clickPos == null) {
            if (!airPlace.get()) return;

            // Airplace: click "at" the target position.
            clickPos = pos;
            clickedSide = Direction.UP;
        }

        Vec3d hitVec = Vec3d.ofCenter(clickPos).add(
            clickedSide.getOffsetX() * 0.5,
            clickedSide.getOffsetY() * 0.5,
            clickedSide.getOffsetZ() * 0.5
        );

        BlockHitResult bhr = new BlockHitResult(hitVec, clickedSide, clickPos, false);
        grimPlace(bhr);
    }

    private void grimPlace(BlockHitResult blockHitResult) {
        if (mc.player == null || mc.player.networkHandler == null) return;

        // Wither-style: swap to offhand, place with offhand, swap back.
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN,
            Direction.DOWN
        ));

        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.OFF_HAND,
            blockHitResult,
            mc.player.currentScreenHandler.getRevision() + 2
        ));

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN,
            Direction.DOWN
        ));

        mc.player.swingHand(Hand.MAIN_HAND);
    }


    private List<BlockPos> computeBlocksToPlace(BlockPos playerPos, Direction facing) {
        List<BlockPos> positions = new ArrayList<>();
        if (playerPos == null) return positions;

        if (buildMode.get() == BuildMode.Vertical) {
            int baseY = playerPos.getY() + offsetY.get() + 2;

            int baseX = playerPos.getX();
            int baseZ = playerPos.getZ();

            int step = (verticalAnchor.get() == VerticalAnchor.InFront) ? 2 : -2;   // offset for vertical placement

            switch (facing) {
                case NORTH -> baseZ += -step;
                case SOUTH -> baseZ +=  step;
                case EAST  -> baseX +=  step;
                case WEST  -> baseX += -step;
                default    -> baseZ += -step;
            }

            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 5; col++) {
                    if (!grid[row][col]) continue;

                    int y = baseY - row;
                    int horizontalOffset = col - 2;

                    int x = baseX;
                    int z = baseZ;

                    switch (facing) {
                        case NORTH, SOUTH -> x += horizontalOffset;
                        case EAST, WEST   -> z += horizontalOffset;
                        default           -> x += horizontalOffset;
                    }

                    positions.add(new BlockPos(x, y, z));
                }
            }
        } else {
            int y = playerPos.getY() + offsetY.get();

            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 5; col++) {
                    if (!grid[row][col]) continue;

                    int forwardOffset = row - 2;
                    int sideOffset = col - 2;

                    int x = playerPos.getX();
                    int z = playerPos.getZ();

                    switch (facing) {
                        case NORTH -> { z -= forwardOffset; x += sideOffset; }
                        case SOUTH -> { z += forwardOffset; x -= sideOffset; }
                        case EAST  -> { x += forwardOffset; z += sideOffset; }
                        case WEST  -> { x -= forwardOffset; z -= sideOffset; }
                        default    -> { z -= forwardOffset; x += sideOffset; }
                    }

                    positions.add(new BlockPos(x, y, z));
                }
            }
        }

        return positions;
    }



    private boolean isInRange(BlockPos pos) {
        if (mc.player == null) return false;
        return mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= placeRange.get();
    }

    private FindItemResult findBlock() {
        Block selected = blockToUse.get();
        return InvUtils.findInHotbar(itemStack -> {
            if (!(itemStack.getItem() instanceof BlockItem bi)) return false;
            return bi.getBlock() == selected;
        });
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        // Try to place blocks every frame (not just per tick) for faster placement
        tryPlace();
        
        if (!render.get() || mc.player == null || mc.world == null) return;

        for (BlockPos pos : plannedPositions) {
            if (mc.world.getBlockState(pos).isReplaceable()) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
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
    private boolean isBuildBlock(net.minecraft.item.ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        return bi.getBlock() == blockToUse.get();
    }
    /** Load once when the module object is created (constructor). */
    private void tryLoadPatternOnInit() {
        try {
            Path file = getPatternFilePath();
            if (Files.exists(file)) loadPatternFromFile(file);
        } catch (Throwable ignored) {}
    }

    // Below is logic specific to warnings regarding block count
    private void warnAutoBuilder(String msg) {
        MutableText t = Text.literal("[")
            .formatted(Formatting.GRAY)
            .append(Text.literal("AutoBuilder").formatted(Formatting.RED))
            .append(Text.literal("] ").formatted(Formatting.GRAY))
            .append(Text.literal(msg).formatted(Formatting.GRAY));

        mc.inGameHud.getChatHud().addMessage(t);
    }
    //private int patternBlockCount() {
    //    int n = 0;
    //    for (int r = 0; r < 5; r++) for (int c = 0; c < 5; c++) if (grid[r][c]) n++;
    //    return n;
    //}

    private int countBuildBlocksInHotbar() {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 9; i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (isBuildBlock(stack)) total += stack.getCount();
        }
        return total;
    }

    //private boolean hotbarHasEnoughBuildBlocks() {
    //    int need = patternBlockCount();
    //    if (need <= 0) return true; // nothing selected
    //    int have = countBuildBlocksInHotbar();
    //    return have >= need;
    //}


    private boolean hotbarHasEnoughBuildBlocksFor(int need) {
        if (need <= 0) return true;
        int have = countBuildBlocksInHotbar();
        return have >= need;
    }
    private void maybeAutoDisableNow() {
        if (!autoDisable.get()) return;
        if (plannedPositions.isEmpty()) return;
        if (!allBlocksPlaced(plannedPositions)) return;

        Timer timer = Modules.get().get(Timer.class);
        if (timer != null) timer.setOverride(Timer.OFF);
        toggle();
    }
}