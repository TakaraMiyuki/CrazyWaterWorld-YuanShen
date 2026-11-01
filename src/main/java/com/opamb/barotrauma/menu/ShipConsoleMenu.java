package com.opamb.barotrauma.menu;

import com.opamb.barotrauma.blockentity.ShipCoreBlockEntity;
import com.opamb.barotrauma.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;

/**
 * 船控面板的菜单：没有物品槽，只是一个绑定到船核心的界面。
 */
public class ShipConsoleMenu extends AbstractContainerMenu {

    private final BlockPos corePos;
    private final String statusText;

    public ShipConsoleMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, buf.readBlockPos(), buf.readUtf(1024));
    }

    public ShipConsoleMenu(int id, Inventory inventory, BlockPos corePos, String statusText) {
        super(ModMenus.SHIP_CONSOLE.get(), id);
        this.corePos = corePos;
        this.statusText = statusText;
    }

    public BlockPos corePos() {
        return corePos;
    }

    public String statusText() {
        return statusText;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(corePos) instanceof ShipCoreBlockEntity;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
