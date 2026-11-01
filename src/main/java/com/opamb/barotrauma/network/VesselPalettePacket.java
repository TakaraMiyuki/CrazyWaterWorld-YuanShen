package com.opamb.barotrauma.network;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.client.ClientVesselCache;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：下发船体调色板。
 */
public record VesselPalettePacket(int entityId, List<BlockState> palette, List<BlockPos> offsets,
                                  int sizeX, int sizeY, int sizeZ) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            buf.writeNbt(NbtUtils.writeBlockState(palette.get(i)));
            buf.writeBlockPos(offsets.get(i));
        }
        buf.writeVarInt(sizeX);
        buf.writeVarInt(sizeY);
        buf.writeVarInt(sizeZ);
    }

    public static VesselPalettePacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int count = buf.readVarInt();
        List<BlockState> states = new ArrayList<>(count);
        List<BlockPos> offsets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CompoundTag tag = buf.readNbt();
            states.add(tag == null ? Blocks.AIR.defaultBlockState() : NbtUtils.readBlockState(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), tag));
            offsets.add(buf.readBlockPos());
        }
        return new VesselPalettePacket(entityId, states, offsets,
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientVesselCache.put(entityId, palette, offsets, sizeX, sizeY, sizeZ)));
        context.setPacketHandled(true);
    }
}
