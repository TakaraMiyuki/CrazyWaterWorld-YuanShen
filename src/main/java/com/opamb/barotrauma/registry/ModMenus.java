package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.menu.ShipConsoleMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 菜单注册。
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, BarotraumaMod.MOD_ID);

    public static final RegistryObject<MenuType<ShipConsoleMenu>> SHIP_CONSOLE =
            MENUS.register("ship_console", () -> IForgeMenuType.create(ShipConsoleMenu::new));

    public static final RegistryObject<MenuType<com.opamb.barotrauma.menu.SonarMenu>> SONAR =
            MENUS.register("sonar", () -> IForgeMenuType.create(com.opamb.barotrauma.menu.SonarMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
