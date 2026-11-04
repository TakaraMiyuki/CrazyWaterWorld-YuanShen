package com.opamb.barotrauma.ship;

import com.opamb.barotrauma.BarotraumaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * 海压（P3）：深度超过船的耐压上限后，船壳开始被压伤——走现有
 * {@link HullDamage} → 渗漏 → 破口 → 进水链路，不新开一套伤害模型。
 *
 * <p>耐压上限：基础 150m，船体加固每级 +100m（0~3 级 → 150/250/350/450m）。
 * 玩家出舱的挤压伤害在 {@code UnderwaterSurvival} 里（300m 起）。</p>
 */
public final class ShipPressure {

    public static final int BASE_RATING_METERS = 150;
    public static final int RATING_PER_UPGRADE = 100;

    /** 每 2 秒压伤一次。 */
    private static final int INTERVAL_TICKS = 40;
    /** 每次压伤：随机一格扣 2% 最大血量。 */
    private static final float DAMAGE_FRACTION = 0.02F;

    private static final Random RANDOM = new Random();
    /** 下一 tick 才发包/警告，避免每次都刷。 */
    private static long nextWarnGameTime;

    private ShipPressure() {
    }

    public static int ratingMeters(ShipState state) {
        int level = Math.round((state.hullHealthMultiplier() - 1.0F) / 0.2F);
        return BASE_RATING_METERS + Math.max(0, Math.min(3, level)) * RATING_PER_UPGRADE;
    }

    /**
     * 每个服务端 tick 调一次（调用方自己节流也行）。
     *
     * @param depthMeters 正数米数（深度）
     * @return 本次是否造成了伤害（调用方可据此刷新血条/存档）
     */
    public static boolean tick(ServerLevel level, ShipState state, double depthMeters) {
        if (state == null || depthMeters <= ratingMeters(state)) {
            return false;
        }
        long now = level.getGameTime();
        if (now % INTERVAL_TICKS != 0L) {
            return false;
        }
        List<BlockPos> hull = List.copyOf(state.geometry().hull());
        if (hull.isEmpty()) {
            return false;
        }
        BlockPos pos = hull.get(RANDOM.nextInt(hull.size()));
        BlockState block = null;
        // 方块船直接读世界；物理船的方块在船坞里，用 hullMaxHealth 的类型查不到就走默认装甲板
        try {
            block = level.getBlockState(pos);
        } catch (Throwable ignored) {
            // 船坞未加载：按装甲板算
        }
        float max = 0.0F;
        if (block != null) {
            max = state.hullMaxHealth(block);
        }
        if (max <= 0.0F) {
            max = com.opamb.barotrauma.ship.HullDamage.PLATING_MAX_HEALTH
                    * state.hullHealthMultiplier();
        }
        float before = state.hullDamage().health(pos);
        state.hullDamage().applyDamage(pos, max * DAMAGE_FRACTION, max);
        if (state.hullDamage().health(pos) < before && now > nextWarnGameTime) {
            nextWarnGameTime = now + 60L;
            level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.6F, 0.6F);
            BarotraumaMod.LOGGER.info("[pressure] 深度 {}m 超过耐压上限 {}m，压伤 {}",
                    Math.round(depthMeters), ratingMeters(state), pos.toShortString());
        }
        return true;
    }

    /** 玩家出舱的挤压阈值与速率（UnderwaterSurvival 用）。 */
    public static final int PLAYER_SAFE_DEPTH = 300;
    public static final float PLAYER_DAMAGE_PER_SECOND = 1.0F / 3.0F;

    public static boolean hasPressureSuit(ItemStack stack) {
        return false; // MVP：暂无潜水服，出舱深潜有代价
    }
}
