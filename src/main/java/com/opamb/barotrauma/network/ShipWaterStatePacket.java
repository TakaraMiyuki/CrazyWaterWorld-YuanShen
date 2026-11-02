package com.opamb.barotrauma.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 物理船的舱内水面状态（服务端 → 客户端），用于自绘平滑水面。
 *
 * <p>每个舱室 7 个 float：装配时的包围盒 minX/minY/minZ/maxX/maxY/maxZ + 水面高度 surfaceY。
 * 客户端用"当前渲染位置 − 装配锚点"做整体平移（姿态锁死，不需要旋转）。</p>
 */
public record ShipWaterStatePacket(long shipId, boolean physical,
                                   float anchorX, float anchorY, float anchorZ, float[] boxes) {

    public static ShipWaterStatePacket empty() {
        return new ShipWaterStatePacket(-1L, false, 0, 0, 0, new float[0]);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(shipId);
        buf.writeBoolean(physical);
        buf.writeFloat(anchorX);
        buf.writeFloat(anchorY);
        buf.writeFloat(anchorZ);
        buf.writeVarInt(boxes.length / 7);
        for (float value : boxes) {
            buf.writeFloat(value);
        }
    }

    public static ShipWaterStatePacket decode(FriendlyByteBuf buf) {
        long id = buf.readVarLong();
        boolean physical = buf.readBoolean();
        float ax = buf.readFloat();
        float ay = buf.readFloat();
        float az = buf.readFloat();
        int count = buf.readVarInt();
        float[] boxes = new float[count * 7];
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = buf.readFloat();
        }
        return new ShipWaterStatePacket(id, physical, ax, ay, az, boxes);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.opamb.barotrauma.client.ShipWaterRenderer.accept(this)));
        context.setPacketHandled(true);
    }
}
