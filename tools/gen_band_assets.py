#!/usr/bin/env python3
"""Writes the Teleport Band's texture and item model. Edit the pixel map here, never the PNG."""
import pathlib
import struct
import zlib

# 16 x 16. Each letter is a colour below; '.' is transparent.
MAP = [
    "................",
    ".......LL.......",
    ".....LTggTL.....",
    "....LT.gG.TL....",
    "...LT..WW..TL...",
    "...T.........T..",
    "..vT.........Tv.",
    "..VT....c....TV.",
    "..VT....c....TV.",
    "..vT.........Tv.",
    "...T.........T..",
    "...DT.......TD..",
    "....DT.....TD...",
    ".....DTTTTTD....",
    ".......DD.......",
    "................",
]
COLOURS = {
    "L": (120, 240, 215, 255),  # light rim
    "T": (25, 211, 176, 255),   # turquoise band
    "D": (12, 120, 100, 255),   # shaded band
    "g": (255, 210, 58, 255),   # gem
    "G": (176, 138, 28, 255),   # gem shade
    "W": (255, 255, 255, 255),  # glint
    "v": (181, 140, 255, 255),  # node
    "V": (111, 75, 196, 255),   # node shade
    "c": (90, 100, 114, 120),   # faint centre line
}


def png(rows):
    raw = b""
    for row in rows:
        raw += b"\x00"
        for ch in row:
            raw += bytes(COLOURS.get(ch, (0, 0, 0, 0)))

    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    root = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/bsp_teleport"
    tex = root / "textures/item/teleport_band.png"
    tex.parent.mkdir(parents=True, exist_ok=True)
    tex.write_bytes(png(MAP))
    model = root / "models/item/teleport_band.json"
    model.parent.mkdir(parents=True, exist_ok=True)
    model.write_text('{\n  "parent": "minecraft:item/generated",\n  "textures": {\n    "layer0": "bsp_teleport:item/teleport_band"\n  }\n}\n')
    print("wrote", tex.relative_to(root.parent.parent.parent), "and", model.relative_to(root.parent.parent.parent))


if __name__ == "__main__":
    main()
