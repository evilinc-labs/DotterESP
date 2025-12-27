package Evil.group.addon;

import Evil.group.addon.hud.BuildCounterHud;
import Evil.group.addon.modules.DotterEsp;
import Evil.group.addon.modules.AutoBuilder;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class AntiDotterAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Dotter Griefing");
    public static final HudGroup HUD_GROUP = new HudGroup("Dotter Griefing");

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        // Modules
        Modules.get().add(new DotterEsp());
        Modules.get().add(new AutoBuilder());

        // HUD elements
        Hud.get().register(BuildCounterHud.INFO);
    }

    @Override
    public String getPackage() {
        return "Evil.group.addon";
    }
}
