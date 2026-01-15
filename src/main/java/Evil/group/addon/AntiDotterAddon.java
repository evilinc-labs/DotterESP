package Evil.group.addon;

import Evil.group.addon.hud.BuildCounterHud;
import Evil.group.addon.modules.DotterEsp;
import Evil.group.addon.modules.AutoBuilder;
// ty https://github.com/5cmc/AutoMap
import Evil.group.addon.modules.FreeYaw;
import Evil.group.addon.modules.BetterAutoSign;
import Evil.group.addon.modules.AutoMapModule;
import Evil.group.addon.modules.AutoCartographyModule;
import meteordevelopment.meteorclient.addons.GithubRepo;
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
        Modules.get().add(new FreeYaw());
        Modules.get().add(new BetterAutoSign());
        Modules.get().add(new AutoMapModule());
        Modules.get().add(new AutoCartographyModule());

        // HUD elements
        Hud.get().register(BuildCounterHud.INFO);
    }

    @Override
    public String getPackage() {
        return "Evil.group.addon";
    }
    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("evilinc-labs", "DotterESP");
    }
}