#!/usr/bin/env python3
"""Writes the twenty Access Token textures and item models (owner's pick D89: the Keycard look). Edit the map here, never the PNGs."""
import json
import pathlib
import struct
import zlib

# 16 x 16: '.' clear, 'P' the token's colour, 'D' darker, 'L' lighter, 'K' dark line, 'W' white stripe, 'G' gold chip
KEYCARD = [
    "................",
    "................",
    "..KKKKKKKKKKKK..",
    ".KPPPPPPPPPPPPK.",
    ".KPPPPPPPPPPPPK.",
    ".KPWWWWWWWWPPPK.",
    ".KPPPPPPPPPPPPK.",
    ".KPGGPPPPPPPPPK.",
    ".KPGGPPPDDDDPPK.",
    ".KPPPPPPPPPPPPK.",
    ".KPPPPPPPPPPPPK.",
    ".KDDDDDDDDDDDDK.",
    "..KKKKKKKKKKKK..",
    "................",
    "................",
    "................",
]
# Token I to XX: name, colour. The same twenty as the mock-up page.
TOKENS = [
    ("red", 0xB02E26), ("orange", 0xF9801D), ("amber", 0xFF9F1C), ("gold", 0xFFD23A), ("yellow", 0xFED83D),
    ("lime", 0x80C71F), ("green", 0x5E7C16), ("mint", 0x8FE0A4), ("turquoise", 0x19D3B0), ("cyan", 0x169C9C),
    ("ice", 0xBFEFFF), ("light blue", 0x3AB3DA), ("blue", 0x3C44AA), ("violet", 0xB58CFF), ("purple", 0x8932B8),
    ("magenta", 0xC74EBD), ("pink", 0xF38BAA), ("rose", 0xFF6B8A), ("brown", 0x835432), ("gray", 0x9D9D97),
]


def rgb(hex_value):
    return ((hex_value >> 16) & 255, (hex_value >> 8) & 255, hex_value & 255)


def shade(colour, factor):
    return tuple(min(255, int(round(c * factor))) for c in colour)


def palette(colour):
    return {"P": colour + (255,), "D": shade(colour, 0.6) + (255,), "L": shade(colour, 1.35) + (255,),
            "K": (29, 33, 39, 255), "W": (255, 255, 255, 255), "G": (255, 210, 58, 255)}


def png(rows, pal):
    raw = b""
    for row in rows:
        raw += b"\x00" + b"".join(bytes(pal.get(ch, (0, 0, 0, 0))) for ch in row)

    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


BOARD = [
    "KKKKKKKKKKKKKKKK",
    "KTTTTTTTTTTTTTTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTBBBBBBBBBBBBTK",
    "KTTTTTTTTTTTTTTK",
    "KKKKKKKKKKKKKKKK",
]
BOARD_PAL = {"K": (29, 33, 39, 255), "T": (25, 211, 176, 255), "B": (7, 10, 14, 255)}
BACK = [["K"] * 16 for _ in range(16)]


def board_model():
    """A panel 3/16 thick against the back of its block, front facing north: the screen face carries the board texture, the rest the back texture."""
    face = {"north": {"uv": [0, 0, 16, 16], "texture": "#front"}, "south": {"uv": [0, 0, 16, 16], "texture": "#back"},
            "west": {"uv": [13, 0, 16, 16], "texture": "#back"}, "east": {"uv": [0, 0, 3, 16], "texture": "#back"},
            "up": {"uv": [0, 13, 16, 16], "texture": "#back"}, "down": {"uv": [0, 0, 16, 3], "texture": "#back"}}
    return {"parent": "minecraft:block/block", "textures": {"particle": "bsp_teleport:block/access_board_back", "front": "bsp_teleport:block/access_board", "back": "bsp_teleport:block/access_board_back"},
            "elements": [{"from": [0, 0, 13], "to": [16, 16, 16], "faces": face}]}


def main():
    root = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/bsp_teleport"
    btex = root / "textures/block"
    btex.mkdir(parents=True, exist_ok=True)
    (btex / "access_board.png").write_bytes(png(BOARD, BOARD_PAL))
    (btex / "access_board_back.png").write_bytes(png(["".join(r) for r in BACK], BOARD_PAL))
    bm = root / "models/block"
    bm.mkdir(parents=True, exist_ok=True)
    (bm / "access_board.json").write_text(json.dumps(board_model(), indent=2) + "\n")
    bs = root / "blockstates"
    bs.mkdir(parents=True, exist_ok=True)
    (bs / "access_board.json").write_text(json.dumps({"variants": {"facing=north": {"model": "bsp_teleport:block/access_board"}, "facing=east": {"model": "bsp_teleport:block/access_board", "y": 90},
                                                      "facing=south": {"model": "bsp_teleport:block/access_board", "y": 180}, "facing=west": {"model": "bsp_teleport:block/access_board", "y": 270}}}, indent=2) + "\n")
    (root / "models/item").mkdir(parents=True, exist_ok=True)
    (root / "models/item/access_board.json").write_text(json.dumps({"parent": "bsp_teleport:block/access_board"}, indent=2) + "\n")
    tex = root / "textures/item"
    models = root / "models/item"
    tex.mkdir(parents=True, exist_ok=True)
    models.mkdir(parents=True, exist_ok=True)
    for i, (name, colour) in enumerate(TOKENS, start=1):
        (tex / ("access_token_%d.png" % i)).write_bytes(png(KEYCARD, palette(rgb(colour))))
        (models / ("access_token_%d.json" % i)).write_text(json.dumps({"parent": "minecraft:item/generated", "textures": {"layer0": "bsp_teleport:item/access_token_%d" % i}}, indent=2) + "\n")
    print("wrote 20 token textures and models, the board texture, model, blockstate and item model")


if __name__ == "__main__":
    main()
