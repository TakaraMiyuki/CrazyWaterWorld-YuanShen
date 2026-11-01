package com.opamb.barotrauma.block;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 船核心：整艘船的中枢。放一个在船舱里，右键扫描船体，得到舱室划分。
 */
public class ShipCoreBlock extends BaseEntityBlock {

    public ShipCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShipCoreBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, com.opamb.barotrauma.registry.ModBlockEntities.SHIP_CORE.get(),
                ShipCoreBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ShipCoreBlockEntity core
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            // 已有状态就复用，避免每次打开面板都重扫、把温度和水量重置
            ShipState ship = core.state() != null ? core.state() : core.rescan(level);
            String status = ship.liveReport().getString();
            net.minecraftforge.network.NetworkHooks.openScreen(serverPlayer,
                    new net.minecraft.world.SimpleMenuProvider(
                            (id, inventory, p) -> new com.opamb.barotrauma.menu.ShipConsoleMenu(id, inventory, pos, status),
                            Component.translatable("block.barotrauma.ship_core")),
                    buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeUtf(status, 1024);
                    });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState();
    }
}
