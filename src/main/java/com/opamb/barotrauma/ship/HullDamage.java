package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 逐方块船壳耐久。
 *
 * <p>按《潜渊症》的结构模型：每个结构段有独立血量，打空才破口。原版基础值
 * （本机 1.20.1 游戏数据核对）：外壳 {@code Shell}=200、舷窗 {@code Window}=100、
 * 船头=400、内墙 55/75；T1/T2/T3 本身不加单块血量，tier 只限制“船体加固”
 * 升级的等级（每级 +20% 最大血量）。所以这里存 0..1 的**剩余比例**，
 * 具体上限由 {@link com.opamb.barotrauma.registry.ModShipBlocks#maxHealth} +
 * 船的 {@code hullHealthMultiplier} 决定，血条直接显示这个比例。</p>
 */
public final class HullDamage {

    /** 装甲板：原版外壳 200。 */
    public static final float PLATING_MAX_HEALTH = 200.0F;
    /** 舷窗：原版窗户 100。 */
    public static final float GLASS_MAX_HEALTH = 100.0F;
    /** 测试船壳（调试用）：默认按外壳 200。 */
    public static final float TEST_HULL_MAX_HEALTH = 200.0F;

    private static final float EPSILON = 1.0E-4F;

    /** 剩余生命比例；没有记录 = 满血。 */
    private final Map<BlockPos, Float> health = new HashMap<>();

    /** 剩余比例（0..1）。 */
    public float health(BlockPos pos) {
        Float value = health.get(pos);
        return value == null ? 1.0F : value;
    }

    /** 已损失比例（0..1）。 */
    public float damage(BlockPos pos) {
        return 1.0F - health(pos);
    }

    public boolean isDamaged(BlockPos pos) {
        return health(pos) < 1.0F - EPSILON;
    }

    public boolean isEmpty() {
        return health.isEmpty();
    }

    /**
     * 扣血，返回扣完后的剩余比例；返回 0 表示这一下打爆了。
     *
     * @param amount    伤害值（和 {@code maxHealth} 同一单位）
     * @param maxHealth 该方块的最大血量（已乘船的加固倍率）
     */
    public float applyDamage(BlockPos pos, float amount, float maxHealth) {
        if (pos == null || amount <= 0.0F || maxHealth <= 0.0F
                || !Float.isFinite(amount) || !Float.isFinite(maxHealth)) {
            return health(pos);
        }
        float next = health(pos) - amount / maxHealth;
        if (!Float.isFinite(next)) {
            // NaN/Infinity 一旦进表，客户端三个区间比较全落空，会画出"黑条"。
            return health(pos);
        }
        if (next <= EPSILON) {
            // 0% 不删记录：方块会切成 breached 状态继续存在，耐久条和焊接都需要它。
            health.put(pos.immutable(), 0.0F);
            return 0.0F;
        }
        if (next >= 1.0F - EPSILON) {
            health.remove(pos);
            return 1.0F;
        }
        health.put(pos.immutable(), next);
        return next;
    }

    /**
     * 修复，返回修完后的剩余比例；返回 1 表示修满（记录已清除）。
     */
    public float repair(BlockPos pos, float amount, float maxHealth) {
        if (pos == null || amount <= 0.0F || maxHealth <= 0.0F || !isDamaged(pos)) {
            return health(pos);
        }
        float next = health(pos) + amount / maxHealth;
        if (next >= 1.0F - EPSILON) {
            health.remove(pos);
            return 1.0F;
        }
        health.put(pos.immutable(), next);
        return next;
    }

    /** 直接写入剩余比例（迁移/自检用）。0..1 之外会被夹紧；非有限值直接丢弃。 */
    public void setHealth(BlockPos pos, float value) {
        if (pos == null || !Float.isFinite(value)) {
            return;
        }
        float clamped = Math.max(0.0F, Math.min(1.0F, value));
        if (clamped >= 1.0F - EPSILON) {
            health.remove(pos);
        } else {
            health.put(pos.immutable(), clamped);
        }
    }

    /** 方块没了：清掉它的记录，避免客户端在空气位置画血条。 */
    public void clear(BlockPos pos) {
        health.remove(pos);
    }

    /** 整船平移（方块船推进时坐标会变）。 */
    public void shift(int dx, int dy, int dz) {
        if (dx == 0 && dy == 0 && dz == 0 || health.isEmpty()) {
            return;
        }
        Map<BlockPos, Float> moved = new HashMap<>(health.size() * 2);
        for (Map.Entry<BlockPos, Float> entry : health.entrySet()) {
            moved.put(entry.getKey().offset(dx, dy, dz), entry.getValue());
        }
        health.clear();
        health.putAll(moved);
    }

    /** 只保留仍然存在的船体方块。 */
    public void retain(Predicate<BlockPos> keep) {
        health.keySet().removeIf(pos -> !keep.test(pos));
    }

    public void copyFrom(HullDamage other) {
        if (other == null) {
            return;
        }
        health.clear();
        health.putAll(other.health);
    }

    /** 按坐标排序的快照：发包和测试都要求顺序稳定。 */
    public List<Map.Entry<BlockPos, Float>> sortedEntries() {
        List<Map.Entry<BlockPos, Float>> out = new ArrayList<>(health.entrySet());
        out.sort((a, b) -> {
            BlockPos pa = a.getKey();
            BlockPos pb = b.getKey();
            if (pa.getX() != pb.getX()) {
                return Integer.compare(pa.getX(), pb.getX());
            }
            if (pa.getY() != pb.getY()) {
                return Integer.compare(pa.getY(), pb.getY());
            }
            return Integer.compare(pa.getZ(), pb.getZ());
        });
        return out;
    }

    public Map<BlockPos, Float> entries() {
        return Collections.unmodifiableMap(health);
    }

    /**
     * 玩家空手/拿工具砸船壳的伤害。参考原版 {@code structuredamage}：
     * 徒手 1，潜水刀 5，镐/斧 4，其它 2；焊接工具不造成结构伤害（它是修结构的）。
     */
    public static float meleeDamage(ItemStack stack, boolean emptyHand) {
        if (emptyHand || stack.isEmpty()) {
            return 1.0F;
        }
        if (stack.getItem() instanceof com.opamb.barotrauma.item.WeldingToolItem) {
            return 0.0F;
        }
        if (stack.getItem() instanceof com.opamb.barotrauma.item.DivingKnifeItem) {
            return 5.0F;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.PickaxeItem
                || stack.getItem() instanceof net.minecraft.world.item.AxeItem) {
            return 4.0F;
        }
        return 2.0F;
    }
}
