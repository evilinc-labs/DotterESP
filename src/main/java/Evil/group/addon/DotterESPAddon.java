package Evil.group.addon;

import Evil.group.addon.modules.DotterEsp;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class DotterESPAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Dotter Griefing");

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        // Meteor’s module system is guaranteed ready after categories registration in this lifecycle
        Modules.get().add(new DotterEsp());
    }

    @Override
    public String getPackage() {
        return "Evil.group.addon";
    }
}
