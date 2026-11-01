package com.opamb.barotrauma.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端缓存的船体调色板（按实体 id）。
 */
public final class ClientVesselCache {

    public record Palette(List<BlockState> states, List<BlockPos> offsets, int sizeX, int sizeY, int sizeZ) {
    }

    private static final Map<Integer, Palette> CACHE = new HashMap<>();

    private ClientVesselCache() {
    }

    public static void put(int entityId, List<BlockState> states, List<BlockPos> offsets,
                           int sizeX, int sizeY, int sizeZ) {
        CACHE.put(entityId, new Palette(List.copyOf(states), List.copyOf(offsets), sizeX, sizeY, sizeZ));
    }

    public static Palette get(int entityId) {
        return CACHE.get(entityId);
    }

    public static void remove(int entityId) {
        CACHE.remove(entityId);
    }
}
