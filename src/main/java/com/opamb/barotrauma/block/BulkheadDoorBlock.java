package com.opamb.barotrauma.block;

import com.opamb.barotrauma.blockentity.BulkheadDoorBlockEntity;
import com.opamb.barotrauma.registry.ModSounds;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.vs.VsState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 竖向舱壁门：1 格宽 × 2 格高，薄板居中（类似栅栏门的占地），整扇向右滑入墙内。
 *
 * <p>门板只有 1 像素厚，中心在方块 z=8；关闭时碰撞体只覆盖这块薄板，打开后清空。
 * 观察窗是门板上的真实缺口，所以从两面都能看穿。模型由 GeckoLib 渲染，
 * 方块本身返回 {@link RenderShape#ENTITYBLOCK_ANIMATED}，不再画方块模型。</p>
 */
public class BulkheadDoorBlock extends Block implements EntityBlock {

    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public enum Part implements StringRepresentable {
        LOWER("lower"), UPPER("upper");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    /**
     * 门板和门框的拾取形状：门板 12px 宽、1px 厚，居中在方块中间（和模型一致），
     * 两侧 2px 是固定门框。只用于射线/右键，不用于碰撞。
     *
     * <p>必须按 {@code FACING} 旋转：模型由 GeckoLib 按 facing 转，形状用固定朝向写
     * 会让东/西向的门只剩中心一小块可点，门框点不到（"点门中间才能开关"）。</p>
     */
    private static final VoxelShape[][] PANEL_SHAPES = new VoxelShape[2][4];
    private static final VoxelShape[][] FRAME_SHAPES = new VoxelShape[2][4];

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            int i = facing.get2DDataValue();
            // 左右立柱（沿门宽的两端）+ 本段的下槛/上槛，厚度与模型一致：门框 z -1..1。
            VoxelShape posts = Shapes.or(
                    DoorShapes.slab(facing, -8.0D, -6.0D, -1.0D, 1.0D, 0.0D, 16.0D),
                    DoorShapes.slab(facing, 6.0D, 8.0D, -1.0D, 1.0D, 0.0D, 16.0D));
            FRAME_SHAPES[Part.LOWER.ordinal()][i] = Shapes.or(posts,
                    DoorShapes.slab(facing, -6.0D, 6.0D, -1.0D, 1.0D, 0.0D, 2.0D));
            FRAME_SHAPES[Part.UPPER.ordinal()][i] = Shapes.or(posts,
                    DoorShapes.slab(facing, -6.0D, 6.0D, -1.0D, 1.0D, 14.0D, 16.0D));
            PANEL_SHAPES[Part.LOWER.ordinal()][i] =
                    DoorShapes.slab(facing, -6.0D, 6.0D, -0.5D, 0.5D, 0.0D, 16.0D);
            PANEL_SHAPES[Part.UPPER.ordinal()][i] = PANEL_SHAPES[Part.LOWER.ordinal()][i];
        }
    }

    private static VoxelShape panelShape(BlockState state) {
        return PANEL_SHAPES[state.getValue(PART).ordinal()][state.getValue(FACING).get2DDataValue()];
    }

    /** 固定门框（左右立柱 + 本段的下槛或上槛），开门后只留它可点。 */
    private static VoxelShape frameShape(BlockState state) {
        return FRAME_SHAPES[state.getValue(PART).ordinal()][state.getValue(FACING).get2DDataValue()];
    }

    public BulkheadDoorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(OPEN, false)
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OPEN, FACING, PART);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState()
                .setValue(PART, Part.LOWER)
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            LivingEntity placer, ItemStack stack) {
        if (state.getValue(PART) != Part.LOWER) {
            return;
        }
        level.setBlock(pos.above(), state.setValue(PART, Part.UPPER), Block.UPDATE_ALL);
    }

    private static BlockPos lowerPos(Level level, BlockPos pos, BlockState state) {
        BlockPos lower = pos.below(state.getValue(PART).ordinal());
        BlockState lowerState = level.getBlockState(lower);
        if (lowerState.getBlock() instanceof BulkheadDoorBlock
                && lowerState.getValue(PART) == Part.LOWER) {
            return lower;
        }
        return null;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockPos lower = lowerPos(level, pos, state);
            if (lower == null) {
                return InteractionResult.PASS;
            }
            boolean open = !state.getValue(OPEN);
            for (int offset = 0; offset < 2; offset++) {
                BlockPos partPos = lower.above(offset);
                BlockState partState = level.getBlockState(partPos);
                if (partState.getBlock() instanceof BulkheadDoorBlock) {
                    level.setBlock(partPos, partState.setValue(OPEN, open), Block.UPDATE_ALL);
                }
            }
            // 原版门音：Door1..4 四段随机（见 sounds.json）。
            level.playSound(null, pos, ModSounds.DOOR_TOGGLE.get(),
                    SoundSource.BLOCKS, 1.0F, 0.95F + level.random.nextFloat() * 0.1F);
            // 方块船：重扫世界几何；物理船：方块类型没变，必须显式重扫船坞几何。
            ShipCores.rescanNear(level, pos, 16);
            if (level instanceof ServerLevel server) {
                VsState.refreshGeometry(server);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos lower = lowerPos(level, pos, state);
            if (lower != null) {
                BlockState lowerState = level.getBlockState(lower);
                if (state.getValue(PART) != Part.LOWER && !player.isCreative()) {
                    Block.dropResources(lowerState, level, lower);
                }
                for (int offset = 0; offset < 2; offset++) {
                    BlockPos partPos = lower.above(offset);
                    if (!partPos.equals(pos)) {
                        level.removeBlock(partPos, false);
                    }
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        // 关着门按整格挡：门板居中（1px 厚）时，1x1x1 的薄板塞不下 0.6 宽的人，
        // MC 的碰撞解算会把贴边的人往侧面挤（方块船用 MC 碰撞，所以只有方块船弹人）。
        // 整格碰撞是唯一"居中 + 不挤人"的形状；代价是离门板还有半格就停下。
        return state.getValue(OPEN) ? Shapes.empty() : Shapes.block();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        // 拾取/右键只看门板和门框：开门后门洞中间不挡射线（能打怪、不会左键打碎门）。
        VoxelShape frame = frameShape(state);
        return state.getValue(OPEN) ? frame : Shapes.or(panelShape(state), frame);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return state.getValue(PART) == Part.LOWER
                ? RenderShape.ENTITYBLOCK_ANIMATED : RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.LOWER
                ? new BulkheadDoorBlockEntity(pos, state) : null;
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(OPEN) ? 0 : super.getLightBlock(state, level, pos);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(OPEN) || super.propagatesSkylightDown(state, level, pos);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
}
