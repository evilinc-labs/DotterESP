package Evil.group.addon;

import Evil.group.addon.modules.DotterEsp;
import Evil.group.addon.modules.AutoBuilder;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class AntiDotterAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Dotter Griefing");

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        // Meteor’s module system is guaranteed ready after categories registration in this lifecycle
        Modules.get().add(new DotterEsp());
        // included to assist with version compatibility
        Modules.get().add(new AutoBuilder());   // credit to https://github.com/voidz420/AutoBuilder-meteor-client/blob/main/src/main/java/com/voidz/autobuilder/modules/AutoBuilder.java

    }

    @Override
    public String getPackage() {
        return "Evil.group.addon";
    }
}
