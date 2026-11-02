package com.opamb.barotrauma.network;

import com.opamb.barotrauma.sonar.ClientSonarCache;
import com.opamb.barotrauma.sonar.SonarScan;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：一帧声纳点云 + 舵位 + 仪表读数 + 自动驾驶目标 + 状态文字。
 *
 * <p>{@code speedTenths}/{@code diveTenths} 是 0.1 公里/时，整数传输避免浮点抖动。
 * {@code tx/ty/tz} 是目标点相对玩家位置的偏移（没有目标时 {@code hasTarget=false}）。</p>
 */
public record SonarDataPacket(int range, int[] points, int power, int hx, int hy, int hz,
                              int mode, int depth, int speedTenths, int diveTenths, int autopilot,
                              boolean hasTarget, int tx, int ty, int tz, String status, String core) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(range);
        buf.writeVarIntArray(points);
        buf.writeVarInt(power);
        buf.writeByte(hx);
        buf.writeByte(hy);
        buf.writeByte(hz);
        buf.writeVarInt(mode);
        buf.writeVarInt(depth);
        buf.writeVarInt(speedTenths);
        buf.writeVarInt(diveTenths);
        buf.writeBoolean(autopilot != 0);
        buf.writeBoolean(hasTarget);
        if (hasTarget) {
            buf.writeVarInt(tx);
            buf.writeVarInt(ty);
            buf.writeVarInt(tz);
        }
        buf.writeUtf(status, 512);
        buf.writeUtf(core, 1024);
    }

    public static SonarDataPacket decode(FriendlyByteBuf buf) {
        int range = buf.readVarInt();
        int[] points = buf.readVarIntArray(SonarScan.MAX_POINTS);
        int power = buf.readVarInt();
        int hx = buf.readByte();
        int hy = buf.readByte();
        int hz = buf.readByte();
        int mode = buf.readVarInt();
        int depth = buf.readVarInt();
        int speed = buf.readVarInt();
        int dive = buf.readVarInt();
        int autopilot = buf.readBoolean() ? 1 : 0;
        boolean hasTarget = buf.readBoolean();
        int tx = 0;
        int ty = 0;
        int tz = 0;
        if (hasTarget) {
            tx = buf.readVarInt();
            ty = buf.readVarInt();
            tz = buf.readVarInt();
        }
        String status = buf.readUtf(512);
        String core = buf.readUtf(1024);
        return new SonarDataPacket(range, points, power, hx, hy, hz, mode, depth, speed, dive,
                autopilot, hasTarget, tx, ty, tz, status, core);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ClientSonarCache.accept(range, points, power, hx, hy, hz,
                mode, depth, speedTenths, diveTenths, autopilot, hasTarget, tx, ty, tz, status, core));
        context.setPacketHandled(true);
    }
}
