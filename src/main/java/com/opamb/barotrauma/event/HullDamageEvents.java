package com.opamb.barotrauma.event;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModShipBlocks;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.vs.VsState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 爆炸对船壳的伤害。
 *
 * <p>原版爆炸会直接把方块炸没；这里把船体格子从破坏列表里拿掉，改成先扣耐久，
 * 只有耐久到 0 才真的破口。物理船的方块在世界里是空气，原版列表根本碰不到它们，
 * 所以走 {@link VsState#damageExplosion} 按装配快照单独结算。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HullDamageEvents {

    /** TNT（radius=4）中心约 140 结构伤害：两发打穿 200 血外壳。 */
    private static final float BASE_DAMAGE = 60.0F;
    private static final float DAMAGE_PER_RADIUS = 20.0F;
    private static final float MAX_DAMAGE = 240.0F;

    private HullDamageEvents() {
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = event.getExplosion().getPosition();
        // 1.20.1 的 Explosion.radius 是 private，也没有 getter；用爆炸实际影响范围
        // 反推一个有效半径（TNT ≈ 4~4.5 格）。开阔水域没有方块可炸时退回 4 格。
        double radius = 0.0D;
        for (BlockPos pos : event.getAffectedBlocks()) {
            radius = Math.max(radius,
                    Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(center)) + 0.5D);
        }
        if (radius < 2.0D) {
            radius = 4.0D;
        }
        float base = (float) Math.min(MAX_DAMAGE, BASE_DAMAGE + DAMAGE_PER_RADIUS * radius);

        if (VsState.active()) {
            // 物理船：世界方块照常炸，船壳单独按快照结算。
            VsState.damageExplosion(level, center, radius + 0.75D, base);
            return;
        }

        BlockPos centerPos = BlockPos.containing(center);
        ShipCoreBlockEntity core = ShipCores.nearestCore(level, centerPos,
                Math.max(32.0D, radius + 32.0D));
        if (core == null || core.state() == null) {
            return;
        }
        List<BlockPos> hullHits = new ArrayList<>();
        Iterator<BlockPos> iterator = event.getAffectedBlocks().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (level.getBlockState(pos).is(ModShipBlocks.SONAR.get())
                    && core.state().tracksHullBlock(pos)) {
                // 声纳是船脑，爆炸也不能炸掉。
                iterator.remove();
                continue;
            }
            if (!ModShipBlocks.isRepairableHull(level.getBlockState(pos))) {
                continue;
            }
            if (!core.state().tracksHullBlock(pos)) {
                continue;
            }
            double distance = Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(center));
            if (distance > radius + 0.75D) {
                continue;
            }
            // 从原版破坏列表移除：爆炸不再秒掉这格，改由耐久系统决定。
            iterator.remove();
            hullHits.add(pos);
        }
        if (!hullHits.isEmpty()) {
            core.applyExplosion(level, hullHits, center, radius + 0.75D, base);
        }
    }
}
