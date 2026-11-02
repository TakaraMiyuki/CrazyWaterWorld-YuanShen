package com.opamb.barotrauma.menu;

import com.opamb.barotrauma.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 声纳 / 舵台的菜单：没有物品槽，只是把"声纳台在哪"绑到界面上。
 *
 * <p>{@code stillValid} 只比对距离——船在开动时方块会整艘挪走，绝对坐标对不上，
 * 但玩家就在船里，和船上那点的相对距离一直不变。</p>
 */
public class SonarMenu extends AbstractContainerMenu {

    private final BlockPos sonarPos;
    private final int range;

    public SonarMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, buf.readBlockPos(), buf.readVarInt());
    }

    public SonarMenu(int id, Inventory inventory, BlockPos sonarPos, int range) {
        super(ModMenus.SONAR.get(), id);
        this.sonarPos = sonarPos;
        this.range = range;
    }

    public BlockPos sonarPos() {
        return sonarPos;
    }

    public int range() {
        return range;
    }

    @Override
    public boolean stillValid(Player player) {
        // 物理船：声纳方块在 VS2 船坞区块，离玩家上百万格；界面靠命令打开，
        // 只要玩家还在物理船状态里就允许（船开着坐标一直在动）。
        if (com.opamb.barotrauma.ship.vs.VsState.active()) {
            return true;
        }
        return player.distanceToSqr(Vec3.atCenterOf(sonarPos)) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
