---
name: run-tests
description: Build and run the three verification layers (JUnit, GameTest, server smoke) on Windows with the project's JDK and proxy setup.
triggers:
  - "run tests"
  - "build failed"
  - "gametest"
  - "smoketest"
  - "JAVA_HOME"
edges:
  - target: context/setup.md
    condition: when the environment or proxy is not set up yet
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTV7PNBD1ZG84B1K5GCG1N
  type: pattern
  status: promoted
  revision: 2
  title: run-tests
  relations:
    - type: related_to
      target: mx_01M6FTBTBNY5R38MW70TVEXZH9
      note: when the environment or proxy is not set up yet
---

# Build and Run Tests

## Context

项目用三层验证：

- **L1**：`src/test/java` 的 JUnit 测试，纯逻辑，不需要启动游戏。
- **L2**：Forge GameTest，需要 `run/gameteststructures/*.snbt` 模板。
- **L3**：`runServer` + `run/.smoketest-vs` 标记，跑物理船装配/进水自检并自动关服。

所有 Gradle 命令都需要 JDK 17 的 `JAVA_HOME`；`gradle.properties` 只固定 Gradle 启动的 JVM，不替代 wrapper 的环境变量。

## Steps

1. 设置 JDK：
   ```bash
   export JAVA_HOME=/path/to/jdk-17
   ```
2. L1：
   ```bash
   ./gradlew test --offline
   ```
3. L2 前先生成模板（删过 `run/` 后必须重跑）：
   ```bash
   python tools/gen_gametest_structure.py
   ./gradlew runGameTestServer --offline
   ```
4. L3：
   ```bash
   touch run/.smoketest-vs
   ./gradlew runServer --offline
   rm run/.smoketest-vs
   ```
5. 客户端验证：
   ```bash
   ./gradlew runClient --offline
   ```

## Gotchas

- `JAVA_HOME is not set` 是 wrapper 层错误；仓库不设置 `org.gradle.java.home`，需要自己把 `JAVA_HOME` 指向 JDK 17。
- 依赖未缓存时不要加 `--offline`；先联网跑一次，再 offline。
- 改过 `DeepSeaChunkGenerator` / `OutpostBuilder` / `TrunkNetwork` 后必须删除 `run/world`，否则 L3 还是旧地形。
- L2 的模板必须放在 `run/gameteststructures/`，放 `src/main/resources` 不会被 1.20.1 GameTest 找到。
- `runServer` 首次会生成 `run/eula.txt`，需要改成 `eula=true`。
- 客户端工作目录是 `run-client/`，与服务端的 `run/` 分开；删存档时不要只删一个。
- 代理失效时先注释 `gradle.properties` 的 5 行 proxy 配置，或确认 `127.0.0.1:7899` 在运行。

## Verify

- [ ] L1 输出 26 tests passed
- [ ] L2 输出 `All 3 required tests passed :)`
- [ ] L3 日志出现 `[smoketest-vs]` 阶段输出并在结束时自动关服
- [ ] 客户端能进入主菜单并加载世界（L3 通过不代表客户端画面正确）

## Debug

构建失败先看 `JAVA_HOME` 和代理；依赖 404/超时看 `settings.gradle` 的 BMCLAPI/阿里云仓库顺序；GameTest 找不到结构看 `run/gameteststructures`；世界地形不对先删 `run/world`。
