package com.opamb.barotrauma.network;

import com.opamb.barotrauma.data.MissionSavedData;
import com.opamb.barotrauma.mission.Mission;
import com.opamb.barotrauma.mission.MissionType;
import com.opamb.barotrauma.registry.ModItems;
import com.opamb.barotrauma.ship.ShipCores;
import com.opamb.barotrauma.ship.ShipState;
import com.opamb.barotrauma.world.ModDimensions;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：任务面板上的操作。
 *
 * <ul>
 *   <li>{@code ACCEPT}——接取任务（DELIVERY 同时发一批补给箱）；</li>
 *   <li>{@code ABANDON}——放弃；</li>
 *   <li>{@code TURN_IN}——交付（READY 才允许，按类型扣货物）；</li>
 *   <li>{@code BUY_*}——商店：修船 / 氧气瓶 / 焊枪 / 船体加固。</li>
 * </ul>
 *
 * <p>全部服务端权威：接取竞争、余额、货物、距离都在这里校验，
 * 处理完给操作者回发一包 {@link MissionStatePacket} 刷新界面。</p>
 */
public record MissionActionPacket(int action, int missionId) {

    public static final int ACCEPT = 1;
    public static final int ABANDON = 2;
    public static final int TURN_IN = 3;
    public static final int BUY_REPAIR = 4;
    public static final int BUY_OXYGEN = 5;
    public static final int BUY_WELDER = 6;
    public static final int BUY_UPGRADE = 7;

    private static final int COST_OXYGEN = 40;
    private static final int COST_WELDER = 30;
    private static final int COST_REPAIR_PER_HP = 1;
    private static final int COST_UPGRADE_BASE = 150;

    public MissionActionPacket(int action) {
        this(action, -1);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(action);
        buf.writeVarInt(missionId);
    }

    public static MissionActionPacket decode(FriendlyByteBuf buf) {
        return new MissionActionPacket(buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            if (!level.dimension().equals(ModDimensions.THE_DEEP)) {
                return;
            }
            MissionSavedData data = MissionSavedData.get(level);
            switch (action) {
                case ACCEPT -> handleAccept(player, level, data, missionId);
                case ABANDON -> {
                    data.abandon(player.getUUID());
                    sendState(player, level);
                }
                case TURN_IN -> handleTurnIn(player, level, data);
                case BUY_REPAIR -> handleRepair(player, level, data);
                case BUY_OXYGEN -> handleBuyItem(player, level, data, COST_OXYGEN,
                        new ItemStack(ModItems.OXYGEN_TANK.get()));
                case BUY_WELDER -> handleBuyItem(player, level, data, COST_WELDER,
                        new ItemStack(ModItems.WELDING_TOOL.get()));
                case BUY_UPGRADE -> handleUpgrade(player, level, data);
                default -> { /* 未知操作忽略 */ }
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleAccept(ServerPlayer player, ServerLevel level, MissionSavedData data, int missionId) {
        Mission mission = data.missions().get(missionId);
        if (mission == null) {
            return;
        }
        if (data.accept(player.getUUID(), missionId, level.getGameTime())) {
            player.sendSystemMessage(Component.literal("已接取任务：" + describe(mission))
                    .withStyle(ChatFormatting.GOLD));
            if (mission.type == MissionType.DELIVERY) {
                player.getInventory().add(new ItemStack(ModItems.SUPPLY_CRATE.get()));
                player.sendSystemMessage(Component.literal("补给箱已放进背包，送到 B 中继站的终端交付")
                        .withStyle(ChatFormatting.AQUA));
            }
        } else {
            player.sendSystemMessage(Component.literal("接取失败：已有进行中的任务，或这条刚被别人接走")
                    .withStyle(ChatFormatting.RED));
        }
        sendState(player, level);
    }

    private static void handleTurnIn(ServerPlayer player, ServerLevel level, MissionSavedData data) {
        Mission mission = data.activeMission(player.getUUID());
        if (mission == null) {
            return;
        }
        if (mission.state() != Mission.State.READY) {
            player.sendSystemMessage(Component.literal("任务还没达成，先去目标点（HUD 上有方向）")
                    .withStyle(ChatFormatting.RED));
            sendState(player, level);
            return;
        }
        boolean consumed = true;
        switch (mission.type) {
            case SALVAGE -> consumed = player.getInventory()
                    .clearOrCountMatchingItems(stack -> stack.is(ModItems.BLACK_BOX.get().asItem()),
                            1, player.getInventory()) > 0;
            case DELIVERY -> consumed = player.getInventory()
                    .clearOrCountMatchingItems(stack -> stack.is(ModItems.SUPPLY_CRATE.get().asItem()),
                            1, player.getInventory()) > 0;
            default -> { /* RECON 无货物 */ }
        }
        if (!consumed) {
            player.sendSystemMessage(Component.literal("找不到要交付的货物（黑匣子/补给箱）")
                    .withStyle(ChatFormatting.RED));
            sendState(player, level);
            return;
        }
        data.finish(player.getUUID(), mission);
        player.sendSystemMessage(Component.literal("任务完成！+" + mission.reward + " credits")
                .withStyle(ChatFormatting.GREEN));
        sendState(player, level);
    }

    private static void handleRepair(ServerPlayer player, ServerLevel level, MissionSavedData data) {
        var core = ShipCores.nearestCore(level, player.blockPosition(), 24.0D);
        if (core == null || core.state() == null) {
            player.sendSystemMessage(Component.literal("附近 24 格内没有船（把船停在泊位再修）")
                    .withStyle(ChatFormatting.RED));
            sendState(player, level);
            return;
        }
        ShipState state = core.state();
        int missing = 0;
        for (var entry : state.hullDamage().sortedEntries()) {
            missing += Math.round((1.0F - entry.getValue()) * 200.0F);
        }
        if (missing <= 0) {
            player.sendSystemMessage(Component.literal("船体完好，不用修").withStyle(ChatFormatting.GRAY));
            sendState(player, level);
            return;
        }
        int cost = missing * COST_REPAIR_PER_HP;
        if (!data.spendCredits(player.getUUID(), cost)) {
            player.sendSystemMessage(Component.literal(
                    "修船需要 " + cost + " credits，余额不足（当前 " + data.credits(player.getUUID()) + "）")
                    .withStyle(ChatFormatting.RED));
            sendState(player, level);
            return;
        }
        for (BlockPos pos : state.hullDamage().entries().keySet().toArray(new BlockPos[0])) {
            state.hullDamage().setHealth(pos, 1.0F);
            state.removeBreach(pos);
        }
        core.setChanged();
        ModNetwork.sendToAll(com.opamb.barotrauma.network.ShipHullDamagePacket.build(
                state, -1L, false, 0.0F, 0.0F, 0.0F));
        player.sendSystemMessage(Component.literal(
                "船体已修复（消耗 " + cost + " credits）").withStyle(ChatFormatting.GREEN));
        sendState(player, level);
    }

    private static void handleBuyItem(ServerPlayer player, ServerLevel level, MissionSavedData data,
                                      int cost, ItemStack stack) {
        if (!data.spendCredits(player.getUUID(), cost)) {
            player.sendSystemMessage(Component.literal(
                    "需要 " + cost + " credits，余额不足（当前 " + data.credits(player.getUUID()) + "）")
                    .withStyle(ChatFormatting.RED));
            sendState(player, level);
            return;
        }
        player.getInventory().add(stack);
        player.sendSystemMessage(Component.literal(
                "已购买 " + stack.getHoverName().getString() + "（-" + cost + " credits）")
                .withStyle(ChatFormatting.GREEN));
        sendState(player, level);
    }

    private static void handleUpgrade(ServerPlayer player, ServerLevel level, MissionSavedData data) {
        var core = ShipCores.nearestCore(level, player.blockPosition(), 24.0D);
        if (core == null || core.state() == null) {
            player.sendSystemMessage(Component.literal("附近 24 格内没有船（升级装在船上）")
                    .withStyle(ChatFormatting.RED));
            sendState(player, level);
            return;
        }
        ShipState state = core.state();
        int level1 = Math.round((state.hullHealthMultiplier() - 1.0F) / 0.2F);
        if (level1 >= 3) {
            player.sendSystemMessage(Component.literal("船体加固已满级（+60%）")
                    .withStyle(ChatFormatting.GRAY));
            sendState(player, level);
            return;
        }
        int cost = COST_UPGRADE_BASE * (level1 + 1);
        if (!data.spendCredits(player.getUUID(), cost)) {
            player.sendSystemMessage(Component.literal(
                    "升级需要 " + cost + " credits，余额不足（当前 " + data.credits(player.getUUID()) + "）")
                    .withStyle(ChatFormatting.RED));
            sendState(player, level);
            return;
        }
        state.setHullHealthMultiplier(state.hullHealthMultiplier() + 0.2F);
        core.setChanged();
        player.sendSystemMessage(Component.literal(String.format(
                "船体加固 +20%%（当前 ×%.1f，消耗 %d credits）",
                state.hullHealthMultiplier(), cost)).withStyle(ChatFormatting.GREEN));
        sendState(player, level);
    }

    private static String describe(Mission mission) {
        return switch (mission.type) {
            case SALVAGE -> "打捞（" + mission.target.getX() + ", " + mission.target.getY()
                    + ", " + mission.target.getZ() + " 附近的沉船）";
            case RECON -> "侦察（抵达 " + mission.target.getX() + ", " + mission.target.getY()
                    + ", " + mission.target.getZ() + " 半径 " + mission.radius + "m）";
            case DELIVERY -> "运输（把补给箱送到 B 中继站）";
        };
    }

    private static void sendState(ServerPlayer player, ServerLevel level) {
        ModNetwork.sendToPlayer(player, MissionStatePacket.build(player, level));
    }
}
