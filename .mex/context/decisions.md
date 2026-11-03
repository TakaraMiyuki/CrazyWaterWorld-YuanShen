---
name: decisions
description: Key architectural and technical decisions with reasoning. Load when making design choices or understanding why something is built a certain way.
triggers:
  - "why do we"
  - "why is it"
  - "decision"
  - "alternative"
  - "we chose"
edges:
  - target: context/architecture.md
    condition: when a decision relates to system structure
  - target: context/stack.md
    condition: when a decision relates to technology choice
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTV797VEN7Z57WW58775G4
  type: architecture
  status: promoted
  revision: 3
  title: decisions
  relations:
    - type: related_to
      target: mx_01M6FTBTA32ESJGHB433FWZZZ3
      note: when a decision relates to system structure
    - type: related_to
      target: mx_01M6FTBTC471WET2A2MK599QB1
      note: when a decision relates to technology choice
---

# Decisions

## Decision Log

<!-- mex:entity
id: mx_01M6FTV75HBWAHM5Q6J55E4EWR
type: decision
status: promoted
revision: 2
relations:
  - type: refines
    target: mx_01M6FTV797VEN7Z57WW58775G4
    note: Section of decisions in context/decisions.md
-->
### 用 MEX 接管项目协作

**Date:** 2026-11-03
**Status:** Active
**Decision:** 项目协作状态迁入仓库内 `.mex/` 项目记忆，由 MEX CLI、ZCode 技能和 Hub 管理。
**Reasoning:** 旧的纯文本交接无法检索、链接代码或校验漂移；MEX 提供 Wiki、代码图、Inbox、Relay、Statechart 和本地 GUI，记忆仍随 Git 共享。
**Alternatives considered:** 继续维护纯文本交接（放弃，缺少检索和漂移检查）；把记忆放到外部服务（放弃，破坏 Git 即传输的模型）。
**Consequences:** `.mex/`、`.zcode/skills`、`.zcode/commands` 和根 `AGENTS.md` 的 MEX 指令块成为协作入口；Git 提交/推送仍需要人类单独授权。

<!-- mex:entity
id: mx_01M6FTV71TC937899A7QJZE20R
type: decision
status: promoted
revision: 2
relations:
  - type: refines
    target: mx_01M6FTV797VEN7Z57WW58775G4
    note: Section of decisions in context/decisions.md
-->
### 深海海域改用空气而不是真实水方块

**Date:** 2026-11-03
**Status:** Active
**Decision:** `DeepSeaChunkGenerator` / `OutpostBuilder` / `TrunkNetwork` 生成空气海域；水下观感由客户端雾/滤镜和 `UnderwaterSurvival` 表现；船内进水继续用每舱标量水位。
**Reasoning:** 真实水会触发 VS2 实体区块碰撞异常，物理船装配后海水还会灌进船体；空气海域让船体、哨站和舱室保持真正的空气环境。
**Alternatives considered:** 继续用真实水并加装 `hull_plug` 堵水（放弃，异常仍会出现在其它位置，且堵水方块维护成本高）。
**Consequences:** 空气海域不生成真实水方块；舱内水面由 `ShipWaterRenderer` 客户端自绘（10 Hz packet + 逐帧插值，默认开启，`-Dbarotrauma.renderWater=false` 可关），破口坐标随包发送、客户端按破口与水面关系画水花/气泡，水面包同时用于干湿判断；`hull_plug` 方块/类/资源已删除；旧存档里已生成的真实水区块不会自动转换。

<!-- mex:entity
id: mx_01M6FTV6Y842Y7SVSJB6P8WDMX
type: decision
status: promoted
revision: 2
relations:
  - type: refines
    target: mx_01M6FTV797VEN7Z57WW58775G4
    note: Section of decisions in context/decisions.md
-->
### 船不是单一实体，物理船交给 VS2

**Date:** 2026-11-02
**Status:** Active
**Decision:** 整船方块逐格处理；装了 VS2 时把方块交给物理刚体，`ShipMover` / `ShipPhysics` 只服务非物理模式。
**Reasoning:** 单一实体无法保留逐方块破坏、舱室和机器交互；VS2 提供刚体、碰撞和船坞坐标，能直接复用。
**Alternatives considered:** 自己实现逐方块物理（放弃，工作量和稳定性都不可接受）；结构体船实体（`VesselEntity` 链路，未采用，现为死代码）。
**Consequences:** `VsPilot` 成为物理船的唯一静态管理器；VS2 船上交互需要 `sonar_proxy` 代理方块；同一时刻只支持一艘物理船。

<!-- mex:entity
id: mx_01M6FTV6T4FFTDQSWQKW423H7K
type: decision
status: promoted
revision: 2
relations:
  - type: refines
    target: mx_01M6FTV797VEN7Z57WW58775G4
    note: Section of decisions in context/decisions.md
-->
### 水不是流体模拟，而是每舱标量水位

**Date:** 2026-11-02
**Status:** Active
**Decision:** 每个舱室维护一个 `ShipSimulation` 水量标量；破口从 `openPockets` 缓慢灌水，`ShipWater` 只在需要时把水位铺成真实水方块。
**Reasoning:** 逐格流体模拟在 Minecraft 里性能和确定性都不可控，且 VS2 船上写流体会引发区块异常；标量模型足够表达“进水/抽水/压载”的玩法。
**Alternatives considered:** 原版流体 + 船体体积拦截（部分保留在 `ShipVolumeGuard`，但不再是主路径）；逐格自定义流体（放弃，复杂度过高）。
**Consequences:** 水面视觉按舱室真实格子拆成矩形棱柱，隔离舱不会被旁边连海空腔的包围盒盖住；客户端水面渲染默认开启（10 Hz packet + 逐帧插值）。

<!-- mex:entity
id: mx_01M6FTV6PJ74EP6JX37ERQV7DF
type: decision
status: promoted
revision: 2
relations:
  - type: refines
    target: mx_01M6FTV797VEN7Z57WW58775G4
    note: Section of decisions in context/decisions.md
-->
### 声纳台兼作船脑

**Date:** 2026-11-02
**Status:** Active
**Decision:** `SonarBlockEntity extends ShipCoreBlockEntity`，扫描、分舱、模拟、推进全部继承；独立的 `ship_core` 方块已合并删除。
**Reasoning:** 玩家在声纳台同时看声纳、操舵和船况，减少方块数量和交互路径。
**Alternatives considered:** 独立船核心 + 独立声纳台（放弃，两个界面和两套同步逻辑重复）。
**Consequences:** 声纳台必须存在于船上才能当船脑；物理船装配后原方块在船坞坐标里，需要 `sonar_proxy` 才能右键打开界面。

<!-- mex:entity
id: mx_01M6FTV6H84EX4GGA6ERWVG1QG
type: decision
status: promoted
revision: 2
relations:
  - type: refines
    target: mx_01M6FTV797VEN7Z57WW58775G4
    note: Section of decisions in context/decisions.md
-->
### VS2 不打包，用 `VsSupport` 隔离

**Date:** 2026-11-02
**Status:** Active
**Decision:** VS2 使用 `compileOnly` + `runtimeOnly`；`VsSupport` 只通过 `ModList.isLoaded("valkyrienskies")` 判断，不引用任何 VS2 类型。
**Reasoning:** 避免正式包内嵌 LGPL 代码和体积膨胀；未安装 VS2 时模组仍能启动并使用非物理船逻辑。
**Alternatives considered:** `implementation` 打包 VS2（放弃，分发和合规问题）；硬依赖（放弃，服务器可不装 VS2）。
**Consequences:** 所有 VS2 调用必须放在 `ship/vs/`；新增 VS2 类型引用时要检查类加载隔离。

