package com.opamb.barotrauma.menu;

import com.opamb.barotrauma.blockentity.DrainPumpBlockEntity;
import com.opamb.barotrauma.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 排水泵菜单：无物品槽，两个数据位（开关、排水量）通过 {@link ContainerData} 自动同步。
 *
 * <p>服务端实例直接代理方块实体的值；客户端实例用 {@link SimpleContainerData}，
 * 由原版 {@code ClientboundContainerSetDataPacket} 每 tick 刷新。</p>
 */
public class PumpMenu extends AbstractContainerMenu {

    /** 数据位 0：开关（0/1）；数据位 1：排水量（单位/秒）。 */
    public static final int DATA_ENABLED = 0;
    public static final int DATA_RATE = 1;

    private final BlockPos pumpPos;
    private final ContainerData data;

    public PumpMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, buf.readBlockPos());
    }

    public PumpMenu(int id, Inventory inventory, BlockPos pumpPos) {
        super(ModMenus.PUMP.get(), id);
        this.pumpPos = pumpPos;
        if (!inventory.player.level().isClientSide
                && inventory.player.level() instanceof ServerLevel serverLevel
                && serverLevel.getBlockEntity(pumpPos) instanceof DrainPumpBlockEntity pump) {
            this.data = new ContainerData() {
                @Override
                public int get(int index) {
                    return index == DATA_ENABLED ? (pump.enabled() ? 1 : 0) : pump.rate();
                }

                @Override
                public void set(int index, int value) {
                    // 服务端的值只由 PumpActionPacket 改，原版同步包不会写到这里
                }

                @Override
                public int getCount() {
                    return 2;
                }
            };
        } else {
            this.data = new SimpleContainerData(2);
        }
        addDataSlots(data);
    }

    public ContainerData data() {
        return data;
    }

    public BlockPos pumpPos() {
        return pumpPos;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(Vec3.atCenterOf(pumpPos)) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
