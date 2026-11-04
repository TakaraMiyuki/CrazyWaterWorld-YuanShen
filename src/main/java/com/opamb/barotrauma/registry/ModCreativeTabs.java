package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 创造模式物品栏分页。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BarotraumaMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.barotrauma"))
                    .icon(() -> new ItemStack(ModItems.OXYGEN_TANK.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.OXYGEN_TANK.get());
                        output.accept(ModItems.DIVING_KNIFE.get());
                        output.accept(ModItems.WELDING_TOOL.get());
                        output.accept(ModItems.SUBMARINE_PLATING.get());
                        output.accept(ModItems.HULL_GLASS.get());
                        output.accept(ModItems.TEST_HULL.get());
                        output.accept(ModItems.SONAR.get());
                        output.accept(ModItems.STATION_PLATING.get());
                        output.accept(ModItems.DRAIN_PUMP.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
