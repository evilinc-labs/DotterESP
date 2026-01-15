package Evil.group.addon.modules;

import Evil.group.addon.AntiDotterAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoCartographyModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> cloneDelay = sgGeneral.add(new IntSetting.Builder()
        .name("clone-delay")
        .description("Delay in ticks between cloning operations.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> searchRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("search-radius")
        .description("Radius to search for cartography tables.")
        .defaultValue(4.5)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> autoClose = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-close")
        .description("Automatically close the cartography table when done.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyCloneSelected = sgGeneral.add(new BoolSetting.Builder()
        .name("only-clone-selected")
        .description("Only clone the map currently in your hand.")
        .defaultValue(false)
        .build()
    );

    private int ticksSinceLastClone = 0;
    private boolean isProcessing = false;
    private ItemStack targetMap = null;
    private boolean waitingForOutput = false;

    public AutoCartographyModule() {
        super(AntiDotterAddon.CATEGORY, "AutoCartography", "Automatically clones maps using cartography tables.");
    }

    @Override
    public void onActivate() {
        ticksSinceLastClone = 0;
        isProcessing = false;
        
        // If only cloning selected, save the currently held map
        if (onlyCloneSelected.get() && mc.player != null) {
            ItemStack held = mc.player.getMainHandStack();
            if (held.getItem() == Items.FILLED_MAP) {
                targetMap = held.copy();
                info("Will only clone the selected map.");
            } else {
                warning("No filled map in hand. Module will clone any maps.");
                targetMap = null;
            }
        }
    }

    @Override
    public void onDeactivate() {
        isProcessing = false;
        targetMap = null;
        waitingForOutput = false;
        
        // Close cartography table if open
        if (mc.player != null && mc.player.currentScreenHandler instanceof CartographyTableScreenHandler) {
            mc.player.closeHandledScreen();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        ticksSinceLastClone++;

        // If we're in a cartography table screen, handle cloning
        if (mc.player.currentScreenHandler instanceof CartographyTableScreenHandler handler) {
            if (ticksSinceLastClone < cloneDelay.get()) return;
            
            handleCartographyTable(handler);
            return;
        }

        // Otherwise, try to find and open a cartography table
        if (!isProcessing) {
            BlockPos tablePos = findNearbyCartographyTable();
            if (tablePos != null) {
                openCartographyTable(tablePos);
            }
        }
    }

    private BlockPos findNearbyCartographyTable() {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        int radius = (int) Math.ceil(searchRadius.get());

        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.CARTOGRAPHY_TABLE) {
                        double dist = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
                        if (dist < closestDist && dist <= searchRadius.get() * searchRadius.get()) {
                            closestDist = dist;
                            closest = pos;
                        }
                    }
                }
            }
        }

        return closest;
    }

    private void openCartographyTable(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null) return;

        // Create a block hit result for the cartography table
        BlockHitResult hitResult = new BlockHitResult(
            Vec3d.ofCenter(pos),
            Direction.UP,
            pos,
            false
        );

        // Interact with the cartography table
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        isProcessing = true;
    }

    private void handleCartographyTable(CartographyTableScreenHandler handler) {
        if (mc.player == null || mc.interactionManager == null) return;

        // Safety check
        if (handler.slots.size() < 3) return;

        ItemStack inputSlot = handler.getSlot(0).getStack();
        ItemStack emptyMapSlot = handler.getSlot(1).getStack();
        ItemStack outputSlot = handler.getSlot(2).getStack();

        // Priority 1: Take output if available
        if (!outputSlot.isEmpty() && outputSlot.getItem() == Items.FILLED_MAP) {
            mc.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
            ticksSinceLastClone = 0;
            info("Cloned map!");
            return;
        }

        // Priority 2: Shift-click filled map into input slot if empty
        if (inputSlot.isEmpty()) {
            FindItemResult mapResult = findMapToClone();
            if (!mapResult.found()) {
                // No more maps to clone
                return;
            }

            int handlerSlot = mapResult.slot() + 3;
            mc.interactionManager.clickSlot(handler.syncId, handlerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
            ticksSinceLastClone = 0;
            return;
        }

        // That's it! User manually places empty maps, we just keep input filled and take outputs
    }

    private FindItemResult findMapToClone() {
        if (onlyCloneSelected.get() && targetMap != null) {
            // Find maps that match the target map's components
            return InvUtils.find(itemStack -> 
                itemStack.getItem() == Items.FILLED_MAP && 
                itemStack.getComponents().equals(targetMap.getComponents())
            );
        } else {
            // Find any filled map
            return InvUtils.find(Items.FILLED_MAP);
        }
    }
}