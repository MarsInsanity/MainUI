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
DEEP = (116, 68, 0)        # dim reverse video (selection bar)
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


def _phosphor(im, glow=1.0, core=True):
    """Lit phosphor: an amber bloom around [im]'s strokes and hot centres in the thick ones."""
    a = im.split()[3]
    layers = [_paint(a.filter(ImageFilter.GaussianBlur(7)), FLARE, 0.5 * glow),
              _paint(a.filter(ImageFilter.GaussianBlur(2.2)), AMBER, 0.75 * glow), im]
    if core:
        layers.append(_paint(a.filter(ImageFilter.MinFilter(3)), HOT, 0.8))
    return _over(*layers)


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


def _corners(d, box, c, w=2, arm=12):
    x0, y0, x1, y1 = box
    for x, y, sx, sy in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        _fill(d, (min(x, x + sx * arm), min(y, y + sy * w), max(x, x + sx * arm), max(y, y + sy * w)), c)
        _fill(d, (min(x, x + sx * w), min(y, y + sy * arm), max(x, x + sx * w), max(y, y + sy * arm)), c)


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
    muted = DIM
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
        w, h = 176, 44
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        f = self.font(36)
        d.text((S(4), S(23)), ">", font=f, fill=rgba(DIM), anchor="lm")
        d.text((S(24), S(23)), "MAINUI", font=f, fill=rgba(AMBER), anchor="lm")
        tw = d.textlength("MAINUI", font=f) / SS
        _fill(d, (24 + tw + 4, 11, 24 + tw + 18, 34), rgba(AMBER))
        return _phosphor(finish(im), 0.9)

    # ---- lists ----------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        top, bot = 4, h - 4
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        _fill(d, (12, top, w - 12, bot), rgba(DEEP, 236))
        _fill(d, (12, top, w - 12, top + 1.5), rgba(AMBER, 230))
        _fill(d, (12, bot - 1.5, w - 12, bot), rgba(AMBER, 230))
        bar = _scan(finish(im), 0.78, phase=1)
        cur = canvas(w, h)
        _fill(ImageDraw.Draw(cur), (4, top, 11, bot), rgba(AMBER))  # the cursor block
        return _over(_phosphor(bar, 0.45, core=False), _phosphor(finish(cur), 0.9, core=False))

    def divider(self):
        im = canvas(640, 4)
        d = ImageDraw.Draw(im)
        for x in range(22, 620, 6):
            _fill(d, (x, 1.2, x + 2.4, 2.8), rgba(LOW, 150))
        return finish(im)

    # ---- tiles ----------------------------------------------------------------------------
    def _window(self, key, focused):
        """A main menu tile without its picture: a double-line window, [key]'s code in its top
        border, a shaded status strip under the label."""
        w, h = 156, 278
        box = (8, 8, w - 8, h - 8)
        tee = 234  # below the label, and below a row-view tile's two-line name and game count
        strip = (box[0] + 7, tee + 7, box[2] - 6, box[3] - 6)
        fr = canvas(w, h)
        _box(ImageDraw.Draw(fr), box, rgba(INK if focused else LOW), 1.7, 4.6, tee=tee, title=CODES.get(key),
             font=self.font(21))
        fr = finish(fr)
        if focused:
            lit = _lit(w, h, box)
            halo = _paint(lit.split()[3].filter(ImageFilter.GaussianBlur(6)), FLARE, 0.85)
            return _over(halo, lit, fr, _shade(w, h, strip, rgba(INK, 200)))
        base = canvas(w, h)
        _fill(ImageDraw.Draw(base), box, rgba(VOID, 170))
        return _over(finish(base), _shade(w, h, strip, rgba(LOW, 200)), _phosphor(fr, 0.55, core=False))

    def tile_base(self, focused):
        return self._window(None, focused)

    def tile_glyph(self, key, focused):
        g = canvas(156, 278)
        TILES[key](ImageDraw.Draw(g), 78, 104, 34, rgba(INK if focused else AMBER), 5.2)
        return finish(g) if focused else _phosphor(finish(g), 1.0)

    def home_tile(self, key, focused):
        if not key:
            return self.tile_base(focused)
        return Image.alpha_composite(self._window(key, focused), self.tile_glyph(key, focused))

    def card(self, w, h, focused):
        box = (5, 5, w - 5, h - 5)
        if focused:
            lit = _lit(w, h, box)
            halo = _paint(lit.split()[3].filter(ImageFilter.GaussianBlur(5)), FLARE, 0.8)
            c = canvas(w, h)
            _corners(ImageDraw.Draw(c), (box[0] + 4, box[1] + 4, box[2] - 4, box[3] - 4), rgba(INK), 2, 12)
            return _over(halo, lit, finish(c))
        c = canvas(w, h)
        d = ImageDraw.Draw(c)
        _fill(d, box, rgba(VOID, 90))
        _corners(d, box, rgba(LOW), 2, 12)
        return _phosphor(finish(c), 0.4, core=False)

    # ---- buttons and small parts ------------------------------------------------------------
    def button(self, label, primary):
        n = 54
        im = canvas(n, n)
        d = ImageDraw.Draw(im)
        _brackets(d, 6, 8, n - 6, n - 8, rgba(AMBER), 2.6, 6)
        f = self.font(38)
        if primary:
            _fill(d, (13, 13, n - 13, n - 13), rgba(AMBER))
            d.text((S(n / 2), S(n / 2 + 1)), label, font=f, fill=rgba(INK), anchor="mm")
        else:
            d.text((S(n / 2), S(n / 2 + 1)), label, font=f, fill=rgba(AMBER), anchor="mm")
        return _phosphor(finish(im), 0.8, core=False)

    def pill(self, label, w, h=48):
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        _brackets(d, 5, 9, w - 5, h - 9, rgba(AMBER), 2.4, 5)
        d.text((S(w / 2), S(h / 2 + 1)), label, font=self.font(28), fill=rgba(AMBER), anchor="mm")
        return _phosphor(finish(im), 0.7, core=False)

    def dot(self, active):
        im = canvas(24, 14)
        d = ImageDraw.Draw(im)
        if active:
            _fill(d, (5, 2, 19, 12), rgba(AMBER))
        else:
            _fill(d, (9.5, 5, 14.5, 10), rgba(LOW))
        return _phosphor(finish(im), 0.8 if active else 0.3, core=False)

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
            d.text((S(w / 2), S(h / 2 + 1)), "OFF", font=f, fill=rgba(LOW), anchor="mm")
        return _phosphor(finish(im), 0.8 if on else 0.4, core=False)

    def arrow(self, left, size=24):
        im = canvas(size, size)
        d = ImageDraw.Draw(im)
        u = size / 8  # a pixel triangle, one unit per row step
        for i in range(4):
            x0 = size / 2 - u * 2 + i * u
            y0, y1 = size / 2 - u * (4 - i) + u * 0.5, size / 2 + u * (4 - i) - u * 0.5
            _fill(d, (x0, y0, x0 + u, y1), rgba(AMBER))
        out = finish(im)
        if left:
            out = out.transpose(Image.FLIP_LEFT_RIGHT)
        return _phosphor(out, 0.7, core=False)

    def panel_image(self, w, h):
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        dialog = w > 500
        box = (30, 10, w - 30, h - 10) if dialog else (2, 2, w - 2, h - 2)
        _fill(d, box, rgba(self.panel, self.panel_alpha))
        base = _scan(finish(im), 0.82)
        fr = canvas(w, h)
        _box(ImageDraw.Draw(fr), box, rgba(AMBER), 1.6, 4.4, tee=88 if dialog else None)
        return _over(base, _phosphor(finish(fr), 0.6, core=False))

    def folder_icon(self):
        im = canvas(60, 60)
        d = ImageDraw.Draw(im)
        poly(d, [(10, 17), (24, 17), (28, 22), (50, 22), (50, 46), (10, 46)], rgba(AMBER), 3.4)
        line(d, [(10, 27), (50, 27)], rgba(AMBER), 2.4)
        return _phosphor(finish(im), 0.8)

    def favorite_mark(self):
        im = canvas(34, 39)
        d = ImageDraw.Draw(im)
        pts = []
        for i in range(10):
            r = 14 if i % 2 == 0 else 6.2
            a = math.radians(-90 + i * 36)
            pts.append((17 + r * math.cos(a), 21 + r * math.sin(a)))
        poly(d, pts, fill=rgba(AMBER))
        return _phosphor(finish(im), 0.8, core=False)

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
        return _phosphor(finish(im), 0.7, core=False)

    def preview_frame(self):
        w, h = 250, 360
        im = canvas(w, h)
        _fill(ImageDraw.Draw(im), (4, 4, w - 4, h - 4), rgba(VOID, 200))
        fr = canvas(w, h)
        _box(ImageDraw.Draw(fr), (4, 4, w - 4, h - 4), rgba(LOW), 1.5, 4.2)
        return _over(finish(im), _phosphor(finish(fr), 0.5, core=False))

    def key(self, focused):
        w, h = 64, 52
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        if focused:  # MainUI writes key labels in white
            _fill(d, (2, 2, w - 2, h - 2), rgba(self.accent))
            _rect(d, (2, 2, w - 2, h - 2), rgba(AMBER), 2.2)
        else:
            _fill(d, (2, 2, w - 2, h - 2), rgba(VOID, 210))
            _rect(d, (2, 2, w - 2, h - 2), rgba(LOW), 1.0)
        return finish(im)

    def input_box(self):
        w, h = 560, 60
        im = canvas(w, h)
        _fill(ImageDraw.Draw(im), (2, 2, w - 2, h - 2), rgba(VOID, 235))
        fr = canvas(w, h)
        _box(ImageDraw.Draw(fr), (2, 2, w - 2, h - 2), rgba(AMBER), 1.4, 4)
        return _over(finish(im), _phosphor(finish(fr), 0.5, core=False))

    # ---- system and app icons -----------------------------------------------------------------
    def icon_picture(self, sid, selected, cy):
        im = canvas(120, 130)
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        draw(Pen(ImageDraw.Draw(im), 60, cy, rgba(INK if selected else AMBER), SS, w=3.4))
        out = finish(im)
        return out if selected else _phosphor(out, 0.75)

    def badge(self, label, selected):
        im = canvas(120, 130)
        d = ImageDraw.Draw(im)
        f = self.font(20)
        tw = d.textlength(label, font=f) / SS
        x0, x1 = 60 - tw / 2 - 7, 60 + tw / 2 + 7
        if selected:
            _fill(d, (x0, 105, x1, 125), rgba(INK))
            d.text((S(60), S(115.5)), label, font=f, fill=rgba(AMBER), anchor="mm")
            return finish(im)
        _brackets(d, x0, 105, x1, 125, rgba(LOW), 1.6, 3.5)
        d.text((S(60), S(115.5)), label, font=f, fill=rgba(AMBER), anchor="mm")
        return _phosphor(finish(im), 0.5, core=False)

    def app_icon(self, key):
        n = 96
        base = canvas(n, n)
        _fill(ImageDraw.Draw(base), (6, 6, n - 6, n - 6), rgba(VOID, 235))
        fr = canvas(n, n)
        _box(ImageDraw.Draw(fr), (6, 6, n - 6, n - 6), rgba(LOW), 1.6, 4.4)
        g = canvas(n, n)
        APPS[key](ImageDraw.Draw(g), n / 2, n / 2, n * 0.225, rgba(AMBER), n * 0.05)
        return _over(finish(base), _phosphor(finish(fr), 0.5, core=False), _phosphor(finish(g), 0.9))

    # ---- sound and config -------------------------------------------------------------------
    def sound(self, path):
        self.change_sound(path, freq=1250, decay=260, length=0.03, volume=5200, square=True)

    def config(self):
        cfg = super().config()
        cfg["grid"]["selectedcolor"] = hexc(INK)  # selected tiles and cells are lit amber
        cfg["currentpage"]["color"] = hexc(HOT)
        cfg["total"]["color"] = hexc(DIM)
        return cfg
