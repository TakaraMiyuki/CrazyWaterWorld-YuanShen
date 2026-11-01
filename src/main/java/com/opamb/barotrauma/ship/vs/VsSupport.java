package com.opamb.barotrauma.ship.vs;

import net.minecraftforge.fml.ModList;

/**
 * 有没有装 VS2。这个类<strong>不能</strong>引用任何 VS2 类型——它要在没装 VS2 的客户端上也能加载。
 */
public final class VsSupport {

    public static final String MOD_ID = "valkyrienskies";

    private VsSupport() {
    }

    public static boolean available() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }
}
