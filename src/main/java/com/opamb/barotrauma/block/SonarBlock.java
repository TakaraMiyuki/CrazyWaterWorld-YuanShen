package com.opamb.barotrauma.block;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.blockentity.SonarBlockEntity;
import com.opamb.barotrauma.menu.SonarMenu;
import com.opamb.barotrauma.registry.ModBlockEntities;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.sonar.SonarScan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 声纳台 / 舵台。右键打开声纳界面（掌舵就在这里），台面上方另有一团 3D 全息（见 SonarRenderer）。
 */
public class SonarBlock extends BaseEntityBlock {

    public SonarBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SonarBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, ModBlockEntities.SONAR.get(), SonarBlockEntity::clientTick);
        }
        // 服务端：声纳台就是船脑（SonarBlockEntity extends ShipCoreBlockEntity），
        // 船体模拟、推进、水位都跑在这个 tick 里
        return createTickerHelper(type, ModBlockEntities.SONAR.get(), ShipCoreBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // 声纳要操纵船，得先找到这艘船的核；装船时还没扫过就现在扫一次
            ShipCoreBlockEntity core = ShipCores.nearestCore(level, pos, 32.0D);
            if (core != null && core.state() == null) {
                core.rescan(level);
            }
            NetworkHooks.openScreen(serverPlayer,
                    new SimpleMenuProvider(
                            (id, inventory, p) -> new SonarMenu(id, inventory, pos, SonarScan.DEFAULT_RANGE),
                            Component.translatable("block.barotrauma.sonar")),
                    buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeVarInt(SonarScan.DEFAULT_RANGE);
                    });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }
}
