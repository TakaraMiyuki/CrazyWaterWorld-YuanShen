package com.opamb.barotrauma.registry;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.block.HullBlock;
import com.opamb.barotrauma.block.MissionTerminalBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块注册表。
 *
 * <p>注意：这里只注册 Block 本体，对应的 BlockItem 在 {@link ModItems} 里注册，
 * 这样方块物品能直接进创造模式物品栏。</p>
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BarotraumaMod.MOD_ID);

    /** 潜艇装甲板：高压环境下也不变形的船体钢板。 */
    public static final RegistryObject<Block> SUBMARINE_PLATING = BLOCKS.register("submarine_plating",
            () -> new HullBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(25.0F, 1200.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .requiresCorrectToolForDrops()
                    .isViewBlocking((state, level, pos) -> !HullBlock.isBreached(state))
                    .isSuffocating((state, level, pos) -> !HullBlock.isBreached(state))));

    /** 任务终端：接任务/交付/商店的界面入口（哨站和 B 中继站各一台）。 */
    public static final RegistryObject<Block> MISSION_TERMINAL = BLOCKS.register("mission_terminal",
            () -> new MissionTerminalBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(15.0F, 1200.0F)
                    .sound(SoundType.METAL)));

    /** 黑匣子方块：沉船船舱里那一格，打掉掉落 black_box（打捞任务的交付物）。 */
    public static final RegistryObject<Block> BLACK_BOX_BLOCK = BLOCKS.register("black_box_block",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)));

    private ModBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
