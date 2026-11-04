package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 菜单注册。
 *
 * <p>旧的 {@code ship_console} 菜单已经删掉了：它是早期"独立的船控制台界面"的遗留物，
 * 后来掌舵和状态全都并进了声纳台，没有任何方块再去打开它，属于纯死代码。</p>
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, BarotraumaMod.MOD_ID);

    public static final RegistryObject<MenuType<com.opamb.barotrauma.menu.SonarMenu>> SONAR =
            MENUS.register("sonar", () -> IForgeMenuType.create(com.opamb.barotrauma.menu.SonarMenu::new));

    public static final RegistryObject<MenuType<com.opamb.barotrauma.menu.MissionMenu>> MISSION_TERMINAL =
            MENUS.register("mission_terminal", () -> IForgeMenuType.create(
                    (id, inv, buf) -> new com.opamb.barotrauma.menu.MissionMenu(id, buf.readBlockPos())));

    private ModMenus() {
    }

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
