---
name: vs2-physical-ship
description: "Work with Valkyrien Skies 2 physical ships — launch, dock, thrust, sonar proxy, and the static VsPilot lifecycle."
triggers:
  - "physical ship"
  - "VS2"
  - "vs launch"
  - "ship assembly"
  - "sonar proxy"
  - "ship disappears after restart"
edges:
  - target: context/architecture.md
    condition: when the physical-ship path and server/client layers are unclear
  - target: patterns/debug-ship-water.md
    condition: when the ship is assembled but water or dry-zone behavior is wrong
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTV7VPSPV6F87V82ZGWGX4
  type: pattern
  status: promoted
  revision: 3
  title: vs2-physical-ship
  relations:
    - type: related_to
      target: mx_01M6FTBTA32ESJGHB433FWZZZ3
      note: when the physical-ship path and server/client layers are unclear
    - type: related_to
      target: mx_01M6FTV7HFY90PQMFBX26T2438
      note: when the ship is assembled but water or dry-zone behavior is wrong
---

# VS2 Physical Ship

## Context

`VsPilot` 是物理船的唯一静态管理器，负责把声纳台周围的船体方块装配成 VS2 刚体、挂推进器、维护船坞坐标偏移、对账破口、同步水量，并放置/移动 `sonar_proxy` 交互代理。`VsSupport` 只做 `ModList.isLoaded("valkyrienskies")` 判断；所有 VS2 类型必须留在 `ship/vs/` 内。

服务端生命周期切换时 `VsPilot.resetForServerLifecycle()` 会清空 `ship`、`thruster`、guard、水面包和偏移。**当前没有持久化**：服务器重启后，世界里已存在的 VS2 船不会被找回，必须重新 `vs launch`。

## Steps

1. 进新世界，执行 `/barotrauma deep`，到哨站/潜艇旁。
2. 在声纳台附近执行 `/barotrauma ship vs launch`；看日志确认装配方块数、质量、舱室数。
3. 用 `/barotrauma ship vs status` 检查船 id、偏移、推进器和水量。
4. 控制：`/barotrauma ship vs thrust <dx> <dy> <dz>`、`/barotrauma ship vs power <5-100>`；停止用 `/barotrauma ship vs thrust 0 0 0`。
5. 拆回：`/barotrauma ship vs dock`；确认方块回到世界坐标、声纳代理移除。
6. 改代码后至少跑 L1 + L2；涉及 VS2 生命周期的改动再跑 L3 `.smoketest-vs`。

## Gotchas

- **不要在 `ship/vs/` 之外 import VS2 类**；未安装 VS2 的服务器会类加载失败。
- **物理船上右键交互必须用 `sonar_proxy`**：VS2 2.4.11 不做方块交互坐标重映射，原声纳方块在船坞坐标里，直接右键不到。
- **`VsPilot` 是单例**：同一时刻只能管理一艘物理船；多船支持尚未实现。
- **没有持久化**：服务器重启后 `VsPilot` 状态为空；旧船不会被认领，测试请用新世界重新 launch。
- 空气海域下 `ShipVolumeGuard` / `LevelFluidGuardMixin` 仍保留，但主要用于旧存档兼容；不要因此往海里放真实水。
- `VsState.offsetX/Y/Z` 是装配坐标到世界坐标的偏移；水面、干湿判定和 HUD 都依赖它，修改移动逻辑时不要绕过它。

## Verify

- [ ] `/barotrauma ship vs status` 能看到船 id、质量和偏移
- [ ] `sonar_proxy` 跟着船移动，右键能打开声纳界面
- [ ] 推进/停止生效，船体不散架
- [ ] 破口后 `VsPilot` 能对账并更新水量
- [ ] `vs dock` 后方块回填、代理移除、静态状态清空
- [ ] 未安装 VS2 时模组仍能启动（`VsSupport.available()` 为 false）

## Debug

装配失败先看 VS2 是否加载、船体是否被 `ModShipBlocks.isShipBlock` 识别、船坞区域是否被占用；船开走后原地留方块看 `ShipState.collectBlocks()` 是否扫描了整个包围盒；重启后船没了是持久化未实现，不是装配失败。
