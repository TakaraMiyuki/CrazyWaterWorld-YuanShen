package com.opamb.barotrauma.event;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.world.OceanEnvironment;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 空气海域的呼吸规则：水下消耗氧气，回到哨站/未进水船舱后恢复。
 *
 * <p>因为海里没有真实水，原版不会自动扣氧气；这里补上同一套节奏
 * （约 15 秒耗尽，之后每约 1 秒掉 2 点伤害）。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class UnderwaterSurvival {

    private UnderwaterSurvival() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide()) {
            return;
        }
        if (player.isCreative() || player.isSpectator()
                || player.hasEffect(MobEffects.WATER_BREATHING)) {
            refill(player);
            return;
        }
        boolean blockShipDry = OceanEnvironment.isInsideBlockShipDryZone(
                player.level(), player.getEyePosition());
        if (!blockShipDry && OceanEnvironment.isSubmerged(player.level(), player.getEyePosition())) {
            // 空气海域没有真实水，玩家落底时不能吃摔落伤害；持续清零坠落距离。
            player.resetFallDistance();
            player.setAirSupply(player.getAirSupply() - 1);
            if (player.getAirSupply() <= -20) {
                player.setAirSupply(0);
                player.hurt(player.damageSources().drown(), 2.0F);
            }
        } else {
            refill(player);
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof Player player
                && OceanEnvironment.isSubmerged(player.level(), player.position())) {
            event.setCanceled(true);
        }
    }

    private static void refill(Player player) {
        if (player.getAirSupply() < player.getMaxAirSupply()) {
            player.setAirSupply(Math.min(player.getMaxAirSupply(), player.getAirSupply() + 4));
        }
    }
}
