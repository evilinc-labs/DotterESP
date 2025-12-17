package Evil.group.addon.utils.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CompatReflect {
    private CompatReflect() {}

    // =========================
    // Core reflection cache
    // =========================
    private static volatile boolean REFLECT_READY = false;

    private static Field RENDERUTILS_CENTER_FIELD;   // RenderUtils.center (some versions)
    private static Field EVENT_TICKDELTA_FIELD;      // Render3DEvent.tickDelta (float/double, some versions)
    private static Method ENTITY_GET_LERPED_POS;     // Entity.getLerpedPos(float) (preferred, some versions)

    // Manual interpolation fallback
    private static Field ENTITY_LAST_X, ENTITY_LAST_Y, ENTITY_LAST_Z;

    private static void init() {
        if (REFLECT_READY) return;
        synchronized (CompatReflect.class) {
            if (REFLECT_READY) return;
            REFLECT_READY = true;

            try { RENDERUTILS_CENTER_FIELD = RenderUtils.class.getField("center"); }
            catch (Throwable ignored) { RENDERUTILS_CENTER_FIELD = null; }

            try { EVENT_TICKDELTA_FIELD = Render3DEvent.class.getField("tickDelta"); }
            catch (Throwable ignored) { EVENT_TICKDELTA_FIELD = null; }

            try { ENTITY_GET_LERPED_POS = Entity.class.getMethod("getLerpedPos", float.class); }
            catch (Throwable ignored) { ENTITY_GET_LERPED_POS = null; }

            try { ENTITY_LAST_X = Entity.class.getDeclaredField("lastX"); ENTITY_LAST_X.setAccessible(true); }
            catch (Throwable ignored) { ENTITY_LAST_X = null; }

            try { ENTITY_LAST_Y = Entity.class.getDeclaredField("lastY"); ENTITY_LAST_Y.setAccessible(true); }
            catch (Throwable ignored) { ENTITY_LAST_Y = null; }

            try { ENTITY_LAST_Z = Entity.class.getDeclaredField("lastZ"); ENTITY_LAST_Z.setAccessible(true); }
            catch (Throwable ignored) { ENTITY_LAST_Z = null; }
        }
    }

    // =========================
    // Depth test compat
    // =========================
    private static volatile boolean DEPTH_READY = false;
    private static Method RS_DISABLE_DEPTH, RS_ENABLE_DEPTH;
    private static Method GSM_DISABLE_DEPTH, GSM_ENABLE_DEPTH;

    private static void initDepth() {
        if (DEPTH_READY) return;
        synchronized (CompatReflect.class) {
            if (DEPTH_READY) return;
            DEPTH_READY = true;

            // 1.21.4-ish path
            try {
                RS_DISABLE_DEPTH = RenderSystem.class.getMethod("disableDepthTest");
                RS_ENABLE_DEPTH  = RenderSystem.class.getMethod("enableDepthTest");
                return;
            } catch (Throwable ignored) {}

            // 1.21.5+ may only expose GlStateManager._disableDepthTest/_enableDepthTest
            try {
                Class<?> gsm = Class.forName("com.mojang.blaze3d.systems.GlStateManager");
                GSM_DISABLE_DEPTH = gsm.getDeclaredMethod("_disableDepthTest");
                GSM_ENABLE_DEPTH  = gsm.getDeclaredMethod("_enableDepthTest");
            } catch (Throwable ignored) {}
        }
    }

    public static double tickDelta(Render3DEvent event) {
        init();

        if (EVENT_TICKDELTA_FIELD != null) {
            try {
                Object v = EVENT_TICKDELTA_FIELD.get(event);
                if (v instanceof Double d) return d;
                if (v instanceof Float f) return f;
            } catch (Throwable ignored) {}
        }

        return 1.0;
    }

    public static Vec3d tracerStart(MinecraftClient mc) {
        init();

        if (RENDERUTILS_CENTER_FIELD != null) {
            try {
                Object v = RENDERUTILS_CENTER_FIELD.get(null);
                if (v instanceof Vec3d c) return c;
            } catch (Throwable ignored) {}
        }

        return mc.gameRenderer.getCamera().getPos();
    }

    public static Vec3d lerpedPos(Entity e, double tickDelta) {
        init();

        // Preferred
        if (ENTITY_GET_LERPED_POS != null) {
            try {
                Object v = ENTITY_GET_LERPED_POS.invoke(e, (float) tickDelta);
                if (v instanceof Vec3d p) return p;
            } catch (Throwable ignored) {}
        }

        // Fallback interpolation
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

        return e.getPos();
    }

    public static void disableDepthTest() {
        initDepth();
        try {
            if (RS_DISABLE_DEPTH != null) RS_DISABLE_DEPTH.invoke(null);
            else if (GSM_DISABLE_DEPTH != null) GSM_DISABLE_DEPTH.invoke(null);
        } catch (Throwable ignored) {}
    }

    public static void enableDepthTest() {
        initDepth();
        try {
            if (RS_ENABLE_DEPTH != null) RS_ENABLE_DEPTH.invoke(null);
            else if (GSM_ENABLE_DEPTH != null) GSM_ENABLE_DEPTH.invoke(null);
        } catch (Throwable ignored) {}
    }

    // =========================
    // InvUtils / FindItemResult compat
    // =========================
    private static volatile boolean INVUTILS_READY = false;

    // Some Meteor versions: FindItemResult#isHotbar()
    private static Method FINDITEMRESULT_IS_HOTBAR;

    // Other Meteor versions: InvUtils#isHotbar(FindItemResult)
    private static Method INVUTILS_IS_HOTBAR_FIR;

    // Some Meteor versions: InvUtils#isHotbar(ItemStack)
    private static Method INVUTILS_IS_HOTBAR_STACK;

    private static void initInvUtils() {
        if (INVUTILS_READY) return;
        synchronized (CompatReflect.class) {
            if (INVUTILS_READY) return;
            INVUTILS_READY = true;

            try {
                FINDITEMRESULT_IS_HOTBAR = FindItemResult.class.getMethod("isHotbar");
            } catch (Throwable ignored) {
                FINDITEMRESULT_IS_HOTBAR = null;
            }

            try {
                INVUTILS_IS_HOTBAR_FIR = InvUtils.class.getMethod("isHotbar", FindItemResult.class);
            } catch (Throwable ignored) {
                INVUTILS_IS_HOTBAR_FIR = null;
            }

            try {
                INVUTILS_IS_HOTBAR_STACK = InvUtils.class.getMethod("isHotbar", ItemStack.class);
            } catch (Throwable ignored) {
                INVUTILS_IS_HOTBAR_STACK = null;
            }
        }
    }

    /**
     * Returns true if a FindItemResult points at the hotbar (0..8) in a version-safe way.
     *
     * WHY:
     * - Meteor moved this helper around between versions (FindItemResult vs InvUtils)
     *
     * FALLBACK:
     * - slot() in [0..8] (hotbar is always 0..8)
     */
    public static boolean isHotbar(FindItemResult fir) {
        initInvUtils();
        if (fir == null) return false;

        try {
            if (FINDITEMRESULT_IS_HOTBAR != null) {
                Object v = FINDITEMRESULT_IS_HOTBAR.invoke(fir);
                if (v instanceof Boolean b) return b;
            }
        } catch (Throwable ignored) {}

        try {
            if (INVUTILS_IS_HOTBAR_FIR != null) {
                Object v = INVUTILS_IS_HOTBAR_FIR.invoke(null, fir);
                if (v instanceof Boolean b) return b;
            }
        } catch (Throwable ignored) {}

        // Safe fallback: infer from slot index.
        try {
            return fir.found() && fir.slot() >= 0 && fir.slot() <= 8;
        } catch (Throwable ignored) {}

        return false;
    }

    /**
     * Version-safe wrapper for InvUtils.isHotbar(ItemStack).
     *
     * WHY:
     * - Some Meteor versions expose InvUtils.isHotbar(ItemStack)
     * - Others don't, but code may call it anyway (your compile error)
     *
     * FALLBACK:
     * - checks player's hotbar for a matching stack
     */
    public static boolean isHotbar(ItemStack stack) {
        initInvUtils();
        if (stack == null || stack.isEmpty()) return false;

        try {
            if (INVUTILS_IS_HOTBAR_STACK != null) {
                Object v = INVUTILS_IS_HOTBAR_STACK.invoke(null, stack);
                if (v instanceof Boolean b) return b;
            }
        } catch (Throwable ignored) {}

        // Fallback: scan player hotbar
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack hot = inv.getStack(i);
            if (!hot.isEmpty() && ItemStack.areItemsAndComponentsEqual(hot, stack)) return true;
        }
        return false;
    }

    // =========================
    // Inventory compat (selected hotbar slot)
    // =========================
    private static volatile boolean INV_READY = false;
    private static Method PI_GET_SELECTED_SLOT;
    private static Method PI_SET_SELECTED_SLOT;
    private static Field  PI_SELECTED_SLOT_FIELD;

    private static void initInventory() {
        if (INV_READY) return;
        synchronized (CompatReflect.class) {
            if (INV_READY) return;
            INV_READY = true;

            // Newer mappings: PlayerInventory#getSelectedSlot()
            try { PI_GET_SELECTED_SLOT = PlayerInventory.class.getMethod("getSelectedSlot"); }
            catch (Throwable ignored) { PI_GET_SELECTED_SLOT = null; }

            // Newer mappings: PlayerInventory#setSelectedSlot(int)
            try { PI_SET_SELECTED_SLOT = PlayerInventory.class.getMethod("setSelectedSlot", int.class); }
            catch (Throwable ignored) { PI_SET_SELECTED_SLOT = null; }

            // Older mappings: PlayerInventory#selectedSlot field
            try {
                PI_SELECTED_SLOT_FIELD = PlayerInventory.class.getDeclaredField("selectedSlot");
                PI_SELECTED_SLOT_FIELD.setAccessible(true);
            } catch (Throwable ignored) {
                PI_SELECTED_SLOT_FIELD = null;
            }
        }
    }

    public static int getSelectedHotbarSlot(PlayerInventory inv) {
        initInventory();
        if (inv == null) return 0;

        if (PI_GET_SELECTED_SLOT != null) {
            try {
                Object v = PI_GET_SELECTED_SLOT.invoke(inv);
                if (v instanceof Number n) return n.intValue();
            } catch (Throwable ignored) {}
        }

        if (PI_SELECTED_SLOT_FIELD != null) {
            try {
                Object v = PI_SELECTED_SLOT_FIELD.get(inv);
                if (v instanceof Number n) return n.intValue();
            } catch (Throwable ignored) {}
        }

        return 0;
    }

    public static void setSelectedHotbarSlot(PlayerInventory inv, int slot) {
        initInventory();
        if (inv == null) return;

        if (PI_SET_SELECTED_SLOT != null) {
            try { PI_SET_SELECTED_SLOT.invoke(inv, slot); return; }
            catch (Throwable ignored) {}
        }

        if (PI_SELECTED_SLOT_FIELD != null) {
            try { PI_SELECTED_SLOT_FIELD.setInt(inv, slot); }
            catch (Throwable ignored) {}
        }
    }

    // =========================
    // Input compat (movement vector)
    // =========================
    private static volatile boolean INPUT_READY = false;
    private static Field INPUT_MOVEMENT_VECTOR;      // newer
    private static Field INPUT_MOVEMENT_FORWARD;     // older
    private static Field INPUT_MOVEMENT_SIDEWAYS;    // older

    private static void initInput() {
        if (INPUT_READY) return;
        synchronized (CompatReflect.class) {
            if (INPUT_READY) return;
            INPUT_READY = true;

            try {
                INPUT_MOVEMENT_VECTOR = Input.class.getDeclaredField("movementVector");
                INPUT_MOVEMENT_VECTOR.setAccessible(true);
            } catch (Throwable ignored) {
                INPUT_MOVEMENT_VECTOR = null;
            }

            try {
                INPUT_MOVEMENT_FORWARD = Input.class.getDeclaredField("movementForward");
                INPUT_MOVEMENT_FORWARD.setAccessible(true);
            } catch (Throwable ignored) {
                INPUT_MOVEMENT_FORWARD = null;
            }

            try {
                INPUT_MOVEMENT_SIDEWAYS = Input.class.getDeclaredField("movementSideways");
                INPUT_MOVEMENT_SIDEWAYS.setAccessible(true);
            } catch (Throwable ignored) {
                INPUT_MOVEMENT_SIDEWAYS = null;
            }
        }
    }

    /** Version-safe read of movement input. */
    public static Vec2f movementVec(Input input) {
        initInput();
        if (input == null) return new Vec2f(0, 0);

        // Newer: Vec2f movementVector
        if (INPUT_MOVEMENT_VECTOR != null) {
            try {
                Object v = INPUT_MOVEMENT_VECTOR.get(input);
                if (v instanceof Vec2f vec) return vec;
            } catch (Throwable ignored) {}
        }

        // Older: floats movementForward / movementSideways
        float forward = 0, sideways = 0;
        if (INPUT_MOVEMENT_FORWARD != null) {
            try { forward = ((Number) INPUT_MOVEMENT_FORWARD.get(input)).floatValue(); }
            catch (Throwable ignored) {}
        }
        if (INPUT_MOVEMENT_SIDEWAYS != null) {
            try { sideways = ((Number) INPUT_MOVEMENT_SIDEWAYS.get(input)).floatValue(); }
            catch (Throwable ignored) {}
        }

        return new Vec2f(sideways, forward); // x=sideways, y=forward
    }

    public static boolean isMovingInput(Input input) {
        Vec2f v = movementVec(input);
        return v.x != 0.0f || v.y != 0.0f;
    }
}
