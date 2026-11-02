# 架构

> 最后更新：2026-11-02 · opamber258

## 分层

| 层 | 路径 | 职责 | 可以依赖 |
|---|---|---|---|
| 入口 | `BarotraumaMod.java` | `@Mod` 入口；构造器里挂 8 个 `DeferredRegister` 和网络频道 | 注册 |
| 注册 | `registry/` | 方块、物品、方块实体、菜单、实体、维度生成器 | 内容、世界 |
| 内容 | `block/`、`blockentity/`、`item/`、`entity/`、`menu/` | 声纳台（兼船脑）、船体方块、交互锚点、物品、菜单 | 船舶、注册、菜单、声纳 |
| 船舶 | `ship/`、`ship/vs/`、`ship/water/` | 扫描分舱、数值模拟、水位、整船平移、VS2 桥接、船体流体拦截 | MC、VS2、少量网络 DTO |
| 网络 | `network/` | 5 个网络包：船控、水面包、声纳请求/回包、船体调色板 | 船舶、声纳、注册；客户端仅靠 Dist 隔离 |
| 客户端 | `client/` | 声纳界面与全息、深度 HUD、船内水面、船体渲染、客户端缓存 | 网络、声纳、世界、注册 |
| 世界 | `world/` | 深海维度、噪声地形、哨站、初始潜艇 | 注册、船舶 VS2 |
| 声纳 | `sonar/` | 服务端点云扫描、客户端缓存 | 仅 MC 数据类型 |
| Mixin | `mixin/` | 拦截 VS2 流体与 MC 破方块路径 | 船舶 |
| 测试 | `src/test/java`、`gametest/` | 几何/物理/进水单测、3 个游戏测试 | 船舶 |

## 依赖方向

- 入口只依赖注册；注册依赖内容与世界。
- 注册与世界存在包级双向引用（`ModChunkGenerators` → `DeepSeaChunkGenerator`，`StarterSub` → `ModShipBlocks`），但具体类之间无环。
- 内容依赖船舶；船舶不依赖客户端。
- 客户端与网络互相引用（界面发请求包、网络下发数据）。唯一危险方向 `network/VesselPalettePacket` → `client/ClientVesselCache` 用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 隔离，避免服务端加载客户端类。
- `ship/ShipWaterStateBuilder` 直接构造 `network/ShipWaterStatePacket`，是船舶 → 网络的一处直接依赖（见技术债）。
- `ship/vs/VsSupport` 不引用任何 VS2 类型，只做 `ModList.isLoaded("valkyrienskies")` 判断，保证未装 VS2 时类加载不炸。

## 关键决策

- **船不是单一实体**：整船方块逐格平移；VS2 模式下把方块交给物理刚体，`ShipMover` / `ShipPhysics` 只处理非物理模式。
- **水不是流体模拟**：每个舱室一个标量水位，`ShipWater` 按 Y 从底往上铺真实水方块；`ShipVolumeGuard` 拦截船只体积内的真实海水，破口通过 `openPockets` 缓慢灌水。客户端水面由 `ShipWaterStateBuilder` 生成包围盒 + 水面高度，`ShipWaterRenderer` 自绘。
- **声纳台兼船脑**：`SonarBlockEntity extends ShipCoreBlockEntity`，扫描、分舱、模拟、推进全部继承；独立的 `ship_core` 方块已在 2026-11-02 合并删除。
- **VS2 不打包**：`compileOnly` + `runtimeOnly`，正式包不内嵌 VS2；`VsSupport` 负责类加载隔离。
- **VS2 船上交互用代理方块**：VS2 2.4.11 不做方块交互的坐标重映射，装配后在声纳原位置放不可见的 `sonar_proxy`，跟着船整格移动，右键它打开声纳界面。
- **深海维度**：1 格 = 1 米，y -1..-1000 是水柱；地形是实心岩挖出的水腔，水占 15%–20%。改了生成器必须删 `run/world`。
- **详细实现**见 `docs/IMPLEMENTATION.md`；该文档写于更早版本，与当前代码冲突时以代码为准。

## 已知技术债

- 船内水面的水平范围仍是 bbox + surface 近似，非矩形舱室不精确；`ShipWaterStateBuilder` 已按 Y 逐层实际格数累计高度，但没有逐格几何。
- 三套坐标并存：世界坐标、VS2 船坞坐标、渲染坐标。HUD 深度用玩家实体原始 Y，和船渲染位置可能相差一个 delta（`HANDOFF.md` 记录过 57 格差）；破口、方块实体、客户端各读各的坐标。
- 客户端水面渲染的坐标/拓扑/深度修复默认开启，但尚未在游戏内完成视觉验收；`-Dbarotrauma.waterFix=false` 回退，F9 或 `-Dbarotrauma.waterDebug=true` 开诊断。
- `ShipAssembler` + `VesselEntity` + `VesselPalettePacket` + `VesselRenderer` 的结构体船链路没有调用方，是 M4 遗留死代码（以当前工作树为准）。
- 文档漂移：`docs/IMPLEMENTATION.md` 仍描述已删除的 `ShipCoreBlock` / `ShipDoorBlock` / `ShipConsole*` 和五台机器；后续改架构时需同步更新该文档。
- 世界生成改动必须删 `run/world`；无头自检依赖 `run/.smoketest` 标记，这是流程性债务。
