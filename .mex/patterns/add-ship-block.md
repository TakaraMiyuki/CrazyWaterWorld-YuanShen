---
name: add-ship-block
description: Add a new block that belongs to a submarine hull, including registration, resources, ship-block classification, and verification.
triggers:
  - "new ship block"
  - "add hull block"
  - "submarine plating variant"
  - "block not scanned as ship"
edges:
  - target: context/conventions.md
    condition: when following the project's registration and naming conventions
  - target: context/architecture.md
    condition: when the block affects ship geometry or compartments
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTV7DM94TP6N5YFZHYP6FC
  type: pattern
  status: promoted
  revision: 3
  title: add-ship-block
  relations:
    - type: related_to
      target: mx_01M6FTBTB77P4PT8TV3HT78NGZ
      note: when following the project's registration and naming conventions
    - type: related_to
      target: mx_01M6FTBTA32ESJGHB433FWZZZ3
      note: when the block affects ship geometry or compartments
---

# Add a Ship Block

## Context

船体方块由 `ModShipBlocks` 注册，`ModShipBlocks.isShipBlock(BlockState)` 决定扫描和分舱时是否把它算作船体。`ShipGeometry` 只认这个方法，不维护单独的方块清单；因此新增船体方块时，**必须同时扩展 `isShipBlock`**，否则船会缺一块、舱室可能漏气。

非船体装饰方块走 `ModBlocks`；两者的资源路径规则相同，但只有船体方块会参与 VS2 装配和破口对账。

## Steps

1. 在 `src/main/java/com/opamb/barotrauma/registry/ModShipBlocks.java` 注册方块（`BLOCKS.register("my_block", ...)`），并在 `isShipBlock` 中加入它。
2. 在 `src/main/resources/assets/barotrauma/` 下补对应资源（以 `submarine_plating` 为例）：
   - `blockstates/submarine_plating.json`
   - `models/block/submarine_plating.json`（通常 `minecraft:block/cube_all`）
   - `textures/block/submarine_plating.png`
3. 在 `assets/barotrauma/lang/en_us.json` 和 `zh_cn.json` 加 `block.barotrauma.<your_id>`。
4. 如需掉落，补 `data/barotrauma/loot_tables/blocks/submarine_plating.json`；如需镐子采集，补 `data/minecraft/tags/blocks/mineable/pickaxe.json`。
5. 如果方块需要交互，再加 `BlockEntity` / `Menu`，并在 `ModBlockEntities` / `ModMenus` 注册；参考 `SonarBlock` + `SonarBlockEntity`。
6. 运行 `./gradlew build --offline` 和 `./gradlew test --offline`，再进游戏用 `/barotrauma ship vs launch` 装配一艘包含该方块的船验证扫描。

## Gotchas

- **只注册不加 `isShipBlock` 是最常见的错误**：方块在世界里存在，但扫描不到，表现为船体缺角或舱室漏气。
- 船体方块应使用完整不透明碰撞箱；玻璃类方块参考 `ModShipBlocks.HULL_GLASS`，不要用 `noOcclusion` 以外的方式破坏分舱。
- `sonar_proxy` 是特殊代理方块，不是普通船体方块；不要把它加入 `isShipBlock`。
- 资源 id、模型名、贴图名、lang key 必须完全一致；Forge 不会为拼写错误报错，只会显示紫黑格。
- 旧存档不会自动获得新方块；测试用新世界。

## Verify

- [ ] `ModShipBlocks.isShipBlock()` 覆盖新方块
- [ ] blockstate / model / texture / lang 四件套齐全
- [ ] `./gradlew build --offline` 通过
- [ ] `./gradlew test --offline` 通过
- [ ] 新世界里装配的船能扫描到该方块，舱室数量与预期一致

## Debug

方块没被识别时，先查 `ModShipBlocks.isShipBlock`；船体缺块但方块存在时，查 `ShipGeometry` 的连通分量和 `ShipState.collectBlocks()` 的包围盒扫描。客户端紫黑格查资源路径和 `pack.mcmeta` 的 `pack_format`。
