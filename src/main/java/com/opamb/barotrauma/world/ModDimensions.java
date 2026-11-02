package com.opamb.barotrauma.world;

import com.opamb.barotrauma.BarotraumaMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 深海维度的坐标常量与换算。
 *
 * <p>世界骨架（y 轴）：</p>
 * <pre>
 *   15 .. 0     冰盖（packed ice），不可穿透
 *   -1 .. -1000 水柱（正好 1000 格 = 1000 米）
 *   -1001..-1008 海床（深板岩）
 * </pre>
 */
public final class ModDimensions {

    public static final ResourceKey<Level> THE_DEEP = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(BarotraumaMod.MOD_ID, "the_deep"));

    /** 世界最低 y。 */
    public static final int MIN_Y = -1008;
    /** 世界总高度（1.20.1 维度类型要求是 16 的倍数）。 */
    public static final int HEIGHT = 1024;

    public static final int CEILING_BOTTOM_Y = 0;
    public static final int WATER_TOP_Y = -1;
    public static final int WATER_BOTTOM_Y = -1000;
    public static final int SEABED_TOP_Y = -1001;

    // ---- 新手哨站（挂在第一层顶板岩壁下方，y 范围见 OutpostBuilder.MIN_Y/MAX_Y）----
    public static final int OUTPOST_X = 0;
    public static final int OUTPOST_Z = 0;
    public static final int OUTPOST_Y = -54;
    public static final int OUTPOST_SPAWN_Y = -62;

    /** 深度换算：1 格 = 1 米。 */
    public static int toMeters(int y) {
        return -y;
    }

    private ModDimensions() {
    }
}
