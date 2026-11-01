"""生成 Barotrauma 模组的 16x16 占位贴图与模组图标。

用法：python tools/gen_textures.py
输出：
  src/main/resources/assets/barotrauma/textures/item/oxygen_tank.png
  src/main/resources/assets/barotrauma/textures/item/diving_knife.png
  src/main/resources/assets/barotrauma/textures/block/submarine_plating.png
  src/main/resources/barotrauma.png          (模组列表图标 128x128)

纯几何绘制，不依赖任何美术资源；换正式美术时直接替换同名 png 即可。
"""

from __future__ import annotations

import random
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "barotrauma" / "textures"

# 工业深潜配色
STEEL_LIGHT = (196, 205, 211, 255)
STEEL = (150, 160, 168, 255)
STEEL_DARK = (86, 95, 102, 255)
OUTLINE = (28, 32, 36, 255)
RUST = (150, 74, 33, 255)
SAFETY_ORANGE = (214, 106, 32, 255)
BRASS = (186, 148, 74, 255)
HANDLE = (58, 41, 28, 255)


def new_image() -> Image.Image:
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def put(draw: ImageDraw.ImageDraw, x: int, y: int, color: tuple[int, int, int, int]) -> None:
    if 0 <= x < 16 and 0 <= y < 16:
        draw.point((x, y), fill=color)


def rect(draw: ImageDraw.ImageDraw, x0: int, y0: int, x1: int, y1: int, color) -> None:
    draw.rectangle([x0, y0, x1, y1], fill=color)


def oxygen_tank() -> Image.Image:
    """竖立钢瓶：橙色警示环 + 黄铜阀。"""
    img = new_image()
    d = ImageDraw.Draw(img)

    # 瓶身轮廓（x 5..10，y 3..14）
    rect(d, 5, 3, 10, 14, STEEL)
    # 顶部与底部圆角
    rect(d, 6, 2, 9, 2, STEEL)
    rect(d, 6, 15, 9, 15, STEEL)
    # 左亮右暗的圆柱体积感
    for y in range(2, 16):
        put(d, 5, y, STEEL_LIGHT)
        put(d, 10, y, STEEL_DARK)
    # 描边
    for y in range(2, 16):
        put(d, 4, y, OUTLINE)
        put(d, 11, y, OUTLINE)
    for x in range(5, 11):
        put(d, x, 1, OUTLINE)
        put(d, x, 16 - 1, OUTLINE)

    # 橙色警示环
    rect(d, 5, 6, 10, 7, SAFETY_ORANGE)
    for x in range(5, 11):
        put(d, x, 6, (232, 132, 58, 255))

    # 黄铜阀门
    rect(d, 7, 0, 8, 1, BRASS)
    put(d, 7, 0, (208, 176, 104, 255))
    put(d, 9, 1, BRASS)

    # 高光与锈迹点缀
    for y in (4, 5, 9, 10, 12):
        put(d, 5, y, (226, 233, 237, 255))
    put(d, 10, 11, RUST)
    put(d, 6, 13, (120, 128, 134, 255))

    return img


def diving_knife() -> Image.Image:
    """斜置潜水刀：左下握柄，右上刀尖。"""
    img = new_image()
    d = ImageDraw.Draw(img)

    # 刀刃（从 (6,10) 到 (12,3)）
    for i in range(7):
        x = 6 + i
        y = 10 - i
        put(d, x, y, (222, 229, 234, 255))
        put(d, x, y + 1, (245, 249, 251, 255))  # 刃口高光
        put(d, x + 1, y - 1, OUTLINE)
        put(d, x - 1, y + 2, STEEL_DARK)

    # 刀尖
    put(d, 12, 2, (245, 249, 251, 255))

    # 护手
    rect(d, 4, 10, 6, 11, BRASS)
    put(d, 4, 10, (214, 176, 110, 255))

    # 握柄
    rect(d, 1, 12, 4, 15, HANDLE)
    rect(d, 2, 13, 3, 14, (86, 62, 42, 255))
    # 柄尾配重球
    rect(d, 0, 14, 1, 15, (42, 42, 46, 255))

    # 描边
    for (x, y) in ((1, 11), (1, 16 - 0), (5, 16), (0, 13)):
        put(d, x, y, OUTLINE)

    return img


def submarine_plating() -> Image.Image:
    """铆接钢板：边框中缝 + 四角铆钉 + 锈斑。"""
    img = Image.new("RGBA", (16, 16), STEEL)
    d = ImageDraw.Draw(img)
    rng = random.Random(47)

    # 噪声：让平面不那么死
    for y in range(16):
        for x in range(16):
            if rng.random() < 0.28:
                delta = rng.randint(-12, 12)
                base = STEEL
                d.point((x, y), fill=(
                    max(0, min(255, base[0] + delta)),
                    max(0, min(255, base[1] + delta)),
                    max(0, min(255, base[2] + delta)),
                    255,
                ))

    # 外框
    d.rectangle([0, 0, 15, 15], outline=STEEL_DARK)
    d.rectangle([0, 0, 15, 15], outline=OUTLINE)

    # 中缝（焊接线）：上亮下暗
    for x in range(0, 16):
        d.point((x, 7), fill=STEEL_LIGHT)
        d.point((x, 8), fill=STEEL_DARK)

    # 四角铆钉
    for (cx, cy) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        d.point((cx, cy), fill=(60, 66, 71, 255))
        d.point((cx - 1, cy - 1), fill=STEEL_LIGHT)
        d.point((cx, cy + 1), fill=(44, 49, 53, 255))

    # 锈斑
    for (x, y) in ((4, 12), (5, 12), (4, 13), (11, 4), (12, 4), (10, 11)):
        d.point((x, y), fill=RUST)

    return img


def mod_logo() -> Image.Image:
    """模组列表图标：深潜海面 + 潜艇剪影 + 气泡。"""
    size = 128
    img = Image.new("RGBA", (size, size), (12, 26, 34, 255))
    d = ImageDraw.Draw(img)

    # 上层水面渐变
    for y in range(size):
        shade = int(12 + 30 * (y / size))
        d.line([(0, y), (size, y)], fill=(shade, int(shade * 1.6), int(shade * 2.0), 255))

    # 潜艇船体
    d.rounded_rectangle([16, 52, 112, 84], radius=16, fill=(150, 160, 168, 255))
    d.rounded_rectangle([16, 52, 112, 62], radius=10, fill=(196, 205, 211, 255))
    # 指挥塔
    d.rounded_rectangle([52, 34, 78, 56], radius=5, fill=(150, 160, 168, 255))
    # 潜望镜
    d.rectangle([64, 20, 66, 36], fill=(186, 148, 74, 255))
    # 舷窗
    for cx in (36, 56, 76, 96):
        d.ellipse([cx - 6, 63, cx + 6, 75], fill=(40, 70, 82, 255))
        d.ellipse([cx - 6, 63, cx + 6, 75], outline=(214, 106, 32, 255), width=2)
    # 尾舵
    d.polygon([(112, 58), (126, 48), (126, 76), (112, 72)], fill=(120, 130, 138, 255))

    # 气泡
    for (cx, cy, r) in ((24, 30, 4), (32, 20, 3), (20, 44, 2), (100, 26, 3), (108, 40, 2)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(120, 200, 220, 200), width=2)

    return img


def _panel(d: ImageDraw.ImageDraw) -> None:
    """统一的金属边框 + 面板底色。"""
    rect(d, 0, 0, 15, 15, STEEL_DARK)
    rect(d, 1, 1, 14, 14, STEEL)
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        d.point((x, y), fill=STEEL_LIGHT)


def _trefoil(d: ImageDraw.ImageDraw, cx: float, cy: float, r: float) -> None:
    """辐射三叶标志。"""
    d.ellipse([cx - r - 1, cy - r - 1, cx + r + 1, cy + r + 1], fill=(52, 46, 28, 255))
    for start in (-90, 30, 150):
        d.pieslice([cx - r, cy - r, cx + r, cy + r], start, start + 60, fill=(236, 196, 62, 255))
    d.ellipse([cx - 1.5, cy - 1.5, cx + 1.5, cy + 1.5], fill=(52, 46, 28, 255))


def hull_glass() -> Image.Image:
    """舷窗：厚钢框 + 蓝玻璃 + 高光。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    _panel(d)
    rect(d, 3, 3, 12, 12, (140, 208, 228, 255))
    rect(d, 4, 4, 11, 6, (186, 232, 245, 255))
    for i in range(4):
        d.point((4 + i, 4 + i), fill=(236, 250, 255, 255))
    rect(d, 3, 11, 12, 12, (86, 150, 176, 255))
    return img


def hatch() -> Image.Image:
    """普通舱门：钢板 + 横向把手 + 底部警示条。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    _panel(d)
    rect(d, 2, 2, 13, 13, (108, 118, 126, 255))
    rect(d, 3, 3, 12, 12, (130, 140, 148, 255))
    rect(d, 5, 6, 10, 7, (54, 60, 66, 255))
    rect(d, 9, 6, 10, 8, SAFETY_ORANGE)
    for x in range(2, 14, 2):
        rect(d, x, 12, x + 1, 13, (44, 48, 52, 255))
    return img


def airlock_door() -> Image.Image:
    """气闸门：黄黑警示条 + 舷窗。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    _panel(d)
    rect(d, 2, 2, 13, 13, (120, 128, 134, 255))
    for x in range(2, 14, 4):
        for i in range(4):
            rect(d, x + i, 2 + i, x + i + 1, 2 + i, (232, 196, 62, 255))
            rect(d, x + i, 11 - i, x + i + 1, 11 - i, (232, 196, 62, 255))
    d.ellipse([5, 5, 10, 10], fill=(30, 60, 74, 255))
    d.ellipse([6, 6, 9, 9], fill=(120, 200, 220, 255))
    return img


def nuclear_reactor() -> Image.Image:
    """核反应堆：深色机壳 + 辐射三叶 + 顶部余热。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    rect(d, 0, 0, 15, 15, (58, 62, 66, 255))
    rect(d, 1, 2, 14, 14, (74, 78, 82, 255))
    rect(d, 1, 0, 14, 1, (196, 96, 48, 255))
    _trefoil(d, 7.5, 8.0, 4.2)
    for x in (2, 13):
        d.point((x, 3), fill=(210, 216, 220, 255))
    rect(d, 4, 13, 11, 13, (40, 44, 48, 255))
    return img


def oxygen_generator() -> Image.Image:
    """制氧机：青蓝气罐 + 气泡 + 压力表。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    rect(d, 0, 0, 15, 15, (32, 74, 82, 255))
    rect(d, 1, 1, 14, 14, (52, 110, 120, 255))
    rect(d, 2, 6, 6, 13, (32, 84, 92, 255))
    for (cx, cy, r) in ((10, 5, 1.6), (12, 8, 1.2), (7, 4, 1.0)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(206, 240, 246, 255))
    d.ellipse([2, 1, 6, 5], fill=(24, 52, 58, 255))
    d.line([3, 3, 5, 2], fill=(220, 232, 236, 255))
    for y in (11, 13):
        rect(d, 8, y, 13, y, (36, 70, 76, 255))
    return img


def engine() -> Image.Image:
    """引擎：灰壳 + 橙红喷口 + 散热片。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    rect(d, 0, 0, 15, 15, (72, 78, 84, 255))
    for x in range(1, 15, 3):
        rect(d, x, 1, x + 1, 7, (96, 104, 110, 255))
    rect(d, 4, 8, 11, 10, (54, 58, 62, 255))
    d.polygon([(5, 11), (10, 11), (12, 15), (3, 15)], fill=(198, 92, 44, 255))
    d.polygon([(6, 12), (9, 12), (10, 15), (5, 15)], fill=(238, 168, 72, 255))
    for (x, y) in ((1, 1), (14, 1), (1, 7), (14, 7)):
        d.point((x, y), fill=(214, 220, 224, 255))
    return img


def ballast_tank() -> Image.Image:
    """压载舱：钢罐 + 蓝色水线 + 铆钉。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    _panel(d)
    rect(d, 2, 2, 13, 13, (96, 106, 114, 255))
    for y in range(8, 14):
        rect(d, 2, y, 13, y, (42, 96, 138, 255))
    for x in range(2, 14, 3):
        d.point((x, 8), fill=(120, 180, 220, 255))
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=(48, 52, 56, 255))
    return img


def pump() -> Image.Image:
    """水泵：灰绿泵体 + 橙色叶轮。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    rect(d, 0, 0, 15, 15, (70, 78, 70, 255))
    rect(d, 1, 1, 14, 14, (88, 98, 88, 255))
    d.ellipse([3, 3, 12, 12], fill=(52, 60, 52, 255))
    for (x0, y0, x1, y1) in ((7, 4, 8, 7), (7, 8, 8, 11), (4, 7, 7, 8), (8, 7, 11, 8)):
        rect(d, x0, y0, x1, y1, SAFETY_ORANGE)
    d.ellipse([6, 6, 9, 9], fill=(40, 46, 40, 255))
    return img


def sonar() -> Image.Image:
    """声纳台：照 wiki 的 Navigation Terminal——米灰锈蚀机身 + 左侧大屏 + 右上小屏 + 两个琥珀窗 + 底部键盘台。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    # 机身
    rect(d, 0, 0, 15, 15, (120, 116, 104, 255))
    rect(d, 1, 1, 14, 11, (176, 170, 152, 255))
    # 锈斑
    for (x, y) in ((3, 9), (9, 2), (13, 10), (4, 11)):
        d.point((x, y), fill=(140, 124, 100, 255))
    # 顶部通风格栅
    for x in range(2, 8, 2):
        rect(d, x, 1, x + 1, 1, (92, 88, 78, 255))
    # 主屏 + 一条扫描线 + 回波光点
    rect(d, 1, 3, 10, 10, (18, 24, 26, 255))
    rect(d, 2, 8, 9, 8, (28, 66, 62, 255))
    d.point((5, 6), fill=(150, 240, 220, 255))
    # 右上小屏
    rect(d, 12, 3, 14, 5, (18, 24, 26, 255))
    d.point((13, 4), fill=(90, 200, 190, 255))
    # 两个琥珀窗
    rect(d, 12, 7, 14, 7, (120, 84, 34, 255))
    rect(d, 12, 9, 14, 9, (120, 84, 34, 255))
    # 底部键盘台
    rect(d, 0, 12, 15, 15, (96, 92, 84, 255))
    for x in range(1, 15, 2):
        d.point((x, 13), fill=(152, 148, 134, 255))
    for x in range(4, 12, 2):
        d.point((x, 14), fill=(132, 128, 116, 255))
    return img


def helm() -> Image.Image:
    """驾驶台：控制面板 + 两根操纵杆 + 小仪表。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    rect(d, 0, 0, 15, 15, (60, 64, 68, 255))
    rect(d, 1, 3, 14, 14, (78, 84, 90, 255))
    rect(d, 1, 1, 14, 2, (52, 56, 60, 255))
    for x in (4, 8):
        rect(d, x, 6, x + 1, 11, (36, 40, 44, 255))
        rect(d, x - 1, 5, x + 2, 6, SAFETY_ORANGE)
    d.ellipse([11, 4, 14, 7], fill=(20, 46, 36, 255))
    d.point((12, 5), fill=(120, 240, 170, 255))
    rect(d, 3, 12, 6, 13, (150, 60, 46, 255))
    return img


def ship_core() -> Image.Image:
    """船核心：深色控制台 + 青色全息潜艇 + 四角螺丝。"""
    img = new_image()
    d = ImageDraw.Draw(img)
    rect(d, 0, 0, 15, 15, (38, 44, 50, 255))
    rect(d, 1, 1, 14, 14, (52, 60, 68, 255))
    for x in range(3, 14, 3):
        rect(d, x, 3, x, 12, (30, 70, 64, 255))
    for y in range(4, 13, 3):
        rect(d, 2, y, 13, y, (30, 70, 64, 255))
    rect(d, 4, 7, 11, 9, (90, 230, 200, 255))
    rect(d, 6, 5, 8, 6, (90, 230, 200, 255))
    d.point((12, 8), fill=(160, 255, 232, 255))
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        d.point((x, y), fill=(150, 156, 160, 255))
    return img


def station_plating() -> Image.Image:
    """站点装甲：比船体更暗、带黄黑警示条，一眼能和船板区分开。"""
    img = Image.new("RGBA", (16, 16), (58, 62, 68, 255))
    d = ImageDraw.Draw(img)
    for y in range(16):
        for x in range(16):
            if (x * 7 + y * 13) % 11 == 0:
                d.point((x, y), fill=(48, 52, 58, 255))
    d.rectangle([0, 0, 15, 15], outline=(28, 30, 34, 255))
    for x in range(2, 14, 4):
        for i in range(4):
            d.point((x + i, 2 + i), fill=(196, 160, 52, 255))
            d.point((x + i, 13 - i), fill=(196, 160, 52, 255))
    for (cx, cy) in ((3, 8), (12, 8)):
        d.point((cx, cy), fill=(150, 156, 160, 255))
    return img


def main() -> None:
    (ASSETS / "item").mkdir(parents=True, exist_ok=True)
    (ASSETS / "block").mkdir(parents=True, exist_ok=True)

    outputs = {
        ASSETS / "item" / "oxygen_tank.png": oxygen_tank(),
        ASSETS / "item" / "diving_knife.png": diving_knife(),
        ASSETS / "block" / "submarine_plating.png": submarine_plating(),
        ASSETS / "block" / "hull_glass.png": hull_glass(),
        ASSETS / "block" / "hatch.png": hatch(),
        ASSETS / "block" / "airlock_door.png": airlock_door(),
        ASSETS / "block" / "nuclear_reactor.png": nuclear_reactor(),
        ASSETS / "block" / "oxygen_generator.png": oxygen_generator(),
        ASSETS / "block" / "engine.png": engine(),
        ASSETS / "block" / "ballast_tank.png": ballast_tank(),
        ASSETS / "block" / "pump.png": pump(),
        ASSETS / "block" / "sonar.png": sonar(),
        ASSETS / "block" / "helm.png": helm(),
        ASSETS / "block" / "ship_core.png": ship_core(),
        ASSETS / "block" / "station_plating.png": station_plating(),
        ROOT / "src" / "main" / "resources" / "barotrauma.png": mod_logo(),
    }
    for path, image in outputs.items():
        image.save(path)
        print(f"wrote {path.relative_to(ROOT)} ({image.width}x{image.height})")


if __name__ == "__main__":
    main()
