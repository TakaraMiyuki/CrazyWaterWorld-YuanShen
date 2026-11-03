---
name: architecture
description: How the major pieces of this project connect and flow. Load when working on system design, integrations, or understanding how components interact.
triggers:
  - "architecture"
  - "system design"
  - "how does X connect to Y"
  - "integration"
  - "flow"
edges:
  - target: context/stack.md
    condition: when specific technology details are needed
  - target: context/decisions.md
    condition: when understanding why the architecture is structured this way
  - target: context/tools.md
    condition: when changing texture generation, GameTest templates, or the AsmDump helper
grounds_to: []
last_updated: 2026-11-04
mex:
  id: mx_01M6FTBTA32ESJGHB433FWZZZ3
  type: architecture
  status: promoted
  revision: 4
  title: architecture
  relations:
    - type: related_to
      target: mx_01M6FTBTC471WET2A2MK599QB1
      note: when specific technology details are needed
    - type: related_to
      target: mx_01M6FTV797VEN7Z57WW58775G4
      note: when understanding why the architecture is structured this way
    - type: related_to
      target: mx_01M6FTMGAC6EFK2JXPVN38G81K
      note: when changing texture generation, GameTest templates, or the AsmDump helper
---

# Architecture

## System Overview

一个 Minecraft 1.20.1 Forge 模组，用服务端权威的方式模拟《潜渊症》式深海潜艇：

1. Forge 调用 `BarotraumaMod` 构造器，注册方块、物品、方块实体、菜单、实体和维度生成器。
2. 声纳台方块实体（`SonarBlockEntity`，继承 `ShipCoreBlockEntity`）扫描周围船体方块，`ShipGeometry` 把船体分成密闭舱室和连海空腔。
3. `ShipSimulation` 为每个舱室维护一个标量水量；破口、水泵、压载、反应堆都改这个数值，不直接写世界水方块。船壳本身是逐方块耐久（`HullDamage`）：左键/爆炸/撞击先扣剩余比例；伤害 ≥10% 开始小漏、≥70% 大漏，0% 时方块切成 `HullBlock.BREACHED`（保留数据、无碰撞、不可见），几何扫描把它当洞，走连海空腔进水；焊接回 >0 恢复实体。
4. 如果装了 Valkyrien Skies 2，`VsPilot` 把整船方块收进 VS2 物理船，逐 tick 把推进、压载、进水比例同步给 `VsThruster`，并按需把船内状态（舱室拆分成的矩形棱柱 + 水面 + 破口位置/所属空腔/朝内方向/空腔上下界）打包成 `ShipWaterStatePacket` 发给客户端。
5. 深海维度是空气海域：`DeepSeaChunkGenerator` 生成岩层和空腔，`OceanEnvironment` 用维度高度、哨站盒子和船内干燥区判断“是否浸没”。
6. 客户端只做表现：`SonarScreen` / `SonarRenderer`、`DepthOverlay`、`UnderwaterEffects` / `UnderwaterMovement`、`ShipWaterRenderer` 负责舱内水面绘制（客户端自绘，10 Hz packet + 逐帧插值；顶面高度按角点周围四格水位平均 + 波浪，相邻水柱之间不画台阶面）以及破口进水视觉（空气侧水花、水下气泡）；`HullDamageRenderer` 画受损方块的朝向相机公告牌耐久条（红→绿，0% 整条亮红）、六面原版裂痕和按泄漏强度的水花。

## Key Components

- **`BarotraumaMod`** — 唯一 `@Mod` 入口，构造器里挂 8 个 `DeferredRegister` 和 `ModNetwork.register()`；注册必须全部在构造阶段完成。
- **`registry/`** — 方块、物品、方块实体、菜单、实体、维度生成器和船体方块的注册表；`ModShipBlocks.isShipBlock()` 是判断“这格属于船”的唯一入口。
- **`ship/ShipState`** — 一艘船的运行时状态：几何、机器统计、`ShipSimulation`、`ShipWater`、逐方块船壳耐久（`HullDamage` + `hullHealthMultiplier`）、油门、功率、自动驾驶和物理速度。
- **`ship/HullDamage`** — 每格 0..1 剩余比例；重扫/平移/拆装时跟随船状态迁移。基础血量在 `ModShipBlocks.maxHealth`：装甲板 200、舷窗 100、测试船壳 200；声纳不可破坏。
- **`ship/vs/VsPilot`** — VS2 物理船的唯一静态管理器：装配/拆回、推进器、船坞偏移、破口对账、水量同步、声纳代理方块移动。`VsState` 提供不引用 VS2 类型的桥接，`VsSupport` 只做 `ModList.isLoaded("valkyrienskies")` 判断。
- **`ship/water/ShipVolumeGuard` + `mixin/LevelFluidGuardMixin`** — 拦截船只体积内的真实流体写入；空气海域下仍保留用于旧存档兼容。
- **`mixin/ShipBreakMixin`** — 把玩家左键破坏映射到船坞坐标，让物理船也能破口。
- **`world/OceanEnvironment`** — 干湿判定的唯一入口：维度高度 + 哨站盒子 + 船内干燥区；客户端和服务端分别保存自己的 `DryZone`（`setClientDryZone` / `setServerDryZone`），不依赖 `FluidState`。
- **`network/`** — 6 个包：`ShipActionPacket`（客户端→服务端）、`VesselPalettePacket`（船体渲染）、`SonarPingPacket` / `SonarDataPacket`（声纳）、`ShipWaterStatePacket`（水面包）、`ShipHullDamagePacket`（受损方块 + 剩余比例）。
- **`event/`** — `DeepSeaEvents`（登录、命令、自检）、`UnderwaterSurvival`（空气海域的氧气/溺水）。

## External Dependencies

- **Minecraft 1.20.1 + Forge 47.4.0** — 所有方块、实体、网络和事件都走 Forge API；不能用 Fabric 或更高版本的类。
- **Valkyrien Skies 2 2.4.11** — 物理船刚体、船坞坐标和推进器；`compileOnly` + `runtimeOnly`，正式包不内嵌。
- **Kotlin for Forge 4.12.0** — VS2 的类依赖 kotlin-stdlib，但它的 `mods.toml` 没有声明，必须作为运行时依赖。
- **Gradle 8.1.1 / ForgeGradle 6.0.54** — 构建链；只能跑在 JDK 17 上。
- **本地 Maven 镜像与代理** — 见 `context/setup.md`；不是运行时依赖，但缺了会构建失败。

## What Does NOT Exist Here

- **没有真实海水方块**：深海维度是空气，水下感由客户端雾/滤镜和 `UnderwaterSurvival` 提供；不要再往海里放 `Blocks.WATER`。
- **没有物理船持久化/世界加载认领**：服务器重启后已装配的 VS2 船不会被 `VsPilot` 找回，`ShipState`、guard cells、声纳代理和推进器都要重新建立。
- **没有多船支持**：`VsPilot` 是 `static` 单例，同一时刻只管理一艘物理船。
- **没有逐格水面几何**：舱室按 Y 层贪心拆成矩形棱柱；连海空腔按 1×1 竖直列发盒子，每列自己的水位由 `ShipPocketWater` 计算（相邻列最低共享格子 = 溢流口，多个隔断依次传递）。一个 Y 层内仍是整块矩形，不做逐格方块边界的流体表面。
- **没有物理船碰撞→船壳伤害**：VS2 2.4.11 没有暴露方便的 CollisionEvent 订阅入口；目前只有玩家左键、爆炸、方块船撞击会扣耐久。物理船撞地形仍是“刚体被挡住”，不掉血。
- **没有 T1/T2/T3 升级系统**：官方基础外壳都是 200；tier 只限制 `increasewallhealth` 升级等级（每级 +20%，T1 2 / T2 4 / T3 6）。`ShipState.hullHealthMultiplier` 已预留，但没有商店/升级 UI。
- **没有船壳耐久持久化**：`HullDamage` 只在运行时；世界存档或物理船拆回再装会重置。
- **没有服务端渲染**：所有画面都在 `client/`，服务端只发数据包。
