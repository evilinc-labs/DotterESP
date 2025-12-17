package Evil.group.addon.modules;

import Evil.group.addon.AntiDotterAddon;
import Evil.group.addon.utils.HotbarSupply;
import Evil.group.addon.utils.compat.CompatReflect;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

// these are needed to save the pattern to file
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


import java.util.ArrayList;
import java.util.List;

public class AutoBuilder extends Module {
    public enum BuildMode {
        Vertical,
        Horizontal
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<BuildMode> buildMode = sgGeneral.add(new EnumSetting.Builder<BuildMode>()
        .name("build-mode")
        .description("Vertical builds a wall, Horizontal builds on the ground.")
        .defaultValue(BuildMode.Vertical)
        .build()
    );

    private final Setting<List<Block>> blocksToUse = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to use for building.")
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

    private final Setting<Integer> offsetX = sgGeneral.add(new IntSetting.Builder()
        .name("offset-x")
        .description("X offset from player.")
        .defaultValue(0)
        .sliderRange(-10, 10)
        .build()
    );

    private final Setting<Integer> offsetY = sgGeneral.add(new IntSetting.Builder()
        .name("offset-y")
        .description("Y offset from player.")
        .defaultValue(0)
        .sliderRange(-10, 10)
        .build()
    );

    private final Setting<Integer> offsetZ = sgGeneral.add(new IntSetting.Builder()
        .name("offset-z")
        .description("Z offset from player.")
        .defaultValue(0)
        .sliderRange(-10, 10)
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

        if (autoOrientation.get() && mc.player != null) {
            buildDirection = mc.player.getHorizontalFacing();
        }

        if (savePatternToFile.get()) {
            try { savePatternToFile(); } catch (Throwable ignored) {}
        }

        if (floating.get()) {
            Timer timer = Modules.get().get(Timer.class);
            if (timer != null) timer.setOverride(0.01);
        }
    }

    @Override
    public void onDeactivate() {
        Timer timer = Modules.get().get(Timer.class);
        if (timer != null) {
            timer.setOverride(Timer.OFF);
        }
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // Try placing on every move event for faster placement
        tryPlace();
        
        // Auto-disable check
        List<BlockPos> positions = getBlocksToPlace();
        if (!positions.isEmpty() && autoDisable.get() && allBlocksPlaced(positions)) {
            // Disable timer before toggling so it happens instantly
            Timer timer = Modules.get().get(Timer.class);
            if (timer != null) {
                timer.setOverride(Timer.OFF);
            }
            toggle();
        }
    }

    private void tryPlace() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPlaceTime < delayMs.get()) return;

        List<BlockPos> positions = getBlocksToPlace();
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
                    
                    // Re-enable timer after placement
                    if (wasFloating) {
                        timer.setOverride(0.01);
                    }
                    
                    lastPlaceTime = now;
                    currentIndex++;
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

        // Try to find a support block first for normal placement
        Direction[] directions = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        for (Direction dir : directions) {
            BlockPos neighbor = pos.offset(dir);
            if (!mc.world.getBlockState(neighbor).isReplaceable()) {
                // Place against this solid neighbor using Grim bypass method
                Vec3d hitVec = Vec3d.ofCenter(neighbor).add(
                    dir.getOpposite().getOffsetX() * 0.5,
                    dir.getOpposite().getOffsetY() * 0.5,
                    dir.getOpposite().getOffsetZ() * 0.5
                );
                BlockHitResult bhr = new BlockHitResult(hitVec, dir.getOpposite(), neighbor, false);
                grimPlace(bhr);
                return;
            }
        }

        // No support block found - airplace using Grim bypass (if enabled)
        if (!airPlace.get()) return;
        
        Vec3d hitPos = Vec3d.ofCenter(pos);
        BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, pos, false);
        grimPlace(bhr);
    }

    private void grimPlace(BlockHitResult blockHitResult) {
        if (mc.player == null) return;

        // Grim bypass: swap to offhand, place with offhand, swap back
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, 
            new BlockPos(0, 0, 0), 
            Direction.DOWN
        ));

        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.OFF_HAND, 
            blockHitResult, 
            mc.player.currentScreenHandler.getRevision() + 2
        ));

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, 
            new BlockPos(0, 0, 0), 
            Direction.DOWN
        ));

        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private List<BlockPos> getBlocksToPlace() {
        List<BlockPos> positions = new ArrayList<>();
        if (mc.player == null) return positions;

        BlockPos playerPos = mc.player.getBlockPos();

        if (buildMode.get() == BuildMode.Vertical) {
            // Vertical mode: builds a wall (X/Z is horizontal, Y is vertical)
            int baseY = playerPos.getY() + offsetY.get() + 2;

            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 5; col++) {
                    if (grid[row][col]) {
                        int y = baseY - row;
                        int horizontalOffset = col - 2; // -2 to +2 for 5x5 grid centered
                        
                        int x = playerPos.getX() + offsetX.get();
                        int z = playerPos.getZ() + offsetZ.get();
                        
                        // Apply orientation based on build direction
                        switch (buildDirection) {
                            case NORTH -> z += -1;
                            case SOUTH -> z += 1;
                            case EAST -> x += 1;
                            case WEST -> x += -1;
                            default -> z += -1;
                        }
                        
                        // Apply horizontal offset perpendicular to facing direction
                        switch (buildDirection) {
                            case NORTH, SOUTH -> x += horizontalOffset;
                            case EAST, WEST -> z += horizontalOffset;
                            default -> x += horizontalOffset;
                        }
                        
                        positions.add(new BlockPos(x, y, z));
                    }
                }
            }
        } else {
            // Horizontal mode: builds on the ground (X/Z plane)
            int y = playerPos.getY() + offsetY.get();

            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 5; col++) {
                    if (grid[row][col]) {
                        int forwardOffset = row - 2;  // -2 to +2 (row 0 = behind, row 4 = in front)
                        int sideOffset = col - 2;     // -2 to +2 (col 0 = left, col 4 = right)
                        
                        int x = playerPos.getX() + offsetX.get();
                        int z = playerPos.getZ() + offsetZ.get();
                        
                        // Apply forward/backward offset based on build direction
                        switch (buildDirection) {
                            case NORTH -> {
                                z -= forwardOffset;
                                x += sideOffset;
                            }
                            case SOUTH -> {
                                z += forwardOffset;
                                x -= sideOffset;
                            }
                            case EAST -> {
                                x += forwardOffset;
                                z += sideOffset;
                            }
                            case WEST -> {
                                x -= forwardOffset;
                                z -= sideOffset;
                            }
                            default -> {
                                z -= forwardOffset;
                                x += sideOffset;
                            }
                        }
                        
                        positions.add(new BlockPos(x, y, z));
                    }
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
        return InvUtils.findInHotbar(itemStack -> {
            if (!(itemStack.getItem() instanceof BlockItem blockItem)) return false;
            return blocksToUse.get().contains(blockItem.getBlock());
        });
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        // Try to place blocks every frame (not just per tick) for faster placement
        tryPlace();
        
        if (!render.get() || mc.player == null || mc.world == null) return;

        for (BlockPos pos : getBlocksToPlace()) {
            if (mc.world.getBlockState(pos).isReplaceable()) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }
    private boolean isBuildBlock(net.minecraft.item.ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        return blocksToUse.get().contains(blockItem.getBlock());
    }

    /** Load once when the module object is created (constructor). */
    private void tryLoadPatternOnInit() {
        try {
            Path file = getPatternFilePath();
            if (Files.exists(file)) loadPatternFromFile(file);
        } catch (Throwable ignored) {}
    }

}