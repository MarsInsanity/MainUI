"""Candy: bright, playful neo-brutalism. Cream paper with confetti, colour blocks, thick ink
outlines and hard offset shadows."""
import math
import random
import struct
import wave

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from theme_kit import APPS, GLYPHS, MENU_TILES, SS, Pen, S, Style, finish, hexc, lerp, rgba, system_ids

CREAM = (255, 246, 233)
PAPER = (255, 253, 248)
INK = (43, 45, 66)
WHITE = (255, 255, 255)
PINK = (255, 93, 143)
YELLOW = (255, 201, 60)
MINT = (61, 220, 151)
SKY = (76, 201, 240)
PURPLE = (155, 93, 229)
ORANGE = (255, 138, 61)
RED = (239, 71, 111)
GRAPE = (139, 79, 224)
FOCUS = (255, 120, 162)      # list bars and the focused card: a lighter pink, so ink text on it reads
RASPBERRY = (200, 35, 95)    # focused keyboard key (MainUI writes white letters on keys)
SLATE = (66, 69, 100)
CANDY = [PINK, YELLOW, MINT, SKY, PURPLE, ORANGE, RED]
TILE_COLOR = dict(zip(MENU_TILES, CANDY))
APP_COLOR = {"activity": YELLOW, "download": SKY, "folder": ORANGE, "gallery": PURPLE, "gameswitcher": PINK,
             "pacman": MINT, "retroarch": RED, "search": SKY, "themes": YELLOW, "tweaks": MINT}
# system pictures: body colours in the order systems.json lists them (pink and red kept apart),
# and the second colour for their details
ICON_BODY = [PINK, MINT, YELLOW, PURPLE, RED, SKY, ORANGE]
DETAIL = {PINK: SKY, YELLOW: PINK, MINT: YELLOW, SKY: YELLOW, PURPLE: MINT, ORANGE: SKY, RED: YELLOW}


def pale(c, t=0.72):
    return lerp(c, WHITE, t)


# ---- masks and layers (supersampled) -------------------------------------------------------

def blank(w, h):
    return Image.new("L", (w * SS, h * SS), 0)


def clear(w, h):
    return Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))


def layer(im, color, mask):
    """[im] with [color] painted through [mask] (the colour's alpha scales it)."""
    c = Image.new("RGBA", im.size, rgba(color))
    if len(color) > 3 and color[3] != 255:
        mask = mask.point(lambda v, k=color[3]: v * k // 255)
    c.putalpha(mask)
    return Image.alpha_composite(im, c)


def shift(mask, dx, dy):
    out = Image.new(mask.mode, mask.size, 0)
    out.paste(mask, (int(round(S(dx))), int(round(S(dy)))))
    return out


def rr_mask(w, h, box, r):
    m = blank(w, h)
    ImageDraw.Draw(m).rounded_rectangle([S(v) for v in box], radius=S(max(0, r)), fill=255)
    return m


def grow(mask, r):
    """[mask] dilated by about [r] 1x pixels (the union of shifted copies)."""
    out = mask
    for rad in (r, r * 0.6, r * 0.3):
        for i in range(20):
            a = 2 * math.pi * i / 20
            out = ImageChops.lighter(out, shift(mask, rad * math.cos(a), rad * math.sin(a)))
    return out


def erode(mask, r):
    return ImageChops.invert(grow(ImageChops.invert(mask), r))


def area(mask):
    """Covered pixels of an L mask (partly covered ones in part)."""
    return sum(i * n for i, n in enumerate(mask.histogram())) / 255


def block(im, box, r, fill, line=3.0, shadow=(5, 5)):
    """A neo-brutalist block on [im]: [fill] in an ink outline [line] thick, over a hard shadow."""
    w, h = im.width // SS, im.height // SS
    outer = rr_mask(w, h, box, r)
    if shadow:
        im = layer(im, INK, shift(outer, *shadow))
    im = layer(im, INK, outer)
    x0, y0, x1, y1 = box
    return layer(im, fill, rr_mask(w, h, (x0 + line, y0 + line, x1 - line, y1 - line), r - line))


# ---- stickers: flat shapes outlined in ink, for the menu pictures ---------------------------

class Sticker:
    """Shapes around (cx, cy) in 1x pixels. shape() outlines a group of parts together (one
    outline around their union, [line] thick outside it) and fills them with one colour."""

    def __init__(self, im, cx, cy, line=5.0):
        self.im, self.cx, self.cy, self.line = im, cx, cy, line
        self.w, self.h = im.width // SS, im.height // SS

    def P(self, x, y):
        return S(self.cx + x), S(self.cy + y)

    def mask(self, parts, g=0.0):
        m = blank(self.w, self.h)
        d = ImageDraw.Draw(m)
        for p in parts:
            kind = p[0]
            if kind == "rr":
                (x0, y0, x1, y1), r = p[1], p[2]
                d.rounded_rectangle([*self.P(x0 - g, y0 - g), *self.P(x1 + g, y1 + g)], radius=S(r + g), fill=255)
            elif kind == "el":
                x0, y0, x1, y1 = p[1]
                d.ellipse([*self.P(x0 - g, y0 - g), *self.P(x1 + g, y1 + g)], fill=255)
            elif kind == "ci":
                x, y, r = p[1:4]
                d.ellipse([*self.P(x - r - g, y - r - g), *self.P(x + r + g, y + r + g)], fill=255)
            elif kind == "poly":
                pts = [self.P(x, y) for x, y in p[1]]
                d.polygon(pts, fill=255)
                if g:
                    d.line(pts + [pts[0]], fill=255, width=int(S(2 * g)), joint="curve")
                    for x, y in pts:
                        d.ellipse([x - S(g), y - S(g), x + S(g), y + S(g)], fill=255)
            elif kind == "ln":
                pts = [self.P(x, y) for x, y in p[1]]
                rad = S(p[2] / 2 + g)
                d.line(pts, fill=255, width=int(2 * rad), joint="curve")
                for x, y in pts:
                    d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=255)
        return m

    def shape(self, parts, fill, line=None):
        lw = self.line if line is None else line
        if lw:
            self.im = layer(self.im, INK, self.mask(parts, lw))
        if fill is not None:
            self.im = layer(self.im, fill, self.mask(parts))

    def ink(self, parts):
        self.im = layer(self.im, INK, self.mask(parts))


def star_pts(cx, cy, r0, r1, n=5, rot=-90):
    pts = []
    for i in range(n * 2):
        r = r0 if i % 2 == 0 else r1
        a = math.radians(rot + i * 180 / n)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


# ---- the main menu pictures (fill: the picture's colour, alt: its details', hole: the tile's) ---

def m_recent(st, fill, alt, hole):
    st.ink([("ln", [(-20, 30), (-28, 41)], 7), ("ln", [(20, 30), (28, 41)], 7)])      # legs
    st.shape([("ci", -26, -29, 13)], alt)                                           # bells
    st.shape([("ci", 26, -29, 13)], alt)
    st.shape([("ci", 0, 3, 35)], fill)                                              # face
    st.ink([("ci", 0, -22, 3.4), ("ci", 25, 3, 3.4), ("ci", 0, 28, 3.4), ("ci", -25, 3, 3.4)])
    st.ink([("ln", [(0, -13), (0, 3), (13, 12)], 6.5)])                              # hands
    st.shape([("ci", 0, 3, 4.5)], alt, 3)


def m_favorite(st, fill, alt, hole):
    st.shape([("poly", star_pts(-2, 6, 44, 21))], fill)
    st.shape([("ln", [(-17, -2), (-10, -11)], 5)], alt, 0)                          # shine
    st.shape([("poly", star_pts(36, -32, 12, 4.5, 4, -90))], alt, 3.5)              # sparkle


def m_game(st, fill, alt, hole):
    st.shape([("rr", (-47, -24, 47, 14), 17), ("ci", -29, 13, 18), ("ci", 29, 13, 18)], fill)
    st.ink([("rr", (-37, -7, -15, 0), 2), ("rr", (-29.5, -14.5, -22.5, 7.5), 2)])   # d-pad
    st.shape([("ci", 21, 0, 6.5)], alt, 3.5)                                        # buttons
    st.shape([("ci", 34, -10, 6.5)], alt, 3.5)
    st.ink([("ln", [(-5, -13), (4, -13)], 4.5)])


def m_emulators(st, fill, alt, hole):
    st.shape([("rr", (-31, -45, 31, 45), 11)], fill)
    st.shape([("rr", (-21, -35, 21, 2), 5)], alt, 4)                                # screen
    st.ink([("rr", (-23, 17, -5, 23), 1.6), ("rr", (-17, 11, -11, 29), 1.6)])       # d-pad
    st.shape([("ci", 11, 25, 5)], alt, 3)                                           # buttons
    st.shape([("ci", 21, 15, 5)], alt, 3)


def m_expert(st, fill, alt, hole):
    rows = ["..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.", "XXXXXXXXXXX",
            "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX..."]
    c = 8.2
    x0, y0 = -c * 5.5, -c * 4 + 2
    parts = [("rr", (x0 + i * c - 0.3, y0 + j * c - 0.3, x0 + (i + 1) * c + 0.3, y0 + (j + 1) * c + 0.3), 0.6)
             for j, row in enumerate(rows) for i, ch in enumerate(row) if ch == "X"]
    st.shape(parts, fill, 4.5)


def m_apps(st, fill, alt, hole):
    st.shape([("rr", (-39, -39, -5, -5), 9)], fill)
    st.shape([("ci", 22, -22, 17)], alt)
    st.shape([("rr", (-39, 5, -5, 39), 9)], alt)
    st.shape([("rr", (5, 5, 39, 39), 9)], fill)


def m_setting(st, fill, alt, hole):
    pts = []
    teeth = 8
    for i in range(teeth * 4):
        a = math.radians(i * 360 / (teeth * 4) - 90 + 360 / teeth / 8)
        r = 42 if (i % 4) in (1, 2) else 31
        pts.append((r * math.cos(a), r * math.sin(a)))
    st.shape([("poly", pts)], fill)
    st.shape([("ci", 0, 0, 13)], hole)


def others(col):
    return [c for c in CANDY if c != col]


def a_themes(st, col):
    st.shape([("el", (-27, -22, 27, 22))], WHITE, 3)
    st.shape([("ci", 11, 9, 5.5)], col, 3)
    for (x, y), c in zip(((-14, -6), (-1, -12), (13, -7), (-12, 9)), others(col)):
        st.shape([("ci", x, y, 5)], c, 2.5)


def a_gallery(st, col):
    st.shape([("rr", (-27, -21, 27, 21), 5)], WHITE, 3)
    st.shape([("ci", 12, -8, 5.5)], YELLOW if col != YELLOW else ORANGE, 2.5)
    st.shape([("poly", [(-20, 14), (-7, -5), (2, 6), (9, -1), (20, 14)])], MINT if col != MINT else SKY, 2.5)


def a_tweaks(st, col):
    for x, y in ((-15, -8), (0, 9), (15, -2)):
        st.ink([("ln", [(x, -23), (x, 23)], 4)])
        st.shape([("ci", x, y, 7)], WHITE, 3)


def a_expert(st, col):
    rows = ["..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.", "XXXXXXXXXXX",
            "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX..."]
    c = 5.2
    x0, y0 = -c * 5.5, -c * 4
    st.shape([("rr", (x0 + i * c - 0.25, y0 + j * c - 0.25, x0 + (i + 1) * c + 0.25, y0 + (j + 1) * c + 0.25), 0.4)
              for j, row in enumerate(rows) for i, ch in enumerate(row) if ch == "X"], WHITE, 3)


APP_STICKERS = {"themes": a_themes, "gallery": a_gallery, "tweaks": a_tweaks, "retroarch": a_expert}

MENU = {"recent": m_recent, "favorite": m_favorite, "game": m_game, "emulators": m_emulators,
        "retroarch": m_expert, "app": m_apps, "setting": m_setting}


# ---- system pictures: GLYPHS line art, chunky ink over flat colour ----------------------------

class CandyPen(Pen):
    """A Pen drawn [sc] times bigger with lines [k] times thicker, whose soft fills go to their
    own mask instead of the line layer."""

    def __init__(self, draw, soft_draw, cx, cy, sc=1.0, k=1.0, w=3.4):
        super().__init__(draw, cx, cy, (255, 255, 255, 255), SS, w)
        self.sd, self.sc, self.k = soft_draw, sc, k

    def p(self, x, y):
        return ((self.cx + x * self.sc) * self.ss, (self.cy + y * self.sc) * self.ss)

    def _w(self, w):
        return max(1, int(round((self.w if w is None else w) * self.k * self.sc * self.ss)))

    def rr(self, x0, y0, x1, y1, r=3, w=None, fill=False, soft=False):
        rad = min(r, abs(x1 - x0) / 2, abs(y1 - y0) / 2) * self.sc * self.ss
        box = [*self.p(x0, y0), *self.p(x1, y1)]
        if soft and not fill:
            self.sd.rounded_rectangle(box, radius=rad, fill=255)
        if fill:
            self.d.rounded_rectangle(box, radius=rad, fill=self.c)
        else:
            self.d.rounded_rectangle(box, radius=rad, outline=self.c, width=self._w(w))

    def poly(self, pts, w=None, fill=False, soft=False):
        if soft and not fill:
            self.sd.polygon([self.p(x, y) for x, y in pts], fill=255)
        super().poly(pts, w, fill)

    def ci(self, x, y, r, w=None, fill=False, soft=False):
        if soft and not fill:
            self.sd.ellipse([*self.p(x - r, y - r), *self.p(x + r, y + r)], fill=255)
        super().ci(x, y, r, w, fill)

    def el(self, x0, y0, x1, y1, w=None, fill=False, soft=False):
        if soft and not fill:
            self.sd.ellipse([*self.p(x0, y0), *self.p(x1, y1)], fill=255)
        super().el(x0, y0, x1, y1, w, fill)


def enclosed(walls, w, h):
    """The areas [walls] (SS-size L mask) close in completely, found at 2x: SS-size L mask."""
    w2, h2 = w * 2, h * 2
    wall = walls.resize((w2, h2), Image.BOX).point(lambda v: 255 if v > 50 else 0)
    pad = Image.new("L", (w2 + 2, h2 + 2), 0)
    pad.paste(wall, (1, 1))
    ImageDraw.floodfill(pad, (0, 0), 128)
    inside = pad.crop((1, 1, w2 + 1, h2 + 1)).point(lambda v: 255 if v == 0 else 0)
    # (grown a little under the lines so no gap shows between fill and outline)
    return inside.filter(ImageFilter.MaxFilter(3)).resize((w * SS, h * SS), Image.BILINEAR)


def candy_picture(w, h, cx, cy, draw_fn, body, detail, sc=1.0, k=1.3, drop=(3, 3)):
    """GLYPHS drawing [draw_fn] as a sticker: ink lines over a [body] fill, [detail] soft parts
    and a hard ink shadow. Returns the 1x image."""
    lines, soft = clear(w, h), blank(w, h)
    draw_fn(CandyPen(ImageDraw.Draw(lines), ImageDraw.Draw(soft), cx, cy, sc, k))
    la = lines.split()[3]
    # (pixels a drawing paints in its own colour, such as eyes, are holes in the line work)
    other = ImageChops.multiply(la, lines.convert("L").point(lambda v: 255 if v < 128 else 0))
    la = ImageChops.subtract(la, other)
    # hairline seams between abutting squares (pixel art) closed
    la = la.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5))
    inside = enclosed(ImageChops.lighter(la, other), w, h)
    every = ImageChops.lighter(ImageChops.lighter(la, inside), soft)
    rim = None
    if area(ImageChops.subtract(ImageChops.lighter(inside, soft), la)) < 0.05 * area(every):
        # almost no colour: solid shapes get the body colour inside an ink outline...
        n = 2 * round(3 * SS) + 1
        solid = la.filter(ImageFilter.MinFilter(n)).filter(ImageFilter.MaxFilter(n))
        core = erode(solid, 4.2 * sc)
        if area(core) > 0.25 * area(la):
            la = ImageChops.subtract(la, core)
            inside = ImageChops.lighter(inside, core)
        else:  # ...bare lines a candy rim
            rim = grow(la, 3.6)
            every = ImageChops.lighter(every, rim)
            drop = (2, 2)
    out = clear(w, h)
    if drop:
        out = layer(out, INK, shift(every, *drop))
    if rim is not None:
        out = layer(out, body, rim)
    out = layer(out, body, inside)
    out = layer(out, detail, soft)
    out = layer(out, WHITE, other)
    out = layer(out, INK, la)
    return finish(out)


class Theme(Style):
    name = "Candy"
    author = "MainUI"
    description = "Bright, playful neo-brutalism: confetti on cream, candy colour blocks, thick ink outlines and hard shadows."

    bg_top = CREAM
    bg_bottom = CREAM
    accent = PINK              # MainUI's toasts, progress bars, row-view glow and its own fallbacks
    accent2 = INK
    highlight = GRAPE          # the page counter's current page
    text = INK
    muted = INK
    glyph_n = INK
    glyph_f = WHITE
    panel = YELLOW            # pop-up title strips (20 % darker), toasts and info panels
    panel_alpha = 250

    font_file = "Fredoka-SemiBold.ttf"
    font_license = "OFL-Fredoka.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 28, 22, 23, 21, 14, 17
    icon_quant = 64
    sel_icons = True          # selected: a white body, like the main menu's selected pictures

    # ---- big pictures -------------------------------------------------------------------
    def background(self, w=640, h=480):
        """Confetti. MainUI cover-scales the picture, so pieces can land anywhere under text on
        screens that are not 4:3: those in the list area are pale and small."""
        im = Image.new("RGBA", (w * SS, h * SS), rgba(CREAM))
        d = ImageDraw.Draw(im)
        rnd = random.Random(11)
        placed = []
        kinds = ["dot", "sprinkle", "tri", "squiggle", "ring", "sprinkle", "plus", "dot"]
        last = None
        tries = 0
        while len(placed) < 66 and tries < 20000:
            tries += 1
            x, y = rnd.uniform(6, w - 6), rnd.uniform(66, h - 66)
            if any(math.hypot(x - px, y - py) < 36 for px, py in placed):
                continue
            if 380 < y < 414 and 140 < x < 500:                          # page dots
                continue
            if 552 < x < 616 and min(abs(y - 92 - 60 * i) for i in range(6)) < 21:  # favourite stars
                continue
            placed.append((x, y))
            col = rnd.choice([c for c in CANDY if c != last])
            last = col
            k = 1.0
            if x < 560 and y > 75:
                col, k = lerp(col, CREAM, 0.4), 0.8
            c = rgba(col)
            kind = kinds[len(placed) % len(kinds)]
            a = rnd.uniform(0, math.pi)
            if kind == "dot":
                r = rnd.uniform(3.5, 5.5) * k
                d.ellipse([S(x - r), S(y - r), S(x + r), S(y + r)], fill=c)
            elif kind == "ring":
                r = rnd.uniform(5, 6.5) * k
                d.ellipse([S(x - r), S(y - r), S(x + r), S(y + r)], outline=c, width=int(S(2.6 * k)))
            elif kind == "sprinkle":
                ln = rnd.uniform(6, 8) * k
                dx, dy = ln * math.cos(a), ln * math.sin(a)
                d.line([S(x - dx), S(y - dy), S(x + dx), S(y + dy)], fill=c, width=int(S(4.6 * k)))
                for ex, ey in ((x - dx, y - dy), (x + dx, y + dy)):
                    e = 2.3 * k
                    d.ellipse([S(ex - e), S(ey - e), S(ex + e), S(ey + e)], fill=c)
            elif kind == "tri":
                r = rnd.uniform(6, 8) * k
                pts = [(S(x + r * math.cos(a + i * 2.094)), S(y + r * math.sin(a + i * 2.094))) for i in range(3)]
                d.polygon(pts, fill=c)
            elif kind == "plus":
                r = 5.5 * k
                for b in (a, a + math.pi / 2):
                    dx, dy = r * math.cos(b), r * math.sin(b)
                    d.line([S(x - dx), S(y - dy), S(x + dx), S(y + dy)], fill=c, width=int(S(3 * k)))
            elif kind == "squiggle":
                pts = []
                for i in range(15):
                    t = (i / 14 - 0.5) * 26 * k
                    v = 3.6 * k * math.sin(i / 14 * 2 * math.pi * 1.5)
                    pts.append((S(x + t * math.cos(a) - v * math.sin(a)), S(y + t * math.sin(a) + v * math.cos(a))))
                d.line(pts, fill=c, width=int(S(3 * k)), joint="curve")
        return finish(im)

    def header_bar(self):
        """Paper with a candy-stripe ribbon along its bottom edge."""
        im = Image.new("RGBA", (640 * SS, 60 * SS), rgba(PAPER))
        stripes = blank(640, 60)
        d = ImageDraw.Draw(stripes)
        for x in range(-20, 660, 12):
            d.polygon([(S(x), S(57)), (S(x + 6), S(57)), (S(x + 11), S(51)), (S(x + 5), S(51))], fill=255)
        im = layer(im, PINK, ImageChops.multiply(stripes, rr_mask(640, 60, (-5, 51, 645, 57), 0)))
        im = layer(im, INK, rr_mask(640, 60, (-5, 49, 645, 51.5), 0))
        return finish(layer(im, INK, rr_mask(640, 60, (-5, 57, 645, 60), 0)))

    def footer_bar(self):
        im = Image.new("RGBA", (640 * SS, 60 * SS), rgba(PAPER))
        return finish(layer(im, INK, rr_mask(640, 60, (-5, 0, 645, 3), 0)))

    def logo(self):
        w, h = 160, 46
        im = clear(w, h)
        f = self.font(34)
        word = "MainUI"
        cols = [PINK, YELLOW, MINT, SKY, PURPLE, ORANGE]
        d = ImageDraw.Draw(im)
        x = 6
        for dx, dy, solid in ((2.5, 2.5, True), (0, 0, False)):
            xx = x
            for ch, col in zip(word, cols):
                d.text((S(xx + dx), S(23 + dy)), ch, font=f, anchor="lm", fill=rgba(INK if solid else col),
                       stroke_width=int(S(2.4)), stroke_fill=rgba(INK))
                xx += d.textlength(ch, font=f) / SS + 2
        return finish(im)

    # ---- lists --------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        im = block(clear(w, h), (9, 5, 621, h - 7), 13, FOCUS, 3, (5, 5))
        return finish(im)

    def divider(self):
        im = clear(640, 4)
        d = ImageDraw.Draw(im)
        for x in range(22, 620, 14):
            d.rounded_rectangle([S(x), S(1), S(x + 7), S(3)], radius=S(1), fill=rgba(INK, 60))
        return finish(im)

    # ---- tiles --------------------------------------------------------------------------
    def plate(self, im, focused):
        box = (19, 180, 137, 210)
        if focused:
            return layer(im, INK, rr_mask(156, 278, box, 15))
        return block(im, box, 15, WHITE, 2.5, None)

    @staticmethod
    def halftone(im, color, box, rows=6):
        """Dots fading up from the bottom edge of the tile [box]."""
        dots = blank(156, 278)
        d = ImageDraw.Draw(dots)
        for row in range(rows):
            y = box[3] - 7 - row * 6.5
            r = 2.5 - row * 0.4
            for col in range(-1, 24):
                x = box[0] + 4 + col * 6.5 + (3.25 if row % 2 else 0)
                d.ellipse([S(x - r), S(y - r), S(x + r), S(y + r)], fill=255)
        inner = rr_mask(156, 278, (box[0] + 3.5, box[1] + 3.5, box[2] - 3.5, box[3] - 3.5), 14.5)
        return layer(im, color, ImageChops.multiply(dots, inner))

    def candy_tile(self, color, focused):
        """Main menu tile: centred on x 78, where MainUI writes the label and the pictures sit."""
        dy = -4 if focused else 0
        box = (11, 10 + dy, 145, 262 + dy)
        im = block(clear(156, 278), box, 18, color if focused else pale(color), 3.5, (6, 6 - dy))
        im = self.halftone(im, lerp(color, INK, 0.12) if focused else pale(color, 0.45), box)
        return self.plate(im, focused)

    def row_tile(self, focused):
        """bg-home-tile: the system row's tile. MainUI fits system names into 142 px (two lines
        for long ones, 170-204) and writes the game count at 222, so the tile is wider than the
        main menu's and carries a full-width label band (ink when focused, for the white text)."""
        dy = -4 if focused else 0
        box = (5, 10 + dy, 150, 262 + dy)
        im = block(clear(156, 278), box, 18, PINK if focused else pale(PINK), 3.5, (6, 6 - dy))
        im = self.halftone(im, lerp(PINK, INK, 0.12) if focused else pale(PINK, 0.45), box, 3)
        x0, x1 = box[0] + 3.5, box[2] - 3.5
        if focused:
            return layer(im, INK, rr_mask(156, 278, (x0, 162, x1, 235), 0))
        im = layer(im, INK, rr_mask(156, 278, (x0, 162, x1, 235), 0))
        return layer(im, PAPER, rr_mask(156, 278, (x0, 164.5, x1, 232.5), 0))

    def tile_base(self, focused):
        return finish(self.row_tile(focused))

    def tile_glyph(self, key, focused):
        main = TILE_COLOR[key]
        fill, alt, hole = (WHITE, main, main) if focused else (main, WHITE, pale(main))
        st = Sticker(clear(156, 278), 78, 108 if focused else 112)
        MENU[key](st, fill, alt, hole)
        g = st.im
        out = layer(clear(156, 278), INK, shift(g.split()[3], 4, 4))
        return Image.alpha_composite(out, g)

    def home_tile(self, key, focused):
        if key is None:
            return self.tile_base(focused)
        base = self.candy_tile(TILE_COLOR[key], focused)
        return finish(Image.alpha_composite(base, self.tile_glyph(key, focused)))

    def card(self, w, h, focused):
        """Games grid cell: a white card with the name on a strip under the picture. Its top
        stays clear of the header line (the grid starts at y 55)."""
        im = block(clear(w, h), (5, 9, w - 10, h - 7), 14, FOCUS if focused else PAPER, 3,
                   (7, 6) if focused else (5, 5))
        strip = (8, h - 34, w - 13, h - 10)
        im = layer(im, INK, rr_mask(w, h, (8, h - 37, w - 13, h - 34), 0))
        if focused:
            im = layer(im, INK, rr_mask(w, h, strip, 11))
            im = layer(im, INK, rr_mask(w, h, (strip[0], strip[1], strip[2], strip[1] + 12), 0))
        return finish(im)

    # ---- buttons and small parts ----------------------------------------------------------
    BUTTONS = {"A": PINK, "B": YELLOW, "X": SKY, "Y": MINT}

    def button(self, label, primary):
        n = 54
        im = clear(n, n)
        im = layer(im, INK, shift(self._circle(n, 25.5, 25.5, 22.5), 3, 3))
        im = layer(im, INK, self._circle(n, 25.5, 25.5, 22.5))
        im = layer(im, self.BUTTONS[label], self._circle(n, 25.5, 25.5, 19.5))
        d = ImageDraw.Draw(im)
        d.text((S(25.5), S(26.5)), label, font=self.font(25), fill=rgba(INK), anchor="mm")
        return finish(im)

    @staticmethod
    def _circle(n, cx, cy, r, h=None):
        m = blank(n, h or n)
        ImageDraw.Draw(m).ellipse([S(cx - r), S(cy - r), S(cx + r), S(cy + r)], fill=255)
        return m

    def pill(self, label, w, h=48):
        im = block(clear(w, h), (3, 9, w - 7, h - 12), 13.5, WHITE, 2.5, (3, 3))
        d = ImageDraw.Draw(im)
        d.text((S((w - 4) / 2), S(h / 2 - 1)), label, font=self.font(15), fill=rgba(INK), anchor="mm")
        return finish(im)

    def dot(self, active):
        im = clear(24, 14)
        if active:
            im = block(im, (2, 1.5, 20.5, 11), 4.75, PINK, 2, (1.5, 1.5))
        else:
            im = layer(im, INK, shift(self._circle(24, 11, 6.5, 4.6, 14), 1.2, 1.2))
            im = layer(im, INK, self._circle(24, 11, 6.5, 4.6, 14))
            im = layer(im, WHITE, self._circle(24, 11, 6.5, 2.7, 14))
        return finish(im)

    def toggle(self, on):
        w, h = 92, 44
        im = block(clear(w, h), (5, 8, 83, 36), 14, MINT if on else (236, 228, 214), 3, (3, 3))
        cx = 69 if on else 19
        im = layer(im, INK, self._circle(w, cx, 22, 11.5, h))
        im = layer(im, WHITE, self._circle(w, cx, 22, 8.5, h))
        return finish(im)

    def arrow(self, left, size=24):
        s = size
        im = clear(s, s)
        pts = [(0.22, 0.5), (0.74, 0.18), (0.74, 0.82)] if left else [(0.78, 0.5), (0.26, 0.18), (0.26, 0.82)]
        st = Sticker(im, 0, 0, line=s / 12)
        tri = [(x * s - s * 0.03, y * s - s * 0.03) for x, y in pts]
        sh = st.mask([("poly", tri)], s / 12)
        st.im = layer(st.im, INK, shift(sh, s / 16, s / 16))
        st.shape([("poly", tri)], PINK)
        return finish(st.im)

    def panel_image(self, w, h):
        if w >= 600:  # pop-bg (dialogs): a yellow title band over a white card
            box = (40, 22, w - 46, h - 14)
            im = block(clear(w, h), box, 18, PAPER, 3.5, (6, 6))
            band = rr_mask(w, h, (box[0] + 3.5, box[1] + 3.5, box[2] - 3.5, 90), 14.5)
            band = ImageChops.lighter(band, rr_mask(w, h, (box[0] + 3.5, 60, box[2] - 3.5, 90), 0))
            im = layer(im, YELLOW, band)
            stripes = blank(w, h)
            d = ImageDraw.Draw(stripes)
            for x in range(box[0], box[2], 12):
                d.polygon([(S(x), S(96)), (S(x + 6), S(96)), (S(x + 11), S(90)), (S(x + 5), S(90))], fill=255)
            im = layer(im, PINK, ImageChops.multiply(stripes, rr_mask(w, h, (box[0] + 3.5, 90, box[2] - 3.5, 96), 0)))
            im = layer(im, INK, rr_mask(w, h, (box[0] + 3.5, 88, box[2] - 3.5, 90.5), 0))
            im = layer(im, INK, rr_mask(w, h, (box[0] + 3.5, 96, box[2] - 3.5, 99), 0))
            return finish(im)
        # bg-pop-menu-N (stretched to 400 x 60N+10, and for the in-game menu to 314 wide):
        # square on top, where MainUI puts its title strip, the full width of it
        im = clear(w, h)
        outer = rr_mask(w, h, (0, -30, w, h - 5), 14)
        im = layer(im, INK, shift(outer, 0, 5))
        im = layer(im, INK, outer)
        im = layer(im, YELLOW, rr_mask(w, h, (3, -30, w - 3, h - 8), 11))
        return finish(layer(im, INK, rr_mask(w, h, (0, 0, w, 3), 0)))

    def folder_icon(self):
        im = clear(60, 60)
        st = Sticker(im, 30, 31, line=3)
        sh = st.mask([("rr", (-20, -17, 20, 16), 4)], 3)
        st.im = layer(st.im, INK, shift(sh, 2.5, 2.5))
        st.shape([("rr", (-20, -17, -3, -6), 3), ("rr", (-20, -11, 20, 16), 4)], ORANGE)
        st.shape([("rr", (-20, -4, 20, 16), 4)], YELLOW)
        return finish(st.im)

    def favorite_mark(self):
        st = Sticker(clear(34, 39), 16, 19, line=2.6)
        pts = star_pts(0, 0, 14, 6.6)
        st.im = layer(st.im, INK, shift(st.mask([("poly", pts)], 2.6), 2, 2))
        st.shape([("poly", pts)], YELLOW)
        return finish(st.im)

    def battery(self, level, charging=False):
        im = clear(48, 48)
        im = block(im, (4, 14, 39, 34), 5, WHITE, 3, (2, 2))
        im = layer(im, INK, rr_mask(48, 48, (38, 19.5, 44, 28.5), 2))
        if level > 0:
            col = RED if level <= 0.2 else MINT
            im = layer(im, col, rr_mask(48, 48, (8.5, 18.5, 8.5 + 26 * level, 29.5), 2))
        if charging:
            st = Sticker(im, 0, 0, line=2.2)
            st.shape([("poly", [(26, 6), (14, 26), (22, 26), (19, 42), (33, 20), (25, 20)])], YELLOW)
            im = st.im
        return finish(im)

    def preview_frame(self):
        return finish(block(clear(250, 360), (2, 2, 242, 352), 16, PAPER, 3.5, (6, 6)))

    def key(self, focused):
        """Drawn at about the real key size (52 x 38) with its shadow straight down, so the
        stretch to wide keys (SPACE is 182) does not smear it."""
        w, h = 52, 40
        box = (1.5, 1.5, 50.5, 36.5)
        outer = rr_mask(w, h, box, 7)
        im = layer(clear(w, h), INK, shift(outer, 0, 3))
        im = layer(im, YELLOW if focused else INK, outer)
        t, ts = (3, 2.2) if focused else (2.5, 2)  # (thinner at the sides, which stretch)
        return finish(layer(im, RASPBERRY if focused else SLATE,
                            rr_mask(w, h, (box[0] + ts, box[1] + t, box[2] - ts, box[3] - t), 7 - t)))

    def input_box(self):
        return finish(block(clear(560, 60), (2, 3, 553, 54), 12, WHITE, 3, (5, 5)))

    # ---- system and app icons -----------------------------------------------------------
    _order = None
    _scales = {}

    def icon_colors(self, sid):
        if Theme._order is None:
            Theme._order = {s: i for i, s in enumerate(system_ids())}
        body = ICON_BODY[Theme._order.get(sid, len(sid)) % len(ICON_BODY)]
        return body, DETAIL[body]

    def icon_scale(self, sid, k):
        """Up to 1.12 times the drawing's size, as long as it stays inside the icon."""
        if sid not in self._scales:
            lines = clear(160, 160)
            m = blank(160, 160)
            (GLYPHS.get(sid) or GLYPHS["fallback"])(CandyPen(ImageDraw.Draw(lines), ImageDraw.Draw(m), 80, 80, 1.0, k))
            x0, y0, x1, y1 = ImageChops.lighter(lines.split()[3], m).getbbox()
            hx, hy = max(80 - x0 / SS, x1 / SS - 80), max(80 - y0 / SS, y1 / SS - 80)
            self._scales[sid] = min(1.12, 54 / hx, 58 / hy)
        return self._scales[sid]

    def icon_picture(self, sid, selected, cy):
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        body, detail = self.icon_colors(sid)
        if selected:
            body, detail = WHITE, body
        k = 1.3
        return candy_picture(120, 130, 58.5, cy, draw, body, detail, self.icon_scale(sid, k), k)

    def system_icon(self, sid, selected):
        # (no name badge: MainUI writes the name under each picture, and badges carry brand names)
        return self.icon_picture(sid, selected, 63.5)

    def app_icon(self, key):
        n = 96
        col = APP_COLOR.get(key, PINK)
        im = block(clear(n, n), (5, 5, 86, 86), 22, col, 3.5, (5, 5))
        if key in APP_STICKERS:
            st = Sticker(im, 45.5, 45.5)
            APP_STICKERS[key](st, col)
            return finish(st.im)
        g = clear(n, n)
        APPS[key](ImageDraw.Draw(g), 45.5, 45.5, n * 0.24, (255, 255, 255, 255), n * 0.065)
        ga = g.split()[3]
        im = layer(im, INK, grow(ga, 3))
        im = layer(im, WHITE, ga)
        return finish(im)

    # ---- sound and config -----------------------------------------------------------------
    def sound(self, path):
        """A short rising 'bloop', faded to silence at the end (no click)."""
        rate, length = 22050, 0.055
        frames = bytearray()
        ph = 0.0
        for i in range(int(rate * length)):
            t = i / rate
            f = 560 + 1100 * (t / length) ** 0.7
            ph += 2 * math.pi * f / rate
            env = min(1.0, t / 0.003, (length - t) / 0.004) * math.exp(-t * 60)
            frames += struct.pack("<h", int(9000 * env * math.sin(ph)))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))

    def config(self):
        cfg = super().config()
        cfg["grid"]["selectedcolor"] = hexc(WHITE)  # (on the ink plates, strips and bands)
        return cfg
