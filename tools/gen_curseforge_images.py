#!/usr/bin/env python3
"""Draws the images of the CurseForge description (docs/curseforge/DESCRIPTION.md) as SVG in the BSP "Holo HUD" look and
renders them to PNG with macOS Quick Look (qlmanage), cropped with sips, the way BSP-InventorySync does. Block and item
pictures are drawn from the mod's own pixel maps (tools/gen_portal_assets.py, gen_access_assets.py, gen_band_assets.py).
Generated files are never edited by hand.

  python3 tools/gen_curseforge_images.py            # writes docs/curseforge/images/*.png (and keeps the .svg next to them)
"""
import importlib.util
import math
import subprocess
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
OUT = TOOLS.parent / "docs/curseforge/images"
SCALE = 2
BG, PANEL, INSET, LINE = "#0B0F14", "#10151C", "#0C0E12", "#2A2F3A"
ACCENT, VIOLET, GOLD, BAD, OK, TEXT, MUTED = "#19D3B0", "#B58CFF", "#FFD23A", "#FF6B5C", "#8FE04A", "#E6EAF2", "#9AA3B5"
SANS = "Helvetica Neue, Helvetica, Arial, sans-serif"
MONO = "Menlo, Monaco, monospace"


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


portal = load("gen_portal_assets")
access = load("gen_access_assets")
band = load("gen_band_assets")


def hexrgb(c):
    return "#%02x%02x%02x" % (c[0], c[1], c[2])


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class Svg:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">', f'<rect width="{w}" height="{h}" fill="{BG}"/>']

    def rect(self, x, y, w, h, fill=PANEL, stroke=None, r=6, sw=1.5, opacity=1):
        s = f' stroke="{stroke}" stroke-width="{sw}"' if stroke else ''
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}"{s} opacity="{opacity}"/>')

    def text(self, x, y, s, size=18, fill=TEXT, weight="normal", anchor="start", family=SANS, spacing=0):
        ls = f' letter-spacing="{spacing}"' if spacing else ''
        self.parts.append(f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}"{ls}>{esc(s)}</text>')

    def line(self, x1, y1, x2, y2, stroke=LINE, sw=2, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ''
        self.parts.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{stroke}" stroke-width="{sw}"{d} stroke-linecap="round"/>')

    def arrow(self, x1, y1, x2, y2, stroke=ACCENT, sw=2.5):
        self.line(x1, y1, x2, y2, stroke, sw)
        a = math.atan2(y2 - y1, x2 - x1)
        for d in (-0.5, 0.5):
            self.line(x2, y2, x2 - 14 * math.cos(a + d), y2 - 14 * math.sin(a + d), stroke, sw)

    def circle(self, cx, cy, r, fill, stroke=None):
        s = f' stroke="{stroke}" stroke-width="2"' if stroke else ''
        self.parts.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}"{s}/>')

    def pixels(self, rows, pal, x, y, scale, tint=None):
        """A 16 x 16 pixel map as crisp rectangles; 'P' cells take the tint when given."""
        self.parts.append('<g shape-rendering="crispEdges">')
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch == '.':
                    continue
                colour = pal.get(ch)
                if ch == 'P' and tint is not None:
                    colour = tint
                if colour is None:
                    continue
                fill = colour if isinstance(colour, str) else hexrgb(colour)
                if not isinstance(colour, str) and len(colour) > 3 and colour[3] == 0:
                    continue
                self.parts.append(f'<rect x="{x + i * scale}" y="{y + j * scale}" width="{scale}" height="{scale}" fill="{fill}"/>')
        self.parts.append('</g>')

    # Holo HUD pieces

    def hud(self, x, y, w, h, title, color=ACCENT, help_text="", tabs=(), active=0):
        self.rect(x - 2, y - 2, w + 4, h + 4, color, r=0)
        self.rect(x, y, w, h, PANEL, r=0)
        self.text(x + 16, y + 30, title.upper(), 20, color, "bold", spacing=2)
        if help_text:
            self.text(x + 16, y + 50, help_text, 13, MUTED)
        tx = x + w - 16
        for i in range(len(tabs) - 1, -1, -1):
            label = tabs[i].upper()
            tw = len(label) * 8.2 + 20
            tx -= tw
            on = i == active
            self.rect(tx, y + 12, tw, 24, INSET, color if on else LINE, r=0)
            self.text(tx + tw / 2, y + 29, label, 12, color if on else MUTED, "bold", "middle", spacing=1)
            tx -= 6

    def row(self, x, y, w, h, name, sub="", name_color=TEXT, sub_color=MUTED, border=LINE):
        self.rect(x, y, w, h, INSET, border, r=0)
        self.text(x + 10, y + 21, name, 15, name_color, "bold")
        if sub:
            self.text(x + 10, y + h - 9, sub, 12, sub_color)

    def button(self, right_x, y, label, color=ACCENT, dim=False):
        w = len(label) * 7.6 + 18
        xx = right_x - w
        self.rect(xx, y, w, 22, INSET, LINE if dim else color, r=0)
        self.text(xx + w / 2, y + 15, label.upper(), 11, MUTED if dim else color, "bold", "middle", spacing=1)
        return xx

    def switch(self, right_x, y, on):
        return self.button(right_x, y, "on" if on else "off", OK if on else LINE, not on)

    def footer(self, x, y, w, left, right="", lc=MUTED, rc=OK):
        self.line(x + 16, y, x + w - 16, y, LINE, 1)
        self.text(x + 16, y + 20, left, 12, lc)
        if right:
            self.text(x + w - 16, y + 20, right, 12, rc, anchor="end")

    def caption(self, x, y, s, color=MUTED):
        self.text(x, y, s, 15, color)

    def save(self, name):
        side = max(self.w, self.h)
        body = self.parts[2:]
        doc = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{side}" height="{side}" viewBox="0 0 {side} {side}">',
               f'<rect width="{side}" height="{side}" fill="{BG}"/>',
               f'<g transform="translate({(side - self.w) // 2}, {(side - self.h) // 2})">',
               f'<rect width="{self.w}" height="{self.h}" fill="{BG}"/>'] + body + ['</g>', '</svg>']
        OUT.mkdir(parents=True, exist_ok=True)
        svg = OUT / f"{name}.svg"
        svg.write_text("\n".join(doc), encoding="utf-8")
        render(svg, self.w, self.h)


def render(svg, w, h):
    png = svg.with_suffix(".png")
    side = max(w, h) * SCALE
    subprocess.run(["qlmanage", "-t", "-s", str(side), "-o", str(OUT), str(svg)], check=True, capture_output=True)
    produced = OUT / (svg.name + ".png")
    subprocess.run(["sips", "-c", str(h * SCALE), str(w * SCALE), str(produced), "--out", str(png)], check=True, capture_output=True)
    produced.unlink()
    print(f"{png.name} {w * SCALE}x{h * SCALE}")


# Pixel maps and palettes from the asset generators

FRAME, CORE, DOOR, EDGE, PPAL = portal.FRAME, portal.CORE_FRONT, portal.DOOR, portal.door_edge(), portal.PALETTE
KEYCARD, TOKENS = access.KEYCARD, access.TOKENS
BOARD, BOARD_PAL = access.BOARD, access.BOARD_PAL
BAND_MAP, BAND_PAL = band.MAP, band.COLOURS


def token_pal(i):
    return access.palette(access.rgb(TOKENS[i][1]))


def field(s, x, y, size, colour="#19D3B0"):
    """The lit opening: the tint over a swirl of rings."""
    s.rect(x, y, size, size, colour, r=0)
    cx, cy = x + size / 2, y + size / 2
    for k in range(5):
        r = size * (0.12 + k * 0.1)
        s.parts.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="rgba(0,0,0,0.25)" stroke-width="{size * 0.05}" stroke-dasharray="{r * 0.9} {r * 0.5}" transform="rotate({k * 37} {cx} {cy})"/>')
    s.circle(cx, cy, size * 0.14, "rgba(255,255,255,0.45)")


def hotbar(s, cx, y, cell=44, selected=0, with_band=True, band_selected=False):
    """A hotbar of nine slots centred on cx, plus the band slot after it."""
    x0 = cx - 4.5 * cell
    for i in range(9):
        s.rect(x0 + i * cell, y, cell, cell, "#1c1c1c", "#ffffff" if i == selected else "#6a6a6a", r=0, sw=3 if i == selected else 1.5)
    if with_band:
        bx = x0 + 9 * cell + 12
        s.rect(bx - 2, y - 2, cell + 4, cell + 4, ACCENT if band_selected else LINE, r=0)
        s.rect(bx, y, cell, cell, "#0F2A28" if band_selected else INSET, r=0)
        s.pixels(BAND_MAP, BAND_PAL, bx + 6, y + 6, 2)
        return bx
    return x0 + 9 * cell


# ------------------------------------------------------------------ images

def banner():
    s = Svg(1200, 400)
    for i in range(0, 1200, 40):
        s.line(i, 0, i, 400, "#111821", 1)
    for j in range(0, 400, 40):
        s.line(0, j, 1200, j, "#111821", 1)
    s.rect(60, 60, 1080, 280, PANEL, ACCENT, r=12)
    s.text(100, 150, "BSP-Teleport", 58, TEXT, "bold")
    s.text(100, 196, "Homes, warps, portals and server access across the Build Secure Protect network.", 24, MUTED)
    x = 100
    for label, color in (("Teleport Band", ACCENT), ("Cross-server", VIOLET), ("Portals with doors", GOLD), ("Access Tokens", ACCENT)):
        w = len(label) * 9.3 + 28
        s.rect(x, 232, w, 34, INSET, color, r=17)
        s.text(x + w / 2, 255, label, 15, color, "bold", "middle")
        x += w + 14
    s.text(100, 312, "Minecraft 1.20.1  ·  Forge 47.4  ·  requires BSP-Core  ·  Region Guard and RankUp optional  ·  MIT", 16, MUTED, family=MONO)
    s.pixels(BAND_MAP, BAND_PAL, 1000, 100, 8)
    s.save("banner")


def band_slot():
    s = Svg(1200, 330)
    s.text(40, 48, "The Teleport Band: a tenth hotbar slot every player has", 28, TEXT, "bold")
    s.text(40, 78, "Scroll past the last slot or press the band key (default .) and the band is selected; right-click opens its screen. Your hand keeps its real item.", 16, MUTED)
    s.rect(40, 110, 1120, 180, "#2b3f55", r=8)
    bx = hotbar(s, 560, 200, 48, selected=0, band_selected=True)
    s.text(bx + 24, 180, "RIGHT CLICK", 12, ACCENT, "bold", "middle", family=MONO)
    s.arrow(bx + 100, 150, bx + 56, 184, ACCENT)
    s.text(bx + 108, 146, "the band", 15, ACCENT)
    s.save("band-slot")


def band_screen():
    s = Svg(1200, 640)
    x, y, w, h = 40, 40, 700, 560
    s.hud(x, y, w, h, "Teleport Band", ACCENT, "Pick a place. Stand still while it counts down.", ("Homes", "Warps", "Players", "More", "Admin"), 0)
    ry = y + 70
    for name, sub, lock in (("base", "BSP-Forest  overworld  120, 64, -40", False), ("mine", "BSP-Forest  the_nether  -8, 30, 212", False), ("farm", "BSP-Caves  overworld  2040, 70, -16", False), ("old", "locked: more homes than slots", True)):
        s.row(x + 16, ry, w - 32, 44, name, sub, MUTED if lock else TEXT, BAD if lock else MUTED)
        s.pixels(KEYCARD, token_pal(3), x + w - 330, ry + 8, 1.6) if False else None
        bx = x + w - 26
        bx = s.button(bx, ry + 11, "del", BAD) - 6
        s.button(bx, ry + 11, "go", ACCENT, lock)
        ry += 50
    s.row(x + 16, ry + 10, w - 32, 44, "Set home here", "name, then set", GOLD)
    s.rect(x + 160, ry + 20, 220, 24, INSET, LINE, r=0)
    s.text(x + 170, ry + 37, "name", 13, LINE)
    s.button(x + w - 26, ry + 21, "set", GOLD)
    s.footer(x, y + h - 60, w, "Ready.      homes 3 of 4      next slot: Extra Homes II on your totem", "Swift Travel I: cooldown -25%", OK, MUTED)
    # right: what the tabs hold
    rx = 780
    s.text(rx, 70, "What the tabs hold", 22, TEXT, "bold")
    items = [("HOMES", "your homes with world and server, Set Home Here, delete; locked homes when your totem lost slots"),
             ("WARPS", "every admin warp you may use, with its coin cost and an icon"),
             ("PLAYERS", "everyone online on the network: ask to teleport to them or bring them to you; incoming requests shown"),
             ("MORE", "spawn, back, random teleport here or on another server, ignore requests, pay to skip a cooldown, your server access"),
             ("ADMIN", "opens the admin panel (admins and moderators only)")]
    yy = 100
    for t, d in items:
        s.text(rx, yy, t, 14, ACCENT, "bold", spacing=1.5)
        for k, chunk in enumerate(wrap(d, 44)):
            s.text(rx, yy + 20 + k * 18, chunk, 14, MUTED)
        yy += 30 + 18 * len(wrap(d, 44)) + 10
    s.save("band-screen")


def wrap(text, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        if len(cur) + len(w) + 1 > width:
            lines.append(cur)
            cur = w
        else:
            cur = (cur + " " + w).strip()
    if cur:
        lines.append(cur)
    return lines


def band_more():
    s = Svg(1200, 640)
    x, y, w, h = 40, 40, 700, 560
    s.hud(x, y, w, h, "Teleport Band", ACCENT, "Spawn, back, random, requests, and your server access.", ("Homes", "Warps", "Players", "More", "Admin"), 3)
    ry = y + 70
    for label, color in (("Spawn", ACCENT), ("Back", ACCENT), ("Random on this server", ACCENT), ("Random on BSP-Wilds", VIOLET), ("Ignoring requests: off", LINE)):
        s.rect(x + 16, ry, w - 32, 30, INSET, color, r=0)
        s.text(x + 26, ry + 20, label, 14, MUTED if color == LINE else color, "bold")
        ry += 34
    ry += 6
    s.text(x + 16, ry + 12, "ACCESS", 12, MUTED, "bold", spacing=2)
    ry += 20
    for i, (server, state, color, tok) in enumerate((("BSP-Forest", "main server", GOLD, 6), ("BSP-Wilds", "full access", ACCENT, 8), ("BSP-Caves", "rented, 6d 5h left", VIOLET, 13), ("BSP-Isles", "no access (Access Token XVI)", LINE, 15))):
        s.rect(x + 16, ry, w - 32, 28, INSET, color, r=0)
        s.pixels(KEYCARD, token_pal(tok), x + 22, ry + 4, 1.25)
        s.text(x + 50, ry + 19, f"{server}   {state}", 13, MUTED if color == LINE else color, "bold")
        ry += 32
    s.rect(x + 16, ry + 4, w - 32, 30, INSET, VIOLET, r=0)
    s.text(x + 26, ry + 24, "HUD layout: Row", 14, VIOLET, "bold")
    s.footer(x, y + h - 60, w, "Ready.      homes 3 of 4", "", OK)
    rx = 780
    s.text(rx, 70, "The MORE tab", 22, TEXT, "bold")
    for k, line in enumerate(wrap("Spawn and Back are one click. Random teleport lands you in a ring around spawn, inside the world border, never in lava and never near a raid; pick another server and that server finds the spot.", 44)):
        s.text(rx, 100 + k * 18, line, 14, MUTED)
    for k, line in enumerate(wrap("The ACCESS list shows each server that needs access, your state there, and the token that sells it. The HUD layout button cycles the icons above your hotbar between Row, Strip and Column.", 44)):
        s.text(rx, 220 + k * 18, line, 14, MUTED)
    s.save("band-more")


def admin_network():
    s = Svg(1200, 640)
    x, y, w, h = 40, 40, 1120, 560
    s.hud(x, y, w, h, "Admin", VIOLET, "Click a server, then an action. Gold is this server.", ("Network", "Settings", "Warps", "Portals", "Tokens"), 0)
    sx, sy, sw, sh = x + 16, y + 70, w - 32, 230
    s.rect(sx, sy, sw, sh, INSET, LINE, r=0)
    nodes = {"BSP-Spawn": (0.5, 0.25, GOLD, "3 players"), "BSP-Forest": (0.2, 0.72, ACCENT, "7 players"), "BSP-Wilds": (0.8, 0.72, BAD, "down"), "BSP-Caves": (0.5, 0.88, ACCENT, "4 players")}
    for name, (fx, fy, c, sub) in nodes.items():
        if name != "BSP-Spawn":
            s.line(sx + sw * 0.5, sy + sh * 0.25, sx + sw * fx, sy + sh * fy, LINE, 1.5)
    for name, (fx, fy, c, sub) in nodes.items():
        nx, ny = sx + sw * fx, sy + sh * fy
        s.rect(nx - 70, ny - 22, 140, 44, PANEL, c, r=0)
        s.text(nx, ny - 3, name, 14, TEXT, "bold", "middle")
        s.text(nx, ny + 14, sub, 12, BAD if c == BAD else MUTED, anchor="middle")
    dy = sy + sh + 10
    s.rect(sx, dy, sw, 70, INSET, LINE, r=0)
    s.text(sx + 10, dy + 22, "BSP-Forest", 16, TEXT, "bold")
    s.text(sx + 10, dy + 42, "Alex, Sam, Kai, Robin, Jo, Max, Pip", 12, MUTED)
    bx = sx + sw - 10
    bx = s.button(bx, dy + 12, "go", ACCENT) - 6
    bx = s.button(bx, dy + 12, "send everyone to...", LINE, True) - 6
    s.button(bx, dy + 12, "bring everyone here", VIOLET)
    s.button(sx + sw - 10, dy + 42, "needs access: on", GOLD)
    py = dy + 80
    for name in ("Alex", "Sam"):
        s.rect(sx, py, sw, 30, INSET, LINE, r=0)
        s.text(sx + 10, py + 20, name, 14, TEXT, "bold")
        bx = sx + sw - 10
        bx = s.button(bx, py + 4, "last position", LINE, True) - 6
        bx = s.button(bx, py + 4, "go to", ACCENT) - 6
        s.button(bx, py + 4, "bring", VIOLET)
        py += 34
    s.footer(x, y + h - 60, w, "this server: BSP-Spawn      storage: mysql ok, 6 warps", "redis: listening")
    s.save("admin-network")


def admin_portals():
    s = Svg(1200, 640)
    x, y, w, h = 40, 40, 1120, 560
    s.hud(x, y, w, h, "Admin", VIOLET, "Open and Close act now and switch the portal to manual. Click a node on the map to select it.", ("Network", "Settings", "Warps", "Portals", "Tokens"), 3)
    s.button(x + w - 16, y + 58, "map", VIOLET)
    ry = y + 90
    rows = [("BSP-SPAWN  (this server)", None), ("Hub East", ("open", OK, "120,64,-40   link hub-east: Market Gate (BSP-SafeRoom)")), ("Wilds Drop", ("open", OK, "88,70,12   random on BSP-Wilds")),
            ("Old Gate", ("no destination", GOLD, "-10,64,200   -")), ("BSP-SAFEROOM", None), ("Market Gate", ("closed", GOLD, "4,65,-90   link hub-east: Hub East (BSP-Spawn)   real clock, opens 18:00")),
            ("Arena Door", ("locked", BAD, "300,64,300   portal warp arena"))]
    for name, info in rows:
        if info is None:
            s.text(x + 24, ry + 16, name, 12, GOLD, "bold", spacing=1.5)
            ry += 26
            continue
        state, color, dest = info
        s.rect(x + 16, ry, w - 32, 44, INSET, LINE, r=0)
        s.text(x + 26, ry + 19, name, 15, TEXT, "bold")
        s.text(x + 26, ry + 36, f"{dest}   {state}", 12, color)
        bx = x + w - 26
        locked = state == "locked"
        bx = s.button(bx, ry + 11, "unlock" if locked else "lock", BAD if locked else LINE, not locked) - 6
        bx = s.button(bx, ry + 11, "close", LINE, True) - 6
        bx = s.button(bx, ry + 11, "open", OK) - 6
        s.button(bx, ry + 11, "go", ACCENT)
        ry += 50
    s.footer(x, y + h - 60, w, "this server: BSP-Spawn      storage: mysql ok", "all servers up")
    s.save("admin-portals")


def blocks_and_items():
    s = Svg(1200, 1080)
    s.text(40, 48, "Blocks and items", 28, TEXT, "bold")
    s.text(40, 78, "Everything the mod adds. The admin blocks are placed and broken by admins only and shrug off TNT and pistons.", 16, MUTED)
    cards = [("Teleport Band", "item", "Opens the player screen. Every player has one in the band slot; the item is for the creative tab and admins.", lambda cx, cy: s.pixels(BAND_MAP, BAND_PAL, cx - 48, cy - 48, 6)),
             ("Portal Core", "admin block", "Configures a portal and counts as part of its frame. Right-click for the core screen. Its face is the side players arrive on.", lambda cx, cy: s.pixels(CORE, PPAL, cx - 48, cy - 48, 6)),
             ("Portal Frame", "admin block", "Any ring shape in one vertical plane. Several openings in one frame are fine.", lambda cx, cy: s.pixels(FRAME, PPAL, cx - 48, cy - 48, 6)),
             ("Portal field", "placed by the mod", "The lit opening of a ready portal, in one of 24 colours. Walk in and you are through.", lambda cx, cy: field(s, cx - 48, cy - 48, 96, "#B58CFF")),
             ("Portal door", "placed by the mod", "The hangar doors of a closed portal, sliding in from both ends to meet in the middle.", lambda cx, cy: (s.pixels(DOOR, PPAL, cx - 72, cy - 36, 4.5), s.pixels(EDGE, PPAL, cx, cy - 36, 4.5))),
             ("Access Board", "admin block", "Panels join into one screen that shows how many players call each server home.", lambda cx, cy: s.pixels(BOARD, BOARD_PAL, cx - 48, cy - 48, 6)),
             ("Access Token I to XX", "item", "Twenty tokens, one colour each. A token is a server's symbol; shops sell it as a rent stack or a full stack. Right-click to use.", lambda cx, cy: [s.pixels(KEYCARD, token_pal(i), cx - 100 + (i % 5) * 42, cy - 44 + (i // 5) * 24, 2.4) for i in range(20)])]
    cw, ch = 360, 300
    for i, (name, kind, desc, draw) in enumerate(cards):
        cx0, cy0 = 40 + (i % 3) * (cw + 20), 100 + (i // 3) * (ch + 20)
        s.rect(cx0, cy0, cw, ch, PANEL, LINE, r=10)
        draw(cx0 + cw / 2, cy0 + 90)
        s.text(cx0 + cw / 2, cy0 + 172, name, 18, TEXT, "bold", "middle")
        s.text(cx0 + cw / 2, cy0 + 192, kind, 12, ACCENT if "admin" in kind else GOLD if kind == "item" else VIOLET, anchor="middle", spacing=1)
        for k, line in enumerate(wrap(desc, 42)):
            s.text(cx0 + cw / 2, cy0 + 216 + k * 18, line, 13, MUTED, anchor="middle")
    s.save("blocks-and-items")


def portal_views():
    s = Svg(1200, 520)
    s.text(40, 48, "A portal, open and closed", 28, TEXT, "bold")
    s.text(40, 78, "Build a ring of frame blocks with the core in it, give the core a destination, and the opening lights up. Closed, doors slide in from both sides.", 16, MUTED)
    cell = 44
    for col, (title, closed) in enumerate((("Open: the field is lit", False), ("Closed: doors meet in the middle", True))):
        ox = 60 + col * 580
        oy = 120
        s.text(ox, oy - 6, title, 18, ACCENT if not closed else GOLD, "bold")
        for j in range(6):
            for i in range(9):
                px, py = ox + i * cell, oy + j * cell
                border = j == 0 or j == 5 or i == 0 or i == 8
                if border:
                    s.pixels(CORE if (j == 5 and i == 4) else FRAME, PPAL, px, py, cell / 16)
                elif closed:
                    left = i <= 4
                    edge = i == 4 or i == 5
                    s.parts.append(f'<g transform="translate({px + (cell if left and edge else 0)},{py}) scale({-1 if left and edge else 1},1)">')
                    s.pixels(EDGE if edge else DOOR, PPAL, 0, 0, cell / 16)
                    s.parts.append('</g>')
                else:
                    field(s, px, py, cell, "#19D3B0")
        s.text(ox, oy + 6 * cell + 28, "9 by 6 frame, 7 by 4 opening, the core in the bottom row" if not closed else "Blocks style shown; the Rendered style slides smooth panels", 13, MUTED)
    s.save("portal-open-closed")


def core_screen():
    s = Svg(1200, 640)
    x, y, w, h = 40, 40, 700, 560
    s.hud(x, y, w, h, "Portal", VIOLET, "Hub East.  One destination per portal. Links are two-way unless Arrive only is on.", ("Destination", "Look", "Open/close", "Access"), 0)
    s.rect(x + 16, y + 62, w - 32, 26, INSET, OK, r=0)
    s.text(x + 26, y + 80, "READY: LINKED TO MARKET GATE ON BSP-SAFEROOM", 12, OK, "bold", spacing=1)
    px = x + 16
    for label, on in (("warp", False), ("portal warp", False), ("random", False), ("position", False), ("link", True)):
        tw = len(label) * 8 + 20
        s.rect(px, y + 100, tw, 22, INSET, VIOLET if on else LINE, r=0)
        s.text(px + tw / 2, y + 115, label.upper(), 11, VIOLET if on else MUTED, "bold", "middle", spacing=1)
        px += tw + 6
    s.button(x + w - 16, y + 100, "clear", BAD)
    s.text(x + 26, y + 146, "Link id   letters, numbers, _ or -", 12, MUTED)
    s.rect(x + 26, y + 156, 220, 24, INSET, LINE, r=0)
    s.text(x + 34, y + 173, "hub-east", 13, TEXT, family=MONO)
    s.button(x + 310, y + 157, "set", ACCENT)
    s.text(x + 26, y + 204, "Partner: Market Gate (BSP-SafeRoom 4,65,-90)", 13, OK)
    ry = y + h - 150
    s.row(x + 16, ry, w - 32, 40, "Arrive only", "players may arrive here but not leave through it")
    s.switch(x + w - 26, ry + 9, False)
    s.row(x + 16, ry + 46, w - 32, 40, "Close when the destination is down", "the doors shut while the partner or its server is away")
    s.switch(x + w - 26, ry + 55, True)
    s.footer(x, y + h - 50, w, "BSP-Spawn  minecraft:overworld  120, 64, -40      frame 9x6, opening 28 cells", "LIT")
    rx = 780
    s.text(rx, 70, "The core screen", 22, TEXT, "bold")
    for k, line in enumerate(wrap("Right-click the core. DESTINATION picks where the portal leads: an existing warp, a portal warp (a warp only portals can reach), a random spot on any server, a position, or a link id shared with one other core anywhere on the network.", 44)):
        s.text(rx, 100 + k * 18, line, 14, MUTED)
    for k, line in enumerate(wrap("LOOK sets the colour from 24, the door style, which side players arrive on, the name and an arrival line. OPEN/CLOSE sets the mode and the lock. ACCESS holds the rules.", 44)):
        s.text(rx, 240 + k * 18, line, 14, MUTED)
    for k, line in enumerate(wrap("Moderators see everything and can press nothing.", 44)):
        s.text(rx, 360 + k * 18, line, 14, MUTED)
    s.save("core-screen")


def core_doors():
    s = Svg(1200, 640)
    x, y, w, h = 40, 40, 700, 560
    s.hud(x, y, w, h, "Portal", VIOLET, "Hub East.  Doors follow the mode; Lock forces them closed in any mode.", ("Destination", "Look", "Open/close", "Access"), 2)
    s.rect(x + 16, y + 62, w - 32, 26, INSET, OK, r=0)
    s.text(x + 26, y + 80, "READY  DOORS: CLOSED", 12, OK, "bold", spacing=1)
    px = x + 16
    for label, on in (("manual", False), ("redstone", False), ("real clock", True), ("game clock", False)):
        tw = len(label) * 8 + 20
        s.rect(px, y + 100, tw, 22, INSET, VIOLET if on else LINE, r=0)
        s.text(px + tw / 2, y + 115, label.upper(), 11, VIOLET if on else MUTED, "bold", "middle", spacing=1)
        px += tw + 6
    s.row(x + 16, y + 134, w - 32, 50, "Real clock", "opens and closes at these times (Europe/London)")
    s.rect(x + 26, y + 150, 70, 24, INSET, LINE, r=0)
    s.text(x + 34, y + 167, "18:00", 13, TEXT, family=MONO)
    s.text(x + 106, y + 167, "to", 13, MUTED)
    s.rect(x + 130, y + 150, 70, 24, INSET, LINE, r=0)
    s.text(x + 138, y + 167, "23:00", 13, TEXT, family=MONO)
    s.button(x + w - 26, y + 150, "set", ACCENT)
    s.row(x + 16, y + 196, w - 32, 40, "Lock", "forces the portal closed in any mode")
    s.switch(x + w - 26, y + 205, False)
    s.row(x + 16, y + 242, w - 32, 40, "Cooldown per player", "seconds before the same player may use it again")
    bx = s.button(x + w - 26, y + 251, "+", ACCENT) - 8
    s.text(bx, y + 267, "5 s", 14, GOLD, anchor="end")
    s.button(bx - 36, y + 251, "-", ACCENT)
    s.text(x + 26, y + 310, "Doors: closed, mode real clock 18:00 to 23:00, doors rendered, close when down", 12, MUTED)
    s.footer(x, y + h - 50, w, "BSP-Spawn  minecraft:overworld  120, 64, -40      frame 9x6, opening 28 cells", "DARK", MUTED, MUTED)
    rx = 780
    s.text(rx, 70, "Four ways to open and close", 22, TEXT, "bold")
    yy = 100
    for t, d in (("Manual", "an Open and a Close button; the panel and /bsptp portal <name> open|close do the same from anywhere"),
                 ("Redstone", "the core reads power on any side: open while powered, or closed while powered"),
                 ("Real clock", "open from one time of day to another on the server's clock, wrapping past midnight"),
                 ("Game clock", "the same in world time, 0 to 24000"),
                 ("Close when down", "the doors shut by themselves while the destination's server is away and reopen when it is back")):
        s.text(rx, yy, t, 14, VIOLET, "bold", spacing=1.5)
        for k, chunk in enumerate(wrap(d, 44)):
            s.text(rx, yy + 20 + k * 18, chunk, 14, MUTED)
        yy += 30 + 18 * len(wrap(d, 44)) + 8
    s.save("core-doors")


def access_hud():
    s = Svg(1200, 360)
    s.text(40, 48, "Above the hotbar: what you may enter", 28, TEXT, "bold")
    s.text(40, 78, "Gold frame: your main server. Turquoise: full access. Violet with a countdown: rented. Each icon is that server's token.", 16, MUTED)
    s.rect(40, 110, 1120, 220, "#2b3f55", r=8)
    bx = hotbar(s, 520, 270, 44, 0, True, False)
    hx = 520 + 30
    for name, tok, color, sub in (("Forest", 6, GOLD, ""), ("Wilds", 8, ACCENT, ""), ("Caves", 13, VIOLET, "6d 5h")):
        s.rect(hx - 2, 190, 40, 40, color, r=0)
        s.rect(hx, 192, 36, 36, "#101418", r=0)
        s.pixels(KEYCARD, token_pal(tok), hx + 2, 194, 2)
        s.text(hx + 18, 244, name, 12, TEXT, anchor="middle")
        if sub:
            s.text(hx + 18, 258, sub, 12, "#d9c9ff", anchor="middle")
        hx += 56
    s.rect(140, 196, 110, 50, "rgba(0,0,0,0.5)", "#666", r=0)
    s.text(150, 214, "RECALL", 11, "#aaa", family=MONO)
    s.text(150, 232, "142 m", 11, "#aaa", family=MONO)
    s.text(140, 186, "BSP-Core's Recall card keeps the left", 12, MUTED)
    s.text(700, 160, "Row layout shown. Strip and Column are the other two; pick on the band's MORE tab.", 13, MUTED)
    s.save("access-hud")


def access_popups():
    s = Svg(1200, 560)
    s.text(40, 48, "When a door is shut to you", 28, TEXT, "bold")
    s.text(40, 78, "A refused teleport, portal or warp shows what you need and where to buy it. A player with no main server yet is offered the free choice instead.", 16, MUTED)
    # denied banner
    x, y, w, h = 40, 110, 1120, 190
    s.rect(x - 2, y - 2, w + 4, h + 4, BAD, r=0)
    s.rect(x, y, w, h, PANEL, r=0)
    s.text(x + 16, y + 30, "ACCESS NEEDED: BSP-WILDS", 20, BAD, "bold", spacing=2)
    s.text(x + 16, y + 50, "Buy one of these and right-click it. Sold at the Spawn Market, next to the bank.", 13, MUTED)
    for k, (sub, hours) in enumerate((("BSP-Wilds, rented 48 hours", True), ("BSP-Wilds, full access", False))):
        ix = x + 16 + k * 548
        s.rect(ix, y + 66, 532, 60, INSET, LINE, r=0)
        s.pixels(KEYCARD, token_pal(8), ix + 12, y + 80, 2)
        s.text(ix + 56, y + 90, "Access Token IX", 15, TEXT, "bold")
        s.text(ix + 56, y + 112, sub, 13, GOLD)
    bx = s.button(x + w - 16, y + 150, "close", LINE, True) - 8
    s.button(bx, y + 150, "choose main server", GOLD)
    # choose banner
    y2 = 330
    h2 = 200
    s.rect(x - 2, y2 - 2, w + 4, h2 + 4, GOLD, r=0)
    s.rect(x, y2, w, h2, PANEL, r=0)
    s.text(x + 16, y2 + 30, "CHOOSE YOUR MAIN SERVER", 20, GOLD, "bold", spacing=2)
    s.text(x + 16, y2 + 50, "One choice, free, for ever. Your base goes there and you always have access.", 13, MUTED)
    ry = y2 + 62
    for name, tok, counts, sel in (("BSP-Forest", 6, "12 call this home, 4 full, 7 renting", True), ("BSP-Wilds", 8, "9 call this home, 6 full, 11 renting", False)):
        s.rect(x + 16, ry, w - 32, 30, INSET, GOLD if sel else LINE, r=0)
        s.pixels(KEYCARD, token_pal(tok), x + 22, ry + 6, 1.2)
        s.text(x + 50, ry + 20, name, 14, GOLD if sel else TEXT, "bold")
        s.text(x + 170, ry + 20, counts, 12, GOLD)
        s.button(x + w - 26, ry + 4, "choose", GOLD)
        ry += 34
    s.rect(x + 16, ry, w - 32, 34, INSET, GOLD, r=0)
    s.text(x + 26, ry + 15, "Choose BSP-Forest as your main server?", 13, TEXT, "bold")
    s.text(x + 26, ry + 29, "This cannot be changed.", 12, MUTED)
    bx = s.button(x + w - 26, ry + 6, "no", LINE, True) - 8
    s.button(bx, ry + 6, "yes", OK)
    s.save("access-popups")


def access_board_img():
    s = Svg(1200, 520)
    s.text(40, 48, "The Access Board", 28, TEXT, "bold")
    s.text(40, 78, "Panels join into one screen, like BSP-Core's Score Screen. Three styles an admin picks per board: Bars shown, Tiles and Columns too.", 16, MUTED)
    bx, by, bw, bh = 40, 110, 720, 370
    s.rect(bx - 6, by - 6, bw + 12, bh + 12, "#3b434c", r=0)
    s.rect(bx, by, bw, bh, "#07090d", r=0)
    s.text(bx + bw / 2, by + 40, "HOME SERVERS", 26, ACCENT, "bold", "middle", spacing=3)
    s.line(bx + 20, by + 54, bx + bw - 20, by + 54, ACCENT, 2)
    servers = (("BSP-Forest", 6, 12, "4 full, 7 renting"), ("BSP-Wilds", 8, 9, "6 full, 11 renting"), ("BSP-Caves", 13, 7, "2 full, 5 renting"), ("BSP-Isles", 15, 4, "1 full, 3 renting"))
    ry = by + 70
    for name, tok, home, sub in servers:
        s.pixels(KEYCARD, token_pal(tok), bx + 20, ry + 8, 2.5)
        s.text(bx + 70, ry + 22, name, 18, TEXT, "bold")
        s.rect(bx + 70, ry + 32, bw - 180, 14, "#1c2230", r=0)
        s.rect(bx + 70, ry + 32, (bw - 180) * home / 12, 14, hexrgb(access.rgb(TOKENS[tok][1])), r=0)
        s.text(bx + 70, ry + 62, sub, 12, MUTED)
        s.text(bx + bw - 24, ry + 32, str(home), 26, GOLD, "bold", "end")
        ry += 74
    rx = 800
    s.text(rx, 130, "What it shows", 22, TEXT, "bold")
    for k, line in enumerate(wrap("Each server that needs access, how many players call it home, and how many hold full or rented access. The numbers come from the shared database every minute, so a board on the spawn server is right for the whole network.", 36)):
        s.text(rx, 160 + k * 18, line, 14, MUTED)
    for k, line in enumerate(wrap("Right-click a panel as an admin for the style, the size and the title.", 36)):
        s.text(rx, 320 + k * 18, line, 14, MUTED)
    s.save("access-board")


def admin_tokens():
    s = Svg(1200, 640)
    x, y, w, h = 40, 40, 1120, 560
    s.hud(x, y, w, h, "Admin", VIOLET, "Pick a token on the left, then click what it should stand for on the right. Rent and Full give you the stack to put in a shop.", ("Network", "Settings", "Warps", "Portals", "Tokens"), 4)
    lx, ly = x + 16, y + 74
    s.text(lx, ly, "TOKENS", 12, MUTED, "bold", spacing=2)
    assigned = {6, 8, 13, 2}
    for i in range(20):
        cx, cy = lx + (i % 5) * 46, ly + 10 + (i // 5) * 46
        picked = i == 13
        s.rect(cx, cy, 42, 42, ACCENT if picked else VIOLET if i in assigned else LINE, r=0)
        s.rect(cx + 2, cy + 2, 38, 38, "#0F2A28" if picked else INSET, r=0)
        s.pixels(KEYCARD, token_pal(i), cx + 5, cy + 5, 2)
        if i not in assigned and not picked:
            s.rect(cx + 2, cy + 2, 38, 38, "rgba(0,0,0,0.55)", r=0)
    iy = ly + 10 + 4 * 46 + 6
    s.rect(lx, iy, 230, 104, INSET, LINE, r=0)
    s.text(lx + 10, iy + 20, "TOKEN XIV", 12, ACCENT, "bold", spacing=1.5)
    s.text(lx + 10, iy + 38, "server BSP-Caves", 13, VIOLET)
    bx = s.button(lx + 220, iy + 46, "+", GOLD) - 6
    s.text(bx, iy + 61, "168 h", 13, GOLD, anchor="end")
    s.button(bx - 44, iy + 46, "-", GOLD)
    bx = s.button(lx + 220, iy + 74, "clear", BAD) - 6
    bx = s.button(bx, iy + 74, "full", GOLD) - 6
    s.button(bx, iy + 74, "rent", GOLD)
    s.rect(lx, iy + 112, 180, 24, INSET, LINE, r=0)
    s.text(lx + 8, iy + 129, "hint", 12, LINE)
    s.button(lx + 230, iy + 113, "set", ACCENT)
    rx = x + 300
    s.text(rx, ly, "TARGETS", 12, MUTED, "bold", spacing=2)
    ry = ly + 10
    for name, kind, tok, hint in (("BSP-Forest", "server", 6, ""), ("BSP-Wilds", "server", 8, ""), ("BSP-Caves", "server", 13, ""), ("BSP-Isles", "server", None, "place here"), ("Market Gate", "portal", 2, ""), ("arena", "warp", None, "place here"), ("BSP-SafeRoom:arena", "region", None, "place here")):
        s.rect(rx, ry, w - 316, 40, INSET, VIOLET if tok is not None else LINE, r=0)
        if tok is not None:
            s.pixels(KEYCARD, token_pal(tok), rx + 8, ry + 6, 1.75)
        s.text(rx + 48, ry + 18, name, 14, TEXT, "bold")
        s.text(rx + 48, ry + 33, kind + ("   " + hint if hint else ""), 12, ACCENT if hint else VIOLET)
        ry += 44
    s.rect(rx, y + h - 66, 220, 24, INSET, LINE, r=0)
    s.text(rx + 8, y + h - 49, "region id", 12, LINE)
    s.button(rx + 300, y + h - 65, "region", VIOLET)
    s.footer(x, y + h - 36, w, "BSP-Spawn", "4 assigned, 16 free", MUTED, MUTED)
    s.save("admin-tokens")


def compat():
    s = Svg(1200, 620)
    s.text(40, 48, "Works with the other BSP mods", 28, TEXT, "bold")
    s.text(40, 78, "BSP-Core is required. Region Guard and RankUp are optional: with them installed, more of the mod switches on.", 16, MUTED)
    cols = [("BSP-Core (required)", GOLD, ["Your Shatter Totem travels with you on every cross-server move and is never left behind",
                                             "Recall from another server: click the offer and this mod brings you back to your totem",
                                             "Nothing lands within 150 blocks of a totem being stolen, on any server; random teleports keep 200 away",
                                             "Extra Homes and Swift Travel totem powers give more home slots and shorter cooldowns",
                                             "Warps and portal rules charge Shatter Coins; Access Tokens sell in BSP-Core's shops",
                                             "Anti Totem zones can forbid teleporting in, out, or setting a home"]),
            ("BSP-Region Guard (optional)", ACCENT, ["Regions forbid teleporting in, out, or setting a home with their flags",
                                                     "Region triggers and world-edge links move players to other servers through this mod",
                                                     "Random teleport stays inside the world border",
                                                     "Buy access to an admin region with an Access Token: the region keeps everyone else out",
                                                     "Shutdown triggers evacuate everyone to a holding server; /back brings them home"]),
            ("BSP-RankUp (optional)", VIOLET, ["Rank rules on portals and warps: a LuckPerms group, this or better",
                                               "RankUp ranks are LuckPerms groups, so a rung name is a rule name",
                                               "A later update reads the ladder directly for display names and order"])]
    for i, (title, color, lines) in enumerate(cols):
        cx = 40 + i * 380
        s.rect(cx, 110, 360, 480, PANEL, color, r=10)
        s.text(cx + 18, 142, title, 17, color, "bold")
        s.line(cx + 18, 154, cx + 342, 154, LINE, 1)
        yy = 178
        for item in lines:
            s.circle(cx + 24, yy - 5, 4, color)
            for k, chunk in enumerate(wrap(item, 40)):
                s.text(cx + 38, yy + k * 17, chunk, 13, MUTED)
            yy += 17 * len(wrap(item, 40)) + 14
    s.save("compat")


def main():
    for fn in (banner, band_slot, band_screen, band_more, admin_network, admin_portals, blocks_and_items, portal_views, core_screen, core_doors, access_hud, access_popups, access_board_img, admin_tokens, compat):
        fn()


if __name__ == "__main__":
    main()
