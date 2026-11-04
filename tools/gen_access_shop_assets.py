#!/usr/bin/env python3
"""Writes the Access Shop's block models, item model and blockstate (D178). Edit here, never the JSON outputs.

  python3 tools/gen_access_shop_assets.py

The shop is two blocks wide, two tall, one deep, in BSP-Core's steel: it uses BSP-Core's machine atlas
(bsp_core:block/machine_atlas, a hard dependency) with the same tile spots as BSP-Core's tools/gen_material_assets.py, so
it matches the Trade Post. The lower block on the right as you face it is the controller and carries the whole model
(elements run from x 0 to 32 and y 0 to 32; the second column is the controller's clockwise neighbour, so +x here); the
other three parts have an empty model. Front faces north (z = 0).

Baked here: the striped kick plate, the desk with its front panel and token slot, the ribbed neck, the monitor housing
with the screen recess (x 4 to 28, y 17 to 29, front at z 10), the hood with its light strip and the side vents.
Drawn by AccessShopRenderer: the message and the token coins on the screen.
"""
import json
import pathlib

ASSETS = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/bsp_teleport"
ATLAS = "bsp_core:block/machine_atlas"
UVU = 16 / 64
# tile origins in BSP-Core's 64 x 64 machine atlas (gen_material_assets.py MATS); glowing ones are drawn full bright
MATS = {"hull": (0, 0), "hull2": (16, 0), "mid": (48, 0), "trim": (0, 16), "turq": (32, 16), "steel": (0, 32), "amber": (16, 32), "screen": (48, 32)}
GLOW = {"turq", "amber"}
# the screen recess; AccessShopRenderer draws on it with the same numbers
SCREEN = (4, 17, 28, 29)
SCREEN_FRONT = 10


def mbox(frm, to, mat):
    ox, oy = MATS[mat]
    w, h, d = to[0] - frm[0], to[1] - frm[1], to[2] - frm[2]
    dims = {"north": (w, h), "south": (w, h), "east": (d, h), "west": (d, h), "up": (w, d), "down": (w, d)}
    el = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": {}}
    for face, (fw, fh) in dims.items():
        fw, fh = min(fw, 16), min(fh, 16)
        el["faces"][face] = {"uv": [ox * UVU, oy * UVU, (ox + fw) * UVU, (oy + fh) * UVU], "texture": "#atlas"}
    if mat in GLOW:
        el["shade"] = False
        el["forge_data"] = {"block_light": 15, "sky_light": 15}
    return el


def desk():
    e = []
    # kick plate with amber stripes
    e.append(mbox([0, 0, 1], [32, 2, 15], "hull2"))
    for i in range(8):
        x = i * 4 + 0.5
        e.append(mbox([x, 0.35, 0.85], [x + 2, 1.65, 1], "amber"))
    # the desk body and its sunken front panel
    e += [mbox([1, 2, 2], [31, 10, 14], "steel"), mbox([3, 3, 1.5], [29, 9, 2], "hull2")]
    for i in range(3):
        y = 4 + i * 1.6
        e.append(mbox([5, y, 1.3], [27, y + 0.4, 1.5], "mid"))
    # the desk top, and the token slot with its light
    e += [mbox([0, 10, 1], [32, 11, 15], "hull2"), mbox([12, 11, 3], [20, 11.4, 6], "hull"), mbox([13, 11.4, 4], [19, 11.5, 5], "amber")]
    return e


def monitor():
    e = []
    # the ribbed neck
    e.append(mbox([12, 11, 10], [20, 15, 14], "hull2"))
    for i in range(3):
        y = 11.6 + i * 1.2
        e.append(mbox([11.6, y, 9.8], [20.4, y + 0.5, 14.2], "trim"))
    # the housing, its frame round the screen recess, and the dark screen at the back of the recess
    x0, y0, x1, y1 = SCREEN
    e += [mbox([2, 15, 12], [30, 31, 15], "steel"),
          mbox([2, 15, 8], [30, y0, 12], "steel"), mbox([2, y1, 8], [30, 31, 12], "steel"),
          mbox([2, y0, 8], [x0, y1, 12], "steel"), mbox([x1, y0, 8], [30, y1, 12], "steel")]
    e.append(mbox([x0, y0, SCREEN_FRONT], [x1, y1, 12], "screen"))
    # corner bolts on the frame
    for x, y in ((3, 16), (29, 16), (3, 30), (29, 30)):
        e.append(mbox([x - 0.35, y - 0.35, 7.75], [x + 0.35, y + 0.35, 8], "trim"))
    # the hood coming forward over the top, with a turquoise light strip under its edge
    e += [mbox([1.6, 30.6, 6], [30.4, 31.6, 15.4], "hull2"), mbox([4, 30.4, 6.4], [28, 30.6, 7], "turq")]
    # vents on both sides of the housing
    for i in range(5):
        y = 18 + i * 2
        e += [mbox([1.9, y, 12.6], [2, y + 0.8, 14.6], "hull"), mbox([30, y, 12.6], [30.1, y + 0.8, 14.6], "hull")]
    return e


def model(elements, item=False):
    m = {"parent": "minecraft:block/block", "render_type": "minecraft:cutout", "ambientocclusion": False,
         "textures": {"atlas": ATLAS, "particle": ATLAS}, "elements": elements}
    if item:
        m["display"] = {"gui": {"rotation": [30, 200, 0], "translation": [-2.2, -2.8, 0], "scale": [0.3, 0.3, 0.3]},
                        "ground": {"scale": [0.2, 0.2, 0.2]}, "fixed": {"translation": [-4, -4, 0], "scale": [0.45, 0.45, 0.45]},
                        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.15, 0.15, 0.15]},
                        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -1, 0], "scale": [0.2, 0.2, 0.2]}}
    return m


def blockstate():
    variants = {}
    for half in ("lower", "upper"):
        for wing in ("false", "true"):
            for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
                main = half == "lower" and wing == "false"
                v = {"model": "bsp_teleport:block/access_shop" if main else "bsp_teleport:block/access_shop_part"}
                if y:
                    v["y"] = y
                variants[f"facing={facing},half={half},wing={wing}"] = v
    return {"variants": variants}


if __name__ == "__main__":
    whole = desk() + monitor()
    assert all(-16 <= v <= 32 for el in whole for v in el["from"] + el["to"]), "an element runs outside -16..32"
    (ASSETS / "models/block/access_shop.json").write_text(json.dumps(model(whole), indent=1) + "\n")
    (ASSETS / "models/block/access_shop_part.json").write_text(json.dumps(model([]), indent=1) + "\n")
    (ASSETS / "models/item/access_shop.json").write_text(json.dumps(model(whole, True), indent=1) + "\n")
    (ASSETS / "blockstates/access_shop.json").write_text(json.dumps(blockstate(), indent=2) + "\n")
    print(f"access shop assets written ({len(whole)} elements)")
