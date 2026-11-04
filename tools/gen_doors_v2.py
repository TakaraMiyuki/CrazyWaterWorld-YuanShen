#!/usr/bin/env python3
"""1x2 竖向舱壁门 + 1x3 水平地板舱口（薄板、单扇滑入墙内）。

- 竖向门：1 格宽 × 2 格高，满宽 1px 薄板，上半段中间是带玻璃的观察窗。
- 水平舱口：3 格宽 × 1 格深，1px 厚贴在地板顶面，整块沿长轴滑进旁边的地板，
  没有窗也没有玻璃（上下舱之间通行用）。

输出：
- textures/item/{bulkhead_door,hatch}.png（16x16 图标）
- blockstates/{bulkhead_door,hatch}.json（模型统一 invisible，实际由 GeckoLib 渲染）
- art/models/mcp_*_{create,edit}.json（给 blockbench-mcp-headless 的参数）
- art/models/*_tex.png（给 bbmodel_add_texture 用）
- 竖向门图集里补一块 glass 区域（观察窗玻璃）

模型坐标以 0 为中心，GeoBlockRenderer 会自动 translate(0.5,0,0.5)；
竖向门 z=-0.5..0.5、y=0..32；水平舱口 x=-24..24、z=-8..8、y=15..17。
"""
from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "src" / "main" / "resources" / "assets" / "barotrauma"
RES_BLOCK = ASSETS / "models" / "block"
RES_ITEM = ASSETS / "models" / "item"
RES_STATE = ASSETS / "blockstates"
RES_TEX = ASSETS / "textures" / "block"
RES_ITEM_TEX = ASSETS / "textures" / "item"
ART_MODELS = REPO / "art" / "models"

FACES = ("north", "south", "east", "west", "up", "down")
REGIONS = {
    "frame": (0, 0, 16, 16),
    "panel": (16, 0, 32, 16),
    "hazard": (32, 0, 48, 16),
    "dark": (48, 0, 64, 16),
    "button": (0, 16, 16, 32),
    "rib": (16, 16, 32, 32),
    "frame_light": (32, 16, 48, 32),
    "dark2": (48, 16, 64, 32),
    # 观察窗玻璃：中间抠空（cutout 下就是透明的），只留一圈窗框和一道高光。
    "glass": (0, 32, 16, 48),
}
EDGE = {"east": "frame", "west": "frame", "up": "frame", "down": "frame"}


class Cube:
    def __init__(self, name, frm, to, tex="panel", face_tex=None, skip=None, pixel_uv=False,
                 parent="panel"):
        self.name = name
        self.parent = parent
        self.frm = list(frm)
        self.to = list(to)
        self.tex = tex
        self.face_tex = dict(face_tex or {})
        self.skip = set(skip or ())
        # pixel_uv=True：按面在模型里的大小取图集子矩形（1 模型像素 = 1 贴图像素），
        # 不会把整块 16×16 拉到一个 2px 高的面上（那样每条边框都会缩成一道"缝"）。
        self.pixel_uv = pixel_uv

    def face_texture(self, face):
        if face in self.skip:
            return None
        return self.face_tex.get(face, self.tex)

    def face_size(self, face):
        dx = abs(self.to[0] - self.frm[0])
        dy = abs(self.to[1] - self.frm[1])
        dz = abs(self.to[2] - self.frm[2])
        if face in ("north", "south"):
            return dx, dy
        if face in ("east", "west"):
            return dz, dy
        return dx, dz


def door_cubes():
    edge = dict(EDGE)
    # 门板 12px 宽，两侧各 2px 是固定的门框（右键门框开关门，见 BulkheadDoorBlock）
    return [
        # 下半段：底部警示条 + 面板
        Cube("lower_hazard", (-6, 2, -0.5), (6, 4, 0.5), "hazard", edge, {"up"}, pixel_uv=True),
        Cube("lower_panel", (-6, 4, -0.5), (6, 16, 0.5), "panel", edge, {"up"}, pixel_uv=True),
        # 上半段：围着观察窗的四块 + 中间横梁
        Cube("upper_left", (-6, 16, -0.5), (-3, 30, 0.5), "panel", edge, {"down"}, pixel_uv=True),
        Cube("upper_right", (3, 16, -0.5), (6, 30, 0.5), "panel", edge, {"down"}, pixel_uv=True),
        Cube("upper_bottom", (-3, 16, -0.5), (3, 18, 0.5), "panel", edge, {"down"}, pixel_uv=True),
        Cube("upper_top", (-3, 27, -0.5), (3, 30, 0.5), "panel", edge, {"down"}, pixel_uv=True),
        Cube("window_bar", (-3, 22, -0.5), (3, 23, 0.5), "frame_light", edge, {"down"},
             pixel_uv=True),
        # 观察窗玻璃：0.5px 薄片嵌在窗洞正中（整块映射，窗框才完整）
        Cube("window_glass", (-3, 18, -0.25), (3, 27, 0.25), "glass"),
    ]


def door_frame_cubes():
    # 固定门框：左右立柱 + 上下槛，厚 2px；不参与滑动动画
    return [
        Cube("frame_left", (-8, 0, -1), (-6, 32, 1), "frame", parent="frame"),
        Cube("frame_right", (6, 0, -1), (8, 32, 1), "frame", parent="frame"),
        Cube("frame_bottom", (-6, 0, -1), (6, 2, 1), "frame", parent="frame"),
        Cube("frame_top", (-6, 30, -1), (6, 32, 1), "frame", parent="frame"),
    ]


def hatch_cubes():
    # 上下舱之间的水平舱口：3 格宽 × 1 格深，1px 厚，位于方块正中（y=7.5..8.5）。
    # 开的时候整块沿长轴滑进旁边的地板里；外面一圈固定的舱口边框留着，
    # 右键边框就能把门关回来（门洞中间是空的，能直接打后面的东西）。
    return [
        Cube("floor_hazard_left", (-23, 7.5, -7), (-21, 8.5, 7), "hazard",
             {"up": "hazard", "down": "dark", "north": "dark", "south": "dark"}),
        Cube("floor_panel", (-21, 7.5, -7), (21, 8.5, 7), "panel",
             {"up": "rib", "down": "panel", "north": "dark", "south": "dark"}),
        Cube("floor_hazard_right", (21, 7.5, -7), (23, 8.5, 7), "hazard",
             {"up": "hazard", "down": "dark", "north": "dark", "south": "dark"}),
        # 板面上的小拉手：高出板面 1px，跟着门板一起滑
        Cube("floor_latch", (-1, 8.5, -2), (1, 9.5, 2), "frame_light",
             {"up": "frame_light", "down": "dark", "north": "frame", "south": "frame",
              "east": "frame", "west": "frame"}),
    ]


def hatch_frame_cubes():
    # 舱口固定的外框：1px 宽的边条，滑门时不动，开门后右键它关门
    rim = {"up": "frame", "down": "frame_light", "north": "frame", "south": "frame",
           "east": "frame", "west": "frame"}
    return [
        Cube("rim_north", (-24, 7.5, -8), (24, 8.5, -7), "frame", rim, parent="frame"),
        Cube("rim_south", (-24, 7.5, 7), (24, 8.5, 8), "frame", rim, parent="frame"),
        Cube("rim_west", (-24, 7.5, -7), (-23, 8.5, 7), "frame", rim, parent="frame"),
        Cube("rim_east", (23, 7.5, -7), (24, 8.5, 7), "frame", rim, parent="frame"),
    ]


def make_door_icon():
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    # 细长门板
    d.rectangle([5, 1, 10, 14], fill=(38, 44, 47, 255), outline=(12, 15, 16, 255))
    d.rectangle([6, 3, 9, 12], fill=(52, 59, 63, 255))
    # 观察窗（中间上方）
    d.rectangle([7, 3, 8, 6], fill=(120, 190, 205, 255))
    d.point((7, 3), fill=(220, 245, 250, 255))
    # 底部警示条
    for x in range(5, 11):
        d.point((x, 13), fill=(198, 158, 42, 255) if (x % 4) < 2 else (22, 22, 20, 255))
        d.point((x, 14), fill=(198, 158, 42, 255) if (x % 4) < 2 else (22, 22, 20, 255))
    return image


def make_hatch_icon():
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    # 俯视的水平舱口板（3:1，上下舱之间用）：中间钢板 + 两端警示条 + 拉手
    d.rectangle([1, 5, 14, 10], fill=(38, 44, 47, 255), outline=(12, 15, 16, 255))
    d.rectangle([3, 6, 12, 9], fill=(52, 59, 63, 255))
    for x in range(2, 13):
        d.point((x, 6), fill=(74, 82, 87, 255))
        d.point((x, 9), fill=(28, 33, 36, 255))
    for x in (1, 2, 13, 14):
        for y in range(5, 11):
            d.point((x, y), fill=(198, 158, 42, 255) if (x + y) % 4 < 2 else (22, 22, 20, 255))
    # 中间的拉手
    d.rectangle([7, 7, 8, 8], fill=(150, 196, 206, 255), outline=(20, 26, 28, 255))
    return image


def paint_glass_region(path):
    """把观察窗玻璃画进 64×64 图集。

    cutout 渲染下 alpha 只有 0/255 两档，纯半透明会被画成实心或者直接消失。
    所以中间用稀疏的抖动点 + 两道斜高光表示玻璃，其余留透明——远看像有一层
    带反光的玻璃，近看又能透过窗看到对面。
    """
    if not path.exists():
        return
    image = Image.open(path).convert("RGBA")
    x0, y0, x1, y1 = REGIONS["glass"]
    for y in range(y0, y1):
        for x in range(x0, x1):
            image.putpixel((x, y), (0, 0, 0, 0))
    light = (168, 214, 224, 255)
    dark = (78, 112, 121, 255)
    shine = (226, 246, 252, 255)
    tint = (128, 176, 190, 255)
    for i in range(16):
        image.putpixel((x0 + i, y0), light)
        image.putpixel((x0 + i, y1 - 1), dark)
        image.putpixel((x0, y0 + i), light)
        image.putpixel((x1 - 1, y0 + i), dark)
    # 玻璃面：极稀疏的反光点（约 8%）+ 两道斜高光，留出能看穿的透明区
    for y in range(y0 + 1, y1 - 1):
        for x in range(x0 + 1, x1 - 1):
            if ((x - x0) * 3 + (y - y0)) % 8 == 0:
                image.putpixel((x, y), tint)
    for i in range(3, 13):
        image.putpixel((x0 + i, y0 + i - 1), shine)
        image.putpixel((x0 + i, y0 + i), shine)
    image.save(path)


def write_blockstates():
    door = {"variants": {}}
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for part in ("lower", "upper"):
            for opened in (False, True):
                entry = {"model": "barotrauma:block/invisible"}
                if y:
                    entry["y"] = y
                door["variants"]["facing=%s,part=%s,open=%s"
                                  % (facing, part, "true" if opened else "false")] = entry
    (RES_STATE / "bulkhead_door.json").write_text(
        json.dumps(door, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    hatch = {"variants": {}}
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for part in ("left", "middle", "right"):
            for opened in (False, True):
                entry = {"model": "barotrauma:block/invisible"}
                if y:
                    entry["y"] = y
                hatch["variants"]["facing=%s,part=%s,open=%s"
                                  % (facing, part, "true" if opened else "false")] = entry
    (RES_STATE / "hatch.json").write_text(
        json.dumps(hatch, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _mcp_cube_ops(cubes, texture, parent="panel"):
    ops = []
    for cube in cubes:
        faces = {}
        for face in FACES:
            tex = cube.face_texture(face)
            if tex is None:
                faces[face] = {"texture": None}
                continue
            x0, y0, x1, y1 = REGIONS[tex]
            region_w = x1 - x0
            region_h = y1 - y0
            if cube.pixel_uv:
                w, h = cube.face_size(face)
                # 只取子矩形；超过区域大小就退回整块（不能采样到别的区域去）。
                if w <= region_w and h <= region_h and w > 0 and h > 0:
                    faces[face] = {"uv": [x0, y0, x0 + w, y0 + h], "texture": texture}
                    continue
            faces[face] = {"uv": [x0, y0, x1, y1], "texture": texture}
        ops.append({
            "op": "add_cube",
            "name": cube.name,
            "from": cube.frm,
            "to": cube.to,
            "parent": parent,
            "box_uv": False,
            "faces": faces,
        })
    return ops


def write_mcp_operations():
    specs = {
        "bulkhead_door": {
            "groups": [("panel", door_cubes()), ("frame", door_frame_cubes())],
            "texture": "bulkhead_door",
            "open_x": 16.0,
            "length": 0.6,
        },
        "hatch": {
            "groups": [("panel", hatch_cubes()), ("frame", hatch_frame_cubes())],
            "texture": "hatch",
            "open_x": 48.0,
            "length": 0.8,
        },
    }
    for name, spec in specs.items():
        create = {
            "file": name + ".bbmodel",
            # Blockbench 4.12 没有 geckolib_model 插件；用内置 Bedrock Entity 格式，
            # 导出的 geo/animation 仍然能被 GeckoLib 读取。
            "format": "bedrock",
            "name": name,
            "box_uv": False,
            "resolution": {"width": 64, "height": 64},
            "overwrite": True,
        }
        (ART_MODELS / ("mcp_%s_create.json" % name)).write_text(
            json.dumps(create, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        ops = []
        for group, cubes in spec["groups"]:
            ops.append({"op": "add_group", "name": group, "origin": [0, 0, 0]})
            ops.extend(_mcp_cube_ops(cubes, spec["texture"], group))
        ops.append({"op": "set_model_properties", "name": name,
                    "model_identifier": "geometry." + name,
                    "resolution": {"width": 64, "height": 64}})
        ops.append({"op": "add_animation", "name": "open",
                    "length": spec["length"], "loop": "once", "snapping": 24})
        ops.append({"op": "add_animation", "name": "closed",
                    "length": 0.01, "loop": "once", "snapping": 24})
        ops.append({"op": "set_keyframe", "animation": "closed", "bone": "panel",
                    "channel": "position", "time": 0.0, "value": [0, 0, 0],
                    "interpolation": "linear"})
        ops.append({"op": "set_keyframe", "animation": "open", "bone": "panel",
                    "channel": "position", "time": 0.0, "value": [0, 0, 0],
                    "interpolation": "catmullrom"})
        ops.append({"op": "set_keyframe", "animation": "open", "bone": "panel",
                    "channel": "position", "time": spec["length"],
                    "value": [spec["open_x"], 0, 0], "interpolation": "catmullrom"})
        edit = {"file": name + ".bbmodel", "operations": ops}
        (ART_MODELS / ("mcp_%s_edit.json" % name)).write_text(
            json.dumps(edit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (ART_MODELS / (name + "_tex.png")).write_bytes(
            (RES_TEX / (name + ".png")).read_bytes())


def main():
    for path in (RES_BLOCK, RES_ITEM, RES_STATE, RES_TEX, RES_ITEM_TEX, ART_MODELS):
        path.mkdir(parents=True, exist_ok=True)
    make_door_icon().save(str(RES_ITEM_TEX / "bulkhead_door.png"))
    make_hatch_icon().save(str(RES_ITEM_TEX / "hatch.png"))
    paint_glass_region(RES_TEX / "bulkhead_door.png")
    write_blockstates()
    for name in ("hatch", "bulkhead_door"):
        (RES_ITEM / (name + ".json")).write_text(
            json.dumps({"parent": "minecraft:item/generated",
                        "textures": {"layer0": "barotrauma:item/" + name}},
                       ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_mcp_operations()
    print("wrote 1x2 door / 3x1 horizontal door blockstates, icons, MCP operations")


if __name__ == "__main__":
    main()
