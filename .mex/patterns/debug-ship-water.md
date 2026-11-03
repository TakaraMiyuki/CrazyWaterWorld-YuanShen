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

船内水不是流体模拟，而是 `ShipSimulation` 里每个舱室的一个标量（1 格 = 100 单位）。服务端把舱室拆成若干**矩形棱柱**（每个棱柱 7 个 float：min/max 包围盒 + surface）打包成 `ShipWaterStatePacket` 发给客户端；客户端 `ShipWaterRenderer` 用它在世界里画水面（10 Hz packet + 逐帧插值）。**不能每个舱室只发一个 bbox**：破口空腔的 bbox 会把旁边的密闭小间一起盖住，用户实测“两边完全隔离，没破的那边也跟着涨水”就是这个视觉假象（服务端日志 `密闭舱 1 / 连海 1 / 舱容 6` 说明隔离其实生效了）。棱柱覆盖整个舱室、每个棱柱带自己的 surface：含水的用当前水位，水到不了的用自己舱底（渲染端跳过），所以客户端平滑数组不会因为“水漫到新格子”换长度。

**连海空腔的水按 1×1 竖直列各自维护水位**：`ShipPocketWater` 把空腔拆成竖直列，相邻两列只有共享空气格子才能过水，**最低的共享格子就是溢流口**。水从破口所在列开始填，涨到溢流口才流到下一列；下一列按自己的水位慢慢上涨，多个隔断会依次传递，不会越过第一个就全部一起涨。破口在舱顶时水沿竖直柱落到舱底，不会先湿上方空气。服务端按列发盒子（每列一个 surface，Y 有断档时拆段），客户端逐列画水面并把同水位的相邻列内部面剔掉。

包里还带连海破口数据（每个破口 8 个 float：格中心 + 所属空腔在 boxes 里的下标 + 朝舱内的水平方向 + 空腔 minY/maxY）。客户端按破口与所在舱室水面的关系画两种效果：破口在水面以上（舱内还是空气）喷白色烟雾（原版 `CLOUD`）。**两种粒子的主轴都沿进水方向**（服务端给的“洞口 → 舱内质心”水平方向），再叠加一个垂直方向的散射锥，不再是固定偏一个角或各向同性散开。破口在水下时才持续冒气泡（`barotrauma:ship_bubble`，放大版原版 BubbleParticle）：顶部破口的气泡向下压进船里，约 2 秒后淡出、不再回升；底部破口正常上浮，寿命 9~13 秒；气泡到水面就破。**破口很多时必须限流**：每 100ms 总共最多 72 个粒子、气泡存活上限 260、破口轮转起点，否则 80+ 个洞 × 长寿命气泡会堆到几万个把内存吃光（2026-11-03 实测 native OOM：`Failed to resize buffer ...`）。**船下沉/移动时，气泡每 tick 跟随船的位移、水面判定也跟着船走**（渲染器把船速度设成 carry），否则粒子会留在原地，看起来“因为惯性往上跑”；空气侧的白烟也把船速度加进初速度。水面使用不写深度的自定义 RenderType，水后粒子/气泡不会被水面整片挡住；相邻棱柱之间的内部面会被跳过，避免半透明叠成深色条。满水舱的气泡密度大幅降低。进水速率已从 20 格³/秒降到 6。水花只在该舱室自己的水面还在涨时出现；破口在水下（舱室已经灌满）时持续冒气泡。渲染默认开启，可用 `-Dbarotrauma.renderWater=false` 临时关闭，以区分数据问题和渲染问题。

“外面蓝色的海”由**位置**决定，不看视线：`UnderwaterEffects` 在 `AFTER_SKY` 用相机相对的大方块把天空铺成海水色（带上下渐变；地形画在它前面，所以船内/哨站室内不会被染色），同时 `ComputeFogColor` 在深海维度里始终返回海水色。**室内和海里用同一套视距**（近 20 / 远 72，按深度略微缩短）：之前水下 1.5~22、干燥区 48~112 会让同一个海从舱内看和从水里看是两个画面。不要再按“视线中心是否看到水”开关雾——那会导致必须直视海面才有颜色、余光看海什么都没有。

因此“看不到水”可能有三类原因：船确实是干的、水面数据没同步、或者渲染被关闭（当前默认）。先区分再修。

## Steps

1. 看服务端日志里的 `[water]` / `[water-diag]` 行：`waterUnits`、`surface`、`minY`、`quads`、`boxesDrawn`。
2. 如果 `waterUnits=0` 且 `surface=minY`，船是干的；用 `/barotrauma ship flood 1500` 或砸一格船壳制造破口。
3. 物理船找不到声纳核心时，`/barotrauma ship flood` 会回退到 `VsPilot.state()`；确认命令返回的是“物理船 1 号舱”而不是“没有船核心”。
4. 检查 `ShipWaterStateBuilder.build(...)` 的输出：`boxes.length` 应该是 7 × 舱室数；`surface` 应随注水上升。
5. 检查客户端是否收到包：`ShipWaterRenderer` 的 `packetCount` 和日志；如果 `boxesDrawn=0`，先确认 ship id / physical 标志。
6. 如果只是“看不到水面”，先确认没有加 `-Dbarotrauma.renderWater=false`；再确认该舱 `surface > minY`（船干的时候不会画），以及客户端日志里的 `boxesDrawn`。
7. 破口没有水花/气泡：看客户端 `[water] 画水面` / `收到水面包` 日志里的 `breaches=N`。只有真正连到外海的洞才会被记录；内部隔墙的洞会被 `ShipState.addBreaches` 过滤掉。空气侧不再涨水时水花停止；灌满后水下气泡会持续冒，这是预期行为。

## Gotchas

- **第一次装配的船通常是干的**：没有破口、没有注水时 `surface == minY`，渲染器按“无水”跳过是正确的。
- `flood` 命令只加模拟水量；物理船不会写世界水方块，世界里看不到水块，水面由客户端自绘。
- 水面高度使用装配坐标；物理船移动后客户端要用 `VsState` 的偏移换算，偏移不同步会表现为水面错位。
- `ShipWater.applyWater()` 仍能给非物理船铺真实水方块；空气海域下不要用它来“验证”水面。
- 水面只按真实格子的矩形棱柱绘制；如果隔离舱还在涨水，先看服务端 `[vs] 船体破损：… → 重扫 N 个舱室 / M 处连海`：M 处连海各自独立，隔离舱应该算在“N 个舱室”里且水量为 0。
- “隔墙顶上留孔”属于同一个连海空腔：孔在水面以上时另一侧必须干燥；如果一破口两侧同步涨水，检查 `ShipPocketWater` 的破口 source 是否传进了 `ShipWaterStateBuilder`（破口位置必须落在 openPockets 格子里）。
- **现在船壳有逐格耐久**：左键/爆炸只扣耐久；耐久 ≤90% 就开始小漏、≤30% 大漏，0% 时方块切成 `breached`（保留方块数据、无碰撞、不可见），几何扫描把它当空气，才进入连海空腔灌水。砸了几下没进水不是水系统坏了，先看 `HullDamage` 的剩余比例；详见 `patterns/debug-hull-damage.md`。
- 物理船的船壳方块在世界里是空气，不能用 `level.getBlockState(worldPos)` 判断破口/耐久；物理船走 `snapshot` + `VsPilot.breakSnapshotBlock`，耐久包 `ShipHullDamagePacket` 的坐标空间和 `ShipWaterStatePacket` 相同。

## Verify

- [ ] `flood` 后日志里的 `waterUnits` 增加
- [ ] 对应的 `surface` 从 `minY` 上升
- [ ] 水面包的 `boxes` 数量等于舱室数
- [ ] 客户端 `packetCount` 增长且 ship id 匹配
- [ ] 明确区分“渲染关闭”和“数据错误”

## Debug

服务端问题看 `VsPilot` 的水量同步计时和 `ShipWaterStateBuilder`；客户端问题看 `ShipWaterRenderer.onRenderStage` 和 `ClientShip` 缓存；干湿判断问题看 `OceanEnvironment.isSubmerged` 与 `VsPilot.isInsideDryZone`。
