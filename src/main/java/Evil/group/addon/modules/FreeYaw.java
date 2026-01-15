package Evil.group.addon.modules;

import Evil.group.addon.AntiDotterAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.option.Perspective;

public class FreeYaw extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> togglePerspective = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-perspective")
        .description("Changes your perspective on toggle.")
        .defaultValue(true)
        .build()
    );

    public float cameraYaw;
    public float cameraPitch;

    private Perspective prePers;

    public FreeYaw() {
        super(AntiDotterAddon.CATEGORY, "FreeYaw", "FreeLook that only locks pitch");
    }

    @Override
    public void onActivate() {
        cameraYaw = mc.player.getYaw();
        cameraPitch = mc.player.getPitch();
        prePers = mc.options.getPerspective();

        if (prePers != Perspective.THIRD_PERSON_BACK && togglePerspective.get()) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
    }

    @Override
    public void onDeactivate() {
        if (mc.options.getPerspective() != prePers && togglePerspective.get()) mc.options.setPerspective(prePers);
        mc.player.setPitch(cameraPitch);
    }
}