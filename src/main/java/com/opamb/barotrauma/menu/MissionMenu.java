package com.opamb.barotrauma.menu;

import com.opamb.barotrauma.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 任务终端菜单：无物品槽，状态全部走 {@code MissionStatePacket}；
 * {@code stillValid} 只做离终端太远就关的守卫。
 */
public class MissionMenu extends AbstractContainerMenu {

    private final BlockPos terminalPos;

    public MissionMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, buf.readBlockPos());
    }

    public MissionMenu(int id, BlockPos terminalPos) {
        super(ModMenus.MISSION_TERMINAL.get(), id);
        this.terminalPos = terminalPos;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(Vec3.atCenterOf(terminalPos)) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** 终端右键时用的打开器。 */
    public record Provider(BlockPos pos) implements
            net.minecraft.world.MenuProvider {
        @Override
        public Component getDisplayName() {
            return Component.translatable("block.barotrauma.mission_terminal");
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
            return new MissionMenu(id, pos);
        }
    }
}
