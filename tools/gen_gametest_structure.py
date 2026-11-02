"""生成 GameTest 用的空结构模板（全空气）。

**踩坑记录（2026-11-01）**：1.20.1 的 GameTest 找模板走 `StructureUtils.getStructureTemplate`，
它先试 `StructureTemplateManager.get(ResourceLocation)`（读 `data/<ns>/structures/*.nbt`），
失败后回落到**文件系统**路径 `Paths.get(testStructuresDir, "gameteststructures/<path>.snbt")`。

实测三种放法：
  1. `src/main/resources/data/barotrauma/structures/*.nbt`          -> 失败
  2. `src/main/resources/data/barotrauma/gameteststructures/*.snbt` -> 失败
  3. **`run/gameteststructures/*.snbt` -> 成功**

所以模板是**运行目录里的文件**，不是 mod 资源。`gradlew runGameTestServer` 的
workingDirectory 是 `run/`，因此本脚本把模板写到 `<项目>/run/gameteststructures/`。

用法：
    python tools/gen_gametest_structure.py
"""

import sys
from pathlib import Path

# 模板尺寸：够摆一辆 5x5x5 的小船，并留出边距
TEMPLATES = {
    "empty_16x8x16": (16, 8, 16),
    "empty_24x12x24": (24, 12, 24),
}

DATA_VERSION = "3465"  # MC 1.20.1


def snbt(sx: int, sy: int, sz: int) -> str:
    return (
        '{size:[%d,%d,%d],palette:[{Name:"minecraft:air"}],blocks:[],entities:[],DataVersion:%s}'
        % (sx, sy, sz, DATA_VERSION)
    )


def main() -> int:
    out_dir = Path(__file__).resolve().parent.parent / "run" / "gameteststructures"
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, (sx, sy, sz) in TEMPLATES.items():
        path = out_dir / (name + ".snbt")
        path.write_text(snbt(sx, sy, sz) + "\n", encoding="ascii")
        print("wrote %s  (%d x %d x %d)" % (path, sx, sy, sz))
    return 0


if __name__ == "__main__":
    sys.exit(main())
