package Evil.group.addon.mixin;

import Evil.group.addon.modules.FreeYaw;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private double updateChangeLookDirection(double cursorDeltaY) {
        FreeYaw freeYaw = Modules.get().get(FreeYaw.class);
        if (freeYaw.isActive()) {
            freeYaw.cameraPitch += (float) cursorDeltaY / 8;
            if (Math.abs(freeYaw.cameraPitch) > 90.0F) {
                freeYaw.cameraPitch = freeYaw.cameraPitch > 0.0F ? 90.0F : -90.0F;
            }
            cursorDeltaY = 0;
        }
        return cursorDeltaY;
    }
}