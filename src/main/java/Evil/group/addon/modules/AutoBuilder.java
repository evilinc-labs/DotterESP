package Evil.group.addon.modules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import Evil.group.addon.AntiDotterAddon;
import Evil.group.addon.utils.HotbarSupply;
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
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

abstract class BlockSelectionStrategy {
    protected final AutoBuilder ab;
    protected BlockSelectionStrategy(AutoBuilder ab) { this.ab = ab; }
    public abstract FindItemResult findBlock();
    public abstract boolean isBuildBlock(ItemStack stack);
}

class Single extends BlockSelectionStrategy {
    public Single(AutoBuilder ab) { super(ab); }

    @Override
    public FindItemResult findBlock() {
        Block sel = ab.blockToUse.get();
        return InvUtils.findInHotbar(s -> s.getItem() instanceof BlockItem bi && bi.getBlock() == sel);
    }

    @Override
    public boolean isBuildBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() == ab.blockToUse.get();
    }
}

class Random extends BlockSelectionStrategy {
    public Random(AutoBuilder ab) { super(ab); }
    // Select random block from pool and in hotbar for placement.
    @Override
    public FindItemResult findBlock() {
        int rnd  = (int) (Math.random() * ab.blockPool.get().size());
        Block sel = ab.blockPool.get().get(rnd);
        return InvUtils.findInHotbar(s -> s.getItem() instanceof BlockItem bi && bi.getBlock() == sel);
    }

    @Override
    public boolean isBuildBlock(ItemStack stack) {
        boolean isInPool = ab.blockPool.get().contains(((BlockItem)stack.getItem()).getBlock());
        return stack.getItem() instanceof BlockItem && isInPool;
    }
}

public class AutoBuilder extends Module {
    public enum BuildMode { Vertical, Horizontal }
    public enum VerticalAnchor { InFront, Behind }

    // Enum backed factory so we can fetch the intended strategy transparently.
    public enum BlockSelectionStrategyOption {
        Single(Single::new),
        Random(Random::new);

        private final java.util.function.Function<AutoBuilder, BlockSelectionStrategy> factory;
        BlockSelectionStrategyOption(java.util.function.Function<AutoBuilder, BlockSelectionStrategy> factory) {
            this.factory = factory;
        }
        BlockSelectionStrategy create(AutoBuilder ab) { return factory.apply(ab); }
    }

    // Delegate so isBuildBlock can be passed around as a higher order function.
    private boolean isBuildBlock(ItemStack stack) {
        return getSelectedStrategy().isBuildBlock(stack);
    }


    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private static final int GridSide = 7;

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

    private final Setting<BlockSelectionStrategyOption> blockSelectionStrategySetting = sgGeneral.add(new EnumSetting.Builder<BlockSelectionStrategyOption>()
        .name("block-selection-mode")
        .description("How blocks are chosen for placement.")
        .defaultValue(BlockSelectionStrategyOption.Single)
        .build()
    );

    public final Setting<Block> blockToUse = sgGeneral.add(new BlockSetting.Builder()
        .name("block")
        .description("Block to use for building when in single-block mode.")
        .defaultValue(Blocks.OBSIDIAN)
        .build()
    );

    public final Setting<List<Block>> blockPool = sgGeneral.add(new BlockListSetting.Builder()
        .name("block-pool")
        .description("List of allowed blocks select from when using multi-block modes.")
        .defaultValue(Blocks.OBSIDIAN)
        .build()
    );

    // Fetches the selected strategy
    private BlockSelectionStrategy getSelectedStrategy() {
        return blockSelectionStrategySetting.get().create(this);
    }

    private final Setting<Integer> delayMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ms")
        .description("Delay between block placements in milliseconds.")
        .defaultValue(30)
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
        .description("How slow to run client ticks while building. Lower = more float.")
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

    private final Setting<Integer> offsetX = sgGeneral.add(new IntSetting.Builder()
        .name("offset-X")
        .description("X offset from player.")
        .defaultValue(0)
        .sliderRange(-5, 5)
        .build()
    );

    private final Setting<Integer> offsetY = sgGeneral.add(new IntSetting.Builder()
        .name("offset-y")
        .description("Y offset from player.")
        .defaultValue(0)
        .sliderRange(-5, 5)
        .build()
    );

    private final Setting<Integer> offsetZ = sgGeneral.add(new IntSetting.Builder()
        .name("offset-Z")
        .description("Z offset from player.")
        .defaultValue(0)
        .sliderRange(-5, 5)
        .build()
    );

    private final Setting<Integer> offsetForward = sgGeneral.add(new IntSetting.Builder()
        .name("offset-forward")
        .description("Offset in blocks along the direction you're facing.")
        .defaultValue(0)
        .sliderRange(-10, 10)
        .build()
    );

    private final Setting<Boolean> autoReplenish = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-replenish")
        .description("Refill the hotbar block stack from inventory when low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> replenishThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("replenish-threshold")
        .description("Refill when stack is at or below this count.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 64)
        .visible(autoReplenish::get)
        .build()
    );

    private final Setting<Boolean> useFreeLook = sgGeneral.add(new BoolSetting.Builder()
        .name("freelook")
        .description("Enable FreeLook while module is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> savePatternToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("save-pattern-to-file")
        .description("Save pattern to config/Evil/autoBuilder.json on enable.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showBuildCounter = sgGeneral.add(new BoolSetting.Builder()
        .name("show-build-counter")
        .description("Show total builds in module info.")
        .defaultValue(true)
        .build()
    );

    // Render settings
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

    // Constants
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PATTERN_DIR = "Evil";
    private static final String PATTERN_FILE = "autoBuilder.json";
    private static final String COUNTER_FILE = "autoBuilderStats.json";

    // 2b2t rate limit: 9 blocks per 300ms
    private static final int MAX_PLACES_PER_WINDOW = 9;
    private static final long PLACE_WINDOW_MS = 300;

    // State
    private final boolean[][] grid = new boolean[GridSide][GridSide];
    private final ArrayDeque<Long> placeTimes = new ArrayDeque<>();
    private List<BlockPos> plannedPositions = List.of();
    private BlockPos activationPlayerPos = null;
    private Direction activationFacing = Direction.NORTH;
    private Direction buildDirection = Direction.NORTH;
    private long lastPlaceTime = 0;
    private int currentIndex = 0;
    private int totalBuildsCompleted = 0;
    private boolean buildCountedThisSession = false;

    public AutoBuilder() {
        super(AntiDotterAddon.CATEGORY, "auto-builder", String.format("Builds %dx%d patterns. Made for 2b2t.", GridSide, GridSide));
        tryLoadPatternOnInit();
        loadBuildCounter();
    }

    @Override
    public String getInfoString() {
        return showBuildCounter.get() ? "Builds: " + totalBuildsCompleted : null;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WTable table = theme.table();
        list.add(table);

        for (int row = 0; row < GridSide; row++) {
            for (int col = 0; col < GridSide; col++) {
                final int r = row, c = col;
                WCheckbox cb = table.add(theme.checkbox(grid[r][c])).widget();
                cb.action = () -> grid[r][c] = cb.checked;
            }
            table.row();
        }
        return list;
    }

    @Override
    public void onActivate() {
        lastPlaceTime = 0;
        currentIndex = 0;
        buildCountedThisSession = false;
        placeTimes.clear();

        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        if (useFreeLook.get()) {
            FreeLook fl = Modules.get().get(FreeLook.class);
            if (fl != null && !fl.isActive()) fl.toggle();
        }

        if (autoOrientation.get()) {
            buildDirection = mc.player.getHorizontalFacing();
        }

        activationPlayerPos = mc.player.getBlockPos();
        activationFacing = buildDirection;
        plannedPositions = computeBlocksToPlace(activationPlayerPos, activationFacing);

        int need = plannedPositions.size();
        if (need == 0) {
            warnAutoBuilder("No pattern selected (grid is empty).");
            toggle();
            return;
        }

        if (!hotbarHasEnoughBuildBlocksFor(need)) {
            warnAutoBuilder("Not enough blocks in hotbar.");
            toggle();
            return;
        }

        if (savePatternToFile.get()) savePatternToFile();

        if (floating.get()) {
            Timer timer = Modules.get().get(Timer.class);
            if (timer != null) timer.setOverride(floatTimerScale.get());
        }
    }

    @Override
    public void onDeactivate() {
        // Always reset timer on deactivate
        Timer timer = Modules.get().get(Timer.class);
        if (timer != null) timer.setOverride(Timer.OFF);

        if (useFreeLook.get()) {
            FreeLook fl = Modules.get().get(FreeLook.class);
            if (fl != null && fl.isActive()) fl.toggle();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Place blocks (runs at real-time frame rate, not affected by timer)
        tryPlace();

        // Auto-disable check - runs every frame at real time!
        if (autoDisable.get() && !plannedPositions.isEmpty() && allBlocksPlaced()) {
            completeAndDisable();
            return;
        }

        // Render preview
        if (render.get()) {
            for (BlockPos pos : plannedPositions) {
                if (mc.world.getBlockState(pos).isReplaceable()) {
                    event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
            }
        }
    }

    private void tryPlace() {
        BlockSelectionStrategy bs = getSelectedStrategy();

        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPlaceTime < delayMs.get()) return;
        if (!canPlaceNow(now)) return;

        if (plannedPositions.isEmpty()) return;

        while (currentIndex < plannedPositions.size()) {
            BlockPos pos = plannedPositions.get(currentIndex);

            if (mc.world.getBlockState(pos).isReplaceable() && isInRange(pos)) {
                FindItemResult block = bs.findBlock();
                if (block.found()) {
                    if (autoReplenish.get()) {
                        HotbarSupply.ensureHotbarStack(this::isBuildBlock, replenishThreshold.get(), false);
                    }

                    InvUtils.swap(block.slot(), false);

                    if (rotate.get()) {
                        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 100, true, () -> {});
                    }

                    placeBlockAt(pos);
                    recordPlace(now);
                    lastPlaceTime = now;
                    currentIndex++;
                    return;
                }
            }
            currentIndex++;
        }

        if (currentIndex >= plannedPositions.size()) {
            currentIndex = 0;
        }
    }

    private void completeAndDisable() {
        // Increment counter first
        if (!buildCountedThisSession) {
            buildCountedThisSession = true;
            totalBuildsCompleted++;
            saveBuildCounter();
        }

        // Reset timer BEFORE toggle to ensure it takes effect
        Timer timer = Modules.get().get(Timer.class);
        if (timer != null) timer.setOverride(Timer.OFF);

        // Small delay to let timer reset propagate, then toggle
        toggle();
    }

    private boolean allBlocksPlaced() {
        if (mc.world == null) return false;
        for (BlockPos pos : plannedPositions) {
            if (mc.world.getBlockState(pos).isReplaceable()) return false;
        }
        return true;
    }

    private void placeBlockAt(BlockPos pos) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.world.getBlockState(pos).isReplaceable()) return;

        Direction clickedSide = Direction.UP;
        BlockPos clickPos = null;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (!mc.world.getBlockState(neighbor).isReplaceable()) {
                clickPos = neighbor;
                clickedSide = dir.getOpposite();
                break;
            }
        }

        if (clickPos == null) {
            if (!airPlace.get()) return;
            clickPos = pos;
            clickedSide = Direction.UP;
        }

        Vec3d hitVec = Vec3d.ofCenter(clickPos).add(
            clickedSide.getOffsetX() * 0.5,
            clickedSide.getOffsetY() * 0.5,
            clickedSide.getOffsetZ() * 0.5
        );

        grimPlace(new BlockHitResult(hitVec, clickedSide, clickPos, false));
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

    private List<BlockPos> computeBlocksToPlace(BlockPos playerPos, Direction facing) {
        List<BlockPos> positions = new ArrayList<>();
        if (playerPos == null) return positions;

        int forwardOff = offsetForward.get();
        int offX = offsetX.get(), offZ = offsetZ.get();
        switch (facing) {
            case NORTH -> offZ = -forwardOff;
            case SOUTH -> offZ = forwardOff;
            case EAST -> offX = forwardOff;
            case WEST -> offX = -forwardOff;
            default -> offZ = -forwardOff;
        }

        if (buildMode.get() == BuildMode.Vertical) {
            int baseY = playerPos.getY() + offsetY.get() + 2;
            int baseX = playerPos.getX() + offX;
            int baseZ = playerPos.getZ() + offZ;
            int step = (verticalAnchor.get() == VerticalAnchor.InFront) ? 2 : -2;

            switch (facing) {
                case NORTH -> baseZ -= step;
                case SOUTH -> baseZ += step;
                case EAST -> baseX += step;
                case WEST -> baseX -= step;
                default -> baseZ -= step;
            }

            for (int row = 0; row < GridSide; row++) {
                for (int col = 0; col < GridSide; col++) {
                    if (!grid[row][col]) continue;
                    int y = baseY - row;
                    int hOff = col - 2;
                    int x = baseX, z = baseZ;
                    switch (facing) {
                        case NORTH, SOUTH -> x += hOff;
                        case EAST, WEST -> z += hOff;
                        default -> x += hOff;
                    }
                    positions.add(new BlockPos(x, y, z));
                }
            }
        } else {
            int y = playerPos.getY() + offsetY.get();
            for (int row = 0; row < GridSide; row++) {
                for (int col = 0; col < GridSide; col++) {
                    if (!grid[row][col]) continue;
                    int fOff = row - 2, sOff = col - 2;
                    int x = playerPos.getX() + offX;
                    int z = playerPos.getZ() + offZ;
                    switch (facing) {
                        case NORTH -> { z -= fOff; x += sOff; }
                        case SOUTH -> { z += fOff; x -= sOff; }
                        case EAST -> { x += fOff; z += sOff; }
                        case WEST -> { x -= fOff; z -= sOff; }
                        default -> { z -= fOff; x += sOff; }
                    }
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return positions;
    }

    private boolean canPlaceNow(long now) {
        while (!placeTimes.isEmpty() && now - placeTimes.peekFirst() > PLACE_WINDOW_MS) {
            placeTimes.pollFirst();
        }
        return placeTimes.size() < MAX_PLACES_PER_WINDOW;
    }

    private void recordPlace(long now) { placeTimes.addLast(now); }

    private boolean isInRange(BlockPos pos) {
        return mc.player != null && mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= placeRange.get();
    }

    private boolean hotbarHasEnoughBuildBlocksFor(int need) {
        if (need <= 0) return true;
        int have = 0;
        if (mc.player != null) {
            for (int i = 0; i < 9; i++) {
                var s = mc.player.getInventory().getStack(i);
                if (isBuildBlock(s)) have += s.getCount();
            }
        }
        return have >= need;
    }

    private void warnAutoBuilder(String msg) {
        MutableText t = Text.literal("[").formatted(Formatting.GRAY)
            .append(Text.literal("AutoBuilder").formatted(Formatting.RED))
            .append(Text.literal("] ").formatted(Formatting.GRAY))
            .append(Text.literal(msg).formatted(Formatting.GRAY));
        mc.inGameHud.getChatHud().addMessage(t);
    }

    // File I/O
    private static class AutoBuilderPatternFile { int version = 1; boolean[][] grid = new boolean[GridSide][GridSide]; }
    private static class AutoBuilderStatsFile { int version = 1; int totalBuildsCompleted = 0; }

    private static Path getPatternFilePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(PATTERN_DIR).resolve(PATTERN_FILE);
    }
    private static Path getCounterFilePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(PATTERN_DIR).resolve(COUNTER_FILE);
    }

    private void tryLoadPatternOnInit() {
        try {
            Path f = getPatternFilePath();
            if (Files.exists(f)) loadPatternFromFile(f);
        } catch (Throwable ignored) {}
    }

    private void loadPatternFromFile(Path file) {
        try {
            var data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AutoBuilderPatternFile.class);
            if (data != null && data.grid != null) {
                for (int r = 0; r < GridSide; r++) for (int c = 0; c < GridSide; c++)
                    grid[r][c] = r < data.grid.length && data.grid[r] != null && c < data.grid[r].length && data.grid[r][c];
            }
        } catch (JsonSyntaxException | java.io.IOException ignored) {}
    }

    private void savePatternToFile() {
        try {
            Path f = getPatternFilePath();
            Files.createDirectories(f.getParent());
            var data = new AutoBuilderPatternFile();
            for (int r = 0; r < GridSide; r++) System.arraycopy(grid[r], 0, data.grid[r], 0, GridSide);
            Files.writeString(f, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }

    private void loadBuildCounter() {
        try {
            Path f = getCounterFilePath();
            if (!Files.exists(f)) return;
            var data = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), AutoBuilderStatsFile.class);
            if (data != null) totalBuildsCompleted = data.totalBuildsCompleted;
        } catch (Throwable ignored) {}
    }

    private void saveBuildCounter() {
        try {
            Path f = getCounterFilePath();
            Files.createDirectories(f.getParent());
            var data = new AutoBuilderStatsFile();
            data.totalBuildsCompleted = totalBuildsCompleted;
            Files.writeString(f, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }
}
