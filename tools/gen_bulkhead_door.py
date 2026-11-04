#!/usr/bin/env python3
"""Rebuild ONLY the vertical bulkhead door (model, atlas, 16x16 item icon) plus optional
real-geometry orthographic previews.
Run: python -I tools/gen_bulkhead_door.py --previews
No imports from the legacy all-door generator, builds, or runtime-animation writes.
"""
import argparse
import base64
import hashlib
import io
import json
import math
from pathlib import Path
import uuid
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'art/models'
ASSETS = ROOT / 'src/main/resources/assets/barotrauma'
NS = uuid.UUID('88332fa1-0e0d-4bbf-a006-5b00b3055072')
FACES = ('north', 'south', 'east', 'west', 'up', 'down')
PALETTE = [(0, 0, 0, 0), (25, 32, 38, 255), (48, 62, 72, 255),
           (72, 91, 103, 255), (112, 134, 145, 255), (164, 132, 50, 255),
           (148, 202, 211, 255), (216, 239, 241, 255)]


def uid(name):
    return str(uuid.uuid5(NS, name))


def texture():
    im = Image.new('RGBA', (64, 64), PALETTE[2])
    d = ImageDraw.Draw(im)
    # Frame strip and full 12 x 28 panel field: intentionally broad, quiet pixels.
    d.rectangle((0, 0, 1, 31), fill=PALETTE[2])
    d.rectangle((1, 1, 1, 30), fill=PALETTE[4])
    d.rectangle((16, 0, 27, 27), fill=PALETTE[3])
    d.rectangle((16, 0, 16, 27), fill=PALETTE[1])
    d.rectangle((27, 0, 27, 27), fill=PALETTE[2])
    d.rectangle((17, 14, 26, 24), fill=PALETTE[2])
    d.rectangle((17, 15, 26, 23), fill=PALETTE[3])
    d.rectangle((17, 26, 26, 27), fill=PALETTE[1])
    for x in (17, 21, 25):
        d.rectangle((x, 26, min(x + 1, 26), 27), fill=PALETTE[5])
    # Each flat swatch is a full texel, sampled by all small edge faces.
    for i, color in enumerate(PALETTE):
        d.rectangle((i * 4, 56, i * 4 + 3, 59), fill=color)
    d.rectangle((0, 40, 5, 47), fill=PALETTE[0])
    d.rectangle((1, 41, 2, 41), fill=PALETTE[7])
    d.rectangle((4, 45, 4, 46), fill=PALETTE[6])
    return im


def solid(i):
    return [i * 4, 56, i * 4 + 1, 57]


def make_icon():
    """16x16 inventory icon: the same door read front-on, one texel per unit.

    Front view like a vanilla door icon (vanilla draws a 2-high door flat, not
    isometric), with the real 6 x 8 opening scaled to 6 x 5 and the glass left
    transparent so the slot background shows through it.
    """
    im = Image.new('RGBA', (16, 16), PALETTE[0])
    d = ImageDraw.Draw(im)
    # Fixed frame: 1 texel per side, slightly darker than the panel.
    d.rectangle((3, 0, 12, 15), fill=PALETTE[2])
    d.rectangle((3, 0, 3, 15), fill=PALETTE[1])
    d.rectangle((12, 0, 12, 15), fill=PALETTE[1])
    d.rectangle((3, 0, 12, 0), fill=PALETTE[1])
    d.rectangle((3, 15, 12, 15), fill=PALETTE[1])
    # Sliding panel field, with one shade column so the plate reads as volume.
    d.rectangle((4, 1, 11, 14), fill=PALETTE[3])
    d.rectangle((11, 1, 11, 14), fill=PALETTE[2])
    # Rectangular window trim around a transparent 6 x 5 pane.
    d.rectangle((4, 2, 11, 2), fill=PALETTE[1])
    d.rectangle((4, 8, 11, 8), fill=PALETTE[1])
    d.rectangle((4, 2, 4, 8), fill=PALETTE[1])
    d.rectangle((11, 2, 11, 8), fill=PALETTE[1])
    d.rectangle((5, 3, 10, 7), fill=PALETTE[0])
    for x, y in ((6, 3), (7, 4)):
        im.putpixel((x, y), PALETTE[7])
    for x, y in ((8, 5), (9, 6), (10, 3), (5, 7)):
        im.putpixel((x, y), PALETTE[6])
    # Reinforcement rib: a highlight row over a shadow row reads as a raised bar
    # at this size; a flat light slab would swallow the lower third of the icon.
    d.rectangle((5, 11, 10, 11), fill=PALETTE[4])
    d.rectangle((5, 12, 10, 12), fill=PALETTE[2])
    # Hazard strip: four equal 2-texel blocks. The dark halves are steel, not
    # frame black, so the strip does not merge into the right-hand frame.
    d.rectangle((4, 13, 5, 14), fill=PALETTE[5])
    d.rectangle((6, 13, 7, 14), fill=PALETTE[2])
    d.rectangle((8, 13, 9, 14), fill=PALETTE[5])
    d.rectangle((10, 13, 11, 14), fill=PALETTE[2])
    return im


def validate_icon(icon):
    assert icon.size == (16, 16)
    assert set(icon.getchannel('A').getdata()) == {0, 255}
    assert set(icon.getdata()) <= set(PALETTE)
    # The pane really is a hole: 6 x 5 opening minus 6 reflection texels.
    hole = [icon.getpixel((x, y)) for x in range(5, 11) for y in range(3, 8)]
    assert sum(c[3] == 0 for c in hole) == 24
    assert icon.getpixel((0, 0))[3] == 0 and icon.getpixel((15, 15))[3] == 0


def geometry():
    cubes = []
    def add(name, lo, hi, parent='panel', color=3, field=False, glass=False, strip=False):
        faces = {f: {'uv': solid(2), 'texture': 0} for f in FACES}
        for f in ('north', 'south'):
            uv = solid(color)
            if field:
                uv = [16 + lo[0] + 6, 30 - hi[1], 16 + hi[0] + 6, 30 - lo[1]]
            if strip:
                uv = [0, 0, 2, 32]
            if glass:
                uv = [0, 40, 6, 48]
            faces[f] = {'uv': uv, 'texture': 0}
        if glass:
            for f in ('east', 'west', 'up', 'down'):
                faces[f] = {'uv': solid(0), 'texture': 0}
        cubes.append({'name': name, 'type': 'cube', 'uuid': uid(name),
                      'from': list(lo), 'to': list(hi), 'origin': [0, 0, 0],
                      'rotation': [0, 0, 0], 'box_uv': False, 'autouv': 0,
                      'rescale': False, 'faces': faces, '_parent': parent})
    add('frame_left', (-8, 0, -1), (-6, 32, 1), 'frame', strip=True)
    add('frame_right', (6, 0, -1), (8, 32, 1), 'frame', strip=True)
    add('frame_bottom', (-6, 0, -1), (6, 2, 1), 'frame', color=2)
    add('frame_top', (-6, 30, -1), (6, 32, 1), 'frame', color=4)
    add('panel_left', (-6, 2, -.5), (-3, 30, .5), field=True)
    add('panel_right', (3, 2, -.5), (6, 30, .5), field=True)
    add('panel_below_window', (-3, 2, -.5), (3, 19, .5), field=True)
    add('panel_above_window', (-3, 27, -.5), (3, 30, .5), field=True)
    # Four rectangular trim bars remain strictly OUTSIDE the 6 x 8 opening.
    add('window_trim_left', (-4, 18, -.625), (-3, 28, .625), color=1)
    add('window_trim_right', (3, 18, -.625), (4, 28, .625), color=1)
    add('window_trim_bottom', (-3, 18, -.625), (3, 19, .625), color=1)
    add('window_trim_top', (-3, 27, -.625), (3, 28, .625), color=1)
    add('window_glass', (-3, 19, -.25), (3, 27, .25), glass=True)
    add('lower_rib_front', (-5, 7, -1), (5, 9, -.5), color=4)
    add('lower_rib_back', (-5, 7, .5), (5, 9, 1), color=4)
    return cubes


def make_model(cubes, png):
    groups = [{'name': n, 'uuid': uid(n), 'origin': [0, 0, 0],
               'rotation': [0, 0, 0], 'export': True, 'visibility': True,
               'autouv': 0, 'shade': True} for n in ('panel', 'frame')]
    animations = []
    for name, length, positions in [('open', .6, [(0, 0), (.6, 16)]),
                                    ('closed', .01, [(0, 0)])]:
        keys = [{'channel': 'position', 'data_points': [{'x': str(x), 'y': '0', 'z': '0'}],
                 'uuid': uid(name + '/key/' + str(t)), 'time': t,
                 'color': -1, 'interpolation': 'catmullrom' if name == 'open' else 'linear'}
                for t, x in positions]
        animations.append({'uuid': uid('animation/' + name), 'name': name,
                           'loop': 'once', 'override': False, 'length': length,
                           'snapping': 24, 'animators': {uid('panel'): {
                               'name': 'panel', 'type': 'bone', 'keyframes': keys}}})
    return {'meta': {'format_version': '5.0', 'model_format': 'bedrock', 'box_uv': False},
            'name': 'bulkhead_door', 'model_identifier': 'geometry.bulkhead_door',
            'resolution': {'width': 64, 'height': 64}, 'visible_box': [4, 3, 1],
            'elements': [{k: v for k, v in c.items() if k != '_parent'} for c in cubes],
            'groups': groups, 'outliner': [{'uuid': g['uuid'], 'isOpen': True,
                'children': [c['uuid'] for c in cubes if c['_parent'] == g['name']]} for g in groups],
            'textures': [{'uuid': uid('texture'), 'name': 'bulkhead_door', 'id': '0',
                          'width': 64, 'height': 64, 'uv_width': 64, 'uv_height': 64,
                          'internal': True, 'render_mode': 'default', 'render_sides': 'double',
                          'source': 'data:image/png;base64,' + base64.b64encode(png).decode('ascii')}],
            'animations': animations}


def make_geo(cubes):
    bones = []
    # Bedrock's X axis and east/west faces mirror the Blockbench source axis.
    for parent in ('panel', 'frame'):
        out = []
        for c in cubes:
            if c['_parent'] != parent:
                continue
            lo, hi = c['from'], c['to']
            uv = {}
            for f, value in c['faces'].items():
                u, v, u1, v1 = value['uv']
                target = {'east': 'west', 'west': 'east'}.get(f, f)
                uv[target] = {'uv': [u, v], 'uv_size': [u1 - u, v1 - v]}
                if f in ('up', 'down'):
                    uv[target] = {'uv': [u1, v1], 'uv_size': [u - u1, v - v1]}
            out.append({'origin': [-hi[0], lo[1], lo[2]],
                        'size': [hi[i] - lo[i] for i in range(3)], 'uv': uv})
        bones.append({'name': parent, 'pivot': [0, 0, 0], 'cubes': out})
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': 'geometry.bulkhead_door', 'texture_width': 64,
                        'texture_height': 64, 'visible_bounds_width': 4,
                        'visible_bounds_height': 3.5, 'visible_bounds_offset': [0, 1.25, 0]},
        'bones': bones}]}


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def render(model, atlas, yaw, pitch, opened=False):
    """Orthographic per-face ray/plane renderer, nearest texels, binary cutout + z buffer.
    Reads the actual editable model. Preview walls are local to this function only.
    """
    import copy
    cubes = copy.deepcopy(model['elements'])
    if opened:
        panel = next(o['children'] for o in model['outliner'] if o['uuid'] == uid('panel'))
        animator = model['animations'][0]['animators'][uid('panel')]
        position = animator['keyframes'][-1]['data_points'][0]
        displacement = [float(position[a]) for a in ('x', 'y', 'z')]
        for c in cubes:
            if c['uuid'] in panel:
                for k in ('from', 'to'):
                    c[k] = [c[k][i] + displacement[i] for i in range(3)]
        # Opaque wall pocket front/back skins surround the slid panel; a small
        # cutaway in the front skin reveals its far end inside the cavity.
        for name, lo, hi, color in [
            ('pocket_back', [8, 0, 1.5], [25, 32, 3], 2),
            ('pocket_front', [8, 0, -3], [19, 32, -1.5], 2),
            ('pocket_top', [8, 30, -3], [25, 32, 3], 3),
            ('pocket_floor', [8, 0, -3], [25, 2, 3], 3),
            ('pocket_end', [24, 2, -3], [25, 30, 3], 4)]:
            cubes.append({'name': name, 'from': lo, 'to': hi,
                          'faces': {f: {'uv': solid(color), 'texture': 0} for f in FACES}})
    w, h = (1000, 800) if opened else (640, 800)
    scale = 20
    center = [8 if opened else 0, 16, 0]
    yaw, pitch = math.radians(yaw), math.radians(pitch)
    right = [math.cos(yaw), 0, math.sin(yaw)]
    up = [-math.sin(yaw) * math.sin(pitch), math.cos(pitch), math.cos(yaw) * math.sin(pitch)]
    toward = [math.sin(yaw) * math.cos(pitch), math.sin(pitch), -math.cos(yaw) * math.cos(pitch)]
    im = Image.new('RGBA', (w, h), (20, 25, 31, 255))
    pixels = im.load()
    tex = atlas.load()
    depth = [-float('inf')] * (w * h)
    # Face normal, fixed axis, horizontal texture axis, vertical texture axis.
    definitions = {'north': (2, -1, 0, 1), 'south': (2, 1, 0, 1),
                   'east': (0, 1, 2, 1), 'west': (0, -1, 2, 1),
                   'up': (1, 1, 0, 2), 'down': (1, -1, 0, 2)}
    for c in cubes:
        lo, hi = c['from'], c['to']
        for f, (axis, sign, ua, va) in definitions.items():
            if toward[axis] * sign <= 1e-7:
                continue
            fixed = hi[axis] if sign > 0 else lo[axis]
            corners = []
            for u in (lo[ua], hi[ua]):
                for v in (lo[va], hi[va]):
                    p = [0, 0, 0]
                    p[axis], p[ua], p[va] = fixed, u, v
                    rel = [p[i] - center[i] for i in range(3)]
                    corners.append((w / 2 + scale * dot(rel, right), h / 2 - scale * dot(rel, up)))
            xmin, xmax = max(0, math.floor(min(p[0] for p in corners))), min(w - 1, math.ceil(max(p[0] for p in corners)))
            ymin, ymax = max(0, math.floor(min(p[1] for p in corners))), min(h - 1, math.ceil(max(p[1] for p in corners)))
            uv = c['faces'][f]['uv']
            for y in range(ymin, ymax + 1):
                sy = (h / 2 - y - .5) / scale
                for x in range(xmin, xmax + 1):
                    sx = (x + .5 - w / 2) / scale
                    ray = [center[i] + sx * right[i] + sy * up[i] for i in range(3)]
                    t = (fixed - ray[axis]) / toward[axis]
                    idx = y * w + x
                    if t <= depth[idx]:
                        continue
                    u, v = ray[ua] + t * toward[ua], ray[va] + t * toward[va]
                    if not (lo[ua] <= u < hi[ua] and lo[va] <= v < hi[va]):
                        continue
                    fu = (u - lo[ua]) / (hi[ua] - lo[ua])
                    fv = (hi[va] - v) / (hi[va] - lo[va])
                    # Reverse back-face U as in the editable cube face convention.
                    if f in ('south', 'west'):
                        fu = 1 - fu
                    tx = min(63, max(0, int(uv[0] + fu * (uv[2] - uv[0]))))
                    ty = min(63, max(0, int(uv[1] + fv * (uv[3] - uv[1]))))
                    color = tex[tx, ty]
                    if color[3] < 128:
                        continue
                    pixels[x, y] = color
                    depth[idx] = t
    d = ImageDraw.Draw(im)
    d.text((20, 18), 'OPEN +16X / WALL POCKET CUTAWAY (PREVIEW ONLY)' if opened else
           'BULKHEAD / REAL GEOMETRY / NEAREST TEXELS', fill=PALETTE[7])
    if opened:
        d.text((20, h - 30), 'Left: clear doorway   Right: panel inside side-wall pocket', fill=PALETTE[7])
    return im


def validate(model, geo, atlas):
    elements = model['elements']
    assert len(elements) == 15
    definitions = [e['uuid'] for e in elements] + [g['uuid'] for g in model['groups']]
    definitions += [t['uuid'] for t in model['textures']]
    for a in model['animations']:
        definitions.append(a['uuid'])
        assert set(a['animators']) == {uid('panel')}
        definitions += [k['uuid'] for animator in a['animators'].values() for k in animator['keyframes']]
    assert len(definitions) == len(set(definitions))
    assert all(str(uuid.UUID(s)) == s and uuid.UUID(s).int for s in definitions)
    assert sorted(ch for o in model['outliner'] for ch in o['children']) == sorted(e['uuid'] for e in elements)
    for c in elements:
        assert c['rotation'] == [0, 0, 0]
        assert all(a < b for a, b in zip(c['from'], c['to']))
        for face in c['faces'].values():
            assert all(0 <= x <= 64 for x in face['uv'])
        if c['name'] != 'window_glass':
            lo, hi = c['from'], c['to']
            assert not (lo[0] < 3 and hi[0] > -3 and lo[1] < 27 and hi[1] > 19), c['name']
    assert atlas.size == (64, 64) and len(set(atlas.getdata())) == 8
    assert set(atlas.getchannel('A').getdata()) == {0, 255}
    assert sum(atlas.getpixel((x, y))[3] == 0 for x in range(6) for y in range(40, 48)) == 44
    for bone in geo['minecraft:geometry'][0]['bones']:
        for c in bone['cubes']:
            assert 'rotation' not in c
            for f in c['uv'].values():
                assert all(0 <= f['uv'][i] + f['uv_size'][i] <= 64 for i in range(2))
    assert model['animations'][0]['animators'][uid('panel')]['keyframes'][-1]['data_points'][0]['x'] == '16'
    print('PASS: 15 axis-aligned cuboids; unique valid UUID definitions and references; 6x8 unobstructed glass opening; 44/48 transparent glass texels; 8 RGBA palette entries; UV bounds; editable +16X animation')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--previews', action='store_true')
    args = parser.parse_args()
    protected = [p for p in (ASSETS / 'animations').rglob('*.json')]
    protected += [ART / 'bulkhead_door.animation.json']
    before = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
    atlas = texture()
    buf = io.BytesIO()
    atlas.save(buf, format='PNG')
    png = buf.getvalue()
    cubes = geometry()
    model, geo = make_model(cubes, png), make_geo(cubes)
    validate(model, geo, atlas)
    icon = make_icon()
    validate_icon(icon)
    ibuf = io.BytesIO()
    icon.save(ibuf, format='PNG')
    ipng = ibuf.getvalue()
    outputs = {ART / 'bulkhead_door.bbmodel': json.dumps(model, indent=2).encode() + b'\n',
               ART / 'bulkhead_door.geo.json': json.dumps(geo, indent=2).encode() + b'\n',
               ART / 'bulkhead_door_tex.png': png,
               ART / 'bulkhead_door_icon.png': ipng}
    outputs[ASSETS / 'geo/block/bulkhead_door.geo.json'] = outputs[ART / 'bulkhead_door.geo.json']
    outputs[ASSETS / 'textures/block/bulkhead_door.png'] = png
    outputs[ASSETS / 'textures/item/bulkhead_door.png'] = ipng
    print('PASS: 16x16 icon, 24 of 30 pane texels transparent, palette-only colours')
    for path, data in outputs.items():
        path.write_bytes(data)
        print('WROTE', path)
    if args.previews:
        # Reload saved files, not an independent illustration or simplified geometry.
        saved = json.loads((ART / 'bulkhead_door.bbmodel').read_text(encoding='utf-8'))
        tex = Image.open(ART / 'bulkhead_door_tex.png').convert('RGBA')
        for name, yaw, pitch, opened in [('front', 0, 0, False), ('back', 180, 0, False),
                                          ('oblique', 38, 15, False), ('open', 25, 12, True)]:
            path = ART / ('bulkhead_door_preview_' + name + '.png')
            render(saved, tex, yaw, pitch, opened).save(path)
            print('RENDERED', path)
        icon_preview = icon.resize((256, 256), Image.NEAREST)
        icon_preview.save(ART / 'bulkhead_door_icon_16x.png')
        print('RENDERED', ART / 'bulkhead_door_icon_16x.png')
    assert all(hashlib.sha256(p.read_bytes()).hexdigest() == h for p, h in before.items())
    assert (ASSETS / 'geo/block/bulkhead_door.geo.json').read_bytes() == (ART / 'bulkhead_door.geo.json').read_bytes()
    assert (ASSETS / 'textures/block/bulkhead_door.png').read_bytes() == (ART / 'bulkhead_door_tex.png').read_bytes()
    print('PASS: resource copies byte-identical; all runtime animation hashes unchanged')
    for p, h in before.items():
        if p.name == 'bulkhead_door.animation.json':
            print('ANIMATION SHA256', p, h)


if __name__ == '__main__':
    main()
