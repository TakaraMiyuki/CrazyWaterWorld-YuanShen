package com.opamb.barotrauma.network;

import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 船壳耐久（服务端 → 客户端）。
 *
 * <p>{@code entries} 每 5 个 float 一格：方块最小角 x/y/z（装配原坐标；方块船就是世界坐标）
 * + 剩余生命比例 0..1 + 泄漏强度 0..1。只发受损的格子；修满后从列表消失，
 * 0% 的 breached 方块会保留（条显示深红 0%，提示可以焊回去）。泄漏强度只用于
 * 客户端按伤害等级喷水花，真正的水量仍由服务端模拟。</p>
 *
 * <p>坐标空间和 {@link ShipWaterStatePacket} 完全一致：物理船用
 * {@code anchor=装配时声纳位置}，客户端用 VS2 的 render 变换映射；
 * 方块船 {@code physical=false}、{@code anchor=0}，坐标就是世界坐标。</p>
 */
public record ShipHullDamagePacket(long shipId, boolean physical,
                                   float anchorX, float anchorY, float anchorZ,
                                   float[] entries) {

    /** 单包上限：正常一条船不会超过这个数；真超了只发伤得最重的。 */
    private static final int MAX_ENTRIES = 2048;

    public static ShipHullDamagePacket empty() {
        return new ShipHullDamagePacket(-1L, false, 0.0F, 0.0F, 0.0F, new float[0]);
    }

    public static ShipHullDamagePacket build(ShipState state, long shipId, boolean physical,
                                             float anchorX, float anchorY, float anchorZ) {
        List<Map.Entry<BlockPos, Float>> damaged = state.hullDamage().sortedEntries();
        // NaN 一旦发到客户端，血条三个区间比较全落空，画出来就是一条黑框黑槽。
        damaged.removeIf(entry -> entry.getValue() >= 0.9999F || !Float.isFinite(entry.getValue()));
        if (damaged.size() > MAX_ENTRIES) {
            // 破口很多时优先同步最严重的格子，条数不会把包撑爆。
            damaged.sort((a, b) -> Float.compare(a.getValue(), b.getValue()));
            damaged = damaged.subList(0, MAX_ENTRIES);
        }
        float[] data = new float[damaged.size() * 5];
        int i = 0;
        for (Map.Entry<BlockPos, Float> entry : damaged) {
            BlockPos pos = entry.getKey();
            data[i++] = pos.getX();
            data[i++] = pos.getY();
            data[i++] = pos.getZ();
            data[i++] = entry.getValue();
            data[i++] = state.leakIntensity(pos);
        }
        return new ShipHullDamagePacket(shipId, physical, anchorX, anchorY, anchorZ, data);
    }

    public int count() {
        return entries.length / 5;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(shipId);
        buf.writeBoolean(physical);
        buf.writeFloat(anchorX);
        buf.writeFloat(anchorY);
        buf.writeFloat(anchorZ);
        buf.writeVarInt(count());
        for (int i = 0; i + 4 < entries.length; i += 5) {
            buf.writeVarInt((int) entries[i]);
            buf.writeVarInt((int) entries[i + 1]);
            buf.writeVarInt((int) entries[i + 2]);
            buf.writeFloat(entries[i + 3]);
            buf.writeFloat(entries[i + 4]);
        }
    }

    public static ShipHullDamagePacket decode(FriendlyByteBuf buf) {
        long id = buf.readVarLong();
        boolean physical = buf.readBoolean();
        float ax = buf.readFloat();
        float ay = buf.readFloat();
        float az = buf.readFloat();
        int count = Math.max(0, Math.min(buf.readVarInt(), MAX_ENTRIES));
        float[] data = new float[count * 5];
        for (int i = 0; i < count; i++) {
            data[i * 5] = buf.readVarInt();
            data[i * 5 + 1] = buf.readVarInt();
            data[i * 5 + 2] = buf.readVarInt();
            data[i * 5 + 3] = buf.readFloat();
            data[i * 5 + 4] = buf.readFloat();
        }
        return new ShipHullDamagePacket(id, physical, ax, ay, az, data);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.opamb.barotrauma.client.HullDamageRenderer.accept(this)));
        context.setPacketHandled(true);
    }
}
