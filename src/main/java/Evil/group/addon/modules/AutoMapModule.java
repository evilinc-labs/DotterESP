package Evil.group.addon.modules;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import Evil.group.addon.AntiDotterAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AutoMapModule extends Module {
    private final Cache<Integer, Integer> pending = CacheBuilder.newBuilder().expireAfterWrite(250, TimeUnit.MILLISECONDS).build();

    public AutoMapModule() {
        super(AntiDotterAddon.CATEGORY, "AutoMap", "Place maps nigga");
    }

    @EventHandler
    private void onTick(TickEvent.Pre ignored) {
        FindItemResult itemResult = InvUtils.findInHotbar(Items.FILLED_MAP);
        if (!itemResult.found()) {
            info("No maparts found in hotbar.");
            toggle();
            return;
        }

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
                pending.put(itemFrame.getId(), 1);
                return;
            } else if (stack.getItem() != Items.FILLED_MAP || !InvUtils.findInHotbar(item -> item.getItem() == Items.FILLED_MAP && item.getComponents().equals(itemFrame.getHeldItemStack().getComponents())).found()) {
                mc.player.swingHand(Hand.MAIN_HAND);
                mc.interactionManager.attackEntity(mc.player, itemFrame);
                pending.put(itemFrame.getId(), 1);
                return;
            }
        }
    }
}