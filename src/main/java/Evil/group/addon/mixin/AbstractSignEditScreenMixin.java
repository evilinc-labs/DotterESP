package Evil.group.addon.mixin;

import Evil.group.addon.event.SignEditEvent;
import Evil.group.addon.modules.BetterAutoSign;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void init(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(new SignEditEvent());
    }

    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void removed(CallbackInfo ci) {
        if (Modules.get().isActive(BetterAutoSign.class)) {
            ci.cancel();
        }
    }
}