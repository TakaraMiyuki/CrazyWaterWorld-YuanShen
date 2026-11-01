# Barotrauma（Minecraft 1.20.1 / Forge）

《潜渊症 Barotrauma》风格的 Minecraft 模组。当前是**可编译、可加载的工程骨架**：注册链路、创造模式分页、中英文本地化、方块/物品模型与贴图都已跑通，可以直接往里加玩法。

```
Minecraft 1.20.1  |  Forge 47.4.0  |  ForgeGradle 6.0.54  |  Gradle 8.1.1  |  JDK 17
```

## 环境状态

本机已配置并**实测通过**（`gradlew build` → `BUILD SUCCESSFUL`，产出 `build/libs/barotrauma-0.1.0.jar`）：

| 组件 | 配置 | 说明 |
| --- | --- | --- |
| JDK | `C:\Program Files\Java\jdk-17` (17.0.16) | 已在 `gradle.properties` 用 `org.gradle.java.home` 锁死。**系统 `JAVA_HOME` 指向 Zulu 21，不能直接用它跑这个工程** |
| Gradle | 8.1.1（wrapper 自带） | `gradlew` 会自动使用本地已缓存的发行版 |
| 依赖镜像 | 阿里云 `https://maven.aliyun.com/repository/public` | 本机访问 `repo.maven.apache.org` 与 `plugins.gradle.org` 会被 **403**，所以 `settings.gradle` 与 `build.gradle` 都把阿里云放在第一位 |
| 代理 | `127.0.0.1:7897`（Clash Verge） | 已写入 `gradle.properties` 的 `systemProp.http(s).proxyHost`；不用代理时注释掉那 5 行 |
| Forge 缓存 | `~/.gradle/caches/forge_gradle` 已有 1.20.1-47.4.0 userdev + official 映射 + 资源 | 首次构建省掉大部分下载 |

## 快速开始

```powershell
cd barotrauma-mc-mod

# 构建（产物：build/libs/barotrauma-0.1.0.jar）
.\gradlew.bat build

# 启动带模组的客户端（首次会下载约数百 MB 的原版资源）
.\gradlew.bat runClient

# 启动专用服务端测试（首次会生成 run/eula.txt，改成 eula=true 再跑）
.\gradlew.bat runServer

# 数据生成（输出到 src/generated/resources）
.\gradlew.bat runData

# 生成 IDEA 运行配置（Run/Debug 里会多出 runClient / runServer）
.\gradlew.bat genIntellijRuns
```

> 用 IntelliJ IDEA 打开这个目录即可，Gradle 会自动同步；也可以装 IntelliJ 的 **Minecraft Development** 插件补全映射/mixin 支持。

## 目录结构

```
barotrauma/
├─ build.gradle                  ForgeGradle 配置（仓库、run 配置、mods.toml 变量替换）
├─ settings.gradle               pluginManagement 仓库（阿里云 + Forge maven）
├─ gradle.properties             JDK 路径、代理、模组元数据、版本号
├─ tools/gen_textures.py         占位贴图生成脚本（Pillow，可重跑）
└─ src/main/
   ├─ java/com/opamb/barotrauma/
   │  ├─ BarotraumaMod.java      入口：拿 mod 事件总线并注册
   │  ├─ registry/ModItems.java  物品注册（氧气瓶、潜水刀、方块物品）
   │  ├─ registry/ModBlocks.java 方块注册（潜艇装甲板）
   │  ├─ registry/ModCreativeTabs.java  创造模式分页
   │  └─ item/OxygenTankItem.java / DivingKnifeItem.java
   └─ resources/
      ├─ META-INF/mods.toml      模组元数据（${} 由 build.gradle 替换）
      ├─ pack.mcmeta             pack_format 15（1.20.1）
      ├─ barotrauma.png          Mod 列表图标
      ├─ assets/barotrauma/      模型、方块状态、贴图、中英文本地化
      └─ data/                   战利品表、mineable/pickaxe 标签
```

## 已实现的示例内容

| 内容 | id | 行为 |
| --- | --- | --- |
| 氧气瓶 | `barotrauma:oxygen_tank` | 右键把氧气值补满，消耗 1 点耐久（耐久=剩余气量），氧气满时不消耗 |
| 潜水刀 | `barotrauma:diving_knife` | 铁剑档位近战武器，自带 tooltip |
| 潜艇装甲板 | `barotrauma:submarine_plating` | 高硬度方块，需要镐子才能掉落，带战利品表与标签 |
| 创造分页 | `itemGroup.barotrauma` | 上述内容统一收集 |

## 深海维度 `barotrauma:the_deep`

一根 1000 米高的水柱，1 格 = 1 米。

| y | 内容 |
| --- | --- |
| 15 … 0 | 冰盖（packed ice），世界天花板 |
| -1 … -1000 | 水柱，正好 1000 米 |
| -150 | **新手哨站**，首次进服的出生点 |
| -1001 … -1008 | 海床（深板岩/凝灰岩） |

- 地形：**整根都是实心岩**，隧道和洞窟是从岩里挖出来的水腔——四面岩壁、不漏光，走进去才有《潜渊症》的压迫感。
  - 隧道网：两个三维噪声的等值面相交处，直径约 9 格，天然三维连通
  - 洞窟：更低频的噪声挖出 16 格左右的空腔
  - 主干道：见下
  - 水占体积约 15%–20%
- **主干道**：8 层（-130 到 -970，每 120 米一层）的长隧道网络，17 宽 × 25 高，节点天然分叉，18% 的节点有竖井通往下一层。坐标全部由种子哈希，跨区块无缝。
- 哨站：40×21×24 的气密盒子，建在外面的椭球水池里，内部三间房、天花板灯、一扇橡木门。**门一开海水就灌进来**——MC 的水会流动，船体破损/进水这套机制是白送的。
- 深度 HUD：在深海维度左上角显示 `深度 N m`。
- **水下粒子已关闭**：深海维度不再生成 `underwater` / `bubble` 粒子（`client/DeepSeaParticles.java`）。想恢复就把那两个 `registerSpriteSet` 删掉。

### 进出与调试

```
/barotrauma deep      # 传送到哨站
/barotrauma surface   # 回主世界
/barotrauma depth     # 报当前深度
```

### 无头自检（改完生成器先跑这个）

```powershell
# 在 run/ 下放一个标记文件，服务端会生成哨站区块、统计地形、打印 ASCII 切片，然后自动关服
ni run\.smoketest -Force
.\gradlew.bat runServer
```

⚠️ **改了生成器必须删 `run/world`**，否则区块从旧存档读，改动看不到效果（这个坑已经踩过）。

## 改配置时注意

1. **不要用 JDK 21 跑**。Gradle 8.1.1 + ForgeGradle 6 只支持 JDK 17；换机器时改 `gradle.properties` 里的 `org.gradle.java.home`。
2. **`gradle.properties` 的值里不要写中文**。它按 ISO-8859-1/GBK 解码，中文会变成乱码写进 `mods.toml`（已踩过一次，描述改成英文了）。中文只放 `assets/barotrauma/lang/zh_cn.json`。
3. **`repo.maven.apache.org` / `plugins.gradle.org` 在本机返回 403**。加新依赖时，仓库顺序保持阿里云在前；别把 `mavenCentral()` 放回去。
4. **首次 `runClient` 需要联网下载原版资源**，走代理会慢；`gradle.properties` 里已放宽 `HttpURLConnection` 超时。
5. **换模组 id 时**要同时改：`gradle.properties` 的 `mod_id`、Java 包名与 `BarotraumaMod.MOD_ID`、`assets/` 与 `data/` 下的命名空间目录、所有 json 里的 `barotrauma:` 前缀。
6. 改完 `build.gradle` / `settings.gradle` 记得刷新 Gradle 项目。

## 下一步可以做的（按《潜渊症》还原度排序）

- **氧气/窒息系统**：水下氧气消耗加速、氧气瓶灌装台（BlockEntity）、氧气面罩
- **压力与水密**：潜艇船体方块组成封闭空间检测，进水、排水泵、破损泄漏
- **潜艇部件**：反应堆、引擎、声呐、气闸舱门、压载舱
- **威胁**：深渊生物、声呐诱饵、辐射/毒素
- **多人协作**：船员权限、医疗系统、死亡与复活

## 参考

- Forge 官方文档（1.20.1）：<https://docs.minecraftforge.net/en/1.20.1/>
- ForgeGradle 6 文档：<https://docs.minecraftforge.net/en/fg-6.x/>
- 本工程由 DeepSeek Harness 的 `minecraft-dev` 插件辅助搭建（含 `minecraft-forge-mod` 技能）
