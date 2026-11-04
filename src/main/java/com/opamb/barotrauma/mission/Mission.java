package com.opamb.barotrauma.mission;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 一条任务：静态定义 + 运行时状态。
 *
 * <p>任务池是**确定性的**（见 {@link com.opamb.barotrauma.world.PoiNetwork}），世界里每次
 * 都是这批任务；SavedData 只存每条的状态和认领人，存档小、逻辑简单。</p>
 *
 * <p>状态机：{@code OFFERED →（接取）ACTIVE →（达成条件）READY →（终端交付）DONE}；
 * 放弃回到 {@code OFFERED}。</p>
 */
public final class Mission {

    public enum State {
        OFFERED, ACTIVE, READY, DONE
    }

    /** 池内唯一 id（从 1 开始，任务定义顺序即 id）。 */
    public final int id;
    public final MissionType type;
    /** 目标点：沉船中心 / 侦察点 / 交付终端位置。 */
    public final BlockPos target;
    /** 到达判定半径（RECON 用；其它类型存着给 HUD 显示距离）。 */
    public final int radius;
    /** 交付奖励（credits）。 */
    public final int reward;

    private State state = State.OFFERED;
    private UUID assignedTo;
    private long assignedAtGameTime;
    /** 仅客户端展示用：这条是否已被谁接走。 */
    private boolean clientTaken;

    public Mission(int id, MissionType type, BlockPos target, int radius, int reward) {
        this.id = id;
        this.type = type;
        this.target = target.immutable();
        this.radius = radius;
        this.reward = reward;
    }

    public State state() {
        return state;
    }

    public void setState(State next) {
        this.state = next;
    }

    public UUID assignedTo() {
        return assignedTo;
    }

    public long assignedAtGameTime() {
        return assignedAtGameTime;
    }

    public boolean isTaken() {
        return assignedTo != null || clientTaken;
    }

    public void setClientTaken(boolean taken) {
        this.clientTaken = taken;
    }

    /** 接取。返回 false = 已被别人接走/状态不允许。 */
    public boolean assign(UUID player, long gameTime) {
        if (state != State.OFFERED) {
            return false;
        }
        state = State.ACTIVE;
        assignedTo = player;
        assignedAtGameTime = gameTime;
        return true;
    }

    /** 放弃：回到可接取。 */
    public void abandon() {
        state = State.OFFERED;
        assignedTo = null;
        assignedAtGameTime = 0;
    }

    /** 交付成功：从池里关掉。 */
    public void complete() {
        state = State.DONE;
        assignedTo = null;
    }

    /** 客户端简报构造（MissionStatePacket 用；不带 assignedTo）。 */
    public static Mission clientBrief(int id, MissionType type, BlockPos target, int radius,
                                      int reward, State state, boolean taken) {
        Mission mission = new Mission(id, type, target, radius, reward);
        mission.state = state;
        mission.clientTaken = taken;
        return mission;
    }

    // ------------------------------------------------------------------ NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("state", state.name());
        if (assignedTo != null) {
            tag.putUUID("assignedTo", assignedTo);
            tag.putLong("assignedAt", assignedAtGameTime);
        }
        return tag;
    }

    /** 从存档恢复运行时状态；id/type 等静态定义以池内为准。 */
    public void load(CompoundTag tag) {
        state = State.valueOf(tag.getString("state"));
        assignedTo = tag.hasUUID("assignedTo") ? tag.getUUID("assignedTo") : null;
        assignedAtGameTime = tag.getLong("assignedAt");
    }
}
