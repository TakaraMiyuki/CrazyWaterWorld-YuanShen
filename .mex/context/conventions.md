---
name: conventions
description: How code is written in this project — naming, structure, patterns, and style. Load when writing new code or reviewing existing code.
triggers:
  - "convention"
  - "pattern"
  - "naming"
  - "style"
  - "how should I"
  - "what's the right way"
edges:
  - target: context/architecture.md
    condition: when a convention depends on understanding the system structure
  - target: context/decisions.md
    condition: when a rule exists because of an earlier design decision
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTBTB77P4PT8TV3HT78NGZ
  type: convention
  status: promoted
  revision: 3
  title: conventions
  relations:
    - type: related_to
      target: mx_01M6FTBTA32ESJGHB433FWZZZ3
      note: when a convention depends on understanding the system structure
    - type: related_to
      target: mx_01M6FTV797VEN7Z57WW58775G4
      note: when a rule exists because of an earlier design decision
---

# Conventions

## Naming

- **Java 包**：统一 `com.opamb.barotrauma.<层>`；层名用单数（`block`、`item`、`ship`、`network`），子域再分（`ship/vs`、`ship/water`）。
- **注册类**：一律 `Mod*` 前缀（`ModBlocks`、`ModItems`、`ModBlockEntities`、`ModShipBlocks`、`ModMenus`、`ModEntities`、`ModChunkGenerators`）。
- **注册 id**：`snake_case`，全小写，和资源路径一致（`submarine_plating`、`sonar_proxy`、`hull_glass`）。
- **网络包**：`XxxPacket`，数据用 `record` + `encode`/`decode` + `handle(Supplier<NetworkEvent.Context>)`。
- **VS2 边界**：所有直接引用 VS2 类型的类都放在 `ship/vs/`；其它包只能通过 `VsState`、`VsSupport`、`VsPilot` 的静态方法访问。
- **注释与日志**：项目注释和玩家可见文本以中文为主；日志用 `BarotraumaMod.LOGGER`，前缀 `[vs]`、`[water]`、`[smoketest]` 等便于过滤。

## Structure

- **服务端权威**：船体扫描、分舱、水量、推进、干湿判定都在服务端；客户端只发输入包、收状态包并渲染。
- **注册集中在入口**：所有 `DeferredRegister` 只在 `BarotraumaMod` 构造器里挂一次；不要在静态初始化块里注册。
- **船逻辑在 `ship/`**：几何（`ShipGeometry`）、模拟（`ShipSimulation`）、排水（`ShipWater`）、整船移动（`ShipMover`/`ShipPhysics`）分开；`ShipState` 只做聚合和对外 API。
- **物理船逻辑在 `ship/vs/`**：`VsPilot` 是唯一入口；`VsThruster` 只负责把 `ShipState` 的控制量转成 VS2 推力，`VsFloodSmoke` 只做自检。
- **事件在 `event/`**：命令注册、登录同步、自检标记、水下生存都放这里；`event/` 可以依赖 `world/`、`ship/`、`sonar/`，但 `ship/` 不反向依赖 `event/`。
- **客户端在 `client/`**：所有 `Dist.CLIENT` 事件和渲染类只在这里；通过 `DistExecutor.unsafeRunWhenOn` 从网络包进入，避免服务端类加载。
- **资源 id 与代码一致**：新增方块/物品时，blockstate、model、texture、lang、loot table、tag 的命名必须和注册 id 完全相同。

## Patterns

### 1. 新内容注册（方块/物品）

```java
// ModShipBlocks 或 ModBlocks 内
public static final RegistryObject<Block> MY_BLOCK = BLOCKS.register("my_block",
        () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)));

public static void register(IEventBus modBus) {
    BLOCKS.register(modBus);
}
```

同时在 `src/main/resources` 下补对应资源（以 `submarine_plating` 为例）：`assets/barotrauma/blockstates/submarine_plating.json`、`assets/barotrauma/models/block/submarine_plating.json`、`assets/barotrauma/textures/block/submarine_plating.png`、`assets/barotrauma/lang/en_us.json` 和 `lang/zh_cn.json`；如果是船体方块，还要确保 `ModShipBlocks.isShipBlock()` 能识别它。

### 2. 网络包

```java
public record MyPacket(int value) {
    public void encode(FriendlyByteBuf buf) { buf.writeVarInt(value); }
    public static MyPacket decode(FriendlyByteBuf buf) { return new MyPacket(buf.readVarInt()); }
    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> { /* 主线程逻辑 */ });
        ctx.setPacketHandled(true);
    }
}
```

注册到 `ModNetwork.CHANNEL`，服务端→客户端用 `ModNetwork.sendToPlayer`，客户端→服务端用 `ModNetwork.sendToServer`。

### 3. 干湿判定

不要在业务代码里直接读 `FluidState` 判断水下。统一调用 `OceanEnvironment.isSubmerged(level, pos)`；船内干燥区由 `VsPilot`（服务端）和 `ShipWaterRenderer`（客户端）注册到 `OceanEnvironment.setDryZone()`。

### 4. 物理船状态访问

需要船状态时先判断 `VsSupport.available() && VsPilot.hasShip()`，再取 `VsPilot.state()`；不要在其它类里缓存 `ShipState` 或 `ServerLevel`，因为 `VsPilot.resetForServerLifecycle()` 会清空所有静态状态。

## Verify Checklist

Before presenting any code:

- [ ] 构建通过：`./gradlew build --offline`（先设置 `JAVA_HOME` 到 JDK 17）
- [ ] L1 通过：`./gradlew test --offline`
- [ ] 改了世界生成 → 删除 `run/world` 再启动，否则旧区块不会重新生成
- [ ] 改了实体/方块/网络注册 → 跑 L2 `./gradlew runGameTestServer --offline`
- [ ] 新增客户端类 → 确认没有在服务端公共路径直接引用，必要时用 `DistExecutor`
- [ ] 新增 VS2 调用 → 确认类在 `ship/vs/` 内，且未安装 VS2 时不会类加载崩溃
- [ ] 新增船体方块 → 确认 `ModShipBlocks.isShipBlock()` 覆盖，且几何/分舱逻辑能识别
- [ ] 玩家可见文本 → 同时更新 `en_us.json` 与 `zh_cn.json`（如适用）
