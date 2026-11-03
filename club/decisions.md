# 决策日志

> 只追加、不修改、不删除；最新条目在最上面。格式见 `club/CLUB.md` 附录 D。

## 2026-11-03 · opamber258

**决定**：深海海域从真实水方块改为空气，水下感由客户端雾/滤镜表现；船内进水继续用每舱标量水位，删除 `hull_plug`。

**理由**：真实水会触发 VS2 实体区块碰撞异常，物理船装配后海水还会灌进船体；空气海域可以让船体、哨站和舱室保持真正的空气环境，同时用客户端效果维持水下观感。

**影响**：`world/` 生成空气海域；新增 `world/OceanEnvironment`、`client/UnderwaterEffects`、`client/UnderwaterMovement`、`event/UnderwaterSurvival`；`ShipWaterRenderer` 停止绘制外部水面，水面包只用于干湿判断；`hull_plug` 方块/类/资源删除，旧存档可能缺失方块。详见 `club/ARCHITECTURE.md`。

## 2026-11-02 · opamber258

**决定**：初始化 Club v1 协作协议。

**理由**：项目已有一套 `HANDOFF.md` / `PLAN.md` / `STATUS.md` 的个人工作流，但缺少多人共享的"当前交接"和"为什么当初这么定"；采用 Club v1，把协作状态收进 `club/`，根目录只留入口指针。

**影响**：新增根目录 `AGENTS.md` 和 `club/` 下 4 个文件（`CLUB.md`、`ARCHITECTURE.md`、`TEMPLATE.md`、`decisions.md`）。现有 `HANDOFF.md` / `PLAN.md` / `STATUS.md` 保留，不迁移、不删除；在第一次 Club 收工产生 `club/latest-opamber258.md` 之前，`HANDOFF.md` 仍是当前状态的主要参考。从下一次收工起，新的交接写入 `club/latest-<id>.md`，影响他人的决定写入本文件。文档与代码冲突时以代码为准。
