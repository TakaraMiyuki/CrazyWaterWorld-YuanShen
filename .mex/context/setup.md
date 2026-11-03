---
name: setup
description: Dev environment setup and commands. Load when setting up the project for the first time or when environment issues arise.
triggers:
  - "setup"
  - "install"
  - "environment"
  - "getting started"
  - "how do I run"
  - "local development"
edges:
  - target: context/stack.md
    condition: when specific technology versions or library details are needed
  - target: context/architecture.md
    condition: when understanding how components connect during setup
  - target: patterns/run-tests.md
    condition: when the task is to build or run the test layers
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTBTBNY5R38MW70TVEXZH9
  type: guide
  status: promoted
  revision: 4
  title: setup
  relations:
    - type: related_to
      target: mx_01M6FTBTC471WET2A2MK599QB1
      note: when specific technology versions or library details are needed
    - type: related_to
      target: mx_01M6FTBTA32ESJGHB433FWZZZ3
      note: when understanding how components connect during setup
    - type: related_to
      target: mx_01M6FTV7PNBD1ZG84B1K5GCG1N
      note: when the task is to build or run the test layers
---

# Setup

## Prerequisites

- **JDK 17** — 本机路径 `C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot`，已写入 `gradle.properties` 的 `org.gradle.java.home`；不要用 JDK 21。
- **Git** — 仓库根为 `D:\zcodeplayground\barotrauma-mc-mod`。
- **Windows + PowerShell/Git Bash** — 命令示例以 Gradle wrapper 为准。
- **可选：本地代理** — 依赖下载走 `127.0.0.1:7899`（自动代理）；直连卸载代理时注释 `gradle.properties` 里的 5 行 `systemProp.*.proxy*`。
- **可选：Python + Pillow** — 只有重新生成占位贴图时需要。

## First-time Setup

1. `git clone https://github.com/opAmber258/barotrauma-mc-mod.git`
2. 进入仓库，确认 `gradle.properties` 里的 JDK 17 路径适用于本机（换机器时修改 `org.gradle.java.home`）。
3. 设置 `JAVA_HOME`（Gradle wrapper 自身需要）：
   ```bash
   export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
   ```
4. 首次构建：`./gradlew build --offline`（依赖已缓存时）；没有缓存就去掉 `--offline`，走 BMCLAPI / 阿里云镜像。
5. 运行客户端：`./gradlew runClient --offline`。
6. 运行服务端前，先启动一次生成 `run/eula.txt`，把 `eula=false` 改成 `eula=true`，再 `./gradlew runServer --offline`。
7. 世界生成规则改动后，先删除 `run/world`（以及 `run-client` 下对应存档）再启动。

## Environment Variables

- `JAVA_HOME`（构建时必须）— 指向 JDK 17；Gradle wrapper 用它启动 JVM。
- `systemProp.http.proxyHost` / `http.proxyPort` / `https.proxyHost` / `https.proxyPort`（可选，已写在 `gradle.properties`）— 本机自动代理 `127.0.0.1:7899`。
- `systemProp.http.nonProxyHosts`（可选）— `localhost|127.*|[::1]`。
- 没有需要提交的密钥或服务端环境变量；这个模组不连接外部数据库或 API。

## Common Commands

- `./gradlew build --offline` — 编译并打包，产物 `build/libs/barotrauma-0.1.0.jar`。
- `./gradlew test --offline` — L1 纯逻辑测试，当前 26 个用例。
- `./gradlew runGameTestServer --offline` — L2 Forge GameTest，需先有 `run/gameteststructures/*.snbt`（`python tools/gen_gametest_structure.py`）。
- `./gradlew runServer --offline` — 专用服务端；在 `run/.smoketest-vs` 存在时跑物理船进水自检并自动关服。
- `./gradlew runClient --offline` — 开发客户端，用 `run-client/` 作为工作目录。
- `python tools/gen_textures.py` — 重新生成 16x16 占位贴图和 128x128 模组图标。
- `python tools/gen_gametest_structure.py` — 重新生成 GameTest 空结构模板到 `run/gameteststructures/`。

## Common Issues

**`JAVA_HOME is not set` 或 Gradle 启动到 JDK 21：** 在命令前显式 `export JAVA_HOME=...`；`gradle.properties` 里的 `org.gradle.java.home` 只影响 Gradle 启动的 JVM，不替代 wrapper 的 `JAVA_HOME`。
**Forge/Maven 下载超时或 `zip END header not found`：** 确认 `settings.gradle` 和 `build.gradle` 的 BMCLAPI 仓库在最前面；必要时删掉损坏的缓存文件重下。
**`run/world` 里还是旧地形：** 世界生成器改动不会回填已生成区块；删除 `run/world` 后重新创建世界。
**VS2 未安装或类加载崩溃：** 确认模组只在 `ship/vs/` 引用 VS2 类型，`VsSupport.available()` 为 false 时走非物理船路径。
**客户端看不到船内水面：** 当前 `ShipWaterRenderer.RENDER_WATER = false`，水面绘制被有意关闭；水面包只用于 `OceanEnvironment` 的干湿判定，不是渲染故障。
