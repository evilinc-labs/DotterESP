package Evil.group.addon.modules;

import Evil.group.addon.AntiDotterAddon;
import Evil.group.addon.event.SignEditEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.network.packet.s2c.play.SignEditorOpenS2CPacket;
import net.minecraft.util.math.BlockPos;

public class BetterAutoSign extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("Delay")
        .description("Delay (ms) before sending packet")
        .defaultValue(20)
        .range(0, 500)
        .sliderRange(0, 500)
        .build()
    );
    private final Setting<Double> timer = sgGeneral.add(new DoubleSetting.Builder()
        .name("Timer")
        .description("Timer speed when signing")
        .defaultValue(0.5)
        .range(0, 1)
        .sliderRange(0, 1)
        .build()
    );
    private final Setting<String> signTextLine1 = sgGeneral.add(new StringSetting.Builder()
        .name("Line 1")
        .description("The text to put on the sign line 1.")
        .defaultValue("")
        .build()
    );
    private final Setting<String> signTextLine2 = sgGeneral.add(new StringSetting.Builder()
        .name("Line 2")
        .description("The text to put on the sign line 2.")
        .defaultValue("")
        .build()
    );
    private final Setting<String> signTextLine3 = sgGeneral.add(new StringSetting.Builder()
        .name("Line 3")
        .description("The text to put on the sign line 3.")
        .defaultValue("")
        .build()
    );
    private final Setting<String> signTextLine4 = sgGeneral.add(new StringSetting.Builder()
        .name("Line 4")
        .description("The text to put on the sign line 4.")
        .defaultValue("")
        .build()
    );

    private BlockPos signPos;

    public BetterAutoSign() {
        super(AntiDotterAddon.CATEGORY, "better-auto-sign", "Auto Sign but it's better on 2b2t");
    }

    @EventHandler
    public void onSignEdit(SignEditEvent ignored) {
        if (signPos != null) {
            mc.setScreen(null);
            new Thread(() -> {
                try { Thread.sleep(delay.get()); } catch (InterruptedException ignored1) {}
                mc.player.networkHandler.sendPacket(new UpdateSignC2SPacket(signPos, true,
                    signTextLine1.get(),
                    signTextLine2.get(),
                    signTextLine3.get(),
                    signTextLine4.get()));
                Modules.get().get(Timer.class).setOverride(Timer.OFF);
            }).start();
        }
    }

    @EventHandler
    public void onPacketReceived(final PacketEvent.Receive event) {
        if (event.packet instanceof SignEditorOpenS2CPacket packet) {
            signPos = packet.getPos();
        }
    }

    @EventHandler
    public void onPlaceBlock(BlockUpdateEvent event) {
        if (mc.world.getBlockEntity(event.pos) instanceof SignBlockEntity) {
            Modules.get().get(Timer.class).setOverride(timer.get());
        }
    }
}