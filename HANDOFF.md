> **2026-11-03 起，当前交接以 `club/latest-opamber258.md` 为准**（Club v1 协议）。
> 本文档保留为历史记录；空气海域方案已取代这里的水面渲染结论，最新状态看 `STATUS.md` 顶部与 `PLAN.md` §10.55。

# 交接文档：船内水面"根本没水"（2026-11-02 14:20）

> 状态（2026-11-02 18:30 更新）：已按本轮施工单完成 **第 0 步诊断** + **独立最小修复补丁**；
> 服务端模拟/破口/水量同步链路未改。修复默认启用，可用 `-Dbarotrauma.waterFix=false` 回退。
> **客户端画面尚未在本会话做视觉验证**（见 §0.5.4/§0.5.5）；不能仅凭编译/L1/L2 宣称"画面已好"。
> 下面第 4/5/6 节是上一版的历史结论，**其中 P0/P1/P2 的判断已被 §0.5 纠正**；原文保留，便于对照。

---

## 0.5 本轮更新（纠正上一版 §4/§5 的结论）

### 0.5.1 上游来源核查（先查再改）

- **Forge 47.4.0（本机实际版本）**：`gradle.properties` 里 `forge_version=47.4.0`。读取本机实际源码 jar
  `C:\Users\opAmb\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.4.0_mapped_official_1.20.1\forge-1.20.1-47.4.0_mapped_official_1.20.1-sources.jar`：
  - `GameRenderer.renderLevel` 只给 level 的 PoseStack 乘相机旋转，**没有** `translate(-camera)`；
    见 `GameRenderer.java:1119-1126`。
  - `LevelRenderer.renderChunkLayer` 画完一层后把**同一个 PoseStack** 交给
    `RenderLevelStageEvent`；见 `LevelRenderer.java:1559`。
  - `RenderType.DEBUG_FILLED_BOX` 是 `VertexFormat.Mode.TRIANGLE_STRIP`（`RenderType.java:141`）；
    `DEBUG_QUADS` 是 `QUADS + NO_CULL + TRANSLUCENT_TRANSPARENCY`（`RenderType.java:142`）。
  - `RenderType.end` 会调用 `setupRenderState()`（`RenderType.java:426-434`），所以 `endBatch` 之前
    手动 `disableCull/depthMask(false)` 会被 RenderType 的 state shard 覆盖；`debugFilledBox` 的默认
    `depthTest=LEQUAL`、`writeMask=COLOR_DEPTH_WRITE`（`CompositeStateBuilder` 默认值）。
- **VS2（本机实际 2.4.11）**：`gradle.properties` 是 `1.20.1-forge-2.4.11`；反编译该 jar 的
  `org.valkyrienskies.mod.common.VSClientGameUtils.transformRenderWithShip`，字节码明确是
  `translate(-camX,-camY,-camZ) * shipToWorld * translate(offsetX,offsetY,offsetZ)`。
  父目录 `_vs2src` 的 HEAD 是 `a79995c`（`1.20.1/main`，提交 "Add dev wiki to README (#1930)"），
  **不等于 2.4.11 tag**，所以以 2.4.11 jar 字节码为准；`_vs2src` 的同名源码
  （`common/src/main/kotlin/org/valkyrienskies/mod/common/VSClientGameUtils.kt:46-66`）与 jar 一致。
- **网络**：`Invoke-WebRequest` 经本地代理成功取到 Forge 1.20.x 的 `LevelRenderer.java.patch`
  （HTTP 200，15929 字节），作为本地源码旁证；其余结论不依赖网络。

### 0.5.2 被纠正的结论（重点）

1. **P0 坐标空间：确认成立，不是"嫌疑"。** 事件 PoseStack 只有相机旋转、没有相机平移；
   `ShipWaterRenderer` 之前提交绝对世界坐标，等于把相机位置当成了原点。修复：顶点先经
   `renderShipToWorld`（绕装配锚点旋转 + 平移到当前 render 位置）再减相机位置。
2. **P2 不是"次要嫌疑"：`debugFilledBox` 的 TRIANGLE_STRIP + CULL 是第二个独立 bug。**
   水面按网格/侧面提交的是多个独立 quad，TRIANGLE_STRIP 会把相邻 quad 连错；
   而且 `debugFilledBox` 默认 CULL=开、写深度，玩家在满水舱内从下往上看时水面是背面，会被剔除。
   `RenderSystem.disableCull()` 在 `endBatch` 之前调用无效（被 `setupRenderState` 覆盖）。
3. **HUD 183 m 与船 render -126.5 的 58 m 差，不能证明"船/水错位"。**
   HUD 用 `-player.blockPosition().getY()`（实体原始坐标），VS2 船上玩家实体坐标和相机/船渲染坐标
   可以不同；上一版没有同帧 HUD 证据。修复不依赖"相机 = 船质心"。
4. **`guardCells` 的 673/125 是不同船体的并集，不能拿来声称"破口必受/不受保护"。**
   必须查实际格成员；本轮没有改 guard 逻辑，也没有据此下结论。
5. **"pose 平移≈0"不是充分证据。** 本轮诊断同时记录 CPU 事件 PoseStack、事件投影、
   `RenderSystem.getModelViewMatrix()`/`getProjectionMatrix()` 全矩阵，并用实际 `renderTransform`
   对第一个水面盒 min 角做 `shipToWorld` vs "仅 delta" 的映射对比；不盲换 stage。
6. **第一人称满舱看不到自由面不一定是"没水"。** 满舱时相机在水下、水面在头顶；
   `debugFilledBox` 的背面剔除会让它消失。改用 `NO_CULL` 后仍需视觉确认。

### 0.5.3 本轮实现（两个独立部分）

- `client/ShipWaterRenderer.java`
  - 诊断默认关闭：`-Dbarotrauma.waterDebug=true` / 环境变量 `BAROTRAUMA_WATER_DEBUG=1` / 游戏内 F9；
    见 `:67`、`:125`。
  - 限频 `[water-diag]`（1 Hz；矩阵 5 s）：camera/eye/player/blockY/cameraEntity/partialTick、
    ship render/current/prev 完整变换、anchor/delta、水面盒、CPU/GPU 矩阵、顶点数、flush/stage/type；
    见 `:615-743`。
  - 诊断标记：视线前 2 m 三个分离 0.2 m 测试面；现有管线把三个面放进同一个
    `debugFilledBox`（TRIANGLE_STRIP，会看到面之间的连接三角形），诊断管线是三个独立 QUADS
    ——这就是拓扑对照；另有同位置深度测试开/关对照 + 一个"绝对世界坐标"对照面；独立
    `BufferBuilder`，不污染主水路径；见 `:479-614`。不新增世界方块。
  - 第 0 步原水体行为保留在 `drawWaterLegacy`（`:240-324`）；`-Dbarotrauma.waterFix=false` 可回退。
  - 独立修复补丁 `drawWaterFixed`（`:326-457`）：`debugQuads`（QUADS + NO_CULL + TRANSLUCENT，
    depthTest=LEQUAL、写深度）+ 相机相对坐标；不再手动 `disableCull/depthMask`。
    主水面改用**独立** `BufferBuilder`（`:347-349`），不再和 `mc.renderBuffers().bufferSource()`
    里的声纳/其它 `debugQuads` 共用批；`endBatch` 外层显式 setIdentity + 0.99975586 缩放
    （`:415-427`），复刻 `VIEW_OFFSET_Z_LAYERING`，降低与船壳共面的 z-fighting。
    诊断 raw QUADS 初版把事件 pose 既烘进顶点又乘进 modelview，复核发现会旋转两次；
    已改为顶点只存相机相对坐标、pose 只经 modelview 应用一次（`:566-590`）。
- `ship/ShipWaterStateBuilder.java:39-86`
  - 水面高度改为按 Y **逐层实际格数**累计，不再用 bbox 底面积；0/部分/满/超量都 clamp。
    协议仍是 bbox + surface（水平范围仍近似），没有把 bbox 当成最终真实水面。
- 新增 `src/test/java/com/opamb/barotrauma/ship/ShipWaterStateBuilderTest.java`：7 个用例
  （0 / 部分跨层 / 满 / 超量 / 矩形回归 / 连海空腔）。

### 0.5.4 验证（本轮实际跑过的命令）

| 命令 | 结果 |
| --- | --- |
| `./gradlew test --offline` | 退出码 0，26 PASSED（原 19 + 新 7） |
| `./gradlew build --offline` | 退出码 0，BUILD SUCCESSFUL；`build/libs/barotrauma-0.1.0.jar` |
| `./gradlew runGameTestServer --offline` | 退出码 0，`All 3 required tests passed :)` |
| `run/.smoketest-vs` + `./gradlew runServer --offline` | 退出码 0；阶段2 装配后 40 tick 水=0 空气=124；破口 40 tick 后连海 1 处、模拟进水 2800 单位；舱内实际水方块 0；拆回后水=0 |
| `./gradlew runClient --offline`（仅一次） | 日志进入主菜单加载阶段（`Sound engine started` / atlas 完成，日志有 `Auto-subscribing ...ShipWaterRenderer to FORGE`）；**没有加载世界，没有视觉验证**；之后已停止该客户端 |

- 复核修正（诊断 raw 双重旋转/独立 buffer/z-fight 缩放）后，只重跑了 `test`（26 PASSED）和
  `build`（BUILD SUCCESSFUL）；L2/smoke 未重跑（只改客户端，服务端代码未动）。
- 客户端启动任务是被强制停止的，Gradle 日志末尾是 **BUILD FAILED / exit -1**，不是通过；
  它只能证明日志进到了主菜单加载阶段，不能算客户端检查通过。
- 客户端启动日志里有一条 VS2 自带的 `MixinVibrationSystemTicker` 非致命 mixin 错误
  （dev 环境既有，未归因本轮改动）。
- 工作树仍有 **101 个既有未提交条目**（35 M / 40 D / 26 ??）；`build/_baseline_20261102/`
  是定向基线（本轮要改的 5 个文件 + 1 份旧客户端日志），**不是整树快照**。
  上一版结果里“git status 只显示本轮改动”的说法不准确，已在此纠正。
- computer-use 技能明确 "Main agent only"，本会话是子代理，未使用 GUI 自动化；
  因此**没有进世界看水面**，也没有截图。

### 0.5.5 仍未解决 / 下一步

- **画面是否真的出现水仍未验证**；需要主代理/用户进游戏看。建议步骤：
  新世界 → `/tp` 到初始潜艇附近（核心约 `(1,-71,20)`）→ `/barotrauma ship vs launch` → 按 F9 →
  `/barotrauma ship flood 1500`（或砸破口等进水）→ 看舱内水面；同时看 `[water-diag]`。
- 若仍不可见：先看 `[water-diag]` 的 `main: fixEnabled/type/mode/quads/flush` 和
  `camera/eye/anchor/renderPos`，再用三个测试面的可见性区分坐标/拓扑/深度/遮挡。
- bbox 水面在非矩形舱室的水平范围仍是近似；要"最终真实水面"得改协议/逐格几何，本轮未做。
- HUD 深度、VS2 玩家实体坐标、服务端模拟均未改。
- **没有 `[water-diag]` 运行输出**：唯一一次客户端没加载世界，诊断代码从未在游戏里执行；
  最终渲染器（18:43 之后）也没有客户端类加载/世界内验证。
- 复核修正后的 `test`/`build` 已重跑通过；L2/smoke 没有重跑（只改客户端，服务端代码未动）。

---

## 1. 当前构建 / 验证

- 项目：`D:\ZCode work\default-workspace\barotrauma`
- 构建：`.\gradlew.bat build --offline` ✅（2026-11-02 14:07 左右）
- L1：`.\gradlew.bat test --offline` ✅ 19 用例
- L2：`.\gradlew.bat runGameTestServer --offline` ✅ `All 3 required tests passed :)`
- L3 隔离自检：`run/.smoketest-vs` ✅（详见第 4 节）
- 客户端：`.\gradlew.bat runClient --offline`，最新一次测试在 14:15–14:16。

> 注意：之前为了加载新构建连续重启了三次客户端，用户看到过三个窗口。
> 以后一次只起一个 `runClient`，旧窗口确认关掉再起下一个。

---

## 2. 用户看到的现象（最终测试）

- 船在深海，玩家在舱内（截图里 HUD 显示"深度 183 m"）。
- 玩家左键砸出破口，聊天栏出现 `船体破损！`，破口处能看到船外。
- **舱内完全没有水**，不是水位低、也不是颜色淡，是画面上根本没有水。
- 之前的测试里还出现过：
  - 船开过/沉过之后海里留下船形空气隧道；
  - 舱底有一小滩真实海水；
  - 每砸一个新洞水位像被清零。
  后两个问题在这一版已经修了（见第 3 节），空气隧道回填逻辑已加但**还没在高速移动下验证**。

---

## 3. 已经确认正常的链路

### 3.1 服务端模拟（确认正常）

最终测试日志（`run-client/logs/latest.log`）：

```
[14:16:01] [vs] 水面同步：船 4｜密闭舱 0｜连海 1｜连海水 27400 单位｜进水 100%
[14:16:03] [vs] 流体拦截（破损后）：舱室 273 格 + 剩余船体 401 格 = 673 格
[14:16:03] [vs] 船体破损：1 格被破坏 → 重扫 0 个舱室 / 1 处连海 / 舱容 0
[14:16:03] [vs] 破口位置（原坐标，前 5 个）：[BlockPos{x=-2, y=-68, z=27}]
[14:16:03] [vs] 连海空腔：274 格，bbox x-3..3 y-71..-68 z17..29
```

说明：
- 破口被识别成 `openPockets`（1 处连海，274 格）；
- `pocketWater` 已经到 27400 单位 = 274 格，进水比例 100%；
- 服务端的水**已经灌满整个舱室**，不是模拟没跑。

### 3.2 物理船砸方块（确认正常）

- `ShipBreakMixin` 拦截 `ServerPlayerGameMode#handleBlockBreakAction`；
- `VsPilot.tryBreak` 用玩家视线对装配快照做 AABB 射线，命中后删除船坞里的方块；
- 日志有 `[vs] 船体破损`，客户端聊天栏有 `船体破损！`；
- 最终测试里用户连续砸了 8 格以上，服务端每次都识别到了。

### 3.3 水面包（确认正常）

```
[14:15:51] [water] 收到水面包：physical=true ship=4 boxes=1 anchor=(0.50009656,-69.530495,23.53011)
[14:15:52] [water] 画水面：ship=4 physical=true boxes=1 delta=(0.16662632,-57.000008,0.06748801) firstSurface=-125.98902
...
[14:16:29] [water] 画水面：ship=4 physical=true boxes=1 delta=(0.22066358,-57.026554,-0.39975265) firstSurface=-125.31502
```

- 包到了（`收到水面包`）；
- 客户端找到了 `ClientShip`（没有 `找不到船 id=` 警告）；
- 渲染回调每秒都在跑（`画水面`）；
- `firstSurface` 是有效值（在水面以上，不是被 `surface <= minY` 跳过）。

### 3.4 隔离自检（确认正常）

```
[vs] 流体拦截：舱室 27 格 + 船体 99 格 = 125 格
阶段2 装配后 40 tick：水=0 空气=124 排水体=0 别的=1
阶段2 破口 40 tick 后：连海空腔 1 处，模拟进水 2800 单位，进水比例 100%
舱内实际水方块（应为 0，水面是客户端自绘）：0
```

- 物理船体积内没有真实海水（流体拦截生效）；
- 破口后模拟水确实涨满；
- 拆船按快照铺回，破口保留。

---

## 4. 关键数字（复现时对照）

最终测试这一艘：

| 项 | 值 |
|---|---|
| 装配锚点 anchor | `(0.50009656, -69.530495, 23.53011)` |
| 客户端 delta | `(≈0.167, ≈-57.0, ≈0.067)` |
| 连海空腔 bbox（原坐标） | `x -3..3, y -71..-68, z 17..29` |
| 空腔体积 | 274 格（后续砸到 282 格） |
| 水面原坐标 | `-68.989`（空腔顶 -68，几乎满） |
| 水面世界坐标 `firstSurface` | `-125.989`（= 原坐标 + dy） |
| 世界空间水体盒 | x `-2.833..3.166`，y `-128.000..-124.000`，z `17.067..29.067` |
| 服务端水量 | 27400 / 28100 单位（100%） |
| HUD 深度 | 183 m（`DepthOverlay` 用 `-player.blockPosition().getY()`） |

**矛盾点**：HUD 的玩家 Y = `-183`，而 `ClientShip.getRenderTransform().getPositionInWorld().y`
算出的是 `-126.53`（anchor -69.53 + delta -57）。两者差 **57 格**，正好等于 delta。
这有两种解释，交接后必须先分清：

1. VS2 把玩家实体放在另一个坐标系（船坞/物理位置），相机和船体渲染在 `-126.5`；
   `DepthOverlay` 读的是实体原始 Y，所以 HUD 偏了 57。船体和水面其实都在 `-126.5`。
2. 客户端 `ClientShip.getRenderTransform()` 是旧的/不同步的，船体实际渲染在 `-183`，
   而水面按 `-126.5` 画，所以两者错开 57 格。

**下一轮第一件事就是确认这一点**：在 `onRenderStage` 里打印
`mc.gameRenderer.getMainCamera().getPosition()` 和 `mc.player.getEyePosition()`，
再和 `firstSurface`、delta 对比。不要先改渲染逻辑。

---

## 5. 最可疑的原因（历史章节；P0/P1/P2 已被 §0.5 纠正，原文保留对照）

### P0：PoseStack 的坐标空间（最大嫌疑）

`ShipWaterRenderer.onRenderStage` 直接用**绝对世界坐标**给顶点：

```java
PoseStack pose = event.getPoseStack();
Matrix4f m = pose.last().pose();
...
vc.vertex(m, minX, minY, minZ) // minX 等是装配原坐标 + delta（世界坐标）
```

`RenderLevelStageEvent` 的 `PoseStack` 在 `AFTER_TRANSLUCENT_BLOCKS` 阶段是否
已经应用了相机平移，取决于 Forge/原版 `LevelRenderer` 的调用点。如果它已经是
**相机相对坐标**，那么这里的世界坐标会被再平移一次相机位置，水体被画到
离船很远的地方（例如 y≈-250），画面里自然什么都没有。

- 旧的 AABB 水体皮肤曾经"看得见"（用户说墙上有蓝灰大块），但那个类已经删除，
  无法从源码确认它当时有没有自己做相机平移；
- 当前代码没有 `translate(-camX, -camY, -camZ)`，也没有减相机位置。

**验证方法**：在 `onRenderStage` 里先画一个以相机为原点的测试盒
（`camPos + (0,1,0)` 附近）。如果测试盒可见、水体不可见 → 坐标空间问题。
**修复方向（下一轮再做）**：顶点改成 `world - camPos`，或
`pose.pushPose(); pose.translate(-camX, -camY, -camZ); ...`。

### P1：玩家/船坐标不一致（见第 4 节；§0.5.2 第 3 条已纠正：HUD 差不能证明错位）

如果 `ClientShip.getRenderTransform()` 与船体实际渲染位置差 57 格，
水面就会画在船体上方/下方。需要先打印相机/玩家眼睛位置确认。
如果确认是 VS2 玩家实体坐标偏移，水面仍应以**船体渲染位置**为准，
不要用玩家坐标。

### P2：RenderType / 深度 / flush（§0.5.2 第 2 条已确认：TRIANGLE_STRIP + CULL 是独立 bug）

`RenderType.debugFilledBox()` + `RenderSystem.depthMask(false)` 的组合在旧皮肤里
能画出来，但现在的调用顺序是：

```java
vc = buffers.getBuffer(RenderType.debugFilledBox());
... 发顶点 ...
RenderSystem.disableCull();
RenderSystem.depthMask(false);
buffers.endBatch(RenderType.debugFilledBox());
RenderSystem.depthMask(true);
RenderSystem.enableCull();
```

深度测试仍然开着，水体在船壳内部，理论上应可见；如果 P0/P1 排除后仍不可见，
再把 `depthMask`/`disableCull` 移到发顶点之前，或换 `RenderType.lightning()` /
`RenderType.debugQuads()` 对比。

### P3：水面被跳过

当前 `firstSurface=-125.989`，`minY=-128`，`surface > minY + 0.01`，不会被跳过。
如果是别的水面，检查 `ShipWaterStateBuilder` 的 `surface` 公式和
`ShipVolumeGuard` 是否把水面格挡掉了。

---

## 6. 建议的下一步（按顺序，不要一次改太多）

1. **只加诊断，不改渲染**：
   - `[water] camera=(x,y,z) eye=(x,y,z) box=(minX,minY,minZ)-(maxX,maxY,maxZ) surface=...`
   - 确认相机/眼睛在 `-126.5` 还是 `-183`，以及水面盒和相机的相对位置。
2. **画一个相机原点的测试盒**（同一 stage、同一 RenderType）：
   - 可见 → 渲染管线没问题，问题在坐标；
   - 不可见 → 问题在 stage/RenderType/flush。
3. **按 P0 修复坐标**：
   - 方案 A（推荐）：顶点统一减 `mc.gameRenderer.getMainCamera().getPosition()`；
   - 方案 B：`pose.pushPose(); pose.translate(-camX,-camY,-camZ); ... pose.popPose();`
   - 两种都试一次，选能对齐船体的。
4. **对齐检查**：在船体一个已知方块位置画一个 1×1×1 的参考盒，
   和船壳方块完全重合才算坐标正确。
5. **再验证水面**：破口后舱内应该从底部往上出现半透明水面；
   同时确认 `[vs] 水面同步` 的 `连海水` 和 `firstSurface` 在涨。
6. **最后再验证空气隧道回填**：装一艘新船，向前/向下全速开，
   看 `[vs] 船尾回填海水` 是否出现、船后是否还有空气洞。

---

## 7. 文件地图

### 服务端（这轮已验证，不要回退）

- `src/main/java/com/opamb/barotrauma/ship/vs/VsPilot.java`
  - `tick()`：物理船每 2 tick 跑 `ShipSimulation.tick` + `tickSeaFlood`；
  - `sendWaterState()`：每 10 tick 发 `ShipWaterStatePacket`，每 60 次打一条 `[vs] 水面同步`；
  - `discoverYardOffset()`：扫 `ChunkClaim` 中心找声纳，算船坞偏移；
  - `observeCore()`：核心方块实体在船坞 tick 时上报偏移；
  - `refreshHull()`：每秒对账船坞方块，发现破坏 → `rebuildGeometry`；
  - `rebuildGeometry()`：用船坞方块重扫，更新 `openPockets`/`interiorCells`/`guardCells`，
    保留旧水量，按破口格数设置进水速率；
  - `tryBreak()`：物理船砸方块的射线映射；
  - `clearShipVolumeWater()` / `refillVacated()` / `isUnderwater()`：清舱 + 船尾回填；
  - `buildGuardCells()`：快照船体格 + 舱室格。
- `src/main/java/com/opamb/barotrauma/ship/ShipSimulation.java`
  - `tickSeaFlood(dt, depth)`：`20 格³/秒 × 深度系数 × 破口格数`；
  - `seedWater(sealed, pocket)`：破损重分舱保留水量；
  - `setBreachSize(int)`。
- `src/main/java/com/opamb/barotrauma/ship/ShipState.java`
  - `replaceGeometry()`：重建几何 + `seedWater`；
  - `floodRatio()`：分母包含 `openPockets`，分子包含 `pocketWaterTotal`。
- `src/main/java/com/opamb/barotrauma/ship/ShipWaterStateBuilder.java`
  - 把每个舱室/连海空腔转成 7 个 float（bbox + surface）。
- `src/main/java/com/opamb/barotrauma/ship/water/ShipVolumeGuard.java`
  - 舱室 + 船体格的 `LongOpenHashSet`，按整数偏移平移。
- `src/main/java/com/opamb/barotrauma/mixin/LevelFluidGuardMixin.java`
  - 拦截 `Level#setBlock` 的流体写入。
- `src/main/java/com/opamb/barotrauma/mixin/ShipBreakMixin.java`
  - 拦截 `ServerPlayerGameMode#handleBlockBreakAction`，回 `ClientboundBlockChangedAckPacket`。
- `src/main/java/com/opamb/barotrauma/blockentity/ShipCoreBlockEntity.java`
  - 物理船上调用 `VsState.observePhysicalCore`，然后停掉方块船逻辑。

### 客户端（当前唯一没打通的）

- `src/main/java/com/opamb/barotrauma/client/ShipWaterRenderer.java`
  - `accept()`：收包，每 40 个包打 `[water] 收到水面包`；
  - `onRenderStage()`：`AFTER_TRANSLUCENT_BLOCKS`，每 20 tick 打 `[water] 画水面`；
  - 顶点目前用**绝对世界坐标**（P0 嫌疑）。
- `src/main/java/com/opamb/barotrauma/network/ShipWaterStatePacket.java`
  - 已注册在 `ModNetwork`（`CHANNEL.registerMessage`），编码/解码正常。
- `src/main/java/com/opamb/barotrauma/client/DepthOverlay.java`
  - HUD 深度 = `-player.blockPosition().getY()`；在物理船上可能不是相机 Y。

### 自检

- `src/main/java/com/opamb/barotrauma/ship/vs/VsFloodSmoke.java`
  - 阶段 2 现在会拆一格并等 40 tick，日志 `模拟进水 2800 单位`。

---

## 8. 已知限制 / 坑

1. **旧存档不会自动认领**：`VsPilot`/`ShipVolumeGuard` 不持久化。
   重进世界后，之前装配好的船是"野船"，新的 `VsPilot` 不知道它的快照。
   测试请用**新世界**。
2. **船外海洋仍是原版方块水**：破口边缘的半格边界还在，彻底解决要等船外海面自绘。
3. **水泵不抽连海空腔**：破口舱室只靠 `tickSeaFlood` 进水/下沉，泵抽不了。
4. **物理船砸方块只覆盖装配快照里的船体方块**：玩家后放的方块暂时不能砸。
5. **声纳台禁止拆**：`tryBreak` 会吃掉左键并提示"声纳是船脑"。
6. **船尾回填只在 `isUnderwater` 为真时执行**：8 点采样，水多于空气才回填；
   在哨站/洞穴干空气里不会凭空造水。
7. **调试日志还在代码里**：`[water]` / `[vs] 水面同步` / `[vs] 连海空腔`
   是这轮加的；定位完客户端渲染后应删掉或降级。
8. **一次只跑一个客户端**：多个 `runClient` 共用 `run-client` 存档/日志，
   会互相覆盖日志、抢存档。之前的"三个客户端"是连续重启造成的。

---

## 9. 复现 / 测试命令

```powershell
cd D:\ZCode work\default-workspace\barotrauma

# 构建 + L1
.\gradlew.bat build --offline

# L2
.\gradlew.bat runGameTestServer --offline

# L3 隔离自检（跑完自己关服）
New-Item -ItemType File -Path run\.smoketest-vs -Force
.\gradlew.bat runServer --offline
Remove-Item run\.smoketest-vs

# 客户端（一次一个）
.\gradlew.bat runClient --offline
```

游戏内复现：

1. 新世界 → 到哨站 → `/barotrauma ship vs launch`；
2. 左键砸船壳/舷窗，聊天栏出现 `船体破损！`；
3. 等 3~10 秒，看舱内是否出现半透明水面；
4. 同时看 `run-client/logs/latest.log`：
   - `[vs] 水面同步` 的 `连海水` 应上涨；
   - `[water] 画水面` 的 `firstSurface` 应上涨；
   - 如果 `[water] 画水面` 有值但画面没水 → P0/P1 坐标问题；
   - 如果连 `[water] 收到水面包` 都没有 → 网络/服务端发送问题。

---

## 10. 交接时不要做的事

- **不要回退** `tickSeaFlood`、`buildGuardCells`、`refillVacated`、`seedWater`：
  这些已经被日志和隔离自检验证过，回退会让"空气隧道/假水/水位归零"复发。
- **不要再用** AABB 水体皮肤、不可见排水方块：用户明确否决过观感。
- **不要在物理船上找"砸方块没反应"**：现在是我们自己的 `ShipBreakMixin` 在映射。
- **不要先动进水模拟本身**：当前客户端渲染仍是主缺口；先按第 5/6 节诊断。
  服务器生命周期清理已单独修过（见第 12 节），不要回退。
- **不要再把 `VsState.active()` 当成“当前世界有物理船”**：它是进程级静态状态。
- **不要同时起多个客户端**。

---

## 11. 一句话给下一位

> 水已经在服务端的舱室里满了（27400/28100 单位），包也到客户端了，
> `ShipWaterRenderer` 也每秒在执行；现在只需要把它的顶点从"绝对世界坐标"
> 改成与 `RenderLevelStageEvent` 的 PoseStack 一致的坐标，并确认
> `ClientShip.getRenderTransform()` 和相机/船体渲染位置一致。

---

## 12. 2026-11-02 20:45 补充：跨世界初始潜艇不生成（已修）

用户现象：第一个世界正常，之后新建世界不再生成初始潜艇。

根因：`VsPilot` / `VsState` 是进程级静态单例；退出世界时没有 `ServerStoppedEvent` 清理，
新世界仍看到上一世界的 `VsState.active=true`，`StarterSub.ensure()` 直接跳过。日志
（`run-client/logs/latest.log` 20:26:31、20:27:11）出现“初始潜艇已收成物理船，跳过重建”，
随后还有“客户端找不到船 id=4”和旧船水面包。

修复：

- `VsPilot.resetForServerLifecycle()`：纯内存清空 `ship/thruster/state/corePos/snapshot/guard/pending/lastLevel`
  等静态字段。
- `hull_plug` 方块注册、`HullPlug` 类、模型/blockstate 和全部调用已删除；旧存档里若还有这个方块，
  加载时可能被当成缺失方块。
- `DeepSeaEvents`：订阅 `ServerStoppedEvent`，并在 `ServerStartedEvent` 兜底清理。
- `StarterSub.ensure()`：只有 `VsState.corePos()` 等于初始潜艇核心时才跳过；玩家先装别的船时，
  初始潜艇仍会生成。

验证：`test` 26 PASSED、`build` 成功、GameTest 3/3、`.smoketest-vs` 通过；日志确认服务器启停
都执行“已清理物理船静态状态”。

多世界客户端实测（2026-11-02 21:07–21:09）：

- 世界 22：初始潜艇建造 → 装配 403 格 ship=4 → 不拆船退回标题。
- 世界 23：启动时执行清理 → 初始潜艇重新建造 → 再装配 403 格 ship=4，水面同步正常。
- 世界 23 没有再出现“初始潜艇已收成物理船，跳过重建”。

世界 22 曾在 21:08:19 出现一次“客户端找不到船 id=4”，世界 23 未复现；按客户端 VS2 船同步
的独立问题记录，不要和跨世界静态状态泄漏混为一谈。

---

## 13. 2026-11-04 02:00 船壳可视化修正完成；舱内水面缝隙未解决（OPEN）

> 这是本文档的最新条目（文件顶部的 Club 交接说明保留为历史）。当前权威状态见 `STATUS.md`
> 顶部和 `.mex/ROUTER.md`。

本轮 commit `716a7e8` 已推送（含 MEX 文档更新）。代码改动集中在：
`HullDamageRenderer`（公告牌血条 + 裂痕绕序）、`ShipWaterRenderer`（连续曲面 + 水盒快照诊断）、
`ShipHullDamagePacket` / `HullDamage`（NaN 防护）。

已完成并验收：血条（任何角度可见、不嵌墙、0% 亮红）、裂痕六面外侧可见、血条原始数值日志。

**唯一未解决问题：舱内水面仍有可见的缝/分界线。** 用户已连续多轮报此问题，最后方案
（角点四格平均 + 波浪 + 删除台阶面）仍未通过实测。**下一轮不要继续盲改几何**：

1. 用 `-Dbarotrauma.waterDebug=true` 启动 runClient；
2. 在**真实进水场景**（组装物理船、打几个破口让水真的流）抓 `[water-diag]` 完整日志；
3. 先确认缝是不是 quad 边界（顶点坐标 dump / 标记），再排查深度精度与船体渲染交互
   （船在 100m+ 深处、VS2 变换后坐标值大，深度缓冲精度可疑）；
4. 对照 `-Dbarotrauma.renderWater=false` 关掉水渲染后缝是否消失（区分水几何 vs 环境滤镜）。

测试注意：
- 新世界/船未组装时 `[water] 画水面` 全是 `ship=-1 boxes=0`，水面渲染不跑，室内被水下滤镜染色。
- 日志关键词：`[water] 画水面`（1Hz）、`[water] 水盒快照`（每船第一包）、`[hull-bar]`（每格首次）。
- 未导出 jar；`export/` 里仍是 2026-11-03 23:18 的旧包。
