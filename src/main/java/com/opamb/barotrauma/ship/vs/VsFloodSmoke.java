package com.opamb.barotrauma.ship.vs;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.ShipState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 隔离自检：证明（或推翻）"VS2 装配把海里那块体积灌成水"这个前提。
 *
 * <p>为什么要单独跑：总自检前面的开门/破口/move 场景会把船和环境弄脏，
 * 之前量到的"装配后 100% 是水"没法归因到装配本身（见 STATUS.md）。
 * 这个场景只做一件事，用 {@code run/.smoketest-vs} 标记文件触发，跑完自己关服。</p>
 *
 * <h2>两个阶段</h2>
 * <ol>
 *   <li><b>阶段 1（原始装配）</b>：5×5×5 透明船泡在开放水里 → 量一次 → 直接调
 *       {@link VsBridge#assemble}（<b>不装排水体、不做对账</b>）→ 等 40 tick 再量。
 *       第二次有水 ⇒ 排水体的前提成立；没水 ⇒ 排水体多余，进水另有原因。</li>
 *   <li><b>阶段 2（正式管线）</b>：同一位置再造一艘 → {@link VsPilot#launch}
 *       （装配对账 + 排水体）→ 等 40 tick 再量 → {@link VsPilot#dock} 拆回再量。
 *       顺便第一次真实验证装配对账和拆船。</li>
 * </ol>
 *
 * <p>所有测量都数 5×5×5 船位方块：水 / 空气 / 排水体 / 其它。</p>
 */
public final class VsFloodSmoke {

    private static final int HULL_SIZE = 5;
    /** 装配后等多久（tick）。40 tick = 2 秒，足够海水把空腔灌满。 */
    private static final int WAIT_TICKS = 40;
    private static final String TAG = "[smoketest-vs]";

    /** 0=没在跑；1=阶段1（原始装配）；2=阶段2（正式管线）；3=结束待关服。 */
    private static int phase;
    private static int countdown;
    private static ServerLevel level;
    /** 船体包围盒最小角。 */
    private static BlockPos origin;
    /** 阶段 1 装配前的方块快照（用来铺回）。 */
    private static Map<BlockPos, BlockState> snapshot = Map.of();
    private static ServerShip rawShip;

    private static int[] rawBefore;
    private static int[] rawAfterImmediate;
    private static int[] rawAfterWait;
    private static int[] rawRestored;
    private static int[] plugAfterWait;
    private static int[] docked;
    /** 阶段2 密闭时舱室内部的水方块数（应为 0）。 */
    private static int interiorSealed;
    /** 阶段2 破口位置（装配原坐标）。 */
    private static BlockPos breachPos;
    /** 破口后 40 tick 的模拟水量（单位）与连海空腔数。 */
    private static double pocketAfterWait;
    private static int openPocketsAfterWait;

    private VsFloodSmoke() {
    }

    public static boolean active() {
        return phase != 0;
    }

    /**
     * 启动隔离场景。返回 false 表示连场景都没法搭（找不到干净水域），调用方应直接关服。
     */
    public static boolean begin(ServerLevel deep) {
        level = deep;
        BlockPos center = findWaterSpot(deep);
        if (center == null) {
            BarotraumaMod.LOGGER.warn("{} 找不到 9×9×9 全是水的干净位置，场景终止", TAG);
            return false;
        }
        origin = center.offset(-HULL_SIZE / 2, -HULL_SIZE / 2, -HULL_SIZE / 2);
        forceChunks(deep, origin);
        buildHull(deep);
        BarotraumaMod.LOGGER.info("{} 船位 {}（中心 {}），周围 9³ 全是水", TAG, origin, center);
        ShipState state = scan(deep);
        if (state != null) {
            BarotraumaMod.LOGGER.info("{} 扫描：{}", TAG,
                    state.summary().getString().replace('\n', '|'));
        }

        // 快照必须在装配前取：装配会把方块从世界里搬走
        snapshot = capture(deep);
        rawBefore = countBox(deep);
        logMeasure("阶段1 装配前", rawBefore);

        try {
            rawShip = VsBridge.assemble(deep, snapshot.keySet());
            if (rawShip != null) {
                BarotraumaMod.LOGGER.info("{} 原始装配（不装排水体）：{} 格，质量 {}，{}", TAG,
                        snapshot.size(), Math.round(VsBridge.mass(rawShip)), VsBridge.claim(rawShip));
            } else {
                BarotraumaMod.LOGGER.warn("{} 原始装配返回 null", TAG);
            }
        } catch (Throwable error) {
            BarotraumaMod.LOGGER.warn("{} 原始装配异常：{}", TAG, error.toString());
            rawShip = null;
            VsPilot.restoreBlocks(deep, snapshot, 0, 0, 0);
        }
        rawAfterImmediate = countBox(deep);
        logMeasure("阶段1 装配后立即", rawAfterImmediate);

        phase = 1;
        countdown = WAIT_TICKS;
        return true;
    }

    /** 每 tick 调一次。返回 true 表示全部跑完，调用方应关服。 */
    public static boolean tick(ServerLevel deep) {
        if (phase == 0) {
            return true;
        }
        if (phase == 3) {
            reset();
            return true;
        }
        if (--countdown > 0) {
            // 阶段 2 中途探针：船有没有动、排水体跟没跟过去
            if (phase == 2 && countdown == WAIT_TICKS / 2) {
                BarotraumaMod.LOGGER.info("{} 阶段2 中途：船位置={} 速度={} 偏移={},{},{} 排水体={}",
                        TAG, VsPilot.shipPosition(), VsPilot.shipVelocity(),
                        VsState.offsetX(), VsState.offsetY(), VsState.offsetZ(), HullPlug.status());
                logMeasure("阶段2 中途", countBox(deep));
            }
            return false;
        }
        if (phase == 1) {
            afterRaw(deep);
            return false;
        }
        if (phase == 4) {
            afterDamage(deep);
            return true;
        }
        afterPlug(deep);
        return false;
    }

    private static void afterRaw(ServerLevel deep) {
        rawAfterWait = countBox(deep);
        logMeasure("阶段1 装配后 40 tick", rawAfterWait);
        BarotraumaMod.LOGGER.info("{} 判定：{}", TAG, rawAfterWait[0] > 0
                ? "原始装配确实把船位灌成水（" + rawAfterWait[0] + " 格）→ 排水体的前提成立"
                : "原始装配 40 tick 后船位没有水 → 排水体多余，进水另有原因");

        if (rawShip != null) {
            try {
                VsBridge.discard(deep, rawShip);
            } catch (Throwable error) {
                BarotraumaMod.LOGGER.warn("{} 删原始船异常：{}", TAG, error.toString());
            }
            rawShip = null;
        }
        int back = VsPilot.restoreBlocks(deep, snapshot, 0, 0, 0);
        BarotraumaMod.LOGGER.info("{} 阶段1 按快照铺回 {} 格", TAG, back);
        rawRestored = countBox(deep);
        logMeasure("阶段1 拆回后", rawRestored);

        // 阶段 2：正式管线（对账 + 排水体）
        buildHull(deep);
        ShipState state = scan(deep);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        if (state != null) {
            for (BlockPos pos : state.collectBlocks(deep)) {
                blocks.put(pos, deep.getBlockState(pos));
            }
        } else {
            blocks.putAll(capture(deep));
        }
        String result = VsPilot.launch(deep, blocks.keySet(), state, origin.offset(2, 1, 2));
        BarotraumaMod.LOGGER.info("{} 阶段2 正式管线装配：{}", TAG, result);
        logMeasure("阶段2 装配后立即", countBox(deep));
        BarotraumaMod.LOGGER.info("{} 阶段2 排水体：{}", TAG, HullPlug.status());
        if (VsPilot.hasShip()) {
            phase = 2;
            countdown = WAIT_TICKS;
        } else {
            BarotraumaMod.LOGGER.warn("{} 阶段2 装配失败，场景结束", TAG);
            phase = 3;
        }
    }

    private static void afterPlug(ServerLevel deep) {
        plugAfterWait = countBox(deep);
        logMeasure("阶段2 装配后 40 tick", plugAfterWait);
        interiorSealed = countInteriorWater(deep);
        if (interiorSealed == 0) {
            BarotraumaMod.LOGGER.info("{} 判定：舱内水=0（外层船壳格水={} 是正常的，船壳格不再铺排水体）"
                    + " → 舱内流体拦截生效", TAG, plugAfterWait[0]);
        } else {
            BarotraumaMod.LOGGER.warn("{} 判定：舱内仍有 {} 格水（流体拦截失效）", TAG, interiorSealed);
        }

        // 破口进水链路：直接在船坞里拆一格船壳，等价于物理船上砸洞。
        // 走的是和 ShipBreakMixin 相同的一条路（删船坞方块 → 重扫 → 连海空腔 → tickSeaFlood）。
        breachPos = origin.offset(0, 2, 2);
        boolean damaged = VsPilot.damageForTest(deep, breachPos);
        BarotraumaMod.LOGGER.info("{} 阶段2 破口：位置 {} → {}", TAG, breachPos,
                damaged ? "已拆一格" : "失败（船坞偏移未就绪/方块不在快照里）");
        if (damaged) {
            phase = 4;
            countdown = WAIT_TICKS;
        } else {
            finishFormal(deep);
            phase = 3;
        }
    }

    private static void afterDamage(ServerLevel deep) {
        ShipState state = VsPilot.state();
        if (state != null) {
            pocketAfterWait = state.simulation().pocketWaterTotal();
            openPocketsAfterWait = state.geometry().openPockets().size();
            BarotraumaMod.LOGGER.info("{} 阶段2 破口 40 tick 后：连海空腔 {} 处，模拟进水 {} 单位，进水比例 {}%",
                    TAG, openPocketsAfterWait, Math.round(pocketAfterWait),
                    Math.round(state.floodRatio() * 100.0D));
            BarotraumaMod.LOGGER.info("{} 判定：{}", TAG, pocketAfterWait > 0.0D
                    ? "破口后模拟水开始上涨 ✅"
                    : "破口后模拟水仍为 0（破口没被识别成连海）");
            BarotraumaMod.LOGGER.info("{} 舱内实际水方块（应为 0，水面是客户端自绘）：{}",
                    TAG, countInteriorWater(deep));
        } else {
            BarotraumaMod.LOGGER.warn("{} 阶段2 破口后没有船状态", TAG);
        }
        finishFormal(deep);
        phase = 3;
    }

    private static void finishFormal(ServerLevel deep) {
        String dockResult = VsPilot.dock(deep);
        BarotraumaMod.LOGGER.info("{} 阶段2 拆船：{}", TAG, dockResult);
        docked = countBox(deep);
        logMeasure("阶段2 拆回后（按原位量，船若漂移过以拆船报告位移为准）", docked);

        BarotraumaMod.LOGGER.info("{} 汇总｜原始装配后水={}｜正式管线后外层水={} 舱内水={} 排水体={}"
                        + "｜破口后模拟水={} 连海={}｜原始拆回后水={}｜正式拆回后水={}",
                TAG, rawAfterWait == null ? -1 : rawAfterWait[0],
                plugAfterWait[0], interiorSealed, plugAfterWait[2],
                Math.round(pocketAfterWait), openPocketsAfterWait,
                rawRestored == null ? -1 : rawRestored[0],
                docked[0]);
    }

    /** 数 5×5×5 内部（3×3×3）的水方块。外层船壳格允许有海水。 */
    private static int countInteriorWater(ServerLevel deep) {
        int water = 0;
        for (int dx = 1; dx < HULL_SIZE - 1; dx++) {
            for (int dy = 1; dy < HULL_SIZE - 1; dy++) {
                for (int dz = 1; dz < HULL_SIZE - 1; dz++) {
                    if (deep.getBlockState(origin.offset(dx, dy, dz)).getFluidState().is(FluidTags.WATER)) {
                        water++;
                    }
                }
            }
        }
        return water;
    }

    // ------------------------------------------------------------------ 场地与测量

    /** 找一片 9×9×9（留 2 格边距）全是水的干净位置。 */
    private static BlockPos findWaterSpot(ServerLevel deep) {
        int[] anchors = {400, 384, 416, 368, 432, 352, 448};
        for (int cx : anchors) {
            for (int cz : anchors) {
                for (int cy = -60; cy >= -140; cy -= 4) {
                    if (isAllWater(deep, cx, cy, cz, 4)) {
                        return new BlockPos(cx, cy, cz);
                    }
                }
            }
        }
        return null;
    }

    private static boolean isAllWater(ServerLevel deep, int cx, int cy, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!deep.getBlockState(new BlockPos(cx + dx, cy + dy, cz + dz))
                            .getFluidState().is(FluidTags.WATER)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 强制加载场景周边区块：没人时区块不 tick，水就不会流、船也不会动。 */
    private static void forceChunks(ServerLevel deep, BlockPos min) {
        int minCx = (min.getX() >> 4) - 1;
        int maxCx = ((min.getX() + HULL_SIZE) >> 4) + 1;
        int minCz = (min.getZ() >> 4) - 1;
        int maxCz = ((min.getZ() + HULL_SIZE) >> 4) + 1;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                deep.setChunkForced(cx, cz, true);
            }
        }
    }

    /** 5×5×5 透明船壳 + 内部空气 + 中央声纳台（船核心）。 */
    private static void buildHull(ServerLevel deep) {
        BlockState hull = ModShipBlocks.TEST_HULL.get().defaultBlockState();
        for (int dx = 0; dx < HULL_SIZE; dx++) {
            for (int dy = 0; dy < HULL_SIZE; dy++) {
                for (int dz = 0; dz < HULL_SIZE; dz++) {
                    boolean shell = dx == 0 || dx == HULL_SIZE - 1
                            || dy == 0 || dy == HULL_SIZE - 1
                            || dz == 0 || dz == HULL_SIZE - 1;
                    deep.setBlock(origin.offset(dx, dy, dz),
                            shell ? hull : Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        deep.setBlock(origin.offset(2, 1, 2),
                ModShipBlocks.SONAR.get().defaultBlockState(), 3);
    }

    private static ShipState scan(ServerLevel deep) {
        BlockEntity be = deep.getBlockEntity(origin.offset(2, 1, 2));
        if (be instanceof ShipCoreBlockEntity core) {
            return core.rescan(deep);
        }
        return null;
    }

    /** 船位里所有非空气方块（装配输入）。 */
    private static Map<BlockPos, BlockState> capture(ServerLevel deep) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (int dx = 0; dx < HULL_SIZE; dx++) {
            for (int dy = 0; dy < HULL_SIZE; dy++) {
                for (int dz = 0; dz < HULL_SIZE; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = deep.getBlockState(pos);
                    if (!state.isAir()) {
                        out.put(pos, state);
                    }
                }
            }
        }
        return out;
    }

    /** 数船位 5×5×5：{水, 空气, 排水体, 其它}。 */
    private static int[] countBox(ServerLevel deep) {
        int water = 0;
        int air = 0;
        int plug = 0;
        int other = 0;
        for (int dx = 0; dx < HULL_SIZE; dx++) {
            for (int dy = 0; dy < HULL_SIZE; dy++) {
                for (int dz = 0; dz < HULL_SIZE; dz++) {
                    BlockState state = deep.getBlockState(origin.offset(dx, dy, dz));
                    if (state.getFluidState().is(FluidTags.WATER)) {
                        water++;
                    } else if (state.isAir()) {
                        air++;
                    } else if (state.is(ModShipBlocks.HULL_PLUG.get())) {
                        plug++;
                    } else {
                        other++;
                    }
                }
            }
        }
        return new int[]{water, air, plug, other};
    }

    private static void logMeasure(String label, int[] m) {
        BarotraumaMod.LOGGER.info("{} {}：水={} 空气={} 排水体={} 别的={}（共 {} 格）", TAG, label,
                m[0], m[1], m[2], m[3], m[0] + m[1] + m[2] + m[3]);
    }

    private static void reset() {
        phase = 0;
        countdown = 0;
        level = null;
        origin = null;
        snapshot = Map.of();
        rawShip = null;
        rawBefore = null;
        rawAfterImmediate = null;
        rawAfterWait = null;
        rawRestored = null;
        plugAfterWait = null;
        docked = null;
        interiorSealed = 0;
        breachPos = null;
        pocketAfterWait = 0.0D;
        openPocketsAfterWait = 0;
    }
}
