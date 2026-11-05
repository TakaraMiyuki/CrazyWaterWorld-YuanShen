---
name: debug-hull-damage
description: Diagnose hull durability, missing damage bars, welding, and the “block only breaks at 0 HP” breach path.
triggers:
  - "hull not breaking"
  - "durability bar missing"
  - "welding not working"
  - "hull damage"
  - "repair progress"
edges:
  - target: patterns/debug-ship-water.md
    condition: when a block finally breaks and you need to verify breach inflow
  - target: patterns/vs2-physical-ship.md
    condition: when the ship is a VS2 physical ship
last_updated: 2026-11-05

mex:
  id: mx_01M6GX58H7XEYC0Q58QBFE0FMY
  type: pattern
  status: promoted
  revision: 3
  title: debug-hull-damage
  relations:
    - type: related_to
      target: mx_01M6FTV7HFY90PQMFBX26T2438
      note: when a block finally breaks and you need to verify breach inflow
    - type: related_to
      target: mx_01M6FTV7VPSPV6F87V82ZGWGX4
      note: when the ship is a VS2 physical ship
---

# Debug Hull Damage

## Context

船壳现在是**逐方块耐久**，不是“砸一下直接破”：

- 服务端 `ShipState.hullDamage()` 保存每格剩余比例（`HullDamage`，0..1）。基础血量来自 `ModShipBlocks.maxHealth`：装甲板 200、舷窗 100、测试船壳 200；再乘 `ShipState.hullHealthMultiplier`（默认 1.0，预留给原版 `increasewallhealth` 升级）。
- 官方基础值：外壳 200、窗户 100、船头 400、内墙 55/75。**T1/T2/T3 本身不改单块血量**；tier 只限制加固升级等级（每级 +20%，T1 2 级 / T2 4 级 / T3 6 级）。
- 伤害来源：左键（`ShipBreakMixin`）、爆炸（`HullDamageEvents`）、方块船撞击（`ShipState.damageFront`）。**VS2 物理船撞地形还没接**（没有方便的 CollisionEvent 订阅入口）。
- **渗漏/破口按原版分段**：
  - 剩余 ≤90%（伤害 ≥10%）：`ShipState.tickLeaks` 往相邻密封舱注水（210 单位/s，约完整破口 35%）。
  - 剩余 ≤30%（伤害 ≥70%）：大漏（450 单位/s，约 75%）。
  - 剩余 0%：方块切成 `HullBlock.BREACHED=true`——**不删除、不掉落、无碰撞、不可见，但方块数据和 0% 耐久保留**；几何扫描把它当空气，舱室变连海空腔，走 `tickSeaFlood`。焊接回 >0 时恢复实体并重扫封口。
- 网络：`ShipHullDamagePacket`，每格 5 个值（x/y/z + 剩余比例 + 泄漏强度），10 Hz + 受击/焊接立即发。物理船坐标空间和 `ShipWaterStatePacket` 一致（装配原坐标 + anchor）。
- 客户端：`HullDamageRenderer` 在 `AFTER_PARTICLES` 画**朝向相机的公告牌大血条**（不是贴面 decal），叠加原版 `destroy_stage_*` 裂痕（六面）和按泄漏强度喷水花；`HullDamageOverlay`/动作栏百分比已删除，只保留这一条。
  - **黑条根因（2026-11-04 定位）**：贴面 decal 选"朝相机的面"用的是船局部坐标；船沉了几十米后相机在船局部坐标里高出船壳几十格，永远选中方块顶面，侧面看顶面 decal 就是一条压扁的黑缝（health 数值全正常）。公告牌不受影响。
  - **防嵌墙公式**：外抬量 = `0.6 + hw·|right·n| + hh·|up·n|`，保证牌子靠墙边恒定悬在方块面外 0.1，任何视角都不会被深度测试剪成红丝。条宽 `min(1.4, 0.55 + dist*0.04)`。
  - 裂痕用 `RenderType.crumbling`，顶点**从方块外看必须逆时针**（默认 CULL）；顺时针会从外面整面被剔，只有钻进方块内部才看得见。
- 焊接：`WeldingToolItem` 右键持续修，每 0.1 s 修 2.5 点（25 HP/s），每 0.5 s 消耗 1 点燃料，耐久 400。

## Steps

1. 看服务端日志：只有真正破块才会出现 `[ship] 方块船船体破损` 或 `[vs] 船体破损`。没这行说明耐久还没到 0，不是进水链路坏了。
2. 左键不掉耐久：
   - 方块船：确认该格在 `geometry.hull()` 里，且 `ShipCores.nearestCore` 找得到核心（64 格内）。
   - 物理船：确认 `VsState.active()`、`snapshot` 非空、`pickHullBlock` 的 `worldToShip` 射线命中；世界里那一格是空气，不能直接读 `level.getBlockState`。
   - 声纳 `maxHealth=0`，永远拒绝伤害（船脑）。
3. 耐久 ≤90% 但没水：检查 `ShipState.tickLeaks` 是否每 tick 被调用、受损块是否 `touchesOutside`（有一面朝船外）且相邻有密封舱；0% 则不靠泄漏，检查几何是否把 breached 当空气、`openPockets` 是否包含洞口。
4. 血条不出现：
   - 在 `HullDamageRenderer.accept` 打日志确认收到包、`count()>0`；
   - 距离超过 40 格的条会被跳过；
   - 物理船要能在客户端 `ShipWaterRenderer.findClientShip` 按 `shipId` 找到 `ClientShip`。
5. 焊接不生效：
   - 物理船必须右键**空气**（船是 VS2 实体），由 `VsState.tryRepair` 对快照射线；
   - 方块船右键实际方块，走 `player.pick` + `core.repairBlock`；
   - `onUseTick` 只在 `startUsingItem` 后运行；燃料为 0 时 `use` 会直接失败。
6. 破口后水不对：转到 `patterns/debug-ship-water.md`；耐久系统只负责“什么时候开始漏/什么时候出现洞”。

## Gotchas

- 创造模式**蹲下**左键：普通方块一击打到 0%（breached）；已经是 breached 的方块则直接清掉（否则无碰撞后挖不掉）。不蹲仍按耐久扣。
- 0% 的 breached 方块不会被删除/掉落；不要再用 `level.setBlock(AIR)`，否则焊接恢复逻辑会丢方块。焊接时先 `removeBreach` 再恢复 blockstate + 重扫。
- `ModShipBlocks.isSolidForShip` 必须把 breached 当非实心，否则几何扫描看不到洞，水不会进。
- `ShipState.rescan` 会新建 `ShipState`，必须把 `HullDamage` 和 `hullHealthMultiplier` 一起拷过去，否则修船进度重置。
- `replaceGeometry` 会清掉已经不在 `geometry.hull()` 里的记录；不要把耐久存到方块实体里，否则重扫就丢。
- 物理船的爆炸半径是从 `event.getAffectedBlocks()` 反推的（1.20.1 `Explosion.radius` 是 private 且无 getter）；开阔水域没有方块可炸时退回 4 格。
- 爆炸事件里要把船体格从原版破坏列表移除，否则还是会被秒掉；声纳也要移除（防炸）。
- 耐久目前只在运行时；世界存档/物理船拆回再装会丢。要做持久化时，随 `ShipState` 一起序列化。
- 世界条是不写深度的半透明 `RenderType`；如果条被水面/粒子整片盖住，检查 `BarRenderType` 的 `WriteMaskStateShard(true,false)`。
- 0% 的条是**整条亮红**（0.95/0.12/0.10）；暗红 (0.55,0.04,0.04) 深水下看着就是黑的。部分血量的填充有最小可见宽度（条宽的 12%）。
- 血条数值异常（黑框黑槽无填充）只可能来自非有限值：`HullDamage.applyDamage/setHealth`、`ShipHullDamagePacket.build`、`HullDamageRenderer.targets` 都有 NaN 防护；每格首次出现血条会打 `[hull-bar] pos=... health=... bits=...`，出问题时先看这行。
- **裂痕看不见，按这两个根因查**（issue #14，2026-11-05 修）：① `HullDamageRenderer.drawCrackCube` 的面绕序——crumbling RenderType 开背面剔除，法线朝内的面整面被剔（顶面曾因坐标顺序发成 -Y，舱内永远看不到舱顶裂痕）；现绕序集中在 `FACE_CORNERS` 表里，全部叉积验证过，改面别再手写六组坐标。② 被实心邻居盖住的面：贴面外扩 0.003 会伸进邻居体积被深度测试挡死——现在露出面外扩 0.003、被盖面画共面（LEQUAL + 地形后绘制，与原版破坏叠加层同机制）。判断用 `ShipCellFaces.isFaceCovered`（船局部坐标查邻居，方块船/物理船同一份代码）。
- **水花/气泡看不见**：粒子不能从破口格中心喷（0% 破口方块仍渲染成实心，会挡住自己）；`spawnLeakParticles`/`spawnBreachWater` 都改用 `ShipCellFaces.pickVisibleFace` 贴"未盖住且最朝向相机"的面。竖直破口的服务端法线是 (0,0)（`ShipWaterStateBuilder.breachNormal`），靠客户端选面兜底，不依赖它。
- **血条部分视角发黑**（issue #14 验收发现，2026-11-05 修）：`BarRenderType` 的 `sortOnUpload` 必须是 **false**——开启后半透明四边形上传时按中心距离重排，而血条三层共面、填充层中心因左对齐偏移挪位，斜视角下填充层排到 90% 不透明近黑底槽之前被盖掉，血条整体发黑且随视角时好时坏。手写顺序的共面多层 quad（描边→底槽→填充）一律关排序，靠插入顺序保证叠加。

## Verify

- [ ] 左键船壳：方块上方出现唯一的红/绿条
- [ ] 耐久 ≤90%：舱内水面开始缓慢上涨（小漏）
- [ ] 耐久 ≤30%：进水明显加快（大漏）
- [ ] 耐久 0%：方块无碰撞/不可见、条变深红 0%，水从洞口灌入；能穿过去
- [ ] 焊接 0% 方块：修到 >0 后碰撞/渲染恢复，重扫封口，漏水停止
- [ ] 爆炸：船壳格先掉耐久而不是直接消失；两发 TNT 打穿 200 血外壳
- [ ] 舷窗最大 100、装甲板最大 200
- [ ] `./gradlew test --offline` 全绿
