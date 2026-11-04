# 交接状态（新会话从这份开始读）

> 每次收工都要更新这份。规则见 `AGENTS.md`；设计细节和历史见 `PLAN.md`
> （最近两轮是 §10.42 只保留物理船、§10.43 舱内真空气 + 声纳锚点）。
>
> 上次更新：2026-11-04 20:30（舱壁门/舱口重绘 + 拾取形状按朝向修好；"按 Shift 不沉"定性为船沉人站舱底，非代码问题）。
> 设计细节和历史见 `PLAN.md`（最近：§10.56 舱门重绘与朝向形状）。

## 2026-11-04 20:30 舱门重绘 + 朝向形状修复 + 游泳问题定性

**未提交、未导出 jar**（工作区仍是脏树，见文末）。

完成：
- **竖门重绘**（1 格宽 × 2 格高，15 个轴对齐长方体）：4 根固定门框 + 4 块绕窗钢板 +
  4 条矩形窗包边 + 1 片薄玻璃 + 正反各 1 条加强筋。6×8 真实窗洞，玻璃 cutout 透明
  （48 个玻璃像素里 44 个透明），无渐变/噪点/曲面。资源在 `geo/block/bulkhead_door.geo.json`
  + `textures/block/bulkhead_door.png`；可编辑源 `art/models/bulkhead_door.bbmodel`
  与 `tools/gen_bulkhead_door.py`（只写竖门，不碰横舱口）。
- **动画契约未动**：`panel` 侧滑 16px / 0.6s，`frame` 不动；运行时
  `animations/block/bulkhead_door.animation.json` SHA-256 全程未变
  （`d65e1808…c9f7`）。BB 源工程是 +16X、运行时是 -16X，**不要统一符号**。
- **物品图标换成新门**：`textures/item/bulkhead_door.png` 16×16，同上配色，观察窗真透明。
- **真 bug：竖门拾取形状没跟 FACING 转**。原来 `frameShape`/`PANEL` 按固定朝北写死，
  东/西向门只有门心 1px 交叉处能点、门框完全在形状外 —— 用户体感就是
  "这版是初版那种只能点门中间才能开关的"。已改成 4 朝向缓存
  （`BulkheadDoorBlock.PANEL_SHAPES/FRAME_SHAPES` + `DoorShapes.slab`），与模型一致。
  横舱口一直用 `DoorShapes`，本来是对的。
- **顺手修的真缺陷**：`physicalKeyDown` 只查绑定键（`left.shift`），按右 Shift 读不到；
  现在左右 Shift 都认。
- **清掉临时诊断**：删除 `client/DoorDebug.java`、`UnderwaterMovement` 的 `[swim]` 每 40 tick
  状态行和键位检测行。**保留** `[pump]`（20 tick 一次）——水泵"同一连通空间远处的水"
  还没解决，下一轮靠它。

核查纠正（2026-11-04）：此前“只是站在舱底、不是游泳 bug”的定性证据不足。世界 y 下降、减整数 offset 后坐标近似恒定，只能说明相对移动很小，不能证明脚接触地板。`pos - VsState.offset*` 也不等于包含旋转的真实逆变换；直接在装配原坐标读取世界方块并非读取船坞方块。下潜问题仍待真实船内相对位移和船坞碰撞诊断，不应关闭。

历史观察（不作为根因证明）：
- 用户报"船舱水里按 Shift 三秒蹲不下去"。破口
  `(1,-68,27)`/`(1,-68,28)` 后舱室 100% 进水，船从 `y=-71` 沉到 `-128`，
  `[swim]` 里世界 y 连续下降而**装配坐标恒定在 (0,-71,23) 附近** —— 人被船带着走，
  一直站在舱底，所以相对船舱没有可下潜空间。
- 物理船（VS2）的方块本体在船坞，**世界坐标 `getBlockState` 读出来永远是 air**，
  `getBlockCollisions` 也看不到船体碰撞；判断"脚下有什么"必须把位置换算回装配坐标
  （`pos - VsState.offset*`，注意 `stableOffset` 有 0.62 格迟滞）。

未解决 / 待确认：
- **门框右键开关**：形状已按朝向修好，但用户还没在游戏里确认东/西向能否点门框关门。
- **水泵抽远处水**（同一连通空间）：用户认为"只要在一个房间都会抽"是错的，尚无方案。
- 舱内水面缝隙（见下一节，仍是老问题）。
- `[pump]` 诊断是故意留的，不是漏删。

顺带修好的陈旧测试（`gradlew test --offline` 现在 **46 用例全绿**）：
- `ShipWaterStateBuilderTest` 还在用旧的 `ShipGeometry.Result` 六参数构造器，补上 `DoorLink` 列表；
- `ShipDrainPumpTest` 没给泵格，水泵改成"只抽自己所在水体"后两条用例必然失败，补
  `setDrainPumpCell(new BlockPos(0,0,0))`。

## 2026-11-04 船壳可视化修正 + 水面连续曲面（水面缝隙未解决）

本轮提交 `716a7e8`（已推送）。工作区干净；**未导出 jar**（export/ 里仍是 2026-11-03 23:18 旧包）。

完成：
- **血条改为朝向相机的公告牌大血条**。贴面 decal 的根本问题是：选“朝相机的面”用船局部坐标，
  船沉几十米后相机在局部坐标里高出船壳几十格，永远选中方块顶面，侧面看顶面 decal 就是一条黑缝
  （日志证明 health 数值全正常）。公告牌不受影响；外抬量 = 0.6 + 牌子在法线方向的半宽，
  任何视角靠墙边都悬在方块面外 0.1，不再嵌墙。0% 整条亮红。
- **裂痕六面可见**：`RenderType.crumbling` 默认开背面剔除，之前六面顶点绕序全是顺时针，
  从外面整片被剔；倒序后外侧可见。
- **水面渲染**：角点高度 = 周围四格水位平均（相邻 quad 共享角点数值完全相同）+ 同相位波浪；
  删除相邻水柱的竖直台阶面；贴墙面只沿法线缩 1cm、沿墙方向占满；明暗用空间+时间连续函数。
- **数据加固**：HullDamage / setHealth / ShipHullDamagePacket / 渲染端全部 NaN 防护；
  新增 `[hull-bar]`（每格首次渲染的原始数值）和 `[water] 水盒快照`（每船第一包前 12 盒）日志。

未解决（下一轮从这里开始）：
- **舱内水面仍有可见的缝/分界线**（用户 2026-11-04 最终实测）。已试无效：平水面、波浪接缝对齐、
  台阶面保留/删除、贴墙面 XZ 双缩/单缩、角点平均。下一步不要继续盲改几何：用
  `-Dbarotrauma.waterDebug=true` 在真实进水场景（组装物理船 + 破口）抓 `[water-diag]` 完整日志，
  先确认缝是否真是 quad 边界，再怀疑深度精度/船体渲染交互。
- 船壳耐久仍是运行时数据，重进世界/拆船会丢。

测试注意：新世界/船未组装时 `ship=-1 boxes=0`，水面渲染根本不跑，室内会被水下滤镜染色——
不要误判成水面 bug。先看 `[water] 画水面` 的 boxes 数量。

## 2026-11-03 空气海域手感与 GUI（当前构建）

方向已改为：**海域是空气 + 客户端滤镜/限视距，浮力按漏水数值模拟**（真实水渲染和排水体都已停用）。
本轮修掉用户实测反馈的五项：

1. **方块船舱内滤镜**：干燥区原来只有一份静态注册，集成服务器启动时 `VsPilot` 覆盖了
   客户端 `ShipWaterRenderer`。现在 `OceanEnvironment` 分 `clientDryZone` / `serverDryZone`，
   按 `level.isClientSide()` 选；客户端注册不会再被服务端覆盖。
2. **方块船不进水**：初始潜艇固定内腔原本无条件返回“干燥”，绕过了水面包水位。
   现在内腔只做范围判断，水面高度仍看水面包；破壳后水面淹到眼睛 → 滤镜 + 掉氧。
3. **干燥区看水没滤镜**：`UnderwaterEffects` 增加视线采样（玻璃可穿过，船壳/岩层截断），
   从看到水的距离开始铺水下雾；室内清晰、窗外蓝绿。
4. **开 GUI 下沉 / 滤镜消失**：`UnderwaterMovement` 不再因 `mc.screen != null` 直接返回，
   GUI 打开时保持悬浮；`RenderGuiEvent.Pre` 色调不再跳过（画在 GUI 下层，不挡界面）。
5. **舱内跳跃顶点闪滤镜**：初始潜艇干燥区上界改到 `-67.5`；干燥舱室的盒子顶格放宽一格。

验证：

```
./gradlew test --offline → 26 PASSED（13:11）
run-client/logs/latest.log：
  13:07:40 舱内 submerged=false；13:08:49 破壳后 physical 船开始连海进水；
  13:08:51 船已从 y=-69.5 沉到 y=-117.5；13:13 开 GUI 测试后用户确认“修好了”
```

新增日志：`[water] 客户端浸没切换：submerged=… pos=…`（只在状态翻转时打）、
`[ship] 方块船船体破损，已重扫：舱室 …｜连海 …｜连海水 …`。

**当前未做**：氧气瓶/水压/舱间压差流/逐段耐久/泵与压载；物理船浮力参数（`FLOOD_WEIGHT=0.65`、
`FLOOD_DRAG=0.65`）只经过一次沉底实测，尚未细调；`ShipWaterRenderer` 里旧水体绘制是死代码，
`ShipVolumeGuard`/`LevelFluidGuardMixin` 仍注册但从不激活。

## 2026-11-02 18:30 船内水面：诊断 + 最小修复（当前构建）

按施工单先做**第 0 步只加诊断**（原水体行为保留），再用实际源码做**独立可逆补丁**。

### 已确认的根因（源码证据）

1. **坐标空间**：Forge 47.4.0 `GameRenderer.renderLevel` 只给 level PoseStack 乘相机旋转，
   没有 `translate(-camera)`（`GameRenderer.java:1119-1126`）；`LevelRenderer.renderChunkLayer`
   把同一个 PoseStack 交给 `RenderLevelStageEvent`（`LevelRenderer.java:1559`）。
   VS2 2.4.11 自己渲染船体时显式做 `translate(-cam) * shipToWorld * translate(offset)`。
   旧渲染器直接提交绝对世界坐标 → 错。
2. **拓扑/剔除**：`RenderType.debugFilledBox()` 是 `TRIANGLE_STRIP`（`RenderType.java:141`），
   多个独立 quad 会连错；它默认 CULL=开、写深度。旧代码在 `endBatch` 前手动
   `disableCull/depthMask(false)` 会被 `RenderType.setupRenderState()` 覆盖（`RenderType.java:426-434`）。
   满舱第一人称在水下往上看，水面是背面，会被剔除。
3. **水位高度**：`ShipWaterStateBuilder` 旧实现用 bbox 底面积，非矩形舱室会算错。
   已改为按 Y 逐层实际格数累计（`ShipWaterStateBuilder.java:39-86`）。

### 本轮改动

- `client/ShipWaterRenderer.java`：诊断默认关闭（`-Dbarotrauma.waterDebug=true` /
  `BAROTRAUMA_WATER_DEBUG=1` / F9）；1 Hz `[water-diag]` 记录 camera/eye/player/cameraEntity/
  partialTick、ship render/current/prev、anchor/delta、水面盒、CPU/GPU 矩阵、顶点数、flush/stage/type；
  视线前 2 m 三个分离 0.2 m 测试面（现有管线 vs 诊断 QUADS，配对；深度开/关；绝对坐标对照），
  独立 buffer，不污染主水路径；不新增世界方块。
- 修复补丁默认启用，`-Dbarotrauma.waterFix=false` 回退到旧行为（`drawWaterLegacy`）。
- 新增 `ShipWaterStateBuilderTest` 7 用例；未改 HUD / VS2 / 服务端模拟 / 禁改清单里的任何方法。

### 独立复核修正（19:10）

- **诊断 raw QUADS 双重旋转（真 bug）**：初版 `drawRawQuad` 同时把事件 pose 烘进顶点和
  modelview；`BufferUploader` 使用 modelview，导致旋转两次。已改为顶点只存相机相对坐标、
  pose 只经 modelview 应用一次（`ShipWaterRenderer.java:566-590`）。
- **主水面与声纳共用 debugQuads 批**：主水面改用独立 `BufferBuilder`（`:347-349`），
  不再和 `SonarRenderer.java:46` 的 `debugQuads` 共用 `mc.renderBuffers().bufferSource()`。
- **debugQuads 缺 VIEW_OFFSET_Z_LAYERING**：主水面 `endBatch` 外层显式 setIdentity +
  0.99975586 缩放（`:415-427`，对应 `RenderStateShard.java:162-171`），降低共面 z-fighting。
- **工作树不是“只显示本轮改动”**：复核时 `git status --porcelain | wc -l` = 101
  （35 M / 40 D / 26 ??）；`build/_baseline_20261102/` 是定向基线（5 文件 + 1 份旧客户端日志），
  不是整树快照。既有改动未 reset/覆盖；本轮源码改动仍只有 3 个文件。

### 验证（实际命令）

| 命令 | 结果 |
| --- | --- |
| `./gradlew test --offline` | 退出码 0，26 PASSED（原 19 + 新 7） |
| `./gradlew build --offline` | 退出码 0，BUILD SUCCESSFUL；jar 已生成 |
| `./gradlew runGameTestServer --offline` | 退出码 0，`All 3 required tests passed :)` |
| `run/.smoketest-vs` + `./gradlew runServer --offline` | 退出码 0；装配后 40 tick 水=0；破口后模拟进水 2800 单位；舱内实际水=0；拆回水=0 |
| `./gradlew runClient --offline`（仅一次） | 日志进入主菜单加载阶段；**未加载世界，未视觉验证**；任务被强制停止，日志末尾 `BUILD FAILED / exit -1`，只算启动证据，不算通过 |

### 仍未验证 / 下一步

- **水面是否真的出现在舱内仍未验证**：computer-use 技能明确 "Main agent only"，本会话是子代理，
  未使用 GUI 自动化。需要主代理/用户进游戏看（建议 `/barotrauma ship vs launch` → F9 →
  `/barotrauma ship flood 1500`，同时看 `[water-diag]`）。
- 非矩形舱室的水平范围仍用 bbox 近似；要"最终真实水面"需改协议/逐格几何，本轮没做。
- 复核修正后只重跑了 `test`/`build`（26 PASSED、BUILD SUCCESSFUL）；L2/smoke 未重跑
  （只改客户端，服务端代码未动）。
- 最终渲染器（18:43 之后）没有客户端类加载或世界内验证，也没有 `[water-diag]` 运行输出。
- 旧存档里已装配的物理船仍不会自动认领（`VsPilot` 不持久化）。

## 一句话现状

深潜维度、哨站、VS2 物理船、3D 声纳、透明试验船壳都能跑；三层测试全绿。

**物理船上不能右键/破坏方块**是 VS2 2.4.11 的硬限制（jar 里没有 `ServerPlayerGameMode`/
`useItemOn` 的重映射）。**声纳已经用"交互锚点"解决**：装配后放一个不可见的真实方块在
声纳位置、跟着船走，右键它就能开声纳；`/barotrauma ship sonar` 作兜底。
**破坏船壳仍然不支持**（要支持得自己拦 VS2 的 use/break 包，暂缓）。

**舱内是真空气**：排水体只盖船壳（98/402 格），舱室格子不填，船移动时把新位置舱内的水
清掉。总自检：舱室 273 格里 272 格空气 + 1 格声纳锚点，10 秒后仍是 0 水。

**方向（2026-11-02 中午最终）：VS2 物理船是主路径。** 方块船实测"移动时找不到声纳、乱闪"
被用户否决，移动已关闭（代码保留）。VS2 服务端/客户端都是连续积分，水是格子，
半格错位只能靠不透明船壳/舷窗遮掩。下一步补 VS2 缺失的方块交互（use/break 坐标映射）。
移动方式：方块船（方块留在世界，整格移动，10 格/秒）。旧 VS2 物理船代码保留，
但用户已否决它的半格错位和"船上不能破坏方块"，不再是主路径。
五台机器（反应堆/制氧机/引擎/压载舱/水泵）和舱门/舱口已按用户要求删除，船上只剩船壳 + 声纳台。

**待用户实测**：物理船是否还一直上浮（已加深度保持）、人在船上是否还卡在玻璃里。




## 本轮新改动（2026-11-02 凌晨）

### 1. 哨站改挂"最上方岩壁下方"（用户指定）

- 旧问题：哨站硬编码 y∈[-160,-140]，`carveCavern` 在原地挖中心 -150、顶到 -124 的椭球，
  **把第一层顶板挖穿**，哨站悬在洞正中 → 用户："之前的都是洞"。
- 新规则：哨站 x/z 范围内把第一层顶板底面**压平到 y=-44**（`FLAT_ROOF_FRACTION=0.12`），
  哨站移到 y∈[-64,-44]、顶部装甲替换最后一块顶板岩；水池椭球中心下移到 y=-72、顶 -46
  不再碰顶板。出生点 -62，初始潜艇 y∈[-72,-68]。
- 自检证据：`leaked water blocks = 0`、`armor(0,-44)=station_plating rock(0,-43)=stone`、
  `beside hull (22,-54)=water`。
- **待用户进游戏确认观感**（服务端只能验几何）。改动细节见 PLAN.md §10.40。

### 2. ZCode 侧 MC 开发技能包（用户已装好）

- 选了 `Jahrome907/minecraft-agent-skills` v2.5.4（`minecraft-modding` 覆盖 Forge 1.20.1）。
- 已打包成 ZCode 插件源：`D:\ZCode work\default-workspace\plugins\minecraft-codex-skills\`
  （13 个技能，SKILL.md 未改）；本地市场：`D:\ZCode work\default-workspace\plugins\marketplace.json`
  （市场 ID `dev-default-workspace-9f3d1272`）。
- 用户已在 UI 里添加市场并安装、重启宿主；当前会话技能列表里已有 13 个 `minecraft-codex-skills:*`。
- 使用约定沿用 AGENTS.md §4：只有 `minecraft-modding` 真覆盖 1.20.1，其余点名再用。

### 3. 隔离 smoketest（STATUS 上一版定的"下次第一件事"，已完成）

- 新入口 `run/.smoketest-vs` → `ship/vs/VsFloodSmoke.java`，只跑一个场景、跑完关服。
- 阶段 1 原始装配（不装排水体）：装配后立即 125 格空气，**40 tick 后 125 格全水**。
- 阶段 2 正式管线：装配后排水体 125 格，**40 tick 后水=0**，拆回后**舱内空气 26 格**。
- 数据与判定见 PLAN.md §10.41。

### 4. 隔离自检顺带修掉的两个真 bug

- **排水体会被 VS2 事后清掉**：`assembleToShip` 返回后装配协程还在跑，超时强行续跑时
  把源区域清一遍，连同刚铺的排水体一起清掉（装配后 20 tick 内 125 格全没）。
  → `HullPlug.ensure()` 每 tick 补缺失的格子，`VsPilot.tick()` 里调用。
- **拆船后舱内积水**：铺回方块时那片已被海水灌满，方块把水封在舱里。
  → `ShipState.clearWater(level,dx,dy,dz)` / `ShipWater.clear(level,dx,dy,dz)`，
  `dock()` 铺回后调用。

### 5. 只保留物理船（PLAN.md §10.42）

- **删除的方块**：五台机器（`nuclear_reactor`/`oxygen_generator`/`engine`/`ballast_tank`/
  `pump`）+ `airlock_door` + `hatch`；注册、物品、创造栏、语言、blockstate/model/texture、
  `vs_mass` 条目全部清掉。`ShipDoorBlock` 类删除。船上只剩船壳 + 声纳台。
- **删除的移动**：`ShipMover`、`ShipState.tickMovement/applyStep/slide`、
  `/barotrauma ship move`、总自检里的 move/drive/舱门连通场景。
- **新增入口**：`/barotrauma ship sonar` 打开声纳界面（VS2 船上不能右键方块）。
- **排水体改形状**：只盖"船体方块 + 密闭舱室"，不是整个包围盒；
  `moveTo`/`remove` 腾出的格子回填海水。
- **浮力**：`VsThruster` 加深度保持（无垂直输入时锁在松开按键那一刻的深度）。



## 这一轮查明的（重要，会推翻旧文档里的结论）

### 1. 旧结论"装配失败是位置问题"是错的

`STATUS.md` 上一版写"只有初始潜艇那个坐标（chunk 0,0）装配失败"。
日志检索出来同一个位置**成功过也失败过**：

```
20:47:28  已收成物理船：409 格，质量 3117600，中心 (0.5, -149.5, 23.5)   ← 成功
21:03:46  已收成物理船：409 格，质量 300，中心 (1.5, -150.5, 20.5)      ← 失败
```

同样的方块数（409）、同样的位置。所以位置不是变量，**是间歇性失败**。
（另一条旁证：18:33 那次是 `已收成物理船：1 格，质量 300`。）

不要再去扫"失败半径"了，那条思路是错的。

### 2. 真正的 bug：`VsPilot.launch()` 从来不校验装配结果

旧代码拿到 `ShipAssembler.assembleToShip()` 的返回值就直接宣布成功并清空状态：

```java
ServerShip assembled = VsBridge.assemble(level, blocks);
if (assembled == null) { return "VS2 装配失败"; }   // 只有 null 才算失败
...
return "已收成物理船：... 质量 %.0f ..."            // 质量多少都报成功
```

而 VS2 的装配顺序是**先把方块从世界里挖成空气，再铺到船坞区块**。
一旦铺失败，世界里那艘船就直接没了，刚体里只剩质量 300（声纳一格）。

**用户报的三个现象是同一个根因**：

| 用户的话 | 实际发生的事 |
| --- | --- |
| "船和声纳不是一体的" | 只有声纳那一格进了刚体 |
| "还是进水" | 船壳被删掉了，原地变回海水 |
| "那些方块也没删" | 更早一版的 bug，已修（现在方块会进船坞） |

### 3. 装配后世界里那片体积确实会变成水——但**我还没证明它和装配有因果关系**

上一轮 smoketest 量到：

```
[smoketest] 装配后立即｜进水诊断：舱室格 267（水=267 空气=0 别的=0）｜船体格 408（水=408 空气=0 别的=0）
```

看着像"装配把海里那块灌满了"。**但这份数据是污染的**：同一个 smoketest 在这之前
跑过「开门 / 破口」场景，那些场景会把舱室重新分舱成"连海"并灌满，`ShipWater.apply`
本来就会写 267 格水。所以这个数字**不能算到装配头上**。

要证明这个因果，必须用隔离场景（见下面"下次开工第一件事"）。

## 代码里已经改了、但没验证的东西

按风险从高到低排；新会话如果只做一件事，先验 1。

### 1. `HullPlug`（排水体）——**已验证，能挡住水，且不再清空船外的水**

- `ship/vs/HullPlug.java`：装配后把**船体方块 + 密闭舱室**的格子用 `barotrauma:hull_plug`
  填上（不是包围盒），船整格移动时按差集跟着挪（`moveTo`），
  **每 tick 用 `ensure` 把被 VS2 事后清掉的格子补回**，拆船前 `remove`。
- **腾出的格子回填海水**，不留干燥尾迹、不留看不见的方块壳。
- `registry/ModShipBlocks.HULL_PLUG`：`noOcclusion` + `noCollission` +
  `isViewBlocking(false)` + `isSuffocating(false)` + `strength(-1)` + `noLootTable`，
  **故意不在 `isShipBlock()` 里**（否则船体扫描会把排水体当船壳，越扫越大）。
- 资源：`blockstates/hull_plug.json` + `models/block/hull_plug.json`
  （`"elements": []`，所以完全不可见）。**没有物品、不在创造栏**，只能用命令放。
- 隔离自检（§10.41）：原始装配 40 tick 后 100% 灌水；正式管线 40 tick 后水=0。
- **仍存在的副作用**：船开走以后原来的位置会留下一圈看不见的方块壳（不碰撞、不可见，
  但挡水），服务器重启后 `VsPilot` 状态丢了，这些壳就永远留在世界里（目前不清理）。

### 2. 装配对账 + 回滚 + 重试（这块逻辑本身是明确的修复）

`ship/vs/VsPilot.java`：

- 装配前用 `VsBridge.blockMass(state)`（内部走 VS2 的 `BlockStateInfo`）把快照里
  每个方块的质量加起来得到 `expected`；
- 装配后比 `VsBridge.mass(ship) / expected`，低于 `ASSEMBLY_MIN_RATIO = 0.85`
  判失败，最多 `ASSEMBLY_ATTEMPTS = 3` 次；
- 每次失败先 `VsBridge.discard(level, candidate)` 删半成品刚体，
  再 `restoreBlocks(...)` 按快照把方块铺回原位，然后重试；
- 全部失败 → 再兜一次铺回 → 返回明确报错（**不再出现"船壳没了"**）。
- `dock()` 里原来手写的铺回循环抽成了 `restoreBlocks(level, snapshot, dx, dy, dz)`，
  装配回滚和拆船共用一套。
- `LOGGER` 字段原来散落在各处，现在 `VsPilot.LOGGER` 统一。

### 3. 诊断入口

- `/barotrauma ship vs flood` → 打印 `VsPilot.floodProbe(...)`：
  船原来的舱室格 / 船体格各有多少格是水、空气、别的方块。
- `/barotrauma ship vs status` 末尾多了 `HullPlug.status()`（排水体盖住多少格）。
- `VsBridge.claim(ship)` → 打印船坞区块坐标（`chunkClaim` 的 start/end/middle + 维度）。
  注意 VS2 的船**不在别的维度**，存在同一维度的预留远处区块里，
  实测船坞在 `chunk[-1791872.., 768127..]` 这一片。

### 4. smoketest 的装配方块集改了

`event/DeepSeaEvents.beginVsSmoke` 从 `geometry.hull()` 改成 `state.collectBlocks(deep)`，
并把船状态和核心坐标传进去。原因：声纳台挂在船壳外面，不属于"最大连通分量"，
用 `hull()` 装船必然把它留在原地——那就是"船和声纳不是一体"。

## 验证状态

| 项 | 结果 |
| --- | --- |
| `gradlew build --offline` | ✅ 绿（11-02 02:31，含声纳锚点/真空气舱） |
| L1 `gradlew test` | ✅ 17 个用例 / 0 失败（02:00；之后只改物理船代码，build 绿） |
| L2 `gradlew runGameTestServer` | ✅ `All 3 required tests passed :)`（02:00） |
| 总自检 `.smoketest` | ✅ 舱室 273 格＝272 空气 + 1 声纳锚点，水=0（10 秒后仍 0）；拆回 403 格 |
| 隔离自检 `.smoketest-vs` | ✅ 原始装配水=125；正式管线 水=0 空气=26 排水体=98 锚点=1 |
| 哨站新位置（贴顶板、0 漏水） | ✅ 总自检 `roof seal`/`pool`/`leaked=0` |
| 排水体只盖船壳（舱内真空气） | ✅ 已改为**流体拦截**（不铺方块）：隔离自检 水=0 空气=124 排水体=0 |
| 流体拦截 mixin（LevelFluidGuardMixin） | ✅ 已接入 MixinGradle；对照：无拦截水=125 / 有拦截水=0 |
| 船内平滑水面（M2） | ✅ `ShipWaterStatePacket` + `ShipWaterRenderer`（舱室水体积连续水面）；❓待实测 |
| 船外海面自绘 | ⏳ 大工程，暂缓（先船内） |
| 停写 MC 水 / 修无限水·海绵 | ✅ 已停止 applyWater；水只作模拟量 |
| 船移动瞬间灌满 | ✅ 移动时清船壳+舱室体积里的已有水 |
| 破口渐进进水（M3） | ✅ `tickSeaFlood` + `ShipFloodingTest`（L1） |
| 声纳交互锚点（右键开界面） | ✅ 用户实测：日志里出现船控指令与位移 (22,13,79)，界面确实打开了 |
| 自动驾驶（物理船） | ✅ 代码修好（`autopilotStep` 每 tick 纠偏）；❓待用户实测 |
| 声纳船体点跟船移动 | ✅ 代码修好（按整数偏移平移 hull 点）；❓待用户实测 |
| 船体闪烁（半格错位） | ✅ 查明：连续渲染 vs 整格排水体的固有错位；初始潜艇改不透明船壳+舷窗；迟滞死区保留 |
| 自动驾驶惯性补偿 | ✅ 改成 PD（期望速度−当前速度），build/L1/L2 绿；❓待用户实测 |
| 舱内残水 | ✅ 每 10 tick 强制清一次舱室水；❓待用户实测 |
| 每块耐久/漏水/框架 | ⏳ 设计已写入 PLAN §10.44，下一轮做 |
| 下潜慢/上浮快 | ⏸ 用户说先不管，等压载舱一起调 |
| 物理船上破坏方块 | ❌ VS2 2.4.11 硬限制，未实现（暂缓） |
| 浮力深度保持 | ❌ 服务端验不了，**待用户实测** |
| 玩家"卡在玻璃里" | ❌ 未定位，**待用户复测** |
| 用户实测（船里有没有水） | ✅ 用户已确认"水是不进了" |
| 姿态锁在开阔水里 | ❌ 没验证（只在狭小水腔里量到过 39.6°） |

## 下一步：等用户实测物理船

现在**只有物理船一套移动**（方块形态移动已删）。玩家侧操作：

```
右键船上的声纳台      打开声纳界面（装配后放了不可见的交互锚点，跟着船走）
/barotrauma ship sonar 兜底入口（锚点意外失效时用）
界面里：拖拽转视角、滚轮调射程/速度、WASD/空格/Shift 驾驶（和以前一样）
/barotrauma ship vs dock      拆回方块
/barotrauma ship vs status    质量/位置/姿态/排水体/物理回调
```

要用户确认的三件事：
1. **右键船上的声纳能不能打开界面**（交互锚点）——这是这一轮新增的；
2. 还会不会一直上浮（深度保持）；"卡在玻璃里"是否复现；
3. 舱内现在是真的空气，可以在船里放方块了（VS2 会映射到船上）。

仍未做：**物理船上不能破坏方块**（VS2 2.4.11 硬限制）。

**新发现的持久化缺口（下次开工优先）**：退游戏时如果船还是物理船状态（没 dock），
VS2 的刚体会随存档保留，但 `VsPilot` 的静态状态（快照/排水体/声纳锚点/自动驾驶）是内存的，
重进世界就丢了——船还在，但无法控制、`vs dock` 也没有快照可铺回。
下次要先做"世界加载时认领已存在的 VS2 船"（或进世界提示必须先拆船）。
姿态锁在开阔水域也还没实测。

接下来按优先级：

1. **用户进游戏看**：装船后船里还有没有水、拆船后船里干不干净、
   哨站是不是真的像挂在岩壁上。服务端验不了视觉。
2. **姿态锁在开阔水里**（唯一还没验证的旧项）：只在狭小水腔里量到过 39.6°，
   开阔水域会不会翻没测。
3. `HullPlug` 的残留壳：船开走后原位会留下不可见挡水方块，重启后 `VsPilot` 状态丢失
   就没人清。要么加一个"服务器启动时扫描并清理孤立排水体"的任务，要么改成别的方案。

## 工作区状态

- **不是干净树**：`barotrauma/` 是 git 仓库，最后一次提交 `0daa731`，
  之后有约 60 个文件改动没提交（`git status` 里的 M/D/?? 全都要保留）。
- 新增未跟踪：`STATUS.md`、`src/test/`、`src/main/java/.../gametest/`、
  `ship/vs/HullPlug.java`、`ship/vs/VsState.java`、`data/barotrauma/vs_mass/`、
  `tools/gen_gametest_structure.py`、`run-client/`、`docs/IMPLEMENTATION.md`
- **`barotrauma/run/world` 被我删了重建**：上一轮 smoketest 把初始潜艇装配走、
  没拆回来就存了盘，世界文件里那一块就是个灌满水的坑。新世界是干净的。
- `barotrauma/run/.smoketest` 标记文件已清掉（否则 `runServer` 会自检完自己关服）。
- `run-client/saves/New World (1)` 是船壳透明的那个存档，`New World` 是旧的。

## 环境怎么跑

```powershell
cd barotrauma
.\gradlew.bat build --offline            # → build\libs\barotrauma-0.1.0.jar
```

**三层测试**（见 `PLAN.md` §10.37）：

```powershell
.\gradlew.bat test --offline                 # L1 纯逻辑，17 个用例，约 20 秒
.\gradlew.bat runGameTestServer --offline    # L2 游戏内 GameTest，3 个用例，约 30 秒
python tools\gen_gametest_structure.py       # 删过 run/ 之后重建 GameTest 模板
```

**无头自检**：

```powershell
New-Item -ItemType File -Path run\.smoketest -Force
.\gradlew.bat runServer --offline
# 自检自己关服；日志 run\logs\latest.log（UTF-8）。控制台是 GBK 乱码，别从控制台读
Remove-Item run\.smoketest

# 隔离进水自检（只跑 VsFloodSmoke，两阶段量水，跑完自动关服）
New-Item -ItemType File -Path run\.smoketest-vs -Force
.\gradlew.bat runServer --offline
Remove-Item run\.smoketest-vs
```

**带客户端**：`.\gradlew.bat runClient --offline`

- 单人世界的服务端消息（`sendSystemMessage`）**只在客户端日志的 `[CHAT]` 行里**，
  `run/logs` 是空的。
- 窗口要 ~1900×1000；`options.txt` 里 `guiScale:2`（`0`=auto 在大窗口上会选 4，反而更装不下）。

## 已知的坑（别再踩一遍）

1. **GameTest 模板路径**：`run/gameteststructures/*.snbt`（运行目录里的文件）。
   放 mod 资源里的 `data/<ns>/structures/*.nbt` 和 `data/<ns>/gameteststructures/*.snbt`
   **都不行**。
2. **`VsPilot` 是单例**：多个 GameTest 挤在同一个 `batch` 里会并行抢同一艘物理船，
   dock 用例必挂。每个用例给独立的 `batch=`。
3. **逐格坐标断言太脆**：VS2 自己会算质心偏移，改成数整个船体区域的方块数。
4. **控制台 GBK 乱码**：`gradlew runServer` 的输出别读，读 `run/logs/latest.log`。
5. **`retrieveLoadedPhysicsEntities()` 是无用探针**：它数的是 `VSPhysicsEntity` 物品，
   不是船的刚体。判断刚体在不在用 `VsBridge.physicsEntity` 之外的证据（速度/位移）。
6. **`deleteShip(level, ship, deleteBlocks, dropBlocks)` 第 3 个参数不是"铺回方块"**：
   `true` = 把世界包围盒挖成空气。VS2 没有"刚体还原成方块"的 API。
7. **给 PowerShell 5.1 跑的 `.ps1` 含中文必须存 UTF-8 带 BOM**，用
   `powershell -ExecutionPolicy Bypass -File xxx.ps1` 跑（`pwsh` 工具实际是 5.1）。
8. **`vs_mass` 数据包**：`data/barotrauma/vs_mass/ship_blocks.json`。
   自定义方块没被任何 `BlockStateInfoProvider` 认领的话，VS2 当它是空气
   （没碰撞体、不排水、穿模往下掉）。
9. **VS2 装配协程会事后清源区域**（2026-11-02 新发现）：`assembleToShip` 返回、
   质量对账 100% 之后，装配协程还会继续跑；船坞区块在无头服务器上不会 tick，
   等 20 tick 超时就 `Timed out waiting for chunks to start ticking after assembly!`
   然后强行续跑，**顺手把源区域再清一遍**——我们在装配后铺的排水体就是被它清掉的。
   凡是"装配后立刻在源区域写方块"的逻辑都必须每 tick 检查一次（`HullPlug.ensure`）。
10. **拆船铺回前先撤排水体、铺回后清舱内水**：那片体积装配后已经灌满水，
   不撤排水体方块铺不进去；不排水就是"拆回一艘满水的沉船"（隔离自检实测 26 格水）。
11. **VS2 2.4.11 原生不支持物理船破坏/使用**：jar 的混入清单里只有
   `feature.block_placement`（放方块），没有破坏 / `useItemOn` 的坐标重映射。
   我们已补 `ShipBreakMixin`（破坏船体快照里的方块，见 `VsPilot.tryBreak`）；
   右键交互仍靠声纳代理方块，掌舵入口 `/barotrauma ship sonar`。
12. **方块形态移动已彻底删除**，不要再加回来：`ShipMover`、`ShipState.tickMovement`、
   `/barotrauma ship move` 都不存在了。用户明确要"只有物理船会动"。
13. **物理船静态状态会跨世界泄漏**（2026-11-02）：单机退出世界后 JVM 不退出，
   `VsPilot` / `VsState` 的静态单例还在；新世界的 `StarterSub.ensure()` 会误判
   “初始潜艇已收成物理船”而跳过，同时客户端还会收到旧船 id 的水面包。
   已在 `ServerStoppedEvent` / `ServerStartedEvent` 调 `VsPilot.resetForServerLifecycle()`；
   `StarterSub.ensure()` 只跳过核心坐标等于初始潜艇核心的物理船。
   **不要再把 `VsState.active()` 当成“当前世界有物理船”**。
   已用客户端实测：世界 22 装船不拆 → 世界 23 启动清理并重新建造初始潜艇，日志见 PLAN §10.54。
14. **`hull_plug` 已彻底删除**（2026-11-02）：方块注册、`HullPlug` 类、模型/blockstate 和全部调用
   都已移除；新船不会再铺任何排水方块。旧存档里若还残留 `barotrauma:hull_plug`，加载时可能被
   当成缺失方块；需要旧存档兼容时再单独做迁移。

## 查资料的路子

见项目文档（Fandom API + 必需浏览器 UA；代理按本机环境配置）。
**`Ballast` 页面不存在、`Submarine` 页的 `==Ballast==` 是空段落**，别再重复查。

VS2：模组本体源码克隆在 `_vs2src\`（分支 `1.20.1/main`，
`ShipAssembler.kt` 是装配逻辑的全部真相）；核心 vs-core 是二进制，
API 类是 `barotrauma/libs/vs2-api-1.1.0.jar`，可以用 `javap -classpath` 查签名。

关键结论（README 里没写，从源码读出来的）：

- VS2 装船 = **先把世界里的方块挖成空气，再铺到同一维度的预留远处区块**（船坞）；
- `assembleToShipFull` → `moveBlocksFromTo`，等 20 tick 让区块开始 tick，
  超时就 `Timed out waiting for chunks to start ticking after assembly!` 然后强行继续
  ——**这个超时之后装配可能只成功一部分**，我们的对账就是防这个；
- `ShipAssembler` 的日志名是 `(Valkyrien Skies) Sandwich Factory`，grep 日志用这个。

## 用户提过的偏好

- 全中文回复，思考过程也尽量中文。
- 不理解的现象先看日志，别猜（他说过"你写完会看日志和维基吗"）。
- **每次开工先上网搜资料，再动手**（`AGENTS.md` §0 铁律）。
- 潜艇要**永远平行于海平面**（不能翻）——用户指定行为，不是原版行为。
- 外壳用透明方块方便看（已做：`barotrauma:test_hull`，`StarterSub` 已改用它）。
- 他明确反对"光搭测试架子不修 bug"：这轮我跑去写测试，被问
  "你在做啥 你不应该修复潜艇进水的bug吗"。**修 bug 优先于搭基础设施。**

## 正式环境（还没做）

PCL2 实例 mods 目录：
`D:\原神\pcl\.minecraft\versions\1.20.1-Forge_47.2.18-OptiFine_I6\mods`

里面已经有 `valkyrienskies-120-2.4.11.jar` 和 `kotlinforforge-4.12.0-all.jar`（都已启用）。
装我们的 jar 之前先处理掉重复 modId：`barotrauma_redux-1.0.0-r6 (1).jar` 和
`barotrauma_redux-1.0.0-r6.jar` 的 modId 相同，两个都启用会崩启动。

## 第三方技能（方案 C）

`AGENTS.md` §4。每次开工先跑：

```powershell
powershell -ExecutionPolicy Bypass -File _skills\verify-agent-skills.ps1
```

输出 `BROKEN` 就立刻告诉用户"方案 C 失效了"，然后跑 `install-agent-skills.ps1` 并重启宿主。

## 2026-11-02 13:45 物理船破口进水链路修复（当前构建）

用户反馈"水进不来 / 代码互相打架"，日志确认：物理船只调了 `tick()`，没有
`tickSeaFlood()`；同时每 10 tick 强制清船体体积里的水。已修：

- 物理船每 2 tick 推进 `tickSeaFlood`；去掉周期性强制清水；只在位移换格时清舱室格。
- 流体拦截改为只保护舱室格，船壳格让海水填满（去掉船周围干空气圈）。
- 船坞偏移：核心方块实体上报 + 扫 `ChunkClaim` 中心找声纳兜底；每秒对账船坞方块，
  破损后重分舱（`openPockets`）。
- 新增 `ShipBreakMixin` + `VsPilot.tryBreak`：物理船上左键砸船壳，服务端射线映射到
  船坞方块并删除；声纳台不可拆；生存模式掉物品。
- `floodRatio` 把连海空腔算进去；进水速率 2 → 20 格³/秒；破损重分舱保留反应堆/压载设置。

验证：

```
build --offline            ✅
L1（19 用例）              ✅
L2 GameTest                ✅ All 3 required tests passed
.smoketest-vs              ✅ 舱内水=0｜破口 40 tick 模拟水=2800/2800｜拆回 98 格
```

**测试注意**：旧存档里已经装配的物理船不会自动认领（`VsPilot` 不持久化）。
请用新世界/新船测试：`/barotrauma ship vs launch` → 左键砸船壳 → 等 1~10 秒看舱内水面。

**下面第 11 条已过时**：物理船现在可以破坏船体方块（`ShipBreakMixin` 自己做的映射）。

## 2026-11-02 14:10 反馈修复：空气隧道 / 舱底假水 / 水位归零

用户实测：船沉底、进水已生效，但（1）开过的地方变成空气隧道；（2）舱底只有一小滩水；
（3）底部破洞没有水压。日志定位到三个实现问题，已修：

1. **拦截体漏船壳棱角**：舱室 6 邻接外扩只到 81 格（5³ 船体缺棱/角），
   海水从漏掉的 44 格钻进来。现在拦截格 = **当前船体快照全部方块 + 舱室格**
   （`buildGuardCells`），破掉的格子从快照移除，真实海水只出现在破口那一格。
2. **船尾不回填**：新增 `refillVacated`，整数偏移变化时把旧拦截格中不再被新拦截格
   覆盖的空气回填成海水；只在水下回填（8 点采样），干洞里不造水。
3. **每砸一个新洞水位归零**：`replaceGeometry` 现在用 `seedWater` 把旧密闭/连海水量
   按新几何容积带过去；破口越大进水越快（`setBreachSize`，速率 × 破掉格数）。

另外：清理改为清 `guardCells`（船壳 + 舱室），装配瞬间灌进船壳层的水也会清掉。

验证：build ✅、L1（19）✅、L2（3/3）✅、`.smoketest-vs` ✅
（装配后 125 格水=0；破口 40 tick 模拟水 2800 单位；舱内真实水=0）。

**测试请用新世界**（旧存档里已装配的船不会被新客户端认领）。
新增客户端日志 `[water] ...`（每秒一条）和服务端 `[vs] 水面同步`，
如果舱内水面还是不对，把 `run-client/logs/latest.log` 里这两类日志发我。
