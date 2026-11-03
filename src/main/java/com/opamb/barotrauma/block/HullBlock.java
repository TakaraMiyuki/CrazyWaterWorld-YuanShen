package com.opamb.barotrauma.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 船壳方块：多一个 {@code breached} 状态。
 *
 * <p>《潜渊症》里墙体耐久到 0 时不是“方块消失”，而是这一段的碰撞体失效、
 * 变成一个可以过水/过人的缺口；墙段本身还在，用焊接工具焊回去就恢复。
 * Minecraft 里没有“保留方块但去掉碰撞”的原生状态，所以这里用 blockstate：</p>
 *
 * <ul>
 *   <li>{@code breached=false}：正常实心方块；</li>
 *   <li>{@code breached=true}：碰撞/窒息/遮挡全部关闭，渲染为不可见，但方块还在世界里，
 *       耐久记录保留 0%，焊接工具可以继续瞄准它并修回。</li>
 * </ul>
 *
 * <p>{@link #getShape} 在 breached 时仍返回整格：这样 {@code player.pick} 还能命中
 * 这个“看不见的洞”，右键焊接才有目标；碰撞用的是 {@link #getCollisionShape}，
 * 那条路径返回空。</p>
 */
public class HullBlock extends Block {

    public static final BooleanProperty BREACHED = BooleanProperty.create("breached");

    public HullBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BREACHED, false));
    }

    public static boolean isBreached(BlockState state) {
        return state.hasProperty(BREACHED) && state.getValue(BREACHED);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BREACHED);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return isBreached(state) ? Shapes.empty() : super.getCollisionShape(state, level, pos, context);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        // 视野/准星仍按整格算，否则玩家看不见也点不到这个洞，无法焊接修复。
        return isBreached(state) ? Shapes.block() : super.getShape(state, level, pos, context);
    }

    // 注意：1.20.1 的 isViewBlocking / isSuffocating 是 BlockState 上的谓词，
    // 不能在这里 override；注册方块时用 Properties.isViewBlocking/.isSuffocating
    // 传入“breached 时 false”的 StatePredicate（见 ModBlocks / ModShipBlocks）。

    // breached 仍然按正常方块渲染：0% 只是“没碰撞”，不是隐身。
    // 如果这里返回 INVISIBLE，整面墙会消失，玩家能直接透视船体（用户实测 bug）。
    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return !isBreached(state);
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return isBreached(state) ? 0 : super.getLightBlock(state, level, pos);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return isBreached(state) || super.propagatesSkylightDown(state, level, pos);
    }
}
