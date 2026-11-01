package com.opamb.barotrauma.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 氧气瓶。
 *
 * <p>右键把玩家氧气值补满（水下屏息时间），并消耗 1 点耐久当作一瓶气。
 * 耐久就是剩余氧气，配合 {@code durability} 属性可直接显示在物品栏里。</p>
 */
public class OxygenTankItem extends Item {

    public OxygenTankItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 氧气本来就是满的，就什么都不做，避免白白耗耐久。
        if (player.getAirSupply() >= player.getMaxAirSupply()) {
            return InteractionResultHolder.pass(stack);
        }

        if (!level.isClientSide) {
            player.setAirSupply(player.getMaxAirSupply());
            stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
        }

        // 1.20.1 没有"气罐放气"音效，用装瓶水的音效调高音调当嘶声。
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.6F, 1.6F);

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.barotrauma.oxygen_tank").withStyle(ChatFormatting.AQUA));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
