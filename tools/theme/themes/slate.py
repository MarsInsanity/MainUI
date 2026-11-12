"""Slate: modern Material-style dark. Tonal surfaces, pill selection and filled-tonal lavender icons."""
import math

from PIL import Image, ImageDraw, ImageFilter

from theme_kit import (APPS, BADGES, GLYPHS, SS, Pen, S, Style, arc, canvas, circle, finish, hexa, hexc, line, poly,
                       rgba, rrect)

# ---- palette ------------------------------------------------------------------------------
BG_A = (31, 33, 42)          # top left of the diagonal gradient
BG_B = (22, 23, 29)          # bottom right
BG = (27, 29, 35)            # #1B1D23
SURFACE = (37, 40, 48)       # #252830, cards
SURFACE_HI = (45, 48, 59)    # keys, fields, switch track
SHEET = (41, 44, 56)         # pop-up sheets (MainUI's title band is 0.8 x the mainui panel colour)
BAR = (33, 35, 43)           # footer
INDIGO = (108, 124, 255)     # #6C7CFF
INDIGO_DEEP = (86, 100, 232)  # solid indigo under white text (4.8:1)
INDIGO_HI = (186, 195, 255)  # active page dot, page counter
SEL = (51, 57, 90)           # SURFACE + 20 % indigo: the tonal container of a selected card
LAV = (199, 203, 255)        # icon lines
LAV_DIM = (176, 182, 230)    # unselected menu glyphs
ON = (230, 232, 242)         # text
ON_VAR = (163, 168, 186)     # secondary text
ON_SEL = (238, 240, 255)     # text on a selected container
OUTLINE = (141, 147, 166)    # switch off
AMBER = (255, 198, 110)
RED = (255, 118, 118)
WHITE = (255, 255, 255)

R_CARD = 22


# ---- masks and layers -----------------------------------------------------------------------

def _mask(w, h, paint):
    """Antialiased L mask (1x) of what [paint](ImageDraw, S) draws on a supersampled canvas."""
    m = Image.new("L", (w * SS, h * SS), 0)
    paint(ImageDraw.Draw(m))
    return m.resize((w, h), Image.LANCZOS)


def _rr_mask(w, h, box, r):
    return _mask(w, h, lambda d: d.rounded_rectangle([v * SS for v in box], radius=r * SS, fill=255))


def _ring_mask(w, h, box, r, width):
    """Outline of a rounded rect, [width] wide, just inside [box]."""
    def paint(d):
        d.rounded_rectangle([v * SS for v in box], radius=r * SS, fill=255)
        e = width
        d.rounded_rectangle([(box[0] + e) * SS, (box[1] + e) * SS, (box[2] - e) * SS, (box[3] - e) * SS],
                            radius=max(0, r - e) * SS, fill=0)
    return _mask(w, h, paint)


def _layer(mask, color, k=1.0):
    """[color] (RGB / RGBA) where [mask] is; alpha scaled by [k]."""
    a = color[3] / 255 if len(color) > 3 else 1.0
    im = Image.new("RGBA", mask.size, rgba(color))
    im.putalpha(mask.point(lambda v: int(round(v * a * k))))
    return im


def _shift(mask, dx, dy):
    out = Image.new("L", mask.size, 0)
    out.paste(mask, (dx, dy))
    return out


def _over(*layers):
    out = layers[0]
    for la in layers[1:]:
        out = Image.alpha_composite(out, la)
    return out


def _top_rim(w, h, box, r, inset=0.0):
    """The 1 px highlight along the top edge of a rounded rect (L mask, fading down the corners)."""
    def paint(d):
        b = [(box[0] + inset) * SS, (box[1] + inset) * SS, (box[2] - inset) * SS, (box[3] - inset) * SS]
        d.rounded_rectangle(b, radius=max(1, r - inset) * SS, fill=255)
        b2 = [b[0], b[1] + SS * 1.2, b[2], b[3] + SS * 1.2]
        d.rounded_rectangle(b2, radius=max(1, r - inset) * SS, fill=0)
    return _mask(w, h, paint)


def _vgrad(w, h, top, bottom):
    col = Image.new("RGBA", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        col.putpixel((0, y), tuple(int(round(top[i] + (bottom[i] - top[i]) * t)) for i in range(3)) + (255,))
    return col.resize((w, h), Image.NEAREST)


def _tone(c, k):
    return tuple(max(0, min(255, int(round(v * k)))) for v in c[:3])


def surface(w, h, box, r, fill, rim=20, shadow=(3, 6, 120), outline=None, ow=2.0, halo=None, lift=0.0):
    """An elevated rounded surface: ambient shadow, fill (a touch lighter at the top by [lift]),
    1 px top highlight, optional outline."""
    m = _rr_mask(w, h, box, r)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    if halo:  # coloured ambient light around a focused surface
        col, blur, a = halo
        out = _over(out, _layer(m.filter(ImageFilter.GaussianBlur(blur)), col, a / 255))
    if shadow:
        dy, blur, a = shadow
        sh = _shift(m, 0, dy).filter(ImageFilter.GaussianBlur(blur))
        out = _over(out, _layer(sh, (0, 0, 0), a / 255))
    if lift:
        body = _vgrad(w, h, _tone(fill, 1 + lift), _tone(fill, 1 - lift * 0.6))
        body.putalpha(m)
        out = _over(out, body)
    else:
        out = _over(out, _layer(m, fill))
    if outline:
        out = _over(out, _layer(_ring_mask(w, h, box, r, ow), outline))
    if rim:
        out = _over(out, _layer(_top_rim(w, h, box, r, ow if outline else 0), WHITE, rim / 255))
    return out


def silhouette(alpha, thresh=80):
    """Everything inside a line drawing's outer outlines (L 0/255): the drawing's alpha [alpha]
    flooded from outside; what the flood cannot reach is the inside."""
    w, h = alpha.size
    pad = Image.new("L", (w + 2, h + 2), 0)
    pad.paste(alpha.point(lambda v: 255 if v >= thresh else 0), (1, 1))
    ImageDraw.floodfill(pad, (0, 0), 128)
    return pad.crop((1, 1, w + 1, h + 1)).point(lambda v: 0 if v == 128 else 255)


def tonal(lines, color, k):
    """A line drawing (RGBA) with its enclosed areas washed in [color] at [k] under the lines."""
    inside = silhouette(lines.getchannel("A")).filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.5))
    return Image.alpha_composite(_layer(inside, color, k), lines)


def spaced(d, xy, txt, font, fill, track, anchor_y="m"):
    """Text with extra letter spacing [track] (1x px), drawn from x (left) at y."""
    x, y = xy
    for ch in txt:
        d.text((S(x), S(y)), ch, font=font, fill=fill, anchor="l" + anchor_y)
        x += d.textlength(ch, font=font) / SS + track


def spaced_width(font, txt, track):
    d = ImageDraw.Draw(Image.new("L", (1, 1)))
    return sum(d.textlength(ch, font=font) / SS for ch in txt) + track * (len(txt) - 1)


# ---- menu glyphs: filled tonal (fill [f] under rounded lines [c]) ------------------------------

def _star_pts(cx, cy, s, inner=0.5):
    pts = []
    for i in range(10):
        r = s if i % 2 == 0 else s * inner
        a = math.radians(-90 + i * 36)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def g_recent(d, cx, cy, s, c, f, w):
    r = s * 0.92
    circle(d, cx, cy, r, fill=f)
    arc(d, cx, cy, r, 212, 512, c, w)
    a = math.radians(212)
    tx, ty = cx + r * math.cos(a), cy + r * math.sin(a)
    # arrow head pointing back (counter-clockwise) at the open end
    dx, dy = math.sin(a), -math.cos(a)
    nx, ny = math.cos(a), math.sin(a)
    k = s * 0.3
    poly(d, [(tx + dx * k * 0.9, ty + dy * k * 0.9), (tx - nx * k * 0.8, ty - ny * k * 0.8),
             (tx + nx * k * 0.8, ty + ny * k * 0.8)], c, w * 0.5, fill=c)
    line(d, [(cx, cy - s * 0.5), (cx, cy), (cx + s * 0.36, cy + s * 0.24)], c, w)


def g_favorite(d, cx, cy, s, c, f, w):
    pts = _star_pts(cx, cy + s * 0.06, s * 1.02, 0.5)
    poly(d, pts, fill=f)
    poly(d, pts, c, w)


def _pad_pts(cx, cy, s):
    return [(cx - s, cy - s * 0.25), (cx - s * 0.75, cy - s * 0.55), (cx + s * 0.75, cy - s * 0.55),
            (cx + s, cy - s * 0.25), (cx + s * 1.05, cy + s * 0.45), (cx + s * 0.75, cy + s * 0.62),
            (cx + s * 0.45, cy + s * 0.3), (cx - s * 0.45, cy + s * 0.3), (cx - s * 0.75, cy + s * 0.62),
            (cx - s * 1.05, cy + s * 0.45)]


def g_game(d, cx, cy, s, c, f, w):
    pts = _pad_pts(cx, cy, s)
    poly(d, pts, fill=f)
    poly(d, pts, c, w)
    x, y, k = cx - s * 0.55, cy - s * 0.06, s * 0.19
    line(d, [(x - k, y), (x + k, y)], c, w * 0.85)
    line(d, [(x, y - k), (x, y + k)], c, w * 0.85)
    circle(d, cx + s * 0.68, cy - s * 0.17, s * 0.11, fill=c)
    circle(d, cx + s * 0.43, cy + s * 0.05, s * 0.11, fill=c)


def g_emulators(d, cx, cy, s, c, f, w):
    rrect(d, (cx - s * 0.62, cy - s, cx + s * 0.62, cy + s), s * 0.2, fill=f)
    rrect(d, (cx - s * 0.62, cy - s, cx + s * 0.62, cy + s), s * 0.2, c, w)
    rrect(d, (cx - s * 0.38, cy - s * 0.72, cx + s * 0.38, cy - s * 0.12), s * 0.08, fill=f)
    rrect(d, (cx - s * 0.38, cy - s * 0.72, cx + s * 0.38, cy - s * 0.12), s * 0.08, c, w * 0.75)
    x, y, k = cx - s * 0.25, cy + s * 0.42, s * 0.15
    line(d, [(x - k, y), (x + k, y)], c, w * 0.8)
    line(d, [(x, y - k), (x, y + k)], c, w * 0.8)
    circle(d, cx + s * 0.34, cy + s * 0.32, s * 0.09, fill=c)
    circle(d, cx + s * 0.2, cy + s * 0.52, s * 0.09, fill=c)


INVADER = ["..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.", "XXXXXXXXXXX",
           "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX..."]


def g_expert(d, cx, cy, s, c, f, w):
    """The pixel invader: edge pixels in line colour, inner ones tonal."""
    px = s * 2.1 / 11
    x0, y0 = cx - px * 5.5, cy - px * 4

    def on(i, j):
        return 0 <= j < len(INVADER) and 0 <= i < 11 and INVADER[j][i] == "X"
    for j, row in enumerate(INVADER):
        for i, ch in enumerate(row):
            if ch != "X":
                continue
            inner = on(i - 1, j) and on(i + 1, j) and on(i, j - 1) and on(i, j + 1)
            g = px * 0.09
            rrect(d, (x0 + i * px + g, y0 + j * px + g, x0 + (i + 1) * px - g, y0 + (j + 1) * px - g), px * 0.22,
                  fill=rgba(f, min(255, f[3] * 2)) if inner else c)


def g_app(d, cx, cy, s, c, f, w):
    h = s * 0.4
    for dx in (-1, 1):
        for dy in (-1, 1):
            x, y = cx + dx * s * 0.54, cy + dy * s * 0.54
            if dx == 1 and dy == -1:
                circle(d, x, y, h + w * 0.5, fill=c)
            else:
                rrect(d, (x - h, y - h, x + h, y + h), h * 0.4, fill=f)
                rrect(d, (x - h, y - h, x + h, y + h), h * 0.4, c, w)


def _gear_pts(cx, cy, s, teeth=8):
    pts = []
    for i in range(teeth * 4):
        a = math.radians(i * 360 / (teeth * 4) - 90 + 360 / teeth / 8)
        r = s if (i % 4) in (1, 2) else s * 0.76
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def g_setting(d, cx, cy, s, c, f, w):
    pts = _gear_pts(cx, cy, s)
    poly(d, pts, fill=f)
    circle(d, cx, cy, s * 0.3, fill=(0, 0, 0, 0))  # (ImageDraw replaces pixels: a real hole)
    poly(d, pts, c, w)
    circle(d, cx, cy, s * 0.3, c, w)


MENU = {"recent": g_recent, "favorite": g_favorite, "game": g_game, "emulators": g_emulators,
        "retroarch": g_expert, "app": g_app, "setting": g_setting}
MENU_SCALE = {"emulators": 1.12, "retroarch": 1.08}  # (optical size: narrow / dotted glyphs look smaller)


class _Pen(Pen):
    """Pen with every stroke (explicit widths too) a little finer, never hairline."""

    def _w(self, w):
        w = self.w if w is None else w
        return max(1, int(round(max(1.4, w * 0.9) * self.ss)))


def _android(p):
    """A friendly robot head and body, drawn tonal (the shared glyph is solid)."""
    p.d.pieslice([*p.p(-32, -28), *p.p(32, 36)], 180, 360, fill=p.soft_c)
    p.arc(0, 4, 32, 180, 360)
    p.ln((-32, 4), (32, 4))
    p.dots([(-12, -8), (12, -8)], 3.6)
    p.ln((-18, -24), (-27, -38))
    p.ln((18, -24), (27, -38))
    p.rr(-32, 12, 32, 40, 7, soft=True)


class Theme(Style):
    name = "Slate"
    author = "MainUI"
    description = "Modern Material-style dark: slate tonal surfaces, indigo pill selection and filled-tonal lavender icons."

    bg_top, bg_bottom = BG_A, BG_B
    accent = INDIGO
    accent2 = SEL
    highlight = INDIGO_HI
    text = ON
    muted = ON_VAR
    glyph_n = LAV_DIM
    glyph_f = WHITE
    panel = (52, 56, 70)       # toasts; 0.8 x this is the pop-up title band (= SHEET)
    panel_alpha = 250
    bar = BAR

    font_file = "Poppins-SemiBold.ttf"
    list_font = "Poppins-Regular.ttf"  # MainUI bolds list text itself: Regular comes out about SemiBold
    font_license = "OFL-Poppins.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 23, 19, 22, 19, 14, 16

    icon_quant = 64
    sel_icons = True

    # ---- big pictures ---------------------------------------------------------------------
    def background(self, w=640, h=480):
        bayer = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        im = Image.new("RGB", (w, h))
        px = im.load()
        for y in range(h):
            for x in range(w):
                t = x / w * 0.55 + y / h * 0.45
                dith = (bayer[(y & 3) * 4 + (x & 3)] + 0.5) / 16
                px[x, y] = tuple(int(BG_A[i] + (BG_B[i] - BG_A[i]) * t + dith) for i in range(3))
        return im.convert("RGBA")

    def header_bar(self):
        # The background's own top rows: invisible over it, but a bar over game screenshots.
        im = self.background().crop((0, 0, 640, 60))
        im.putalpha(240)
        return im

    def footer_bar(self):
        im = Image.new("RGBA", (640, 60), rgba(BAR, 250))
        d = ImageDraw.Draw(im)
        d.line((0, 0, 639, 0), fill=(255, 255, 255, 22))
        return im

    def logo(self):
        w, h = 150, 42
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        rrect(d, (4, 8, 30, 34), 9, fill=rgba(INDIGO))
        for i, (x, y) in enumerate(((10.5, 14.5), (18.5, 14.5), (10.5, 22.5), (18.5, 22.5))):
            rrect(d, (x, y, x + 6.5, y + 6.5), 2, fill=rgba(WHITE, 255 if i == 1 else 150))
        f = self.font(21)
        d.text((S(39), S(22.5)), "Main", font=f, fill=rgba(ON), anchor="lm")
        tw = d.textlength("Main", font=f) / SS
        d.text((S(39 + tw), S(22.5)), "UI", font=f, fill=rgba(INDIGO_HI), anchor="lm")
        return finish(im)

    # ---- lists --------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        box = (6, 6, w - 6, h - 6)
        r = (h - 12) / 2 if h < 70 else R_CARD
        m = _rr_mask(w, h, box, r)
        return _over(_layer(m, INDIGO, 0.2), _layer(_ring_mask(w, h, box, r, 1.5), INDIGO, 0.85))

    def divider(self):
        return Image.new("RGBA", (640, 4), (0, 0, 0, 0))

    # ---- tiles --------------------------------------------------------------------------
    def card(self, w, h, focused, inset=6, r=R_CARD):
        box = (inset, inset, w - inset, h - inset)
        if focused:
            return surface(w, h, box, r, SEL, rim=0, shadow=(3, 6, 110), outline=rgba(INDIGO), ow=2,
                           halo=(INDIGO, 7, 60), lift=0.07)
        return surface(w, h, box, r, SURFACE, rim=22, shadow=(3, 6, 110), lift=0.08)

    def tile_base(self, focused):
        return self.card(156, 278, focused, inset=7, r=26)

    def tile_glyph(self, key, focused):
        w, h = 156, 278
        cy = 114
        pill = canvas(w, h)
        if focused:
            rrect(ImageDraw.Draw(pill), (78 - 52, cy - 34, 78 + 52, cy + 34), 34, fill=rgba(INDIGO))
            c, f = rgba(WHITE), rgba(WHITE, 72)
        else:
            c, f = rgba(LAV_DIM), rgba(LAV_DIM, 56)
        # (the glyph on its own layer: ImageDraw replaces pixels, so a tonal fill drawn straight
        # onto the pill would punch a hole in it)
        g = canvas(w, h)
        MENU[key](ImageDraw.Draw(g), 78, cy, 27 * MENU_SCALE.get(key, 1.0), c, f, 3.5)
        return finish(Image.alpha_composite(pill, g))

    def home_tile(self, key, focused):
        base = self.tile_base(focused)
        return Image.alpha_composite(base, self.tile_glyph(key, focused)) if key else base

    # ---- buttons and small parts ----------------------------------------------------------
    def button(self, label, primary):
        # A / B are drawn at their own size and X / Y scaled to 44 high: the same 30 px disc in both.
        w, h = 36, (36 if label in "AB" else 44)
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        cx, cy, r = w / 2, h / 2, 15
        if primary:
            circle(d, cx, cy, r, fill=rgba(INDIGO_DEEP))
            ink = rgba(WHITE)
        else:
            circle(d, cx, cy, r, fill=rgba(LAV, 40))
            ink = rgba(LAV)
        d.text((S(cx), S(cy + 0.6)), label, font=self.font(15), fill=ink, anchor="mm")
        return finish(im)

    def pill(self, label, w, h=44):
        f = self.font(11.5)
        tw = spaced_width(f, label, 0.9)
        w = int(math.ceil(tw + 26))
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        rrect(d, (1, h / 2 - 13, w - 1, h / 2 + 13), 13, fill=rgba(LAV, 40))
        spaced(d, ((w - tw) / 2, h / 2 + 0.8), label, f, rgba(LAV), 0.9)
        return finish(im)

    def dot(self, active):
        im = canvas(22, 12)
        d = ImageDraw.Draw(im)
        if active:
            rrect(d, (1, 3, 21, 9), 3, fill=rgba(INDIGO_HI))
        else:
            circle(d, 11, 6, 3, fill=rgba(LAV, 70))
        return finish(im)

    def toggle(self, on):
        w, h = 92, 44
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        x0, x1, y0, y1 = 32, 84, 6, 38
        r = (y1 - y0) / 2
        if on:
            rrect(d, (x0, y0, x1, y1), r, fill=rgba(INDIGO))
            cx = x1 - r
            circle(d, cx, h / 2, 12, fill=rgba(ON_SEL))
            line(d, [(cx - 5, h / 2 + 0.5), (cx - 1.5, h / 2 + 4), (cx + 5, h / 2 - 3.5)], rgba(INDIGO), 2.4)
        else:
            rrect(d, (x0, y0, x1, y1), r, fill=rgba(SURFACE_HI))
            rrect(d, (x0, y0, x1, y1), r, rgba(OUTLINE), 2)
            circle(d, x0 + r, h / 2, 8, fill=rgba(OUTLINE))
        return finish(im)

    def arrow(self, left, size=24):
        im = canvas(size, size)
        d = ImageDraw.Draw(im)
        c = size / 2
        if size > 30:
            circle(d, c, c, c - 2, fill=rgba(LAV, 36))
        k = size * (0.16 if size > 30 else 0.2)
        sx = -1 if left else 1
        line(d, [(c - sx * k * 0.5, c - k), (c + sx * k * 0.5, c), (c - sx * k * 0.5, c + k)], rgba(LAV), 2.6)
        return finish(im)

    def panel_image(self, w, h):
        if w == 640:  # dialog: a centred sheet with every corner rounded
            box = (40, 8, 600, 292)
            return surface(w, h, box, 28, SHEET, rim=24, shadow=(4, 8, 150))
        # Pop-up menus hang under MainUI's square title band (0.8 x mainui panel = SHEET):
        # square top corners, rounded bottom, a hairline between title and items.
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        m = _mask(w, h, lambda d: (d.rounded_rectangle((0, 0, w * SS, h * SS), radius=24 * SS, fill=255),
                                   d.rectangle((0, 0, w * SS, 30 * SS), fill=255)))
        im = _over(im, _layer(m, SHEET))
        d = ImageDraw.Draw(im)
        d.line((16, 0, w - 17, 0), fill=(255, 255, 255, 26))
        return im

    def folder_icon(self):
        im = canvas(60, 60)
        d = ImageDraw.Draw(im)
        pts = [(9, 46), (9, 15), (23, 15), (28, 20), (51, 20), (51, 46)]
        poly(d, pts, fill=rgba(LAV, 60))
        line(d, pts + [pts[0]], rgba(LAV), 3)
        line(d, [(9, 27), (51, 27)], rgba(LAV), 2.2)
        return finish(im)

    def favorite_mark(self):
        im = canvas(34, 39)
        d = ImageDraw.Draw(im)
        pts = _star_pts(17, 20.5, 11.5, 0.5)
        poly(d, pts, rgba(AMBER), 2.4, fill=rgba(AMBER))
        return finish(im)

    def battery(self, level, charging=False):
        im = canvas(48, 48)
        d = ImageDraw.Draw(im)
        body = (7, 16, 38, 32)
        rrect(d, body, 5, fill=rgba(ON, 34))
        rrect(d, body, 5, rgba(ON, 225), 1.8)
        rrect(d, (39.5, 21, 42.5, 27), 1.3, fill=rgba(ON, 225))
        if level > 0:
            col = rgba(RED) if level <= 0.2 else rgba(LAV)
            rrect(d, (10, 19, 10 + 25 * level, 29), 2.4, fill=col)
        if charging:
            bolt = [(25.5, 10), (16.5, 25), (22.5, 25), (20.5, 38), (30, 22), (24, 22)]
            poly(d, bolt, rgba(BG), 3.2, fill=rgba(BG))
            poly(d, bolt, fill=rgba(AMBER))
        return finish(im)

    def preview_frame(self):
        return surface(250, 360, (0, 0, 250, 360), 14, SURFACE, rim=24, shadow=None)

    def key(self, focused):
        w, h = 52, 38
        if focused:
            return surface(w, h, (1, 1, w - 1, h - 1), 10, INDIGO_DEEP, rim=40, shadow=None)
        return surface(w, h, (1, 1, w - 1, h - 1), 10, SURFACE_HI, rim=20, shadow=None)

    def input_box(self):
        w, h = 560, 60
        m = _mask(w, h, lambda d: (d.rounded_rectangle((0, 0, w * SS, h * SS), radius=14 * SS, fill=255),
                                   d.rectangle((0, 30 * SS, w * SS, h * SS), fill=255)))
        im = _layer(m, SURFACE_HI)
        ImageDraw.Draw(im).rectangle((0, h - 3, w - 1, h - 1), fill=rgba(INDIGO))
        return _over(im, _layer(_top_rim(w, h, (0, 0, w, h), 14), WHITE, 0.08))

    # ---- system and app icons ---------------------------------------------------------------
    def icon_picture(self, sid, selected, cy):
        im = canvas(120, 130)
        pen = _Pen(ImageDraw.Draw(im), 60, cy, rgba(ON_SEL if selected else LAV), SS)
        if sid == "android":
            _android(pen)
        else:
            (GLYPHS.get(sid) or GLYPHS["fallback"])(pen)
        return tonal(finish(im), INDIGO, 0.5) if selected else tonal(finish(im), LAV, 0.24)

    def badge(self, label, selected):
        im = canvas(120, 130)
        d = ImageDraw.Draw(im)
        f = self.font(11.5)
        tw = spaced_width(f, label, 0.6)
        bw = min(116, tw + 16)
        x0 = (120 - bw) / 2
        rrect(d, (x0, 106, x0 + bw, 126), 10, fill=rgba(INDIGO_DEEP) if selected else rgba(LAV, 38))
        spaced(d, (60 - tw / 2, 116.6), label, f, rgba(WHITE if selected else LAV), 0.6)
        return finish(im)

    def system_icon(self, sid, selected):
        label = BADGES.get(sid, sid[:7].upper())
        out = self.icon_picture(sid, selected, 52 if label else 62)
        if label:
            out = Image.alpha_composite(out, self.badge(label, selected))
        return out

    def app_icon(self, key):
        n = 96
        base = surface(n, n, (6, 6, n - 6, n - 6), 26, (52, 58, 99), rim=30, shadow=None)
        g = canvas(n, n)
        d = ImageDraw.Draw(g)
        if key == "retroarch":
            g_expert(d, n / 2, n / 2, n * 0.26, rgba(LAV), rgba(LAV, 60), 3)
        else:
            APPS[key](d, n / 2, n / 2, n * 0.25, rgba(LAV), 3.4)
        return Image.alpha_composite(base, tonal(finish(g), LAV, 0.22))

    # ---- sound ----------------------------------------------------------------------------
    def sound(self, path):
        self.change_sound(path, freq=1250, decay=210, length=0.03, volume=7000)

    # ---- config ---------------------------------------------------------------------------
    def config(self):
        cfg = super().config()
        cfg["list"]["font"] = self.list_font
        cfg["grid"]["selectedcolor"] = hexc(ON_SEL)
        cfg["currentpage"]["color"] = hexc(INDIGO_HI)
        cfg["total"]["color"] = hexc(ON_VAR)
        cfg["mainui"] = {"accent": hexc(INDIGO), "panel": hexa(self.panel, self.panel_alpha), "background": hexc(BG)}
        return cfg
