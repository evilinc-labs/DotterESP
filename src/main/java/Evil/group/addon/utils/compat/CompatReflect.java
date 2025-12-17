/**
 * CompatReflect
 *
 * Centralized reflection-based compatibility layer for Meteor / Minecraft
 * versions 1.21.4, 1.21.5, and 1.21.8.
 *
 * WHY THIS EXISTS:
 * Meteor and Minecraft internals change between minor versions:
 * - Fields may be added, removed, or renamed
 * - Methods may move or disappear
 * - Rendering utilities may change implementation
 *
 * This class hides all version-specific reflection logic behind
 * stable helper methods so modules do NOT:
 * - depend on mappings
 * - duplicate fragile reflection code
 * - break when upgrading MC/Meteor versions
 *
 * If something breaks in a future version, fix it here ONCE.
 * Reflections CAN be malicious, watch commits onto this if this repo is ever extensively forked.
 */


package Evil.group.addon.utils.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CompatReflect {
    private CompatReflect() {}

    private static volatile boolean REFLECT_READY = false;
    private static Field RENDERUTILS_CENTER_FIELD;
    private static Field EVENT_TICKDELTA_FIELD;
    private static Method ENTITY_GET_LERPED_POS;
    private static Field ENTITY_LAST_X, ENTITY_LAST_Y, ENTITY_LAST_Z;

    private static volatile boolean DEPTH_READY = false;
    private static Method RS_DISABLE_DEPTH, RS_ENABLE_DEPTH;
    private static Method GSM_DISABLE_DEPTH, GSM_ENABLE_DEPTH;

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

    private static void initDepth() {
        if (DEPTH_READY) return;
        synchronized (CompatReflect.class) {
            if (DEPTH_READY) return;
            DEPTH_READY = true;

            try {
                RS_DISABLE_DEPTH = RenderSystem.class.getMethod("disableDepthTest");
                RS_ENABLE_DEPTH = RenderSystem.class.getMethod("enableDepthTest");
                return;
            } catch (Throwable ignored) {}

            try {
                Class<?> gsm = Class.forName("com.mojang.blaze3d.systems.GlStateManager");
                GSM_DISABLE_DEPTH = gsm.getDeclaredMethod("_disableDepthTest");
                GSM_ENABLE_DEPTH = gsm.getDeclaredMethod("_enableDepthTest");
            } catch (Throwable ignored) {}
        }
    }

    public static double tickDelta(Render3DEvent event) {
    /**
     * Retrieves the render tick delta in a version-safe way.
     *
     * WHY:
     * - Some Meteor versions expose Render3DEvent.tickDelta
     * - Others do not, or change its type (float vs double)
     *
     * Without this:
     * - Animations, tracers, and interpolation jitter or break
     *
     * FALLBACK:
     * - Returns 1.0 if tickDelta cannot be accessed safely
     */
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
    /**
     * Determines the tracer start position in a version-safe way.
     *
     * WHY:
     * - Meteor exposes RenderUtils.center in some versions
     * - Other versions remove or rename it
     *
     * This is the correct tracer origin (crosshair center).
     *
     * FALLBACK:
     * - Uses the camera position if RenderUtils.center is unavailable
     */
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
    /**
     * Retrieves a smoothly interpolated entity position.
     *
     * WHY:
     * - Newer MC versions expose Entity.getLerpedPos(float)
     * - Older versions require manual interpolation using lastX/Y/Z
     *
     * This prevents:
     * - jittery tracers
     * - snapping entities
     * - broken ESP visuals
     *
     * FALLBACK ORDER:
     * 1) Entity.getLerpedPos(float)
     * 2) Manual interpolation via lastX/lastY/lastZ
     * 3) Entity.getPos()
     */
        init();

        if (ENTITY_GET_LERPED_POS != null) {
            try {
                Object v = ENTITY_GET_LERPED_POS.invoke(e, (float) tickDelta);
                if (v instanceof Vec3d p) return p;
            } catch (Throwable ignored) {}
        }

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
    /**
     * Disables OpenGL depth testing in a version-safe way.
     *
     * WHY:
     * - 1.21.4 uses RenderSystem.disableDepthTest()
     * - 1.21.5+ may only expose GlStateManager._disableDepthTest()
     *
     * Without this:
     * - ESP lines render behind terrain
     * - Tracers disappear when obstructed
     */
        initDepth();
        try {
            if (RS_DISABLE_DEPTH != null) RS_DISABLE_DEPTH.invoke(null);
            else if (GSM_DISABLE_DEPTH != null) GSM_DISABLE_DEPTH.invoke(null);
        } catch (Throwable ignored) {}
    }

    public static void enableDepthTest() {
    /**
     * Re-enables OpenGL depth testing after ESP rendering.
     *
     * WHY:
     * - Leaving depth test disabled breaks ALL subsequent rendering
     * - Causes UI, world, and other modules to render incorrectly
     *
     * ALWAYS call this in a finally{} block.
     */
        initDepth();
        try {
            if (RS_ENABLE_DEPTH != null) RS_ENABLE_DEPTH.invoke(null);
            else if (GSM_ENABLE_DEPTH != null) GSM_ENABLE_DEPTH.invoke(null);
        } catch (Throwable ignored) {}
    }
}
