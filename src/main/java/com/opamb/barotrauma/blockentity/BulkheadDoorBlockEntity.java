package com.opamb.barotrauma.blockentity;

import com.opamb.barotrauma.block.BulkheadDoorBlock;
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
 * 舱壁门的 GeckoLib 方块实体（只挂在最下面一段）。
 *
 * <p>和舱口一样，状态只读 blockstate 的 {@code open}；打开/关闭各一个动画，
 * 由控制器做过渡。模型本身是 3 格高，渲染器挂在最下面一段，向上覆盖两格。</p>
 */
public class BulkheadDoorBlockEntity extends BlockEntity implements GeoBlockEntity {

    private static final RawAnimation OPEN = RawAnimation.begin().thenPlayAndHold("open");
    private static final RawAnimation CLOSED = RawAnimation.begin().thenPlayAndHold("closed");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public BulkheadDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BULKHEAD_DOOR.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 4, this::predicate)
                .transitionLength(4));
    }

    private PlayState predicate(AnimationState<BulkheadDoorBlockEntity> state) {
        boolean open = getBlockState().getValue(BulkheadDoorBlock.OPEN);
        return state.setAndContinue(open ? OPEN : CLOSED);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
