package com.opamb.barotrauma;

import com.mojang.logging.LogUtils;
import com.opamb.barotrauma.registry.ModBlocks;
import com.opamb.barotrauma.registry.ModBlockEntities;
import com.opamb.barotrauma.registry.ModChunkGenerators;
import com.opamb.barotrauma.registry.ModCreativeTabs;
import com.opamb.barotrauma.registry.ModEntities;
import com.opamb.barotrauma.registry.ModItems;
import com.opamb.barotrauma.registry.ModMenus;
import com.opamb.barotrauma.registry.ModShipBlocks;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * 模组入口。
 *
 * <p>Forge 在 1.20.1 上通过 {@link Mod} 注解发现这个类，构造器里拿到 mod 事件总线，
 * 把各类 DeferredRegister 挂上去。所有注册都必须在构造器阶段完成。</p>
 */
@Mod(BarotraumaMod.MOD_ID)
public final class BarotraumaMod {

    /** 模组 id，必须全小写，且与 mods.toml / 资源路径 / 数据包命名空间一致。 */
    public static final String MOD_ID = "barotrauma";

    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Forge 47.4.0 把 {@link FMLJavaModLoadingContext#get()} 标成了 forRemoval，但 1.20.1 官方文档
     * （gettingstarted/modfiles）给的仍是这一写法，且整条 47.x 线都在用；这里保留调用并压掉警告。
     */
    @SuppressWarnings("removal")
    public BarotraumaMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.register(modBus);
        ModItems.register(modBus);
        ModCreativeTabs.register(modBus);
        ModChunkGenerators.register(modBus);
        ModShipBlocks.register(modBus);
        ModBlockEntities.register(modBus);
        ModMenus.register(modBus);
        ModEntities.register(modBus);
        com.opamb.barotrauma.network.ModNetwork.register();

        LOGGER.info("[Barotrauma] loaded, 该上潜了。");
    }
}
