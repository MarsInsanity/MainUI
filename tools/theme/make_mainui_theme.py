#!/usr/bin/env python3
"""Generate MainUI's default theme ("MainUI", blue) in OnionOS theme format.

The output is a normal OnionOS theme folder (config.json, skin/, icons/,
icons/sel/, icons/app/, sound/, preview.png): it also works on a Miyoo Mini.
Everything is drawn here, including an icon for every system in
app/src/main/assets/systems.json (plus Android and Search), so systems that
OnionOS never had (PS2, PS3, Vita, Switch, Xbox, Wii, 3DS...) get art too.

    python3 tools/theme/make_mainui_theme.py [OUT_DIR]

Requires Pillow. OUT_DIR defaults to app/src/main/assets/themes/MainUI.
"""
import json
import math
import struct
import sys
import wave
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from system_glyphs import GLYPHS, Pen, glyph  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
FONT = ASSETS / "fonts/Exo-2-Bold-Italic.ttf"
SS = 4  # supersampling

# ---- palette: deep navy to midnight, azure accent, cyan highlights -------------------
BG_TOP = (18, 44, 92)
BG_BOTTOM = (5, 12, 30)
ACCENT = (58, 150, 255)
ACCENT_DEEP = (26, 82, 190)
CYAN = (86, 220, 255)
TEXT = (255, 255, 255)
MUTED = (150, 176, 220)
GLYPH_N = (160, 190, 240)


def hexc(rgb):
    return "#%02X%02X%02X" % rgb[:3]


def font(size):
    return ImageFont.truetype(str(FONT), int(size * SS))


def canvas(w, h, fill=(0, 0, 0, 0)):
    return Image.new("RGBA", (w * SS, h * SS), fill)


def finish(im):
    return im.resize((im.width // SS, im.height // SS), Image.LANCZOS)


def save(im, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, optimize=True)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def vgradient(w, h, top, bottom):
    """RGBA vertical gradient at 1x."""
    im = Image.new("RGBA", (w, h))
    px = im.load()
    for y in range(h):
        c = lerp(top, bottom, y / max(1, h - 1))
        for x in range(w):
            px[x, y] = c
    return im


def hgradient(w, h, left, right):
    im = Image.new("RGBA", (w, h))
    px = im.load()
    for x in range(w):
        c = lerp(left, right, x / max(1, w - 1))
        for y in range(h):
            px[x, y] = c
    return im


def mask_rrect(w, h, box, r):
    """Supersampled rounded-rect alpha mask, returned at 1x."""
    m = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle([v * SS for v in box], radius=r * SS, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def S(v):
    return v * SS


# --------------------------------------------------------------------------------------
# Line-art primitives (coordinates in 1x pixels, drawn on a supersampled canvas)
# --------------------------------------------------------------------------------------

def line(d, pts, c, w):
    pts = [(S(x), S(y)) for x, y in pts]
    d.line(pts, fill=c, width=int(S(w)), joint="curve")
    r = S(w) / 2
    for x, y in (pts[0], pts[-1]):
        d.ellipse((x - r, y - r, x + r, y + r), fill=c)


def rrect(d, box, r, c=None, w=0, fill=None):
    d.rounded_rectangle([S(v) for v in box], radius=S(r), outline=c, width=int(S(w)) if c else 0, fill=fill)


def ellipse(d, box, c=None, w=0, fill=None):
    d.ellipse([S(v) for v in box], outline=c, width=int(S(w)) if c else 0, fill=fill)


def circle(d, cx, cy, r, c=None, w=0, fill=None):
    ellipse(d, (cx - r, cy - r, cx + r, cy + r), c, w, fill)


def arc(d, cx, cy, r, a0, a1, c, w):
    d.arc((S(cx - r), S(cy - r), S(cx + r), S(cy + r)), a0, a1, fill=c, width=int(S(w)))


def poly(d, pts, c=None, w=0, fill=None):
    if fill is not None:
        d.polygon([(S(x), S(y)) for x, y in pts], fill=fill)
    if c is not None:
        line(d, pts + [pts[0]], c, w)


def dpad(d, cx, cy, s, c, w):
    line(d, [(cx - s, cy), (cx + s, cy)], c, w)
    line(d, [(cx, cy - s), (cx, cy + s)], c, w)


def buttons(d, cx, cy, s, c):
    circle(d, cx + s * 0.55, cy - s * 0.35, s * 0.36, fill=c)
    circle(d, cx - s * 0.45, cy + s * 0.35, s * 0.36, fill=c)


# ---- menu glyphs ---------------------------------------------------------------------

def g_recent(d, cx, cy, s, c, w):
    arc(d, cx, cy, s, -40, 260, c, w)
    a = math.radians(-40)
    px, py = cx + s * math.cos(a), cy + s * math.sin(a)
    poly(d, [(px + s * 0.05, py - s * 0.42), (px + s * 0.38, py + s * 0.12), (px - s * 0.3, py + s * 0.14)], fill=c)
    line(d, [(cx, cy - s * 0.55), (cx, cy), (cx + s * 0.4, cy + s * 0.3)], c, w)


def g_star(d, cx, cy, s, c, w):
    pts = []
    for i in range(10):
        r = s if i % 2 == 0 else s * 0.46
        a = math.radians(-90 + i * 36)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    line(d, pts + [pts[0]], c, w)


def g_gamepad(d, cx, cy, s, c, w):
    pts = [(cx - s, cy - s * 0.25), (cx - s * 0.75, cy - s * 0.55), (cx + s * 0.75, cy - s * 0.55),
           (cx + s, cy - s * 0.25), (cx + s * 1.05, cy + s * 0.45), (cx + s * 0.75, cy + s * 0.62),
           (cx + s * 0.45, cy + s * 0.3), (cx - s * 0.45, cy + s * 0.3), (cx - s * 0.75, cy + s * 0.62),
           (cx - s * 1.05, cy + s * 0.45)]
    line(d, pts + [pts[0]], c, w)
    dpad(d, cx - s * 0.55, cy - s * 0.08, s * 0.2, c, w * 0.8)
    buttons(d, cx + s * 0.55, cy - s * 0.08, s * 0.3, c)


def g_apps(d, cx, cy, s, c, w):
    h = s * 0.36
    for dx in (-1, 1):
        for dy in (-1, 1):
            x, y = cx + dx * s * 0.52, cy + dy * s * 0.52
            if dx == 1 and dy == -1:
                circle(d, x, y, h, c, w)
            else:
                rrect(d, (x - h, y - h, x + h, y + h), h * 0.35, c, w)


def g_gear(d, cx, cy, s, c, w):
    pts = []
    teeth = 8
    for i in range(teeth * 4):
        a = math.radians(i * 360 / (teeth * 4) - 90 + 360 / teeth / 8)
        r = s if (i % 4) in (1, 2) else s * 0.76
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    line(d, pts + [pts[0]], c, w)
    circle(d, cx, cy, s * 0.3, c, w)


def g_expert(d, cx, cy, s, c, w):
    """A pixel invader: RetroArch / 'Expert'."""
    rows = ["..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.", "XXXXXXXXXXX",
            "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX..."]
    px = s * 2 / 11
    x0, y0 = cx - px * 5.5, cy - px * 4
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch == "X":
                rrect(d, (x0 + i * px + 0.6, y0 + j * px + 0.6, x0 + (i + 1) * px - 0.6, y0 + (j + 1) * px - 0.6), px * 0.2, fill=c)


def g_search(d, cx, cy, s, c, w):
    circle(d, cx - s * 0.2, cy - s * 0.2, s * 0.6, c, w)
    line(d, [(cx + s * 0.25, cy + s * 0.25), (cx + s * 0.85, cy + s * 0.85)], c, w * 1.3)


def g_folder(d, cx, cy, s, c, w):
    line(d, [(cx - s, cy + s * 0.7), (cx - s, cy - s * 0.65), (cx - s * 0.3, cy - s * 0.65),
             (cx - s * 0.1, cy - s * 0.4), (cx + s, cy - s * 0.4), (cx + s, cy + s * 0.7), (cx - s, cy + s * 0.7)], c, w)


def g_palette(d, cx, cy, s, c, w):
    ellipse(d, (cx - s, cy - s * 0.82, cx + s, cy + s * 0.82), c, w)
    for x, y in ((-0.45, -0.3), (0, -0.48), (0.45, -0.3), (0.52, 0.18)):
        circle(d, cx + s * x, cy + s * y, s * 0.14, fill=c)
    circle(d, cx - s * 0.3, cy + s * 0.35, s * 0.2, c, w)


def g_download(d, cx, cy, s, c, w):
    line(d, [(cx, cy - s), (cx, cy + s * 0.35)], c, w)
    line(d, [(cx - s * 0.45, cy - s * 0.05), (cx, cy + s * 0.4), (cx + s * 0.45, cy - s * 0.05)], c, w)
    line(d, [(cx - s, cy + s * 0.4), (cx - s, cy + s * 0.85), (cx + s, cy + s * 0.85), (cx + s, cy + s * 0.4)], c, w)


def g_bag(d, cx, cy, s, c, w):
    rrect(d, (cx - s * 0.85, cy - s * 0.35, cx + s * 0.85, cy + s * 0.9), s * 0.18, c, w)
    arc(d, cx, cy - s * 0.35, s * 0.42, 180, 360, c, w)


def g_switcher(d, cx, cy, s, c, w):
    rrect(d, (cx - s * 0.9, cy - s * 0.75, cx + s * 0.5, cy + s * 0.35), s * 0.12, c, w)
    line(d, [(cx - s * 0.6, cy + s * 0.75), (cx + s * 0.9, cy + s * 0.75), (cx + s * 0.9, cy - s * 0.3)], c, w)


def g_image(d, cx, cy, s, c, w):
    rrect(d, (cx - s, cy - s * 0.75, cx + s, cy + s * 0.75), s * 0.15, c, w)
    line(d, [(cx - s * 0.75, cy + s * 0.5), (cx - s * 0.2, cy - s * 0.1), (cx + s * 0.15, cy + s * 0.25),
             (cx + s * 0.4, cy), (cx + s * 0.75, cy + s * 0.5)], c, w)
    circle(d, cx + s * 0.4, cy - s * 0.4, s * 0.14, fill=c)


def g_chart(d, cx, cy, s, c, w):
    line(d, [(cx - s, cy - s), (cx - s, cy + s), (cx + s, cy + s)], c, w)
    line(d, [(cx - s * 0.6, cy + s * 0.3), (cx - s * 0.1, cy - s * 0.2), (cx + s * 0.3, cy + s * 0.1),
             (cx + s * 0.85, cy - s * 0.6)], c, w)


def g_sliders(d, cx, cy, s, c, w):
    for i, k in enumerate((-0.3, 0.45, -0.05)):
        x = cx + (i - 1) * s * 0.7
        line(d, [(x, cy - s), (x, cy + s)], c, w * 0.8)
        circle(d, x, cy + k * s, s * 0.22, fill=c)


# system id -> name badge under the picture (None: no badge)
BADGES = {
    "3do": "3DO", "3ds": "3DS", "advision": "A-VISION", "alg": "ALG", "amiga": "AMIGA", "amigacd32": "CD32",
    "amstradcpc": "CPC", "gx4000": "GX4000", "apple2": "APPLE II", "vmac": "MAC", "piece": "P/ECE",
    "arcade": "ARCADE", "arduboy": "ARDUBOY", "astrocde": "ASTRO", "atari2600": "2600", "atari5200": "5200",
    "atari7800": "7800", "atari800": "800", "atarijaguar": "JAGUAR", "atarilynx": "LYNX", "atarist": "ST",
    "atarixegs": "XEGS", "atomiswave": "AWAVE", "bbcmicro": "BBC", "cavestory": "CAVE", "cdimono1": "CD-i",
    "coleco": "COLECO", "c128": "C128", "c16": "C16", "c64": "C64", "vic20": "VIC-20", "cps1": "CPS1",
    "cps2": "CPS2", "cps3": "CPS3", "daphne": "LASER", "doom": "DOOM", "dragon32": "DRAGON", "dreamcast": "DC",
    "vmu": "VMU", "easyrpg": "RPG", "enterprise": "ENT", "channelf": "CHAN-F", "fds": "FDS", "gameandwatch": "G&W",
    "gb": "GB", "gba": "GBA", "gbc": "GBC", "gamegear": "GG", "gc": "GC", "megadrive": "MD", "intellivision": "INTV",
    "j2me": "J2ME", "lowresnx": "LOWRES", "mame2003": "MAME 2003", "mame": "MAME", "mastersystem": "SMS",
    "megaduck": "DUCK", "msumd": "MSU-MD", "xbox": "XBOX", "mv": "MICROV", "msx": "MSX", "msx2": "MSX2",
    "naomi": "NAOMI", "neogeo": "NEO GEO", "neogeocd": "NEO CD", "ngp": "NGP", "ngpc": "NGPC", "n64": "N64",
    "n64dd": "64DD", "nds": "NDS", "nes": "NES", "switch": "SWITCH", "wii": "Wii", "odyssey2": "ODYSSEY",
    "openbor": "BOR", "palm": "PALM", "dos": "DOS", "pcenginecd": "PCE-CD", "pcengine": "PCE", "pcfx": "PC-FX",
    "pc98": "PC-98", "pico-8": "PICO-8", "psx": "PS1", "ps2": "PS2", "ps3": "PS3", "psp": "PSP", "pspminis": "MINIS",
    "psvita": "VITA", "pokemonmini": "MINI", "puzzlescript": "PUZZLE", "satellaview": "BS-X", "scummvm": "SCUMM",
    "sega32x": "32X", "segacd": "SEGA CD", "pico": "PICO", "saturn": "SATURN", "sg-1000": "SG-1000", "x1": "X1",
    "x68000": "X68K", "solarus": "SOLARUS", "sufami": "SUFAMI", "scv": "SCV", "sgb": "SGB", "supergrafx": "SGX",
    "snes": "SNES", "snes-hacks": "HACKS", "snesmsu1": "MSU-1", "coco3": "COCO3", "thomson": "MO/TO",
    "ti99": "TI-99", "tic80": "TIC-80", "tigerlcd": "TIGER", "uzebox": "UZEBOX", "vectrex": "VECTREX",
    "videopac": "G7000", "tvc": "TVC", "vircon32": "VIRCON", "virtualboy": "VB", "onscripter": "ONS",
    "wasm4": "WASM-4", "supervision": "SV", "wolf": "WOLF3D", "wonderswan": "WS", "wonderswancolor": "WSC",
    "zxspectrum": "ZX", "zx81": "ZX81", "ports": "PORTS", "android": "ANDROID", "search": None,
}



# --------------------------------------------------------------------------------------
# Skin
# --------------------------------------------------------------------------------------

def background(w=640, h=480):
    im = vgradient(w, h, BG_TOP + (255,), BG_BOTTOM + (255,))
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse((w * 0.45, -h * 0.55, w * 1.35, h * 0.55), fill=ACCENT + (90,))
    gd.ellipse((-w * 0.4, h * 0.55, w * 0.45, h * 1.45), fill=ACCENT_DEEP + (70,))
    glow = glow.filter(ImageFilter.GaussianBlur(70))
    im = Image.alpha_composite(im, glow)
    # Soft waves along the bottom.
    waves = canvas(w, h)
    wd = ImageDraw.Draw(waves)
    for k, (amp, off, a) in enumerate(((14, 330, 34), (18, 360, 26), (10, 392, 20))):
        pts = [(x, off + amp * math.sin(x / 70 + k * 1.3) + 0.04 * x) for x in range(0, w + 8, 8)]
        line(wd, pts, CYAN + (a,), 1.6)
    im = Image.alpha_composite(im, finish(waves))
    # Fine dot grid in the upper left for texture.
    dots = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dd = ImageDraw.Draw(dots)
    for y in range(70, 260, 16):
        for x in range(16, 260, 16):
            a = max(0, int(22 - (x + y) / 18))
            if a:
                dd.ellipse((x - 1, y - 1, x + 1, y + 1), fill=(255, 255, 255, a))
    return Image.alpha_composite(im, dots)


def bar(h, line_at_top):
    im = vgradient(640, h, (4, 10, 26, 205), (6, 16, 38, 165)) if not line_at_top else \
        vgradient(640, h, (6, 16, 38, 165), (4, 10, 26, 210))
    y = 0 if line_at_top else h - 2
    ln = hgradient(640, 2, ACCENT + (0,), CYAN + (230,))
    ln2 = ln.transpose(Image.FLIP_LEFT_RIGHT)
    half = Image.new("RGBA", (640, 2))
    half.paste(ln.crop((0, 0, 320, 2)).resize((320, 2)), (0, 0))
    half.paste(ln2.crop((320, 0, 640, 2)), (320, 0))
    im.alpha_composite(half, (0, y))
    return im


def selection(h):
    w = 640
    base = hgradient(w, h, ACCENT + (215,), ACCENT_DEEP + (60,))
    m = mask_rrect(w, h, (6, 3, w - 6, h - 3), 12)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(base, (0, 0), Image.composite(m, Image.new("L", (w, h), 0), m))
    edge = canvas(w, h)
    ed = ImageDraw.Draw(edge)
    rrect(ed, (6, 3, w - 6, h - 3), 12, CYAN + (150,), 1.5)
    rrect(ed, (10, 12, 15, h - 12), 2.5, fill=CYAN + (255,))
    return Image.alpha_composite(out, finish(edge))


def card(w, h, focused, r=16, pad=4):
    """Rounded card used for main menu tiles and grid cells."""
    if focused:
        fill = vgradient(w, h, (64, 150, 255, 235), (20, 62, 150, 235))
    else:
        fill = vgradient(w, h, (255, 255, 255, 22), (255, 255, 255, 8))
    m = mask_rrect(w, h, (pad, pad, w - pad, h - pad), r)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(fill, (0, 0), m)
    if focused:
        glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(glow).rounded_rectangle((pad, pad, w - pad, h - pad), radius=r, outline=CYAN + (200,), width=4)
        glow = glow.filter(ImageFilter.GaussianBlur(3))
        out = Image.alpha_composite(glow, out)
    edge = canvas(w, h)
    rrect(ImageDraw.Draw(edge), (pad, pad, w - pad, h - pad), r, (CYAN + (230,)) if focused else (255, 255, 255, 40), 2 if focused else 1.2)
    return Image.alpha_composite(out, finish(edge))


def home_tile(glyph, focused):
    w, h = 156, 278
    im = card(w, h, focused, r=20, pad=6)
    g = canvas(w, h)
    if glyph is not None:  # None: the blank tile MainUI draws system icons on (bg-home-tile-*)
        glyph(ImageDraw.Draw(g), w / 2, 112, 38, TEXT + (255,) if focused else GLYPH_N + (235,), 5.5)
    g = finish(g)
    if focused:
        shadow = g.filter(ImageFilter.GaussianBlur(4))
        im = Image.alpha_composite(im, shadow)
    im = Image.alpha_composite(im, g)
    if focused:  # accent underline below where the label sits
        u = canvas(w, h)
        rrect(ImageDraw.Draw(u), (w / 2 - 18, 236, w / 2 + 18, 240), 2, fill=CYAN + (255,))
        im = Image.alpha_composite(im, finish(u))
    return im


def button_icon(label, color, size=54):
    im = canvas(size, size)
    d = ImageDraw.Draw(im)
    circle(d, size / 2, size / 2, size / 2 - 3, fill=color + (255,))
    circle(d, size / 2, size / 2, size / 2 - 3, (255, 255, 255, 90), 1.5)
    f = font(size * 0.56)
    d.text((S(size / 2), S(size / 2) + S(1)), label, font=f, fill=TEXT + (255,), anchor="mm")
    return finish(im)


def pill_icon(label, w=96, h=48):
    im = canvas(w, h)
    d = ImageDraw.Draw(im)
    rrect(d, (2, 4, w - 2, h - 4), (h - 8) / 2, fill=ACCENT_DEEP + (255,))
    rrect(d, (2, 4, w - 2, h - 4), (h - 8) / 2, (255, 255, 255, 90), 1.5)
    d.text((S(w / 2), S(h / 2) + S(1)), label, font=font(h * 0.4), fill=TEXT + (255,), anchor="mm")
    return finish(im)


def arrow(direction, size=24, color=TEXT):
    im = canvas(size, size)
    d = ImageDraw.Draw(im)
    if direction == "left":
        line(d, [(size * 0.62, size * 0.2), (size * 0.32, size * 0.5), (size * 0.62, size * 0.8)], color + (255,), 3.2)
    else:
        line(d, [(size * 0.38, size * 0.2), (size * 0.68, size * 0.5), (size * 0.38, size * 0.8)], color + (255,), 3.2)
    return finish(im)


def dot(active):
    im = canvas(24, 14)
    d = ImageDraw.Draw(im)
    if active:
        rrect(d, (2, 3, 22, 11), 4, fill=CYAN + (255,))
    else:
        circle(d, 12, 7, 3.5, fill=MUTED + (130,))
    return finish(im)


def toggle(on):
    w, h = 92, 44
    im = canvas(w, h)
    d = ImageDraw.Draw(im)
    rrect(d, (6, 8, w - 6, h - 8), (h - 16) / 2, fill=(ACCENT + (255,)) if on else (60, 74, 100, 255))
    cx = w - 6 - (h - 16) / 2 if on else 6 + (h - 16) / 2
    circle(d, cx, h / 2, (h - 16) / 2 - 3, fill=TEXT + (255,))
    return finish(im)


def panel(w, h, r=18):
    fill = vgradient(w, h, (24, 52, 104, 248), (10, 24, 54, 248))
    m = mask_rrect(w, h, (2, 2, w - 2, h - 2), r)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(fill, (0, 0), m)
    e = canvas(w, h)
    rrect(ImageDraw.Draw(e), (2, 2, w - 2, h - 2), r, ACCENT + (200,), 2)
    return Image.alpha_composite(out, finish(e))


def logo():
    w, h = 148, 42
    im = canvas(w, h)
    d = ImageDraw.Draw(im)
    rrect(d, (4, 9, 28, 33), 6, fill=ACCENT + (255,))
    dpad(d, 16, 21, 5.5, TEXT + (255,), 2.6)
    f = font(25)
    d.text((S(36), S(22)), "Main", font=f, fill=TEXT + (255,), anchor="lm")
    tw = d.textlength("Main", font=f) / SS
    d.text((S(36 + tw + 1), S(22)), "UI", font=f, fill=CYAN + (255,), anchor="lm")
    return finish(im)


def favorite_mark():
    im = canvas(34, 39)
    g_star(ImageDraw.Draw(im), 17, 20, 14, (255, 204, 64, 255), 3)
    return finish(im)


def battery_icon(level, charging=False):
    im = canvas(48, 48)
    d = ImageDraw.Draw(im)
    rrect(d, (6, 15, 40, 33), 4, TEXT + (230,), 2.4)
    rrect(d, (40, 20, 44, 28), 1.5, fill=TEXT + (230,))
    if level > 0:
        col = (232, 84, 84, 255) if level <= 0.2 else CYAN + (255,)
        rrect(d, (9.5, 18.5, 9.5 + 27 * level, 29.5), 2, fill=col)
    if charging:
        poly(d, [(25, 12), (17, 26), (23, 26), (21, 36), (30, 21), (24, 21)], fill=(255, 220, 90, 255))
    return finish(im)


def system_icon(sid, selected):
    badge = BADGES.get(sid, sid[:7].upper())
    w, h = 120, 130
    im = canvas(w, h)
    d = ImageDraw.Draw(im)
    col = TEXT + (255,) if selected else GLYPH_N + (240,)
    cy = 52 if badge else 62
    draw = GLYPHS.get(sid) or GLYPHS["fallback"]
    draw(Pen(d, w / 2, cy, col, SS))
    out = finish(im)
    if selected:
        glow = Image.new("RGBA", out.size, (0, 0, 0, 0))
        glow.paste(Image.new("RGBA", out.size, CYAN + (255,)), (0, 0), out.split()[3])
        out = Image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(5)), out)
    if badge:
        b = canvas(w, h)
        bd = ImageDraw.Draw(b)
        f = font(15)
        tw = bd.textlength(badge, font=f) / SS
        bw = min(w - 4, tw + 18)
        x0 = (w - bw) / 2
        if selected:
            rrect(bd, (x0, 104, x0 + bw, 126), 11, fill=ACCENT + (255,))
        else:
            rrect(bd, (x0, 104, x0 + bw, 126), 11, fill=(28, 60, 120, 235))
            rrect(bd, (x0, 104, x0 + bw, 126), 11, (110, 160, 240, 120), 1)
        bd.text((S(w / 2), S(115.5)), badge, font=f, fill=TEXT + (255,), anchor="mm")
        out = Image.alpha_composite(out, finish(b))
    return out


def app_icon(glyph, size=96):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    fill = vgradient(size, size, (64, 150, 255, 255), (22, 64, 156, 255))
    m = mask_rrect(size, size, (4, 4, size - 4, size - 4), 22)
    im.paste(fill, (0, 0), m)
    g = canvas(size, size)
    glyph(ImageDraw.Draw(g), size / 2, size / 2, size * 0.27, TEXT + (255,), size * 0.055)
    return Image.alpha_composite(im, finish(g))


def change_sound(path):
    rate = 22050
    n = int(rate * 0.035)
    frames = bytearray()
    for i in range(n):
        t = i / rate
        env = math.exp(-t * 140)
        v = int(9000 * env * math.sin(2 * math.pi * 1650 * t))
        frames += struct.pack("<h", v)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wv:
        wv.setnchannels(1)
        wv.setsampwidth(2)
        wv.setframerate(rate)
        wv.writeframes(bytes(frames))


def g_handheld(d, cx, cy, s, c, w):
    """A handheld: the Emulators menu."""
    rrect(d, (cx - s * 0.62, cy - s, cx + s * 0.62, cy + s), s * 0.18, c, w)
    rrect(d, (cx - s * 0.42, cy - s * 0.78, cx + s * 0.42, cy - s * 0.08), s * 0.06, c, w * 0.8)
    dpad(d, cx - s * 0.26, cy + s * 0.42, s * 0.16, c, w * 0.8)
    buttons(d, cx + s * 0.26, cy + s * 0.42, s * 0.3, c)


TILES = {"recent": g_recent, "favorite": g_star, "game": g_gamepad, "retroarch": g_expert, "app": g_apps, "setting": g_gear,
         "emulators": g_handheld}
APPS = {"themes": g_palette, "pacman": g_bag, "gameswitcher": g_switcher, "search": g_search, "gallery": g_image,
        "activity": g_chart, "retroarch": g_expert, "tweaks": g_sliders, "download": g_download, "folder": g_folder}


def preview(skin):
    """A main menu screenshot, composed from the generated parts."""
    im = skin["background"].rotate(180)
    im.alpha_composite(skin["bg-title"], (0, 0))
    im.alpha_composite(skin["miyoo-topbar"], (16, 9))
    im.alpha_composite(skin["power-full-icon"], (580, 6))
    labels = ["Recents", "Favorites", "Games", "Expert"]
    for i, k in enumerate(["recent", "favorite", "game", "retroarch"]):
        tile = skin[f"ic-{k}-{'f' if i == 2 else 'n'}"]
        x = 4 + i * 158
        im.alpha_composite(tile, (x, 92))
        t = canvas(156, 40)
        ImageDraw.Draw(t).text((S(78), S(20)), labels[i], font=font(22),
                               fill=TEXT + (255,) if i == 2 else (200, 214, 240, 255), anchor="mm")
        im.alpha_composite(finish(t), (x, 92 + 196))
    for i in range(2):
        im.alpha_composite(skin["dot-a" if i == 0 else "dot-n"], (296 + i * 24, 392))
    im.alpha_composite(skin["tips-bar-bg"], (0, 420))
    im.alpha_composite(skin["icon-A-54"].resize((40, 40), Image.LANCZOS), (16, 430))
    t = canvas(200, 40)
    ImageDraw.Draw(t).text((S(0), S(20)), "SELECT", font=font(26), fill=TEXT + (255,), anchor="lm")
    im.alpha_composite(finish(t), (64, 430))
    return im


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ASSETS / "themes/MainUI"
    skin_dir = out / "skin"
    skin = {}

    def put(name, im):
        skin[name] = im
        save(im, skin_dir / f"{name}.png")

    put("background", background().rotate(180))  # OnionOS shows background.png turned 180 degrees
    put("bg-title", bar(60, line_at_top=False))
    put("tips-bar-bg", bar(60, line_at_top=True))
    put("bg-list-s", selection(56))
    put("bg-list-l", selection(90))
    div = hgradient(640, 4, (255, 255, 255, 0), (255, 255, 255, 0))
    dl = canvas(640, 4)
    line(ImageDraw.Draw(dl), [(24, 2), (616, 2)], (150, 190, 255, 36), 1)
    put("div-line-h", Image.alpha_composite(div, finish(dl)))
    put("dot-a", dot(True))
    put("dot-n", dot(False))
    for k, g in TILES.items():
        put(f"ic-{k}-n", home_tile(g, False))
        put(f"ic-{k}-f", home_tile(g, True))
    # The same tile without a glyph: the Games row view draws each system's icon on it.
    put("bg-home-tile-n", home_tile(None, False))
    put("bg-home-tile-f", home_tile(None, True))
    put("bg-game-item-n", card(155, 170, False, r=16, pad=5))
    put("bg-game-item-f", card(155, 170, True, r=16, pad=5))
    put("icon-A-54", button_icon("A", (58, 150, 255)))
    put("icon-B-54", button_icon("B", (34, 92, 196)))
    put("icon-x", button_icon("X", (34, 92, 196)))
    put("icon-y", button_icon("Y", (34, 92, 196)))
    put("icon-START", pill_icon("START"))
    put("icon-SELECT", pill_icon("SELECT", 112))
    put("ic-MENU", pill_icon("MENU", 65, 48))
    put("icon-left-arrow-24", arrow("left"))
    put("icon-right-arrow-24", arrow("right"))
    put("extra/arrowLeft", arrow("left", 36))
    put("extra/arrowRight", arrow("right", 36))
    fo = canvas(60, 60)
    g_folder(ImageDraw.Draw(fo), 30, 31, 20, CYAN + (255,), 4)
    put("icon-folder", finish(fo))
    put("ic-favorite-mark", favorite_mark())
    put("extra/toggle-on", toggle(True))
    put("extra/toggle-off", toggle(False))
    put("extra/gs-top-bar", bar(40, line_at_top=False))
    put("extra/gs-bottom-bar", bar(40, line_at_top=True))
    for i, hh in enumerate((70, 130, 190, 250), 1):
        put(f"bg-pop-menu-{i}", panel(320, hh))
    put("pop-bg", panel(640, 300, 22))
    put("preview-bg", card(250, 360, False, r=18, pad=4))
    put("miyoo-topbar", logo())
    for name, lv in (("power-0%-icon", 0.03), ("power-20%-icon", 0.2), ("power-50%-icon", 0.5),
                     ("power-80%-icon", 0.8), ("power-full-icon", 1.0)):
        put(name, battery_icon(lv))
    put("ic-power-charge-100%", battery_icon(1.0, charging=True))

    systems = json.loads((ASSETS / "systems.json").read_text())["systems"]
    ids = [s["id"] for s in systems] + ["android", "search"]
    missing = [i for i in ids if i not in GLYPHS]
    for sid in ids:
        save(system_icon(sid, False), out / "icons" / f"{sid}.png")
        save(system_icon(sid, True), out / "icons/sel" / f"{sid}.png")
    for name, g in APPS.items():
        save(app_icon(g), out / "icons/app" / f"{name}.png")
    change_sound(out / "sound/change.wav")
    save(preview(skin), out / "preview.png")

    cfg = {
        "name": "MainUI",
        "author": "MainUI",
        "description": "MainUI's default theme: deep blue, with art for every system MainUI supports.",
        "hideLabels": {"icons": False, "hints": False},
        "batteryPercentage": {"visible": True, "font": "Exo-2-Bold-Italic.ttf", "size": 18, "color": "#FFFFFF",
                              "textAlign": "left", "fixed": False, "offsetX": 0, "offsetY": 0},
        "title": {"font": "Exo-2-Bold-Italic.ttf", "size": 30, "color": "#FFFFFF"},
        "hint": {"font": "Exo-2-Bold-Italic.ttf", "size": 30, "color": "#FFFFFF"},
        "currentpage": {"color": hexc(CYAN)},
        "total": {"color": "#FFFFFF"},
        "grid": {"font": "Exo-2-Bold-Italic.ttf", "grid1x4": 24, "grid3x4": 17, "color": hexc(MUTED),
                 "selectedcolor": "#FFFFFF"},
        "list": {"font": "Exo-2-Bold-Italic.ttf", "size": 24, "color": "#FFFFFF"},
        "mainui": {"accent": hexc(ACCENT), "panel": "#F0122A5A", "background": hexc(BG_BOTTOM)},
    }
    # Content stamp: MainUI re-copies the bundled theme only when this changes.
    import hashlib
    h = hashlib.sha1()
    for f in sorted(p for p in out.rglob("*") if p.is_file() and p.name not in ("config.json",)):
        h.update(str(f.relative_to(out)).encode())
        h.update(f.read_bytes())
    cfg["mainui"]["stamp"] = h.hexdigest()[:16]
    (out / "config.json").write_text(json.dumps(cfg, indent=4) + "\n")
    (out / "readme.txt").write_text(
        "MainUI theme - generated by tools/theme/make_mainui_theme.py.\n"
        "Includes an icon for every system MainUI knows. Works as an OnionOS theme too.\n")
    print(f"wrote {out} ({len(ids)} system icons{', generic picture for: ' + ', '.join(missing) if missing else ''})")


if __name__ == "__main__":
    main()
