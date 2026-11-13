"""Phosphor: an amber CRT terminal. Scanlines and bloom, double-line box frames, reverse-video selection."""
import math
import random

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from theme_kit import APPS, GLYPHS, SS, TILES, Pen, S, Style, canvas, finish, hexc, line, poly, rgba

VOID = (9, 6, 2)
AMBER = (255, 176, 0)
TEXT = (255, 186, 36)      # a touch brighter than the amber art, for reading
HOT = (255, 226, 150)      # stroke cores: phosphor driven hard
FLARE = (255, 120, 0)      # the wide part of the bloom
DIM = (214, 142, 12)       # unselected labels
LOW = (150, 92, 0)         # half-intensity frames
DEEP = (116, 68, 0)        # dim reverse video
BAR = (98, 57, 0)          # the list cursor bar (list text stays amber on it)
INK = (22, 11, 0)          # on lit amber

CODES = {"recent": "HIST", "favorite": "FAV", "game": "ROMS", "emulators": "EMU", "retroarch": "CORE",
         "app": "BIN", "setting": "CFG"}


# ---- image helpers -------------------------------------------------------------------------

def _clear(w, h):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def _paint(mask, c, k=1.0):
    """Colour [c] shaped by [mask] (L), its alpha scaled by [k]."""
    if k != 1.0:
        mask = mask.point(lambda v: min(255, int(v * k)))
    out = Image.new("RGBA", mask.size, rgba(c))
    out.putalpha(mask)
    return out


def _over(*layers):
    out = layers[0]
    for l in layers[1:]:
        out = Image.alpha_composite(out, l)
    return out


def _phosphor(im, glow=1.0, core=True, spread=7, inner=2.2):
    """Lit phosphor: an amber bloom around [im]'s strokes and hot centres in the thick ones."""
    a = im.split()[3]
    layers = [_paint(a.filter(ImageFilter.GaussianBlur(spread)), FLARE, 0.5 * glow),
              _paint(a.filter(ImageFilter.GaussianBlur(inner)), AMBER, 0.75 * glow), im]
    if core:
        layers.append(_paint(a.filter(ImageFilter.MinFilter(3)), HOT, 0.8))
    return _over(*layers)


def _feather(im, n=3):
    """Fades the last [n] px of alpha towards the edges, so bloom never ends in a hard cut."""
    w, h = im.size
    m = Image.new("L", (w, h), 255)
    d = ImageDraw.Draw(m)
    for i in range(n):
        d.rectangle((i, i, w - 1 - i, h - 1 - i), outline=int(255 * (i + 0.5) / n))
    out = im.copy()
    out.putalpha(ImageChops.multiply(im.split()[3], m))
    return out


def _scan(im, k=0.8, phase=0, period=3):
    """The raster: every [period]th row (counted from [phase]) darkened to [k]."""
    w, h = im.size
    col = Image.new("L", (1, h), 255)
    for y in range(h):
        if (y + phase) % period == period - 1:
            col.putpixel((0, y), int(255 * k))
    m = col.resize((w, h), Image.NEAREST)
    r, g, b, a = im.split()
    return Image.merge("RGBA", (ImageChops.multiply(r, m), ImageChops.multiply(g, m), ImageChops.multiply(b, m), a))


def _grain(w, h, seed, density, amount):
    """Scattered specks: [density] of the pixels, alpha up to [amount]."""
    rnd = random.Random(seed)
    m = Image.new("L", (w, h), 0)
    px = m.load()
    for _ in range(int(w * h * density)):
        px[rnd.randrange(w), rnd.randrange(h)] = rnd.randrange(amount // 3, amount)
    return m


def _rect(d, box, c, w):
    """Square outline, [w] thick inside [box] (1x coordinates on a supersampled canvas)."""
    x0, y0, x1, y1 = box
    d.rectangle([S(x0), S(y0), S(x1), S(y1)], outline=c, width=max(1, int(S(w))))


def _fill(d, box, c):
    x0, y0, x1, y1 = box
    d.rectangle([S(x0), S(y0), S(x1), S(y1)], fill=c)


def _box(d, box, c, w=1.6, gap=4.5, tee=None, title=None, font=None):
    """A double-line box (like the terminal's box-drawing characters). [tee]: y of a double
    divider across it, joined to the sides; [title]: text set into the top border."""
    x0, y0, x1, y1 = box
    _rect(d, box, c, w)
    _rect(d, (x0 + gap, y0 + gap, x1 - gap, y1 - gap), c, w)
    if tee is not None:
        for x in (x0 + gap, x1 - gap - w):  # open the inner sides where the divider joins
            _fill(d, (x, tee + w, x + w, tee + gap), (0, 0, 0, 0))
        _fill(d, (x0 + gap, tee, x1 - gap, tee + w), c)
        _fill(d, (x0 + gap, tee + gap, x1 - gap, tee + gap + w), c)
    if title:
        tw = d.textlength(title, font=font) / SS
        cx = (x0 + x1) / 2
        _fill(d, (cx - tw / 2 - 5, y0 - 1, cx + tw / 2 + 5, y0 + gap + w + 1), (0, 0, 0, 0))
        d.text((S(cx), S(y0 + (gap + w) / 2)), title, font=font, fill=c, anchor="mm")


def _brackets(d, x0, y0, x1, y1, c, w=2.2, arm=5):
    """[ ] around a box."""
    for x, s in ((x0, 1), (x1, -1)):
        line(d, [(x + s * arm, y0), (x, y0), (x, y1), (x + s * arm, y1)], c, w)


def _corners(d, box, c, w=2, arm=12, low_arm=None):
    """Corner brackets; [low_arm]: a shorter upright arm for the bottom two."""
    x0, y0, x1, y1 = box
    for x, y, sx, sy in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        up = arm if sy > 0 or low_arm is None else low_arm
        _fill(d, (min(x, x + sx * arm), min(y, y + sy * w), max(x, x + sx * arm), max(y, y + sy * w)), c)
        _fill(d, (min(x, x + sx * w), min(y, y + sy * up), max(x, x + sx * w), max(y, y + sy * up)), c)


def _shade(w, h, box, c):
    """The light-shade block character: a staggered grid of single pixels (1x layer)."""
    im = _clear(w, h)
    px = im.load()
    x0, y0, x1, y1 = (int(round(v)) for v in box)
    for y in range(y0 + y0 % 2, y1, 2):
        for x in range(x0 + y % 4, x1, 4):
            px[x, y] = c
    return im


def _lit(w, h, box):
    """A lit amber block (reverse video) with its raster, as a 1x layer."""
    im = canvas(w, h)
    _fill(ImageDraw.Draw(im), box, rgba(AMBER))
    return _scan(finish(im), 0.86)


class Theme(Style):
    name = "Phosphor"
    author = "MainUI"
    description = "An amber CRT terminal: scanlines and soft bloom, double-line box frames and reverse-video cursors."

    bg_top = (22, 13, 3)
    bg_bottom = VOID
    accent = (176, 100, 0)   # what MainUI fills itself (keeps white text on it readable)
    accent2 = DEEP
    highlight = AMBER
    text = TEXT
    muted = (240, 168, 24)
    glyph_n = AMBER
    glyph_f = INK
    panel = (16, 10, 3)
    panel_alpha = 244
    bar = (6, 4, 1)

    font_file = "VT323-Regular.ttf"
    font_license = "OFL-VT323.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 40, 32, 32, 32, 22, 26
    sel_icons = True
    icon_quant = 48

    # ---- screen ---------------------------------------------------------------------------
    def background(self, w=640, h=480):
        im = Image.new("RGBA", (w, h), rgba(VOID))
        # warm glow from the middle of the tube
        g = Image.new("L", (w, h), 0)
        ImageDraw.Draw(g).ellipse((w * 0.1, h * 0.12, w * 0.9, h * 0.92), fill=255)
        im = _over(im, _paint(g.filter(ImageFilter.GaussianBlur(110)), (88, 46, 4), 0.62))
        # sparse grain (dense noise would not compress), then the raster
        im = _over(im, _paint(_grain(w, h, 7, 0.06, 40), (255, 196, 110)))
        im = _scan(im, 0.62)
        # the curved glass: corners and edges fall off into the bezel
        v = Image.new("L", (w, h), 0)
        ImageDraw.Draw(v).rounded_rectangle((26, 22, w - 26, h - 22), radius=90, fill=255)
        v = ImageChops.invert(v.filter(ImageFilter.GaussianBlur(34)))
        return _over(im, _paint(v, (0, 0, 0), 0.92))

    def header_bar(self):
        im = Image.new("RGBA", (640, 60), rgba(VOID, 222))
        c = canvas(640, 60)
        d = ImageDraw.Draw(c)
        _fill(d, (0, 51, 640, 52.6), rgba(AMBER))
        _fill(d, (0, 55.5, 640, 57.1), rgba(AMBER))
        return _over(im, _phosphor(finish(c), 0.7, core=False))

    def footer_bar(self):
        return self.header_bar().transpose(Image.FLIP_TOP_BOTTOM)

    def logo(self):
        f = self.font(42)
        m, cy = 7, 22  # bloom margin; the text's middle
        probe = ImageDraw.Draw(canvas(1, 1))
        x = m + probe.textlength("> ", font=f) / SS - 4
        tw = probe.textlength("MAINUI", font=f) / SS
        cur = (x + tw + 4, cy - 13, x + tw + 20, cy + 14)
        im = canvas(int(cur[2] + m + 1), 2 * cy)
        d = ImageDraw.Draw(im)
        d.text((S(m), S(cy)), ">", font=f, fill=rgba(DIM), anchor="lm")
        d.text((S(x), S(cy)), "MAINUI", font=f, fill=rgba(AMBER), anchor="lm")
        _fill(d, cur, rgba(AMBER))
        return _phosphor(finish(im), 0.9, spread=3.5)

    # ---- lists ----------------------------------------------------------------------------
    def selection(self, h):
        # MainUI keeps the 24 px ends in the 400 px pop-up: everything stays 12 px in from the
        # sides and 6 px from the top and bottom, inside the pop-up's frame.
        w = 640
        top, bot = 6, h - 6
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        _fill(d, (12, top, w - 12, bot), rgba(BAR, 245))
        _fill(d, (12, top, w - 12, top + 1.5), rgba(AMBER, 230))
        _fill(d, (12, bot - 1.5, w - 12, bot), rgba(AMBER, 230))
        bar = _scan(finish(im), 0.85, phase=1)
        cur = canvas(w, h)
        _fill(ImageDraw.Draw(cur), (12, top, 17, bot), rgba(AMBER))  # the cursor block
        return _feather(_over(_phosphor(bar, 0.45, core=False, spread=4), _phosphor(finish(cur), 0.9, core=False, spread=4)))

    def divider(self):
        im = canvas(640, 4)
        d = ImageDraw.Draw(im)
        for x in range(22, 620, 6):
            _fill(d, (x, 1.2, x + 2.4, 2.8), rgba(LOW, 150))
        return finish(im)

    # ---- tiles ----------------------------------------------------------------------------
    def _window(self, key, focused):
        """A main menu tile without its picture: a double-line window, [key]'s code in its top
        border, its rails open around the label, and a shaded status box below."""
        w, h = 156, 278
        box = x0, y0, x1, y1 = (8, 8, w - 8, h - 8)
        lw, gap = 1.7, 4.6
        # Row-view system names are fitted to 142 px (wider than the window), on one or two
        # lines, with the game count under them: y 160-234 stays clear of the rails.
        cut, tee = 160, 234
        strip = (x0 + 7, tee + gap + lw + 3, x1 - 6, y1 - 6)
        c = rgba(INK if focused else LOW)
        fr = canvas(w, h)
        d = ImageDraw.Draw(fr)
        _box(d, box, c, lw, gap, title=CODES.get(key), font=self.font(21))
        clear = (0, 0, 0, 0)
        _fill(d, (x0 - 2, cut, x0 + gap + lw + 1, tee), clear)
        _fill(d, (x1 - gap - lw - 1, cut, x1 + 2, tee), clear)
        _fill(d, (x0 + gap - 1, tee, x0 + gap + lw + 1, tee + gap), clear)
        _fill(d, (x1 - gap - lw - 1, tee, x1 - gap + 1, tee + gap), clear)
        for sx, x in ((1, x0), (-1, x1)):  # the window's bottom corners, open in the middle
            xs = sorted((x + sx * gap, x + sx * (gap + lw)))
            _fill(d, (xs[0] - 0.5, cut - gap + 0.3, xs[1] + 0.5, cut - lw - 0.3), clear)
            xs = sorted((x, x + sx * (gap + 14)))
            _fill(d, (xs[0], cut - lw, xs[1], cut), c)
            xs = sorted((x + sx * gap, x + sx * (gap + 14)))
            _fill(d, (xs[0], cut - gap - lw, xs[1], cut - gap), c)
        _fill(d, (x0, tee, x1, tee + lw), c)  # the status box's own top
        _fill(d, (x0 + gap, tee + gap, x1 - gap, tee + gap + lw), c)
        fr = finish(fr)
        if focused:
            lit = _lit(w, h, box)
            halo = _paint(lit.split()[3].filter(ImageFilter.GaussianBlur(4)), FLARE, 0.85)
            layers = [halo, lit]
            if CODES.get(key):  # a lit tab behind the code, which stands above the box
                tw = d.textlength(CODES[key], font=self.font(21)) / SS
                layers.append(_lit(w, h, (w / 2 - tw / 2 - 5, y0 - 3, w / 2 + tw / 2 + 5, y0 + 2)))
            return _feather(_over(*layers, fr, _shade(w, h, strip, rgba(INK, 200))))
        base = canvas(w, h)
        _fill(ImageDraw.Draw(base), box, rgba(VOID, 170))
        return _feather(_over(finish(base), _shade(w, h, strip, rgba(LOW, 200)), _phosphor(fr, 0.55, core=False)))

    def tile_base(self, focused):
        return self._window(None, focused)

    def tile_glyph(self, key, focused):
        g = canvas(156, 278)
        TILES[key](ImageDraw.Draw(g), 78, 96, 34, rgba(INK if focused else AMBER), 5.2)
        return finish(g) if focused else _phosphor(finish(g), 1.0)

    def home_tile(self, key, focused):
        if not key:
            return self.tile_base(focused)
        return Image.alpha_composite(self._window(key, focused), self.tile_glyph(key, focused))

    def card(self, w, h, focused):
        # the name is centred at y 148, up to 147 px wide: short bottom arms stay out of its way
        box = (5, 5, w - 5, h - 5)  # wide enough for the longest names, which are INK on it
        if focused:
            lit = _lit(w, h, box)
            halo = _paint(lit.split()[3].filter(ImageFilter.GaussianBlur(3)), FLARE, 0.8)
            c = canvas(w, h)
            _corners(ImageDraw.Draw(c), (box[0] + 4, box[1] + 4, box[2] - 4, box[3] - 4), rgba(INK), 2, 12, 4)
            return _feather(_over(halo, lit, finish(c)))
        c = canvas(w, h)
        d = ImageDraw.Draw(c)
        _fill(d, box, rgba(VOID, 90))
        _corners(d, box, rgba(LOW), 2, 12, 5)
        return _feather(_phosphor(finish(c), 0.4, core=False, spread=3))

    # ---- buttons and small parts ------------------------------------------------------------
    def button(self, label, primary):
        # MainUI shows A / B at their own 54 px but scales X / Y to 44 high: those are drawn
        # 44 high, so all four brackets come out 36 px tall
        w, h = 54, 54 if label in "AB" else 44
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        top, bot = h / 2 - 18, h / 2 + 18
        _brackets(d, 6, top, w - 6, bot, rgba(AMBER), 2.6, 6)
        if primary:
            _fill(d, (13, top + 5, w - 13, bot - 5), rgba(AMBER))
        d.text((S(w / 2), S(h / 2 - 1)), label, font=self.font(36), fill=rgba(INK if primary else AMBER), anchor="mm")
        return _feather(_phosphor(finish(im), 0.8, core=False, spread=2.5, inner=1.4))

    def pill(self, label, w, h=48):
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        _brackets(d, 5, 9, w - 5, h - 9, rgba(AMBER), 2.4, 5)
        d.text((S(w / 2), S(h / 2 + 1)), label, font=self.font(28), fill=rgba(AMBER), anchor="mm")
        return _feather(_phosphor(finish(im), 0.7, core=False, spread=3, inner=1.6))

    def dot(self, active):
        im = canvas(24, 14)
        d = ImageDraw.Draw(im)
        if active:
            _fill(d, (6, 4, 18, 10), rgba(AMBER))
        else:
            _fill(d, (9.5, 4.5, 14.5, 9.5), rgba(LOW))
        return _feather(_phosphor(finish(im), 0.8 if active else 0.3, core=False, spread=2.5, inner=1.2))

    def toggle(self, on):
        w, h = 92, 44
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        _brackets(d, 6, 8, w - 6, h - 8, rgba(AMBER if on else LOW), 2.2, 5)
        f = self.font(26)
        if on:
            _fill(d, (13, 12, w - 13, h - 12), rgba(AMBER))
            d.text((S(w / 2), S(h / 2 + 1)), "ON", font=f, fill=rgba(INK), anchor="mm")
        else:
            d.text((S(w / 2), S(h / 2 + 1)), "OFF", font=f, fill=rgba(DIM), anchor="mm")
        return _feather(_phosphor(finish(im), 0.8 if on else 0.4, core=False, spread=3, inner=1.6))

    def arrow(self, left, size=24):
        im = canvas(size, size)
        d = ImageDraw.Draw(im)
        u = size / 10  # a pixel triangle, one unit per row step
        for i in range(4):
            x0 = size / 2 - u * 2 + i * u
            y0, y1 = size / 2 - u * (4 - i) + u * 0.5, size / 2 + u * (4 - i) - u * 0.5
            _fill(d, (x0, y0, x0 + u, y1), rgba(AMBER))
        out = finish(im)
        if left:
            out = out.transpose(Image.FLIP_LEFT_RIGHT)
        return _feather(_phosphor(out, 0.7, core=False, spread=size / 10, inner=size / 16))

    def panel_image(self, w, h):
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        dialog = w > 500
        # a pop-up's first and last cursor bars come within 13 px of its top and bottom
        box = (30, 10, w - 30, h - 10) if dialog else (3, 3, w - 3, h - 3)
        _fill(d, box, rgba(self.panel, self.panel_alpha))
        base = _scan(finish(im), 0.82)
        fr = canvas(w, h)
        _box(ImageDraw.Draw(fr), box, rgba(AMBER), 1.6, 4.4 if dialog else 4.0, tee=88 if dialog else None)
        bloom = dict(spread=3, inner=2.2) if dialog else dict(spread=1.6, inner=1.2)
        return _feather(_over(base, _phosphor(finish(fr), 0.6, core=False, **bloom)))

    def folder_icon(self):
        im = canvas(60, 60)
        d = ImageDraw.Draw(im)
        poly(d, [(10, 17), (24, 17), (28, 22), (50, 22), (50, 46), (10, 46)], rgba(AMBER), 3.4)
        line(d, [(10, 27), (50, 27)], rgba(AMBER), 2.4)
        return _feather(_phosphor(finish(im), 0.8, spread=4))

    def favorite_mark(self):
        im = canvas(34, 39)
        d = ImageDraw.Draw(im)
        pts = []
        for i in range(10):
            r = 14 if i % 2 == 0 else 6.2
            a = math.radians(-90 + i * 36)
            pts.append((17 + r * math.cos(a), 21 + r * math.sin(a)))
        poly(d, pts, fill=rgba(AMBER))
        return _feather(_phosphor(finish(im), 0.8, core=False, spread=2.5, inner=1.4))

    def battery(self, level, charging=False):
        im = canvas(48, 48)
        d = ImageDraw.Draw(im)
        _rect(d, (5, 14, 40, 34), rgba(AMBER), 2.4)
        _fill(d, (40, 19.5, 44, 28.5), rgba(AMBER))
        cells = 0 if level <= 0.05 else max(1, min(4, int(level * 4 + 0.5)))
        for i in range(cells):
            _fill(d, (9 + i * 7.6, 18, 9 + i * 7.6 + 5.6, 30), rgba(AMBER))
        if level <= 0.2 and not charging:  # "!" in the empty part
            x = 22 if cells == 0 else 27
            _fill(d, (x, 17.5, x + 2.6, 26), rgba(AMBER))
            _fill(d, (x, 28, x + 2.6, 30.6), rgba(AMBER))
        if charging:
            poly(d, [(26, 9), (15, 26), (22, 26), (19, 40), (31, 21), (24, 21), (28, 9)], fill=rgba(INK))
            poly(d, [(26, 9), (15, 26), (22, 26), (19, 40), (31, 21), (24, 21), (28, 9)], rgba(AMBER), 1.6)
        return _feather(_phosphor(finish(im), 0.7, core=False, spread=2.5, inner=1.4))

    def preview_frame(self):
        w, h = 250, 360
        im = canvas(w, h)
        _fill(ImageDraw.Draw(im), (4, 4, w - 4, h - 4), rgba(VOID, 200))
        fr = canvas(w, h)
        _box(ImageDraw.Draw(fr), (4, 4, w - 4, h - 4), rgba(LOW), 1.5, 4.2)
        return _feather(_over(finish(im), _phosphor(finish(fr), 0.5, core=False, spread=3, inner=1.6)))

    def key(self, focused):
        w, h = 64, 52
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        if focused:  # MainUI writes key labels in white: the fill stays dark enough for them
            _fill(d, (3, 3, w - 3, h - 3), rgba(self.accent))
            _rect(d, (5.2, 5.2, w - 5.2, h - 5.2), rgba(HOT), 1.0)
            fr = canvas(w, h)
            _rect(ImageDraw.Draw(fr), (3, 3, w - 3, h - 3), rgba(AMBER), 2.2)
            return _feather(_over(_scan(finish(im), 0.86), _phosphor(finish(fr), 0.5, core=False, spread=1.5, inner=1.2)))
        else:
            _fill(d, (2, 2, w - 2, h - 2), rgba(VOID, 210))
            _rect(d, (2, 2, w - 2, h - 2), rgba(LOW), 1.0)
        return finish(im)

    def input_box(self):
        w, h = 560, 60
        im = canvas(w, h)
        _fill(ImageDraw.Draw(im), (4, 4, w - 4, h - 4), rgba(VOID, 235))
        fr = canvas(w, h)
        _box(ImageDraw.Draw(fr), (4, 4, w - 4, h - 4), rgba(AMBER), 1.4, 4)
        return _feather(_over(finish(im), _phosphor(finish(fr), 0.5, core=False, spread=2.5, inner=1.4)))

    # ---- system and app icons -----------------------------------------------------------------
    def icon_picture(self, sid, selected, cy):
        im = canvas(120, 130)
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        draw(Pen(ImageDraw.Draw(im), 60, cy, rgba(INK if selected else AMBER), SS, w=3.4))
        out = finish(im)
        return out if selected else _phosphor(out, 0.75)

    def badge(self, label, selected):
        return _clear(120, 130)  # MainUI writes every system's name under its icon

    def system_icon(self, sid, selected):
        return self.icon_picture(sid, selected, 62)

    def app_icon(self, key):
        n = 96
        base = canvas(n, n)
        _fill(ImageDraw.Draw(base), (6, 6, n - 6, n - 6), rgba(VOID, 235))
        fr = canvas(n, n)
        _box(ImageDraw.Draw(fr), (6, 6, n - 6, n - 6), rgba(LOW), 1.6, 4.4)
        g = canvas(n, n)
        APPS[key](ImageDraw.Draw(g), n / 2, n / 2, n * 0.225, rgba(AMBER), n * 0.05)
        return _feather(_over(finish(base), _phosphor(finish(fr), 0.5, core=False), _phosphor(finish(g), 0.9)))

    # ---- sound and config -------------------------------------------------------------------
    def sound(self, path):
        self.change_sound(path, freq=1250, decay=260, length=0.03, volume=5200, square=True)

    def config(self):
        cfg = super().config()
        cfg["grid"]["selectedcolor"] = hexc(INK)  # selected tiles and cells are lit amber
        cfg["currentpage"]["color"] = hexc(HOT)
        cfg["total"]["color"] = hexc(DIM)
        return cfg
