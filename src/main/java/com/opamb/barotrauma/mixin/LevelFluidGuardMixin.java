package com.opamb.barotrauma.mixin;

import com.opamb.barotrauma.ship.water.ShipVolumeGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 流体拦截：物理船体积内拒绝任何"写入水方块"的操作。
 *
 * <p>这是 {@link ShipVolumeGuard} 的执行点。选 {@code Level#setBlock} 是因为
 * MC 的水流扩散最终都落到这个入口，拦这里最稳，不用依赖 {@code FlowingFluid}
 * 内部实现细节。</p>
 */
@Mixin(Level.class)
public abstract class LevelFluidGuardMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void barotrauma$blockWaterInsideShip(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        if (state.getFluidState().isEmpty()) {
            return;
        }
        if (ShipVolumeGuard.isProtected(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
