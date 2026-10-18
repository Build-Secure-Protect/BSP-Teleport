#!/usr/bin/env python3
"""Writes the portal blocks' textures, block models, blockstates and item models (owner's pick D66: the Hangar look).
Edit the pixel maps here, never the PNGs or the JSON files."""
import json
import math
import pathlib
import struct
import zlib

# 16 x 16 maps. Letters are colours in PALETTE.
FRAME = [
    "################",
    "#..............#",
    "#.--------.....#",
    "#.-......-..==.#",
    "#.-......-..==.#",
    "#.--------.....#",
    "#..............#",
    "################",
    "#..............#",
    "#..----------..#",
    "#..-........-..#",
    "#..-........-..#",
    "#..----------..#",
    "#..............#",
    "#..............#",
    "################",
]
CORE_FRONT = [
    "################",
    "#..............#",
    "#..##########..#",
    "#..#........#..#",
    "#..#..PPPP..#..#",
    "#..#.PCCCCP.#..#",
    "#..#.PCCCCP.#..#",
    "#..#.PCCCCP.#..#",
    "#..#.PCCCCP.#..#",
    "#..#..PPPP..#..#",
    "#..#........#..#",
    "#..##########..#",
    "#..............#",
    "#.=.=.=.=.=.=..#",
    "#..............#",
    "################",
]
DOOR = [
    "################",
    "#..............#",
    "#.------------.#",
    "#.------------.#",
    "#..............#",
    "#==============#",
    "#..............#",
    "#.------------.#",
    "#.------------.#",
    "#.------------.#",
    "#..............#",
    "#==============#",
    "#..............#",
    "#.------------.#",
    "#..............#",
    "################",
]
PALETTE = {
    ".": (232, 236, 239, 255),  # panel
    "-": (210, 217, 223, 255),  # raised panel
    "=": (155, 165, 175, 255),  # vent, rail
    "#": (59, 67, 76, 255),     # seam
    "Y": (240, 194, 51, 255),   # hazard yellow
    "K": (43, 47, 52, 255),     # hazard black
    "C": (25, 211, 176, 255),   # core lens
    "P": (122, 240, 215, 255),  # lens rim
}
FIELD_FRAMES = 8


def door_edge():
    """The door cell that meets the other door: a diagonal hazard stripe down its right-hand four columns."""
    return ["".join(("Y" if ((x + y) % 4) < 2 else "K") if x >= 12 else row[x] for x in range(16)) for y, row in enumerate(DOOR)]


def field_rows():
    """A swirl in greys with alpha, FIELD_FRAMES frames stacked; the block colour handler tints it in game."""
    rows = []
    for t in range(FIELD_FRAMES):
        phase = 2 * math.pi * t / FIELD_FRAMES
        for y in range(16):
            row = []
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                r = math.hypot(dx, dy)
                a = math.atan2(dy, dx)
                v = 0.55 + 0.33 * math.sin(3 * a + 0.8 * r - phase) * max(0.0, 1 - r / 11) + 0.1 * math.sin(1.5 * r - phase)
                v = max(0.22, min(1.0, v))
                g = int(255 * v)
                row.append((g, g, g, 200))
            rows.append(row)
    return rows


def png_from_map(rows):
    return png([[PALETTE[ch] for ch in row] for row in rows])


def png(pixels):
    height, width = len(pixels), len(pixels[0])
    raw = b""
    for row in pixels:
        raw += b"\x00" + b"".join(bytes(p) for p in row)

    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def door_model(axis, side):
    """A door edge cell: the stripe faces the middle of the opening. axis is the row's direction, side the end it came from."""
    body, edge, flipped = "bsp_teleport:block/portal_door", "bsp_teleport:block/portal_door_edge", [16, 0, 0, 16]
    faces = {"up": {"texture": "#body"}, "down": {"texture": "#body"}}
    if axis == "x":
        # Row runs west to east. The west door's stripe sits on its east side: right on the south face, left on the north face.
        normal, mirrored = ("south", "north") if side == "left" else ("north", "south")
        faces.update({"west": {"texture": "#body"}, "east": {"texture": "#body"}, normal: {"texture": "#edge"}, mirrored: {"texture": "#edge", "uv": flipped}})
    else:
        # Row runs north to south. The north door's stripe sits on its south side: right on the west face, left on the east face.
        normal, mirrored = ("west", "east") if side == "left" else ("east", "west")
        faces.update({"north": {"texture": "#body"}, "south": {"texture": "#body"}, normal: {"texture": "#edge"}, mirrored: {"texture": "#edge", "uv": flipped}})
    for f in faces.values():
        f.setdefault("uv", [0, 0, 16, 16])
    return {"parent": "minecraft:block/block", "textures": {"particle": body, "body": body, "edge": edge},
            "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces}]}


def field_model(axis):
    tex = "bsp_teleport:block/portal_field"
    if axis == "x":
        element = {"from": [0, 0, 6], "to": [16, 16, 10], "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "#field", "tintindex": 0}, "south": {"uv": [0, 0, 16, 16], "texture": "#field", "tintindex": 0}}}
    else:
        element = {"from": [6, 0, 0], "to": [10, 16, 16], "faces": {"west": {"uv": [0, 0, 16, 16], "texture": "#field", "tintindex": 0}, "east": {"uv": [0, 0, 16, 16], "texture": "#field", "tintindex": 0}}}
    return {"render_type": "minecraft:translucent", "ambientocclusion": False, "textures": {"particle": tex, "field": tex}, "elements": [element]}


def main():
    root = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/bsp_teleport"
    tex = root / "textures/block"
    tex.mkdir(parents=True, exist_ok=True)
    (tex / "portal_frame.png").write_bytes(png_from_map(FRAME))
    (tex / "portal_core_front.png").write_bytes(png_from_map(CORE_FRONT))
    (tex / "portal_door.png").write_bytes(png_from_map(DOOR))
    (tex / "portal_door_edge.png").write_bytes(png_from_map(door_edge()))
    (tex / "portal_field.png").write_bytes(png(field_rows()))
    (tex / "portal_field.png.mcmeta").write_text(json.dumps({"animation": {"frametime": 3, "interpolate": True}}, indent=2) + "\n")

    models = {
        "portal_frame": {"parent": "minecraft:block/cube_all", "textures": {"all": "bsp_teleport:block/portal_frame"}},
        "portal_core": {"parent": "minecraft:block/orientable", "textures": {"front": "bsp_teleport:block/portal_core_front", "side": "bsp_teleport:block/portal_frame", "top": "bsp_teleport:block/portal_frame"}},
        "portal_door": {"parent": "minecraft:block/cube_all", "textures": {"all": "bsp_teleport:block/portal_door"}},
        "portal_door_hidden": {"textures": {"particle": "bsp_teleport:block/portal_door"}},
        "portal_door_x_left": door_model("x", "left"), "portal_door_x_right": door_model("x", "right"),
        "portal_door_z_left": door_model("z", "left"), "portal_door_z_right": door_model("z", "right"),
        "portal_field_x": field_model("x"), "portal_field_z": field_model("z"),
    }
    bm = root / "models/block"
    bm.mkdir(parents=True, exist_ok=True)
    for name, model in models.items():
        (bm / (name + ".json")).write_text(json.dumps(model, indent=2) + "\n")

    im = root / "models/item"
    im.mkdir(parents=True, exist_ok=True)
    for name in ("portal_frame", "portal_core"):
        (im / (name + ".json")).write_text(json.dumps({"parent": "bsp_teleport:block/" + name}, indent=2) + "\n")

    door_variants = {}
    for axis in ("x", "z"):
        door_variants["axis=%s,part=body" % axis] = {"model": "bsp_teleport:block/portal_door"}
        door_variants["axis=%s,part=hidden" % axis] = {"model": "bsp_teleport:block/portal_door_hidden"}
        door_variants["axis=%s,part=edge_left" % axis] = {"model": "bsp_teleport:block/portal_door_%s_left" % axis}
        door_variants["axis=%s,part=edge_right" % axis] = {"model": "bsp_teleport:block/portal_door_%s_right" % axis}
    states = {
        "portal_frame": {"variants": {"": {"model": "bsp_teleport:block/portal_frame"}}},
        "portal_core": {"variants": {"facing=north": {"model": "bsp_teleport:block/portal_core"}, "facing=east": {"model": "bsp_teleport:block/portal_core", "y": 90},
                                     "facing=south": {"model": "bsp_teleport:block/portal_core", "y": 180}, "facing=west": {"model": "bsp_teleport:block/portal_core", "y": 270}}},
        "portal_field": {"variants": {"axis=x": {"model": "bsp_teleport:block/portal_field_x"}, "axis=z": {"model": "bsp_teleport:block/portal_field_z"}}},
        "portal_door": {"variants": door_variants},
    }
    bs = root / "blockstates"
    bs.mkdir(parents=True, exist_ok=True)
    for name, state in states.items():
        (bs / (name + ".json")).write_text(json.dumps(state, indent=2) + "\n")
    print("wrote 5 textures, %d block models, 2 item models, %d blockstates" % (len(models), len(states)))


if __name__ == "__main__":
    main()
