package com.opamb.barotrauma.event;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModBlocks;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.ShipState;
import com.opamb.barotrauma.ship.vs.VsBridge;
import com.opamb.barotrauma.ship.vs.VsFloodSmoke;
import com.opamb.barotrauma.ship.vs.VsPilot;
import com.opamb.barotrauma.ship.vs.VsState;
import com.opamb.barotrauma.ship.vs.VsSupport;
import com.opamb.barotrauma.ship.water.ShipVolumeGuard;
import com.opamb.barotrauma.sonar.SonarScan;
import com.opamb.barotrauma.world.ModDimensions;
import com.opamb.barotrauma.world.StarterSub;
import com.opamb.barotrauma.world.TrunkNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Locale;

/**
 * 深海维度的进入、出生与自检。
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DeepSeaEvents {

    private static final String VISITED_TAG = "barotrauma_visited_deep";

    /** 自检用：还要跑多少 tick，以及要观察的船核心。 */
    private static int smokeTicks = 0;
    private static ShipCoreBlockEntity smokeCore;

    private DeepSeaEvents() {
    }

    /**
     * 交互锚点不许被拆掉（创造模式能拆".strength(-1)"的方块）。
     *
     * <p>它不是真实的船上设备，只是声纳的点击代理；被拆掉玩家就再也开不了声纳。</p>
     */
    @SubscribeEvent
    public static void onProxyBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getState().is(ModShipBlocks.SONAR_PROXY.get())) {
            event.setCanceled(true);
        }
    }

    /** 第一次进服的玩家直接送进哨站，并把重生点设在那里。 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.getPersistentData().getBoolean(VISITED_TAG)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        player.getPersistentData().putBoolean(VISITED_TAG, true);
        // 登录事件里直接传送容易被打断，推迟到下一 tick。
        server.execute(() -> sendToOutpost(player));
    }

    public static boolean sendToOutpost(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        ServerLevel deep = server.getLevel(ModDimensions.THE_DEEP);
        if (deep == null) {
            BarotraumaMod.LOGGER.warn("[Barotrauma] 深海维度未加载，检查 data/barotrauma/dimension/the_deep.json");
            return false;
        }
        player.teleportTo(deep, 0.5D, ModDimensions.OUTPOST_SPAWN_Y, 0.5D, 0.0F, 0.0F);
        player.setRespawnPosition(ModDimensions.THE_DEEP,
                new BlockPos(ModDimensions.OUTPOST_X, ModDimensions.OUTPOST_SPAWN_Y, ModDimensions.OUTPOST_Z),
                0.0F, true, false);
        // 门口那艘初始潜艇：出生点旁边就该有自己的船（第一次调用会现造 + 扫舱）
        StarterSub.ensure(deep);
        return true;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("barotrauma")
                .then(Commands.literal("deep").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!sendToOutpost(player)) {
                        ctx.getSource().sendFailure(Component.literal("深海维度未加载"));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("已进入深海哨站"), false);
                    return 1;
                }))
                .then(Commands.literal("surface").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ServerLevel overworld = player.getServer().overworld();
                    BlockPos spawn = overworld.getSharedSpawnPos();
                    player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D, 0.0F, 0.0F);
                    ctx.getSource().sendSuccess(() -> Component.literal("已返回主世界"), false);
                    return 1;
                }))
                .then(Commands.literal("depth").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    int meters = ModDimensions.toMeters(player.blockPosition().getY());
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("当前深度 " + meters + " m").withStyle(ChatFormatting.AQUA), false);
                    return 1;
                }))
                .then(Commands.literal("ship")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            BlockEntity found = findShipCore(player);
                            if (!(found instanceof ShipCoreBlockEntity core)) {
                                ctx.getSource().sendFailure(Component.literal("附近 16 格内没有船核心"));
                                return 0;
                            }
                            ShipState ship = core.rescan(player.serverLevel());
                            player.sendSystemMessage(ship.summary());
                            player.sendSystemMessage(ship.liveReport());
                            return 1;
                        })
                        .then(Commands.literal("sim")
                                .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.1D, 600.0D))
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            BlockEntity found = findShipCore(player);
                                            if (!(found instanceof ShipCoreBlockEntity core)) {
                                                ctx.getSource().sendFailure(Component.literal("附近 16 格内没有船核心"));
                                                return 0;
                                            }
                                            ShipState ship = ensureState(core, player.serverLevel());
                                            double seconds = DoubleArgumentType.getDouble(ctx, "seconds");
                                            player.sendSystemMessage(ship.simulate(seconds));
                                            return 1;
                                        })))
                        .then(Commands.literal("flood")
                                .then(Commands.argument("units", DoubleArgumentType.doubleArg(0.0D, 100000.0D))
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            BlockEntity found = findShipCore(player);
                                            if (!(found instanceof ShipCoreBlockEntity core)) {
                                                ctx.getSource().sendFailure(Component.literal("附近 16 格内没有船核心"));
                                                return 0;
                                            }
                                            ShipState ship = ensureState(core, player.serverLevel());
                                            double units = DoubleArgumentType.getDouble(ctx, "units");
                                            ship.simulation().flood(0, units);
                                            int changed = ship.applyWater(player.serverLevel());
                                            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                                                            "向 1 号舱注入 %d 单位水，写入 %d 格水方块",
                                                            Math.round(units), changed))
                                                    .withStyle(ChatFormatting.AQUA));
                                            return 1;
                                        })))
                        .then(Commands.literal("throttle")
                                .then(Commands.argument("dx", IntegerArgumentType.integer(-1, 1))
                                        .then(Commands.argument("dy", IntegerArgumentType.integer(-1, 1))
                                                .then(Commands.argument("dz", IntegerArgumentType.integer(-1, 1))
                                                        .executes(DeepSeaEvents::throttle)))))
                        .then(Commands.literal("stop")
                                .executes(DeepSeaEvents::stopShip))
                        .then(Commands.literal("power")
                                .then(Commands.argument("percent", IntegerArgumentType.integer(5, 100))
                                        .executes(DeepSeaEvents::power)))
                        .then(Commands.literal("autopilot")
                                .then(Commands.literal("hold").executes(ctx -> autopilot(ctx, true)))
                                .then(Commands.literal("off").executes(ctx -> autopilot(ctx, false)))
                                .then(Commands.literal("goto")
                                        .then(Commands.argument("dx", IntegerArgumentType.integer(-64, 64))
                                                .then(Commands.argument("dy", IntegerArgumentType.integer(-64, 64))
                                                        .then(Commands.argument("dz",
                                                                        IntegerArgumentType.integer(-64, 64))
                                                                .executes(DeepSeaEvents::autopilotGoto))))))
                        .then(Commands.literal("sonar")
                                .executes(ctx -> openSonar(ctx))
                                .then(Commands.literal("passive")
                                        .executes(ctx -> sonar(ctx, SonarScan.DEFAULT_RANGE,
                                                SonarScan.Mode.PASSIVE)))
                                .then(Commands.literal("active")
                                        .executes(ctx -> sonar(ctx, SonarScan.DEFAULT_RANGE,
                                                SonarScan.Mode.ACTIVE)))
                                .then(Commands.literal("directional")
                                        .executes(ctx -> sonar(ctx, SonarScan.DEFAULT_RANGE,
                                                SonarScan.Mode.DIRECTIONAL)))
                                .then(Commands.argument("range",
                                                IntegerArgumentType.integer(SonarScan.MIN_RANGE, SonarScan.MAX_RANGE))
                                        .executes(ctx -> sonar(ctx, IntegerArgumentType.getInteger(ctx, "range"),
                                                SonarScan.Mode.ACTIVE))))
                        // 物理船（瓦尔基里天空 2）：装船后整艘船变成刚体，方块离开世界
                        .then(Commands.literal("vs")
                                .then(Commands.literal("launch")
                                        .executes(DeepSeaEvents::vsLaunch)
                                        // 控制台也能装船：不给玩家时按坐标找核心，方便无人调试
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> vsLaunchAt(ctx,
                                                        BlockPosArgument.getBlockPos(ctx, "pos")))))
                                .then(Commands.literal("dock").executes(DeepSeaEvents::vsDock))
                                .then(Commands.literal("status").executes(DeepSeaEvents::vsStatus))
                                // 进水诊断：船原来占的那片世界里现在有多少格是海水
                                .then(Commands.literal("flood").executes(DeepSeaEvents::vsFlood))
                                // 全局统计：船 / 已加载船 / 物理实体各多少。没船也能跑，
                                // 用来判断"存档里加载回来的船到底有没有物理实体"。
                                .then(Commands.literal("world").executes(DeepSeaEvents::vsWorld))
                                .then(Commands.literal("thrust")
                                        .then(Commands.argument("dx", IntegerArgumentType.integer(-1, 1))
                                                .then(Commands.argument("dy", IntegerArgumentType.integer(-1, 1))
                                                        .then(Commands.argument("dz",
                                                                        IntegerArgumentType.integer(-1, 1))
                                                                .executes(ctx -> vsThrust(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "dx"),
                                                                        IntegerArgumentType.getInteger(ctx, "dy"),
                                                                        IntegerArgumentType.getInteger(ctx, "dz")))))))
                                .then(Commands.literal("power")
                                        .then(Commands.argument("percent", IntegerArgumentType.integer(5, 100))
                                                .executes(ctx -> vsPower(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "percent"))))))));
    }

    /** 已有船状态就直接用，避免每次命令都重扫（重扫会重置模拟）。 */
    private static ShipState ensureState(ShipCoreBlockEntity core, ServerLevel level) {
        ShipState ship = core.state();
        return ship != null ? ship : core.rescan(level);
    }

    /** 设定推进方向。 */
    private static int throttle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return applyThrottle(ctx, IntegerArgumentType.getInteger(ctx, "dx"),
                IntegerArgumentType.getInteger(ctx, "dy"),
                IntegerArgumentType.getInteger(ctx, "dz"));
    }

    private static int stopShip(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return applyThrottle(ctx, 0, 0, 0);
    }

    private static int applyThrottle(CommandContext<CommandSourceStack> ctx, int dx, int dy, int dz)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockEntity found = findShipCore(player);
        if (!(found instanceof ShipCoreBlockEntity core)) {
            ctx.getSource().sendFailure(Component.literal("附近 16 格内没有船核心"));
            return 0;
        }
        ShipState ship = ensureState(core, player.serverLevel());
        ship.setThrottle(dx, dy, dz);
        player.sendSystemMessage(Component.literal("推进：" + ship.throttleName())
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** 设定推进功率（%）。 */
    private static int power(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockEntity found = findShipCore(player);
        if (!(found instanceof ShipCoreBlockEntity core)) {
            ctx.getSource().sendFailure(Component.literal("附近 16 格内没有船核心"));
            return 0;
        }
        ShipState ship = ensureState(core, player.serverLevel());
        ship.setPower(IntegerArgumentType.getInteger(ctx, "percent"));
        player.sendSystemMessage(Component.literal("推力 " + ship.power() + "%")
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** 自动驾驶：保持当前位置（wiki 的 Maintain position）。 */
    private static int autopilot(CommandContext<CommandSourceStack> ctx, boolean hold) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockEntity found = findShipCore(player);
        if (!(found instanceof ShipCoreBlockEntity core)) {
            ctx.getSource().sendFailure(Component.literal("附近 16 格内没有船核心"));
            return 0;
        }
        ShipState ship = ensureState(core, player.serverLevel());
        ship.setAutopilotHold(hold);
        player.sendSystemMessage(Component.literal("自动驾驶：" + (hold ? "开启（等待标记点）" : "关闭"))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** 自动驾驶：在当前位置偏移处标一个目标点（等价于在声纳上点一下）。 */
    private static int autopilotGoto(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockEntity found = findShipCore(player);
        if (!(found instanceof ShipCoreBlockEntity core)) {
            ctx.getSource().sendFailure(Component.literal("附近 16 格内没有船核心"));
            return 0;
        }
        ShipState ship = ensureState(core, player.serverLevel());
        BlockPos target = player.blockPosition().offset(
                IntegerArgumentType.getInteger(ctx, "dx"),
                IntegerArgumentType.getInteger(ctx, "dy"),
                IntegerArgumentType.getInteger(ctx, "dz"));
        ship.setAutopilotTarget(target);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "自动驾驶：前往 %s（%d 格）", target.toShortString(),
                        ship.autopilotDistance(core.getBlockPos())))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** 手动跑一次声纳扫描，看看点云和耗时（界面走的是同一个扫描器）。 */
    private static int sonar(CommandContext<CommandSourceStack> ctx, int range, SonarScan.Mode mode)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();

        ShipCoreBlockEntity core = ShipCores.resolve(level, null, center);
        ShipState ship = core == null ? null : ensureState(core, level);
        java.util.Set<BlockPos> hull = ship == null ? null : ship.geometry().hull();

        float dirX = ship == null ? 0.0F : ship.throttleX();
        float dirY = ship == null ? 0.0F : ship.throttleY();
        float dirZ = ship == null ? 1.0F : ship.throttleZ();

        long start = System.nanoTime();
        SonarScan.Frame frame = SonarScan.scan(level, center, range, hull, player, mode, dirX, dirY, dirZ);
        long micros = (System.nanoTime() - start) / 1000L;

        int terrain = 0;
        int hullPoints = 0;
        int contacts = 0;
        for (int point : frame.points()) {
            switch (SonarScan.type(point)) {
                case SonarScan.TYPE_HULL -> hullPoints++;
                case SonarScan.TYPE_CONTACT -> contacts++;
                default -> terrain++;
            }
        }
        String text = String.format(Locale.ROOT,
                "声纳 %s %d m（步长 %d）｜地形 %d 点｜船体 %d 点｜接触 %d｜耗时 %d µs",
                mode, frame.range(), SonarScan.strideFor(range), terrain, hullPoints, contacts, micros);
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.AQUA));
        return 1;
    }


    /**
     * 打开声纳界面。
     *
     * <p>物理船的方块在 VS2 船坞区块里，右键声纳台不会生效（VS2 2.4.11 不重映射方块交互），
     * 所以掌舵入口改成命令：站在船上执行 {@code /barotrauma ship sonar} 即可。</p>
     */
    private static int openSonar(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos anchor = player.blockPosition();
        ShipCoreBlockEntity core = ShipCores.resolve(level, null, anchor);
        if (core != null && core.state() == null) {
            core.rescan(level);
        }
        NetworkHooks.openScreen(player,
                new net.minecraft.world.SimpleMenuProvider(
                        (id, inventory, p) -> new com.opamb.barotrauma.menu.SonarMenu(
                                id, inventory, anchor, SonarScan.DEFAULT_RANGE),
                        Component.translatable("block.barotrauma.sonar")),
                buf -> {
                    buf.writeBlockPos(anchor);
                    buf.writeVarInt(SonarScan.DEFAULT_RANGE);
                });
        return 1;
    }

    // ------------------------------------------------------------------ 物理船（VS2）

    private static int vsLaunch(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!VsSupport.available()) {
            ctx.getSource().sendFailure(Component.literal("没装瓦尔基里天空 2（valkyrienskies），物理船不可用"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockEntity found = findShipCore(player);
        if (!(found instanceof ShipCoreBlockEntity core)) {
            ctx.getSource().sendFailure(Component.literal("附近 16 格内没有船核心"));
            return 0;
        }
        ShipState state = ensureState(core, player.serverLevel());
        // 用包围盒收集全部船方块，而不是 geometry.hull()（最大连通分量）：
        // 漏掉的那些会留在原地，看起来就是"船开走了原地还剩一截"。
        java.util.Set<BlockPos> blocks = state.collectBlocks(player.serverLevel());
        player.sendSystemMessage(Component.literal(
                        "⚠ 物理船：方块会被 VS2 收进刚体（世界里那一格只剩空气，这是正常的），"
                                + "之后移动只走物理、方块那一套自动停用；舵在声纳里，"
                                + "拆回来用 /barotrauma ship vs dock（按装配快照原样铺回）")
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal(
                        VsPilot.launch(player.serverLevel(), blocks, state, core.getBlockPos()))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** 不给玩家时按坐标装船，控制台也能跑（无人调试用）。 */
    private static int vsLaunchAt(CommandContext<CommandSourceStack> ctx, BlockPos center) {
        if (!VsSupport.available()) {
            ctx.getSource().sendFailure(Component.literal("没装瓦尔基里天空 2（valkyrienskies），物理船不可用"));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        BlockEntity found = findShipCore(level, center);
        if (!(found instanceof ShipCoreBlockEntity core)) {
            ctx.getSource().sendFailure(Component.literal("坐标附近 16 格内没有船核心"));
            return 0;
        }
        ShipState state = ensureState(core, level);
        java.util.Set<BlockPos> blocks = state.collectBlocks(level);
        ctx.getSource().sendSuccess(
                () -> Component.literal(VsPilot.launch(level, blocks, state, core.getBlockPos())), false);
        return 1;
    }

    /** 找指定坐标附近最近的船核心（不看玩家）。 */
    @Nullable
    private static BlockEntity findShipCore(ServerLevel level, BlockPos center) {
        BlockEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -16, -16), center.offset(16, 16, 16))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ShipCoreBlockEntity core)) {
                continue;
            }
            double dist = pos.distSqr(center);
            if (dist < bestDist) {
                bestDist = dist;
                best = core;
            }
        }
        return best;
    }

    private static int vsDock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!VsSupport.available()) {
            ctx.getSource().sendFailure(Component.literal("没装瓦尔基里天空 2"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.sendSystemMessage(Component.literal(VsPilot.dock(player.serverLevel()))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /**
     * 进水诊断：物理船原来占的那片世界现在有多少格是海水。
     *
     * <p>VS2 装配会把船体方块从世界里挖走，海里那一块立刻被海水灌满。玩家透过透明船壳看，
     * 看到的就是"船里全是水"。这条命令就是量这个数字的。</p>
     */
    private static int vsFlood(CommandContext<CommandSourceStack> ctx) {
        if (!VsSupport.available()) {
            ctx.getSource().sendFailure(Component.literal("没装瓦尔基里天空 2"));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        ctx.getSource().sendSuccess(
                () -> Component.literal(VsPilot.floodProbe(level, VsPilot.state())), false);
        return 1;
    }

    private static int vsStatus(CommandContext<CommandSourceStack> ctx) {        if (!VsSupport.available()) {
            ctx.getSource().sendFailure(Component.literal("没装瓦尔基里天空 2"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(VsPilot.status()), false);
        return 1;
    }

    /** 当前维度 VS2 船世界的全局统计（不看有没有我们的船）。 */
    private static int vsWorld(CommandContext<CommandSourceStack> ctx) {
        if (!VsSupport.available()) {
            ctx.getSource().sendFailure(Component.literal("没装瓦尔基里天空 2"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                VsBridge.worldStats(ctx.getSource().getLevel())), false);
        return 1;
    }

    private static int vsThrust(CommandContext<CommandSourceStack> ctx, int dx, int dy, int dz) {
        if (!VsSupport.available() || !VsPilot.hasShip()) {
            ctx.getSource().sendFailure(Component.literal("现在没有物理船（先 /barotrauma ship vs launch）"));
            return 0;
        }
        VsPilot.thruster().setThrottle(dx, dy, dz);
        // 面板上的推力来自 ShipState，控制台改推力也要同步过去，否则面板显示 0 实际在推
        ShipState ship = VsPilot.state();
        if (ship != null) {
            ship.setThrottle(dx, dy, dz);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("物理船推力 (" + dx + "," + dy + "," + dz + ")"), false);
        return 1;
    }

    private static int vsPower(CommandContext<CommandSourceStack> ctx, int percent) {
        if (!VsSupport.available() || !VsPilot.hasShip()) {
            ctx.getSource().sendFailure(Component.literal("现在没有物理船"));
            return 0;
        }
        VsPilot.thruster().setPower(percent);
        ShipState ship = VsPilot.state();
        if (ship != null) {
            ship.setPower(percent);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("物理船推力 " + percent + "%"), false);
        return 1;
    }

    // ------------------------------------------------------------------ 物理船（VS2）

    /**
     * A2 自检：在实心岩里搭一个 9×5×15 的空盒子（外壳装甲板、内部空气），放船核心后扫描。
     * 预期：1 个密闭舱室、容积 7×3×13 = 273 格、进水 0%。
     */
    private static void shipSelfTest(ServerLevel deep) {
        BlockPos base = new BlockPos(200, -200, 200);
        deep.getChunk(base.getX() >> 4, base.getZ() >> 4);
        // 没有玩家时区块不会 tick；强制加载船所在区块，才能验证方块实体实时推进
        deep.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        // 声纳只读已加载区块（否则会同步加载区块，一次扫描卡两百毫秒），自检里把周围一圈也加载上
        for (int cdx = -3; cdx <= 3; cdx++) {
            for (int cdz = -3; cdz <= 3; cdz++) {
                deep.setChunkForced((base.getX() >> 4) + cdx, (base.getZ() >> 4) + cdz, true);
            }
        }

        // 先在岩石里挖一个水池，给后面的平移测试留出空间
        BlockState pocketWater = Blocks.WATER.defaultBlockState();
        for (int dx = -2; dx <= 12; dx++) {
            for (int dy = -2; dy <= 8; dy++) {
                for (int dz = -4; dz <= 34; dz++) {
                    deep.setBlock(base.offset(dx, dy, dz), pocketWater, 2);
                }
            }
        }

        BlockState hull = ModBlocks.SUBMARINE_PLATING.get().defaultBlockState();
        for (int dx = 0; dx < 9; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                for (int dz = 0; dz < 15; dz++) {
                    boolean shell = dx == 0 || dx == 8 || dy == 0 || dy == 4 || dz == 0 || dz == 14;
                    deep.setBlock(base.offset(dx, dy, dz),
                            shell ? hull : Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        // 声纳台就是船脑（机器/舱门已删除，自检船只有船壳 + 声纳）
        BlockPos corePos = base.offset(5, 1, 4);
        deep.setBlock(corePos, ModShipBlocks.SONAR.get().defaultBlockState(), 3);
        // 贴着船体放一圈站点装甲：它不该被算进船体
        BlockState station = ModShipBlocks.STATION_PLATING.get().defaultBlockState();
        for (int i = 1; i <= 8; i++) {
            deep.setBlock(base.offset(9, 1 + (i % 3), i), station, 3);
        }
        BlockEntity be = deep.getBlockEntity(corePos);
        if (be instanceof ShipCoreBlockEntity core) {
            ShipState ship = core.rescan(deep);
            BarotraumaMod.LOGGER.info("[smoketest] ship core -> {}",
                    ship.summary().getString().replace('\n', '|'));

            // 场景 0b：声纳点云（界面用的就是这一个扫描器）
            for (int sonarRange : new int[]{16, SonarScan.DEFAULT_RANGE, SonarScan.MAX_RANGE}) {
                long started = System.nanoTime();
                SonarScan.Frame frame = SonarScan.scan(deep, corePos, sonarRange,
                        ship.geometry().hull(), null);
                long micros = (System.nanoTime() - started) / 1000L;
                int terrain = 0;
                int hullPoints = 0;
                int contacts = 0;
                double nearestHull = Double.MAX_VALUE;
                for (int point : frame.points()) {
                    switch (SonarScan.type(point)) {
                        case SonarScan.TYPE_HULL -> {
                            hullPoints++;
                            double d = Math.sqrt(SonarScan.x(point) * SonarScan.x(point)
                                    + SonarScan.y(point) * SonarScan.y(point)
                                    + SonarScan.z(point) * SonarScan.z(point));
                            nearestHull = Math.min(nearestHull, d);
                        }
                        case SonarScan.TYPE_CONTACT -> contacts++;
                        default -> terrain++;
                    }
                }
                BarotraumaMod.LOGGER.info(
                        "[smoketest] sonar {}m stride={} -> points={} terrain={} hull={} contacts={} nearestHull={} cost={}µs",
                        frame.range(), SonarScan.strideFor(sonarRange), frame.size(), terrain, hullPoints,
                        contacts, nearestHull == Double.MAX_VALUE ? -1.0D : Math.round(nearestHull), micros);
            }

            // 场景 0c：三种模式（wiki：主动 360° / 定向 30° / 被动只听声音）
            for (SonarScan.Mode sonarMode : SonarScan.Mode.values()) {
                SonarScan.Frame frame = SonarScan.scan(deep, corePos, SonarScan.DEFAULT_RANGE,
                        ship.geometry().hull(), null, sonarMode, 0.0F, 0.0F, 1.0F);
                int terrain = 0;
                int hullPoints = 0;
                for (int point : frame.points()) {
                    if (SonarScan.type(point) == SonarScan.TYPE_HULL) {
                        hullPoints++;
                    } else if (SonarScan.type(point) == SonarScan.TYPE_TERRAIN) {
                        terrain++;
                    }
                }
                BarotraumaMod.LOGGER.info("[smoketest] sonar mode {} -> terrain={} hull={}",
                        sonarMode, terrain, hullPoints);
            }

            // 场景 0d：自动驾驶 = 前往标记点；到点 2 格内推力归零
            ship.setAutopilotTarget(corePos.offset(12, 0, 0));
            int[] far = ship.previewAutopilot(corePos);
            ship.setAutopilotTarget(corePos.offset(1, 0, 0));
            int[] near = ship.previewAutopilot(corePos);
            ship.clearAutopilotTarget();
            ship.setAutopilotHold(false);
            BarotraumaMod.LOGGER.info(
                    "[smoketest] autopilot aim -> far=({},{},{}) near=({},{},{}) (期望 1,0,0 / 0,0,0)",
                    far[0], far[1], far[2], near[0], near[1], near[2]);

            // 场景 0e：运动模型——起步是加速不是瞬移，松油门靠惯性滑；压载满会自己往下沉
            com.opamb.barotrauma.ship.ShipPhysics physics = new com.opamb.barotrauma.ship.ShipPhysics();
            for (int i = 0; i < 20; i++) {
                physics.tickManual(0.1D, 1, 0, 0, 100, 0.0D, 0.0D);
            }
            double topSpeed = physics.speed();
            for (int i = 0; i < 20; i++) {
                physics.tickManual(0.1D, 0, 0, 0, 100, 0.0D, 0.0D);
            }
            double coastSpeed = physics.speed();
            com.opamb.barotrauma.ship.ShipPhysics sinking = new com.opamb.barotrauma.ship.ShipPhysics();
            for (int i = 0; i < 30; i++) {
                sinking.tickManual(0.1D, 0, 0, 0, 100, 1.0D, 0.0D);
            }
            BarotraumaMod.LOGGER.info(
                    "[smoketest] physics -> 满推力 2s 速度={} / 松油门再 2s 速度={} / 压载满 3s 垂速={}",
                    String.format(java.util.Locale.ROOT, "%.2f", topSpeed),
                    String.format(java.util.Locale.ROOT, "%.2f", coastSpeed),
                    String.format(java.util.Locale.ROOT, "%.2f", sinking.verticalSpeed()));

            // 水方块可视化：注入 5000 单位（=50 格），应从舱底铺起 50 格水
            ship.simulation().flood(0, 5000.0D);
            int placed = ship.applyWater(deep);
            int waterCells = 0;
            for (BlockPos cell : ship.compartments().get(0).cells()) {
                if (deep.getBlockState(cell).is(Blocks.WATER)) {
                    waterCells++;
                }
            }
            BarotraumaMod.LOGGER.info("[smoketest] water apply -> changed={} waterCells={} (expect 50)",
                    placed, waterCells);

            // 留给实时 tick 验证：200 tick 后打印状态并关服
            if (smokeCore == null) {
                smokeCore = core;
            }
            smokeTicks = 200;
        } else {
            BarotraumaMod.LOGGER.warn("[smoketest] ship core block entity missing: {}", be);
        }
    }



    /** 找玩家附近最近的船核心。 */
    @Nullable
    private static BlockEntity findShipCore(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        BlockEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -16, -16), center.offset(16, 16, 16))) {
            BlockEntity be = player.serverLevel().getBlockEntity(pos);
            if (!(be instanceof ShipCoreBlockEntity core)) {
                continue;
            }
            double dist = pos.distSqr(center);
            if (dist < bestDist) {
                bestDist = dist;
                best = core;
            }
        }
        return best;
    }

    /**
     * 单机退出世界后 JVM 不退出，物理船是静态单例。这里在服务器停止/启动时
     * 清掉上一世界残留的船状态，否则新世界会被误判为"初始潜艇已收成物理船"。
     */
    private static void resetShipRuntime(String phase) {
        VsState.deactivate();
        ShipVolumeGuard.deactivate();
        if (VsSupport.available()) {
            VsPilot.resetForServerLifecycle();
        }
        BarotraumaMod.LOGGER.info("[Barotrauma] 已清理物理船静态状态（{}）", phase);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        resetShipRuntime("服务器停止");
    }

    /**
     * 开发期自检：run/.smoketest 存在时，生成一次哨站区块、打印水柱统计然后关服。
     * 用 gradlew runServer 跑，验证生成器不会崩。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 新服务器不可能沿用上一个服务器的刚体；顺便兜住上次崩溃没走到 ServerStoppedEvent 的情况。
        resetShipRuntime("服务器启动");
        // 隔离场景优先：run/.smoketest-vs 只跑 VsFloodSmoke（水面空腔灌水实验），不跑总自检
        File vsMarker = new File(".smoketest-vs");
        if (!vsMarker.isFile()) {
            vsMarker = new File("run/.smoketest-vs");
        }
        if (vsMarker.isFile()) {
            MinecraftServer vsServer = event.getServer();
            ServerLevel vsDeep = vsServer.getLevel(ModDimensions.THE_DEEP);
            boolean started = vsDeep != null && VsSupport.available() && VsFloodSmoke.begin(vsDeep);
            if (!started) {
                BarotraumaMod.LOGGER.warn("[smoketest-vs] 场景无法启动（维度或 VS2 不可用）");
            }
            if (!vsMarker.delete()) {
                BarotraumaMod.LOGGER.warn("[smoketest-vs] 无法删除标记文件 {}", vsMarker.getAbsolutePath());
            }
            if (!started) {
                vsServer.halt(false);
            }
            return;
        }
        File marker = new File(".smoketest");
        if (!marker.isFile()) {
            marker = new File("run/.smoketest");
        }
        if (!marker.isFile()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel deep = server.getLevel(ModDimensions.THE_DEEP);
        BarotraumaMod.LOGGER.info("[smoketest] deep level = {}", deep);
        if (deep != null) {
            deep.getChunk(0, 0); // 生成哨站区块
            BlockState outpostFloor = deep.getBlockState(new BlockPos(0, ModDimensions.OUTPOST_SPAWN_Y - 1, 0));
            BlockState outpostAir = deep.getBlockState(new BlockPos(0, ModDimensions.OUTPOST_SPAWN_Y, 0));

            int water = 0;
            int rock = 0;
            int air = 0;
            for (int y = ModDimensions.WATER_TOP_Y; y >= ModDimensions.WATER_BOTTOM_Y; y--) {
                BlockState state = deep.getBlockState(new BlockPos(64, y, 64));
                if (state.is(Blocks.WATER)) {
                    water++;
                } else if (state.isAir()) {
                    air++;
                } else {
                    rock++;
                }
            }
            BarotraumaMod.LOGGER.info("[smoketest] outpost floor={} air={}", outpostFloor, outpostAir);

            // 气密性：哨站内部不允许有水
            int leaked = 0;
            for (int x = -18; x <= 18; x++) {
                for (int z = -10; z <= 10; z++) {
                    for (int y = ModDimensions.OUTPOST_SPAWN_Y; y <= ModDimensions.OUTPOST_SPAWN_Y + 16; y++) {
                        if (deep.getBlockState(new BlockPos(x, y, z)).is(Blocks.WATER)) {
                            leaked++;
                        }
                    }
                }
            }
            BarotraumaMod.LOGGER.info("[smoketest] outpost leaked water blocks = {}", leaked);

            // 新布局几何：哨站顶装甲（y=-44）上应直接是顶板岩（贴岩壁），装甲下一格缝隙（y=-45，
            // 压平区天然水层顶部）应是水＝"水池贴顶、哨站上沿嵌进岩壁"的观感来源
            BarotraumaMod.LOGGER.info("[smoketest] roof seal: armor(0,-44)={} rock(0,-43)={}",
                    deep.getBlockState(new BlockPos(0, ModDimensions.OUTPOST_Y + 10, 0)),
                    deep.getBlockState(new BlockPos(0, ModDimensions.OUTPOST_Y + 11, 0)));
            BarotraumaMod.LOGGER.info("[smoketest] pool: beside hull (22,-54)={} station ceiling (0,-45)={}",
                    deep.getBlockState(new BlockPos(22, ModDimensions.OUTPOST_Y, 0)),
                    deep.getBlockState(new BlockPos(0, -45, 0)));
            BarotraumaMod.LOGGER.info("[smoketest] door={} outside={} shell={}",
                    deep.getBlockState(new BlockPos(0, ModDimensions.OUTPOST_SPAWN_Y, 12)),
                    deep.getBlockState(new BlockPos(0, ModDimensions.OUTPOST_SPAWN_Y, 14)),
                    deep.getBlockState(new BlockPos(20, ModDimensions.OUTPOST_Y, 0)));

            // 分层统计：远离哨站的 48x48 区域，看每个深度有多少岩体、多少"被岩石上下夹住的水"
            // 切片可视图：# 岩石  ~ 水  空格 空气
            for (int centerY : new int[]{-300, -600, -900}) {
                StringBuilder sb = new StringBuilder("\n[smoketest] slice z=200, x=176..207, y="
                        + (centerY + 11) + ".." + (centerY - 12) + "\n");
                for (int y = centerY + 11; y >= centerY - 12; y--) {
                    sb.append(String.format("%6d ", y));
                    for (int x = 176; x <= 207; x++) {
                        BlockState state = deep.getBlockState(new BlockPos(x, y, 200));
                        if (state.is(Blocks.WATER)) {
                            sb.append('~');
                        } else if (state.isAir()) {
                            sb.append(' ');
                        } else {
                            sb.append('#');
                        }
                    }
                    sb.append('\n');
                }
                BarotraumaMod.LOGGER.info(sb.toString());
            }

            // 主干道：水平切片，看是否有一条贯穿岩体的水走廊
            StringBuilder map = new StringBuilder("\n[smoketest] horizontal slice y=-610, x=128..175, z=128..175\n");
            for (int z = 128; z <= 175; z++) {
                for (int x = 128; x <= 175; x++) {
                    BlockState state = deep.getBlockState(new BlockPos(x, -610, z));
                    map.append(state.is(Blocks.WATER) ? '~' : (state.isAir() ? ' ' : '#'));
                }
                map.append('\n');
            }
            BarotraumaMod.LOGGER.info(map.toString());
            BarotraumaMod.LOGGER.info("[smoketest] trunk carved blocks = {}", TrunkNetwork.totalCarved());

            // 体积占比：32×32×32 的方块里有多少是水（衡量隧道密度）
            for (int centerY : new int[]{-300, -600, -900}) {
                int waterCells = 0;
                int totalCells = 0;
                for (int x = 128; x < 160; x++) {
                    for (int z = 128; z < 160; z++) {
                        for (int y = centerY - 16; y < centerY + 16; y++) {
                            totalCells++;
                            if (deep.getBlockState(new BlockPos(x, y, z)).is(Blocks.WATER)) {
                                waterCells++;
                            }
                        }
                    }
                }
                BarotraumaMod.LOGGER.info("[smoketest] volume y={}±16: water {}/{} = {}%",
                        centerY, waterCells, totalCells, Math.round(waterCells * 1000.0 / totalCells) / 10.0);
            }
            BarotraumaMod.LOGGER.info("[smoketest] column(64,64): water={} rock={} air={}", water, rock, air);
            BarotraumaMod.LOGGER.info("[smoketest] min_y={} height={}",
                    deep.getMinBuildHeight(), deep.getHeight());
            shipSelfTest(deep);
            com.opamb.barotrauma.blockentity.ShipCoreBlockEntity starter = StarterSub.ensure(deep);
            if (starter != null && starter.state() != null) {
                BarotraumaMod.LOGGER.info("[smoketest] starter sub -> {}",
                        starter.state().summary().getString().replace('\n', '|'));
            } else {
                BarotraumaMod.LOGGER.warn("[smoketest] starter sub missing at {}", StarterSub.corePos());
            }
            BarotraumaMod.LOGGER.info("[smoketest] dimType: skyLight={} ceiling={} ambient={} minY={} height={}",
                    deep.dimensionType().hasSkyLight(),
                    deep.dimensionType().hasCeiling(),
                    deep.dimensionType().ambientLight(),
                    deep.dimensionType().minY(),
                    deep.dimensionType().height());
            BarotraumaMod.LOGGER.info("[smoketest] biome at outpost = {}",
                    deep.getBiome(new BlockPos(0, ModDimensions.OUTPOST_Y, 0)).unwrapKey()
                            .map(key -> key.location().toString()).orElse("?"));
            BarotraumaMod.LOGGER.info("[smoketest] light at (0,{},0): sky={} block={}", ModDimensions.OUTPOST_Y,
                    deep.getBrightness(LightLayer.SKY, new BlockPos(0, ModDimensions.OUTPOST_Y, 0)),
                    deep.getBrightness(LightLayer.BLOCK, new BlockPos(0, ModDimensions.OUTPOST_Y, 0)));
        }
        if (!marker.delete()) {
            BarotraumaMod.LOGGER.warn("[smoketest] 无法删除标记文件 {}", marker.getAbsolutePath());
        }
        if (smokeTicks <= 0) {
            server.halt(false);
        }
    }

    /** 实时模拟的验证：等 200 tick（10 秒）后打印船状态再关服。 */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        // 物理船：补挂上一 tick 没挂上的推进器（VS2 装配后要过一 tick 才认这艘船）
        if (VsSupport.available()) {
            VsPilot.tick();
        }
        // 隔离场景（.smoketest-vs）：自己管自己的节奏，跑完关服
        if (VsFloodSmoke.active()) {
            net.minecraft.server.level.ServerLevel deep =
                    event.getServer().getLevel(ModDimensions.THE_DEEP);
            if (deep != null && VsFloodSmoke.tick(deep)) {
                event.getServer().halt(false);
            }
            return;
        }
        if (smokeTicks <= 0) {
            return;
        }
        if (smokeTicks == 200) {
            beginVsSmoke(event.getServer());
        }
        smokeTicks--;
        if (smokeTicks == 0) {
            if (smokeCore != null && smokeCore.state() != null) {
                BarotraumaMod.LOGGER.info("[smoketest] live 200 ticks summary -> {}",
                        smokeCore.state().summary().getString().replace('\n', '|'));
                BarotraumaMod.LOGGER.info("[smoketest] live 200 ticks report -> {}",
                        smokeCore.state().liveReport().getString());
            }
            if (VsSupport.available() && VsPilot.hasShip()) {
                net.minecraft.server.level.ServerLevel deep = event.getServer().getLevel(ModDimensions.THE_DEEP);
                if (deep != null) {
                    BarotraumaMod.LOGGER.info("[smoketest] {}", VsPilot.finishSmoke(deep));
                }
            }
            event.getServer().halt(false);
        }
    }

    /** 物理船自检：把船收成 VS2 刚体并给满推力，真正的位移要等物理步跑起来才看得到。 */
    private static void beginVsSmoke(net.minecraft.server.MinecraftServer server) {
        if (!VsSupport.available() || smokeCore == null || smokeCore.state() == null) {
            BarotraumaMod.LOGGER.info("[smoketest] 物理船：跳过（没装 VS2 或没有船核心）");
            return;
        }
        net.minecraft.server.level.ServerLevel deep = server.getLevel(ModDimensions.THE_DEEP);
        if (deep == null) {
            return;
        }
        // 用 collectBlocks（包围盒里所有船方块）而不是 geometry.hull()：声纳台往往挂在船壳外面，
        // 不属于"最大连通分量"，用 hull() 装船会把它留在原地——那就是"船和声纳不是一体的"。
        java.util.Set<BlockPos> blocks = smokeCore.state().collectBlocks(deep);
        BarotraumaMod.LOGGER.info("[smoketest] 装船方块集 {} 格（hull {} 格）",
                blocks.size(), smokeCore.state().geometry().hull().size());
        BarotraumaMod.LOGGER.info("[smoketest] {}", VsPilot.beginSmoke(deep, blocks,
                smokeCore.state(), smokeCore.getBlockPos()));
    }

    /** 兜底：万一维度被写在别处，也可以从主世界传送门坐标判断。 */
    static boolean isDeep(Level level) {
        return level.dimension().equals(ModDimensions.THE_DEEP);
    }
}
