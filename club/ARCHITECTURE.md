# 架构

> 最后更新：2026-11-03 · opamber258

## 分层

| 层 | 路径 | 职责 | 可以依赖 |
|---|---|---|---|
| 入口 | `BarotraumaMod.java` | `@Mod` 入口；构造器里挂 8 个 `DeferredRegister` 和网络频道 | 注册 |
| 注册 | `registry/` | 方块、物品、方块实体、菜单、实体、维度生成器 | 内容、世界 |
| 内容 | `block/`、`blockentity/`、`item/`、`entity/`、`menu/` | 声纳台（兼船脑）、船体方块、交互锚点、物品、菜单 | 船舶、注册、菜单、声纳 |
| 船舶 | `ship/`、`ship/vs/`、`ship/water/` | 扫描分舱、数值模拟、水位、整船平移、VS2 桥接、船体流体拦截 | MC、VS2、少量网络 DTO |
| 网络 | `network/` | 5 个网络包：船控、水面包、声纳请求/回包、船体调色板 | 船舶、声纳、注册；客户端仅靠 Dist 隔离 |
| 事件 | `event/` | 登录/命令/自检、水下呼吸与溺水 | 世界、船舶、声纳 |
| 客户端 | `client/` | 声纳界面与全息、深度 HUD、水下雾/滤镜、游泳、船体渲染、客户端缓存 | 网络、声纳、世界、注册 |
| 世界 | `world/` | 深海维度、噪声地形、空气海域判定、哨站、初始潜艇 | 注册、船舶 VS2 |
| 声纳 | `sonar/` | 服务端点云扫描、客户端缓存 | 仅 MC 数据类型 |
| Mixin | `mixin/` | 拦截 VS2 流体与 MC 破方块路径 | 船舶 |
| 测试 | `src/test/java`、`gametest/` | 几何/物理/进水单测、3 个游戏测试 | 船舶 |

## 依赖方向

- 入口只依赖注册；注册依赖内容与世界。
- 注册与世界存在包级双向引用（`ModChunkGenerators` → `DeepSeaChunkGenerator`，`StarterSub` → `ModShipBlocks`），但具体类之间无环。
- 内容依赖船舶；船舶不依赖客户端。
- 客户端与网络互相引用（界面发请求包、网络下发数据）。唯一危险方向 `network/VesselPalettePacket` → `client/ClientVesselCache` 用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 隔离，避免服务端加载客户端类。
- 事件层把世界、船舶、VS2、声纳粘起来；`ship/` 本身不依赖 `event/`。
- `ship/ShipWaterStateBuilder` 直接构造 `network/ShipWaterStatePacket`，是船舶 → 网络的一处直接依赖（见技术债）。
- `ship/vs/VsSupport` 不引用任何 VS2 类型，只做 `ModList.isLoaded("valkyrienskies")` 判断，保证未装 VS2 时类加载不炸。

## 关键决策

- **船不是单一实体**：整船方块逐格平移；VS2 模式下把方块交给物理刚体，`ShipMover` / `ShipPhysics` 只处理非物理模式。
- **深海是空气海域，不是真实水**：`DeepSeaChunkGenerator` / `OutpostBuilder` / `TrunkNetwork` 生成空气而不是水；水下观感由客户端 `UnderwaterEffects`（雾/滤镜）和 `UnderwaterMovement`（水阻/上浮）表现；服务端 `UnderwaterSurvival` 负责氧气/溺水。
- **船内进水是标量水位**：每个舱室一个数值，`ShipWater` 仍可把水位铺成真实水方块；但 `ShipWaterRenderer` 在空气海域方案下 `RENDER_WATER = false`，不再绘制外部水面，只保留水面包给 `OceanEnvironment` 做干湿判断。`hull_plug` 方块/类/资源已删除。
- **干湿判定集中在 `OceanEnvironment`**：维度高度 + 哨站盒子 + 船内 `DryZone`（服务端 `VsPilot`、客户端 `ShipWaterRenderer` 注册）三层判断，服务端和客户端都不再依赖 `FluidState`。
- **声纳台兼船脑**：`SonarBlockEntity extends ShipCoreBlockEntity`，扫描、分舱、模拟、推进全部继承；独立的 `ship_core` 方块已在 2026-11-02 合并删除。
- **VS2 不打包**：`compileOnly` + `runtimeOnly`，正式包不内嵌 VS2；`VsSupport` 负责类加载隔离。
- **VS2 船上交互用代理方块**：VS2 2.4.11 不做方块交互的坐标重映射，装配后在声纳原位置放不可见的 `sonar_proxy`，跟着船整格移动，右键它打开声纳界面。
- **深海维度**：1 格 = 1 米，y -1..-1000 是空气海域；地形是实心岩挖出的空腔。改了生成器必须删 `run/world`。
- **详细实现**见 `docs/IMPLEMENTATION.md`；该文档写于空气海域改造前，水/世界生成部分已过时，与当前代码冲突时以代码为准。

## 已知技术债

- 船内水面的水平范围仍是 bbox + surface 近似，非矩形舱室不精确；`ShipWaterStateBuilder` 已按 Y 逐层实际格数累计高度，但没有逐格几何。
- 三套坐标并存：世界坐标、VS2 船坞坐标、渲染坐标。HUD 深度用玩家实体原始 Y，和船渲染位置可能相差一个 delta（`HANDOFF.md` 记录过 57 格差）；破口、方块实体、客户端各读各的坐标。
- `ShipWaterRenderer` 当前 `RENDER_WATER = false`，水面不再绘制；水面包只用于干湿判断。旧的坐标/拓扑修复和 F9 诊断仍在代码里，但不再是当前视觉路径，水下观感需要按雾/滤镜重新验收。
- `OceanEnvironment.dryZone` 是单个静态字段，客户端 `ShipWaterRenderer` 和服务端 `VsPilot` 都会注册；集成服务器同 JVM 下会互相覆盖，单人游戏可能出现干湿判定抖动。当前未复现，但属于设计债。
- `hull_plug` 已删除；旧存档里若还残留 `barotrauma:hull_plug`，加载时可能被当成缺失方块，需要兼容时单独做迁移。
- 空气海域只影响新生成区块；旧存档已生成的真实水区块不会自动转换。
- `ShipAssembler` + `VesselEntity` + `VesselPalettePacket` + `VesselRenderer` 的结构体船链路没有调用方，是 M4 遗留死代码（以当前工作树为准）。
- 文档漂移：`docs/IMPLEMENTATION.md` 仍描述已删除的 `ShipCoreBlock` / `ShipDoorBlock` / `ShipConsole*` 和五台机器；后续改架构时需同步更新该文档。
- 世界生成改动必须删 `run/world`；无头自检依赖 `run/.smoketest` 标记，这是流程性债务。
