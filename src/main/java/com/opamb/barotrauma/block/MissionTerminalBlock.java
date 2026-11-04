package com.opamb.barotrauma.block;

import com.opamb.barotrauma.menu.MissionMenu;
import com.opamb.barotrauma.network.MissionStatePacket;
import com.opamb.barotrauma.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 任务终端：哨站里的接单/交付/商店界面入口。
 *
 * <p>右键打开 {@link MissionMenu}；任务数据是世界级的（{@code MissionSavedData}），
 * 菜单本身不带状态，打开后由服务端补发一包 {@link MissionStatePacket}。</p>
 */
public class MissionTerminalBlock extends Block {

    private static final Component TITLE = Component.translatable("block.barotrauma.mission_terminal");

    public MissionTerminalBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new MissionMenu.Provider(pos));
            ModNetwork.sendToPlayer(serverPlayer, MissionStatePacket.build(serverPlayer, level));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
