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

**物理船三个症状是同一件事**（用户实测：装了就进水、开不了、声纳空白）：

`assembleToShip` 把方块**从世界里搬走**，原地只剩水 → 看起来就是"船没了、水灌进来了"；
声纳扫的是世界方块，船体已经不在世界里 → 扫不到船；操舵依赖的船核心也进了刚体，
`ShipCores.resolve` 找不到 → 开不动。三个现象同一个根因，不是三个 bug。

所以 `vs launch` 现在**直接拒绝执行**，只打印原因和建议；真的要装得用
`/barotrauma ship vs force`（仅调试用）。已经装过的船用 `vs dock` 拆回来。

## 10.33 物理船（VS2）实测与源码查证（2026-11-01）

### 查到了什么源码

VS2 的**模组本体**是开源的（`github.com/ValkyrienSkies/Valkyrien-Skies-2`，分支 `1.20.1/main`），
已克隆到 `_vs2src/`；但**物理内核 vs-core 不在这个仓库里**（`.gitmodules` 是空文件，
README 里的 `--recurse-submodules` 已过期，core 以 `org.valkyrienskies.core:impl:1.1.0+<hash>`
的形式从 `maven.valkyrienskies.org` 发布二进制，没有 sources jar）。内核相关的结论来自
vineflower 反编译 `PhysShipImpl` 和 `GameToPhysicsAdapter`。

关键引用：

| 位置 | 内容 |
| --- | --- |
| `common/.../assembly/ShipAssembler.kt:139` | `toShip.isStatic = fromShip == null \|\| fromShip.isStatic` —— 全新装配的船先被标成静态 |
| `common/.../assembly/ShipAssembler.kt:172` | `toShip.isStatic = false` —— 几行之后才设回动态 |
| `common/.../assembly/ShipAssembler.kt:376` | `assembleToShip(level, blocks, scale)` 就是官方 `ShipAssemblerItem` 用的入口，我们没走歪 |
| `common/.../util/GameToPhysicsAdapter.kt:42` | 力在 `physTickEvent` 里按维度分桶排空（`applyWorldForce` 等） |
| `common/.../ValkyrienSkiesMod.kt:148` | `physTickEvent` 只驱动 GTPA、方块实体监听器、实体监听器三类，**不驱动 `ShipPhysicsListener`** |
| 反编译 `core/impl/game/ships/PhysShipImpl` | 力先进 `invPosForces` 队列，由 `applyQueuedForces()` 逐帧排空；`canApplyWrenches()` 里 `return !isStatic` |

`DimensionParametersResolver` 的 `maxYPos` / `seaLevel` 只影响大气密度（机翼升力），
不参与"船是否被模拟"的判定 —— `/vs get-gravity` 在 `barotrauma:the_deep` 返回 10.0，
说明我们那份 `vs_dimension_parameters/the_deep.json` 是**生效的**，格式也对。

### 实测结论（都带数据）

1. **装船本身成功**：130 格 → 质量 130000 kg，`/vs get-ship` 查得到 slug，
   `physTick` 稳定 60 次/秒，力算出 (0, 1300000, 325000) N，`static=false`、`sleeping=false`。
2. **但刚体是死的**：速度永远 0.000，位置一格不动，**连重力都不拉它**；`/vs teleport`
   能搬走，200 ms 内又瞬移回原位。力和重力双双无效 = 这个刚体没进求解器。
3. **`vs dock` 只铺回 1 格**（"已拆回世界：铺回 1 格"），129 格蒸发 —— 恢复命令是坏的。
4. **服务端重启 = 装着的船丢失**：方块已被搬出世界，重启后 VS 船没回来。
5. **反复 `vs launch` 会叠船**：`/vs delete @v` 一次删掉 **4 艘**，互相碰撞顶死。
   第一次"船不动"的现场其实是这个，不是物理没生效。
6. **确实见过一次真正的物理**：17:19 玩家从深海维度传回主世界后，同一艘船开始受重力
   （速度 -0.33 → -0.54，y 持续下降）。VS2 物理本身能跑，只是"什么样的船才算活着"没钉死。

### 这轮顺手修掉的我们自己的 bug

- `VsPilot` 僵尸句柄：`/vs delete` 之后旧 `ServerShip` 句柄不会变 null，位置/速度/质量
  还读得出最后一帧的值，`vs status` 看起来正常而船已不存在。现在用之前回船世界
  `getAllShips().getById()` 确认，查不到就丢掉（`stillExists` / `dropStale`）。
- `vs launch` 支持 `vs launch <x> <y> <z>`，不带玩家也能从控制台装船，方便无人调试。
- 装配后连续 20 tick 主动 `setStatic(false)`（`unstaticTicks`）—— **实测无效**，
  先留着，等根因确定再决定删不删。

### 下一步（按优先级）

1. 用 `VsiServerShipWorld.retrieveLoadedPhysicsEntities()` 确认运行时装配的船到底有没有
   `PhysicsEntityServer` —— 这是"刚体在不在求解器里"的直接证据。
2. 已经试过**无效**的：`applyInvariantForce` 直压队列、`GameToPhysicsAdapter.applyWorldForce`、
   装配后 `setStatic(false)`、`/vs remass`、让玩家离开再回来（4 秒）。
3. 若确认 VS2 在"区块已加载时装配"这条路径上就是不出物理体，现实选择只有两个：
   装完船后强制卸载再加载船区块（需要 core 内部 API），或改用自己写的刚体方案。

## 10.34 声纳合并船核心 + 清理死方块 + 物理再调查（2026-11-01 晚）

### 声纳台就是船核心

`SonarBlockEntity extends ShipCoreBlockEntity`。因为 63 处判断写的是
`instanceof ShipCoreBlockEntity`，而声纳是它的子类，**那些逻辑一行没改就全接上了**
（`ShipCores.resolve/nearestCore`、`VsPilot.launch`、`ShipActionPacket`、
`SonarPingPacket`、`ShipMover`、`StarterSub`……）。

改动点：
- `ShipCoreBlockEntity` 加了一个 `protected ShipCoreBlockEntity(BlockEntityType<?>, pos, state)`
  构造给子类用（`BlockEntity` 的类型必须在构造时给定，而声纳的类型不是 SHIP_CORE）。
- `SonarBlock.getTicker` 服务端也返回 `ShipCoreBlockEntity::serverTick`。
- `StarterSub.corePos()` 改成指向声纳台的位置；造初始潜艇时不再放船核心方块。

**踩到的坑**：`ShipGeometry.collectHull` 选的是"离核心最近的连通块"。声纳既是核心又是
`isShipBlock`，它和外面那层船壳之间隔着一格空气、**不连通**，于是选中的是距离 0 的
声纳自己 —— 装船只收到 1 格、质量 300。修法是在收集时 `if (pos.equals(core)) continue;`。
排除核心之后选中的才是真正的船壳。

### 删掉的东西

| 删除 | 原因 |
| --- | --- |
| `ship_core` 方块 | 已合并进声纳 |
| `helm` 驾驶台 | 从没实现过任何逻辑，掌舵本来就在声纳里 |
| `ship_console` 菜单 + `ShipConsoleMenu`/`ShipConsoleScreen` | 早期"独立船控制台界面"的遗留，没有任何方块能打开它，纯死代码 |

`oxygen_tank`（右键补氧）和 `diving_knife`（铁剑档位）**保留**——它们有实现，只是还没接进玩法。

### 声纳界面翻页

顶部两个页签，第 1 页声纳、第 2 页核心。核心页内容由服务端拼成多行文本
（`ship.summary()` + `ship.liveReport()`）随 `SonarDataPacket` 一起下发，客户端只负责
换行和裁剪，避免为这一页再开一堆数值字段的协议。

注意：页面版式是 500×276，**GUI 缩放为 2 时 856 宽的窗口装不下**，页签会被挤到边界外。
这不是 bug，全屏或调小 GUI 缩放即可。

### vs_mass 数据包

VS2 wiki（`Valkyrien-Skies-2.wiki`，只有一页）写明方块质量来自数据包 `vs_mass`。
我们之前**一条都没有**，所有自定义方块都吃 `defaultBlockMass`（1000）。现在按真实密度
补了 `data/barotrauma/vs_mass/ship_blocks.json`：钢壳 7850、强化玻璃 2600、反应堆 6000
等，装船质量的量级终于对了（131 格 ≈ 1020 吨）。

### 物理：间歇性工作，未稳定

**见过一次真的物理**（19:26，同一份代码）：

```
19:26:13  中心(0.5, 193.7, 0.5)  速度 -8.83
19:26:14  中心(0.5, 174.3, 0.5)  速度 -16.43
19:26:16  中心(0.5, 150.0, 0.4)  速度 -22.48   live=同一个
```

船在自由落体，而且**加速度约 5 m/s² 而不是纯重力的 10**，说明我们施加的 10.2 MN
上浮推力**确实作用到刚体上了**，只是没完全抵消重力。物理链路是通的。

但之后三次同样流程的装配（19:28 / 19:31 / 19:37）船都纹丝不动，**无法稳定复现**。

新证据与排除项：
- `live=同一个`：`physTick` 回调拿到的 `PhysShip` 和 `level.getShipById(id)` 是同一个对象，
  排除"力施加在快照上"。
- `teleportShip` 能把船搬动（曾因参数 `posInShip` 传错把船传到了
  `(28653628, 60, -12289987)`，已回滚），说明物理引擎本身能正常积分。
- 删世界重建、换位置、不调 `/vs delete` 都复现了"不动"。

结论倾向：**dev 环境（ForgeGradle runServer）下 VS2 的物理体创建时有时无**，
与我们的代码无关。下一步要打包成正式 jar 丢进 PCL2 实例验证。

## 10.35 两套移动系统 / 切换进水 / 方块没删 / 姿态（2026-11-01 夜，用户实测反馈）

用户实测原话：**"声纳有两种移动系统，一种方块一种物理叠加到一起；切换状态依旧会进水；
那些方块也没删；潜艇要永远平行于海平面，不然会翻。"**

四个症状不是四个 bug，是**两个**根因：

### 根因 1：方块进了刚体之后**还在 tick**，方块级模拟没停

这是最关键的一条认知修正。之前一直以为"装配完方块就不在世界里了"，**错的**：
VS2 的 `assembleToShipFull`（`ShipAssembler.kt:250-284`）把方块从原坐标搬走，
但搬到的是**船坞区块**（`chunkClaim` 中心），方块数据仍然在世界的区块里、
**方块实体也照旧 tick**。于是装配完之后：

| 还在跑的东西 | 后果 |
| --- | --- |
| `ShipCoreBlockEntity.serverTick` → `state.tickMovement` | `ShipMover` 在船坞坐标里搬同一艘船的方块，VS2 又在按 transform 搬整艘船 → **两套移动系统叠加** |
| 同一条里的 `applyWater` | 把水写进船坞里的"船舱"，看起来就是**一直进水** |
| `hullIntact` + 每 40 tick 重扫 | 船坞坐标下永远判定失败 → 无限重扫 → 舱室被判"连海" → 灌满 |

之前的"修法"是在 `serverTick` 里加了一个**全局**开关
`VsSupport.available() && VsPilot.hasShip()`——那是"世界上有没有物理船"，
不是"这台声纳所在的船是不是物理船"，所以既拦不住自己人、还会误伤别的船。

**修法：按船判定 + 显式停手。**

- 新增 `ship/vs/VsState.java`：**不引用任何 VS2 类型**的中转站。
  存 `active` 标志、`activeState`（物理船那份 `ShipState`）、
  以及两个回调接口 `Probe`（这个方块位置属于物理船吗）和 `Control`（把船状态推给推进器）。
  为什么单独一个类：判断点分布在 `ShipCoreBlockEntity` / `ShipActionPacket` /
  `StarterSub` 里，这些地方**没装 VS2 也得能加载**，直接引用 `VsPilot`
  会因为 `ServerShip` 不在而 `NoClassDefFoundError`。
- `VsBridge.managingShip(level, pos)` 用 VS2 自己的
  `VSGameUtilsKt.getShipManagingPos(level, blockPos)` 问"这个方块归哪艘船"，
  `VsPilot` 拿它和当前船 id 比 → **per-ship** 判定。
- `ShipCoreBlockEntity.serverTick` 一进来就问 `VsState.isPhysical(level, pos)`，
  是就 `return`——推进、水位、破口重扫**全部**停手。
- `ShipCoreBlockEntity.rescan` 同样代理：物理船的声纳方块实体是个**新**的
  （旧的随装配被搬走），它自己的 `state` 是 null，按船坞坐标扫出来全是垃圾；
  现在直接返回 `VsState.activeState()`（同一个对象），面板和操舵就都对得上。

### 根因 2：`StarterSub.ensure` 在物理船存在时**又造了一艘**

`sendToOutpost`（`/barotrauma deep`、首次登录、重生）都会调 `StarterSub.ensure`。
装配之后声纳方块被搬进船坞，`corePos()` 那一格变成空气 → `ensure` 认为"还没造过"
→ **在原地把整艘方块潜艇重新造一遍**。这才是用户看到的"方块没删 / 两个东西叠在一起"
的字面来源：物理船在海里，原地又站着一艘一模一样的方块潜艇。

**修法**：`ensure` 开头两重保护——`VsState.active()` 直接跳过；
声纳没了但船体方块还在（`hasRemainingHull`）也跳过，避免"半条船"被重建。

### 顺带修掉的：`vs dock` 会吞掉全部方块

`ShipAssembler.deleteShip(level, ship, deleteBlocks, dropBlocks)` 的第三个参数
**不是"铺回方块"**：`false` = 世界一格不动只删船对象，`true` = 把船的世界包围盒
**挖成空气**（`/vs delete <ship> true` 那个）。VS2 里根本没有"刚体还原成方块"的 API。
我们之前写的 `deleteShip(..., false, false)` 注释说"这才是铺回方块"——错的，
所以"已拆回世界：铺回 1 格"，其余全蒸发。

**修法**：装配前留一份方块快照 `Map<BlockPos, BlockState>`；
拆船时先 `discard`（只删刚体），再按 `快照位置 + 船自装配以来的整数位移` 铺回世界，
最后对新声纳台 `rescan`。因为姿态锁死在水平（见下），这里不需要做旋转变换。

### 姿态：永远平行于海平面

推力全部作用在质心上（`applyInvariantForce`），本身不产生力矩，倾斜只可能来自碰撞。
`VsThruster` 加了两条，都在物理回调里按质量缩放（转动惯量随质量增长，
不缩放的话大船纹丝不动、小船被甩飞）：

1. **角速度阻尼**：力矩 = `−ω × 900 × mass`，把"转起来"掐死在源头；
2. **回正力矩**：船的上方向 `up` 在世界的朝向和 `+Y` 有夹角时，
   绕 `up × (+Y)` 施加 `倾角 × 700 × mass` 的力矩把它拉回来。
   完全翻过来（夹角 π）时转轴退化，退回到绕 +X 轴推。

力矩封顶 `4000 × mass`。`vs status` 现在会打印 `姿态 倾角 x.x° 偏航 y.y°`，
理想的物理船永远是 `倾角 0.0°`。

### 控制路径也并成一条

之前"两套"在操作上也是真的：声纳面板的油门只写 `ShipState`，
物理推进器只能靠 `/barotrauma ship vs thrust` 控制，两边互不相通。

现在：面板 → `ShipActionPacket` → `ShipState` → `VsState.syncControl(state)`
→（物理时）`VsThruster.setThrottle/setPower/setBallast`。
`VsPilot.tick()` 每 tick 再同步一次（压载、进水比例会变），
并且把刚体的真实速度 `sync` 回 `ShipPhysics`，
所以面板上的航速、下潜速度是刚体的真数，不是方块模型的假数。

`SonarPingPacket` 在物理模式下扫描中心改用**玩家坐标**：
物理船的声纳方块位置是船坞坐标，拿它扫世界只会扫到船坞。
核心页也会多一行 `驱动：VS2 物理刚体` / `驱动：方块推进`，一眼看出哪套在跑。

### VS2 源码查证补充（这轮）

| 位置 | 内容 |
| --- | --- |
| `ShipAssembler.kt:250-284` | `removeOriginal=true` 时把源方块先设 `BARRIER` 再 `removeBlock`，方块搬到船坞 |
| `ShipAssembler.kt:715-733` | `deleteShip` 的 `deleteBlocks` 是"挖掉世界方块"，不是"铺回" |
| `ShipAssembler.kt:138-172` | 装配时先 `isStatic = true`，几行后设回 `false`；新船旋转是 `Quaterniond()`（单位四元数）→ **装配出来天生是水平的**，这对姿态锁定是个好消息 |
| `VSGameUtils.kt:376-388` | `ServerLevel?.getLoadedShipManagingPos(chunkX, chunkZ)` / `(blockPos)` / `(pos)` |
| `VSGameUtils.kt:447-459` | `ServerLevel?.getShipManagingPos(blockPos) : Ship?` —— 方块归属查询用它 |

### 顺带查清的：`retrieveLoadedPhysicsEntities()` 那个探针是错的

之前一直拿 `VsiServerShipWorld.retrieveLoadedPhysicsEntities()` 判断"刚体进没进求解器"，
返回 0 就被当成"船没进物理世界"。**这个结论是错的**：那个方法返回的是
`VSPhysicsEntity`（用物理实体生成器物品手动造出来的刚体球），**和船的刚体没有关系**。
`VSPhysicsEntity.kt:176` 是唯一调用 `createPhysicsEntity` 的地方。船 100% 在物理世界里，
physTick 每秒 60 次就是证据。

### 又排除掉的三个：力还是进不去

这轮把力度诊断补全（`力=(x,y,z)`），三个实验都做了，**船还是速度恒 0**：

| 实验 | 结果 |
| --- | --- |
| 力 API 从 `applyInvariantForce(f)` 换成 `applyWorldForce(f, 质心世界坐标)` | 力算出来了 `力=(0, 4.08e6, 1.02e6)`，**速度仍 0** |
| 装配后 20 tick 内 `setStatic(true)` → `setStatic(false)` 翻转（逼 VS2 重建刚体类型） | **无效**，`static=false sleeping=false` 但不动 |
| 复核 `physTick` 里读到的 `PhysShip.getTransform()` | 位置 11 秒纹丝不动，说明是**求解器根本没积分这具刚体**，不是我们读错了 |

为什么换 API 仍然值得做：反编译 `PhysShipImpl` 看得很清楚，
`applyInvariantForce(f)` → `applyWorldForceToBodyPos(f, null)` →
`invPosPositions.add(null)`，最后 `applyQueuedForces()` 里执行
`rigidBodyReference.a(null, force)`——**位置参数是 null**。
`applyWorldForce` 会先把世界坐标变换到模型坐标再减质心，得到真实的体坐标点，
走同一条队列但没有 null。这条不管冻不冻结都是更正确的写法。

`vs-core-server.toml` 里相关的几个值（都是默认值，看起来不像"专门冻结"）：

```
shipLoadDistance = 128.0   shipUnloadDistance = 196.0   shipLoadFreezeSeconds = 5.0
physicsBackend = "KRUNCH_CLASSIC"   physicsTicksPerGameTick = 3
synchronizePhysics = false          enableConnectivity = false
```

**下一步**：按 §10.33 的原计划，打包成正式 jar 丢进 PCL2 实例、**带玩家**跑一次。
19:26 那次真物理是有玩家在线、且当时还在主世界；无玩家的自检服务器里
`loadedShips=1` 但刚体从不积分，很可能是"没有玩家 → 船区块的加载票据不完整 → 
VS2 把船挂在冻结状态"这条路径。

## 10.36 物理船真的能动了——只剩"初始潜艇那个位置装配失败"（2026-11-01 深夜）

用户要求：**"以后每次开工先上网搜"**。这条已经写进 `AGENTS.md` 第 0 条铁律。
下面先写查到的资料，再写实测。

### 查到的资料（Barotrauma wiki，Fandom 镜像）

| 页面 | 内容 |
| --- | --- |
| `Pumps` | 大泵 `powerconsumption = 300`、`maxflow = 600`；小泵 `powerconsumption = 60`、`maxflow = 100` |
| `Pumps > Ballast` | 压载泵 = 把所在房间维持在某水位，**从而控制浮力、决定垂直加速度**；做法是把泵的 `set_targetlevel` 接到**导航终端的 `velocity_y_out`**（−100..100 = 目标水位 0..100%） |
| `Pumps > Bilge` | 舱底泵由**水探测器**触发（给 `set_state`），满速抽到房间干为止 |
| `Pumps > Connection Panel` | 输入：`power_in` / `toggle_state` / `set_state` / `set_speed`(−100..100) / `set_targetlevel` |

**查不到的（必须标成假设）**：

- `Ballast` 页面**不存在**（API 返回空字符串）。
- `Submarine` 页里的 `==Ballast==` 是**空段落**，没有任何内容。
- 所以"潜艇用什么姿态配平、倾斜多少算正常"**没有官方数据**。

**按假设处理**：用户要求"永远平行于海平面"，那就按"姿态锁死到 0°"实现（不是原版行为，
是用户指定行为）；压载只影响垂直加速度，不影响姿态。等以后有官方数据再回来对。

**还欠的一处机制对齐**：原版是"垂直速度 → 目标水位"的闭环（`velocity_y_out` → `set_targetlevel`），
我们现在是直接给推力。数值上等价、机制上不同，记在这里待办。

### 实测：物理是好的

用户开了 dev 客户端，我直接在里面用命令做对照实验（数据全部来自
`run-client/logs/latest.log` 的 `[CHAT]` 行，不是推理）。

| 场景 | 装配结果 | 能不能动 |
| --- | --- | --- |
| 主世界 (100,66,100) 空中 5×5×5 | ✅ 99 格 **质量 769600** 位置正确 | ✅ 0 → 4.30 → 11.53 格/秒 |
| 主世界 (500,150,500) 空中 5×5×5 | ✅ 99 格 769600 位置正确 | ✅ **0 → 7.44 → 14.70 格/秒**，位移 +44，倾角 0.0°→10.0° |
| 深海维度 (2000,−150,2000) 空中 | ✅ 99 格 769600 位置正确 | 未加推力 |
| 深海维度 (3000,−100,3000) **水下** | ✅ 99 格 769600 位置正确，`liquid=0.908` | 沉浮 1.05 格/秒 |
| **深海维度 初始潜艇位置** | ❌ **质量 300（只剩声纳一块）**，中心跳到 **(0,300,0)** | 不可能动 |

`质量 769600 = 98 × 7850（装甲板）+ 1 × 300（声纳）`，说明 **`vs_mass` 数据包生效、
方块全部进了刚体**。所以 `applyWorldForce` 那套、姿态锁、`VsState` 停手逻辑都是对的。

**关键结论**：`质量 300` 正好等于 `vs_mass` 里声纳那一条 —— 那个位置的装配**只把声纳一块
收进了刚体**，其余 408 格被删掉但没进船。用户说的"船和声纳不是一体"和"还是进水"
就是这一件事：船壳消失了、原地变回海水，声纳孤零零留在原地。

### 已经排除的因素

- ❌ 不是维度问题（深海维度 (2000) 和 (3000) 都成功）
- ❌ 不是水下问题（(3000) 那个就是泡在水里装配的）
- ❌ 不是"无玩家"（这次有玩家）
- ❌ 不是 `vs_mass` 缺失（769600 对得上）
- ❌ 不是 `applyInvariantForce` 的 null 位置（已换成 `applyWorldForce`）

### 还没排除（下一步按这个顺序试）

1. **尺寸 / 跨区块**：成功的三例都是 5×5×5（单区块 x/z 各 1 个），失败的是 9×5×15
   （x 跨 chunk −1 和 0，z 跨 16..30 也在两个 chunk）。**下一发就在 (2000) 造一个 9×5×15**
   —— 成功则说明是位置，失败则锁定"多区块船装配坏"。
2. **初始潜艇那个位置本身**：chunk (0,0) 附近有哨站、有海、有大量生成的岩石。

### 结论：是**位置**，不是尺寸（已测）

最后一发：在深海维度 (2000,−150,2000) 用 `barotrauma:test_hull` 造一艘
**和初始潜艇一模一样的 9×5×15**（x 跨 2 个 chunk、z 跨 2 个 chunk），装配结果：

```
已收成物理船：403 格，质量 804300，中心 (2000.5, -149.5, 2001.5)
物理船｜质量 804300｜中心 (2000.5, -149.5, 2001.5)｜动态｜姿态 倾角 0.0°
```

`402 × 2000（test_hull）+ 1 × 300（声纳）= 804300` **一分不差**，位置也对。

**所以跨区块、尺寸、水下、维度、vs_mass 全部排除。**
失败只发生在初始潜艇那一片（约 chunk 0,0 附近）。

**目前最可能的解释**：VS2 给第一艘船分配的**船坞 chunk claim 就在 chunk (0,0) 一带**，
而那正好是我们的**前哨站**所在——船坞区块被已经生成的哨站结构占着，
`moveBlocksFromTo` 往那里铺方块时铺不进去，于是只有声纳那一格（最近/最先）进去了，
船位置退回 claim 中心 (0,300,0)（这个 y 还超出了深海维度 maxY=15）。

**下一步（下次开工第一件事）**：在 (0,−150,0)、(16,−150,0)、(32,−150,0) 各装配一次小船，
把"失败半径"量出来；如果确认是 chunk (0,0)，就把哨站/初始潜艇整体挪开，
或者干脆在装配前先检查目标位置附近有没有我们的方块并给出明确报错。

### 新加的东西：透明试验船壳

用户建议"把潜艇外壳换成透明方块类似玻璃那样比较好测试"。新增：

| 项 | 内容 |
| --- | --- |
| 方块 | `barotrauma:test_hull`「试验船壳（透明）」 |
| 模型/贴图 | `block/cube_all` + `minecraft:block/glass`，客户端 `RenderType.translucent()` |
| 属性 | `noOcclusion` + `isViewBlocking=false` + `isSuffocating=false`，真的能看穿 |
| 算不算船体 | `isShipBlock` 认它，`vs_mass` 给 2000 |
| 用在哪 | **`StarterSub` 的船壳直接用它造**，出生那艘船现在整个是透明的 |

已截图确认：站在船里能直接看穿船壁看到外面的水，机器（声纳台、引擎）都清清楚楚。
















## 10.37 测试体系：按 minecraft-testing 技能搭三层（2026-11-01 深夜）

用户要求"用 mctesting 看看物理船怎么搞"。加载 `minecraft-testing` 技能后，它的路由判断是：
**纯逻辑用 JUnit、游戏内交互用 GameTest、全流程用集成服务器**，不要再靠"改代码 → 开服 →
人肉看日志"这一条路。

对号入座：

| 层 | 工具 | 覆盖 | 状态 |
| --- | --- | --- | --- |
| L1 纯逻辑 | JUnit Jupiter | `ShipPhysics` / `ShipGeometry` | ✅ 17 个用例，`gradlew test` 20 秒内跑完 |
| L2 游戏内 | Forge GameTest | 物理船装配 / 质量 / 拆回 | ✅ 3 个用例，`gradlew runGameTestServer` |
| L3 集成 | 现有 `runServer` + `.smoketest` | 世界生成、声纳、反应堆、水泵 | 已有，缺断言（待办） |

### L1：JUnit（`src/test/java`）

`build.gradle` 里补：

```groovy
testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
tasks.named('test', Test).configure { useJUnitPlatform() }
```

**踩坑**：第一次跑必须**联网**（`--offline` 时 JUnit 不在缓存里，会报
`No cached version of org.junit.jupiter:junit-jupiter:5.10.2`）。拉一次之后就能离线跑了。

两个测试类的期望值**全部来自已有日志**，不是编的：

- `ShipPhysicsTest`（11 个）：`满推力 2s 速度=5.35`、`松油门再 2s=0.58`、
  `压载满 3s 垂速=-0.71` —— 就是自检里 `[smoketest] physics -> ...` 那一行。
- `ShipGeometryTest`（6 个）：5×5×5 空心壳 = 98 格 / 1 舱室 / 27 容积 / 密闭；
  挖一格变连海；**核心自己不算船体**（这是"装船只收到 1 格"那个 bug 的回归测试）。

`BlockPos` 这种 MC 基础类在测试 classpath 上是可用的，不需要 `Bootstrap` 引导。

### L2：Forge GameTest

`src/main/java/.../gametest/PhysicsShipGameTests.java`，3 个用例：

1. `scan_collectsWholeHullNotJustCore` —— 声纳**悬在舱中、不和船壳相连**，
   断言 `hullBlocks == 98`、`collectBlocks == 99`。守住"又选中核心自己"。
2. `launch_putsEveryBlockIntoRigidBody` —— 断言刚体质量 `= 98×2000 + 300 = 196300`。
   这是"船和声纳不是一体 / 水灌进来"的直接指标。
3. `dock_restoresBlocksToWorld` —— 断言拆完之后船壳 98 格 + 声纳 1 台都回到世界里。

**三个坑（都踩过）**：

| 坑 | 现象 | 修法 |
| --- | --- | --- |
| 模板路径 | `Could not find structure file gameteststructures\empty_16x8x16.snbt` | 反编译 `StructureUtils.getStructureTemplate` 后确认：它先走 `StructureTemplateManager.get()`（读 `data/<ns>/structures/*.nbt`），失败后回落到**文件系统** `Paths.get(testStructuresDir, "gameteststructures/<path>.snbt")`。实测只有 **`run/gameteststructures/*.snbt`** 生效（相对 run 工作目录），放 mod 资源里没用 |
| `VsPilot` 单例 | 三个用例同一个 batch 并行跑，互相抢船，dock 用例挂 | `@GameTest(..., batch = "physics_xxx")` 各给一个 batch，串行执行 |
| 位置精确断言 | 断言声纳"回到原坐标"失败，但 dock 报告"铺回 99 格" | 改成**数整个船体区域的方块数**（98 船壳 + 1 声纳）。VS2 会自己算质心/位移，逐格坐标断言太脆 |

模板生成器：`tools/gen_gametest_structure.py` —— 输出全空气的 `.snbt` 到 `run/gameteststructures/`。
**`run/` 会被重建，删了 world 之后记得重跑一次。**

### 这套测试抓不到什么

GameTest 跑在平的测试世界里，**复现不了"初始潜艇那个坐标装配失败"**——
那是位置/维度相关的，只能靠真实世界扫坐标（见 §10.36 的"下次开工第一件事"）。

所以层与层是互补的：L1/L2 守回归，L3 + 真实世界扫坐标找环境相关的 bug。

### 常用命令

```powershell
.\gradlew.bat test --offline                 # L1，约 20 秒
.\gradlew.bat runGameTestServer --offline    # L2，约 25 秒
python tools\gen_gametest_structure.py       # 重建 GameTest 模板（删过 run/ 之后）
```
---

## 10.38 进水的真凶：装配结果从来没被校验（2026-11-01 23:30 前后）

上一节结尾把"装配失败"归因成**位置**（"只有初始潜艇那个坐标会失败"）。**这个结论是错的。**

### 推翻旧结论的证据

把三处日志（`run/logs`、`run-client/logs`，含 `.gz` 解压）全量检索"已收成物理船"，
同一个位置既有成功也有失败：

```
20:47:28  已收成物理船：409 格，质量 3117600，中心 (0.5, -149.5, 23.5)   ← 成功
21:03:46  已收成物理船：409 格，质量 300，中心 (1.5, -150.5, 20.5)      ← 失败
18:33:18  已收成物理船：1 格，  质量 300，中心 (0.5, 201.5, 0.5)        ← 失败（只 1 格）
```

方块数一样（409）、位置一样。所以 **位置不是变量，是间歇性失败**。
"扫失败半径"那条路是白走的，已从 STATUS 里删掉。

### 代码层面的真 bug

`VsPilot.launch()` 只判 `assembleToShip()` 返回 null，不校验刚体里到底进了多少方块，
就把状态清空并回报"已收成物理船：N 格，质量 X"。而 VS2 的装配顺序是
**先挖空世界方块，再铺到船坞区块**（见 `_vs2src/.../ShipAssembler.kt:250-320`），
铺失败时世界已经空了：

- `质量 300` = 只有声纳那一格进了刚体 → 用户："船和声纳不是一体的"
- 船壳被删掉、海里那块原地变回海水 → 用户："还是进水"

**两个抱怨是同一个根因。** 之前 §10.35 修的 `VsState.isPhysical`（方块那一套停手）
解决的是"两套移动系统叠加"，和这个是两件事。

另外 VS2 自己的日志里有旁证：装配后会等 20 tick 让船坞区块开始 tick，超时就
`Timed out waiting for chunks to start ticking after assembly! Forcibly resuming...`
然后**强行继续**——这时候装配很可能只成功了一部分。日志里这个警告反复出现
（15:05 / 15:17 / 19:28 / 20:30 / 20:34 / 20:37）。

### 修法

1. **质量对账**：装配前用 `BlockStateInfo` 把快照里每个方块的质量加起来当 `expected`
   （新方法 `VsBridge.blockMass`），装配后比 `VsBridge.mass(ship) / expected`。
   低于 `0.85` 判失败（留 15% 容差，因为 VS2 的质量走它自己的体素/连通性数据）。
2. **失败回滚 + 重试**：最多 3 次。每次失败先 `VsBridge.discard` 删掉半成品刚体，
   再按快照把方块铺回原位，然后重试。全部失败就铺回并明确报错——
   **不再出现"船壳没了、原地变海水"**。
3. **方块集改用 `collectBlocks`**：`geometry.hull()` 是"最大连通分量"，
   声纳台挂在船壳外面时不属于它，用 `hull()` 装船必然把声纳留在原地。
4. `launch` / `dock` 里手写的铺回循环抽成 `restoreBlocks(level, snapshot, dx, dy, dz)`。

### 关于"进水"的二次假设（**未证实**）

smoketest 量到装配后 `舱室格 267（水=267）｜船体格 408（水=408）`。
若成立，机制是：VS2 把方块挖走后海里出现空腔，海水立刻灌满；而船是渲染在船坞区块上、
正好渲染在这个空腔位置——**透过透明船壳看就是"船里全是水"**。

**但这批数据是污染的**：同一个 smoketest 在这之前跑过「开门 / 破口」场景，
那些场景会把舱室重新分舱成"连海"，`ShipWater.apply` 本来就会写满 267 格水。
所以不能把 267 算到装配头上。

为此写了 `HullPlug`（`barotrauma:hull_plug`：不可见 / 不碰撞 / 挡水，装配后填满船的
包围盒，按整格差集跟船移动，拆船前撤掉）和 `/barotrauma ship vs flood` 诊断命令，
**但方案前提没证实**。下次开工必须先用隔离场景验，见 STATUS.md。

### 这一轮验证状态

```
gradlew build --offline              ✅ jar 231 KB
gradlew test --offline               ✅ 17 用例 / 0 失败
gradlew runGameTestServer --offline  ✅ All 3 required tests passed :)
```

装配对账/回滚没跑过真实装配；`HullPlug` 完全没验证。

### 附带发现

- VS2 的船**不在别的维度**：`ShipAssembler.moveBlocksFromTo` 把方块铺在
  **同一维度的预留远处区块**（船坞），坐标是 `ship.chunkClaim`。
  实测那一批船坞在 `chunk[-1791872..-1791871, 768127..768128]`。
  所以 `valkyrienskies:shipyard` 那个维度在这条路径上没参与。
- `run/world` 被删重建：上一轮 smoketest 把初始潜艇装配走、没拆回来就存盘，
  世界文件里那块就是个灌满水的坑。**自检会改存档，跑之前先想清楚要不要备份。**

## 10.39 ZCode 侧 MC 开发技能调研与安装（2026-11-02）

> 用户要求："去 GitHub 上找找 mc 模组开发插件"。ZCode 桌面环境里此前没有任何
> Minecraft 技能（DSH 的方案 C 只覆盖 DSH 宿主）。

### 候选与结论

| 候选 | 结论 |
| --- | --- |
| **Jahrome907/minecraft-agent-skills**（196★，2026-10-30 仍在更新） | ✅ **选它**。13 个技能，v2.5.4 起自带标准 `.claude-plugin` 插件目录；`minecraft-modding` 明确覆盖 **Forge 1.20.1 + Java 17 + ForgeGradle 6**（我们的版本）；testing（JUnit/GameTests）、world-generation、resource-pack、datapack 直接可用 |
| chouzz/minecraft-mod-dev | ❌ 只面向 NeoForge/Fabric + 1.21+（"Migrate code to 1.21+"），无 1.20.1 |
| vfyjxf/ModDevMCP | ❌ NeoForge 调试 MCP（本地 HTTP + skill-first），不支持 Forge 1.20.1；7★。思路（agent 直接操作运行中的游戏实例）值得以后参考 |

- skillsdirectory 上那个 "Grade A" 的 minecraft-modding 技能，源头就是
  Jahrome907/minecraft-agent-skills（gabrielmoreira/agent-skills-mirror 是它的镜像）。
  即：DSH 方案 C 装的 13 个技能与本次装的是同一套上游。

### 安装方式（ZCode 本地测试市场，plugin-creator 正路）

- Jahrome907 仓库**不是**标准市场布局（根目录无 `.claude-plugin/marketplace.json`，
  只有一个插件目录 `plugins/minecraft-codex-skills/`），不能直接
  "添加 GitHub 市场"，所以走本地市场。
- 插件源：`D:\ZCode work\default-workspace\plugins\minecraft-codex-skills\`
  （13 个技能原样拷贝，SKILL.md 一律未改；`.claude-plugin/` 清单换成
  `.zcode-plugin/plugin.json`，版本 2.5.4 保持）。
- 本地市场：`D:\ZCode work\default-workspace\plugins\marketplace.json`，
  市场 ID `dev-default-workspace-9f3d1272`，
  插件全 ID `minecraft-codex-skills@dev-default-workspace-9f3d1272`。
- 用户侧 UI 步骤：插件市场 → 添加 → 添加插件市场 → 粘贴
  `D:\ZCode work\default-workspace\plugins` → 安装 minecraft-codex-skills → 重启宿主。
- **使用约定沿用 AGENTS.md §4**：13 个技能里只有 `minecraft-modding` 真覆盖 1.20.1，
  其余 12 个 description 写的是 26.x/1.21.x——点名时才加载，不为自动匹配改 description。
- 评估时浅 clone 了三个仓库到会话目录 `_mc_plugins\`（仅作参考，不参与构建）。

### 踩坑记录

- GitHub API 未认证配额（60/h）按出口 IP 共享，18.166.98.56 这个代理出口经常
  已被打满 → **抓 GitHub 一律走代理 + raw.githubusercontent.com / git clone**，
  别依赖 api.github.com；WebFetch 直连 GitHub 会 ECONNRESET，也走代理。

## 10.40 哨站改挂"最上方岩壁下方"（2026-11-02）

> 用户反馈："把初始站点的生成规则改一下，放到最上方的岩壁或者岩柱下方，之前的都是洞"。

### 为什么之前是"洞"

- 哨站硬编码在 y∈[-160,-140]，而 `carveCavern` 在**同一位置**挖半径 34×26×34 的椭球
  （中心 y=-150，y∈[-176,-124]）。椭球顶部 -124 把第一层顶板岩层直接挖穿，
  哨站悬在椭球洞正中，四周是光滑洞壁 → 观感就是"生在洞里"。
- 第一层顶板厚度由噪声决定：`roof = rock + hanging`，
  `rock ∈ [0.12, 0.30]`、`hanging ∈ [0, 0.38]` → 顶板厚 14~82 格，底面在 y=-44~-112 之间波动，
  想直接"贴上去"必须先把顶板底面压平。

### 新布局

| 项 | 旧 | 新 |
| --- | --- | --- |
| 哨站 y 范围 | -160..-140 | **-64..-44**（顶装甲与顶板底面重合） |
| 出生点 | -158 | **-62** |
| 水池椭球 | 中心 y=-150，顶 -124（挖穿顶板） | **中心 y=-72，顶 -46（不碰 -44 顶板）** |
| 初始潜艇 | y -152..-148 | **y -72..-68** |
| 门外平台 | y=-159 | y=-63（`OUTPOST_SPAWN_Y-1`） |

- **压平**：`OutpostBuilder.inRoofFlattenZone(x,z)`（哨站范围外扩 1 格）内的列，
  `rock = FLAT_ROOF_FRACTION = 0.12`（噪声最小值）、`hanging = 0`。
  第一层顶板底面因此固定在 **y=-44**（frac(‑44)=14/120<0.12 是最后一块岩）。
  压平区外噪声顶板只会更厚（底面更低），岩壁从平顶向四周自然下垂，不会留悬空边。
- 哨站 `MAX_Y=-44` 用装甲替换最后一块顶板岩 → 气密直接靠装甲，无接缝。
- 椭球中心下移后：哨站下半截（y<-46）泡在水腔里、上半截（y=-46..-44 装甲）贴着顶板；
  气闸门外 (0,-62,13) 与初始潜艇 (z 16..30) 都核对过在椭球内。
- `rising`（升柱）保留：第一层底板顶面在 y≈-135，离哨站 70 格外，不冲突。

### 改动文件

- `world/OutpostBuilder.java`：y 常量、压平判定/常量、椭球参数、门外平台、类注释
- `world/DeepSeaChunkGenerator.java`：分层循环里按 `inRoofFlattenZone` 压平 rock/hanging
- `world/ModDimensions.java`：`OUTPOST_Y=-54`、`OUTPOST_SPAWN_Y=-62`
- `world/StarterSub.java`：`ORIGIN_Y=-72`
- `event/DeepSeaEvents.java`：自检硬编码 `-142`/`-150` 改成常量；新增两条几何断言
  （哨站是否贴岩壁 / 侧面是否有水）

### 验证（2026-11-02 00:38，run/logs/latest.log 原文）

```
[smoketest] outpost floor=station_plating air=air
[smoketest] outpost leaked water blocks = 0
[smoketest] roof seal: armor(0,-44)=station_plating rock(0,-43)=stone     ← 顶上直接是岩
[smoketest] pool: beside hull (22,-54)=water station ceiling (0,-45)=station_plating
[smoketest] door=oak_door[...] outside=water shell=station_plating
```

- `gradlew build --offline` ✅；L1 17 用例 ✅；L2 `All 3 required tests passed` ✅。
- `run/world` 已删重建（新生成规则），`.smoketest` 标记已删。
- **观感尚未人工确认**：需要用户进游戏看"哨站是不是真的贴着岩壁"。
  服务端只能验几何，验不了视觉。

## 10.41 隔离自检结束：进水前提成立，排水体被 VS2 事后清掉（2026-11-02 01:19）

> STATUS §"下次开工第一件事" 的隔离 smoketest 已实现并跑完。
> 入口：`run/.smoketest-vs`（`VsFloodSmoke.java`），跑完自动关服。

### 场景

深潜维度找一片 9×9×9 全是水的干净位置 → 造 5×5×5 透明船（内部空气 + 中央声纳台）
→ 扫描（1 舱室、27 格、密闭）→ 量一次 5×5×5 → 装配 → 40 tick 后量 → 拆回量。
分两阶段：阶段 1 直接 `VsBridge.assemble`（不装排水体、不对账）；阶段 2 走正式
`VsPilot.launch`（对账 + 排水体）。

### 测量结果（run/logs/latest.log 原文，01:19）

```
阶段1 装配前：水=0 空气=26 排水体=0 别的=99
阶段1 装配后立即：水=0 空气=125 排水体=0 别的=0
阶段1 装配后 40 tick：水=125 空气=0 排水体=0 别的=0      ← 原始装配 100% 灌满
阶段1 拆回后：水=26 空气=0 排水体=0 别的=99               ← 铺回后舱内仍积水（已修）
阶段2 装配后立即：水=0 空气=0 排水体=125 别的=0
阶段2 中途（20 tick）：水=0 空气=0 排水体=125 别的=0
阶段2 装配后 40 tick：水=0 空气=0 排水体=125 别的=0       ← 修复后排水体守住
阶段2 拆回后：水=0 空气=26 排水体=0 别的=99               ← 拆回的船是干的
```

**判定：排水体的前提成立（原始装配确实把 125 格全灌成水），HullPlug 保留。**

### 顺带查明的两个真 bug（都已修）

1. **VS2 装配协程会事后把排水体清掉**。
   `assembleToShip` 返回后，VS2 的 "Sandwich Factory" 还在等船坞区块开始 tick；
   无头/远处区块等不到（`Timed out waiting for chunks to start ticking after assembly!`），
   超时"强行继续"时会再清一遍源区域——把 `HullPlug.install` 刚铺的排水体一起清掉。
   修复前：装配后立即 plug=125，20 tick 后 plug=0、水=125（HullPlug 自己还以为在）。
   → `HullPlug.ensure(level)` 每 tick 把船位内缺的格子补回（只在空气/水上铺，不覆盖玩家方块），
     `VsPilot.tick()` 里跟在 `moveTo` 后面调用。修复后：中途/40 tick 都是 plug=125。
2. **拆船后舱内积水**。`dock()` 铺回方块时，那片体积早被海水灌满，方块一铺就把水封在舱里
   （阶段 1 拆回：舱内 26 格全是水）。→ `ShipState.clearWater(level, dx,dy,dz)` /
   `ShipWater.clear(level, dx,dy,dz)` 支持位移，`dock()` 铺回后调用。修复后拆回：空气=26。

### 验证状态

- `gradlew build --offline` ✅（238 KB）
- L1 17 用例 ✅、L2 3 GameTest ✅（01:20）
- 隔离场景两阶段数据 ✅（上面）
- 装配对账第一次跑真实装配：质量 196300/196300（100%）✅
- **待用户进游戏看**：装船后船里是否还有水（几何与方块层面已经正确）

### 仍然存在的风险

- VS2 的船坞区块超时是无头环境的固有问题；真实客户端有玩家时是否也超时没验证。
  但 `ensure` 是每 tick 兜底的，超时与否都能把排水体补回来。
- 排水体每 tick 扫一遍船位（675 格级别的船 ≈ 675 次 getBlockState），开销可接受；
  若以后船很大再改成"检测到丢失才全扫 + 差分"。

## 10.42 只保留物理船 + 删机器/舱门 + 排水体改形状（2026-11-02 凌晨）

> 用户实测反馈：水不进了；但物理船"完全没法开、一直往上浮、人在船上卡在玻璃里、
> 声纳点不开、左键只有粒子"；"一直清空潜艇附近的水，太割裂"。
> 用户拍板：**只保留开物理船的功能，方块船不再动了**（"动的时候一卡一卡的"）；
> 删掉五台机器和舱门/舱口。

### 根因（VS2 2.4.11 的能力边界，已从 jar 的混入清单确认）

- `valkyrienskies-1.20.1-forge-2.4.11.jar` 里只有 `feature.block_placement`
  （在船上**放**方块），没有 `ServerPlayerGameMode` / `useItemOn` 的坐标重映射：
  **物理船上不能右键使用方块、也不能破坏方块**。方块在船坞区块、渲染在船位，
  所以左键只有客户端粒子、右键声纳无效。
- 解决方式不是改 VS2，而是给声纳加一个不走方块交互的入口：
  `/barotrauma ship sonar`（打开与右键相同的界面；物理模式下 `SonarMenu.stillValid`
  直接放行，因为声纳方块在船坞、距离判定永远失败）。

### 删掉的东西

| 删除 | 原因 |
| --- | --- |
| 五台机器：`nuclear_reactor` / `oxygen_generator` / `engine` / `ballast_tank` / `pump` | 除了被 ShipSimulation 计数外没有任何实现（无方块实体、无交互） |
| `airlock_door`、`hatch`（ShipDoorBlock） | 用户点名删除；`ShipDoorBlock` 类一并删除 |
| 方块形态移动：`ShipMover`、`ShipState.tickMovement/applyStep/slide`、`/barotrauma ship move`、自检的 move/drive 场景、舱门连通自检 | 用户："方块船不再动了，动起来一卡一卡的"；只留 VS2 物理船 |
| 相关注册/物品/创造栏/语言/blockstate/model/texture/vs_mass 条目 | 随方块删除 |

- 自检船与初始潜艇现在只有船壳 + 声纳台。

### 排水体改造（解决"清空潜艇附近的水"）

- `HullPlug.install(level, Collection<BlockPos> cells)`：从"包围盒"改成
  "船体方块 + 密闭舱室"精确形状。船轮廓外的海水不动。
- `moveTo` / `remove`：腾出来的格子**回填海水**而不是空气——不再拖干燥尾迹，
  服务器重启后也不会留下看不见的方块壳。
- `ensure` 保留：每 tick 补回被 VS2 装配协程事后清掉的格子。
- `VsPilot.launch` 传入 `captured.keySet()` + `shipState.compartments()`。

### 物理船浮力

- `VsThruster` 加"深度保持"：没有垂直输入（且压载/进水≈0）时，
  垂速阻尼 `-vy×2.0` + 深度误差回拉 `(holdY-y)×0.8`，锁在松开按键那一刻的深度。
  修用户实测"物理船一直往上浮"。有垂直油门/压载/进水时自动让开。

### 新增命令入口

- `/barotrauma ship sonar`：打开声纳界面（不依赖方块右键）。
  其余 `throttle / stop / power / autopilot / vs *` 保留。

### 验证

```
build --offline                  ✅
L1 17 用例 / L2 3 GameTest        ✅（本轮大改前跑过；改后 build 绿 + 两套自检绿）
.smoketest（总自检）              ✅ 402 船体方格 + 声纳×1；质量 3156000/3156000
  装配后 10s：舱室水=0（别的=273 是排水体）｜船体格水=0（别的=402）
  拆回：铺回 403 格
.smoketest-vs（隔离）             ✅ 原始装配 40 tick 水=125；正式管线水=0 排水体 125；
                                  拆回后舱内空气 26
```

### 仍未解决 / 待用户实测

- 玩家"卡在玻璃方块里"：未单独定位；可能与玩家在船内装配后的碰撞/随船移动有关，
  等新版本实测复现再查。
- VS2 2.4.11 物理船上不能破坏方块（硬限制）：拆船后编辑，或接受。
- 4 秒后船坞区块超时警告仍在（无头环境固有），`ensure` 已兜底。

## 10.43 舱内真空气 + 声纳交互锚点（2026-11-02 凌晨，用户拍板可玩性优先）

> 用户反馈："你让船舱不进水的实现方式是往船里面填满那个排水方块吗？这条路不行，
> 潜艇上的方块肯定不能不交互。" 并要求"以后再做先考虑可玩性"（已记入长期记忆）。

### 舱内改真空气

- `HullPlug.install` 只接收**船壳方块**的位置（`captured.keySet()`），舱室格子不再填。
- 船整格移动时，`VsPilot.clearInteriorWater(level, dx,dy,dz)` 把新位置舱室里的水清成空气
  （只在整数偏移变化时扫一次；空腔自检：舱室 273 格里 272 格是空气，1 格是声纳锚点）。
- 挪走/拆船时，排水体腾出的格子回填海水（§10.42）。
- 保留 `ensure` 每 tick 补被 VS2 装配协程清掉的船壳格子。

### 声纳交互锚点（解决"船上右键不到方块"）

- 新增方块 `barotrauma:sonar_proxy`（不可见、不碰撞、不可破坏，无物品、不进创造栏；
  不属于 `isShipBlock`，不会被扫描/二次装配）。
- `VsPilot.launch` 在装配成功后，把锚点放在声纳原来的位置；`moveProxy` 每 tick 跟着
  船整格移动；`dock` / `dropStale` 时撤掉。
- 右键锚点 = 打开同一个声纳界面（`SonarProxyBlock.use`）。
- `BlockEvent.BreakEvent` 取消对锚点的破坏（创造模式也拆不掉）。
- 命令入口 `/barotrauma ship sonar` 保留作兜底。

### 验证（02:31 日志）

```
隔离自检：阶段2 装配后 40 tick：水=0 空气=26 排水体=98 别的=1（5×5×5 测试船）
总自检：舱室格 273（水=0 空气=272 别的=1）｜船体格 402（别的=402）
        10 秒后同上；拆回 403 格
outpost leaked water blocks = 0
```

### 仍未做（VS2 2.4.11 硬限制）

- **物理船上不能破坏方块**：VS2 没有 `ServerPlayerGameMode`/`useItemOn` 的坐标重映射，
  客户端射线拿到的是船坞坐标，服务端按距离校验拒绝。要支持"打穿船壳进水"需要
  我们自己在产物包里拦 `ServerGamePacketListenerImpl` 的 use/break 包（工作量中等，风险较高）。
  当前用交互锚点覆盖了"控制方块必须能用"这个可玩性核心；破坏船壳暂缓。

### 10.43 补充：用户实测（02:36–02:38 客户端日志）

- 02:36:03 装配成功：403 格，质量 809700/809700（100%）。
- 02:38 期间船开出去 **偏移 (22, 13, 79)**——声纳界面能操作、船能开。
- 每走一格都有 `[vs] 舱内排水：清掉 N 格（偏移 x,y,z）`，舱内始终是空气
  （N 通常 40~91，只有新进入水的格子）。
- 聊天里出现 `实时｜...` 说明船控面板指令在发（声纳界面确实打开了，交互锚点生效）。
- 顺带清掉机器时代的界面噪音：`liveReport` 不再报温度/功率/氧气/气闸，
  只报「舱内水 / 进水% / 航向 / 推力」；`MachineCount.describe` 只报声纳。

### 10.43 补充 2：自动驾驶与声纳船体点（用户 02:40 反馈）

| 现象 | 根因 | 修法 |
| --- | --- | --- |
| 自动驾驶不会动 | `tickMovement` 删除后，`aimAtTarget` 没有任何调用点了；物理船只会照 `ShipState.throttle` 推，而它是 0 | 新增 `ShipState.autopilotStep(currentPos)`（直接写 throttle，不走 `setThrottle` 以免退出自动驾驶）；`VsPilot.tick` 在算完整数偏移后调用它，再 `applyControl` 推给 VS2 |
| 声纳里的船体点不跟船走 | 声纳扫描以玩家（船）为中心，但 `geometry().hull()` 是**装配时**的世界坐标；船开走后点还留在原地 | `SonarPingPacket.hullForSonar`：物理模式下把船体点按 `VsState.offsetX/Y/Z` 平移 |

- 下潜慢 / 上浮快：用户明确说**先不管**，等以后做压载舱再调垂直加速度。
- 本轮验证：build ✅、L1 17 ✅、隔离自检 ✅（水=0 空气=26 排水体=98 锚点=1）。

## 10.44 船体耐久 / 漏水 / 框架（用户 2026-11-02 睡前提出的目标）

> 用户："船体能不能不改成排水方块？因为会一直来回乱闪。而且后期我想做成类似潜渊症那样，
> 船体方块每个都有损坏程度，耐久度低时漏水，为 0 时只剩框架能让玩家/怪物钻出去。"

### 为什么排水体不能完全去掉

VS2 装配把船体方块搬进船坞，世界船位是空的。海里的一块空气必须有方块挡水，
所以船壳位置一定有"替身方块"。它不是把船体改掉，而是替身；舱内已经是真空气。

### 闪烁：已修（待实测）

- 根因假设：船静止在小数 .5 附近时 `Math.round(pos - assembledPos)` 每 tick 在 N/N+1 之间跳，
  排水体跟着每 tick 挪一格、旧格回填水 → "来回乱闪"。
- 修法：`VsPilot.stableOffset` 迟滞死区（离当前格 < 0.62 不换格）。build/L1 ✅，待用户实测。

### 耐久系统设计（下一轮做，先记方向）

**路线 A：VS2 船坞方块上做（推荐先做，见效快）**

| 需求 | 实现 |
| --- | --- |
| 每块耐久 | `ShipDamage` 表（按方块相对偏移存 HP），碰撞/攻击/怪物扣血 |
| 耐久低漏水 | 该格排水体从 `hull_plug` 改成水，且**暂停 clearInteriorWater**（有破口时让水自然灌满） |
| 耐久 0 开洞 | 删除船坞里那块方块（VS2 质量/碰撞更新），排水体对应格改水；洞是真实可穿的 |
| "框架" | 用无碰撞形状的自定义方块占位（VS2 碰撞当空气，但仍渲染成框架）替换 0 耐久格 |
| 玩家/怪物钻出 | 洞格无方块/无碰撞，实体自然可过 |

风险：VS2 的方块更新/质量重算、渲染同步；但比换物理方案小。

**路线 B：回到"方块留在世界里"（最终最像潜渊症）**

- 破损=方块状态变化，水从洞自然流入，没有排水体、没有闪烁。
- 代价：移动只能整格（需客户端平滑渲染 + 自定义碰撞），等于自己写移动/渲染层，工作量以周计。
- 如果 A 路线在 VS2 同步上反复踩坑，再切 B。

**当前决定**：先修闪烁；耐久系统下一轮按路线 A 做最小闭环（HP→漏水→开洞→框架）。

### 10.44 补充：半格错位 / 自动驾驶惯性 / 舱内残水（2026-11-02 中午）

#### 闪烁的真正原因：连续渲染 vs 整格排水体

用户："由于排水体是方块形态，所以在方块船 y 轴位置不是整数的情况下能看见半个凸出来的方块。"
VS2 的船按**连续坐标**渲染，排水体/水是**整格方块**——船 y=-69.4 时，船壳的渲染面和
方块边界差 0.4 格；透明船壳直接把这个"半格水/缺口"露出来，船一动就来回闪。

- **固有错位，不能消除**（除非把船渲染也量化到整格，那会变成跳格移动）。
- 缓解：**船壳别用透明方块**。`StarterSub` 改成不透明 `submarine_plating` + 玻璃舷窗；
  玩家自建的透明壳船仍会看到这个现象。
- 偏移迟滞（`stableOffset`，0.62 格死区）保留，减少静止时的方块/水来回换。

#### 自动驾驶：加惯性补偿（PD）

`ShipState.autopilotStep(x,y,z,vx,vy,vz)`：
期望速度 = 位置误差 × 0.5（限幅 6 格/秒水平、2.5 垂直），
推力方向 = 期望速度 − 当前速度 → 接近目标会提前反向刹车；到点 1.5 格内且速度 < 0.6 自动收油。

#### 舱内残水：每 10 tick 兜底清一次

船移动时偶尔有几格水从船壳/排水体边界缝挤进舱内，只按"整数偏移变化"清会漏。
`VsPilot.tick` 每 10 tick 调 `clearInteriorWaterNow`（强制清舱室格的水）。

#### 《潜渊症》实现参考（本机 Steam 版 Barotrauma.dll + PDB 反射导出）

源码不公开，但类结构可读（工具：`tools/AsmDump`，用 `dotnet csc` + `System.Reflection.Metadata`）：

| 类 | 关键成员 | 机制 |
| --- | --- | --- |
| `Hull` | `waterVolume`、`pressure`、`surface`、`oxygen`、`ConnectedGaps` | **水按舱室体积存**，水面高度由体积换算；不是逐格流体 |
| `Gap` | `open`、`higherSurface/lowerSurface`、`waterFlowThisFrame`、`ConnectedWall` | 舱室之间/舱室与外界之间的开口；`UpdateRoomToRoom` / `UpdateRoomToOut` 按两侧水位差算流量 |
| `Structure` | `Sections`、`LeakThreshold`、`BigGapThreshold`、`SmallGapOpenness`、`LargeGapOpenness`、`AddDamage`、`SectionIsLeaking` | 墙面切成 `WallSection[]`，每段独立 `damage`；到漏阈值开始渗水，到大洞阈值开洞 |
| `WallSection` | `damage`、`gap` | 一段墙的状态 |

**对我们的映射**：每块船体 = 一个 WallSection；damage < LeakThreshold 不漏，
≥ LeakThreshold 慢慢往舱室 `waterUnits` 加水，≥ BigGapThreshold 删方块 + 排水体开洞（大流量）。
水按舱室 `waterUnits` → `ShipWater` 从舱底铺格，和原版一个模型。

## 10.45 方向调整：方块船回归（2026-11-02 中午，用户拍板）

> 用户结论：VS2 的半格错位/不能破坏方块没法接受；要"船壳本身挡水 + 每块耐久/漏水/框架"。
> 用户答复："读 VS2 源码重新改一个物理"（已提供 VS2 仓库和 wiki 链接）。

### 本轮做了什么（MVP，可玩性优先）

- **恢复方块船移动**（`ShipMover` 从 git 恢复，`ShipState.tickMovement/applyStep/slide` 恢复），
  `ShipCoreBlockEntity.serverTick` 重新调用；VS2 物理船代码保留但不再是主路径。
- **提速**：`ShipPhysics.MAX_SPEED` 6 → **10 格/秒**，`MAX_RISE` 2.5 → 3.5（减少跳格感）。
- 方块船 = 船体方块留在世界里：船壳天然挡水、破洞天然进水、方块可交互/可破坏，
  不再需要排水体、声纳锚点、半格错位也不存在。
- 验证：build ✅、L1 17 ✅（速度断言同步更新为 8.92 / 0.97）、总自检 ✅。

### 下一步（自己的"物理"层，按优先级）

1. **平滑移动**（核心）：服务端保持整格权威移动，客户端把船体渲染成插值后的"幽灵"，
   解决 10 格/秒下的跳格感。VS2 的 `ClientShipTransformProvider` / 渲染思路可参考，
   但方块留在世界、服务器碰撞/水仍按整格。
2. **耐久系统**（PLAN §10.44 路线 B）：每块船体 HP → 低耐久漏水（往舱室 waterUnits 加水）
   → 归零开洞（方块消失，水自然灌入）。
3. VS2 物理船代码暂时保留（`/barotrauma ship vs *`），确认方块船稳定后再决定删不删。

### 提醒

- 旧的 `ShIPhysicsTest` 断言已随新速度更新；以后改速度记得同步测试。

### 10.45 结论：方块船否决，VS2 回归主路径（同日）

用户实测："方块船太烂了，移动的时候都找不到声纳，而且乱闪。"
原因：方块船是"两端整格"——玩家/声纳跟着整格跳、400 多块方块每 2 tick 重写一次，
视觉和操作都不如 VS2。**方块船移动已关闭**（代码保留，等以后做"服务端整格+客户端插值"时再用）。

**VS2 的物理粒度（回答用户提问）**：服务端和客户端都是**连续**的——
vs-core 以约 60Hz 做刚体积分、transform 是连续位置+四元数；客户端插值渲染；
方块只是搬到船坞区块当体素数据，世界里没有整格搬动。所以"平滑的船 + 格子的水"
必然出现半格错位；只能靠不透明船壳/舷窗遮掩，或者把水也做成平滑渲染（不现实）。

**下一步（可玩性优先）**：VS2 主路径 + 自己补上它缺失的方块交互
（在产物包里拦 `ServerGamePacketListenerImpl` 的 use/break，把船位坐标映射到船坞坐标），
这样船能开、声纳/方块也能点；耐久/漏水再在这个基础上做。

## 10.46 《潜渊症》船/水实现（反编译源码结论）与我们的"双物理化"设计

> 用户目标："把船和水都物理化"。本机 Steam 版 `Barotrauma.dll` + PDB 已用 ilspycmd 8.2 反编译，
> 结论来自真实代码（`_baro_src/Hull.cs`、`Gap.cs`、`Structure.cs`），不是推测。

### 原版怎么做（2D）

**Hull（舱室）= 水体积容器**
```csharp
Volume = rect.Width * rect.Height;                 // 舱室体积
surface = rect.Y - rect.Height + WaterVolume / rect.Width;  // 水面高度 = 舱底 + 水体积/宽度
Pressure = surface;                                // 压力直接等于水面高度
```

**Gap（开口）**
```csharp
// 舱室 ↔ 外界（进水）：
num  = Size * open * open * (1 - overlapReduction);   // 开口面积 × 开度²
flow = 500 * num * dt;                                // 每秒 500 单位/单位开口
flow = min(flow, Volume*1.05 - WaterVolume);          // 不超过剩余容积

// 舱室 ↔ 舱室：
num  = Size/100 * open * (1 - overlapReduction);
flow = min((P_high - P_low) * 300 * num * dt,
           min(源舱水量, 源舱体积));                    // 压差驱动
flow = min(flow, 目标舱 Volume*1.05 - WaterVolume);
```
水面的波动是 `WaveY/WaveVel` 弹簧网格（`CreateWave`），只影响渲染。

**Structure（墙）= 逐段耐久**
```csharp
LeakThreshold   = 0.10f;   // damage ≥ 10% MaxHealth → 生成 Gap（开始进水）
BigGapThreshold = 0.70f;   // ≥70% → 大洞
SmallGapOpenness = 0.35f;  // 10%~70% 之间开度 0→0.35 线性
LargeGapOpenness = 0.75f;  // ≥70% 开度往 0.75
// damage < 10% 时把已有 Gap 移除
```
墙被切成 `WallSection[]`，每段独立 damage/gap；`AddDamage/SectionIsLeaking`。

### 我们的 3D 映射（设计）

| 原版 | 我们 |
| --- | --- |
| Hull 矩形体积 | `ShipGeometry.Compartment`（格子集合）+ `Volume = 格子数`（1 格 = 1 m³） |
| surface = 底 + vol/宽 | 逐层填充：按 Y 累计截面积，水面 = 填满的层 + 部分层比例（`ShipWater` 已有底部铺格逻辑） |
| Pressure = surface | 用世界深度/水面高度；与大水体连通时按外界压力 |
| Gap | 被破坏的船壳格（或 0 耐久格）作为开口 |
| 开口流量公式 | 直接复用：外界进水 `K_out·面积·open²·dt`；舱间 `K_rr·压差·面积·open·dt`（常数按 MC 尺度调） |
| Structure/Section | 每个船壳方块 = 一段墙，独立 HP；10% → 小漏，70% → 大洞，0 → 方块消失 |
| 水的渲染 | **舱内自绘平滑水面**（船本地坐标的平面/四边形），不再用 MC 水方块；这样船和水都是连续渲染，半格错位消失 |

### 关键取舍

- **舱内不写字方块**，水面由客户端按 `WaterVolume/Volume` 渲染（半透明平面 + 轻微波浪）。
  舱内看到的是平滑水面；船外仍是 MC 海水方块（隔着外壳看，不影响）。
  破口进水时水面上升是连续的，不会再出现"半格水/方块闪"。
- 船体仍是 VS2 刚体（连续物理）；方块留在船坞，不参与世界水。破损时删除船坞方块 +
  在 Gap 模型里开洞，水流按公式走。
- 浮力：用 `WaterVolume/Volume` 和舱室位置算排水量，给 VS2 加浮力/重力（替代当前"中性补偿"）。

### 下一步（实现顺序）

1. `ShipWaterVolume`：每舱室体积/水量/水面高度，10Hz 更新（替换现有方块水位写法）。
2. `GapModel`：从船壳破损格生成开口，按原版公式算舱外/舱间流量。
3. 客户端水面渲染（船本地坐标，VS2 transform 绘制）。
4. 耐久：每块 HP → 10%/70% 阈值 → 开洞/删方块。
5. 浮力接物理：按舱室水量算排开体积，影响垂直加速度。

### 10.46 补充：Physics Mod 调研 + 排水方块去留（用户问）

**haubna/PhysicsMod**（130★）：仓库只有文档和 RagdollHook 示例，没有完整源码（Pro 功能闭源）。
其 "Ocean Physics 3.0" 是**给 Iris shaderpack 用的 API**（`gbuffers_water` 里调
`physics_oceanVertex/Fragment` 做波形位移/法线/泡沫），**纯客户端渲染，不是服务端流体模拟**。
可参考其"客户端自绘海面"的思路，但不能直接拿来解决舱内进水/浮力。

**要不要排水方块（用户问）**：
- 按"舱室水体积 + 开口流量"模拟后，舱内的水不再是 MC 水方块，所以**不需要用排水方块来挡舱内水**。
- 但船用 VS2 时，世界船位是空的，舱外 MC 海水仍会灌进那片空间 → 仍然需要阻止它：
  - (a) 排水方块（有半格错位，用户不接受）；
  - (b) **拦截流体流动**（mixin 阻止 MC 水流入船体包围盒）——没有方块，但水边界仍是格子，
    半格在"船外水 vs 船壳"交界处仍可能出现（只是没有隐形方块）。
- **真正消除半格**只有把船外的水也自绘（像 Physics Mod 的海面），成本高。
  可玩性优先的折中：不透明船壳 + 小舷窗（已做），排水方块换成流体拦截，
  舱内水按体积模拟 + 平滑水面。

**结论**：可以去掉排水方块，改为流体拦截；这解决"船壁进水"（按耐久/开口模拟，而不是 MC 水灌）
和"隐形方块凸半格"的观感，但船外 MC 水与船壳的交界半格只能靠不透明船壳遮掩。

## 10.47 去掉排水方块：流体拦截（M1，已完成并验证）

> 用户："排水方块和船 y 轴高度不一样多出来半格太难受了，就解决船壁会进水的问题就好了。"
> 决定：不铺排水方块，改为**拦截 MC 水流进船体体积**；海面自绘（M2）后续做。

### 实现

1. **MixinGradle 接入**：`build.gradle` 加 `org.spongepowered.mixin` 插件 + `mixin { add sourceSets.main,
   "barotrauma.refmap.json"; config "barotrauma.mixins.json" }`；新建 `barotrauma.mixins.json`。
2. **`ship/water/ShipVolumeGuard`**：记录物理船的装配包围盒 + 整数偏移；
   `activate / setOffset / deactivate / isProtected`。
3. **`mixin/LevelFluidGuardMixin`**：注入 `Level#setBlock(BlockPos, BlockState, int, int)` 的 HEAD，
   目标是水且位置在物理船体积内 → 直接取消。选 `setBlock` 是因为 MC 水流扩散最终都走这里。
4. **`VsPilot`**：装配成功后 `ShipVolumeGuard.activate(...)`；每 tick `setOffset`；
   `dock` / `dropStale` / 装配失败回滚时 `deactivate`。
   **不再调用 `HullPlug.install/ensure/moveTo`**（`HullPlug.remove` 保留，用于清理旧存档的排水体）。
5. 声纳交互锚点、舱内排水逻辑保留。

### 验证（`run/.smoketest-vs`，12:38 日志）

```
阶段1（原始装配，无拦截）：装配后 40 tick  水=125             ← 对照
阶段2（正式管线 + 拦截）：装配后 40 tick  水=0 空气=124 排水体=0 别的=1
判定：没有排水体、也没有水 → 流体拦截（ShipVolumeGuard）生效
汇总：原始装配后水=125｜正式管线后水=0 排水体=0｜拆回后水=0
```

- 舱内是真正的空气（124 格空气 + 1 格声纳锚点），**没有任何排水方块**；
- 船外的 MC 水照常存在，只是写不进船体体积；
- `build --offline` ✅；L1/L2 之前已绿（本轮只加 mixin/guard，仍需回归确认）。

### 已知限制 / 下一步

- 船外 MC 水与船壳的交界仍是格子边界（没有方块了，但水是格子）；彻底消除要等 **M2 海面自绘**。
- 持久化：重进世界后 `VsPilot`/`ShipVolumeGuard` 状态丢失，已存在的船不会被认领（旧问题）。
- 下一步顺序：M2 客户端平滑水面 → M3 舱室水体积/Gap 流量 → M4 每块耐久/漏水/框架。

## 10.48 M2 水体皮肤 + M3 破口渐进进水（2026-11-02 下午）

### M3：舱室水体积 → 渐进进水（服务端，可验证）

- `ShipSimulation` 原本已有"每舱室水量 `double[] water`"（1 格 = 100 单位）。
- 新增 `pocketWater[]`（连海空腔 = 破口舱室）与 `tickSeaFlood(dt, depthMeters)`：
  `flow = 2 格³/秒 × (1 + 深度/100) × dt`，按空腔容积封顶。
  参照反编译的《潜渊症》`Gap.UpdateRoomToOut`（原版 `500 × Size × open² × dt`，我们做了尺度简化）。
- `ShipWater.apply` 里连海空腔不再"直接满水"，而是按 `pocketWater` 铺格。
- `ShipCoreBlockEntity.serverTick` 每 0.1s 调 `tickSeaFlood`。
- 新 L1：`ShipFloodingTest`（破口 → 水量随时间增加、被容积封顶）✅。

### M2：海面自绘第一版（水体皮肤）

- `network/VsShipSkinPacket`：装配时把"船 id + 相对船中心的包围盒"发给所有玩家，拆船时发 inactive。
- `client/WaterSkinRenderer`：`RenderLevelStageEvent(AFTER_TRANSLUCENT_BLOCKS)`，
  玩家在船体附近时，用半透明水色体积（`RenderType.debugFilledBox`，双面、不写深度）
  包住船体包围盒，把"船外 MC 水 vs 船壳"的格子边界盖住；从外面看不会罩住船。
- 这是第一版观感验证：颜色/透明度/范围都可调；下一步可换成真正的水面/体积着色 + 波浪。

### 验证

```
build --offline            ✅
L1（18 用例，含 ShipFloodingTest） ✅
L2 GameTest                ✅
.smoketest-vs              ✅ 阶段2 水=0 空气=124 排水体=0（流体拦截仍生效）
```

**水体皮肤是纯客户端渲染，服务端验不了，需要用户进游戏看观感。**

## 10.49 M2 v1 失败复盘：水体皮肤撤掉 + 停写 MC 水（2026-11-02 下午）

用户实测反馈：
1. 水体皮肤"衔接怪、不平滑"——截图里墙上的大块蓝灰就是**盒子内壁**；盒子是 AABB，
   不是真的水面，观感失败。
2. 非物理模式"水变无限水、水生水、海绵没用"——根因是 `ShipWater.apply` **每 10 tick
   重写 MC 水源方块**：海绵吸掉后又被写回来；填满的一层水源在密封舱里还会互相生成。
3. 启动物理后"水变半格进不来、船一开瞬间灌满"——流体拦截区是**整格对齐**的，
   和连续渲染的船壳差半格；船移动后新位置本来就有海水，旧代码只清舱室格、没清船壳格。

处理：
- **删除** `WaterSkinRenderer` / `VsShipSkinPacket`（盒子方案作废）。
- **停止** `ShipCoreBlockEntity` 里的 `applyWater`：不再把水位写成 MC 水方块。
  舱内水只作为模拟量存在（`ShipSimulation`），修掉无限水/海绵问题。
- `VsPilot.clearShipVolumeWater`：船移动时清**船壳格 + 舱室格**里已有的水，
  修"一开船瞬间灌满"。
- 物理船体积内仍然用 `ShipVolumeGuard` 流体拦截（不让 MC 水写进来）。

验证：build ✅、L1/L2 ✅、隔离自检 阶段2 水=0 空气=124 排水体=0 ✅。

**下一步（真正的 M2）**：客户端**平滑水面渲染**——按舱室 `waterUnits/Volume` 算水位，
在船本地坐标绘制半透明水面（不是 AABB 盒子），水面高度连续变化；必要时再加简单波浪。
这一步做完，船内进水才"看得见"且不闪。

## 10.50 船内平滑水面渲染（M2 真 v1，2026-11-02 傍晚）

- 服务端 `VsPilot.sendWaterState`：每 10 tick 把每个舱室的包围盒（装配世界坐标）+
  连续水面高度发给客户端（`ShipWaterStatePacket`）；拆船发空包。
- 客户端 `ShipWaterRenderer`：找到物理船的实时渲染位置，按 `渲染位置 − 装配锚点` 平移，
  用半透明水体（`debugFilledBox`）画每个舱室"舱底到水面"的体积。
  水面高度是连续值，进水时平滑上升，不再有 MC 水的半格跳变。
- 船内不再写 MC 水方块（无限水/海绵问题已修），水只作为模拟量 + 自绘。

**关于"做成潜渊症那样真实的水"的结论**：
- 《潜渊症》的水也不是流体粒子模拟，而是"舱室水体积 + 水面高度 + 开口流量"——
  这部分我们已经按原版公式实现，船内水面可以画得像原版（平面 + 波浪）。
- 船外的海洋是 MC 方块水；要做成平滑海面需要**自绘/着色器替换原版水渲染**，
  是渲染层的大工程（Physics Mod 的 Ocean Physics 也是闭源 Pro + Iris shader）。
  建议分步：先船内水面 → 再考虑船外水面覆盖层 → 最后才谈全海洋自绘。

## 10.51 物理船破口进水链路打通 + 物理船砸方块（2026-11-02 晚）

### 根因（用户："你的屎山代码互相打架了 水进不来"）

1. **物理船只跑了半套模拟**：`VsPilot.tick` 每 2 tick 调用 `ShipSimulation.tick()`，
   但 `tickSeaFlood()` 只在 `ShipCoreBlockEntity.serverTick` 里调用；物理船的方块实体
   在船坞里，`serverTick` 会提前 return。结果：破口空腔的 `pocketWater` 永远是 0。
2. **每 10 tick 强制清船体体积里的水**：`VsPilot.clearShipVolumeWaterNow` 无条件把
   舱室格 + 船壳格的水删掉。就算有别的路径把水写进来，也会被立刻擦掉。
3. **物理船砸方块服务端根本没生效**：VS2 2.4.11 只重映射了放置，没有破坏；
   客户端砸掉的是渲染层/船坞里的方块，服务端世界里那一格是空气，原版 `destroyBlock` 打空。
4. **船坞坐标不知道**：船体方块被 VS2 搬到远处的 claim 区域；没有平移量就既读不到"洞"、
   也删不掉真正的方块。无头服务器里船坞区块不 tick，核心方块实体报不上坐标。

### 修复

- `VsPilot.tick`：在物理船的模拟步里补上 `tickSeaFlood(0.1, 深度)`；
  删除每 10 tick 的强制清舱内水，只在**整数偏移变化**时清一次。
- `ShipVolumeGuard`：从"整个船体包围盒"改成**只保护舱室格**（装配原坐标 + 整数偏移）。
  船壳格让海水自然填满，透过破口看出去不再有一圈干空气。
- `VsPilot` 新增船坞偏移：
  - 优先由核心方块实体在船坞里 tick 时上报（`VsState.observePhysicalCore`）；
  - 无头/区块不 tick 时，围绕 `ChunkClaim` 中心扫半径内的声纳方块反推偏移
    （日志：`[vs] 船坞偏移（扫 claim 中心）`）。
- `VsPilot.refreshHull` 每秒拿 `snapshot` 原坐标 + 船坞偏移对账方块；
  发现破坏就删掉快照项并 `rebuildGeometry`（用船坞方块重新分舱）。
- 新增 `mixin/ShipBreakMixin`：注入 `ServerPlayerGameMode#handleBlockBreakAction` 的
  `START_DESTROY_BLOCK`。`VsPilot.tryBreak` 从玩家眼睛沿视线对船体快照做 AABB 射线
  （带船的小数位移，+1 格余量），命中后删船坞方块、重扫几何、播粒子和声音、
  生存模式掉物品；声纳台禁止拆。
- `ShipState.floodRatio()`：分母包含 `openPockets`，分子包含 `pocketWaterTotal`，
  破船灌水后会真的下沉。
- `ShipSimulation.copySettingsFrom`：破损重分舱时保留反应堆/压载/气泵设置。
- 进水速率 2 → **20 格³/秒**（按潜渊症手感，270 格舱约 13 秒灌满）。

### 验证（2026-11-02 13:44，隔离自检）

```
[vs] 船坞偏移（扫 claim 中心）：(-28514704,-420,12289663)
[vs] 船体破损：1 格被破坏 → 重扫 0 个舱室 / 1 处连海
阶段2 破口 40 tick 后：连海空腔 1 处，模拟进水 2800 单位，进水比例 100%
判定：破口后模拟水开始上涨 ✅
舱内实际水方块（应为 0，水面是客户端自绘）：0
阶段2 拆船：铺回 98 格（破口保留，不再补回）
```

- 外层船壳格水=98 / 舱内水=0：流体拦截只护舱室，船壳格允许海水；
- 破口后 2 秒灌满 28 格空腔（无头服务器），舱内仍是真空气；
- `build --offline` ✅、L1（19 用例）✅、L2 `All 3 required tests passed` ✅、
  `.smoketest-vs` ✅。

### 已知限制

- 物理船砸方块只覆盖**装配快照里的船体方块**；玩家后放的方块暂时不吃这一套。
- 船外海洋仍是 MC 方块水：透过破口看，交界仍在整数格上（"半格"来源），
  彻底解决要等船外海面自绘。
- 水泵只抽密闭舱室，不抽连海空腔（破口够大时泵本来就抽不赢）。
- `VsPilot`/`ShipVolumeGuard` 仍不持久化：重进世界后已装配的船不会被认领。
- 客户端水面渲染仍需进游戏看观感。

## 10.52 实测复盘：空气隧道 + 舱底假水 + 水位归零（2026-11-02 晚）

用户实测（沉底 + 进水已生效）反馈三条：
1. **路过的地方全部变成空气**——船开过/沉过之后海里留下船形空气洞；
2. **进水效果不对**——舱底只有一小滩水，不像水压把水顶上来；
3. **没有水压**——底部破洞水不会往上涌。

日志（`run-client/logs/2026-11-02-1.log.gz`）确认的根因：

1. **拦截体漏了船壳的棱和角**：
   - 第一版只护舱室格（27 格）。船是连续坐标渲染，y 不是整数时舱底会漏出
     半层没被保护的空间，真实海水钻进来在舱底形成一小滩——截图里那滩水不是自绘水面。
   - 第二版改成"舱室 6 邻接外扩 1 圈"，无头自检实测只得到 81 格：
     船壳的 **棱和角** 不在 6 邻接范围内，仍然漏 44 格真实海水。
2. **船尾没有回填**：船每挪一格，`clearShipVolumeWater` 只清**新位置**，
   旧位置的舱室/船壳空气永远留在海里 → 开过的地方一条空气隧道
   （日志里每个整数偏移都有 `舱内排水：清掉 91 格`）。
3. **每砸一个新洞水位归零**：`rebuildGeometry` 重建 `ShipSimulation`，
   `pocketWater` 从 0 开始。用户连续砸 6 格，水位被重置 6 次。
4. **进水速率不随破口大小变化**：固定 20 格³/秒，6 格洞和 1 格洞一样慢。

### 修复

- 拦截格改为 **当前船体快照的全部方块格 + 舱室格**（`buildGuardCells`）。
  这正好是整个船体体积，不漏棱角；破掉的格子从快照移除，真实海水只允许出现在破口那一格。
- 清理也清 `guardCells`（船壳 + 舱室）：装配瞬间灌进船壳层的水也会被清掉。
- 新增 `refillVacated`：整数偏移变化时，把"旧拦截格 − 新拦截格"里仍是空气的格
  回填成海水，船尾不再留隧道。只在水下回填（8 点采样，水多于空气），
  停在哨站/洞穴干空气里不会凭空造水。
- `ShipState.replaceGeometry` / `ShipSimulation.seedWater`：破损重分舱时把旧的
  密闭舱水量和连海空腔水量按新几何容积重新分配，连续砸洞水位不再归零。
- `ShipSimulation.setBreachSize`：进水速率 × 破口格数（初始快照数 − 当前快照数），
  洞越大灌得越快（潜渊症 Gap 的简化）。
- 诊断日志：
  - 服务端：`[vs] 流体拦截：舱室 X 格 + 船体 Y 格 = Z 格`、
    `[vs] 连海空腔：N 格，bbox ...`、每 60 次同步一条 `[vs] 水面同步`；
  - 客户端：每 40 个包一条 `[water] 收到水面包`，每秒一条
    `[water] 画水面 ... delta/firstSurface`；找不到 ClientShip 时只警告一次。

### 验证（14:08，隔离自检）

```
[vs] 流体拦截：舱室 27 格 + 船体 99 格 = 125 格
阶段2 装配后 40 tick：水=0 空气=124 排水体=0 别的=1
判定：舱内水=0（外层船壳格水=0）→ 舱内流体拦截生效
阶段2 破口 40 tick 后：连海空腔 1 处，模拟进水 2800 单位，进水比例 100%
舱内实际水方块（应为 0，水面是客户端自绘）：0
```

- `build --offline` ✅、L1（19 用例）✅、L2 `All 3 required tests passed` ✅；
- 船壳层现在是真空气（不是真实海水），破口后的水只存在于模拟 + 客户端渲染。

### 仍需用户确认

- 客户端自绘水面是否真的出现在舱内（新 `[water]` 日志可定位：
  包到没到、ClientShip 找到没、delta/firstSurface 是多少）；
- 船尾回填在真实下沉/开船速度下的观感与性能；
- 旧存档里已装配的船不会自动认领（`VsPilot` 不持久化），这轮测试请用新世界。

## 10.53 船内水面诊断与最小修复（2026-11-02 18:30，纠正 §10.50–10.52 的若干推断）

> 施工单要求：先保留原差异基线、只加诊断、保持原水体行为；再用实际源码确定坐标与 QUADS
> 最小修复，单独可逆补丁；不得重写物理/服务端模拟，不得加 AABB 水体皮肤/排水方块。

### 10.53.1 上游来源（先查后改）

- **Forge 47.4.0**（`gradle.properties:forge_version=47.4.0`）：读本机实际源码 jar
  `.../forge/1.20.1-47.4.0_mapped_official_1.20.1/forge-1.20.1-47.4.0_mapped_official_1.20.1-sources.jar`：
  - `GameRenderer.renderLevel` 对 level 的 PoseStack 只做 `mulPose(Axis.ZP/XP/YP...)` 相机旋转，
    **没有** `translate(-camera)`（`GameRenderer.java:1119-1126`）。
  - `LevelRenderer.renderChunkLayer` 把同一个 PoseStack 交给
    `ForgeHooksClient.dispatchRenderStage`（`LevelRenderer.java:1559`）。
  - `RenderType.DEBUG_FILLED_BOX` = `TRIANGLE_STRIP`（`RenderType.java:141`）；
    `DEBUG_QUADS` = `QUADS + NO_CULL + TRANSLUCENT_TRANSPARENCY`（`RenderType.java:142`）。
  - `RenderType.end` → `setupRenderState()`（`RenderType.java:426-434`），因此 `endBatch` 前手动
    `disableCull/depthMask(false)` 会被 state shard 覆盖；`debugFilledBox` 默认
    `depthTest=LEQUAL`、`writeMask=COLOR_DEPTH_WRITE`。
- **VS2 2.4.11**（本机实际运行版本）：反编译 jar 的
  `org.valkyrienskies.mod.common.VSClientGameUtils.transformRenderWithShip`，字节码是
  `translate(-cam) * shipToWorld * translate(offset)`；父目录 `_vs2src` 的 HEAD 是 `a79995c`
  （`1.20.1/main`），**不是 2.4.11 tag**，所以以 jar 为准；`_vs2src` 同名源码
  `common/src/main/kotlin/org/valkyrienskies/mod/common/VSClientGameUtils.kt:46-66` 与 jar 一致。
- **网络**：经本地代理成功取到 Forge 1.20.x `LevelRenderer.java.patch`（HTTP 200，15929 字节），
  作为旁证；其余不依赖网络。

### 10.53.2 根因（两条独立，都有源码证据）

1. **坐标空间**：`AFTER_TRANSLUCENT_BLOCKS` 的 PoseStack 只有相机旋转，顶点必须是
   `world − camera`。旧 `ShipWaterRenderer` 直接提交装配原坐标 + delta（绝对世界坐标），
   相当于多平移了一次相机位置。VS2 自己渲染船体时也显式做 `translate(-cam)`。
2. **拓扑/剔除**：旧主水面用 `RenderType.debugFilledBox()`（TRIANGLE_STRIP）提交多个独立 quad；
   相邻 quad 会被连成错误三角形。该类型默认 CULL=开、写深度；满舱第一人称在水下往上看时
   水面是背面，会被剔除。旧代码在 `endBatch` 前 `disableCull()/depthMask(false)`，
   会被 `RenderType.setupRenderState` 覆盖，等于没生效。

### 10.53.3 实现

- `client/ShipWaterRenderer.java`
  - 诊断默认关闭：`-Dbarotrauma.waterDebug=true` / `BAROTRAUMA_WATER_DEBUG=1` / F9（`:67`、`:125`）。
  - `[water-diag]` 1 Hz（矩阵 5 s）：camera/eye/player/blockY/cameraEntity/partialTick、ship
    render/current/prev 完整变换、anchor/delta、水面盒、CPU/GPU 矩阵、顶点数、flush/stage/type
    （`:615-743`）。
  - 测试标记：视线前 2 m 三个分离 0.2 m 面；现有管线把三个面放进同一个 `debugFilledBox`
    （TRIANGLE_STRIP，会看到面之间的连接三角形），诊断管线是三个独立 QUADS——拓扑对照；
    同位置深度测试开/关对照 + 绝对世界坐标对照；独立 buffer，不污染主水路径（`:479-614`）；
    不新增世界方块。
  - 原行为保留：`drawWaterLegacy`（`:240-324`）；`-Dbarotrauma.waterFix=false` 回退。
  - 修复补丁：`drawWaterFixed`（`:326-457`）用 `debugQuads`（QUADS + NO_CULL + TRANSLUCENT，
    LEQUAL、写深度）和相机相对坐标；`waterToCamera` 用实际 `renderTransform` 绕装配锚点旋转，
    避免二次旋转（`:743-754`）。主水面改用**独立** `BufferBuilder`（`:347-349`），
    不再和 `mc.renderBuffers().bufferSource()` 里的声纳/其它 `debugQuads` 共用批；
    `endBatch` 外层显式 setIdentity + 0.99975586 缩放（`:415-427`），复刻
    `VIEW_OFFSET_Z_LAYERING`，降低与船壳共面的 z-fighting。
- `ship/ShipWaterStateBuilder.java:39-86`：水面高度按 Y 逐层实际格数累计，不再用 bbox 底面积；
  0/部分/满/超量 clamp。协议仍只有 bbox + surface，水平范围是近似，**不把 bbox 当最终真实水面**。
- 新增 `ShipWaterStateBuilderTest`：7 用例（0/部分跨层/满/超量/矩形回归/连海空腔）。

### 10.53.4 验证

```
./gradlew test --offline                  → exit 0，26 PASSED（原 19 + 新 7）
./gradlew build --offline                 → exit 0，BUILD SUCCESSFUL
./gradlew runGameTestServer --offline     → exit 0，All 3 required tests passed :)
run/.smoketest-vs + runServer --offline   → exit 0
  阶段2 装配后 40 tick：水=0 空气=124
  破口 40 tick：连海空腔 1 处，模拟进水 2800 单位，进水比例 100%
  舱内实际水方块：0；拆回后水=0
```

- 客户端只启动一次，日志进入主菜单加载阶段（`Auto-subscribing ...ShipWaterRenderer to FORGE`），
  **没有加载世界，未做视觉验证**；computer-use 技能标注 "Main agent only"，本会话是子代理，
  未使用 GUI 自动化。该次 Gradle 任务是被强制停止的，日志末尾 **BUILD FAILED / exit -1**，
  只能算启动证据，不能算客户端检查通过。启动日志里 VS2 自带的
  `MixinVibrationSystemTicker` 错误是 dev 环境既有，未归因本轮。
- 因此：**"水面已可见"仍未证实**，不能只凭 build/L1/L2 下结论。

### 10.53.5 被纠正的旧推断

- §10.52 的 673/125 guard 数字是不同船体并集，不能用来断言破口保护范围；本轮未改 guard。
- HUD 183 m vs 船 render -126.5 的 58 m 差不能证明船/水错位；HUD 读实体 Y，不是相机/渲染船位。
- 第一人称满舱看不到自由面可能是背面剔除，不等同于"没水"。
- "pose 平移≈0"不是充分证据；本轮同时记录 CPU/GPU 全矩阵和 shipToWorld vs delta 的参考点映射。


### 10.53.6 独立复核修正（2026-11-02 19:10）

只读复核发现并已修复以下有事实依据的问题：

1. **诊断 raw QUADS 双重旋转（真 bug）**：初版 `drawRawQuad` 既把事件 pose 烘进顶点，
   又在 modelview 里 `mulPoseMatrix(pose)`；`BufferUploader` 会使用 modelview，导致旋转两次。
   已改为顶点只存相机相对坐标、pose 只经 modelview 应用一次（`ShipWaterRenderer.java:566-590`）。
2. **主水面与声纳共用 debugQuads 批**：主水面原来用 `mc.renderBuffers().bufferSource()`，
   而 `SonarRenderer.java:46` 也用 `RenderType.debugQuads()`；`endBatch(debugQuads)` 可能提前
   flush 别人的 pending 顶点。已改为主水面独立 `BufferBuilder`（`:347-349`），诊断也有独立 buffer。
3. **debugQuads 缺少 VIEW_OFFSET_Z_LAYERING**：`debugFilledBox` 有该 shard，`debugQuads` 没有
   （`RenderType.java:141/142`）；主水面 `endBatch` 外层显式 setIdentity + 0.99975586 缩放
   （`ShipWaterRenderer.java:415-427`，对应 `RenderStateShard.java:162-171`），降低共面 z-fighting。
4. **客户端启动不是通过**：唯一一次 `runClient` 被强制停止，`build/_client_startup.log` 末尾是
   `BUILD FAILED ... exit -1`；它只到主菜单加载阶段，没有加载世界，也没有 `[water-diag]` 输出。
   最终渲染器（18:43 之后）没有客户端类加载/世界内验证。
5. **工作树不是“只显示本轮改动”**：复核时 `git status --porcelain | wc -l` = 101
   （35 M / 40 D / 26 ??）；`build/_baseline_20261102/` 是定向基线（5 文件 + 1 份旧客户端日志），
   不是整树快照。既有改动未被 reset/覆盖；本轮源码改动仍只有 3 个文件。

复核修正后的本轮必要检查：`./gradlew test --offline` → exit 0，26 PASSED；
`./gradlew build --offline` → exit 0，BUILD SUCCESSFUL。L2/smoke 未重跑（只改客户端）。
**画面与最终客户端代码仍未验收。**

## 10.54 跨世界初始潜艇不生成：静态物理船状态泄漏（2026-11-02 20:45）

用户现象：创建第一个世界后，后续新世界不再生成初始潜艇。

根因（日志 + 源码）：

- `run-client/logs/latest.log` 20:25:19 首世界装配初始潜艇 403 格；20:26:31 第二个世界登录时
  输出“初始潜艇已收成物理船，跳过重建”，紧接着出现“客户端找不到船 id=4”，随后仍收到旧
  `ship=4` 的水面包；20:27:11 第三个世界重复同一日志。
- `StarterSub.ensure()` 原先只要 `VsState.active()` 就返回 null；`VsState.active` 和 `VsPilot`
  都是进程级静态单例，没有 `ServerStoppedEvent` / `ServerStoppingEvent` 清理；`VsPilot.stillExists()`
  在查询异常时返回 true，旧世界船句柄不会自愈。

修复：

- `VsPilot.resetForServerLifecycle()`：纯内存清理 `ship/thruster/state/corePos/snapshot/guard/pending/lastLevel`
  等静态字段；最后 `VsState.deactivate()`。
- 空气海域改造后，`hull_plug` 方块注册、`HullPlug` 类、模型/blockstate 和全部调用已彻底删除。
- `DeepSeaEvents`：新增 `ServerStoppedEvent` 处理，并在 `ServerStartedEvent` 兜底再清一次；
  `resetShipRuntime()` 只在 VS2 可用时加载 `VsPilot`，没装 VS2 也不会触发类加载。
- `StarterSub.ensure()`：只有 `VsState.corePos()` 等于初始潜艇核心时才跳过；玩家先装了别的船时，
  初始潜艇仍会生成；新增“初始潜艇已建造”日志。

验证：

```
./gradlew test --offline                → 26 PASSED，BUILD SUCCESSFUL
./gradlew build --offline               → BUILD SUCCESSFUL
./gradlew runGameTestServer --offline   → All 3 required tests passed :)
run/.smoketest-vs + runServer --offline → BUILD SUCCESSFUL
  阶段2 装配后 40 tick：水=0 空气=124
  破口 40 tick：连海空腔 1 处，模拟进水 2800 单位，进水比例 100%
  舱内实际水方块：0；拆回后水=0
```

日志确认服务器启动/停止都会执行 `[Barotrauma] 已清理物理船静态状态`。

多世界客户端实测（2026-11-02 21:07–21:09，`run-client/logs/latest.log`）：

```
21:07:46 世界22 初始潜艇已建造：core={x=1,y=-71,z=20}
21:07:59 世界22 装配 403 格，ship=4
21:08:31 世界22 服务器停止 → 已清理物理船静态状态（服务器停止）
21:08:53 世界23 服务器启动 → 已清理物理船静态状态（服务器启动）
21:08:54 世界23 初始潜艇已建造：core={x=1,y=-71,z=20}
21:09:09 世界23 装配 403 格，ship=4；水面同步正常
```

世界 23 没有再出现“初始潜艇已收成物理船，跳过重建”。世界 22 在 21:08:19 有一次
“客户端找不到船 id=4”，但世界 23 同流程未复现；这是客户端 VS2 船同步的独立问题，
不是跨世界静态状态泄漏，后续若再出现单独查。

## 10.55 空气海域手感与 GUI 修复（2026-11-03）

用户连续实测反馈，逐条定位并修复：

1. **方块船舱内仍有水下滤镜**。
   根因不是坐标，是干燥区注册被覆盖：单人存档的客户端和集成服务端在同一个 JVM，
   `ClientSetup` 先注册 `ShipWaterRenderer::isInsideDryZone`，服务器启动加载 `VsPilot` 时
   又用 `OceanEnvironment.setDryZone(VsPilot::isInsideDryZone)` 覆盖了同一份静态字段；
   方块船还没装成刚体，`VsPilot` 判定不了舱内，滤镜就漏进舱里。
   修复：`OceanEnvironment` 拆成 `clientDryZone` / `serverDryZone`，按 `level.isClientSide()` 选；
   `ClientSetup` 用 `setClientDryZone`，`VsPilot` 静态块用 `setServerDryZone`。

2. **方块船不会进水**。
   `ShipWaterRenderer.isInsideDryZone` 原先对初始潜艇固定内腔无条件返回干燥，
   绕过了水面包里的水面高度。修复：固定内腔只负责范围判断，水面高度仍取覆盖初始潜艇的
   盒子；破壳后水面淹到眼睛 → 舱内进滤镜、服务端开始掉氧。
   `OceanEnvironment.isInsideBlockShipDryZone` 同样改为以水面包水面为准，只有没有盒子时才兜底干燥。
   另外方块船破壳现在会打 `[ship] 方块船船体破损，已重扫：舱室 …｜连海 …｜连海水 …`。

3. **站在干燥区往水里看没有滤镜**。
   原先雾/色调只看相机是否浸没。现在 `UnderwaterEffects` 增加视线采样：
   相机在干燥区时沿视线逐格找第一个“水”采样点，玻璃（`canOcclude()==false`）可穿过，
   船壳/岩层直接截断；找到后从该距离开始铺水下雾，室内保持清晰、窗外仍是蓝绿色。
   屏幕色调仍只在相机真正浸没时叠加。

4. **打开 GUI 后滤镜和浮力失效、直接下沉**。
   `UnderwaterMovement` 原先 `mc.screen != null` 直接 return，原版重力接管 → 玩家沉底；
   `UnderwaterEffects.onRenderGui` 同样在 GUI 打开时跳过色调。
   修复：GUI 打开时仍保持悬浮（垂直速度归 0，不受按键影响）；色调不再因 GUI 跳过。
   Forge `GameRenderer.render` 里 `gui.render`（`RenderGuiEvent.Pre`）在 `drawScreen` 之前，
   所以色调画在 GUI 底下，不会盖住界面。

5. **舱内跳跃顶点滤镜闪烁**。
   初始潜艇干燥区上界从 `-68` 改为 `-67.5`：舱内跳跃时眼睛最高约 `-68.1`，
   屋顶表面在 `-67`，用 `-67.5` 既能覆盖跳跃、又不会把贴着屋顶游泳的人误判成干燥。
   通用盒测试对“干燥舱室”也放宽一格顶格，进水舱室仍以水面为准。

验证：

```
./gradlew test --offline   → 26 PASSED（2026-11-03 13:11）
客户端实测（run-client/logs/latest.log）：
  方块船舱内 submerged=false，破壳进水后翻 true；
  打开 GUI 后不再下沉；物理船破口后 12 秒从 y=-69.5 沉到 y=-117.5。
用户确认：“修好了”（GUI 悬浮/滤镜）、“可以”（进水/看水）。
```
