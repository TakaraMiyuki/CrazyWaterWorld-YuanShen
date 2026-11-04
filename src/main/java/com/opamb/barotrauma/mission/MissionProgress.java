package com.opamb.barotrauma.mission;

import com.opamb.barotrauma.BarotraumaMod;
import com.opamb.barotrauma.data.MissionSavedData;
import com.opamb.barotrauma.registry.ModItems;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 任务进度推进：每秒检查所有在线玩家的进行中任务是否达成（OFFERED/ACTIVE → READY）。
 *
 * <ul>
 *   <li>{@code RECON}：玩家进入目标半径；</li>
 *   <li>{@code SALVAGE}：背包里出现黑匣子；</li>
 *   <li>{@code DELIVERY}：交给 B 端终端时判定（在终端交付逻辑里），这里不推进。</li>
 * </ul>
 *
 * <p>状态变化时给任务主人补发一次状态包（其它同步靠终端打开/操作时推送）。</p>
 */
@Mod.EventBusSubscriber(modid = BarotraumaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MissionProgress {

    private MissionProgress() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20L != 0L) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerLevel deep = server.getLevel(com.opamb.barotrauma.world.ModDimensions.THE_DEEP);
        if (deep == null) {
            return;
        }
        MissionSavedData data = MissionSavedData.get(deep);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Mission mission = data.activeMission(player.getUUID());
            if (mission == null || mission.state() != Mission.State.ACTIVE) {
                continue;
            }
            boolean ready = false;
            switch (mission.type) {
                case RECON -> ready = player.blockPosition().distSqr(mission.target)
                        <= (double) mission.radius * mission.radius;
                case SALVAGE -> ready = player.getInventory()
                        .countItem(ModItems.BLACK_BOX.get().asItem()) > 0;
                case DELIVERY -> { /* 在 B 端终端交付时判定 */ }
            }
            if (ready) {
                data.markReady(mission);
                com.opamb.barotrauma.network.ModNetwork.sendToPlayer(player,
                        com.opamb.barotrauma.network.MissionStatePacket.build(player, deep));
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "任务目标达成！回哨站终端交付（奖励 " + mission.reward + " credits）"), false);
            }
        }
    }
}
