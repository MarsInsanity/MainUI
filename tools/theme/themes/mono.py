"""Mono: true black, hairline drawings, white focus brackets, IBM Plex Sans."""
import math

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from theme_kit import (APPS, BADGES, GLYPHS, SS, TILES, Pen, S, Style, arc, canvas, circle, finish, g_folder,
                       hexa, hexc, line, poly, rgba, rrect)

WHITE = (255, 255, 255)
DIM = (0x6A, 0x6A, 0x6A)      # unselected glyphs
LABEL = (0x8C, 0x8C, 0x8C)    # unselected text (5.9:1 on black)
HAIR = (0x2A, 0x2A, 0x2A)     # rules and frames
FAINT = (0x1A, 0x1A, 0x1A)    # list dividers
MENU_NO = {k: "%02d" % (i + 1) for i, k in enumerate(["recent", "favorite", "game", "emulators", "retroarch",
                                                       "app", "setting"])}


def brackets(d, box, arm, color, w=2):
    """Four corner marks (1x pixel rectangles, crisp) on a 1x ImageDraw."""
    x0, y0, x1, y1 = box
    for x, sx in ((x0, 1), (x1, -1)):
        for y, sy in ((y0, 1), (y1, -1)):
            xa, xb = sorted((x, x + sx * arm))
            ya, yb = sorted((y, y + sy * w))
            d.rectangle((xa, ya, xb - 1, yb - 1), fill=color)
            xa, xb = sorted((x, x + sx * w))
            ya, yb = sorted((y, y + sy * arm))
            d.rectangle((xa, ya, xb - 1, yb - 1), fill=color)


class HairPen(Pen):
    """A Pen whose every stroke, explicit widths included, is thinned towards a hairline."""

    def __init__(self, draw, cx, cy, color, ss, k=0.5, floor=1.15, soft=0.28):
        super().__init__(draw, cx, cy, color, ss)
        self.k, self.floor = k, floor
        self.soft_c = color[:3] + (int(color[3] * soft),)

    def _w(self, w):
        w = self.w if w is None else w
        return max(1, int(round(max(self.floor, w * self.k + 0.35) * self.ss)))


def g_invader(d, cx, cy, s, c, w):
    """Expert: the pixel invader as a dot matrix, light enough to sit with hairlines."""
    rows = ["..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.", "XXXXXXXXXXX",
            "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX..."]
    px = s * 2 / 11
    x0, y0 = cx - px * 5, cy - px * 3.5
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch == "X":
                circle(d, x0 + i * px, y0 + j * px, px * 0.3, fill=c)


def g_history(d, cx, cy, s, c, w):
    """Recents: a clock with an open counter-clockwise arrow (no filled head)."""
    arc(d, cx, cy, s, 205, 515, c, w)
    a = math.radians(205)
    tx, ty = cx + s * math.cos(a), cy + s * math.sin(a)
    back = math.atan2(-math.cos(a), math.sin(a)) + math.pi  # opposite of the arrow's travel
    for k in (-0.75, 0.75):
        line(d, [(tx + s * 0.36 * math.cos(back + k), ty + s * 0.36 * math.sin(back + k)), (tx, ty)], c, w)
    line(d, [(cx, cy - s * 0.52), (cx, cy), (cx + s * 0.36, cy + s * 0.24)], c, w)


GLYPH = dict(TILES, retroarch=g_invader, recent=g_history)
ICONS = dict(APPS, retroarch=g_invader)


class Theme(Style):
    name = "Mono"
    author = "MainUI"
    description = "Pure black minimalism: hairline drawings, white focus brackets and lots of space."

    bg_top = bg_bottom = (0, 0, 0)
    accent = WHITE
    accent2 = (0x4A, 0x4A, 0x4A)
    highlight = WHITE
    text = WHITE
    muted = LABEL
    glyph_n = DIM
    glyph_f = WHITE
    panel = (0x0C, 0x0C, 0x0C)
    panel_alpha = 255
    bar = (0, 0, 0)

    font_file = "IBMPlexSans-Medium.ttf"
    list_font = "IBMPlexSans-Regular.ttf"  # MainUI always bolds list text: Regular comes out about Medium
    font_license = "OFL-IBMPlexSans.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 25, 22, 23, 21, 15, 17

    sel_icons = True
    icon_quant = 48

    # ---- big pictures ---------------------------------------------------------------------
    def background(self, w=640, h=480):
        im = Image.new("RGBA", (w, h))
        px = im.load()
        for y in range(h):
            for x in range(w):
                t = min(1.0, math.hypot((x - w / 2) / (w * 0.62), (y - h * 0.46) / (h * 0.7)))
                v = int(round(9 * (1 - t * t)))
                px[x, y] = (v, v, v, 255)
        return im

    def _bar(self, rule_y):
        im = Image.new("RGBA", (640, 60), (0, 0, 0, 255))
        ImageDraw.Draw(im).rectangle((20, rule_y, 619, rule_y), fill=rgba(HAIR))
        return im

    def header_bar(self):
        return self._bar(59)

    def footer_bar(self):
        return self._bar(0)

    def logo(self):
        w, h = 150, 42
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        cx, cy, r = 12, 21, 8.5
        circle(d, cx, cy, r, rgba(WHITE), 1.6)
        d.pieslice((S(cx - r), S(cy - r), S(cx + r), S(cy + r)), 90, 270, fill=rgba(WHITE))
        f = self.font(21)
        d.text((S(29), S(21.5)), "Main", font=f, fill=rgba(WHITE), anchor="lm")
        tw = d.textlength("Main", font=f) / SS
        d.text((S(29 + tw), S(21.5)), "UI", font=f, fill=rgba(LABEL), anchor="lm")
        return finish(im)

    # ---- lists --------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rectangle((8, 4, w - 9, h - 5), fill=(255, 255, 255, 16))
        brackets(d, (8, 4, w - 8, h - 4), 10, rgba(WHITE), 2)
        return im

    def divider(self):
        im = Image.new("RGBA", (640, 4), (0, 0, 0, 0))
        ImageDraw.Draw(im).rectangle((20, 1, 619, 1), fill=rgba(FAINT))
        return im

    # ---- tiles --------------------------------------------------------------------------
    def card(self, w, h, focused):
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        if focused:
            d = ImageDraw.Draw(im)
            d.rectangle((7, 9, w - 8, h - 6), fill=(255, 255, 255, 10))
            brackets(d, (7, 9, w - 7, h - 5), 14, rgba(WHITE), 2)
        return im

    def tile_base(self, focused):
        im = Image.new("RGBA", (156, 278), (0, 0, 0, 0))
        if focused:
            brackets(ImageDraw.Draw(im), (12, 22, 144, 240), 16, rgba(WHITE), 2)
        return im

    def tile_glyph(self, key, focused):
        im = canvas(156, 278)
        d = ImageDraw.Draw(im)
        GLYPH[key](d, 78, 114, 33, rgba(WHITE if focused else DIM), 2.4)
        d.text((S(24), S(40)), MENU_NO[key], font=self.font(12), fill=rgba(WHITE if focused else (0x4A, 0x4A, 0x4A)),
               anchor="lm")
        return finish(im)

    # ---- buttons and small parts ----------------------------------------------------------
    def button(self, label, primary):
        # A / B are drawn at their own size, X / Y scaled to 44 high: the same 28 px disc in both.
        w, h = 32, (32 if label in "AB" else 44)
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        cx, cy, r = w / 2, h / 2, 13.5
        if primary:
            circle(d, cx, cy, r, fill=rgba(WHITE))
        else:
            circle(d, cx, cy, r - 0.75, rgba(WHITE, 230), 1.5)
        d.text((S(cx), S(cy + 0.5)), label, font=self.font(15), fill=(0, 0, 0, 255) if primary else rgba(WHITE),
               anchor="mm")
        return finish(im)

    def pill(self, label, w, h=44):
        f = self.font(11)
        probe = ImageDraw.Draw(canvas(1, 1))
        tw = sum(probe.textlength(ch, font=f) / SS for ch in label) + 1.6 * (len(label) - 1)
        w = int(math.ceil(tw + 24))
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        rrect(d, (1, h / 2 - 12.5, w - 1, h / 2 + 12.5), 12.5, rgba(WHITE, 230), 1.5)
        x = (w - tw) / 2
        for ch in label:
            d.text((S(x), S(h / 2 + 0.5)), ch, font=f, fill=rgba(WHITE), anchor="lm")
            x += d.textlength(ch, font=f) / SS + 1.6
        return finish(im)

    def dot(self, active):
        im = Image.new("RGBA", (24, 14), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        if active:
            d.rectangle((3, 6, 20, 7), fill=rgba(WHITE))
        else:
            d.rectangle((9, 6, 14, 7), fill=rgba((0x4A, 0x4A, 0x4A)))
        return im

    def toggle(self, on):
        w, h = 92, 44
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        x0, x1, y0, y1 = 34, 82, 11, 33
        r = (y1 - y0) / 2
        if on:
            rrect(d, (x0, y0, x1, y1), r, fill=rgba(WHITE))
            circle(d, x1 - r, h / 2, r - 4, fill=(0, 0, 0, 255))
        else:
            rrect(d, (x0, y0, x1, y1), r, rgba(LABEL), 1.5)
            circle(d, x0 + r, h / 2, r - 5, fill=rgba(LABEL))
        return finish(im)

    def arrow(self, left, size=24):
        im = canvas(size, size)
        d = ImageDraw.Draw(im)
        xs = (0.6, 0.38) if left else (0.4, 0.62)
        line(d, [(size * xs[0], size * 0.24), (size * xs[1], size * 0.5), (size * xs[0], size * 0.76)], rgba(WHITE),
             max(1.6, size * 0.075))
        return finish(im)

    def panel_image(self, w, h):
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rectangle((0, 0, w - 1, h - 1), fill=rgba(self.panel, self.panel_alpha), outline=rgba((0x33, 0x33, 0x33)))
        return im

    def folder_icon(self):
        im = canvas(60, 60)
        g_folder(ImageDraw.Draw(im), 30, 31, 18, rgba(WHITE), 2.2)
        return finish(im)

    def favorite_mark(self):
        im = canvas(34, 39)
        pts = []
        for i in range(10):
            r = 9 if i % 2 == 0 else 3.9
            a = math.radians(-90 + i * 36)
            pts.append((17 + r * math.cos(a), 20.5 + r * math.sin(a)))
        poly(ImageDraw.Draw(im), pts, fill=rgba(WHITE))
        return finish(im)

    def battery(self, level, charging=False):
        im = canvas(48, 48)
        d = ImageDraw.Draw(im)
        rrect(d, (7, 16.5, 39, 31.5), 2.5, rgba(WHITE, 235), 1.5)
        rrect(d, (40.5, 21, 42.5, 27), 1, fill=rgba(WHITE, 235))
        if level > 0:
            col = (255, 92, 92, 255) if level <= 0.2 else rgba(WHITE)
            rrect(d, (9.75, 19.25, 9.75 + 26.5 * level, 28.75), 1, fill=col)
        if charging:  # a white bolt cut into the full battery with a clear gap around it
            bolt = [(26, 11), (17, 25.5), (22.6, 25.5), (20.5, 37), (29.5, 22.5), (23.9, 22.5)]
            poly(d, bolt, (0, 0, 0, 0), 3.2, fill=(0, 0, 0, 0))
            poly(d, bolt, fill=rgba(WHITE))
        return finish(im)

    def preview_frame(self):
        w, h = 250, 360
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rectangle((0, 0, w - 1, h - 1), outline=rgba(HAIR))
        brackets(d, (0, 0, w, h), 12, rgba((0x8C, 0x8C, 0x8C)), 2)
        return im

    def key(self, focused):
        w, h = 64, 52
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        if focused:
            d.rectangle((2, 2, w - 3, h - 3), fill=(255, 255, 255, 34), outline=rgba(WHITE))
        else:
            d.rectangle((2, 2, w - 3, h - 3), fill=(255, 255, 255, 14), outline=rgba(HAIR))
        return im

    def input_box(self):
        im = Image.new("RGBA", (560, 60), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rectangle((0, 0, 559, 59), fill=(255, 255, 255, 12))
        d.rectangle((0, 57, 559, 58), fill=rgba(WHITE))
        return im

    # ---- system and app icons ---------------------------------------------------------------
    def icon_picture(self, sid, selected, cy):
        im = canvas(120, 130)
        col = rgba(WHITE if selected else (0x92, 0x92, 0x92))
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        draw(HairPen(ImageDraw.Draw(im), 60, cy, col, SS))
        if sid == "android":  # solid shapes (with navy eyes) to a hairline outline
            a = im.split()[3].point(lambda v: 255 if v else 0)
            px, ap = im.load(), a.load()
            for y in range(im.height):
                for x in range(im.width):
                    if px[x, y] == (12, 28, 64, 255):
                        ap[x, y] = 0
            edge = ImageChops.subtract(a, a.filter(ImageFilter.MinFilter(int(2.1 * SS) * 2 + 1)))
            im = Image.new("RGBA", im.size, col[:3] + (0,))
            im.putalpha(edge)
        return finish(im)

    def badge(self, label, selected):
        im = canvas(120, 130)
        d = ImageDraw.Draw(im)
        f = self.font(10.5)
        sp = 1.3
        tw = sum(d.textlength(ch, font=f) / SS for ch in label) + sp * (len(label) - 1)
        x = 60 - tw / 2
        col = rgba(WHITE if selected else (0x7C, 0x7C, 0x7C))
        for ch in label:
            d.text((S(x), S(118)), ch, font=f, fill=col, anchor="lm")
            x += d.textlength(ch, font=f) / SS + sp
        return finish(im)

    def system_icon(self, sid, selected):
        label = BADGES.get(sid, sid[:7].upper())
        out = self.icon_picture(sid, selected, 54 if label else 62)
        if label:
            out = Image.alpha_composite(out, self.badge(label, selected))
        return out

    def app_icon(self, key):
        n = 96
        im = canvas(n, n)
        d = ImageDraw.Draw(im)
        rrect(d, (6, 6, n - 6, n - 6), 20, rgba((0x3A, 0x3A, 0x3A)), 1.5)
        ICONS[key](d, n / 2, n / 2, n * 0.24, rgba(WHITE), 2.6)
        return finish(im)

    # ---- sound ----------------------------------------------------------------------------
    def sound(self, path):
        self.change_sound(path, freq=2200, decay=320, length=0.022, volume=6500)

    # ---- config ---------------------------------------------------------------------------
    def config(self):
        cfg = super().config()
        cfg["list"]["font"] = self.list_font
        cfg["hint"]["color"] = "#D6D6D6"
        cfg["currentpage"]["color"] = "#FFFFFF"
        cfg["total"]["color"] = hexc(LABEL)
        # MainUI draws white glyphs and text on its accent (built-in list icons, fallbacks): grey, not white.
        cfg["mainui"] = {"accent": "#7A7A7A", "panel": hexa(self.panel, self.panel_alpha), "background": "#000000"}
        return cfg
