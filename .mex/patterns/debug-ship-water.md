---
name: debug-ship-water
description: Diagnose why a ship is dry, flooding incorrectly, or not showing water, using the scalar water simulation and water-state packet.
triggers:
  - "ship not flooding"
  - "no water visible"
  - "water level wrong"
  - "breach not filling"
  - "water diag"
edges:
  - target: context/architecture.md
    condition: when the water simulation's place in the ship loop is unclear
  - target: patterns/vs2-physical-ship.md
    condition: when the ship is a VS2 physical ship
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTV7HFY90PQMFBX26T2438
  type: pattern
  status: promoted
  revision: 3
  title: debug-ship-water
  relations:
    - type: related_to
      target: mx_01M6FTBTA32ESJGHB433FWZZZ3
      note: when the water simulation's place in the ship loop is unclear
    - type: related_to
      target: mx_01M6FTV7VPSPV6F87V82ZGWGX4
      note: when the ship is a VS2 physical ship
---

# Debug Ship Water

## Context

船内水不是流体模拟，而是 `ShipSimulation` 里每个舱室的一个标量（1 格 = 100 单位）。服务端把舱室 bbox + surface 打包成 `ShipWaterStatePacket`（每舱 7 个 float）发给客户端；客户端 `ShipWaterRenderer` 用它在世界里画水面。**当前 `ShipWaterRenderer.RENDER_WATER = false`，水面绘制被有意关闭**，水面包只用于 `OceanEnvironment` 的干湿判断。

因此“看不到水”可能有三类原因：船确实是干的、水面数据没同步、或者渲染被关闭（当前默认）。先区分再修。

## Steps

1. 看服务端日志里的 `[water]` / `[water-diag]` 行：`waterUnits`、`surface`、`minY`、`quads`、`boxesDrawn`。
2. 如果 `waterUnits=0` 且 `surface=minY`，船是干的；用 `/barotrauma ship flood 1500` 或砸一格船壳制造破口。
3. 物理船找不到声纳核心时，`/barotrauma ship flood` 会回退到 `VsPilot.state()`；确认命令返回的是“物理船 1 号舱”而不是“没有船核心”。
4. 检查 `ShipWaterStateBuilder.build(...)` 的输出：`boxes.length` 应该是 7 × 舱室数；`surface` 应随注水上升。
5. 检查客户端是否收到包：`ShipWaterRenderer` 的 `packetCount` 和日志；如果 `boxesDrawn=0`，先确认 ship id / physical 标志。
6. 如果只是“看不到水面”，先确认 `RENDER_WATER` 仍为 false——这是当前空气海域方案的预期行为，不要当 bug 修。

## Gotchas

- **第一次装配的船通常是干的**：没有破口、没有注水时 `surface == minY`，渲染器按“无水”跳过是正确的。
- `flood` 命令只加模拟水量；物理船不会写世界水方块，所以世界里看不到水块是正常的。
- 水面高度使用装配坐标；物理船移动后客户端要用 `VsState` 的偏移换算，偏移不同步会表现为水面错位。
- `ShipWater.applyWater()` 仍能给非物理船铺真实水方块；空气海域下不要用它来“验证”水面。
- 舱室 bbox 是近似几何，非矩形舱室的水面会超出实际空间。

## Verify

- [ ] `flood` 后日志里的 `waterUnits` 增加
- [ ] 对应的 `surface` 从 `minY` 上升
- [ ] 水面包的 `boxes` 数量等于舱室数
- [ ] 客户端 `packetCount` 增长且 ship id 匹配
- [ ] 明确区分“渲染关闭”和“数据错误”

## Debug

服务端问题看 `VsPilot` 的水量同步计时和 `ShipWaterStateBuilder`；客户端问题看 `ShipWaterRenderer.onRenderStage` 和 `ClientShip` 缓存；干湿判断问题看 `OceanEnvironment.isSubmerged` 与 `VsPilot.isInsideDryZone`。
