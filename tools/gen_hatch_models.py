#!/usr/bin/env python3
"""生成 hatch（3 宽 × 1 深 × 1 厚）与 bulkhead_door（1 宽 × 3 高）的原创素材。

输出：
- textures/block/{hatch,bulkhead_door}.png（64x64 图集）
- textures/item/{hatch,bulkhead_door}.png（16x16 物品图标）
- models/block/*.json（每个分段、开/关、地板/天花板）
- blockstates/*.json
- models/item/*.json（generated 图标）
- art/models/*.bbmodel（Blockbench 工程，整扇 3 格模型）

材质是重绘的：深色钢板、铆钉、黄黑警示条、红色按钮，不复制原版像素。
"""
from __future__ import annotations

import base64
import json
import random
import uuid
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "src" / "main" / "resources" / "assets" / "barotrauma"
RES_BLOCK = ASSETS / "models" / "block"
RES_ITEM = ASSETS / "models" / "item"
RES_STATE = ASSETS / "blockstates"
RES_TEX = ASSETS / "textures" / "block"
RES_ITEM_TEX = ASSETS / "textures" / "item"
RES_GEO = ASSETS / "geo"
RES_ANIM = ASSETS / "animations"
ART_MODELS = REPO / "art" / "models"

TEX_W = TEX_H = 64
MC_UV_SCALE = 16.0 / TEX_W
FACES = ("north", "south", "east", "west", "up", "down")
DOOR_PARTS = ("bottom", "middle", "top")
HATCH_PARTS = ("left", "middle", "right")

REGIONS = {
    "frame": (0, 0, 16, 16),
    "panel": (16, 0, 32, 16),
    "hazard": (32, 0, 48, 16),
    "dark": (48, 0, 64, 16),
    "button": (0, 16, 16, 32),
    "rib": (16, 16, 32, 32),
    "frame_light": (32, 16, 48, 32),
    "dark2": (48, 16, 64, 32),
}


def clamp(value):
    return max(0, min(255, int(value)))


def tint(color, delta):
    return tuple(clamp(channel + delta) for channel in color)


def fill_noise(image, box, base, variance, rnd):
    x0, y0, x1, y1 = box
    draw = ImageDraw.Draw(image)
    for y in range(y0, y1):
        for x in range(x0, x1):
            draw.point((x, y), fill=tint(base, rnd.randint(-variance, variance)) + (255,))


def bevel(draw, box, light, dark):
    x0, y0, x1, y1 = box
    draw.line([(x0, y0), (x1 - 1, y0)], fill=light)
    draw.line([(x0, y0), (x0, y1 - 1)], fill=light)
    draw.line([(x0, y1 - 1), (x1 - 1, y1 - 1)], fill=dark)
    draw.line([(x1 - 1, y0), (x1 - 1, y1 - 1)], fill=dark)


def rivet(draw, x, y, size, light, dark):
    draw.ellipse([x, y, x + size, y + size], fill=dark)
    draw.ellipse([x + 1, y + 1, x + size - 1, y + size - 1], fill=light)


def scratches(draw, box, count, rnd, color):
    x0, y0, x1, y1 = box
    for _ in range(count):
        x = rnd.randint(x0 + 1, x1 - 3)
        y = rnd.randint(y0 + 1, y1 - 1)
        length = rnd.randint(1, 4)
        draw.line([(x, y), (x + length, y + rnd.choice([-1, 0, 0, 1]))], fill=color)


def draw_frame(image, box, rnd):
    x0, y0, x1, y1 = box
    fill_noise(image, box, (50, 56, 59), 7, rnd)
    draw = ImageDraw.Draw(image)
    bevel(draw, box, (91, 99, 103, 255), (26, 30, 32, 255))
    for cx, cy in ((x0 + 2, y0 + 2), (x1 - 5, y0 + 2), (x0 + 2, y1 - 5), (x1 - 5, y1 - 5)):
        rivet(draw, cx, cy, 3, (112, 121, 125, 255), (30, 34, 36, 255))
    scratches(draw, box, 9, rnd, (79, 86, 90, 255))
    scratches(draw, box, 4, rnd, (27, 31, 33, 255))


def draw_frame_light(image, box, rnd):
    x0, y0, x1, y1 = box
    fill_noise(image, box, (68, 75, 79), 6, rnd)
    draw = ImageDraw.Draw(image)
    bevel(draw, box, (112, 122, 126, 255), (38, 43, 46, 255))
    scratches(draw, box, 8, rnd, (95, 103, 107, 255))


def draw_panel(image, box, rnd):
    x0, y0, x1, y1 = box
    fill_noise(image, box, (36, 41, 44), 6, rnd)
    draw = ImageDraw.Draw(image)
    for x in range(x0 + 1, x1, 3):
        draw.line([(x, y0 + 1), (x, y1 - 2)], fill=(45, 51, 54, 255))
        draw.line([(x + 1, y0 + 1), (x + 1, y1 - 2)], fill=(28, 32, 35, 255))
    draw.rectangle([x0, y0, x1 - 1, y0 + 1], fill=(58, 65, 69, 255))
    draw.rectangle([x0, y1 - 2, x1 - 1, y1 - 1], fill=(20, 23, 25, 255))
    for _ in range(4):
        rx = rnd.randint(x0 + 2, x1 - 4)
        ry = rnd.randint(y0 + 3, y1 - 5)
        draw.rectangle([rx, ry, rx + rnd.randint(1, 2), ry + rnd.randint(1, 2)],
                       fill=(72, 47, 32, 255))
    scratches(draw, box, 7, rnd, (54, 60, 63, 255))
    scratches(draw, box, 3, rnd, (24, 28, 30, 255))


def draw_hazard(image, box, rnd):
    tile = Image.new("RGBA", (16, 16), (22, 22, 20, 255))
    draw = ImageDraw.Draw(tile)
    for offset in range(-16, 32, 8):
        draw.polygon([(offset, 0), (offset + 4, 0),
                      (offset + 4 + 16, 16), (offset + 16, 16)],
                     fill=(198, 158, 42, 255))
    for y in range(16):
        for x in range(16):
            if rnd.random() < 0.18:
                r, g, b, a = tile.getpixel((x, y))
                tile.putpixel((x, y), (clamp(r + rnd.randint(-24, 24)),
                                       clamp(g + rnd.randint(-24, 24)),
                                       clamp(b + rnd.randint(-18, 18)), a))
    image.paste(tile, (box[0], box[1]))


def draw_dark(image, box, rnd, base=(7, 9, 10)):
    x0, y0, x1, y1 = box
    draw = ImageDraw.Draw(image)
    for y in range(y0, y1):
        for x in range(x0, x1):
            draw.point((x, y), fill=tint(base, rnd.randint(-2, 4) + (y - y0) // 5) + (255,))
    draw.line([(x0, y0), (x1 - 1, y0)], fill=(26, 30, 32, 255))


def draw_button(image, box, rnd):
    x0, y0, x1, y1 = box
    fill_noise(image, box, (48, 54, 57), 5, rnd)
    draw = ImageDraw.Draw(image)
    bevel(draw, box, (96, 105, 109, 255), (24, 28, 30, 255))
    draw.ellipse([x0 + 4, y0 + 2, x0 + 12, y0 + 10], fill=(74, 20, 16, 255))
    draw.ellipse([x0 + 5, y0 + 3, x0 + 11, y0 + 9], fill=(154, 42, 32, 255))
    draw.ellipse([x0 + 6, y0 + 4, x0 + 9, y0 + 6], fill=(216, 84, 62, 255))
    draw.rectangle([x0 + 3, y0 + 11, x0 + 12, y0 + 14], fill=(32, 54, 30, 255))
    draw.rectangle([x0 + 4, y0 + 12, x0 + 11, y0 + 13], fill=(78, 122, 66, 255))
    draw.line([(x0 + 4, y0 + 13), (x0 + 10, y0 + 13)], fill=(116, 168, 92, 255))


def draw_rib(image, box, rnd):
    x0, y0, x1, y1 = box
    fill_noise(image, box, (60, 67, 71), 5, rnd)
    draw = ImageDraw.Draw(image)
    draw.rectangle([x0 + 6, y0, x0 + 9, y1 - 1], fill=(78, 86, 90, 255))
    draw.line([(x0 + 7, y0), (x0 + 7, y1 - 1)], fill=(108, 117, 121, 255))
    draw.line([(x0 + 9, y0), (x0 + 9, y1 - 1)], fill=(34, 39, 41, 255))
    scratches(draw, box, 5, rnd, (48, 54, 57, 255))


def draw_dark2(image, box, rnd):
    x0, y0, x1, y1 = box
    fill_noise(image, box, (27, 31, 33), 5, rnd)
    draw = ImageDraw.Draw(image)
    bevel(draw, box, (48, 54, 57, 255), (12, 15, 16, 255))
    scratches(draw, box, 5, rnd, (36, 41, 44, 255))


def make_atlas(seed):
    image = Image.new("RGBA", (TEX_W, TEX_H), (10, 12, 13, 255))
    rnd = random.Random(seed)
    for y in range(TEX_H):
        for x in range(TEX_W):
            image.putpixel((x, y), tint((16, 19, 20), rnd.randint(-4, 4)) + (255,))
    draw_frame(image, REGIONS["frame"], rnd)
    draw_panel(image, REGIONS["panel"], rnd)
    draw_hazard(image, REGIONS["hazard"], rnd)
    draw_dark(image, REGIONS["dark"], rnd)
    draw_button(image, REGIONS["button"], rnd)
    draw_rib(image, REGIONS["rib"], rnd)
    draw_frame_light(image, REGIONS["frame_light"], rnd)
    draw_dark2(image, REGIONS["dark2"], rnd)
    return image


def make_door_icon():
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    # 门体
    d.rectangle([4, 1, 11, 14], fill=(34, 39, 42, 255), outline=(12, 15, 16, 255))
    d.rectangle([5, 3, 10, 12], fill=(43, 49, 52, 255))
    for x in (6, 8, 10):
        d.line([(x, 4), (x, 12)], fill=(58, 65, 69, 255))
        d.line([(x + 1, 4), (x + 1, 12)], fill=(28, 32, 35, 255))
    # 警示条
    for y in (1, 2, 13, 14):
        for x in range(4, 12):
            d.point((x, y), fill=(198, 158, 42, 255) if (x + y) % 4 < 2 else (22, 22, 20, 255))
    # 红色按钮
    d.rectangle([9, 6, 10, 8], fill=(30, 34, 36, 255))
    d.point((9, 7), fill=(190, 58, 44, 255))
    d.point((10, 7), fill=(120, 30, 24, 255))
    return image


def make_hatch_icon():
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    # 3:1 横向舱口
    d.rectangle([0, 4, 15, 11], fill=(36, 41, 44, 255), outline=(11, 14, 15, 255))
    d.rectangle([1, 5, 14, 10], fill=(48, 54, 57, 255))
    d.rectangle([1, 7, 14, 8], fill=(27, 31, 33, 255))
    for x in range(2, 14, 4):
        d.line([(x, 5), (x, 10)], fill=(70, 78, 82, 255))
    # 两端警示条
    for x in (0, 1, 14, 15):
        for y in range(4, 12):
            d.point((x, y), fill=(198, 158, 42, 255) if (x + y) % 4 < 2 else (22, 22, 20, 255))
    # 按钮
    d.rectangle([11, 4, 13, 6], fill=(40, 46, 49, 255), outline=(15, 18, 20, 255))
    d.point((12, 5), fill=(190, 58, 44, 255))
    return image


class Cube:
    def __init__(self, name, frm, to, tex="frame", face_tex=None, skip=None):
        self.name = name
        self.frm = list(frm)
        self.to = list(to)
        self.tex = tex
        self.face_tex = dict(face_tex or {})
        self.skip = set(skip or ())

    def mirrored_y(self):
        cube = Cube(self.name, [self.frm[0], 16 - self.to[1], self.frm[2]],
                    [self.to[0], 16 - self.frm[1], self.to[2]],
                    self.tex, self.face_tex, self.skip)
        cube.face_tex = {}
        for face, tex in self.face_tex.items():
            cube.face_tex["down" if face == "up" else "up" if face == "down" else face] = tex
        return cube

    def face_regions(self):
        return {face: self.face_tex.get(face, self.tex)
                for face in FACES if face not in self.skip}


# ---------------------------------------------------------------- 竖向门（1x3）

def door_segment(part, opened):
    is_bottom = part == "bottom"
    is_top = part == "top"
    cubes = []

    if is_bottom:
        cubes.append(Cube("sill", (0, 0, 0), (16, 2, 4), "frame", {"north": "hazard"}))
        cubes.append(Cube("jamb_left", (0, 2, 0), (2, 16, 4), "frame"))
        cubes.append(Cube("jamb_right", (14, 2, 0), (16, 16, 4), "frame"))
    elif is_top:
        cubes.append(Cube("lintel", (0, 14, 0), (16, 16, 4), "frame", {"north": "hazard"}))
        cubes.append(Cube("jamb_left", (0, 0, 0), (2, 14, 4), "frame"))
        cubes.append(Cube("jamb_right", (14, 0, 0), (16, 14, 4), "frame"))
    else:
        cubes.append(Cube("jamb_left", (0, 0, 0), (2, 16, 4), "frame"))
        cubes.append(Cube("jamb_right", (14, 0, 0), (16, 16, 4), "frame"))
        cubes.append(Cube("button", (13.2, 7.0, 0.1), (14.4, 10.0, 0.6), "button"))

    # 门体侧墙/背板，保持整格不空心
    cubes.extend([
        Cube("body_left", (0, 0, 4), (2, 16, 16), "frame"),
        Cube("body_right", (14, 0, 4), (16, 16, 16), "frame"),
        Cube("back", (2, 0, 15), (14, 16, 16), "dark"),
    ])

    if opened:
        if is_bottom:
            cubes.extend([
                Cube("panel_left", (0.4, 2, 0.8), (1.8, 16, 2.2), "panel"),
                Cube("panel_right", (14.2, 2, 0.8), (15.6, 16, 2.2), "panel"),
            ])
        elif is_top:
            cubes.extend([
                Cube("panel_left", (0.4, 0, 0.8), (1.8, 14, 2.2), "panel"),
                Cube("panel_right", (14.2, 0, 0.8), (15.6, 14, 2.2), "panel"),
            ])
        else:
            cubes.extend([
                Cube("panel_left", (0.4, 0, 0.8), (1.8, 16, 2.2), "panel"),
                Cube("panel_right", (14.2, 0, 0.8), (15.6, 16, 2.2), "panel"),
            ])
    else:
        if is_bottom:
            cubes.extend([
                Cube("panel_left", (2.5, 2, 0.5), (8, 16, 2.5), "panel"),
                Cube("panel_right", (8, 2, 0.5), (13.5, 16, 2.5), "panel"),
                Cube("rib_1", (4, 3, 0.2), (5, 13, 0.6), "rib"),
                Cube("rib_2", (6, 3, 0.2), (7, 13, 0.6), "rib"),
                Cube("rib_3", (10, 3, 0.2), (11, 13, 0.6), "rib"),
                Cube("rib_4", (12, 3, 0.2), (13, 13, 0.6), "rib"),
            ])
        elif is_top:
            cubes.extend([
                Cube("panel_left", (2.5, 0, 0.5), (8, 14, 2.5), "panel"),
                Cube("panel_right", (8, 0, 0.5), (13.5, 14, 2.5), "panel"),
                Cube("rib_1", (4, 1, 0.2), (5, 13, 0.6), "rib"),
                Cube("rib_2", (6, 1, 0.2), (7, 13, 0.6), "rib"),
                Cube("rib_3", (10, 1, 0.2), (11, 13, 0.6), "rib"),
                Cube("rib_4", (12, 1, 0.2), (13, 13, 0.6), "rib"),
            ])
        else:
            cubes.extend([
                Cube("panel_left", (2.5, 0, 0.5), (8, 16, 2.5), "panel"),
                Cube("panel_right", (8, 0, 0.5), (13.5, 16, 2.5), "panel"),
                Cube("rib_1", (4, 1, 0.2), (5, 15, 0.6), "rib"),
                Cube("rib_2", (6, 1, 0.2), (7, 15, 0.6), "rib"),
                Cube("rib_3", (10, 1, 0.2), (11, 15, 0.6), "rib"),
                Cube("rib_4", (12, 1, 0.2), (13, 15, 0.6), "rib"),
            ])

    # 分段边界不画内侧面
    for cube in cubes:
        if not is_top and cube.to[1] == 16:
            cube.skip.add("up")
        if not is_bottom and cube.frm[1] == 0:
            cube.skip.add("down")
    return cubes


def door_full(opened):
    cubes = []
    for index, part in enumerate(DOOR_PARTS):
        for cube in door_segment(part, opened):
            cubes.append(Cube(cube.name + "_" + part,
                              [cube.frm[0], cube.frm[1] + index * 16, cube.frm[2]],
                              [cube.to[0], cube.to[1] + index * 16, cube.to[2]],
                              cube.tex, cube.face_tex, cube.skip))
    return cubes


# ---------------------------------------------------------------- 横向舱口（3x1）

def split_x(cubes):
    """把全局 x=0..48 的立方体切成 left/middle/right 三段。"""
    result = {part: [] for part in HATCH_PARTS}
    spans = {"left": (0, 16), "middle": (16, 32), "right": (32, 48)}
    for cube in cubes:
        for part, (start, end) in spans.items():
            x0 = max(cube.frm[0], start)
            x1 = min(cube.to[0], end)
            if x0 >= x1:
                continue
            skip = set(cube.skip)
            if cube.frm[0] < start:
                skip.add("west")
            if cube.to[0] > end:
                skip.add("east")
            result[part].append(Cube(
                cube.name, [x0 - start, cube.frm[1], cube.frm[2]],
                [x1 - start, cube.to[1], cube.to[2]],
                cube.tex, cube.face_tex, skip))
    for part, cubes_in_part in result.items():
        for cube in cubes_in_part:
            if part != "left" and cube.frm[0] == 0:
                cube.skip.add("west")
            if part != "right" and cube.to[0] == 16:
                cube.skip.add("east")
    return result


def hatch_segments(opened, bottom=False):
    cubes = [
        # 北/南长边框：左右两段用警示条，中段用普通钢板
        Cube("frame_north_left", (0, 13, 0), (16, 16, 2), "frame", {"up": "hazard"}),
        Cube("frame_north_mid", (16, 13, 0), (32, 16, 2), "frame"),
        Cube("frame_north_right", (32, 13, 0), (48, 16, 2), "frame", {"up": "hazard"}),
        Cube("frame_south_left", (0, 13, 14), (16, 16, 16), "frame", {"up": "hazard"}),
        Cube("frame_south_mid", (16, 13, 14), (32, 16, 16), "frame"),
        Cube("frame_south_right", (32, 13, 14), (48, 16, 16), "frame", {"up": "hazard"}),
        Cube("frame_west", (0, 13, 2), (2, 16, 14), "frame"),
        Cube("frame_east", (46, 13, 2), (48, 16, 14), "frame"),
        Cube("well", (2, 0, 2), (46, 13, 14), "dark"),
        Cube("button", (40, 15.4, 0.4), (43, 16, 1.8), "button", {"up": "button"}),
    ]
    if opened:
        cubes.extend([
            Cube("panel_left", (0.4, 14, 2), (2.4, 15, 14), "panel"),
            Cube("panel_right", (45.6, 14, 2), (47.6, 15, 14), "panel"),
        ])
    else:
        cubes.extend([
            Cube("panel_left", (2, 14, 2), (24, 15, 14), "panel"),
            Cube("panel_right", (24, 14, 2), (46, 15, 14), "panel"),
        ])
        for x in (5, 9, 13, 17, 21, 27, 31, 35, 39, 43):
            cubes.append(Cube("rib_%d" % x, (x, 15, 3), (x + 1, 16, 13), "rib"))

    # 边框/门体的内部面在分段后不渲染
    for cube in cubes:
        if cube.name.startswith("frame_north") or cube.name.startswith("frame_south"):
            if cube.name.endswith("_left"):
                cube.skip.add("east")
            elif cube.name.endswith("_mid"):
                cube.skip.update(("west", "east"))
            elif cube.name.endswith("_right"):
                cube.skip.add("west")

    segments = split_x(cubes)
    if bottom:
        segments = {part: [cube.mirrored_y() for cube in part_cubes]
                    for part, part_cubes in segments.items()}
    return segments


def hatch_full(opened, bottom=False):
    segments = hatch_segments(opened, bottom)
    cubes = []
    for index, part in enumerate(HATCH_PARTS):
        for cube in segments[part]:
            cubes.append(Cube(cube.name + "_" + part,
                              [cube.frm[0] + index * 16, cube.frm[1], cube.frm[2]],
                              [cube.to[0] + index * 16, cube.to[1], cube.to[2]],
                              cube.tex, cube.face_tex, cube.skip))
    return cubes


# ---------------------------------------------------------------- 写出

DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0],
                              "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0],
                              "scale": [0.4, 0.4, 0.4]},
}


def write_model(path, texture_name, cubes):
    elements = []
    for cube in cubes:
        faces = {}
        for face, region_name in cube.face_regions().items():
            x0, y0, x1, y1 = REGIONS[region_name]
            # Java 方块模型的 UV 是 0..16，不是纹理像素坐标；64x64 图集要除以 4。
            faces[face] = {
                "uv": [x0 * MC_UV_SCALE, y0 * MC_UV_SCALE,
                       x1 * MC_UV_SCALE, y1 * MC_UV_SCALE],
                "texture": "#all",
            }
        elements.append({
            "name": cube.name,
            "from": cube.frm,
            "to": cube.to,
            "faces": faces,
        })
    data = {
        "ambientocclusion": False,
        "textures": {
            "particle": "barotrauma:block/" + texture_name,
            "all": "barotrauma:block/" + texture_name,
        },
        "elements": elements,
        "display": DISPLAY,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_bbmodel(path, name, texture_path, cubes, origin):
    encoded = base64.b64encode(texture_path.read_bytes()).decode("ascii")
    elements = []
    outliner = []
    for cube in cubes:
        element_uuid = str(uuid.uuid4())
        outliner.append(element_uuid)
        faces = {}
        for face, region_name in cube.face_regions().items():
            x0, y0, x1, y1 = REGIONS[region_name]
            faces[face] = {"uv": [x0, y0, x1, y1], "texture": 0}
        elements.append({
            "name": cube.name,
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "light_emission": 0,
            "render_order": "default",
            "allow_mirror_modeling": True,
            "from": cube.frm,
            "to": cube.to,
            "autouv": 0,
            "color": 0,
            "origin": origin,
            "faces": faces,
            "type": "cube",
            "uuid": element_uuid,
        })
    data = {
        "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
        "name": name,
        "model_identifier": "",
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "timeline_setups": [],
        "unhandled_root_fields": {},
        "resolution": {"width": TEX_W, "height": TEX_H},
        "elements": elements,
        "outliner": outliner,
        "textures": [{
            "path": "",
            "name": texture_path.name,
            "folder": "block",
            "namespace": "barotrauma",
            "id": "0",
            "particle": True,
            "render_mode": "default",
            "visible": True,
            "mode": "bitmap",
            "saved": False,
            "uuid": str(uuid.uuid4()),
            "relative_path": "assets/barotrauma/textures/block/" + texture_path.name,
            "source": "data:image/png;base64," + encoded,
        }],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_blockstates():
    # 方块本体由 GeckoLib 渲染；blockstate 只负责状态与碰撞，模型用空模型。
    door = {"variants": {}}
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        # 注意：方块状态用的是 lower/middle/upper，几何分段名才是 bottom/middle/top。
        for part in ("lower", "middle", "upper"):
            for opened in (False, True):
                entry = {"model": "barotrauma:block/invisible"}
                if y:
                    entry["y"] = y
                door["variants"]["facing=%s,part=%s,open=%s"
                                  % (facing, part, "true" if opened else "false")] = entry
    (RES_STATE / "bulkhead_door.json").write_text(
        json.dumps(door, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    hatch = {"variants": {}}
    for half in ("top", "bottom"):
        for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            for part in HATCH_PARTS:
                for opened in (False, True):
                    entry = {"model": "barotrauma:block/invisible"}
                    if y:
                        entry["y"] = y
                    hatch["variants"]["facing=%s,half=%s,part=%s,open=%s"
                                      % (facing, half, part,
                                         "true" if opened else "false")] = entry
    (RES_STATE / "hatch.json").write_text(
        json.dumps(hatch, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _geo_cube(cube, x_offset=0, z_offset=0):
    faces = {}
    for face, region_name in cube.face_regions().items():
        x0, y0, x1, y1 = REGIONS[region_name]
        # Bedrock/GeckoLib 的 UV 是纹理像素坐标，64x64 图集直接用 0..64。
        faces[face] = {"uv": [x0, y0], "uv_size": [x1 - x0, y1 - y0]}
    return {
        "origin": [cube.frm[0] + x_offset, cube.frm[1], cube.frm[2] + z_offset],
        "size": [cube.to[0] - cube.frm[0], cube.to[1] - cube.frm[1],
                 cube.to[2] - cube.frm[2]],
        "uv": faces,
    }


def write_geo(path, identifier, bones, width, height, offset):
    data = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": identifier,
                "texture_width": TEX_W,
                "texture_height": TEX_H,
                "visible_bounds_width": width,
                "visible_bounds_height": height,
                "visible_bounds_offset": offset,
            },
            "bones": bones,
        }],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_door_geo():
    cubes = door_full(False)
    frame, left, right = [], [], []
    for cube in cubes:
        if cube.name.startswith("panel_left") or cube.name.startswith("rib_1") \
                or cube.name.startswith("rib_2"):
            left.append(cube)
        elif cube.name.startswith("panel_right") or cube.name.startswith("rib_3") \
                or cube.name.startswith("rib_4"):
            right.append(cube)
        else:
            frame.append(cube)
    bones = [
        {"name": "frame", "pivot": [0, 0, 0],
         "cubes": [_geo_cube(cube, -8, -8) for cube in frame]},
        {"name": "panel_left", "parent": "frame", "pivot": [-2.75, 24, -6.5],
         "cubes": [_geo_cube(cube, -8, -8) for cube in left]},
        {"name": "panel_right", "parent": "frame", "pivot": [2.75, 24, -6.5],
         "cubes": [_geo_cube(cube, -8, -8) for cube in right]},
    ]
    write_geo(RES_GEO / "block" / "bulkhead_door.geo.json", "geometry.bulkhead_door",
              bones, 2, 4, [0, 2, 0])


def write_hatch_geo():
    cubes = hatch_full(False)
    frame, left, right = [], [], []
    for cube in cubes:
        if cube.name.startswith("panel_left"):
            left.append(cube)
        elif cube.name.startswith("panel_right"):
            right.append(cube)
        elif cube.name.startswith("rib_"):
            if cube.frm[0] < 24:
                left.append(cube)
            else:
                right.append(cube)
        else:
            frame.append(cube)
    bones = [
        {"name": "frame", "pivot": [0, 0, 0],
         "cubes": [_geo_cube(cube, -24, -8) for cube in frame]},
        {"name": "panel_left", "parent": "frame", "pivot": [-11, 15, 0],
         "cubes": [_geo_cube(cube, -24, -8) for cube in left]},
        {"name": "panel_right", "parent": "frame", "pivot": [11, 15, 0],
         "cubes": [_geo_cube(cube, -24, -8) for cube in right]},
    ]
    write_geo(RES_GEO / "block" / "hatch.geo.json", "geometry.hatch", bones, 4, 2, [0, 1, 0])


def _animation(open_x):
    return {
        "format_version": "1.8.0",
        "animations": {
            "open": {
                "loop": False,
                "animation_length": 0.55,
                "bones": {
                    "panel_left": {"position": {"0.0": [0, 0, 0], "0.55": [-open_x, 0, 0]}},
                    "panel_right": {"position": {"0.0": [0, 0, 0], "0.55": [open_x, 0, 0]}},
                },
            },
            "closed": {
                "loop": False,
                "animation_length": 0.01,
                "bones": {
                    "panel_left": {"position": {"0.0": [0, 0, 0]}},
                    "panel_right": {"position": {"0.0": [0, 0, 0]}},
                },
            },
        },
    }


def write_animations():
    for name, open_x in (("bulkhead_door", 4.15), ("hatch", 11.6)):
        path = RES_ANIM / "block" / (name + ".animation.json")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(_animation(open_x), ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")


def write_invisible_model():
    data = {"textures": {"particle": "barotrauma:block/hatch"}}
    (RES_BLOCK / "invisible.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _mcp_parent(name, cube, is_hatch):
    if is_hatch:
        if name.startswith("panel_left"):
            return "panel_left"
        if name.startswith("panel_right"):
            return "panel_right"
        if name.startswith("rib_"):
            return "panel_left" if cube.frm[0] < 24 else "panel_right"
        return "frame"
    if name.startswith("panel_left") or name.startswith("rib_1") or name.startswith("rib_2"):
        return "panel_left"
    if name.startswith("panel_right") or name.startswith("rib_3") or name.startswith("rib_4"):
        return "panel_right"
    return "frame"


def _mcp_cube_ops(cubes, texture, is_hatch):
    ops = []
    shift_x = -24 if is_hatch else -8
    shift_z = -8
    for cube in cubes:
        parent = _mcp_parent(cube.name, cube, is_hatch)
        faces = {}
        visible = cube.face_regions()
        for face in FACES:
            if face in visible:
                x0, y0, x1, y1 = REGIONS[visible[face]]
                faces[face] = {"uv": [x0, y0, x1, y1], "texture": texture}
            else:
                faces[face] = {"texture": None}
        ops.append({
            "op": "add_cube",
            "name": cube.name,
            "from": [cube.frm[0] + shift_x, cube.frm[1], cube.frm[2] + shift_z],
            "to": [cube.to[0] + shift_x, cube.to[1], cube.to[2] + shift_z],
            "parent": parent,
            "box_uv": False,
            "faces": faces,
        })
    return ops


def write_mcp_operations():
    """写出 headless Blockbench MCP 的 create/edit 参数。"""
    specs = {
        "bulkhead_door": {
            "cubes": door_full(False),
            "texture": "bulkhead_door",
            "groups": [
                {"name": "frame", "origin": [0, 0, 0]},
                {"name": "panel_left", "origin": [-2.75, 24, -6.5], "parent": "frame"},
                {"name": "panel_right", "origin": [2.75, 24, -6.5], "parent": "frame"},
            ],
            "open_x": 4.15,
            "is_hatch": False,
        },
        "hatch": {
            "cubes": hatch_full(False),
            "texture": "hatch",
            "groups": [
                {"name": "frame", "origin": [0, 0, 0]},
                {"name": "panel_left", "origin": [-11, 15, 0], "parent": "frame"},
                {"name": "panel_right", "origin": [11, 15, 0], "parent": "frame"},
            ],
            "open_x": 11.6,
            "is_hatch": True,
        },
    }
    for name, spec in specs.items():
        create = {
            "file": name + ".bbmodel",
            "format": "geckolib_model",
            "name": name,
            "box_uv": False,
            "resolution": {"width": TEX_W, "height": TEX_H},
            "overwrite": True,
        }
        (ART_MODELS / ("mcp_%s_create.json" % name)).write_text(
            json.dumps(create, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        ops = [dict(op="add_group", **group) for group in spec["groups"]]
        ops.extend(_mcp_cube_ops(spec["cubes"], spec["texture"], spec["is_hatch"]))
        ops.append({"op": "set_model_properties", "name": name,
                    "model_identifier": "geometry." + name,
                    "resolution": {"width": TEX_W, "height": TEX_H}})
        ops.append({"op": "add_animation", "name": "open", "length": 0.55,
                    "loop": "once", "snapping": 24})
        ops.append({"op": "add_animation", "name": "closed", "length": 0.01,
                    "loop": "once", "snapping": 24})
        for bone, direction in (("panel_left", -1.0), ("panel_right", 1.0)):
            ops.append({"op": "set_keyframe", "animation": "closed", "bone": bone,
                        "channel": "position", "time": 0.0, "value": [0, 0, 0],
                        "interpolation": "linear"})
            ops.append({"op": "set_keyframe", "animation": "open", "bone": bone,
                        "channel": "position", "time": 0.0, "value": [0, 0, 0],
                        "interpolation": "catmullrom"})
            ops.append({"op": "set_keyframe", "animation": "open", "bone": bone,
                        "channel": "position", "time": 0.55,
                        "value": [direction * spec["open_x"], 0, 0],
                        "interpolation": "catmullrom"})
        edit = {"file": name + ".bbmodel", "operations": ops}
        (ART_MODELS / ("mcp_%s_edit.json" % name)).write_text(
            json.dumps(edit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # 纹理放进 root，供 bbmodel_add_texture 读取
        source = RES_TEX / (name + ".png")
        target = ART_MODELS / (name + "_tex.png")
        target.write_bytes(source.read_bytes())


def main():
    for path in (RES_BLOCK, RES_ITEM, RES_STATE, RES_TEX, RES_ITEM_TEX,
                 RES_GEO / "block", RES_ANIM / "block", ART_MODELS):
        path.mkdir(parents=True, exist_ok=True)

    hatch_tex = make_atlas(47)
    door_tex = make_atlas(83)
    hatch_tex.save(str(RES_TEX / "hatch.png"))
    door_tex.save(str(RES_TEX / "bulkhead_door.png"))
    make_hatch_icon().save(str(RES_ITEM_TEX / "hatch.png"))
    make_door_icon().save(str(RES_ITEM_TEX / "bulkhead_door.png"))

    for part in DOOR_PARTS:
        for opened, suffix in ((False, "closed"), (True, "open")):
            write_model(RES_BLOCK / ("bulkhead_door_%s_%s.json" % (part, suffix)),
                        "bulkhead_door", door_segment(part, opened))

    for part in HATCH_PARTS:
        for half in ("top", "bottom"):
            for opened, suffix in ((False, "closed"), (True, "open")):
                cubes = hatch_segments(opened, bottom=(half == "bottom"))[part]
                write_model(RES_BLOCK / ("hatch_%s_%s_%s.json" % (part, half, suffix)),
                            "hatch", cubes)

    write_blockstates()
    write_door_geo()
    write_hatch_geo()
    write_animations()
    write_invisible_model()
    write_mcp_operations()

    for name in ("hatch", "bulkhead_door"):
        (RES_ITEM / (name + ".json")).write_text(
            json.dumps({"parent": "minecraft:item/generated",
                        "textures": {"layer0": "barotrauma:item/" + name}},
                       ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    write_bbmodel(ART_MODELS / "bulkhead_door_3x1.bbmodel", "bulkhead_door_3x1",
                  RES_TEX / "bulkhead_door.png", door_full(False), [8, 24, 8])
    write_bbmodel(ART_MODELS / "hatch_3x1.bbmodel", "hatch_3x1",
                  RES_TEX / "hatch.png", hatch_full(False), [24, 8, 8])

    print("wrote 2 block atlases, 2 item icons, 18 reference models, 2 blockstates, "
          "2 item models, 2 geo, 2 animation, 2 bbmodel")


if __name__ == "__main__":
    main()
