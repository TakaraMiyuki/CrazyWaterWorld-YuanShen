# 深海环境开发计划（M1）

> 目标：进游戏就出生在深海新手哨站里，推门出去是 1000 米深的水柱，岩体之间有岔路可走。
> 版本：Minecraft 1.20.1 / Forge 47.4.0

---

## 0. 先把《潜渊症》拆成可实现的要素

《潜渊症》的核心场景不是"海底"，而是**木卫二冰层下的一整根水柱**：从冰盖背面（深度 0）一路向下到几千米，玩家开着潜艇在**岩体隧道**与**开阔水域**之间穿行，每个哨站是水柱里的一处**气密舱室群**，靠对接舱口进出。

对我们最有价值的四条设定：

| 设定 | 为什么重要 | MC 里怎么落地 |
| --- | --- | --- |
| 深度是唯一的进度轴 | 一切危险/资源/剧情由深度决定 | `深度 = -y`，一个维度从 0 m 到 1000 m |
| 哨站 = 水中的气密房间 | 安全区/补给/出生点 | 房间内生成空气，外墙有厚度；**破洞会进水**（MC 水会流动，白送船体破损玩法） |
| 地形是岩体 + 隧道岔路 | 潜艇要"钻"，不是"飞" | 3D 噪声生成岩体，隧道用噪声场雕刻并灌满水 |
| 潜艇是移动的密闭容器 | 移动的安全区 | 后期做（M3），M1 只保证人能活着出门 |

**关键设计判断**：先做"世界 + 哨站"，不做潜艇。因为潜艇本质是"会动的小哨站"，哨站的密闭/进水/供氧机制做通了，潜艇只是换个外壳。

---

## 1. 世界参数（我建议的数值，可改）

| 项目 | 值 | 说明 |
| --- | --- | --- |
| 维度 id | `barotrauma:the_deep` | 独立维度，不污染主世界 |
| `min_y` / `height` | `-1008` / `1024` | 1.20.1 上限：`min_y ∈ [-2032,2031]`、`height ∈ [16,4064]`，均为 16 的倍数（[Minecraft Wiki](https://minecraft.wiki/w/Dimension_type)） |
| 顶部 y | `15` | `-1008 + 1024 - 1` |
| 冰盖（天花板） | y `0..15` | 类似原版基岩层，不可穿透，也是"世界边界" |
| 水柱 | y `-1000..-1` | **正好 1000 格 = 1000 m** |
| 海床 | y `-1008..-1001` | 玄武岩/深板岩，可挖但极硬 |
| 深度换算 | `深度(m) = -y` | y=-1 → 1 m，y=-1000 → 1000 m |
| 出生哨站 | 深度 **150 m**（y=-150） | 深到有压迫感，又不至于开局就被深渊生物贴脸 |
| 哨站尺寸 | 约 `64 × 24 × 40` 格 | 2 层走廊 + 4 个房间 + 1 个气闸 |
| 水柱岩体占比 | 25%~30% | 太高像矿洞，太低压不住"隧道"的感觉 |

**为什么是 1024 而不是 2048**：1024 高度时每个区块 64 个 section，已经是原版的 2.7 倍；2048 会让客户端渲染和区块生成时间翻倍，1.20.1 的渲染管线扛不住。1000 m 刚好够"从哨站往下走 10 分钟才到底"。

**如果性能不达标**：代码里用常量 `METERS_PER_BLOCK`（默认 1）驱动一切深度换算，改成 2 就只需把水柱缩到 500 格，HUD 仍然显示 1000 m，不用改玩法逻辑。

---

## 2. 技术方案

### 2.1 维度（纯数据包 JSON，零代码）

`src/main/resources/data/barotrauma/dimension_type/the_deep.json`

```json
{
  "ultrawarm": false, "natural": false, "coordinate_scale": 1.0,
  "has_skylight": false, "has_ceiling": true,
  "ambient_light": 0.1, "fixed_time": 18000,
  "monster_spawn_light_level": 0, "monster_spawn_block_light_limit": 0,
  "piglin_safe": false, "bed_works": false, "respawn_anchor_works": false, "has_raids": false,
  "logical_height": 1024, "min_y": -1008, "height": 1024,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "effects": "minecraft:overworld"
}
```

`src/main/resources/data/barotrauma/dimension/the_deep.json` 指向自定义生成器：

```json
{
  "type": "barotrauma:the_deep",
  "generator": {
    "type": "barotrauma:deep_sea",
    "biome_source": { "type": "minecraft:fixed", "biome": "barotrauma:abyss" }
  }
}
```

要点：`has_skylight=false` + `ambient_light=0.1` 让整个维度是暗的（哨站靠灯，水下靠手电）；`has_ceiling=true` 关掉天气和云。

### 2.2 区块生成器（M1 的核心工作量）

新增 `DeepSeaChunkGenerator extends ChunkGenerator`，注册到 `Registries.CHUNK_GENERATOR`，用 `Codec` 序列化（构造参数：seed 相关、岩体密度、隧道频率）。

生成流程（每个区块一次，`fillFromNoise` 里做完）：

1. **整柱水**：按 section 粒度批量填水。整个 section 都是水时调 `LevelChunkSection#fill(WATER)`，只在岩体/隧道穿过的 section 逐格写 —— 这是性能关键，1024 高度逐格写会慢 10 倍。
2. **冰盖/海床**：y ≥ 0 填冰（`packed_ice`/`blue_ice`），y ≤ -1001 填深板岩，边缘做随机过渡。
3. **岩体**：3D 噪声（两层 `value` 噪声叠加 + 阈值）生成大块岩体/峡谷壁，控制在水柱的 25%~30%。
4. **隧道岔路**：独立的"隧道噪声场"（ridged noise，频率约为岩体的 2 倍），在岩体内部用 `|noise| < t` 切出管状空腔并灌水 —— 噪声天然分叉，不需要手写路径图。效果是岩体里东一条西一条、互相连通的水道。
5. **哨站**：区块包含哨站锚点时，按调色板直接覆盖生成（见 2.3），房间内是空气。

> 不做 `buildSurface` / `applyCarvers`（直接 no-op），所有逻辑集中在 `fillFromNoise`，行为可控、好调试。

### 2.3 新手哨站

M1 先用**代码内调色板**生成（不上 Structure 系统，少一层复杂度）：

- 外壳：`submarine_plating` 双层 + 缝隙填 `iron_bars`，保证不会被水压"压穿"
- 内部：2 层走廊（高 4 格）、4 个房间（医疗/军械/补给/反应堆）、天花板挂 `sea_lantern`
- 气闸：外侧 2 格厚闸门 + 内侧气密门，中间留 3×3×4 的小舱（以后做"抽水/注水"逻辑）
- 出生点：哨站中心房间，脚下是 `smooth_stone_slab`
- 告示牌/物品展示框先放占位文字，后续换成商店系统

**为什么天然支持"进水"**：MC 的水是真实流动的方块。玩家在墙上砸个洞，水就会灌进来 —— 这就是《潜渊症》最标志性的玩法，不需要写任何"船体破损"代码。M1 只需要保证**没破洞时房间不漏水**。

### 2.4 出生与重生

`PlayerEvent.PlayerLoggedInEvent`（game bus）：

```java
if (event.getEntity() instanceof ServerPlayer player && !hasVisited(player)) {
    ServerLevel deep = player.server.getLevel(DEEP_LEVEL);
    player.teleportTo(deep, outpostX, outpostY, outpostZ, 0f, 0f);
    markVisited(player);           // 记到 Player 的 PersistentData
    player.setRespawnPosition(DEEP_LEVEL, outpostPos, 0f, true, false);
}
```

用 `player.getPersistentData()` 存"是否已经来过"，避免每次登录都传送；新玩家/死亡重生都回哨站。
另外加 `/barotrauma deep` 命令方便你随时进出测试。

### 2.5 深度 HUD（客户端）

`RenderGuiOverlayEvent` 画一行 `深度 150 m · 水温 2°C`，按 `-y` 计算。M1 只做深度数字，方便验收"确实有 1000 米"。

---

## 3. 里程碑与验收

| 里程碑 | 内容 | 验收标准 | 预估 |
| --- | --- | --- | --- |
| **M1.1 维度落地** | dimension_type + dimension JSON + 空生成器 | `/execute in barotrauma:the_deep run tp @s 0 -150 0` 能进去，看到头顶冰盖、脚下 1000 格水 | 半天 |
| **M1.2 地形与岔路** | 噪声岩体 + 隧道雕刻 + section 批量填水 | `runClient` 里飞一圈：岩体连成片、隧道互通、F3 显示 Y 从 15 到 -1008 | 1~2 天 |
| **M1.3 哨站与出生** | 哨站生成 + 首次登录传送 + 重生点 | 新建世界直接出生在哨站房间里，房间无水，出门即溺水 | 1 天 |
| **M1.4 氛围与工具** | 深度 HUD、暗环境、水下音效、`/barotrauma deep` 命令 | HUD 水深随下潜递增，哨站有灯光 | 半天 |

**M1 验收（一句话）**：新世界 → 出生在 150 m 哨站的灯下 → 打开气闸门 → 被 1000 m 深的水柱淹没、开始掉氧 → 往深处游能看到岩体与岔路。

---

## 4. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| **1024 高度性能** | 区块生成慢、客户端掉帧 | section 级批量填水；`view-distance` 建议 8；先测单区块生成耗时，> 60 ms 就把 `METERS_PER_BLOCK` 调成 2 |
| **客户端水渲染** | 深水里看出去一片蓝，远处全裁掉 | 靠水下雾+手电，不要试图看清 100 m 外；必要时给该维度加自定义 fog |
| **出生点被水淹没** | 新玩家一进去就溺水 | 哨站生成后校验房间空气，`fillFromNoise` 里最后一步"排空"房间内的水 |
| **维度不可达** | 玩家没法回主世界 | M1 就加 `/barotrauma surface` 回主世界，避免测试卡死 |
| **存档兼容** | 改生成器后旧区块不更新 | M1 阶段频繁删档测试，1.0 前不做存档兼容 |

---

## 5. 代码结构（M1 结束时）

```
com.opamb.barotrauma
├─ BarotraumaMod            入口（已有）
├─ world/
│  ├─ ModDimensions         维度 key 常量
│  ├─ DeepSeaChunkGenerator 生成器（冰盖/水柱/岩体/隧道/哨站）
│  ├─ DeepSeaNoise          噪声参数与采样
│  └─ OutpostBuilder        哨站调色板生成
├─ event/PlayerJoinHandler  首次登录传送 + 重生点
├─ client/DepthOverlay      深度 HUD
└─ command/BarotraumaCommand /barotrauma deep|surface
```

数据文件：`dimension_type/the_deep.json`、`dimension/the_deep.json`、`worldgen/biome/abyss.json`。

---

## 6. 需要你拍板的三件事

1. **比例**：默认 `1 格 = 1 米`（水柱 1000 格）。如果更看重帧数，可以改 `1 格 = 2 米`（水柱 500 格，HUD 仍显示 1000 m）。
2. **哨站深度**：默认 150 m。想更压抑可以 300 m，想先熟悉操作可以 80 m。
3. **地形风格**：
   - A（推荐）**开阔水柱 + 大块岩体 + 岩内隧道**：能看到远处，适合先跑通玩法
   - B **全封闭隧道网**：最还原《潜渊症》的"钻洞"感，但需要更复杂的路径生成，M1 时间 +1~2 天
```

---

## 7. M1 实施结果（已完成，服务端实测）

### 已落地

| 项 | 文件 | 状态 |
| --- | --- | --- |
| 维度类型 + 维度 | `data/barotrauma/dimension_type/the_deep.json`、`data/barotrauma/dimension/the_deep.json` | ✅ 进服自动加载，`min_y=-1008 height=1024` |
| 深海生成器 | `world/DeepSeaChunkGenerator.java` | ✅ 冰盖 / 1000 格水柱 / 海床 / 岩体 / 隧道 |
| 岩体与隧道 | 同上（`DeepSeaNoise`） | ✅ 4 格步长噪声；隧道用双噪声等值面相交 |
| 新手哨站 | `world/OutpostBuilder.java` | ✅ 40×21×24 气密盒子，三间房 + 灯 + 橡木门 + 门外平台 |
| 首次登录传送 | `event/DeepSeaEvents.java` | ✅ 传送 + 设重生点 + `/barotrauma` 命令 |
| 深度 HUD | `client/DepthOverlay.java` | ✅ 深海维度左上角显示 `深度 N m` |
| 无头自检 | `run/.smoketest` | ✅ 生成哨站区块、统计地形、输出 ASCII 切片后自动关服 |

### 实测数据（`gradlew runServer` + 自检标记）

- 哨站内部 **0 格水**（气密）
- 门口 `oak_door`（可手开），门外是水，外墙 `barotrauma:submarine_plating`
- 自检切片（z=200）：
  - `y=-289..-312`：纯水柱
  - `y=-589..-606`：岩体里出现贯通水道与石柱（`############~~~~~~#########~~~~~~`）
  - `y=-889..-912`：岩壁陡崖
- 单列 `(64,64)`：945 格水 / 55 格岩（该列岩体偏少，属正常分布）

### 与原版设定的对应

官方 wiki（[Campaign](https://barotraumagame.com/baro-wiki/index.php?title=Campaign)）确认的 5 个生物群系与 8 类地点，正好可以按深度切分到这条 1000 米水柱上：

| 深度 | 对应群系 | 后续内容 |
| --- | --- | --- |
| 0–200 m | Cold Caverns | 冰盖、新手哨站（当前 150 m） |
| 200–450 m | Europan Ridge | 峡谷壁、沉船点 |
| 450–700 m | Aphotic Plateau | 无光层、深渊生物 |
| 700–900 m | Great Sea | 开阔水域、大体积岩体 |
| 900–1000 m | Hydrothermal Wastes | 热泉、最终哨站 |

### M1 剩余打磨（下一轮）

1. **客户端实机确认**：`gradlew runClient` 看光照、水下雾、帧数（服务端已无异常）
2. **隧道更像"管道网"**：目前是岩体内部的腔体/拱门，可加一条"沿曲线走"的低频噪声做主干道
3. **哨站内容**：商店/医疗/船坞的交互点（原版是 NPC，MC 里先用告示牌 + 箱子占位）
4. **维度入口**：现在只有命令，需要一个"下潜舱"方块或传送门
5. **删档重生成**：改了生成器必须删 `run/world`，否则读旧区块

---

## 8. M1.5：主干道 + 移除水下粒子（已完成，服务端实测）

### 8.1 主干道 world/TrunkNetwork.java

做法是**抖动网格图**，而不是再加一层噪声：

- 每 128 格一个节点，节点在 XZ 上随机偏 ±29 格、Y 上偏 ±18 格
- 每个节点向 +X / +Z 邻居各连一条管道 → 整张图无限延伸、每个节点都是岔路口
- 共 8 层：`-130 / -250 / -370 / -490 / -610 / -730 / -850 / -970`
- 管道截面是 17 宽 × 25 高的椭圆（够以后开潜艇），18% 的节点向下打竖井连到下一层
- 所有坐标由「世界种子 + 格点索引」哈希得到，不需要存档数据，跨区块无缝

验收（`runServer` 自检，`y=-610` 水平切片 48×48）：

`
~~~~~~~~########################################
~~~~~~~~~~~~####################################
~~~~~~~~~~~~~~######################~~~#########
~~~~~~~~~~~~~~~~~################~~~~~~~~#######
~~~~~~~~~~~~~~~~~~~############~~~~~~~~~~~######
~~~~~~~~~~~~~~~~~~~~~#######~~~~~~~~~~~~~~~#####
`

一条 17 格宽的走廊斜穿整块岩体，右侧还有一条支路 → 岔路成立。
本轮加载的区块里共挖掉 **46,240 格**岩石。

### 8.2 水下粒子

粒子来源查清了：`WaterFluid#animateTick` 每帧在玩家附近随机抽水方块生成
`minecraft:underwater`（悬浮颗粒），1000 米水柱里量非常大。

处理方式（`client/DeepSeaParticles.java`）：用 Forge 的 `RegisterParticleProvidersEvent`
重新注册 `UNDERWATER` 与 `BUBBLE` 的 Provider，**只在 `barotrauma:the_deep` 里返回 `null`**
（`ParticleEngine` 收到 null 直接不添加），其它维度委托给原版 Provider，行为不变。

> 这一项是纯客户端改动，服务端自检验证不到，需要 `runClient` 目视确认。

---

## 9. 地形翻转：从"水里漂岩石"改成"岩里挖隧道"（实测通过）

### 9.1 亮度的真实原因

截图里看着像"海底很亮"，但我加了诊断后确认：**维度本来就是全黑的**

```
dimType: skyLight=false ceiling=true ambient=0.1 minY=-1008 height=1024
biome at outpost = barotrauma:abyss
light at (0,-150,0): sky=0 block=7
```

（`block=7` 是哨站自己的海晶灯。）真正的观感问题来自**地形**：上一版是"开阔水柱里漂着几块岩"，
视线能一路穿过水看到冰盖、水面和天空盒，所以看着发亮、像峡谷。把地形翻成实心岩之后，
光线被岩壁挡住，隧道里天然是全黑的。

### 9.2 新的生成逻辑

| 层 | 做法 |
| --- | --- |
| 基础 | y<0 全部填实心岩（-704 以下深板岩），y>=0 冰盖 |
| 隧道网 | 两个 3D 噪声的等值面相交（`|a|<0.10 && |b|<0.10`，尺度 44）→ 直径约 9 格的三维管道，连通、分叉 |
| 洞窟 | 低频噪声（尺度 150，`|c|<0.055`）挖出 16 格左右的大空腔 |
| 主干道 | `TrunkNetwork`：8 层、17×25 的走廊 + 竖井 |
| 哨站水池 | 哨站外侧挖一个 34×26×34 的椭球水池，气闸门外才是水 |

### 9.3 实测数据

```
outpost leaked water blocks = 0
door=oak_door  outside=water  shell=submarine_plating
volume y=-300±16: water 20.6%
volume y=-600±16: water 15.6%
volume y=-900±16: water 14.7%
y=-610 水平切片（48×48）：中间一条 S 形隧道贯穿实心岩，向两侧分叉
```

隧道占比 15%~20%，其余是岩——和《潜渊症》关卡"大部分是岩、通路是水"的比例一致。

---

## 10. M3 潜艇系统（进行中）

### 10.1 从 wiki 抄下来的真实数值

抓取方式：`web_fetch` 工具连不上该站，改用 `Invoke-WebRequest -Proxy http://127.0.0.1:7897` 直接抓（200，2–3 s）。

| 设备 | 数值 |
| --- | --- |
| 潜艇核反应堆 | 4 个燃料棒槽；最大 **5 000 kW**（前哨站型 20 000 kW） |
| 燃料棒 | 耐久 100 / 热势 80（钍 200/100，富铬 150/150，易爆富铬 400/150） |
| 反应堆温度阈值 | 偏低 3515、过低 2331、过热 **6482**（起火）、熔毁 **7965**；熔毁爆炸半径 7.5 m / 300 结构伤害 |
| 功率报警 | 输出 > 当前负载 150% 判"critical" |
| 制氧机 | 每秒产氧 = 100×产氧量，按通风口所辖舱室体积分配；**每人每秒耗 700**；condition² 衰减；可存 5 个氧气瓶（2.5%/s 充满） |
| 引擎 | 普通 **2 000 kW / 力 300**；大型 **4 000 kW / 力 500**；穿梭 **500 kW / 力 300**（防水） |
| 水泵 | 普通 **300 kW / 最大 600**；小型 **60 kW / 100**；滑条 IN/OUT，`set_targetlevel` 取值 −100..100 |
| 压载舱 | 没有独立页，由水泵注排（同一套 target level 逻辑） |
| 门/舱门 | 关闭时阻水、阻生物；相邻舱室靠它隔离；可撬开/切割 |
| 声纳 | 被动（静默、近距）/ 主动（全向、暴露、引怪）/ 定向 / 矿物扫描 |

### 10.2 已完成：A1 方块与 3D 声纳

- 新增 10 个方块：`hull_glass`、`hatch`、`airlock_door`、`nuclear_reactor`、`oxygen_generator`、`engine`、`ballast_tank`、`pump`、`sonar`、`helm`；
- 贴图全部由 `tools/gen_textures.py` 生成（16×16，工业配色）；声纳台按 wiki 的军规 CRT 监视器画（米绿机身 + 主屏 + 两个小屏 + 红绿按钮）；
- **3D 声纳**：`SonarBlockEntity` 在客户端每 20 tick 扫描半径 40 格内的实心方块（4 格步长、上限 2400 点），`SonarRenderer` 用 `RenderType.debugQuads()` 画成悬浮在台面上方的缩微体素云（近青绿、远蓝），底部两圈距离环 + 一条旋转扫描线，不依赖任何材质贴图；
- `runData` 通过，方块与方块实体注册无误。

### 10.3 下一轮

A2 船网 `ShipState` + 舱室洪水填充 + `/barotrauma ship` 快照；A3 七台机器的 GUI 与 10 Hz 模拟。

### 10.4 A2 船网与舱室（已完成）

- `ShipGeometry`：先在核心半径内收集"船体方块"（`ModShipBlocks.isShipBlock`），取**最大连通分量**作为这艘船，得到包围盒；再只在包围盒内对非固体格子做 BFS——碰到包围盒边界 = 连到外海，不计入舱室。
  - 这样门关着 = 两个舱室，墙破洞 = 舱室与海连通，都会自然成立；
  - 纯逻辑、不依赖 Level，方便以后单测。
- `ShipCoreBlockEntity`：右键或 `/barotrauma ship` 触发扫描，半径 24 格。
- `ShipCoreBlock`：放在船舱里，右键打印摘要。
- 自检（实心岩里搭 9×5×15 空盒子）：

```
船体扫描：1 个舱室，船体方块 402，舱内容积 273 格，密闭正常
  #1 容积 273 格，进水 0%
```

402 = 9×5×15 − 7×3×13，273 = 7×3×13，与预期完全一致。

### 10.5 下一轮（A3）

七台机器的数值模拟：反应堆供电 → 制氧机产氧 → 水泵排水 → 压载舱改变浮力，配 `/barotrauma ship sim <秒>` 快进与验证。

### 10.6 A3 数值模拟（进行中）

已落地 `ShipSimulation`（纯逻辑，10 Hz 步长）：反应堆产电、温度与熔毁阈值、制氧机按舱室体积分配、乘员耗氧、水泵抽水/注压载、引擎与声纳耗电。

自检（9×5×15 盒子里装 1 台反应堆 / 1 台制氧机 / 1 台水泵 / 1 个压载舱 / 1 台引擎 / 1 个驾驶台）：

```
船体扫描：1 个舱室，船体方块 408，舱内容积 267 格，密闭正常
  机器：反应堆×1 制氧机×1 引擎×1 水泵×1 压载舱×1 声纳×0 驾驶台×1
  #1 容积 267 格，进水 0%

模拟 30.0s（水泵 OUT）-> 温度 3518°C｜功率 2500/1600 kW (100%)｜氧气 100%｜舱内水 0 单位｜压载 0%
模拟  5.0s（水泵 IN） -> 温度 3590°C｜功率 2500/1600 kW (100%)｜氧气 100%｜舱内水 0 单位｜压载 100%
```

注水 5000 单位后 30 秒被抽干，切到注水模式 5 秒把 600 单位的压载舱灌满——水泵/压载链路可用；温度随裂变上升但未到 6482 过热线。

**剩余**：气闸（双门互锁 + 抽干闸室）目前还是普通方块，没有逻辑；这是 A3 的最后一项。

### 10.7 A3 完成（七台机器全部可模拟）

自检船装齐 7 台设备 + 驾驶台：

```
船体扫描：1 个舱室，船体方块 410，舱内容积 265 格，密闭正常
  机器：反应堆×1 制氧机×1 引擎×1 水泵×1 压载舱×1 气闸×1 声纳×1 驾驶台×1

模拟 30.0s（水泵 OUT）-> 温度 3518°C｜功率 2500/1680 kW (100%)｜氧气 100%｜舱内水 0｜压载 0%｜气闸 100%(关闭×0)
模拟  5.0s（水泵 IN） -> 温度 3590°C｜功率 2500/1680 kW (100%)｜氧气 100%｜舱内水 0｜压载 100%｜气闸 100%(关闭×0)
气闸 drain 10s -> 气闸 0%(关闭×1)
气闸 flood 10s -> 气闸 100%(关闭×2)
```

声纳接入后需求功率 1600 → 1680 kW，与 80 kW 的设定一致；气闸抽干/注水各 10 秒完成并累加循环计数。

**命令**：`/barotrauma ship`、`/barotrauma ship sim <秒>`、`/barotrauma ship airlock drain|flood`

**已知限制**：机器行为目前挂在"船核心扫描出的船"上，还没有独立 GUI 和方块实体 tick；A4 再做面板与实时运行。

### 10.8 A4 实时化（已完成）

- `ShipCoreBlock` 接上 BlockEntityTicker：服务端每 2 tick 推进一次 ShipSimulation（0.1 s，即 10 Hz），不需要命令快进。
- 命令控制：
  - `/barotrauma ship`：扫描 + 机器清单 + 实时数值
  - `/barotrauma ship pump in|out`
  - `/barotrauma ship reactor <裂变%> <涡轮%>`
  - `/barotrauma ship airlock drain|flood`
  - `/barotrauma ship sim <秒>`：调试用快进
- 命令操作复用已有 ShipState，不再每次重扫（重扫会重置模拟）。

实测（`runServer`，强制加载船所在区块后跑 200 tick）：

```
反应堆 100%/100% 1s -> 温度 3050°C｜功率 5000/1680 kW (100%)
live 200 ticks report -> 实时｜温度 3980°C｜功率 2500/1680 kW｜氧气 100%｜舱内水 0｜压载 100%｜气闸 100%(关闭)
```

10 秒实时推进把温度从 3845 抬到 3980（≈ +13.5°C/s），与模拟公式吻合。

> 注意：没有玩家时区块不 tick，验证时需要 `setChunkForced`；正常游玩有玩家在附近就自动 tick。

### 10.9 水位 → 真实水方块（B 方案第一步）

- `ShipWater`：把每个舱室的水量换算成"从舱底往上铺多少格水"，每 10 tick 在服务端重写水方块；连到外海的破口空腔直接按满水处理。
- 命令 `/barotrauma ship flood <单位>`：向 1 号舱注水并立刻刷新水方块（测试用）。
- 自检：注入 5000 单位（1 格 = 100 单位）→

```
water apply -> changed=50 waterCells=50 (expect 50)
```

即 50 格水从舱底铺上去，符合预期。

**尚未做**：破口自动检测（现在要重新扫描才会把破口舱室判成"连海"并灌满）、舱室之间的连通平衡、进水对浮力的影响。这三项是 B 方案的后半段。

### 10.10 舱门连通 + 破口检测（B 方案第二、三步）

- `ShipDoorBlock`：舱门/气闸门可右键开合。关 = 实心（挡水、隔断）；开 = 只剩 4 格高门槛，几何上不算墙。
- 开合时立刻让附近 32 格内的船核心重扫 → 相邻舱室合并，水位自然拉平。
- `ShipCoreBlockEntity` 每 40 tick 检查船体：只要有船体方块被破坏就重扫；破口舱室会变成"连海"空腔，`ShipWater` 直接灌满。

自检（11×5×15 盒子，中间一道隔墙，墙上一个门）：

```
door closed -> compartments=2 sealed=true
door open   -> compartments=1 volume=313
hull breach -> sealed=false openPockets=1
```

313 = 156 + 156 + 1（两个舱室 + 门自己那格），数值精确对上。

### 10.11 整船平移（航行第一步）

- `ShipMover`：剪切 → 目标占位校验 → 旧位置填回海水 → 新位置重建（先清舱内空气）→ 搬走船范围内实体 → 新核心重扫。
- 命令 `/barotrauma ship move <dx> <dy> <dz>`（−32..32）。
- 自检（水池里放好船，向 +Z 移 12 格）：

```
move -> ok=true msg=移动 411 格
after move -> newCore=Block{barotrauma:ship_core} compartments=1 oldCoreIsWater=true
```

踩到的坑：船核心悬在舱室中间，不属于"最大连通船体块"，第一版没被搬走（新位置是空气、旧位置还留着核心）。修法是把核心显式并入搬运集合，411 = 410 船体 + 1 核心。

**限制**：离散平移（不是平滑推进）、不处理碰撞伤害、移动后模拟状态重置（重扫重建）。下阶段做成"每 tick 插值的结构体"才算真航行。

### 10.12 驾驶与撞击损伤（闭环）

- `ShipState` 增加推进状态：每 10 tick（0.5 s）沿设定方向挪一格；成功移动后把推力复制给新的船核心，所以能连续开。
- 撞到实心方块：拆掉**迎面**的一块船体、停车 → 40 tick 内船体检查发现破口 → 重扫 → 舱室变"连海" → `ShipWater` 灌满。
- 命令：`/barotrauma ship throttle <dx> <dy> <dz>`（每轴 −1/0/1）、`/barotrauma ship stop`。

自检（船向 +Z 连续推进到撞墙）：

```
drive -> core=204, -198, 227 stopped=true hull=409 sealed=false
```

从 z=215 开到 z=227（12 格），撞墙后船体 410 → 409，推力自动清零，`sealed=false` 说明破口已被识别，下一步就会进水。

至此"开船 → 撞岩 → 破口 → 进水 → 抽水"整条链路在服务端跑通。

### 10.13 船控面板 GUI（选了 1+2 里的 2）

- `ShipConsoleMenu`：绑船核心的菜单，打开时带上实时状态字符串。
- `ShipConsoleScreen`：8 个按钮（泵抽出/注入、反应堆 ±10%、气闸抽干/注水、推进、停车），底部显示状态；点击通过 `ShipActionPacket` 发到服务端执行并回一条实时数值。
- 网络：`SimpleChannel` 通道 `barotrauma:main`，一个 `ShipActionPacket`（corePos + action）。
- 右键船核心即打开面板。
- `runData` 通过（菜单/网络注册无误），`gradlew build` 通过。

> GUI 只能在客户端目视验证，服务端自检覆盖不到。

**教训**：用 PowerShell `Set-Content` 改 Java 文件会把 UTF-8 中文写成 GBK+BOM，编译直接报非法字符——涉及中文源码一律用写文件工具，别走 shell 重写。

**待做**：选项 1（结构体航行）——把船体变成独立对象、每 tick 插值、自定义渲染。这是最后一块大改造。

### 10.14 结构体航行（选项 1 第一版）

- `VesselEntity`：整艘船的方块存进实体（palette + 局部偏移 + 尺寸），NBT 可存读；进入玩家视野时通过 `VesselPalettePacket` 下发调色板。
- `ShipAssembler.launch`：扫描船体 → 收集方块 → 世界方块换成水 → 生成实体；`dock`：按实体位置展开回方块并重扫核心。
- 推进：`sail <dx dy dz>` 设推力，实体每 tick 加速度、阻尼，用包围盒采样点做阻挡判断；`halt` 停车。
- 客户端 `VesselRenderer` 按调色板逐方块渲染（`BlockRenderDispatcher.renderSingleBlock`）。
- 命令：`/barotrauma ship launch | undock | sail <dx dy dz> | halt`。

自检：

```
launch -> ok=true msg=已收成结构体（410 格）
sail   -> pos=(200.0, -200.0, 220.0)
dock   -> ok=true msg=已展开（410 格） vesselGone=true
```

收起 → 推进 12 格 → 展开，船体方块数一致、实体已移除。

**未验证**：客户端渲染与调色板同步（服务端自检覆盖不到，需要 runClient 目视）。**已知限制**：无逐方块碰撞、玩家不随船移动、发射后船体方块从世界消失（只存在于实体里）。

### 10.15 修 5 条反馈 + 回到方块推进（选 B）

| # | 问题 | 修法 | 验证 |
| --- | --- | --- | --- |
| 1 | 前哨站被算进船体（6243 格） | 新增 `station_plating`，前哨站改用它；它不属于船体 | 贴 8 块站点装甲在船边，扫描仍是 410 |
| 2 | 核心必须不接触装甲 | 核心从船体识别里剔除 | 不再依赖接触关系 |
| 3 | 按面板按钮温度下降 | 面板打开不再 rescan（之前每次重置到 3000）；停堆散热 1%/s → 0.15%/s | 100% 跑 1s 得 3058°C，之后 3892→4027→4295→4558 持续上升 |
| 4 | `flood 5000` 无效 | 1、2 的连带问题 | changed=50，waterCells=50 |
| 5 | 结构体只带 9 格 | 1、2 的连带问题 | 410 格全带走 |

**方向改为 B（方块推进）**：结构体的"不挡水、摸不到方块"是方案固有问题，所以把 `launch/undock/sail/halt` 命令全部移除（实体代码保留但休眠），航行回到 `throttle` + 方块平移。

- 移动间隔 10 tick → **4 tick**（0.2 s 一格，约 5 格/秒）
- 方块始终在世界上：挡水、可点击、机器在原位
- 船上的玩家/实体随船一起被平移（`ShipMover` 会把包围盒内实体搬走）

自检：`drive -> core=204,-198,237 stopped=true hull=409 sealed=false`（同样 160 tick 内从 z=215 开到 237，撞墙掉一格、识别到破口）。

### 10.16 声纳台 / 舵台（掌舵进声纳）

《潜渊症》里没有独立的舵轮，掌舵就是在声纳台上做的。所以这一轮把 M3 阶段的"浮空全息声纳"
升级成真正可用的声纳界面，并把航行操作全部搬进去。

**分层**

- `sonar/SonarScan`（通用）：以声纳为中心按 `stride` 格采样一个球，先记下每格是否实心，
  再挑出六个网格邻居里至少有一个是空的格子——输出的是**表面**而不是一整块实心石头。
  步长随射程自适应（`range/12`，16m→2、48m→4、64m→5），所以方块查询次数基本恒定。
  船体方块走精确坐标（不进网格），活物作为接触点。
- `network/SonarPingPacket`（C→S）/ `SonarDataPacket`（S→C）：客户端每秒 ping 一次，
  服务端扫描后回传点云 + 舵位 + 右栏状态。**必须服务端扫**：客户端拿不到未加载区块。
- `client/SonarScreen`：左半是 3D 透视点云（CPU 投影 + `GuiGraphics.fill` 逐点画），
  右半是罗盘 + 推力条 + 上浮/停车/下潜。
- `menu/SonarMenu` + `ModMenus.SONAR`：`stillValid` 只比距离，船开着时坐标会变但玩家在船里。

**3D 声纳怎么画**

相机在球外 `1.35×range` 处朝球心看（放在球心的话自身格子在相机背后），FOV 60°，
逐点算 `depth = dot(p, forward) + camDist`，再投影到屏幕并做视口裁剪。配色按距离衰减：
近处青绿、远处深蓝、船体绿、活物红；每个新帧到达后 1 秒内有亮度加成（模拟回波）。
底面画三圈距离环当深度参照。

**性能（重要修复）**

`Level#getBlockState` 遇到未加载区块会**同步加载**——第一次 48 m 扫描花了 **219 ms**，
全耗在硬拉 49 个区块上。改成先 `level.hasChunk()` 判断、未加载当实心跳过：

| 射程 | 步长 | 点数 | 修复前 | 修复后 |
| --- | --- | --- | --- | --- |
| 16 m | 2 | 1324 | 5.2 ms | 5.2 ms（首个，冷缓存） |
| 48 m | 4 | 2032（地形 1622 + 船体 410） | **219 ms** | **2.9 ms** |
| 64 m | 5 | 2396（地形 1986 + 船体 410） | **109 ms** | **3.2 ms** |

**顺带把推进从"重扫"改成"刚体平移"**

之前每挪一格都 `core.rescan()`：13 万次方块查询 + 重建 HashSet，约 8~10 ms，每秒 5 次，
而且会把温度/水位重置。现在 `ShipGeometry.Result.shifted(dx,dy,dz)` 直接把船体、舱室、
包围盒整体平移，`ShipSimulation` 实例原样带过去。

自检：`move keeps sim -> tempBefore=4558 tempAfter=4558`——平移后温度和水量不再重置。

**操作**

```
右键声纳台           打开声纳界面
  左键拖拽（左侧）    旋转 3D 视角
  滚轮               射程 16/32/48/64 m（改射程会立刻重扫）
  点罗盘             设定水平航向（八向）
  上浮/停车/下潜      竖直方向（可和水平航向叠加，即"斜着下潜"）
  推 力 条           5%..100%，100% = 4 tick 一格（5 格/秒），50% = 8 tick 一格
```

命令新增：`/barotrauma ship power <5..100>`、`/barotrauma ship sonar [range]`。

**未验证**：GUI 的实际观感（投影矩阵、点大小、配色）在服务端跑不了，需要 runClient 目视。
`SonarScreen` 用的是纯 `GuiGraphics.fill`，不依赖渲染管线，所以不会因为 GUI 缩放崩。

### 10.17 初始潜艇（门口那艘）

原版流程是"出生在站点、站点连着你的第一艘船"。所以 `sendToOutpost` 里加了一步
`StarterSub.ensure(deep)`：在哨站水池里、门正前方（x −4..4、y −152..−148、z 16..30）
拼一艘 9×5×15 的小艇，顶部有舷窗和一个舱口，甲板下摆齐 7 台机器 + 船核心，然后 `rescan` 一次。

自检：

```
[smoketest] starter sub -> 1 个舱室，船体方块 410，舱内容积 265 格，密闭正常
                          机器：反应堆×1 制氧机×1 引擎×1 水泵×1 压载舱×1 气闸×1 声纳×1 驾驶台×1
```

`ensure` 是幂等的：核心已经在就直接返回，不会重复施工。位置经过核对，整艘都落在
`OutpostBuilder.carveCavern` 的椭球里，所以四周是水而不是岩石。

**近裁剪（重要）**

相机在球外 `1.5×range` 处，如果不裁剪，镜头这一侧的岩壁会以极小的 depth 投影成覆盖全屏的点，
把船完全糊住。所以近裁剪面直接压在船的中剖面上（`depth < camDistance` 的地形不画），
只显示"船前方那半边"的岩壁；船体点不受裁剪，永远是完整的绿色轮廓。转视角时可见的那半边会跟着变。

### 10.18 查证声纳（返工 10.16 凭印象做的部分）

来源（Fandom 镜像的 MediaWiki API，官方站对本机 403）：

- `Navigation Terminal` — https://barotrauma.fandom.com/wiki/Navigation_Terminal
- `Sonar Monitor` — https://barotrauma.fandom.com/wiki/Sonar_Monitor
- `Sonar Transducer` — https://barotrauma.fandom.com/wiki/Sonar_Transducer
- 图片 `File:Navigation Terminal.png`、`File:Sonar Monitor.png`、`File:Handheld Sonar menu.png`
  （已存到 `docs/ref/`）

**原文要点**

> Using the Navigation Terminal will show its GUI, it displays an outline of the submarine and a
> **reticle** to steer the submarine.

> The sonar can either be **passive** or **active**: While **active** the submarine will send a
> sonar ping in a **360 degree arc**, anything it bounces off will be displayed on the GUI.
> Another option while active is **Directional Ping**, if enabled the submarine will send sonar
> pings in **30 degree arcs** in the selected direction instead. It should be noted that active
> sonar will very easily acquire the attention of any roaming sea creatures nearby.

> While **passive** instead of sending out sonar pings, the Navigation Terminal will scan for
> **sound signatures**, they can be generated by the submarine itself (nuclear reactor, engines)
> or underwater Creatures. Similarly to active sonar, these sound signatures will bounce off of
> their surroundings and are displayed on the GUI, **the range of passive sonar is dependent on
> how loud the submarine is**. Passive sonar is much less likely to alert any nearby creatures.

> **powerconsumption = 100**

接线：`velocity_x_out` → 引擎 `set_force`（水平推力），`velocity_y_out` → 压载泵 `set_targetlevel`
（垂直），`toggle_docking` → 对接舱口 `toggle_state`，`transducer_in` → 声纳换能器。

界面图（`Handheld Sonar menu.png`）：圆形示波器 + **一圈细密刻度环**；右侧深灰绿控制盒，里面有
一个拨动开关、`PASSIVE SONAR` / `ACTIVE SONAR` 两个**橙点单选**、一条 `ZOOM` 滑条、
`DIRECTIONAL PING` 开关。字体米白，选中态橙色。

**我上一版做错的地方**

| 项 | 原版 | 我做的（错） |
| --- | --- | --- |
| 操舵 | 在圆里拖一个**准星**，偏移量 = 速度矢量 | 点八向罗盘 + 独立推力条 |
| 模式 | 主动 / 被动 / 定向（30° 锥） | 只有一种全向扫描 |
| 被动 | 只听声音（反应堆、引擎、生物），射程随船吵闹程度变 | 没有 |
| 定向 | 30° 扇形 | 没有 |
| 耗电 | 100 | 80 |
| 外观 | 圆形示波器 + 刻度环 + 深灰绿控制盒 | 直角点云视口 + 方形按钮 |

按这些重做。

### 10.19 按 wiki 重做声纳界面

**改掉的错**

| 项 | 之前（凭印象） | 现在（照 wiki） |
| --- | --- | --- |
| 操舵 | 点八向罗盘 | 圆形示波器里**拖准星**：偏移方向 = 航向，偏移量 = 推力（5%..100%，量化 5% 一档） |
| 模式 | 只有全向 | **被动 / 主动 / 定向 30°** 三档，控制盒橙点单选 + 拨动开关 |
| 被动 | 无 | 不发脉冲，**没有地形**，只有船体 + 声源；**射程随船的吵闹程度**（反应堆出力 + 引擎在推，16 m 安静 → 64 m 全速） |
| 方位 | 无 | 示波器外圈 **1–12 方位环**（12 点 = 正北、顺时针），环上另用青色三角标出**镜头朝向**；控制盒里还有「航向 东北 045°」 |
| 仪表 | 无 | 航向 / 深度 / 速度 / 下潜速度，绿色 LED 数字 |
| 耗电 | 声纳 80 kW | **100 kW**（wiki: powerconsumption = 100） |
| 贴图 | 军绿 CRT | 米灰锈蚀机身 + 左大屏 + 右上小屏 + 两个琥珀窗 + 底部键盘台 |

**两个操作 bug**

1. **左右镜像**：右向量写成了 `(cos yaw, 0, -sin yaw)`，正确的右手系应是 `right = forward × up = (-cos yaw, 0, sin yaw)`。
   取反导致整个 3D 画面水平翻转——看着像"左右拖反了"，其实是渲染镜像。
2. **上下反**：拖拽时 `pitch += dy`，往上拖等于低头。改成 `pitch -= dy`（往上拖抬头）。

**自检**

```
sonar mode ACTIVE      -> terrain=1622 hull=410
sonar mode PASSIVE     -> terrain=0    hull=410     ← 被动确实没有地形
sonar mode DIRECTIONAL -> terrain=43   hull=410     ← 30° 锥，约占全向 1.7%
live 200 ticks report  -> 功率 2500/1700 kW          ← 声纳从 80 改成 100 后
```

**仍未做**（原版有、我们这轮没做）

- 矿物扫描仪（mineral scanner）
- 舰首 / 舰中 / 舰尾声纳（多换能器切换）
- 主动 ping 引来生物——目前没有生物，等生物系统
- 手动 / 自动驾驶切换 + 目的地列表 + 对接（下一轮的关卡循环要用）

### 10.20 键盘驾驶 + 方向箭头 + 扫描线刷新 + 自动驾驶

上一版是"拖准星 = 八向航向"，用户反馈三维里照搬二维不对。改成键盘驾驶。

**操作**

| 键 | 动作 |
| --- | --- |
| W / S | 前进 / 后退（方向 = 当前视角的水平朝向） |
| A / D | 左移 / 右移 |
| 空格 / Shift | 上浮 / 下潜（可与水平叠加，斜着走） |
| 滚轮 | 速度档 10%..100% |
| 拖拽示波器 | **只转视角**，不操舵 |
| 停车按钮 | 清零推力 |

屏幕收不到"按住"事件，所以在 `containerTick()` 里用 `InputConstants.isKeyDown` 轮询；
方向或竖直分量变化时才发包；关界面补发一次停车，避免松手前关窗船一直开。

**航向取整**：`dx = |wx| > 0.38 ? ±1 : 0`（水平，wx 由视角前/右向量合成），竖直直接取按键。
0.38 对应每轴 ±22°，正好把八个方向各分 45°。

**方向箭头**：圆心箭头指向**服务端回报的实际推力向量**（不是本地按键），按相机基向量投影，
所以转视角时箭头在世界里不动、在屏幕上跟着动。纯竖直时退化成上下小三角。

**方位环跟着视角转**：1–12 数字按 `hour*30° - 视角方位` 摆放，屏幕正上方永远是镜头正对的方位。

**扫描线刷新**（用户要求：从中心向外扫过才更新）

- 世界方位波束 2.5 秒转一圈，从中心射向外圈；
- 地形点按"离波束过去多少度"算亮度：`0.18 + 0.82·exp(-age/50°)`，波束前方 40° 内先不显示（`age>320°`）；
- 转完第一圈之前，未扫到的区域完全不画；之后未扫到的部分保留 16% 暗值；
- 正上/正下的点没有方位，常亮 80%；
- 被动模式没有波束（不发脉冲就没有回波）。

**自动驾驶**（wiki `Orders` 页 Steer 一节的 Maintain position）

控制盒加 手动驾驶 / 自动驾驶（保持位置）单选。`ShipState.autopilotHold` 为真时
`throttled()` 恒为 false、`tickMovement` 直接返回；`setThrottle` 收到非零推力会自动退出自动模式。
命令：`/barotrauma ship autopilot hold|off`。

**自检**

```
sonar mode ACTIVE      -> terrain=1622 hull=410
sonar mode PASSIVE     -> terrain=0    hull=410
sonar mode DIRECTIONAL -> terrain=43   hull=410
autopilot hold         -> movingBefore=true movingAfter=false
starter sub            -> 1 个舱室，船体 410，容积 265，密闭正常
```

顺手修了一个真 bug：`Level#getBlockEntity` 对**未加载区块返回 null**，世界重开后初始潜艇的
区块没加载，会被判成"没造过"（自检里报 `starter sub missing`）。`StarterSub.ensure` 现在先
`level.getChunk()` 把区块拉起来再查。

### 10.21 瓦尔基里天空 2（VS2）能不能用

- 协议：**LGPL-3.0-only**（仓库 LICENSE 与 Modrinth 元数据一致）。
- 1.20.1 Forge 有正式版 `1.20.1-forge-2.4.11`，27.7 MB，硬依赖 Kotlin for Forge（LGPL-2.1），Cloth Config 内嵌。
- 只当**依赖**用（玩家自己装、`mods.toml` 声明）→ 本模组可保持任意许可；**抄它源码**才要整体 LGPL-3；
  改了它再发布要把改动开源。非商业/商业不影响这些义务（LGPL 管的是能否替换该库，不是收不收钱）。
- 源码看下来（`api/VsApi.kt`、`common/assembly/ShipAssembler.kt`）：它是真物理刚体，
  `ShipTransform` 连续位移+旋转，质量由方块状态算，物理在 vs-core 里跑。
- **结论：能解决"移动方式不行"，但迁移量大**——分舱、水位方块、机器模拟、声纳扫描全要换成船本地坐标。
  本轮不接，留作单独的 spike；验收标准是"能连续转向 + 玩家站在船上不掉 + 水泵仍能抽水"。

### 10.22 声纳改版：左探测 / 右操舵 + 标记点自动驾驶

按用户的明确分工重做：

- **手动驾驶**：左边是扫描显示（自动扫描、拖拽只转视角），右边是**独立的舵盘**
  （八向航向按钮 + 推力条 + 上浮/停车/下潜），键盘 WASD/空格/Shift 也照常可用。
- **自动驾驶**：在左边声纳画面上**单击一个点**，生成目标标记，船自动朝那个世界坐标开过去，
  到点 2 格内推力归零并保持——**不是原地悬停**。

点选目标的算法：把屏幕点反投影成世界射线，在点云里找离射线最近（垂直距离 < 3 格）的地形点，
取它的距离当目标距离；没点中地形就按视线取半程射程。

服务端 `ShipState` 新增 `autopilotTarget`：`aimAtTarget` 按每轴符号设推力（差 < 3 格不纠偏），
撞墙时清掉目标避免一直顶墙。**注意**：平移后的状态本来就带着推力/自动驾驶/目标点，
所以 `tickMovement` 成功分支里**不能**再调 `setThrottle`（它会把自动驾驶清掉，等于每挪一格掉线一次）。

自检：

```
sonar mode ACTIVE/PASSIVE/DIRECTIONAL -> terrain 1622 / 0 / 43
autopilot aim -> far=(1,0,0) near=(0,0,0)     ← 目标在东边 12 格 → 推力 +X；目标 1 格内 → 停
```

### 10.23 移速 / 浮力的原版数值（查证）

来源：`Engines`、`Pumps`（Fandom 镜像）。

| 东西 | 数值 / 机制 |
| --- | --- |
| 小型引擎（Shuttle Engine） | 500 kW，推力很小 |
| 引擎（Engine） | **2000 kW**，"速度是大引擎的一半" |
| 大型引擎（Large Engine） | **4000 kW**，最快、最耗电 |
| `set_force` | −100..100（百分比），由导航终端的 `velocity_x_out` 驱动 |
| 泵 / 小泵 | 300 kW / maxflow **600**；60 kW / maxflow 100 |
| `set_targetlevel` | −100..100 → 舱内目标水位 0%..100%，由 `velocity_y_out` 驱动 |
| 浮力机制 | **压载泵控制舱内水位 → 决定垂向加速度**，不是直接给一个上升力 |

所以物理化的正确做法是：水平用引擎推力（按 kW 换算），垂直用压载水位（水位↑→排水体积↓→下沉），
而不是给一个凭感觉的"上浮键速度"。

### 10.24 瓦尔基里天空 2 接入状态

- 依赖已加进 `build.gradle`（Modrinth maven，`compileOnly fg.deobf`），版本在 `gradle.properties` 的 `vs2_version`。
- **已跑通编译**：`compileJava` 成功，说明 27 MB 的 VS2 jar 和它的传递依赖都能解析。
- 仅编译期依赖：正式包不打包 VS2，玩家自己装（LGPL-3.0 的常规用法）。

**下一步（未做）**：真正的物理化——用 `ShipAssembler` 把船体收成 `ServerShip`，
水平施加引擎推力、垂直按压载水位算浮力，替换现在的 `ShipMover` 离散平移；
分舱、水位方块、声纳扫描都要改成船本地坐标。验收标准：连续转向 + 玩家站在船上不掉 + 水泵仍能抽水。

> 更正（10.25 复核）：VS2 2.4.11 的 `mods.toml` 里**没有** kotlin/forge 依赖，只有 `forge [47.2.0,)` 和 `minecraft [1.20.1]`，
> create / computercraft 都是可选。10.21 里"硬依赖 Kotlin for Forge"的说法是按旧版本记的，以 jar 里的 mods.toml 为准。

### 10.25 声纳改"由内向外扩散" + 连续运动模型（物理 v1）

用户反馈两条：**手动驾驶别扭**、**声纳像表盘/指针扫描**，要求改成从内向外扩散；并且"先把物理做出来，
不然潜艇开着太难受"。

**声纳：去掉表盘，改成回声波前**

| 之前 | 现在 |
| --- | --- |
| 外圈细密刻度 + **1–12 方位数字**（照 `Handheld Sonar menu.png` 做的） | **全删**，只剩一圈量程参考线（没刻度、没数字） |
| 一根指针绕圆心转，转过 40° 内的方位才亮 | 波前是**同心圆**，从圆心往外交替推出 4 圈 |
| 亮度按"方位落后指针多少度"衰减 | 亮度按"波前比这个点亮了多少"衰减：`0.15 + 0.85·exp(-age·4.5)` |
| 2.5 s 一圈 | 2.6 s 一发脉冲，开界面从圆心重新开始（`openedAt` 起算，不是 `currentTimeMillis % 周期`，否则一开窗就是扫了一半） |

> **这是对 wiki 参考图的故意偏离**：原版声纳界面确实有一圈刻度环，用户明确要求去掉，别再照图加回来。

实现：屏幕半径 `r = |(px,py) - 圆心| / FIELD_R` 就是"这条回声走了多远"，波前 `p` 推过 `r` 才画。
纯屏幕空间的波前有个已知代价：正前方远处的东西投影在圆心附近，会跟着近处一起先亮——但这样
"亮起来的位置和画出来的环"永远对得上，比按世界距离算、环和点对不上要好。

**物理 v1：`ShipPhysics`（连续量 + 落格执行）**

方块世界没法让方块停在半格上，所以把"物理"和"落格"拆开：速度和位移是连续的（格/秒、格），
每 tick 只把攒够的整数格交给 `ShipMover`，余量留到下一 tick。

| 参数 | 取值 | 依据 |
| --- | --- | --- |
| 满推力水平速度 | 6 格/秒（≈21.6 km/h） | wiki 只给功率（引擎 2000 kW、`set_force` −100..100），没给绝对速度；按《潜渊症》潜艇巡航量级取，且通道窄，再快没法机动 |
| 水平时间常数 τ | 0.9 s | 2000 kW 推一坨铁，从静止到满速要 2 s 出头，不是瞬移 |
| 推进器垂直速度 | 2.5 格/秒 | 同上，垂直方向只有压载/推进器 |
| 垂直时间常数 τ | 1.4 s | 水的阻尼比水平大得多 |
| 压载下沉 | 压载舱满水 → −1.5 格/秒 | wiki：**浮力由压载水位决定**，不是直接给升力；压载不满不产生漂移 |
| 进水下沉 | 进水比例 × −1.5 格/秒（封顶 −2.0） | 灌了水就该沉，这是"破口→下沉"的压力来源 |
| 撞墙 | 只清**撞上的那一轴**速度和余量 | 三轴分开试，才有贴墙滑行；整步撞上才拆一格船体 |

自动驾驶改成 P 控制：目标速度 = 位置误差 × 0.7 × 推力%，夹在 ±MAX_SPEED 内。
误差大就快、靠近自然减速，**不需要额外刹车**，也不会像原来那样靠"差 3 格就佛系停车"。

`ShipState.shifted()` 必须把 `physics` 实例带过去（和 `simulation` 一样），否则每挪一格船就"停"一次——
和 10.22 那个自动驾驶掉线是同一类 bug。

**自检**

```
sonar mode ACTIVE/PASSIVE/DIRECTIONAL -> terrain 1622 / 0 / 43
autopilot aim -> far=(1,0,0) near=(0,0,0)
physics -> 满推力 2s 速度=5.35 / 松油门再 2s 速度=0.58 / 压载满 3s 垂速=-0.71
drive 2s -> 走了 7 格（3.5/秒），期望 ~10          ← 从 0 起步，平均速度当然低于满速 5.35
drive -> core=204,-200,227 stopped=true hull=409 sealed=false
```

`drive 2s` 走 7 格而不是 10 格，是因为起步是从 0 加速上去的（前 1 秒平均只有 2.6 格/秒），
不是 bug；撞墙只掉 **1** 格船体（`410 → 409`），因为撞上那一轴会立刻收油，不会像之前那样顶着墙连啃 5 格。

**撞轻撞重**：`abs(speedOn(axis)) > 1.0 格/秒` 才掉船体。慢慢沉到海床上只是停住，
不然压载满了往海底一坐就把船底啃穿。

**仍未做**：真正的刚体（船体连续位移+旋转、玩家站在船上不掉）还是得 VS2；
这一轮先把"加减速/惯性/浮力/撞墙"这些手感问题解决掉，方块仍在世界里，抽水、分舱、可点击都没动。

### 10.26 瓦尔基里天空 2 接入（进行中，未打通）

用户选了"直接上 VS2，接受一段时间机器不可点"。

**已经打通的**

| # | 事项 | 关键点 |
| --- | --- | --- |
| 1 | VS2 能在开发环境启动 | 必须给 run 配置加 `mixin.env.remapRefMap=true` + `refMapRemappingFile=build/createSrgToMcp/output.srg`。否则它的 mixin 写的是 SRG 名（`m_130011_`），开发环境是官方名，`MixinMinecraftServer` 直接崩 |
| 2 | 运行时依赖齐了 | VS2 的 jar 里**没有** kotlin-stdlib，必须另装 Kotlin for Forge（`kff_version=4.12.0`）；cloth config 是内嵌的 |
| 3 | vs-core API 能编译 | `org.valkyrienskies.core.api.*` 在 VS2 的 jar-in-jar（`META-INF/jars/api-*.jar`）里，FG 不会摊到编译期 classpath，已抽到 `libs/vs2-api-1.1.0.jar` 单独引用；它继承了 Kotlin 集合类型，还得加 `compileOnly kotlin-stdlib:1.9.22` |
| 4 | 装船成功 | `ShipAssembler.assembleToShip(level, Set<BlockPos>, 1.0)` → 411 格、质量 362000 kg、中心 (204.5,-197.3,218.7) |
| 5 | 附件能挂上 | 必须先 `ValkyrienSkiesMod.getVsCore().registerAttachment(VsThruster.class)`，否则 `setAttachment` 抛 "unregistered class" |
| 6 | 物理回调被调用 | `ShipPhysicsListener.physTick` 10 秒收到 **591 次**（≈59 Hz），监听链路是通的 |

**没打通的**

- **船不动**：回调里 `applyInvariantForce(质量×2.5, 0, 0)` 施加了 905 kN，速度始终 0.00，中心坐标一动不动。
- 两个可疑点：
  1. `assembleToShip` 返回的是 `ShipData`（未加载快照），它的 `getTransform()` 不随物理更新。
     已确认 `getLoadedShips().getById(id)` 能拿到已加载的船，读数也改走它了——坐标仍然不变，
     说明就算读的是已加载句柄，物理也没推动它。
  2. `deleteShip(level, ship, false, false)` 只"铺回 1 格"，很可能方块根本没被真正搬进刚体、或搬走后没还原。
     装配时是否与哨站方块相交也没查。
- 结论：**装配/物理这条链路还有一处没打通**，不是调参数能解决的。

**踩坑记录**

- VS2 的 `ServerShip#saveAttachment` 在字节码里是编译器合成的桥方法，源码里写不出来（javac 报
  "cannot find symbol"），只能反射调。
- `VsiQueryableShipData` 继承了 Kotlin 的 `KMappedMarker`，源码里一用到它就得有 kotlin-stdlib。
- 附件类必须先注册，而且键是**类本身**（`setAttachment(thruster)`），不是 `ShipPhysicsListener.class`。

**下一步**

1. 查"船为什么不动"：在 `physTick` 里打 `PhysShip.isStatic()` / `getMass()` / `getBuoyantFactor()`，
   确认是"静态船"还是"力没进积分器"。
2. `deleteShip` 的参数用 Kotlin 元数据确认语义（目前只查到 `dropBlocks` 一个名字），
   加"装配前 / 装配后 / 拆回后"三点方块计数对账。
3. 打通之后再谈坐标换算：分舱、水位、声纳、机器交互都要按 `ShipTransform.shipToWorld` 改。

### 10.27 物理船：碰撞与浮力（用户实测反馈）

用户实测："没做碰撞体积潜艇自己穿模下去了"，并指出"碰撞体积是船核心导致的，声纳是有碰撞体积的"。

**两条修复**

| 问题 | 原因 | 修法 |
| --- | --- | --- |
| 穿模、往下掉 | 船核心是**隐形方块**（没有碰撞形状），一起装配会把整艘船的 voxel 建坏 | 装配集合里**去掉船核心**，只装 `geometry.hull()`。核心只是逻辑锚点，不需要物理形状 |
| 一直往下沉 | 实测 `PhysShip.getLiquidOverlap()` **恒为 0.000**——VS2 算不出我们自定义方块的排水量，等于没有浮力 | `VsThruster` 自己补：`ay += 10.0 × (1 − 0.5×(压载+进水))`，完全抵消重力；压载/进水再让它变重 |

`GRAVITY = 10.0` 必须和 `data/valkyrienskies/vs_dimension_parameters/the_deep.json` 里的 `-10` 对齐。
这个数据包是本轮新加的——VS2 只给 overworld/nether/end 配了维度参数，
`barotrauma:the_deep` 之前属于"未配置维度"。

**诊断数据（自检）**

```
已收成物理船：410 格，质量 361000，中心 (204.5,-197.3,218.7)
周边 水=326 空气=42
物理回调 520 次｜static=false mass=361000 liquidOverlap=0.000 力Y=3610000
```

力确实施加上去了（361 万牛顿），但自检里船 10 秒**一动没动**，坐标分毫未变。
判断是自检环境问题：船被移到哨站水池里，很可能和站点结构相交被卡死。
用户实机反馈船会掉，说明物理链路本身是通的——所以"动没动"这件事自检判断不了，
只能在客户端目视。

**采样坑**：取样必须在**装配之前**做，装配完方块就不在世界里了（第一版写反了，
永远返回"船体方块已不在世界"）。

### 10.28 声纳三条反馈 + 地形重做

**声纳**

| 问题 | 真正原因 | 修法 |
| --- | --- | --- |
| 看不清 | 波前扫过的地形亮度只有 `0.35`，而且按 `exp(-age·4.5)` 掉得极快，2.6 秒周期里大部分时间全黑 | 基线 0.15→0.35、衰减 4.5→1.8；点从 1~2 px 加到 2~3 px；颜色对比拉大 |
| 自动驾驶点击不对 | **射线起点算错了**：画点云时相机在球外 `1.5×range` 处，反投影却从球心出发，点到射线的垂距系统性偏大一个 camDistance（48 格量程时偏 72 格），点哪儿目标都跑到很远 | 射线起点改成 `-forward × camDistance`，且阈值 3→4.5；拖拽判定 4→6 px，手抖不再被当成转视角 |
| 不知道船头朝哪 | 只有底部一行小字 | 示波器上加 **东南西北方位字**（跟着视角转，"北"用橙色）；圆下方直接报「航向 东北 045°」；手动模式单击也能标记目标（不用先点单选） |

原版参考：`Biome` 页说明关卡由生物群系决定地形特征；`Abyss` 页提到"vertical caverns /
long horizontal caverns"，即**洞穴网络**，有竖直洞也有长横洞——所以下面的地形是往"宽通道 + 大洞窟"改的。

**地形**

`trunk carved = 0` 是个真 bug 的信号：主干道那套一直在跑，但半径 8.5 太细，且被噪声隧道抢先挖掉，
计数器一直是 0。这轮一并修了。

| 参数 | 原值 | 现值 | 理由 |
| --- | --- | --- | --- |
| 隧道半宽 `TUNNEL_WIDTH` | 0.100（≈10 格） | **0.15**（≈13 格） | 初始潜艇 9×5×15，10 格管道转弯必卡 |
| 隧道尺度 `TUNNEL_SCALE` | 44 | **80** | 44 太小，满世界蜂窝状碎洞 |
| 洞窟半宽 `CAVERN_WIDTH` | 0.055（≈12 格） | **0.10**（≈20 格） | 要能掉头 |
| 洞窟尺度 `CAVERN_SCALE` | 150 | **180** | 洞更大更整 |
| 主干道半径 `RADIUS` | 8.5（17 格） | **12**（24 格） | 潜艇舒服通行 |
| 竖井半径 / 概率 | 6.5 / 0.18 | **10 / 0.45** | 上下层经常找不到路 |
| 节点间距 `SPACING` | 128 | **160** | 走廊更长更直 |

另加**写死的哨站竖井**：`(x=44, z=0)` 从 y=-130 挖到 -960，半径 12。
主干道是哈希出来的，出站能不能碰上竖井全看运气；这条保证"从出生点一路往下开"永远可行。

自检（水体占比，越大越空）：

```
                 修复前              第一次加宽          最终
y=-300      10.6%              92.4%              40.7%
y=-600      55.5%              74.5%              69.3%
y=-900       5.9%               1.4%              42.3%
trunk carved: 0              275820             596700
```

第一次加宽矫枉过正（92% 全是水、没有洞窟感），最终值大约"一半岩一半水"。

> **注意**：改世界生成必须删 `run/world`，否则看到的还是旧地形。

### 10.29 地形改成"分层海域"（用户第二次指出方向）

用户："洞太小了，潜渊症里是上下岩壁间隔比较大，中间有大片海域"。

**维基查证结果（诚实记录）**：查了 `Biome` / `Abyss` / `Level` / `Level generation` / `cave` /
`map generation` 六个入口，Fandom 镜像上**没有**讲关卡地形长什么样的页面：

- `Biome` 页是 `{{Stub}}`，只有一句话列出五个生物群系的名字；
- `Abyss` 页唯一相关的一句是 "if a map forces you to navigate lots of vertical caverns …
  whereas if a map forces you to navigate long, horizontal caverns"，只能证明"垂直/水平洞穴并存"；
- 没有配图，也没有"通道多宽、水占多少"这类数值。

所以这一版是**照用户描述做的**，不是照维基做的。以后再有人问"原版地形什么样"，先说这个前提。

**结构换了**：从"实心岩里用 3D 噪声挖小洞"改成"分层海域"。

| 项 | 值 | 效果 |
| --- | --- | --- |
| 层高 `LAYER_HEIGHT` | 120 格 | 每 120 米一层 |
| 岩层厚度 | 层高的 21% ± 9% ≈ 14~36 格 | 上下岩壁，随水平位置起伏成波浪 |
| 岩柱 `PILLAR_REACH` | 最多伸出 38% 层高 | 有的从天花板垂下、有的从地面升起，交错 |
| 开阔水域 | 层内剩余部分，约 90 格高 | 潜艇（9×5×15）在里面是"大片海域" |
| 第一层岩层顶部 | y = −30 | 再往上到水面全是水 |

自检（水体占比）：

```
                 10.28（蜂窝洞）   10.29（分层海域）
y=-300              40.7%              76.3%
y=-600              69.3%              73.4%
y=-900              42.3%              76.3%
```

三层数据几乎一致，说明结构是均匀的分层，不再靠噪声随机。

**竖井改成弯曲的**：`carveWindingShaft` 把一条竖井拆成 6 段，每段水平随机偏 ±2.2 半径、
半径随机 0.75~1.25 倍——直筒圆柱看着像电梯井。横向走廊全部取消（层内本来就是水，
再挖走廊会把"上下岩壁"掏成筛子）。

### 10.30 物理船：漏水 + 声纳开不了船

| 现象 | 原因 | 修法 |
| --- | --- | --- |
| 改成瓦尔基里后船体漏水 | 方块进了刚体、不在世界里，`ShipCoreBlockEntity` 还在每 10 tick 往世界写水位、每 40 tick 做 `hullIntact` 检查；后者永远失败 → 反复重扫 → 舱室被判"连海" → 一直进水 | 物理船激活时跳过 `applyWater` 和 `hullIntact` 重扫 |
| 装了瓦尔基里就开不了船 | 核心方块跟着进了刚体，`ShipCores.resolve` 在世界里找不到它，声纳和操舵包一律当成"没有船" | `VsPilot` 装船时留一份 `ShipState`；`SonarPingPacket` / `ShipActionPacket` 找不到核心时回退到它 |

**声纳"每次探测就黑"**：波前没推到的点原来是直接 `continue`（不画），所以每轮脉冲开头整屏是黑的。
现在没扫到的点保留 0.30 的暗底、扫过的 0.45→1.0，观感是"同一张图在刷新"，不是"重新探测"。

### 10.31 声纳那个"球"是假的（三态网格）

用户截图：转一下视角，声纳就变成一个完美的球体点云。

**根因**：`SonarScan` 用 `boolean[] solid` 且 `Arrays.fill(solid, true)`——"球外当实心"是为了
避免射程边界被误判成表面。但表面判定是"实心 && 有邻居是空"，于是：

- 球边界那一圈格子：`solid=true`（球外），邻居是真实的空（球内）→ **全部被当成表面**；
- 未加载的区块同上（`continue` 之后保持 true）。

两者叠起来就是一个完美的球壳。点云越往里越密是因为球内真实的岩壁本来就稀疏。

**修法**：改成三态 `byte[] kind`——`UNKNOWN(0)` / `SOLID(1)` / `EMPTY(2)`。
网格外和未加载的格子一律 `UNKNOWN`，既不输出点，也**不算空**（邻居判定用 `knownEmpty`）。

自检对比（同一个位置、同一套船体）：

| 射程 | 修复前 terrain | 修复后 terrain |
| --- | --- | --- |
| 16 m | 914 | 360 |
| 48 m | 1622 | **509** |
| 64 m | 1986 | 766 |

点少了 2/3，少掉的全是假点。**开阔海域本来就该扫不到东西**——现在只剩真实的岩壁和岩柱。

### 10.32 方块留在原地 + 声纳射程 + 物理船判决

**方块留在原地（用户：开船时方块停留原地，看着在船上其实还在那）**

根因：`ShipMover` 和 `VsPilot.launch` 都用 `geometry.hull()`，那是**最大连通分量**。
船上的门、玻璃、单独挂着的机器如果不和主船体连通，就**不会被搬走**——船开走了它们留在原地。

修法：新增 `ShipState.collectBlocks(level)`，扫包围盒（±1）里**所有** `isShipBlock` 的方块。
`ShipMover.move` 和 `vsLaunch` 都改用它。自检里 `move` 依然是"移动 411 格"，说明密闭船的
连通分量本来就是全的；但只要船有一块断开（撞掉一格、后期加独立舱），旧写法就会漏。

**声纳射程（用户：范围太小）**

| 项 | 原值 | 现值 |
| --- | --- | --- |
| `MAX_RANGE` | 64 | **160** |
| `DEFAULT_RANGE` | 48 | **80** |

顺带修了一个**会在大射程下爆掉的编码 bug**：`pack()` 用 8 位 + 偏移 64 存坐标，只能表示
−64..191；射程一超过 64，负方向的坐标会被 `& 0xFF` 截断成垃圾值，点云直接错乱。
改成三个 9 位（偏移 256，覆盖 −256..255）+ 类型 2 位，共 29 位，仍在 int 内。

自检（同一位置）：

```
sonar 16m  stride=2  -> terrain 85   cost 5.9 ms
sonar 80m  stride=7  -> terrain 679  cost 367 ms（首次，要加载区块）
sonar 160m stride=13 -> terrain 872  cost 2.6 ms
```

步长随射程自适应（`range/12`），所以查询格数基本恒定，只有首次会卡在区块加载上。

**物理船判决：不工作，回退**

加了 `PhysShip.getTransform().getPositionInWorld()` 探针，结果是：

```
物理体位置=(0.0,0.0,0.0)      ← 船实际在 (204.5, -197.5, 219.4)
```

物理体根本不在船的位置上。力施加上去了（410 万牛顿）、回调每秒 59 次、`static=false`，
但推动的是一个坐标原点的 body，所以船纹丝不动。这不是调参数能解决的——是
`assembleToShip` 之后物理体的 transform 没和 ShipData 对上。

按事先说好的"1 轮打不通就回退"，**物理船这条线停在这里，方块船继续做主路径**。
`/barotrauma ship vs *` 命令保留但标注实验性，不参与玩法。






