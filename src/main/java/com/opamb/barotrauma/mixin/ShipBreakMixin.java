package com.opamb.barotrauma.mixin;

import com.opamb.barotrauma.ship.vs.VsState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物理船砸方块。
 *
 * <p>VS2 2.4.11 只给"放置方块"做了坐标映射，没有 <code>ServerGamePacketListener</code>
 * / <code>useItemOn</code> 的破坏重映射。玩家看到的船壳是客户端按刚体变换渲染的，
 * 服务端世界里那一格其实是空气——所以原版 {@code destroyBlock} 永远打空。</p>
 *
 * <p>这里在服务端收到"开始破坏"时先问 {@link VsState#tryBreak}：物理船用玩家视线
 * 对船体快照做射线检测，命中后删掉船坞里的对应方块。返回 true 就把原版处理整段取消，
 * 避免它在空气上白跑一遍（还会给客户端发纠正包）。</p>
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ShipBreakMixin {

    @Shadow
    protected ServerPlayer player;

    @Inject(
            method = "handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V",
            at = @At("HEAD"),
            cancellable = true)
    private void barotrauma$breakShipBlock(BlockPos pos, ServerboundPlayerActionPacket.Action action,
                                           Direction face, int maxBuildHeight, int sequence,
                                           CallbackInfo ci) {
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            return;
        }
        if (VsState.tryBreak(player.serverLevel(), player, pos)) {
            // 原版 destroyAndAck 会回这个 ack；我们整段取消后必须自己回，
            // 否则客户端的预测破坏一直挂着不被确认（创造模式序列号会一直涨）。
            player.connection.send(new ClientboundBlockChangedAckPacket(sequence));
            ci.cancel();
        }
    }
}
