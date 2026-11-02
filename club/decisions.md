# 决策日志

> 只追加、不修改、不删除；最新条目在最上面。格式见 `club/CLUB.md` 附录 D。

## 2026-11-02 · opamber258

**决定**：初始化 Club v1 协作协议。

**理由**：项目已有一套 `HANDOFF.md` / `PLAN.md` / `STATUS.md` 的个人工作流，但缺少多人共享的"当前交接"和"为什么当初这么定"；采用 Club v1，把协作状态收进 `club/`，根目录只留入口指针。

**影响**：新增根目录 `AGENTS.md` 和 `club/` 下 4 个文件（`CLUB.md`、`ARCHITECTURE.md`、`TEMPLATE.md`、`decisions.md`）。现有 `HANDOFF.md` / `PLAN.md` / `STATUS.md` 保留，不迁移、不删除；在第一次 Club 收工产生 `club/latest-opamber258.md` 之前，`HANDOFF.md` 仍是当前状态的主要参考。从下一次收工起，新的交接写入 `club/latest-<id>.md`，影响他人的决定写入本文件。文档与代码冲突时以代码为准。
