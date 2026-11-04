package com.opamb.barotrauma.network;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端的船控指令。
 *
 * <p>{@code a/b/c} 用 VarInt 传输：既当 −1/0/1 的方向与开关，也当自动驾驶目标点的相对坐标。</p>
 */
public record ShipActionPacket(BlockPos corePos, int action, int a, int b, int c) {

    public static final int PUMP_OUT = 1;
    public static final int PUMP_IN = 2;
    public static final int AIRLOCK_DRAIN = 3;
    public static final int AIRLOCK_FLOOD = 4;
    public static final int THROTTLE_FORWARD = 5;
    public static final int STOP = 6;
    public static final int REACTOR_UP = 7;
    public static final int REACTOR_DOWN = 8;
    /** 设定推进方向：a/b/c = x/y/z，每轴 −1/0/1。 */
    public static final int SET_HEADING = 9;
    /** 设定推进功率：a = 百分比。 */
    public static final int SET_POWER = 10;
    /** 自动驾驶开关：a = 1 开启 / 0 关闭。 */
    public static final int SET_AUTOPILOT = 11;
    /** 在声纳上标记目标点：a/b/c = 相对位置坐标偏移。 */
    public static final int SET_AUTOPILOT_TARGET = 12;
    /** 清除目标点。 */
    public static final int CLEAR_AUTOPILOT_TARGET = 13;
    /** 把附近的方块船收进 VS2 物理刚体（声纳屏"下潜"按钮；等价于 /barotrauma ship vs launch）。 */
    public static final int LAUNCH = 14;
    /** 把物理船按装配快照铺回世界（声纳屏"靠泊"按钮；等价于 /barotrauma ship vs dock）。 */
    public static final int DOCK = 15;

    public ShipActionPacket(BlockPos corePos, int action) {
        this(corePos, action, 0, 0, 0);
    }

    public ShipActionPacket(BlockPos corePos, int action, int a) {
        this(corePos, action, a, 0, 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(corePos);
        buf.writeVarInt(action);
        buf.writeVarInt(a);
        buf.writeVarInt(b);
        buf.writeVarInt(c);
    }

    public static ShipActionPacket decode(FriendlyByteBuf buf) {
        return new ShipActionPacket(buf.readBlockPos(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (action == LAUNCH) {
                handleLaunch(player, level);
                context.setPacketHandled(true);
                return;
            }
            if (action == DOCK) {
                handleDock(player, level);
                context.setPacketHandled(true);
                return;
            }
            // 船在开动时核心坐标一直在变，提示坐标失效就退回"离玩家最近的核心"
            ShipCoreBlockEntity core = ShipCores.resolve(level, corePos, player.blockPosition());
            ShipState ship;
            if (core != null) {
                ship = core.state() != null ? core.state() : core.rescan(level);
            } else if (com.opamb.barotrauma.ship.vs.VsSupport.available()
                    && com.opamb.barotrauma.ship.vs.VsPilot.hasShip()) {
                // 装了 VS2：方块已经收进刚体，世界里找不到核心，操舵走物理船那份状态
                ship = com.opamb.barotrauma.ship.vs.VsPilot.state();
            } else {
                return;
            }
            if (ship == null) {
                return;
            }
            switch (action) {
                case PUMP_OUT -> ship.simulation().setPumpOut(true);
                case PUMP_IN -> ship.simulation().setPumpOut(false);
                case AIRLOCK_DRAIN -> ship.simulation().requestDrain();
                case AIRLOCK_FLOOD -> ship.simulation().requestFlood();
                case THROTTLE_FORWARD -> ship.setThrottle(0, 0, 1);
                case STOP -> ship.setThrottle(0, 0, 0);
                case SET_HEADING -> ship.setThrottle(clampAxis(a), clampAxis(b), clampAxis(c));
                case SET_POWER -> ship.setPower(a);
                case SET_AUTOPILOT -> ship.setAutopilotHold(a != 0);
                case SET_AUTOPILOT_TARGET -> {
                    // 偏移以玩家位置为基准（玩家就站在声纳台前，和扫描中心基本重合）
                    ship.setAutopilotTarget(player.blockPosition().offset(a, b, c));
                }
                case CLEAR_AUTOPILOT_TARGET -> ship.clearAutopilotTarget();
                case REACTOR_UP -> {
                    double next = Math.min(1.0D, ship.simulation().fission() + 0.1D);
                    ship.simulation().setFission(next);
                    ship.simulation().setTurbine(next);
                }
                case REACTOR_DOWN -> {
                    double next = Math.max(0.0D, ship.simulation().fission() - 0.1D);
                    ship.simulation().setFission(next);
                    ship.simulation().setTurbine(next);
                }
                default -> BarotraumaMod.LOGGER.warn("[Barotrauma] 未知船控指令 {}", action);
            }
            // 船已经被收成物理刚体时，把油门/功率/压载立刻同步给 VS2 推进器。
            // 这是"一套操作面板、一套移动系统"的关键：面板照旧只改 ShipState，
            // 真正推动船的是哪个系统由 VsState 决定。
            com.opamb.barotrauma.ship.vs.VsState.syncControl(ship);
            // 掌舵指令每一下都刷屏，交给面板显示
            if (action != SET_HEADING && action != SET_POWER && action != SET_AUTOPILOT_TARGET) {
                player.sendSystemMessage(ship.liveReport());
            }
        });
        context.setPacketHandled(true);
    }

    private static int clampAxis(int value) {
        return Math.max(-1, Math.min(1, value));
    }

    /**
     * 声纳屏"下潜"：玩家 16 格内必须有船核心（和命令入口同一套校验与提示）。
     * 服务端权威：距离、是否已有物理船都在这里判定，客户端按钮只发请求。
     */
    private static void handleLaunch(ServerPlayer player, ServerLevel level) {
        if (!com.opamb.barotrauma.ship.vs.VsSupport.available()) {
            player.sendSystemMessage(Component.literal(
                    "没装瓦尔基里天空 2（valkyrienskies），物理船不可用")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (com.opamb.barotrauma.ship.vs.VsPilot.hasShip()) {
            player.sendSystemMessage(Component.literal("已经有一艘物理船了，先靠泊再启动下一艘")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ShipCoreBlockEntity core = ShipCores.nearestCore(level, player.blockPosition(), 16.0D);
        if (core == null) {
            player.sendSystemMessage(Component.literal("附近 16 格内没有船核心，到船的声纳台旁边再试")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ShipState state = core.state() != null ? core.state() : core.rescan(level);
        java.util.Set<BlockPos> blocks = state.collectBlocks(level);
        player.sendSystemMessage(Component.literal(
                        com.opamb.barotrauma.ship.vs.VsPilot.launch(level, blocks, state, core.getBlockPos()))
                .withStyle(ChatFormatting.AQUA));
    }

    /** 声纳屏"靠泊"：物理船按装配快照铺回世界；没船/不同维度由 {@code VsPilot.dock} 回报原因。 */
    private static void handleDock(ServerPlayer player, ServerLevel level) {
        String message = com.opamb.barotrauma.ship.vs.VsPilot.dock(level);
        player.sendSystemMessage(Component.literal(message)
                .withStyle(ChatFormatting.AQUA));
    }
}
