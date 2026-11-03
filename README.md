# Barotrauma（Minecraft 1.20.1 / Forge）

《潜渊症 Barotrauma》风格的 Minecraft 模组。已实现深海空气海域、VS2 物理船、声纳、船内进水和水下生存；注册链路、资源与三层测试都在仓库内。

```
Minecraft 1.20.1  |  Forge 47.4.0  |  ForgeGradle 6.0.54  |  Gradle 8.1.1  |  JDK 17
```

## 环境状态

本机已配置并**实测通过**（`gradlew build` → `BUILD SUCCESSFUL`，产出 `build/libs/barotrauma-0.1.0.jar`）：

| 组件 | 配置 | 说明 |
| --- | --- | --- |
| JDK | 任意 JDK 17 发行版 | Gradle 8.1.1 自身必须跑在 JDK 17 上（`build.gradle` 的 toolchain 只负责编译）。仓库不写死路径：设 `JAVA_HOME`，或在用户级 `~/.gradle/gradle.properties` 写 `org.gradle.java.home` |
| Gradle | 8.1.1（wrapper 自带） | `gradlew` 会自动使用本地已缓存的发行版 |
| 依赖镜像 | 默认标准仓库 | 大陆网络可在用户级 `gradle.properties` 设 `barotrauma.aliyun=true`，把阿里云排到最前 |
| 代理 | 本机自定 | 放用户级 `~/.gradle/gradle.properties`；仓库不提交代理配置 |
| VS2 API | 构建时自动抽取 | `extractVs2Api` 从 `vs2_version` 对应的 VS2 jar 的 `META-INF/jars/api-*.jar` 抽出，升级 VS2 不用手工重抽 |
| Forge 缓存 | `~/.gradle/caches/forge_gradle` 已有 1.20.1-47.4.0 userdev + official 映射 + 资源 | 首次构建省掉大部分下载 |

> 仓库里的 `gradle.properties` 只放项目通用配置。JDK 路径、代理、镜像偏好属于本机环境，放在 `%USERPROFILE%\.gradle\gradle.properties`（Linux/macOS: `~/.gradle/gradle.properties`）。

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

> 用 IntelliJ IDEA 打开这个目录即可，Gradle JDK 选 17；也可以装 IntelliJ 的 **Minecraft Development** 插件补全映射/mixin 支持。

## 目录结构

```
barotrauma/
├─ build.gradle                  ForgeGradle 配置（仓库、run 配置、mods.toml 变量替换）
├─ settings.gradle               pluginManagement 仓库（默认标准仓库，可用 barotrauma.aliyun 切镜像）
├─ gradle.properties             模组元数据、版本号；不含本机 JDK/代理
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

一片 1000 米高的空气海域，1 格 = 1 米。海里没有真实水方块；水下观感由客户端雾/滤镜和 `UnderwaterSurvival` 的氧气/溺水规则表现。

| y | 内容 |
| --- | --- |
| 15 … 0 | 冰盖（packed ice），世界天花板 |
| -1 … -1000 | 空气海域，正好 1000 米 |
| -64 … -44 | **新手哨站**，首次进服的出生点 |
| -1001 … -1008 | 海床（深板岩/凝灰岩） |

- 地形：分层岩层 + 岩柱，空腔由噪声挖出；不再往海里放真实水。
- **主干道**：8 层（-130 到 -970，每 120 米一层）的隧道网络，半径约 12 格，节点按种子哈希分叉，层间有竖井，跨区块无缝。
- 哨站：气密盒子，内部是干燥区；破口/进水由船内标量水位模拟，不依赖 MC 流体。
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

1. **Gradle 必须用 JDK 17 启动**。Gradle 8.1.1 + ForgeGradle 6 不支持 JDK 21；仓库不写死路径，设 `JAVA_HOME` 或把 `org.gradle.java.home` 放进用户级 `~/.gradle/gradle.properties`。
2. **`gradle.properties` 的值里不要写中文**。它按 ISO-8859-1/GBK 解码，中文会变成乱码写进 `mods.toml`（已踩过一次，描述改成英文了）。中文只放 `assets/barotrauma/lang/zh_cn.json`。
3. **默认仓库是标准 Maven/Gradle Portal**。如果所在网络访问不了，在用户级 `gradle.properties` 设 `barotrauma.aliyun=true`，不要改仓库里的默认顺序。
4. **首次 `runClient` 需要联网下载原版资源**，走代理会慢；项目里已放宽 `HttpURLConnection` 超时。
5. **换模组 id 时**要同时改：`gradle.properties` 的 `mod_id`、Java 包名与 `BarotraumaMod.MOD_ID`、`assets/` 与 `data/` 下的命名空间目录、所有 json 里的 `barotrauma:` 前缀。
6. 改完 `build.gradle` / `settings.gradle` 记得刷新 Gradle 项目。
7. **VS2 API 由 `extractVs2Api` 自动抽取**。如果 IDE 报找不到 `org.valkyrienskies.core.api`，先跑一次 `gradlew extractVs2Api` 再刷新 Gradle；升级 `vs2_version` 后不需要手工替换 jar。

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
