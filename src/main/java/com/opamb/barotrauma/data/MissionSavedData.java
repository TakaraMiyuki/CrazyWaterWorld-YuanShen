package com.opamb.barotrauma.data;

import com.opamb.barotrauma.mission.Mission;
import com.opamb.barotrauma.mission.MissionType;
import com.opamb.barotrauma.world.PoiNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 任务与经济的世界存档：任务池状态、每个玩家的进行中任务和 credits。
 *
 * <p>任务定义（id/类型/目标/奖励）来自 {@link PoiNetwork#definitions()}，是确定性的；
 * 这里只存运行时状态。credits 死亡不掉（存档在服务端，不在背包里）。</p>
 */
public final class MissionSavedData extends SavedData {

    public static final String NAME = "barotrauma_missions";

    private final Map<Integer, Mission> missions = new LinkedHashMap<>();
    /** 玩家 → 进行中的任务 id（一人同时一条）。 */
    private final Map<UUID, Integer> active = new HashMap<>();
    private final Map<UUID, Integer> credits = new HashMap<>();

    public MissionSavedData() {
        for (Mission mission : PoiNetwork.definitions()) {
            missions.put(mission.id, mission);
        }
    }

    public static MissionSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                MissionSavedData::load, MissionSavedData::new, NAME);
    }

    public Map<Integer, Mission> missions() {
        return missions;
    }

    @Nullable
    public Mission activeMission(UUID player) {
        Integer id = active.get(player);
        return id == null ? null : missions.get(id);
    }

    public int credits(UUID player) {
        return credits.getOrDefault(player, 0);
    }

    public void addCredits(UUID player, int amount) {
        credits.put(player, credits.getOrDefault(player, 0) + amount);
        setDirty();
    }

    public boolean spendCredits(UUID player, int amount) {
        int current = credits.getOrDefault(player, 0);
        if (current < amount) {
            return false;
        }
        credits.put(player, current - amount);
        setDirty();
        return true;
    }

    /** 接取。false = 已有进行中任务/任务被别人接走。 */
    public boolean accept(UUID player, int missionId, long gameTime) {
        if (active.containsKey(player)) {
            return false;
        }
        Mission mission = missions.get(missionId);
        if (mission == null || !mission.assign(player, gameTime)) {
            return false;
        }
        active.put(player, missionId);
        setDirty();
        return true;
    }

    /** 放弃：任务回到池里（已拿的货物不回收）。 */
    public void abandon(UUID player) {
        Mission mission = activeMission(player);
        if (mission != null) {
            mission.abandon();
            active.remove(player);
            setDirty();
        }
    }

    /** 达成（进 READY），由 {@code MissionProgress} 在条件满足时调用。 */
    public void markReady(Mission mission) {
        mission.setState(Mission.State.READY);
        setDirty();
    }

    /** 交付成功：任务关闭并从 active 摘除。 */
    public void finish(UUID player, Mission mission) {
        mission.complete();
        active.remove(player);
        addCredits(player, mission.reward);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag missionList = new ListTag();
        for (Mission mission : missions.values()) {
            missionList.add(mission.save());
        }
        tag.put("missions", missionList);
        CompoundTag activeTag = new CompoundTag();
        for (Map.Entry<UUID, Integer> entry : active.entrySet()) {
            activeTag.putInt(entry.getKey().toString(), entry.getValue());
        }
        tag.put("active", activeTag);
        CompoundTag creditsTag = new CompoundTag();
        for (Map.Entry<UUID, Integer> entry : credits.entrySet()) {
            creditsTag.putInt(entry.getKey().toString(), entry.getValue());
        }
        tag.put("credits", creditsTag);
        return tag;
    }

    private static MissionSavedData load(CompoundTag tag) {
        MissionSavedData data = new MissionSavedData();
        ListTag missionList = tag.getList("missions", Tag.TAG_COMPOUND);
        for (int i = 0; i < missionList.size(); i++) {
            CompoundTag entry = missionList.getCompound(i);
            Mission mission = data.missions.get(entry.getInt("id"));
            if (mission != null) {
                mission.load(entry);
            }
        }
        CompoundTag activeTag = tag.getCompound("active");
        for (String key : activeTag.getAllKeys()) {
            try {
                data.active.put(UUID.fromString(key), activeTag.getInt(key));
            } catch (IllegalArgumentException ignored) {
                // 存档里混进了非法 key，跳过
            }
        }
        CompoundTag creditsTag = tag.getCompound("credits");
        for (String key : creditsTag.getAllKeys()) {
            try {
                data.credits.put(UUID.fromString(key), creditsTag.getInt(key));
            } catch (IllegalArgumentException ignored) {
                // 同上
            }
        }
        return data;
    }
}
