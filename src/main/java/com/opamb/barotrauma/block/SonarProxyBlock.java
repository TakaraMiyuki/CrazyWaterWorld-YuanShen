package com.opamb.barotrauma.block;

import com.opamb.barotrauma.menu.SonarMenu;
import com.opamb.barotrauma.sonar.SonarScan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

/**
 * 物理船的声纳"交互锚点"。
 *
 * <p>VS2 2.4.11 把船体方块搬到船坞区块，船位世界里没有声纳的方块实体，
 * 右键射到的是船位而不是船坞，所以正常交互打不开声纳（VS2 这个版本没有
 * 方块使用/破坏的坐标重映射）。</p>
 *
 * <p>解决方式：VS2 船头上放一个真实方块（本类），它不可见、不碰撞、不挡水，
 * 跟着船整格移动；右键它就直接打开声纳界面。这样玩家看到的"声纳台"（VS2 渲染的
 * 船上模型）和交互点重合，体验和普通方块一致，不需要依赖 VS2 的交互链路。</p>
 */
public class SonarProxyBlock extends Block {

    public SonarProxyBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
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
