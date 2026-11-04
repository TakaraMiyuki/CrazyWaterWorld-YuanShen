package com.opamb.barotrauma.network;

import com.opamb.barotrauma.blockentity.DrainPumpBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：排水泵菜单操作（开关 / 设置排水量）。
 *
 * <p>服务端权威：只接受 8 格内的方块实体、档位只取预置值；改完由
 * {@code ContainerData} 自动同步回客户端。</p>
 */
public record PumpActionPacket(BlockPos pos, int action, int value) {

    public static final int TOGGLE = 0;
    public static final int SET_RATE = 1;

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(action);
        buf.writeVarInt(value);
    }

    public static PumpActionPacket decode(FriendlyByteBuf buf) {
        return new PumpActionPacket(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 64.0D) {
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof DrainPumpBlockEntity pump)) {
                return;
            }
            switch (action) {
                case TOGGLE -> pump.setEnabled(!pump.enabled());
                case SET_RATE -> {
                    for (int allowed : DrainPumpBlockEntity.RATES) {
                        if (allowed == value) {
                            pump.setRate(value);
                            break;
                        }
                    }
                }
                default -> { /* 未知操作忽略 */ }
            }
        });
        context.setPacketHandled(true);
    }
}
