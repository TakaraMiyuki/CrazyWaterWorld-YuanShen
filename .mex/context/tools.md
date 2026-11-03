---
name: tools
description: Development-only scripts in tools/ — placeholder texture generation, GameTest structure templates, and the AsmDump metadata helper. Load when changing generated assets or test fixtures.
triggers:
  - "texture generation"
  - "gen_textures"
  - "gametest structure"
  - "AsmDump"
  - "placeholder asset"
edges:
  - target: context/setup.md
    condition: when running the scripts or regenerating assets for a fresh checkout
  - target: context/conventions.md
    condition: when adding a new generated asset or test fixture
grounds_to: []
last_updated: 2026-11-03
mex:
  id: mx_01M6FTMGAC6EFK2JXPVN38G81K
  type: component
  status: promoted
  revision: 3
  title: tools
  relations:
    - type: related_to
      target: mx_01M6FTBTBNY5R38MW70TVEXZH9
      note: when running the scripts or regenerating assets for a fresh checkout
    - type: related_to
      target: mx_01M6FTBTB77P4PT8TV3HT78NGZ
      note: when adding a new generated asset or test fixture
---

# Tools

`tools/` 只放开发期脚本，不进入模组运行时。三个入口互相独立：贴图生成、GameTest 结构模板、程序集元数据导出。

## Overview

- `gen_textures.py` 用 Pillow 以几何绘制方式生成全部 16x16 方块/物品贴图和 128x128 模组图标；换正式美术时直接替换同名 PNG。
- `gen_gametest_structure.py` 生成全空气的 `.snbt` 结构模板，必须写到 `run/gameteststructures/`，不能放 mod 资源。
- `AsmDump/Program.cs` 是 .NET 控制台工具，用 `System.Reflection.Metadata` 读 DLL 的 PE 元数据，按类型名打印字段和方法签名，用于对照混淆/映射后的类。

## Key Behaviors

- 贴图脚本的随机噪声使用固定种子 `47`，重复运行结果稳定。
- GameTest 模板的 `DataVersion` 固定为 `3465`（Minecraft 1.20.1），模板调色板只有 `minecraft:air`。
- AsmDump 跳过 `get_` / `set_` 属性访问器，并按方法名去重，避免重载刷屏。

<!-- mex:entity
id: mx_01M6FTMG8MFPTYK2488DPZ294C
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 贴图生成输出在哪
    - 换掉占位贴图
    - 重新生成全部方块贴图
    - 模组图标也是脚本画的
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:5a6cb79eebe5aec410f2713c1042d86e
    fingerprint: mh2:64:wwIMWy4fBhzI9wB4a9QANhf9AswFbAHL0vAEPiN-AM4iSgGCUnADC6s3BME68QCr5c4ETA_6ARtj7wCxr4wCZRcRACz60gCACPgCvE-bBrLrLAJGiB0Dx2AnAQk30wWDE90EsHftBRZ6jQeDjqMC2qDrDZOuqAAZISIHJdv1CL61TQBDbkcBZRWqAy-ekwwjtU0DCqRGA5fsjQMvzw8RQnXrBscq9gfDI2QAxxH7AVn1TABHuNwDW5EbDDeMYwb4FrgB4mJhA7nCDgGr8FAIqU-_AR59NgbDkmkEdimUBwq9sAM68x0BQ36BAei2XwLm67YDnECnAASF7wHss7kAsvBNAQhmdW5jdGlvbhAADeNvNWKOLnTxkut-hIX71QAg2uVLhWNczdx1LxlAp4WYACd8X1ibHBjtOJ89acHB_OkAZCxSGZq1Jv8qYex0cJPiYwBt7SD6mcsr_F9cOUPieTq7AG-sKrsb1UlFJPKl-w1P6FcAdX0bJQjYR3jJ6_xVbFp30AB7L68GZp-0oiLQQcyeo0-OAH6bcxBmmZMqCDQh7OHiL0gAgi1WRp6HLSHZcJIwrC2ppQCSDbWxWz7LdwDmdHFQAMT4AKNw8fM9z0jYxr-g9Y5kwGgAqxxK0JgDHVcBoi1COFuZXwDHQvpyGzOEtOtLYDESkf8pAPVXcbV_BwA-0NOL8Qu0PWcA9hcTZjGXqR3DMOx937x2JQ
    bodyHash: 6201a9863a27b224d2a41003ef7bbe5683f34b107a0aa1a0cc1abbe81e2d462f
-->
## 贴图脚本的输出清单

`main()` 创建 `item/` 和 `block/` 目录，并写出 15 张 16x16 方块/物品贴图，最后把 128x128 模组图标写到 `src/main/resources/barotrauma.png`；换正式美术时直接替换同名 PNG 即可。

<!-- mex:entity
id: mx_01M6FTMGCAF18MCBPWSMWTQMMQ
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 贴图为什么是 16x16
    - 透明背景从哪来
    - 画布尺寸写死在哪
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:bad2103a825525a97003abd88c06493c
    fingerprint: mh2:64:IhR9yX4SlK6NBy2bzANnl5AHaNKaBbyjkg01_NEIueVXBwb3SAMLqzcMidleAKvlzgKEkM4fDSyJICgsBgJYmiMHETztD8N2GQBeH6gDuMYNEWoHWwnyIjgF3Bd-FKCFISV9-78LPnZgFMTXphKoANwFY6CkAppWVwHkpb4H1OAJAENuRwefoqoFlGGeCGdB4wyOHpgDl-yNAIGyjhKXn6YRIeY5B9yMeAVdj3EGB6LqCbhVmAezOl4PsokrDuvpqQfizT4ML5odCdk1aRN_DGUHIAGsC1h4sQMl6KIlXMO5AzrzHQ4MpEwNS3mnBF9cLxnKTpQKtmN0EaO7YAX6bhcBCGZ1bmN0aW9uDQAN4281Yo4udPGS636EhfvVACDa5UuFY1zN3HUvGUCnhZgAJ3xfWJscGO04nz1pwcH86QBkLFIZmrUm_yph7HRwk-JjAG-sKrsb1UlFJPKl-w1P6FcAdX0bJQjYR3jJ6_xVbFp30AB-m3MQZpmTKgg0Iezh4i9IAIItVkaehy0h2XCSMKwtqaUAkg21sVs-y3cA5nRxUADE-ACjcPHzPc9I2Ma_oPWOZMBoAKscStCYAx1XAaItQjhbmV8A9VdxtX8HAD7Q04vxC7Q9ZwD2FxNmMZepHcMw7H3fvHYl
    bodyHash: 812d9f1fd12359d9109104e1edaba00250ac4ff69f577c5e05fba70013880bde
-->
## 贴图画布尺寸与透明背景

`new_image()` 返回 16x16、RGBA 模式、初始全透明的 `Image`；所有方块/物品贴图都从这个画布开始。

<!-- mex:entity
id: mx_01M6FTMGDA7JA9Q6GZG6KG1WKG
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 像素画到边界外会怎样
    - 贴图越界不报错
    - 边缘像素没画出来
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:2ae5d70c290413e9ebd0816ce167c346
    fingerprint: mh2:64:PAJArXwOon4UBOleHws62QgG4FqJAopw7ABlQloDyglRBY045hvs1i4BEUxwB3R3KQRMD_oDJ1h5AElnPQgx9A0HETztC5O7_xAdgRAE0xoeALF9Nwg2UEkAG5psBbqo5AGLeHkHm5GlCdDCdQSIFSkDGHbxAEzrjR5keAoDX2CoAENuRwTlKMQDL56TCEQ1GQaZY9oDl-yNA2UvmQfhuUEGxyr2DgOIsAAMnMESkaovAFWIrQF2JxkHRN2yDVCCXwdTc3gBXhdVCP_b8BN_DGUD8y-oCuRvnwD-8VsD1axNBUwaZwH8sAYH50dcAKUX1wLk_FIMepaKB6MyrACy8E0BCGZ1bmN0aW9uAgAN4281Yo4udPGS636EhfvVAIItVkaehy0h2XCSMKwtqaU
    bodyHash: a35bff429e3c49cbf72228415c3fb90cbbd7a7cc7ab9c5582efd1fd144e76e3a
-->
## put() 越界坐标会被静默丢弃

`put()` 只在 `0 <= x < 16` 且 `0 <= y < 16` 时调用 `draw.point()`；越界坐标不报错也不绘制，所以贴图函数里的边缘像素可以越界写而不会崩溃。

<!-- mex:entity
id: mx_01M6FTMGECFHQH230HAQA8KG4V
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 氧气瓶图标怎么画
    - 橙色警示环位置
    - 钢瓶贴图构图
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:0de36f35628e2e74f192eb7e8485fbd5
    fingerprint: mh2:64:uQIED2fpBmQPrQEcW0IHkpAvAKKQQQXWJtgAZUJaA3gb4Aaz_1MDC6s3AAX8NwNRXAYChJDOAmA-igBJZz0Bpv1yBEV_EABzStIAXh-oAe6jlgJGiB0HAr_aABuabACV-t8W3dkIAnAVHwh33WILEKKpDqxzgQAZISIB5KW-Bzd7dABDbkcIXmv8BUpwZwGOAqUDnYvOA5fsjQCBso4NRbNaBZj7GgLj2dYAxxH7AJyqYgm4VZgHszpeB0TdsgJ9JbYA4VcMBzCLoAHPVwcHcc-8AR59NgjHvWIDix2lBC9MDgb9mNULtuj8DUt5pwYdm2oD-mcMBKFMjAL6rNIAsvBNAQhmdW5jdGlvbgQAEM-7Av0_PXfi4K3cn3ApaAAq5dcMKQQT6evQgWzhZ8NGAFpst57r5a7EEPJxPBBC2G4AutIQOoJVJalwA6vYjAZJPA
    bodyHash: 07ed9f0cd3eccf3ecab77405f1a161b2017c45a58105579cbd10d9c65c7c9cc8
-->
## 氧气瓶贴图的构图

`oxygen_tank()` 在 16x16 画布上画竖立钢瓶：瓶身占 x=5..10、y=3..14，橙色警示环在 y=6..7，黄铜阀门在顶部 y=0..1。

<!-- mex:entity
id: mx_01M6FTMGFF8M5X76F0BTKHF8S0
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 潜水刀图标怎么画
    - 刀刃对角线方向
    - 握柄在左下角
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:822d56469e872d21d9709230ac2da9a5
    fingerprint: mh2:64:jwIED2fpBmQPrQEcW0IHkpAvAKKQQQXWJtgAZUJaAILzvAaz_1MDC6s3AGrXJgNRXAYChJDOAmA-igBJZz0AfK3BBEV_EABzStIAXh-oAe6jlgArzBwJ8iI4ABuabACV-t8IGUIvAXvEBAh33WIBsBIiBV0rzgAZISIB5KW-Ao_rLwBDbkcJG2BYBUpwZwDX8yADnYvOA5fsjQCBso4DYd7bAEw7zwLj2dYAxxH7AJyqYgm4VZgHszpeBBNPZQJ9JbYA4VcMBgo-PAHPVwcCAkCVAR59NgB1baADJeiiBC9MDgIr11gAbPM7CD9JWgUW-XsCfsv6BKFMjAVbn3UAsvBNAQhmdW5jdGlvbgQAEM-7Av0_PXfi4K3cn3ApaAAq5dcMKQQT6evQgWzhZ8NGAFpst57r5a7EEPJxPBBC2G4AutIQOoJVJalwA6vYjAZJPA
    bodyHash: c0a06cb686e29391c912755822d397270cdebb1b02cbccd9c2e58557e2c0f075
-->
## 潜水刀贴图的对角构图

`diving_knife()` 从 (6,10) 到 (12,2) 逐像素画刀刃，护手在 (4,10)..(6,11)，握柄在左下 (1,12)..(4,15)，刀尖在 (12,2)。

<!-- mex:entity
id: mx_01M6FTMGG3C0E1CCS4K79PG1FG
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 装甲贴图每次生成不一样吗
    - 固定种子 47
    - 焊接线和铆钉位置
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:c742fa721b3384b4eb4b60311291ff29
    fingerprint: mh2:64:hgMCgdIABmQPrQTpXh8DD5DiAORWMASkOfkGjr6LAILzvAaz_1MDC6s3ARFMcACr5c4ChJDOAmA-igFnYLgAfK3BBEV_EABzStIAXh-oAWVMzwArzBwFBnsGABuabACV-t8FzvowAXvEBAHOy5kDEinqAl9OqgAZISIB5KW-AqjyfABDbkcCIJbAAIrloADX8yAJDuejAugLhQCBso4DYd7bAEw7zwBYL7kBJyx3AJyqYgBViK0FE7QJBBNPZQJ9JbYFu0buBUN5nAHPVwcHcc-8Afz5dQB1baADJeiiCQfcXAIBn4QA7dm0B-dHXAClF9cCnRaaAXsehACi_QMAsvBNAQhmdW5jdGlvbgEAWmy3nuvlrsQQ8nE8EELYbg
    bodyHash: 5ed4001d21bb463eea06e8398ad2437ec075491f1ae96c0b1841fcf88ff61da3
-->
## 潜艇装甲贴图使用固定种子噪声

`submarine_plating()` 用 `random.Random(47)` 生成噪声，因此重复运行脚本得到相同像素；焊接线在 y=7/y=8，四角铆钉在 (2,2)、(13,2)、(2,13)、(13,13)。

<!-- mex:entity
id: mx_01M6FTMGH25JS5CRPWVYDZ5HS9
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 模组列表图标怎么来的
    - 128x128 图标
    - 潜艇剪影是脚本画的
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:6ded20fa99cb2bfc5f5c3943e2793abb
    fingerprint: mh2:64:vAMBFNvHBmQPrQHz4acCXmUaAORWMARLwJAAZUJaA8oJUQNljRkAU7PCARFMcACr5c4ChJDOAmA-igBJZz0AfK3BAl7nAQBzStIAXh-oAWVMzwArzBwFkVVHABuabACV-t8FzvowAXvEBAISX0cAwBptAV5pXAAVlAMB5KW-Bzd7dABDbkcB79V3AIrloAdu-gABnxoTAKXajQCBso4DYd7bAEw7zwb5D_sBJyx3AJyqYgBViK0AsqI_BBNPZQJ9JbYF47JBA4fT7wHPVwcA6OuxAlJcdAhVmtEDJeiiBC9MDgIBn4QG-TZSBepyTQClF9cBe-TXAXsehACi_QMAsvBNAQhmdW5jdGlvbgEAWmy3nuvlrsQQ8nE8EELYbg
    bodyHash: 536cefb857e208dcece6530206c0d5caaf5f2087d46885e6a0fcd339468c0bd9
-->
## 模组图标是 128x128 的程序化潜艇剪影

`mod_logo()` 固定 `size = 128`，先画从上到下变亮的深海渐变，再画舷窗、指挥塔、潜望镜、尾舵和气泡；它不读取任何外部美术资源。

<!-- mex:entity
id: mx_01M6FTMGHXNMSW7S3PC3164JTF
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 面板外框在哪画
    - 多个图标共用的金属边框
    - 舷窗和舱门底部一样吗
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:0642c0e43f2b212e6f48caf30a245805
    fingerprint: mh2:64:Wgj3pPIGksECBOleHws62QgA5FYwE04QQQkGZvUDeBvgCiWaOAYwmoYBEUxwA1FcBgRMD_oDBAUwBPLZ2QB8rcEHETztAIAI-ABeH6gB7qOWACvMHAFDIacAG5psAJX63wgZQi8Be8QECHfdYgSIFSkFXSvOAEzrjQK8jM0H1OAJAENuRwYh-wwDL56TCGdB4waZY9oDl-yNAaxc0QNh3tsATDvPDF_sNgDHEfsA8vNbAFWIrQezOl4Brv4bBZ4SEQYPd9UHMIugAc9XBwipT78BHn02CQGmyAMl6KIEtFRiAivXWBZiBaMH50dcAKUX1wLk_FIKtmN0Avqs0gCy8E0BCGZ1bmN0aW9uBQAQz7sC_T89d-LgrdyfcCloAG-sKrsb1UlFJPKl-w1P6FcAdX0bJQjYR3jJ6_xVbFp30AB-m3MQZpmTKgg0Iezh4i9IAKNw8fM9z0jYxr-g9Y5kwGg
    bodyHash: ed977b17440d22f3f4be81ef5f117d159e6e8e051ac513dbabe066904bdfa836
  - file: tools/gen_textures.py
    node: function:7e9b73106699932a083421ece1e22f48
    fingerprint: mh2:64:gwEED2fpCEiP4gJuZGoHkpAvAKKQQQXWJtgGjr6LAILzvAW-i8UDC6s3AAX8NwNRXAYChJDOAwQFMAGwEbIAfK3BBxE87QBzStIAXh-oA7jGDQNwujEHAr_aAeK6JACV-t8JCeJIAwC27Ah33WIDOaScFLL91QAZISIB5KW-Ao_rLwBDbkcJi8kDALorxghnQeMMjh6YA5fsjQCBso4YPVwPBscq9gLj2dYAxxH7AJyqYgBViK0CLBe_Eu_mKgJ9JbYGgIZKBzCLoAHPVwcIKqMFAR59NgB1baADix2lBC9MDgb9mNULtuj8B-dHXAClF9cBe-TXBKFMjAhlEMMAsvBNAQhmdW5jdGlvbgQABkLA5D8rIS5vSMrzCiRYBQAQz7sC_T89d-LgrdyfcCloAFpst57r5a7EEPJxPBBC2G4AutIQOoJVJalwA6vYjAZJPA
    bodyHash: 0fe29da9db5a6a3538b553352a6978b6f0742b524610ddc70148a5d0cb6e704f
  - file: tools/gen_textures.py
    node: function:6fac2abb1bd5494524f2a5fb0d4fe857
    fingerprint: mh2:64:kwEED2fpCHbZ7gctm8wHkpAvAKKQQQXWJtgAZUJaA3gb4Aaz_1MDC6s3AAX8NwNRXAYChJDOAydYeQBJZz0Bpv1yBaz_0wBzStIAXh-oAe6jlgJGiB0HAr_aABuabACV-t8W3dkIAwC27Ah33WIMQr5SDqxzgQAZISIB5KW-Bzd7dABDbkcIXmv8BUpwZwhnQeMDnYvOA5fsjQCBso4LLSUKFfSUVwLj2dYAxxH7AJyqYgm4VZgHszpeB0TdsgJ9JbYGgIZKBzCLoAHPVwcCAkCVAR59NgkBpsgDix2lBC9MDgb9mNULtuj8DUt5pwYdm2oD-mcMBKFMjAL6rNIAsvBNAQhmdW5jdGlvbgQABkLA5D8rIS5vSMrzCiRYBQAQz7sC_T89d-LgrdyfcCloAFpst57r5a7EEPJxPBBC2G4AutIQOoJVJalwA6vYjAZJPA
    bodyHash: 1c8e691a58b8e02e7db48c56cfad7e3abdda79600e226f1c38a6dab10542af73
-->
## _panel() 是所有面板类贴图的共用外框

`_panel()` 先画 16x16 的 `STEEL_DARK` 底，再画内缩 1 像素的 `STEEL` 面板和四角高光；`hull_glass()`、`hatch()`、`airlock_door()`、`ballast_tank()` 都复用它。

<!-- mex:entity
id: mx_01M6FTMGJZPW8SJ6Y97M78F5HN
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 辐射标志怎么画
    - 核反应堆图标构图
    - 三叶标志位置
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_textures.py
    node: function:7860169496297224e24bf1282d1947cc
    fingerprint: mh2:64:mwECgdIABTdRzwHz4acDD5DiA4514wSkOfkAZUJaAbQiGAWZZ1cGMJqGA56EGwJlYCYETA_6BPqOZABJZz0Bpv1yAUzlHwBzStIAXh-oAe6jlgKewjEAjDIIAVTMhgCV-t8FzvowBdxffAISX0cDOaScBGixwAFHXekCvIzNBzd7dABDbkcB79V3BUpwZwdu-gAGmWPaA5fsjQGgWdkH4blBA5rf4QAZDycADJzBAKYeyQBViK0B5OvBB0TdsgZAoDkF47JBAV4XVQHPVwcCAkCVBJIrBAINl7MDix2lBLRUYgb9mNUB_LAGB-dHXAqFKOwC6GwEAXsehBBb3f4AsvBNAQhmdW5jdGlvbgEAkg21sVs-y3cA5nRxUADE-A
    bodyHash: d693183082ac86df761cf98015320db61304126fb57267cd377be595edcaf7b3
  - file: tools/gen_textures.py
    node: function:920db5b15b3ecb7700e674715000c4f8
    fingerprint: mh2:64:nAEED2fpD2bu5gNXigQHkpAvAKKQQQXWJtgALxSOA8oJUQIqkq0DC6s3AAX8NwNRXAYChJDOAydYeQGwEbIBpv1yBxE87QBzStIAXh-oA7jGDQNwujEHAr_aAC3saQCV-t8EQUIoB_IdTQDvjG4DOaScF-3feQAZISIB5KW-Bzd7dABDbkcQM7OVAy-ekwhnQeMMjh6YA5fsjQCBso4OfLnZBscq9gLj2dYAxxH7Bgei6gBViK0BNXyZF8LXpgMhoYwGgIZKBzCLoAHPVwcEUXNfAR59NgjHvWIDix2lBC9MDgb9mNULtuj8B-dHXAYdm2oD-mcMBKFMjAT7OXIAsvBNAQhmdW5jdGlvbgQAEM-7Av0_PXfi4K3cn3ApaABabLee6-WuxBDycTwQQthuAHhgFpSWKXIk4kvxKC0ZR8wAutIQOoJVJalwA6vYjAZJPA
    bodyHash: 48d84ec5bb4128f5991d7f5e33e66bb8227e992a66f5cfcb5839c85244613b18
-->
## 核反应堆图标复用三叶辐射标志

`_trefoil()` 在指定圆心画深色圆底和三个 60° 扇区；`nuclear_reactor()` 调用它把标志放在 (7.5, 8.0)、半径 4.2。

<!-- mex:entity
id: mx_01M6FTMGM96ZC5MZ04XC5Q9C2F
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - GameTest 模板放哪
    - 结构文件找不到
    - 模板为什么不能放 resources
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_gametest_structure.py
    node: function:2d26265ee1c84c6dd8a803ccbf81fac9
    fingerprint: mh2:64:bg0C6LQKSG90AofMCQA2F_0CFBJzAcvS8ABlQloF4cTfBRePGQCHHD8ABfw3AKvlzgKEkM4DJ1h5AElnPQaGD0MC4aa3AIAI-AW3yNcQeDYtACvMHADLZn0AG5psAJX63wXO-jAFFnqNA4m7AgD9frgFXSvOAUdd6QDwYnEAcnjwAENuRwI1w14F5kuaAY4CpQCVV7gA0S07AqJD1wPYWXQBxL-6B4XQagDHEfsBWfVMCVkGtgK00IUEE09lBvgWuAj7g1oCio18AavwUAdGIXwBHn02A8tWfAfhJ2YEL0wOAzrzHQGCsx8Ge3FMAubrtgRbuF0F3dddAeyzuQCy8E0BCGZ1bmN0aW9uAQB4q00bJeP3GMMxy4WQyEE7
    bodyHash: f5541b657d9461ac884d9e87bb078ab05a9b7db23d5dbeb861d333f7203a0079
-->
## GameTest 模板必须写到 run/gameteststructures

`gen_gametest_structure.py` 的 `main()` 把 `.snbt` 写到脚本上级目录的 `run/gameteststructures/`，不是 mod 资源；因为 1.20.1 的 `StructureUtils.getStructureTemplate` 在资源查找失败后回落到运行目录的文件系统路径。

<!-- mex:entity
id: mx_01M6FTMGNHK4YMZH0FETVQG9A1
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - 空模板 snbt 长什么样
    - DataVersion 3465
    - GameTest 模板为什么全空气
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_gametest_structure.py
    node: function:78ab4d1b25e3f718c331cb8590c8413b
    fingerprint: mh2:64:Ig0C6LQFN1HPGixafQNnl5AHaNKaEwzIHwBlQloGKnzfBwb3SBHcu3cAo69VAKvlzgKEkM4DJ1h5AElnPQwpMG0HETztEFmx-REANosE0xoeIoLFtQg2UEkAG5psEQ5eDAir1lkIqVD6A4m7AgMSKeoHv3UyCPBiRgHkpb4IvrVNDBXlxwiaBpAJj6_qCZ83kAYWE9YLwWfuA2UvmQfhuUED55V6FrU_dQAMnMEAd9yBJmquvAezOl4HRN2yHOOQNRFpKIMBXhdVCP_b8AtJPB4X_7bzDGuP3gUWhAUEtFRiDjJpHQaCPoAH4v3OEo5PIAgeztsIOYB9EFvd_gCy8E0BCGZ1bmN0aW9uAQAtJiZe4chMbdioA8y_gfrJ
    bodyHash: 03f698581ac55e45679ec2d4f0c69fac782dd26c3ab2677829182f8847999bee
-->
## 空结构 SNBT 的内容

`snbt(sx, sy, sz)` 生成单行 SNBT：`size:[sx,sy,sz]`、`palette:[{Name:"minecraft:air"}]`、空的 `blocks`/`entities`，`DataVersion` 固定为 `3465`（MC 1.20.1）。

<!-- mex:entity
id: mx_01M6FTMGPGKHKSDM3RZWJDE54M
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - GameTest 模板有哪几个
    - 新增结构模板没生成
    - 模板尺寸在哪定义
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/gen_gametest_structure.py
    node: function:2d26265ee1c84c6dd8a803ccbf81fac9
    fingerprint: mh2:64:bg0C6LQKSG90AofMCQA2F_0CFBJzAcvS8ABlQloF4cTfBRePGQCHHD8ABfw3AKvlzgKEkM4DJ1h5AElnPQaGD0MC4aa3AIAI-AW3yNcQeDYtACvMHADLZn0AG5psAJX63wXO-jAFFnqNA4m7AgD9frgFXSvOAUdd6QDwYnEAcnjwAENuRwI1w14F5kuaAY4CpQCVV7gA0S07AqJD1wPYWXQBxL-6B4XQagDHEfsBWfVMCVkGtgK00IUEE09lBvgWuAj7g1oCio18AavwUAdGIXwBHn02A8tWfAfhJ2YEL0wOAzrzHQGCsx8Ge3FMAubrtgRbuF0F3dddAeyzuQCy8E0BCGZ1bmN0aW9uAQB4q00bJeP3GMMxy4WQyEE7
    bodyHash: f5541b657d9461ac884d9e87bb078ab05a9b7db23d5dbeb861d333f7203a0079
-->
## 两个 GameTest 模板的尺寸

`TEMPLATES` 只定义 `empty_16x8x16` 和 `empty_24x12x24` 两个模板；`main()` 遍历这个字典，所以新增模板必须同时加进该字典才会被写出。

<!-- mex:entity
id: mx_01M6FTMGQMRR9Z61HF1E17AD1N
type: fact
status: promoted
revision: 1
metadata:
  aliases:
    - AsmDump 输出的签名格式
    - 泛型类型怎么显示
    - 怎么反查 DLL 方法参数
relations:
  - type: refines
    target: mx_01M6FTMGAC6EFK2JXPVN38G81K
    note: Part of tools in context/tools.md
provenance:
  createdBy:
    kind: agent
    id: zcode
  createdAt: 2026-11-03T04:30:00Z
grounds_to:
  - file: tools/AsmDump/Program.cs
    node: class:dfc1a83981ab31b0bb6f907b915ebd94
    fingerprint: mh2:64:qQII96TyAGLrLwAFLMYANhf9Bo9uyASVSLAI3-tJAGmHgwOr20EC85IaARFMcABm97YEhq4KALgQEQEDhz8AicbSASIzAAH6ddEFq24hAUZbigGs5LAC_1nHABuabAWbOigEUH_CArqu2AAfQasBWyi4ACSNxwKaVlcAvtEvAe_-IwAt8JoAyVzKBbmAPABJGtUAaQvaANEtOwBZESkD2Fl0AdAgeADzZmEC6U_RAF6dGgF_sAYAe3FKCo1U2Auo2wkGEN7SAl4-lQX_iR4CVGCGBjqgTAC1xiEAS-ZFA7C_0gCbWj4A8CDCAOYq6gEEws8AjknxDPClzwFfY3kAMcsQAAA
    bodyHash: 94b102be7fe7918848bbbccbd013e7a363d12a8f0208fe2738d68ba41b90f144
  - file: tools/AsmDump/Program.cs
    node: method:e3d400c6e4b3986612e5be6d3094dbab
    fingerprint: mh2:64:EREFQicAYusvEMKH0Q7XTIwbzNhOB6pVewjf60kAaYeDEWS-cSIJqjAYC8KaDlhO6UfR18MKgpo8ExuY9AYBaKdLWrv8CAjH_yvykaMCG3ErFZl2YQbHZ0YLXPCcDf0e0i7S-FoYx_96FqrNkQFbKLgF-6J5Dd7n_g4bMFMF1UvhAC3wmhEY9gIQjQb0KDQJJAB6qTIGhFDrDBSeZSATdVkpVl8KDWGScQLpT9EavBMqET-N1AG5tL8KjVTYDpUTQgqHP2YL3XRgIaDFeg7KB-ILWEmvERM59xpcX_QImocZAJtaPgcCxCoNLfVQA0XGhDPf89cm62FYIyApUDR0gbUAAA
    bodyHash: 79659dfdac407087fab53ca9dc9faee0a1cb9a41393bdbeebb689fe0f2a4fd7b
  - file: tools/AsmDump/Program.cs
    node: method:a1505968e6e0d58680b048815f0d8c6f
    fingerprint: mh2:64:KQkWBWgAYusvAFzyqgpPGSgHDGCKB6pVewjf60kAaYeDA6vbQQLzkhoIb3EiDlhO6QV-ryQKgpo8ExuY9AYBaKcBIjMAAfp10QmHF1IBWVkDBsTX5gbHZ0YAG5psB6A6xwRQf8IYx_96ALl9lQFbKLgF-6J5AppWVwXGMGMIvrVNAC3wmgSzyB0FuYA8COwbcwB6qTIDl-yNAFkRKQPYWXQCRGmcAiKl1gLpT9EOvZfmEmd0yQGDRngKjVTYDpUTQgj7g1oCXj6VEV6j6wZ6BEILWEmvCFQyNABL5kUTqfeJAJtaPgcCxCoCaqPYA0XGhAcTwsgO5_kVG4Begxowt7kAAA
    bodyHash: 2e8c5f3cdb7427f8c9adc7bd15e53ce07b494c68d2ecbfd45977a915535e62bb
-->
## AsmDump 把元数据签名解码成可读字符串

`TypeProvider` 实现 `ISignatureTypeProvider<string, object>`，把数组、`ref`、泛型实例化、指针和基元类型分别格式化成 `[]`、`ref `、`<...>`、`*` 或枚举名，供 `Program.cs` 打印字段和方法签名。
