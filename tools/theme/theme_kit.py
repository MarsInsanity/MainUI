"""Toolkit for MainUI themes (OnionOS theme format), used by tools/theme/themes/*.py.

A theme module defines `class Theme(Style)` and overrides the palette and any of the
element builders below. `build(style, out_dir)` writes a complete theme folder:

    config.json, readme.txt, preview.png, skin/*.png, skin/extra/*.png,
    icons/<system>.png (+ icons/sel/ when style.sel_icons), icons/app/*.png,
    sound/change.wav, fonts the theme uses (+ their licence)

How MainUI draws a theme (logical screen 640 x 480; taller screens add height in the middle):
- background.png is shown turned 180 degrees, like OnionOS: build() turns the picture that
  Style.background() returns, so draw it upright.
- Header bar bg-title 640x60 at the top, footer tips-bar-bg 640x60 at the bottom; the home
  header shows miyoo-topbar (the logo, left, centred at y 30) and the battery on the right.
- Main menu: four ic-<name>-n / -f tiles (156x278) at x 8 + 156 i, y 90; MainUI writes each
  label centred at tile y + 195 in the grid font (grid1x4 size) unless hideLabels.icons, so
  keep that band calm. Names: recent, favorite, game, emulators, retroarch, app, setting.
  Page dots (dot-a / dot-n) centred at y 396. bg-home-tile-n / -f: the same tile without a
  picture; the row view draws a system icon on it (box 103 x 103 centred at y 100).
- Games grid (themes with a "mainui" block, as all of these): bg-game-item-n / -f cells
  155x170 (stretched to the cell), the system icon fitted into (10, 8, 135, 124) of the cell,
  the name in grid3x4 size centred at cell y 148.
- Lists: rows 60 high; div-line-h (640x4) on top of each, then bg-list-s (640x56) on the
  selected one (its ends kept and its middle stretched when the list is narrower); 90 high
  rows use bg-list-l (640x90). Text at x 20 in the list font (always bold), centred.
- Pop-up menus: bg-pop-menu-N (N = rows, 1..4, used for 5+ too) stretched to 400 x (60 N + 10).
  Dialogs: pop-bg (640x300) centred. Info toasts are drawn in the "mainui" panel colour.
- Hints: icon-A-54 / icon-B-54 / icon-x / icon-y (any size, about 44-54 high), icon-START,
  icon-SELECT, ic-MENU (pills), label in the hint font after each.
- Toggles extra/toggle-on/off, arrows icon-left/right-arrow-24 and extra/arrowLeft/Right,
  icon-folder (list row icon), ic-favorite-mark, battery power-*-icon (48x48 canvas) and
  ic-power-charge-100%, preview-bg (frame behind a game's picture).
- System icons: icons/<id>.png, 120x130; on-screen keyboard keys btn-bg-n / -f and bg-input.
"""
import hashlib
import io
import json
import math
import shutil
import struct
import sys
import wave
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import make_mainui_theme as mm  # noqa: E402  (shared line-art helpers and menu glyphs)
from system_glyphs import GLYPHS, Pen  # noqa: E402

ROOT = HERE.parents[1]
ASSETS = ROOT / "app/src/main/assets"
FONTS = HERE / "fonts"
SS = mm.SS

# ---- re-exported drawing helpers (coordinates in 1x pixels on a supersampled canvas) ----
canvas, finish, lerp, vgradient, hgradient, mask_rrect, S = mm.canvas, mm.finish, mm.lerp, mm.vgradient, mm.hgradient, mm.mask_rrect, mm.S
line, rrect, ellipse, circle, arc, poly, dpad = mm.line, mm.rrect, mm.ellipse, mm.circle, mm.arc, mm.poly, mm.dpad
TILES, APPS, BADGES = mm.TILES, mm.APPS, mm.BADGES  # menu glyphs (d, cx, cy, size, colour, width)
g_folder, g_star = mm.g_folder, mm.g_star

MENU_TILES = ["recent", "favorite", "game", "emulators", "retroarch", "app", "setting"]
LABELS = {"recent": "Recents", "favorite": "Favorites", "game": "Games", "emulators": "Emulators",
          "retroarch": "Expert", "app": "Apps", "setting": "Settings"}


def rgba(c, a=255):
    return tuple(c[:3]) + (a,)


def hexc(c):
    return "#%02X%02X%02X" % tuple(c[:3])


def hexa(c, a):
    return "#%02X%02X%02X%02X" % ((a,) + tuple(c[:3]))


def system_ids():
    systems = json.loads((ASSETS / "systems.json").read_text())["systems"]
    return [s["id"] for s in systems] + ["android", "search"]


def radial(w, h, cx, cy, r, inner, outer):
    """RGBA radial gradient (1x)."""
    im = Image.new("RGBA", (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            t = min(1.0, math.hypot(x - cx, y - cy) / r)
            px[x, y] = lerp(inner, outer, t)
    return im


def noise(w, h, amount=10, seed=1, mono=True):
    """Fine grain to lay over flat colour (alpha = amount)."""
    import random
    rnd = random.Random(seed)
    im = Image.new("RGBA", (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            v = rnd.randint(0, 255)
            px[x, y] = (v, v, v, amount) if mono else (rnd.randint(0, 255), rnd.randint(0, 255), v, amount)
    return im


def tint(im, color):
    """[im]'s shape (alpha) filled with [color] (RGBA; its alpha scales the shape's)."""
    out = Image.new("RGBA", im.size, rgba(color, 0))
    a = im.split()[3]
    if len(color) > 3 and color[3] != 255:
        a = a.point(lambda v: v * color[3] // 255)
    out.paste(Image.new("RGBA", im.size, rgba(color)), (0, 0), a)
    return out


def glow(im, color, radius=5, strength=1):
    """A blurred halo of [im]'s shape in [color] behind it."""
    halo = tint(im, color).filter(ImageFilter.GaussianBlur(radius))
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    for _ in range(strength):
        out = Image.alpha_composite(out, halo)
    return Image.alpha_composite(out, im)


def shadow(im, offset=(0, 3), radius=4, color=(0, 0, 0, 120)):
    sh = tint(im, color).filter(ImageFilter.GaussianBlur(radius))
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.alpha_composite(sh, (max(0, offset[0]), max(0, offset[1])))
    return Image.alpha_composite(out, im)


def pixelate(im, factor=3):
    """Hard pixels: down with box filter, up with nearest; alpha snapped to 0 / 255."""
    small = im.resize((max(1, im.width // factor), max(1, im.height // factor)), Image.BOX)
    a = small.split()[3].point(lambda v: 255 if v >= 110 else 0)
    small.putalpha(a)
    return small.resize((small.width * factor, small.height * factor), Image.NEAREST).crop((0, 0, im.width, im.height)) \
        if small.width * factor >= im.width else small.resize(im.size, Image.NEAREST)


def posterize(im, palette):
    """Every visible pixel to the nearest of [palette] (RGB tuples), keeping alpha."""
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            best = min(palette, key=lambda c: (c[0] - r) ** 2 + (c[1] - g) ** 2 + (c[2] - b) ** 2)
            px[x, y] = tuple(best[:3]) + (a,)
    return out


def paste_center(dst, im, cx, cy):
    dst.alpha_composite(im, (int(round(cx - im.width / 2)), int(round(cy - im.height / 2))))


class Style:
    """A theme. Every builder returns a PIL RGBA image at its final size; override freely."""

    # ---- identity ---------------------------------------------------------------------
    name = "Untitled"
    author = "MainUI"
    description = ""
    # ---- palette (RGB tuples) ------------------------------------------------------------
    bg_top = (30, 34, 44)
    bg_bottom = (12, 14, 20)
    accent = (90, 140, 255)      # selection, focused tiles, A button, toggles
    accent2 = (50, 80, 170)      # deeper accent: gradients, B / X / Y buttons
    highlight = (140, 200, 255)  # edges, glows, active dot
    text = (255, 255, 255)       # titles, list text, hints
    muted = (150, 160, 180)      # unselected labels
    glyph_n = (170, 180, 205)    # unselected tile / icon line colour
    glyph_f = (255, 255, 255)    # selected tile / icon line colour
    panel = (24, 28, 40)         # pop-ups, dialogs, toasts
    panel_alpha = 240
    bar = (8, 10, 16)            # header / footer
    bar_alpha = 200
    # ---- type ---------------------------------------------------------------------------
    font_file = "Exo-2-Bold-Italic.ttf"   # in tools/theme/fonts or app assets/fonts
    font_license = None                    # licence file next to it (OFL.txt...), copied too
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 30, 30, 24, 24, 17, 18
    # ---- options -----------------------------------------------------------------------
    sel_icons = False   # also write icons/sel/ (selected variants); doubles the icons' size
    hide_icon_labels = False
    icon_quant = 128    # colours per system icon PNG (palette PNG); 0 = keep RGBA
    battery_percent = True

    # ---- fonts --------------------------------------------------------------------------
    def font_path(self, file=None):
        f = file or self.font_file
        for d in (FONTS, ASSETS / "fonts"):
            if (d / f).is_file():
                return d / f
        raise FileNotFoundError(f)

    def font(self, size, file=None):
        """A supersampled font for drawing on canvas()."""
        return ImageFont.truetype(str(self.font_path(file)), int(size * SS))

    def font1x(self, size, file=None):
        return ImageFont.truetype(str(self.font_path(file)), int(size))

    # ---- big pictures ---------------------------------------------------------------------
    def background(self, w=640, h=480):
        """Upright; build() stores it turned 180 degrees as OnionOS expects."""
        return vgradient(w, h, rgba(self.bg_top), rgba(self.bg_bottom))

    def header_bar(self):
        im = vgradient(640, 60, rgba(self.bar, self.bar_alpha), rgba(self.bar, self.bar_alpha - 30))
        return im

    def footer_bar(self):
        return vgradient(640, 60, rgba(self.bar, self.bar_alpha - 30), rgba(self.bar, self.bar_alpha))

    def logo(self):
        """Home header, left (about 150 x 42)."""
        w, h = 160, 42
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        d.text((S(4), S(22)), self.name, font=self.font(24), fill=rgba(self.text), anchor="lm")
        return finish(im)

    # ---- lists --------------------------------------------------------------------------
    def selection(self, h):
        """bg-list-s (h 56) / bg-list-l (h 90), 640 wide; ends are kept, middle stretched."""
        w = 640
        im = canvas(w, h)
        rrect(ImageDraw.Draw(im), (6, 3, w - 6, h - 3), 12, fill=rgba(self.accent, 220))
        return finish(im)

    def divider(self):
        im = canvas(640, 4)
        line(ImageDraw.Draw(im), [(24, 2), (616, 2)], rgba(self.glyph_n, 40), 1)
        return finish(im)

    # ---- tiles --------------------------------------------------------------------------
    def card(self, w, h, focused):
        """Games grid cell (155x170) and other cards."""
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        if focused:
            rrect(d, (5, 5, w - 5, h - 5), 16, fill=rgba(self.accent, 230))
        else:
            rrect(d, (5, 5, w - 5, h - 5), 16, fill=(255, 255, 255, 16))
        return finish(im)

    def tile_base(self, focused):
        """Main menu tile without its picture (156x278); also bg-home-tile-n/f."""
        return self.card(156, 278, focused)

    def tile_glyph(self, key, focused):
        """The picture on main menu tile [key] (156x278 transparent layer)."""
        im = canvas(156, 278)
        TILES[key](ImageDraw.Draw(im), 78, 112, 38, rgba(self.glyph_f if focused else self.glyph_n), 5.5)
        return finish(im)

    def home_tile(self, key, focused):
        base = self.tile_base(focused)
        return Image.alpha_composite(base, self.tile_glyph(key, focused)) if key else base

    # ---- buttons and small parts ----------------------------------------------------------
    def button(self, label, primary):
        """icon-A-54 / icon-B-54 / icon-x / icon-y (54x54)."""
        n = 54
        im = canvas(n, n)
        d = ImageDraw.Draw(im)
        circle(d, n / 2, n / 2, n / 2 - 4, fill=rgba(self.accent if primary else self.accent2))
        d.text((S(n / 2), S(n / 2 + 1)), label, font=self.font(n * 0.5), fill=rgba(self.text), anchor="mm")
        return finish(im)

    def pill(self, label, w, h=48):
        """icon-START / icon-SELECT / ic-MENU."""
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        rrect(d, (2, 6, w - 2, h - 6), (h - 12) / 2, fill=rgba(self.accent2))
        d.text((S(w / 2), S(h / 2 + 1)), label, font=self.font(h * 0.38), fill=rgba(self.text), anchor="mm")
        return finish(im)

    def dot(self, active):
        im = canvas(24, 14)
        d = ImageDraw.Draw(im)
        if active:
            rrect(d, (3, 3, 21, 11), 4, fill=rgba(self.highlight))
        else:
            circle(d, 12, 7, 3.5, fill=rgba(self.muted, 140))
        return finish(im)

    def toggle(self, on):
        w, h = 92, 44
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        rrect(d, (6, 8, w - 6, h - 8), (h - 16) / 2, fill=rgba(self.accent) if on else rgba(self.muted, 110))
        cx = w - 6 - (h - 16) / 2 if on else 6 + (h - 16) / 2
        circle(d, cx, h / 2, (h - 16) / 2 - 3, fill=rgba(self.text))
        return finish(im)

    def arrow(self, left, size=24):
        im = canvas(size, size)
        d = ImageDraw.Draw(im)
        xs = (0.62, 0.32) if left else (0.38, 0.68)
        line(d, [(size * xs[0], size * 0.2), (size * xs[1], size * 0.5), (size * xs[0], size * 0.8)], rgba(self.text), size * 0.13)
        return finish(im)

    def panel_image(self, w, h):
        """Pop-up menus (400 x 60N+10) and dialogs (640x300)."""
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        rrect(d, (2, 2, w - 2, h - 2), 16, fill=rgba(self.panel, self.panel_alpha))
        rrect(d, (2, 2, w - 2, h - 2), 16, rgba(self.accent, 200), 2)
        return finish(im)

    def folder_icon(self):
        im = canvas(60, 60)
        g_folder(ImageDraw.Draw(im), 30, 31, 20, rgba(self.highlight), 4)
        return finish(im)

    def favorite_mark(self):
        im = canvas(34, 39)
        g_star(ImageDraw.Draw(im), 17, 20, 14, (255, 204, 64, 255), 3)
        return finish(im)

    def battery(self, level, charging=False):
        im = canvas(48, 48)
        d = ImageDraw.Draw(im)
        rrect(d, (6, 15, 40, 33), 4, rgba(self.text, 230), 2.4)
        rrect(d, (40, 20, 44, 28), 1.5, fill=rgba(self.text, 230))
        if level > 0:
            col = (232, 84, 84, 255) if level <= 0.2 else rgba(self.highlight)
            rrect(d, (9.5, 18.5, 9.5 + 27 * level, 29.5), 2, fill=col)
        if charging:
            poly(d, [(25, 12), (17, 26), (23, 26), (21, 36), (30, 21), (24, 21)], fill=(255, 220, 90, 255))
        return finish(im)

    def preview_frame(self):
        """preview-bg, behind a game's picture beside a list (250x360)."""
        return self.card(250, 360, False)

    def key(self, focused):
        """On-screen keyboard key (btn-bg-n / -f), stretched to each key."""
        return self.card(64, 52, focused)

    def input_box(self):
        im = canvas(560, 60)
        rrect(ImageDraw.Draw(im), (2, 2, 558, 58), 12, fill=(255, 255, 255, 235))
        return finish(im)

    # ---- system and app icons ---------------------------------------------------------------
    def icon_pen_color(self, selected):
        return rgba(self.glyph_f if selected else self.glyph_n, 245)

    def icon_picture(self, sid, selected, cy):
        """The line drawing alone (120x130 layer); override to restyle every system icon."""
        im = canvas(120, 130)
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        draw(Pen(ImageDraw.Draw(im), 60, cy, self.icon_pen_color(selected), SS))
        return finish(im)

    def badge(self, label, selected):
        """The name badge under a system picture (120x130 layer), or a transparent layer."""
        im = canvas(120, 130)
        d = ImageDraw.Draw(im)
        f = self.font(15)
        tw = d.textlength(label, font=f) / SS
        bw = min(116, tw + 18)
        x0 = (120 - bw) / 2
        rrect(d, (x0, 104, x0 + bw, 126), 11, fill=rgba(self.accent if selected else self.accent2, 240))
        d.text((S(60), S(115.5)), label, font=f, fill=rgba(self.text), anchor="mm")
        return finish(im)

    def system_icon(self, sid, selected):
        label = BADGES.get(sid, sid[:7].upper())
        out = self.icon_picture(sid, selected, 52 if label else 62)
        if label:
            out = Image.alpha_composite(out, self.badge(label, selected))
        return out

    def app_icon(self, key):
        n = 96
        im = canvas(n, n)
        d = ImageDraw.Draw(im)
        rrect(d, (4, 4, n - 4, n - 4), 22, fill=rgba(self.accent))
        APPS[key](d, n / 2, n / 2, n * 0.27, rgba(self.text), n * 0.055)
        return finish(im)

    # ---- sound ----------------------------------------------------------------------------
    def change_sound(self, path, freq=1650, decay=140, length=0.035, volume=9000, square=False):
        rate = 22050
        frames = bytearray()
        for i in range(int(rate * length)):
            t = i / rate
            s = math.sin(2 * math.pi * freq * t)
            if square:
                s = 1.0 if s >= 0 else -1.0
            frames += struct.pack("<h", int(volume * math.exp(-t * decay) * s))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))

    def sound(self, path):
        self.change_sound(path)

    # ---- config ---------------------------------------------------------------------------
    def config(self):
        f = self.font_file
        return {
            "name": self.name,
            "author": self.author,
            "description": self.description,
            "hideLabels": {"icons": self.hide_icon_labels, "hints": False},
            "batteryPercentage": {"visible": self.battery_percent, "font": f, "size": self.battery_size,
                                  "color": hexc(self.text), "textAlign": "left", "fixed": False, "offsetX": 0, "offsetY": 0},
            "title": {"font": f, "size": self.title_size, "color": hexc(self.text)},
            "hint": {"font": f, "size": self.hint_size, "color": hexc(self.text)},
            "currentpage": {"color": hexc(self.highlight)},
            "total": {"color": hexc(self.text)},
            "grid": {"font": f, "grid1x4": self.grid1x4, "grid3x4": self.grid3x4, "color": hexc(self.muted),
                     "selectedcolor": hexc(self.text)},
            "list": {"font": f, "size": self.list_size, "color": hexc(self.text)},
            "mainui": {"accent": hexc(self.accent), "panel": hexa(self.panel, self.panel_alpha),
                       "background": hexc(self.bg_bottom)},
        }

    def fonts(self):
        """Font files (and licences) to copy into the theme."""
        out = {self.font_file}
        cfg = self.config()
        for v in cfg.values():
            if isinstance(v, dict) and v.get("font"):
                out.add(v["font"])
        return sorted(out)


# ---- writing a theme ---------------------------------------------------------------------

def _save(im, path, quant=0):
    path.parent.mkdir(parents=True, exist_ok=True)
    im = im.convert("RGBA")
    if quant:
        q = im.quantize(colors=quant, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE)
        q.save(path, optimize=True)
    else:
        im.save(path, optimize=True)


def skin_images(st):
    """Every skin image the theme has: {name: image}. Names as OnionOS / MainUI use them."""
    sk = {}
    sk["background"] = st.background().convert("RGBA")
    sk["bg-title"] = st.header_bar()
    sk["tips-bar-bg"] = st.footer_bar()
    sk["miyoo-topbar"] = st.logo()
    sk["bg-list-s"] = st.selection(56)
    sk["bg-list-l"] = st.selection(90)
    sk["div-line-h"] = st.divider()
    sk["dot-a"] = st.dot(True)
    sk["dot-n"] = st.dot(False)
    for k in MENU_TILES:
        sk[f"ic-{k}-n"] = st.home_tile(k, False)
        sk[f"ic-{k}-f"] = st.home_tile(k, True)
    sk["bg-home-tile-n"] = st.home_tile(None, False)
    sk["bg-home-tile-f"] = st.home_tile(None, True)
    sk["bg-game-item-n"] = st.card(155, 170, False)
    sk["bg-game-item-f"] = st.card(155, 170, True)
    sk["icon-A-54"] = st.button("A", True)
    sk["icon-B-54"] = st.button("B", False)
    sk["icon-x"] = st.button("X", False)
    sk["icon-y"] = st.button("Y", False)
    sk["icon-START"] = st.pill("START", 96)
    sk["icon-SELECT"] = st.pill("SELECT", 112)
    sk["ic-MENU"] = st.pill("MENU", 76)
    sk["icon-left-arrow-24"] = st.arrow(True)
    sk["icon-right-arrow-24"] = st.arrow(False)
    sk["extra/arrowLeft"] = st.arrow(True, 36)
    sk["extra/arrowRight"] = st.arrow(False, 36)
    sk["extra/toggle-on"] = st.toggle(True)
    sk["extra/toggle-off"] = st.toggle(False)
    sk["icon-folder"] = st.folder_icon()
    sk["ic-favorite-mark"] = st.favorite_mark()
    for i in range(1, 5):
        sk[f"bg-pop-menu-{i}"] = st.panel_image(400, 60 * i + 10)
    sk["pop-bg"] = st.panel_image(640, 300)
    sk["preview-bg"] = st.preview_frame()
    sk["btn-bg-n"] = st.key(False)
    sk["btn-bg-f"] = st.key(True)
    sk["bg-input"] = st.input_box()
    for name, lv in (("power-0%-icon", 0.03), ("power-20%-icon", 0.2), ("power-50%-icon", 0.5),
                     ("power-80%-icon", 0.8), ("power-full-icon", 1.0)):
        sk[name] = st.battery(lv)
    sk["ic-power-charge-100%"] = st.battery(1.0, charging=True)
    return sk


def build(st, out, preview=True):
    """Writes theme [st] to [out] (replacing it). Returns the skin images."""
    out = Path(out)
    if out.exists():
        shutil.rmtree(out)
    sk = skin_images(st)
    for name, im in sk.items():
        if name == "background":
            im = im.rotate(180)  # OnionOS shows background.png turned 180 degrees
        _save(im, out / "skin" / f"{name}.png")
    for sid in system_ids():
        _save(st.system_icon(sid, False), out / "icons" / f"{sid}.png", st.icon_quant)
        if st.sel_icons:
            _save(st.system_icon(sid, True), out / "icons/sel" / f"{sid}.png", st.icon_quant)
    for key in APPS:
        _save(st.app_icon(key), out / "icons/app" / f"{key}.png", st.icon_quant)
    st.sound(out / "sound/change.wav")
    for f in st.fonts():
        src = st.font_path(f)
        shutil.copy(src, out / f)
    if st.font_license:
        lic = st.font_path(st.font_license) if not Path(st.font_license).is_absolute() else Path(st.font_license)
        shutil.copy(lic, out / lic.name)
    cfg = st.config()
    (out / "config.json").write_text(json.dumps(cfg, indent=4) + "\n")
    if preview:
        import mock_screens
        _save(mock_screens.home(None, out, selected=1).convert("RGB").convert("RGBA"), out / "preview.png")
    # Content stamp: MainUI copies a bundled theme out of the APK again only when this changes.
    h = hashlib.sha1()
    for f in sorted(p for p in out.rglob("*") if p.is_file() and p.name != "config.json"):
        h.update(str(f.relative_to(out)).encode())
        h.update(f.read_bytes())
    cfg.setdefault("mainui", {})["stamp"] = h.hexdigest()[:16]
    (out / "config.json").write_text(json.dumps(cfg, indent=4) + "\n")
    (out / "readme.txt").write_text(
        f"{st.name} - a MainUI theme by {st.author}, generated by tools/theme/themes/ in the MainUI repository.\n"
        "Includes an icon for every system MainUI knows. Works as an OnionOS theme too.\n"
        + (f"Font: {', '.join(st.fonts())} (licence: {Path(st.font_license).name}).\n" if st.font_license else ""))
    return sk


def check(out):
    """Problems with a written theme (missing files, wrong sizes, unreadable fonts): list of strings."""
    out = Path(out)
    probs = []
    need = ["config.json", "preview.png", "skin/background.png", "skin/bg-list-s.png", "sound/change.wav"]
    need += [f"skin/ic-{k}-{s}.png" for k in MENU_TILES for s in "nf"]
    need += [f"icons/{sid}.png" for sid in system_ids()]
    for n in need:
        if not (out / n).is_file():
            probs.append("missing " + n)
    sizes = {"skin/background.png": (640, 480), "skin/bg-title.png": (640, 60), "skin/tips-bar-bg.png": (640, 60),
             "skin/bg-list-s.png": (640, 56), "skin/bg-list-l.png": (640, 90), "skin/bg-game-item-f.png": (155, 170),
             "skin/bg-game-item-n.png": (155, 170)}
    sizes.update({f"skin/ic-{k}-{s}.png": (156, 278) for k in MENU_TILES for s in "nf"})
    for n, sz in sizes.items():
        p = out / n
        if p.is_file() and Image.open(p).size != sz:
            probs.append(f"{n} is {Image.open(p).size}, expected {sz}")
    for p in (out / "icons").glob("*.png"):
        if Image.open(p).size != (120, 130):
            probs.append(f"{p.name} is {Image.open(p).size}, expected (120, 130)")
    try:
        cfg = json.loads((out / "config.json").read_text())
        for k, v in cfg.items():
            if isinstance(v, dict) and v.get("font") and not (out / v["font"]).is_file():
                probs.append(f"font {v['font']} ({k}) not in the theme")
    except Exception as e:  # noqa: BLE001
        probs.append(f"config.json: {e}")
    total = sum(p.stat().st_size for p in out.rglob("*") if p.is_file())
    if total > 1_600_000:
        probs.append(f"theme is {total // 1024} KB (keep it under about 1500 KB)")
    return probs
