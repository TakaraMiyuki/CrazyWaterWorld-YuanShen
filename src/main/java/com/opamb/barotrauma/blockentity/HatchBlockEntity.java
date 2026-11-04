package com.opamb.barotrauma.blockentity;

import com.opamb.barotrauma.block.HatchBlock;
import com.opamb.barotrauma.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 舱口的 GeckoLib 方块实体（只挂在中间段）。
 *
 * <p>动画完全由 blockstate 的 {@code open} 驱动：打开播 {@code open} 并停在最后一帧，
 * 关闭切到 {@code closed}；控制器自带 4 tick 过渡，所以关门不会瞬移。</p>
 */
public class HatchBlockEntity extends BlockEntity implements GeoBlockEntity {

    private static final RawAnimation OPEN = RawAnimation.begin().thenPlayAndHold("open");
    private static final RawAnimation CLOSED = RawAnimation.begin().thenPlayAndHold("closed");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public HatchBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HATCH.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 4, this::predicate)
                .transitionLength(4));
    }

    private PlayState predicate(AnimationState<HatchBlockEntity> state) {
        boolean open = getBlockState().getValue(HatchBlock.OPEN);
        return state.setAndContinue(open ? OPEN : CLOSED);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
