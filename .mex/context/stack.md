---
name: stack
description: Technology stack, library choices, and the reasoning behind them. Load when working with specific technologies or making decisions about libraries and tools.
triggers:
  - "library"
  - "package"
  - "dependency"
  - "which tool"
  - "technology"
edges:
  - target: context/decisions.md
    condition: when the reasoning behind a tech choice is needed
  - target: context/conventions.md
    condition: when understanding how to use a technology in this codebase
  - target: context/setup.md
    condition: when the toolchain or environment must be installed
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTBTC471WET2A2MK599QB1
  type: architecture
  status: promoted
  revision: 4
  title: stack
  relations:
    - type: related_to
      target: mx_01M6FTV797VEN7Z57WW58775G4
      note: when the reasoning behind a tech choice is needed
    - type: related_to
      target: mx_01M6FTBTB77P4PT8TV3HT78NGZ
      note: when understanding how to use a technology in this codebase
    - type: related_to
      target: mx_01M6FTBTBNY5R38MW70TVEXZH9
      note: when the toolchain or environment must be installed
---

# Stack

## Core Technologies

- **Java 17** — 唯一可用的编译目标；Forge 1.20.1 在 JDK 21 上不能直接构建。
- **Minecraft 1.20.1 + Forge 47.4.0** — 模组平台；`mods.toml`、事件总线、`DeferredRegister`、网络 `SimpleChannel` 都是 Forge 47 的 API。
- **ForgeGradle 6.0.54 + Gradle 8.1.1** — 构建与运行配置；映射使用 `official` 1.20.1。
- **Valkyrien Skies 2（VS2）2.4.11** — 物理船刚体与船坞坐标；不是硬依赖，未安装时 `VsSupport.available()` 返回 false。
- **Kotlin for Forge 4.12.0** — VS2 运行时的隐式依赖，`runtimeOnly` 引入。

## Key Libraries

- **VS2（`compileOnly` + `runtimeOnly`）** — 正式 jar 不内嵌；所有 VS2 类型只能出现在 `ship/vs/` 内，其它包通过 `VsState` 的静态字段和 `VsSupport` 判断访问。
- **JUnit Jupiter 5.10.2** — L1 纯逻辑测试，跑 `gradlew test`，不需要启动游戏。
- **Forge GameTest** — L2 游戏内测试，`gradlew runGameTestServer`；测试结构模板放在 `run/gameteststructures/`，不是 mod 资源。
- **Gson / Minecraft 自带 Codec** — 网络与序列化优先用 MC 的 `FriendlyByteBuf` / `CompoundTag` / Codec，不额外引入 JSON 库。
- **Pillow（Python 侧工具）** — `tools/gen_textures.py` 用 Pillow 生成占位贴图；仅开发期使用，不进入模组运行时。

## What We Deliberately Do NOT Use

- **不打包 VS2** — 避免分发体积和 LGPL 合规问题；用 `VsSupport` 做类加载隔离，未安装时模组仍能启动。
- **不用真实流体方块模拟海水** — 空气海域 + 标量水位；真实水会触发 VS2 实体区块碰撞异常，也会灌进船体。
- **不在公共代码里引用客户端类** — `client/` 只在 `Dist.CLIENT` 事件或 `DistExecutor.unsafeRunWhenOn` 中加载。
- **不引入数据库/外部服务** — 项目没有持久化后端；船状态目前只在内存中。
- **不做自动 Git 提交/推送** — 开发和交接的 Git 操作需要人类明确授权。

## Version Constraints

- **只能用 JDK 17**：`gradle.properties` 用 `org.gradle.java.home` 固定本机 JDK 17 路径；系统 `JAVA_HOME` 指向 21 时构建会失败。
- **Gradle 8.1.1 与 ForgeGradle 6 绑定**：不要升级 wrapper，ForgeGradle 6 不支持更高版本。
- **VS2 2.4.11 不做方块交互坐标重映射**：物理船上必须用 `sonar_proxy` 代理方块，不能直接右键船坞里的声纳原方块。
- **1 格 = 1 米**：深海维度高度、深度 HUD 和 HUD 米数换算都按这个比例。
