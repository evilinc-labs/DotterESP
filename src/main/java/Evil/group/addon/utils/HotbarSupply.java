package Evil.group.addon.utils;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

import java.util.function.Predicate;

import Evil.group.addon.utils.compat.CompatReflect;

/**
 * Pattern-aware hotbar supplier for 1.21.5.
 * Keeps the right block in the hotbar and refills when low.
 * Uses PlayerInventory.getSelectedSlot()/setSelectedSlot(int) (no direct field access).
 */
public final class HotbarSupply {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private HotbarSupply() {}

    /**
     * Ensure a stack that matches `matcher` exists in hotbar; if its count < threshold,
     * replace it with a full (or largest) stack from inventory.
     *
     * @param matcher          predicate to match target items (e.g., a specific BlockItem)
     * @param refillThreshold  when current hotbar count < this, pull a full / largest stack
     * @param selectSlot       if true, switch held item to the matched hotbar slot
     * @return hotbar slot [0..8], or -1 if none found anywhere
     */
    public static int ensureHotbarStack(Predicate<ItemStack> matcher, int refillThreshold, boolean selectSlot) {
        if (mc.player == null) return -1;
        var inv = mc.player.getInventory();

        // Find in hotbar
        FindItemResult inHotbar = InvUtils.findInHotbar(matcher);

        // Not in hotbar? Move best stack from main inventory into hotbar.
        if (!inHotbar.found()) {
            int bestInv = findBestInventoryStack(matcher);
            if (bestInv == -1) return -1;

            int dest = findEmptyHotbarSlot();
            if (dest == -1) dest = findSmallestHotbarStack(matcher);
            if (dest == -1) dest = CompatReflect.getSelectedHotbarSlot(inv);

            InvUtils.move().from(bestInv).toHotbar(dest);
            if (selectSlot) CompatReflect.setSelectedHotbarSlot(inv, dest);
            return dest;
        }

        int slot = inHotbar.slot();
        int count = inv.getStack(slot).getCount();

        // Refill if low (prefer full stack; else largest)
        if (count < refillThreshold) {
            int bestInv = findBestInventoryStack(matcher);
            if (bestInv != -1) {
                InvUtils.move().from(bestInv).to(slot);
            }
        }

        if (selectSlot) inv.setSelectedSlot(slot);
        return slot;
    }

    /** Convenience predicate for a specific Block. */
    public static Predicate<ItemStack> blockIs(net.minecraft.block.Block block) {
        return s -> !s.isEmpty() && s.getItem() instanceof BlockItem bi && bi.getBlock() == block;
    }

    // ----- internals -----
    private static int findEmptyHotbarSlot() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getStack(i).isEmpty()) return i;
        return -1;
    }

    private static int findSmallestHotbarStack(Predicate<ItemStack> matcher) {
        var inv = mc.player.getInventory();
        int slot = -1, min = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (matcher.test(s)) {
                int c = s.getCount();
                if (c < min) { min = c; slot = i; }
            }
        }
        return slot;
    }

    /** Search main inventory (9..35) for full stack first, else largest. */
    private static int findBestInventoryStack(Predicate<ItemStack> matcher) {
        var inv = mc.player.getInventory();
        int best = -1, bestCount = -1;
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack s = inv.getStack(slot);
            if (!s.isEmpty() && matcher.test(s)) {
                int c = s.getCount();
                if (c == s.getMaxCount()) return slot; // full stack wins
                if (c > bestCount) { bestCount = c; best = slot; }
            }
        }
        return best;
    }
}