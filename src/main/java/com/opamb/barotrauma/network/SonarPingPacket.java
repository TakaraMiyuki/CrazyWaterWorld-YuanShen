package com.opamb.barotrauma.network;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.ShipSimulation;
import com.opamb.barotrauma.ship.ShipState;
import com.opamb.barotrauma.sonar.SonarScan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求一次声纳扫描。
 *
 * <p>声纳必须由服务端扫：客户端拿不到未加载区块，而且在海里视野只有几格。</p>
 *
 * <p>模式按 wiki 的三种走：主动（360°）/ 定向（30° 锥）/ 被动（只听声音，射程随船的吵闹程度）。</p>
 */
public record SonarPingPacket(BlockPos sonarPos, int range, int mode, float aimX, float aimY, float aimZ) {

    /** 被动声纳的最小声程：船完全安静时也能听到周围这么远。 */
    private static final int PASSIVE_MIN_RANGE = 16;
    /** 被动声纳每多一格射程需要的"吵闹程度"跨度。 */
    private static final int PASSIVE_RANGE_SPAN = 48;

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(sonarPos);
        buf.writeVarInt(range);
        buf.writeVarInt(mode);
        buf.writeFloat(aimX);
        buf.writeFloat(aimY);
        buf.writeFloat(aimZ);
    }

    public static SonarPingPacket decode(FriendlyByteBuf buf) {
        return new SonarPingPacket(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            // 船在开动，声纳台方块会跟着挪；坐标对不上时以玩家为准（玩家在船里）
            // 物理船：声纳台的方块坐标是 VS2 的"船坞坐标"，拿它去扫世界只会扫到船坞，
            // 玩家自己在船上、坐标已经是世界坐标，直接用玩家的。
            boolean physical = com.opamb.barotrauma.ship.vs.VsState.active();
            BlockPos center = !physical && level.getBlockState(sonarPos).is(ModShipBlocks.SONAR.get())
                    ? sonarPos : player.blockPosition();

            ShipCoreBlockEntity core = ShipCores.resolve(level, null, center);
            ShipState ship = null;
            if (core != null) {
                ship = core.state() != null ? core.state() : core.rescan(level);
            } else if (com.opamb.barotrauma.ship.vs.VsSupport.available()
                    && com.opamb.barotrauma.ship.vs.VsPilot.hasShip()) {
                // 装了 VS2：船方块进了刚体，世界里找不到核心，用物理船那份状态
                ship = com.opamb.barotrauma.ship.vs.VsPilot.state();
            }
            Set<BlockPos> hull = ship == null ? null : hullForSonar(ship);

            SonarScan.Mode scanMode = SonarScan.Mode.byOrdinal(mode);
            // 被动模式没有回波，射程由船有多吵决定：反应堆出力 + 引擎在不在推
            int effectiveRange = scanMode == SonarScan.Mode.PASSIVE
                    ? passiveRange(ship)
                    : range;

            // 定向脉冲打向"视线方向"（屏幕里准星指的地方），没给就退回船的推力方向
            float dirX = aimX;
            float dirY = aimY;
            float dirZ = aimZ;
            if (Math.abs(dirX) + Math.abs(dirY) + Math.abs(dirZ) < 1.0E-4F) {
                dirX = ship == null ? 0.0F : ship.throttleX();
                dirY = ship == null ? 0.0F : ship.throttleY();
                dirZ = ship == null ? 1.0F : ship.throttleZ();
                if (dirX == 0.0F && dirY == 0.0F && dirZ == 0.0F) {
                    dirZ = 1.0F;
                }
            }

            SonarScan.Frame frame = SonarScan.scan(level, center, effectiveRange, hull, player,
                    scanMode, dirX, dirY, dirZ);

            int power = ship == null ? 100 : ship.power();
            double speed = ship == null ? 0.0D : ship.blocksPerSecond();
            // 有惯性之后"下潜速度"得看真实垂速，不能再拿推力方向凑
            double verticalSpeed = ship == null ? 0.0D : ship.physics().verticalSpeed();
            long speedTenths = Math.round(speed * 3.6D * 10.0D);          // 格/秒 → 公里/时
            long diveTenths = Math.round(Math.abs(verticalSpeed) * 3.6D * 10.0D)
                    * (verticalSpeed < -0.05D ? -1 : verticalSpeed > 0.05D ? 1 : 0);

            // 自动驾驶目标：转成相对玩家的偏移，和客户端点选时的基准保持一致
            BlockPos target = ship == null ? null : ship.autopilotTarget();
            BlockPos base = player.blockPosition();
            boolean hasTarget = target != null;

            ModNetwork.sendToPlayer(player, new SonarDataPacket(frame.range(), frame.points(),
                    power,
                    ship == null ? 0 : ship.throttleX(),
                    ship == null ? 0 : ship.throttleY(),
                    ship == null ? 0 : ship.throttleZ(),
                    scanMode.id(),
                    -center.getY(),
                    (int) speedTenths,
                    (int) diveTenths,
                    ship != null && ship.autopilotHold() ? 1 : 0,
                    hasTarget,
                    hasTarget ? target.getX() - base.getX() : 0,
                    hasTarget ? target.getY() - base.getY() : 0,
                    hasTarget ? target.getZ() - base.getZ() : 0,
                    statusText(ship, center, scanMode, frame.range()),
                    coreText(ship)));
        });
        context.setPacketHandled(true);
    }

    /**
     * 声纳要显示的船体点。
     *
     * <p>物理船的船体方块在船坞坐标里，而声纳是以玩家（船）为中心扫世界——
     * 不把船体点按船的整数位移搬过去，画面上那团绿色船体就会留在装配时的老位置，
     * 看起来就是"声纳里的船不跟着船走"。</p>
     */
    private static Set<BlockPos> hullForSonar(ShipState ship) {
        Set<BlockPos> raw = ship.geometry().hull();
        if (!com.opamb.barotrauma.ship.vs.VsState.active()) {
            return raw;
        }
        int dx = com.opamb.barotrauma.ship.vs.VsState.offsetX();
        int dy = com.opamb.barotrauma.ship.vs.VsState.offsetY();
        int dz = com.opamb.barotrauma.ship.vs.VsState.offsetZ();
        if (dx == 0 && dy == 0 && dz == 0) {
            return raw;
        }
        Set<BlockPos> out = new java.util.HashSet<>(raw.size());
        for (BlockPos pos : raw) {
            out.add(pos.offset(dx, dy, dz));
        }
        return out;
    }

    /**
     * 核心页正文。
     *
     * <p>声纳台现在兼任船核心，所以"核心"这一页讲的就是这艘船本身：
     * 船体扫描结果（舱室、方块数、密闭）+ 机器清单 + 实时状态。</p>
     */
    private static String coreText(ShipState ship) {
        if (ship == null) {
            return "未接入船核心\n\n声纳台就是船核心：把它装进船体内部，右键即可接管这艘船。";
        }
        // 明确告诉玩家现在哪一套移动系统在跑——两套同时跑过一次就够了
        String drive = com.opamb.barotrauma.ship.vs.VsState.active()
                ? "驱动：VS2 物理刚体（方块推进已停用，姿态锁定水平）"
                : "驱动：方块推进（离散落格，未物理化）";
        return ship.summary().getString() + "\n" + ship.liveReport().getString() + "\n" + drive;
    }

    /** 船越吵，被动声纳听得越远（wiki：range of passive sonar is dependent on how loud the submarine is）。 */
    private static int passiveRange(ShipState ship) {
        if (ship == null) {
            return PASSIVE_MIN_RANGE;
        }
        ShipSimulation.Report report = ship.simulation().report();
        double reactor = Math.min(1.0D, report.powerOutput() / 5000.0D);
        double engine = ship.throttled() ? 1.0D : 0.0D;
        double loudness = Math.min(1.0D, reactor * 0.75D + engine * 0.45D);
        return (int) Math.round(PASSIVE_MIN_RANGE + PASSIVE_RANGE_SPAN * loudness);
    }

    /** 面板右栏的几行字。 */
    public static String statusText(ShipState ship, BlockPos center, SonarScan.Mode mode, int range) {
        StringBuilder sb = new StringBuilder();
        String modeName = switch (mode) {
            case PASSIVE -> "被动";
            case DIRECTIONAL -> "定向";
            default -> "主动";
        };
        sb.append(modeName).append(" ").append(range).append(" m");
        if (mode == SonarScan.Mode.ACTIVE) {
            sb.append("  ·  会引来生物");
        }
        if (ship == null) {
            sb.append("\n未接入船核心");
            return sb.toString();
        }
        ShipSimulation.Report report = ship.simulation().report();
        sb.append(String.format(Locale.ROOT, "\n温度 %.0f°C   氧气 %.0f%%",
                report.temperature(), report.oxygenPercent() * 100.0D));
        return sb.toString();
    }
}
