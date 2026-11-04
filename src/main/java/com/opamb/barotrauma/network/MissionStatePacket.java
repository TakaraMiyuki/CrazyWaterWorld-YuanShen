package com.opamb.barotrauma.network;

import com.opamb.barotrauma.data.MissionSavedData;
import com.opamb.barotrauma.mission.Mission;
import com.opamb.barotrauma.mission.MissionType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：任务面板/目标 HUD 需要的全部状态。
 *
 * <p>在终端打开、接取/放弃/交付、进度达成时推送。字段布局见 {@link #encode}。</p>
 */
public record MissionStatePacket(int credits, int activeId, int activeState, int activeType,
                                 int targetX, int targetY, int targetZ, int radius, int reward,
                                 int[] offeredIds, int[] offeredStates, boolean[] offeredTaken) {

    public static MissionStatePacket build(ServerPlayer player, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return empty();
        }
        MissionSavedData data = MissionSavedData.get(serverLevel);
        Mission active = data.activeMission(player.getUUID());
        var missions = data.missions();
        int[] ids = new int[missions.size()];
        int[] states = new int[missions.size()];
        boolean[] taken = new boolean[missions.size()];
        int i = 0;
        for (Mission mission : missions.values()) {
            ids[i] = mission.id;
            states[i] = mission.state().ordinal();
            taken[i] = mission.isTaken();
            i++;
        }
        if (active != null) {
            return new MissionStatePacket(data.credits(player.getUUID()), active.id,
                    active.state().ordinal(), active.type.ordinal(),
                    active.target.getX(), active.target.getY(), active.target.getZ(),
                    active.radius, active.reward, ids, states, taken);
        }
        return new MissionStatePacket(data.credits(player.getUUID()), -1, -1, -1,
                0, 0, 0, 0, 0, ids, states, taken);
    }

    private static MissionStatePacket empty() {
        return new MissionStatePacket(0, -1, -1, -1, 0, 0, 0, 0, 0,
                new int[0], new int[0], new boolean[0]);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(credits);
        buf.writeVarInt(activeId + 1);
        buf.writeVarInt(activeState + 1);
        buf.writeVarInt(activeType + 1);
        buf.writeBlockPos(new net.minecraft.core.BlockPos(targetX, targetY, targetZ));
        buf.writeVarInt(radius);
        buf.writeVarInt(reward);
        buf.writeVarInt(offeredIds.length);
        for (int i = 0; i < offeredIds.length; i++) {
            buf.writeVarInt(offeredIds[i]);
            buf.writeVarInt(offeredStates[i] + 1);
            buf.writeBoolean(offeredTaken[i]);
        }
    }

    public static MissionStatePacket decode(FriendlyByteBuf buf) {
        int credits = buf.readVarInt();
        int activeId = buf.readVarInt() - 1;
        int activeState = buf.readVarInt() - 1;
        int activeType = buf.readVarInt() - 1;
        net.minecraft.core.BlockPos target = buf.readBlockPos();
        int radius = buf.readVarInt();
        int reward = buf.readVarInt();
        int count = buf.readVarInt();
        int[] ids = new int[count];
        int[] states = new int[count];
        boolean[] taken = new boolean[count];
        for (int i = 0; i < count; i++) {
            ids[i] = buf.readVarInt();
            states[i] = buf.readVarInt() - 1;
            taken[i] = buf.readBoolean();
        }
        return new MissionStatePacket(credits, activeId, activeState, activeType,
                target.getX(), target.getY(), target.getZ(), radius, reward, ids, states, taken);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.opamb.barotrauma.client.ClientMissionCache.accept(this)));
        context.setPacketHandled(true);
    }

    public MissionType activeTypeEnum() {
        return activeType >= 0 ? MissionType.values()[activeType] : null;
    }
}
