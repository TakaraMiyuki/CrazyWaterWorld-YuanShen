package com.opamb.barotrauma.mixin;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.HullDamage;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.vs.VsState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
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

    /**
     * 不用 {@code @Shadow}：生产环境的 SRG 字段名需要 refmap 字段映射，
     * 而 Mixin AP 只给注入方法生成了映射（没有 player 字段）。直接按
     * gameMode 反查玩家，避免正式包启动时 shadow 字段找不到。
     */
    @Nullable
    private ServerPlayer barotrauma$player() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (candidate.gameMode == (ServerPlayerGameMode) (Object) this) {
                return candidate;
            }
        }
        return null;
    }

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
        ServerPlayer player = barotrauma$player();
        if (player == null || player.isSpectator()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.mayInteract(player, pos)) {
            return;
        }
        // 左键一下 = 一次结构伤害；空手/刀/镐不同，创造蹲下 = 建造模式一击拆。
        float amount = HullDamage.meleeDamage(player.getMainHandItem(),
                player.getMainHandItem().isEmpty());
        if (player.isCreative() && player.isShiftKeyDown()) {
            amount = 1.0E9F;
        }
        // 物理船：世界里是空气，必须先在服务端还原成船坞坐标再扣耐久。
        if (VsState.tryDamage(level, player, pos, amount)) {
            ackAndCancel(player, sequence, ci);
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(ModShipBlocks.SONAR.get())) {
            player.displayClientMessage(Component.translatable("message.barotrauma.hull_sonar")
                    .withStyle(ChatFormatting.GOLD), true);
            ackAndCancel(player, sequence, ci);
            return;
        }
        if (!ModShipBlocks.isRepairableHull(state)) {
            return;
        }
        // 方块船：先扣耐久，0 耐久才真的破块并重扫。
        if (amount <= 0.0F) {
            // 焊接工具左键不拆船（它只能右键修）
            ackAndCancel(player, sequence, ci);
            return;
        }
        ShipCoreBlockEntity core = ShipCores.nearestCore(level, pos, 64.0D);
        if (core != null && core.damageBlock(level, pos, amount, player)) {
            ackAndCancel(player, sequence, ci);
        }
    }

    /** 原版 destroyAndAck 会回这个 ack；整段取消后必须自己回，否则客户端预测破坏一直挂着。 */
    private void ackAndCancel(ServerPlayer player, int sequence, CallbackInfo ci) {
        player.connection.send(new ClientboundBlockChangedAckPacket(sequence));
        ci.cancel();
    }
}
