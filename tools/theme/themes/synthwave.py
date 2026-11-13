"""Synthwave: an 80s sunset over a neon grid, glass panels with glowing tube edges."""
import math
import random
import struct
import wave

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from theme_kit import APPS, GLYPHS, SS, TILES, Pen, S, Style, canvas, circle, finish, g_folder, g_star, line, poly, rgba, rrect

NIGHT = (8, 2, 24)
INK = (20, 5, 42)            # panel glass
CYAN = (40, 232, 255)
ICE = (215, 252, 255)        # cyan tube core
MAGENTA = (255, 46, 170)
PINK = (255, 92, 196)
VIOLET = (160, 96, 255)
GOLD = (255, 196, 64)
TEXT = (255, 242, 252)
MUTED = (238, 182, 228)
# The sun: a thin gold rim, then deep enough for white list text wherever 'cover' scaling puts a row
SUN = [(0.0, (250, 190, 80)), (0.07, (236, 116, 46)), (0.2, (206, 52, 70)), (0.5, (204, 34, 112)), (1.0, (160, 22, 146))]
LOGO_SUN = [(0.0, (255, 214, 92)), (0.32, (255, 128, 56)), (0.68, (240, 50, 120)), (1.0, (196, 30, 160))]
HORIZON = 300
GRID_K = 0.6                 # floor line strength, kept low for list text over it
# Glass alpha down a tile / card: the sun shows through the top, the label band stays dark
TILE_GLASS = ((0, 128), (0.62, 215), (1, 244))
CARD_GLASS = ((0, 150), (0.6, 215), (1, 240))


# ---- small image helpers (PIL only) --------------------------------------------------------

def _paint(mask, c, k=1.0):
    """Colour [c] shaped by [mask] (L), its alpha scaled by [k]."""
    if k != 1.0:
        mask = mask.point(lambda v: min(255, int(v * k)))
    out = Image.new("RGBA", mask.size, rgba(c))
    out.putalpha(mask)
    return out


def _over(*layers):
    out = layers[0]
    for lay in layers[1:]:
        out = Image.alpha_composite(out, lay)
    return out


def _clear(w, h):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def _stop(stops, t):
    t = min(1.0, max(0.0, t))
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            k = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(int(round(c0[i] + (c1[i] - c0[i]) * k)) for i in range(len(c0)))
    return tuple(stops[-1][1])


def _vgrad(w, h, stops, y0=0, y1=None):
    """RGBA vertical gradient through colour [stops] (RGB or RGBA) between rows y0 and y1."""
    y1 = h - 1 if y1 is None else y1
    col = Image.new("RGBA", (1, h))
    for y in range(h):
        c = _stop(stops, (y - y0) / max(1, y1 - y0))
        col.putpixel((0, y), c if len(c) == 4 else c + (255,))
    return col.resize((w, h), Image.NEAREST)


def _hgrad(w, h, stops):
    row = Image.new("RGBA", (w, 1))
    for x in range(w):
        c = _stop(stops, x / max(1, w - 1))
        row.putpixel((x, 0), c if len(c) == 4 else c + (255,))
    return row.resize((w, h), Image.NEAREST)


def _masked(im, mask):
    out = im.copy()
    out.putalpha(ImageChops.multiply(im.split()[3], mask))
    return out


def _rr_mask(w, h, box, r, width=0):
    """Anti-aliased rounded rect (L, 1x): filled, or an outline [width] wide centred on the box."""
    m = Image.new("L", (w * SS, h * SS), 0)
    d = ImageDraw.Draw(m)
    if width:
        e = width / 2
        d.rounded_rectangle([S(box[0] - e), S(box[1] - e), S(box[2] + e), S(box[3] + e)], radius=S(r + e),
                            outline=255, width=max(1, int(S(width))))
    else:
        d.rounded_rectangle([S(v) for v in box], radius=S(r), fill=255)
    return m.resize((w, h), Image.LANCZOS)


def _blur(m, r):
    return m.filter(ImageFilter.GaussianBlur(r))


def _lighter(c, k):
    return tuple(int(c[i] + (255 - c[i]) * k) for i in range(3))


def _mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def _edge_fade(w, h, n=8, sides="lrtb"):
    """L mask: 0 on the canvas border, 255 from [n] px inside, so a glow fades out instead of being cut."""
    def ramp(m, lo, hi):
        out = []
        for i in range(m):
            d = min(i if lo else n, m - 1 - i if hi else n)
            t = min(1.0, d / n)
            out.append(int(round(255 * t * t * (3 - 2 * t))))
        return out
    row = Image.new("L", (w, 1))
    row.putdata(ramp(w, "l" in sides, "r" in sides))
    col = Image.new("L", (1, h))
    col.putdata(ramp(h, "t" in sides, "b" in sides))
    return ImageChops.multiply(row.resize((w, h), Image.NEAREST), col.resize((w, h), Image.NEAREST))


def _halo(m, layers, fade=None, cut=None):
    """Blurred copies of mask [m] added up ((radius, gain) each), faded at the canvas edge, minus [cut]."""
    out = Image.new("L", m.size, 0)
    for r, k in layers:
        out = ImageChops.add(out, _blur(m, r).point(lambda v: min(255, int(v * k))))
    if fade is not None:
        out = ImageChops.multiply(out, fade)
    if cut is not None:
        out = ImageChops.subtract(out, cut)
    return out


def _faded(im, n=4):
    """[im] with its alpha faded out towards the canvas border."""
    out = im.copy()
    out.putalpha(ImageChops.multiply(im.split()[3], _edge_fade(im.width, im.height, n)))
    return out


def _clean(im, cut=16):
    """Faint pixels cleared to (0, 0, 0, 0): a palette PNG would average them into the transparent
    pixels of the same colour and leave a faint box around the picture."""
    out = _clear(im.width, im.height)
    out.paste(im, (0, 0), im.split()[3].point(lambda v: 255 if v >= cut else 0))
    return out


def _erode(m, n):
    """[n] passes of a 3x3 minimum: removes n px from every edge."""
    for _ in range(n):
        m = m.filter(ImageFilter.MinFilter(3))
    return m


def _snap(m, steps):
    """Alpha [m] snapped to fixed levels ((threshold, level), high to low): the icon palette keeps them as clean rings."""
    lut = [next((lv for t, lv in steps if v >= t), 0) for v in range(256)]
    return m.point(lut)


def _neon(a_ss, color, core=0.75, halo=((2.2, 1.1), (7, 0.9)), body=1.0):
    """A glowing tube from a supersampled shape mask (L): halo, coloured body, a light core inside."""
    w, h = a_ss.width // SS, a_ss.height // SS
    a = a_ss.resize((w, h), Image.LANCZOS)
    out = _clear(w, h)
    for r, k in halo:
        out = Image.alpha_composite(out, _paint(_blur(a, r), color, k))
    out = Image.alpha_composite(out, _paint(a, color, body))
    if core:
        solid = a_ss.point(lambda v: 255 if v > 200 else 0)
        c = _blur(solid.filter(ImageFilter.MinFilter(SS + 1)).resize((w, h), Image.LANCZOS), 0.35)
        out = Image.alpha_composite(out, _paint(c, _lighter(color, core)))
    return out


def _lean(im):
    """Channels rounded down to multiples of 3: invisible, and the PNG shrinks by a third."""
    return Image.merge("RGBA", [c.point(lambda v: v - v % 3) for c in im.split()])


def _tube(w, h, draw, color, **kw):
    """Draw with draw(ImageDraw, colour) on a supersampled canvas and light it as neon."""
    im = canvas(w, h)
    draw(ImageDraw.Draw(im), (255, 255, 255, 255))
    return _neon(im.split()[3], color, **kw)


def _text_mask(w, h, s, font, x, cy, anchor="lm", spacing=0.0, slant=0.0):
    """Text as an L mask at 1x (drawn supersampled), letter-spaced and slanted on request."""
    m = Image.new("L", (w * SS, h * SS), 0)
    d = ImageDraw.Draw(m)
    widths = [d.textlength(ch, font=font) for ch in s]
    total = sum(widths) + S(spacing) * (len(s) - 1)
    xx = S(x) - (total / 2 if anchor[0] == "m" else 0)
    if not spacing:
        d.text((xx, S(cy)), s, font=font, fill=255, anchor="l" + anchor[1])
    else:
        for ch, cw in zip(s, widths):
            d.text((xx, S(cy)), ch, font=font, fill=255, anchor="l" + anchor[1])
            xx += cw + S(spacing)
    if slant:  # rows above cy move right
        m = m.transform(m.size, Image.AFFINE, (1, slant, -slant * S(cy), 0, 1, 0), resample=Image.BICUBIC)
    return m.resize((w, h), Image.LANCZOS)


def _sun_disc(d, cx, cy, r, gaps):
    """The striped sun on a supersampled L mask: a disc cut by horizontal gaps (y, thickness)."""
    d.ellipse([S(cx - r), S(cy - r), S(cx + r), S(cy + r)], fill=255)
    for y, t in gaps:
        d.rectangle([S(cx - r - 2), S(y), S(cx + r + 2), S(y + t)], fill=0)


def _sun_gaps(top, bottom, n=5, zone=0.5):
    """Gaps cutting the lower [zone] of a sun seen from [top] to [bottom]: thicker and closer lower down."""
    y = bottom - (bottom - top) * zone
    bands = [1.0 - 0.72 * i / n for i in range(n + 1)]
    gaps = [0.22 + 0.7 * i / (n - 1) for i in range(n)]
    k = (bottom - y) / (sum(bands) + sum(gaps))
    out = []
    for b, g in zip(bands, gaps):
        y += b * k
        out.append((y, g * k))
        y += g * k
    return out


def _sun(w, h, cx, cy, r, bottom, n=5, gaps=None):
    """The sun's mask (L, 1x), cut off at [bottom]; gaps=[] gives the whole disc."""
    m = Image.new("L", (w * SS, h * SS), 0)
    d = ImageDraw.Draw(m)
    _sun_disc(d, cx, cy, r, _sun_gaps(cy - r, bottom, n) if gaps is None else gaps)
    d.rectangle([0, S(bottom), S(w), S(h)], fill=0)
    return m.resize((w, h), Image.LANCZOS)


class Theme(Style):
    name = "Synthwave"
    author = "MainUI"
    description = "80s retro-futurism: a striped sunset sun over a neon grid, glass panels lit by glowing tubes."

    bg_top = NIGHT
    bg_bottom = (16, 3, 34)
    accent = (222, 28, 138)      # MainUI writes white on it (fallback tiles, keys): kept deep
    accent2 = VIOLET
    highlight = CYAN
    text = TEXT
    muted = MUTED
    glyph_n = PINK
    glyph_f = CYAN
    panel = INK
    panel_alpha = 242
    bar = NIGHT
    bar_alpha = 225

    font_file = "Audiowide-Regular.ttf"
    font_license = "OFL-Audiowide.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 26, 24, 22, 19, 14, 16
    icon_quant = 255
    sel_icons = True

    # ---- background ----------------------------------------------------------------------
    def background(self, w=640, h=480):
        hz = HORIZON
        cx = w / 2
        sky = _vgrad(w, h, [(0.0, NIGHT), (0.3, (22, 5, 52)), (0.52, (52, 9, 86)),
                            (hz / h, (112, 20, 112)), (hz / h + 0.001, (18, 3, 38)), (1.0, (30, 5, 58))])
        haze = Image.new("L", (w, h), 0)
        ImageDraw.Draw(haze).ellipse((-120, hz - 70, w + 120, hz + 40), fill=150)
        sky = _over(sky, _paint(_blur(haze, 34), (190, 30, 140), 0.55))

        rnd = random.Random(80)
        st = canvas(w, h)
        sd = ImageDraw.Draw(st)
        for _ in range(95):
            x = rnd.uniform(4, w - 4)
            y = 4 + (hz - 60) * (rnd.random() ** 1.5)
            r = rnd.choice((0.55, 0.65, 0.8, 0.8, 1.0, 1.25))
            circle(sd, x, y, r, fill=(255, 236, 255, int(rnd.uniform(90, 230) * (1 - y / hz * 0.6))))
        for x, y, s in ((92, 48, 5), (548, 92, 6), (210, 120, 4), (452, 30, 4.5), (36, 170, 4), (606, 196, 3.5)):
            line(sd, [(x - s, y), (x + s, y)], (255, 240, 255, 200), 0.9)
            line(sd, [(x, y - s), (x, y + s)], (255, 240, 255, 200), 0.9)
            circle(sd, x, y, 1.2, fill=(255, 255, 255, 255))
        stars = finish(st)
        sky = _over(sky, _paint(_blur(stars.split()[3], 1.5), (255, 150, 240), 0.7), stars)

        cy, r = hz - 12, 122
        disc = _sun(w, h, cx, cy, r, hz, gaps=[])
        sun = _sun(w, h, cx, cy, r, hz)
        # the gaps are cut back to the night sky, or the disc's own glow fills them in
        sky = _over(sky, _paint(_blur(disc, 26), (255, 40, 150), 0.65),
                    _paint(ImageChops.subtract(disc, sun), (60, 10, 92), 0.85))
        sky = _over(sky, _masked(_vgrad(w, h, SUN, int(cy - r), hz), sun.point(lambda v: v * 238 // 255)))

        mnt = canvas(w, h)
        md = ImageDraw.Draw(mnt)
        ridge = canvas(w, h)
        rd = ImageDraw.Draw(ridge)
        for pts in ([(-10, hz), (30, hz - 26), (62, hz - 14), (104, hz - 46), (150, hz - 20), (176, hz - 30), (214, hz)],
                    [(w - 214, hz), (w - 178, hz - 22), (w - 146, hz - 38), (w - 112, hz - 16), (w - 70, hz - 52),
                     (w - 30, hz - 24), (w + 10, hz)]):
            poly(md, pts, fill=(26, 5, 52, 255))
            line(rd, pts, (255, 255, 255, 255), 1.3)
        ridge = finish(ridge).split()[3]
        sky = _over(sky, finish(mnt), _paint(_blur(ridge, 2.5), MAGENTA, 0.8), _paint(ridge, (215, 60, 165), 0.75))

        # the sun's reflection on the floor
        refl = Image.new("L", (w, h), 0)
        ImageDraw.Draw(refl).ellipse((cx - 90, hz - 6, cx + 90, hz + 70), fill=255)
        sky = _over(sky, _paint(_blur(refl, 22), (255, 60, 150), 0.064))

        gl = canvas(w, h)
        gd = ImageDraw.Draw(gl)
        for k in range(40):
            y = hz + 200 / (k * 0.62 + 1)
            if y < hz + 2.5:
                break
            line(gd, [(-10, y), (w + 10, y)], (255, 255, 255, 255), 1.5)
        hl = finish(gl).split()[3]
        vl = canvas(w, h)
        vd = ImageDraw.Draw(vl)
        for i in range(-14, 15):
            line(vd, [(cx + i * 3.0, hz + 1), (cx + i * 74 * 1.6, hz + 180 * 1.6)], (255, 255, 255, 255), 1.4)
        vl = finish(vl).split()[3]
        # dim near the horizon, where the lines bunch up into a bright band behind list text
        fade = _vgrad(w, h, [(0, (0, 0, 0, 0)), (0.12, (0, 0, 0, 80)), (30 / (h - hz), (0, 0, 0, 128)),
                             (0.3, (0, 0, 0, 230)), (0.5, (0, 0, 0, 255)), (1, (0, 0, 0, 255))], hz, h).split()[3]
        hl, vl = ImageChops.multiply(hl, fade), ImageChops.multiply(vl, fade)
        k = GRID_K
        sky = _over(sky, _paint(_blur(hl, 3), MAGENTA, 0.9 * k), _paint(_blur(vl, 3), CYAN, 0.7 * k),
                    _paint(hl, (255, 90, 200), 0.85 * k), _paint(vl, (90, 220, 255), 0.7 * k))

        hzl = canvas(w, h)
        line(ImageDraw.Draw(hzl), [(-5, hz), (w + 5, hz)], (255, 255, 255, 255), 1.2)
        hzl = finish(hzl).split()[3]
        sky = _over(sky, _paint(_blur(hzl, 4), MAGENTA, 1.4), _paint(hzl, (228, 76, 186)))
        return sky.convert("RGB").convert("RGBA")

    # ---- bars ----------------------------------------------------------------------------
    def _bar_line(self, w, h, y, side):
        ln = canvas(w, h)
        line(ImageDraw.Draw(ln), [(0, y), (w, y)], (255, 255, 255, 255), 1.6)
        m = finish(ln).split()[3]
        col = _hgrad(w, h, [(0, CYAN), (0.5, MAGENTA), (1, VIOLET)])
        return _over(_masked(col, _halo(m, ((2.5, 2.2),), _edge_fade(w, h, 4, side))), _masked(col, m))

    def header_bar(self):
        return _over(_vgrad(640, 60, [(0, (6, 1, 18, 236)), (1, (16, 3, 38, 214))]), self._bar_line(640, 60, 56.5, "b"))

    def footer_bar(self):
        return _over(_vgrad(640, 60, [(0, (14, 3, 34, 214)), (1, (6, 1, 18, 238))]), self._bar_line(640, 60, 3.5, "t"))

    # ---- logo ----------------------------------------------------------------------------
    def logo(self):
        w, h = 256, 42
        sm = _sun(w, h, 21, 21, 15.5, 34.5, gaps=[(24.2, 1.2), (28.6, 2.0)])
        sun = _over(_paint(_blur(sm, 3), MAGENTA, 0.9), _masked(_vgrad(w, h, LOGO_SUN, 6, 34), sm))
        tm = _text_mask(w, h, "SYNTHWAVE", self.font(27), 46, 22, spacing=0.5, slant=0.2)
        bb = tm.getbbox()
        chrome = _vgrad(w, h, [(0, (252, 253, 255)), (0.38, (150, 214, 255)), (0.5, (64, 96, 200)),
                               (0.53, (30, 12, 70)), (0.62, (160, 34, 150)), (0.84, (255, 128, 206)),
                               (1.0, (255, 232, 248))], bb[1], bb[3])
        outline = ImageChops.subtract(tm.filter(ImageFilter.MaxFilter(3)), tm)
        out = _over(_clear(w, h), sun, _paint(_blur(tm, 3.5), MAGENTA, 1.0), _paint(outline, (40, 8, 70)),
                    _masked(chrome, tm))
        gl = canvas(w, h)
        gx, gy = bb[2] - 6, bb[1] + 2
        poly(ImageDraw.Draw(gl), [(gx, gy - 6), (gx + 1, gy - 1), (gx + 6, gy), (gx + 1, gy + 1), (gx, gy + 6),
                                  (gx - 1, gy + 1), (gx - 6, gy), (gx - 1, gy - 1)], fill=(255, 255, 255, 255))
        gl = finish(gl)
        return _faded(_over(out, _paint(_blur(gl.split()[3], 1.5), (200, 240, 255), 1.2), gl))

    # ---- lists ---------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        box = (12, 7, w - 12, h - 7)   # inside MainUI's 24 px end caps, clear of a pop-up's own ring
        r = 10 if h < 70 else 14
        fill = _masked(_hgrad(w, h, [(0, (70, 14, 110, 200)), (0.5, (44, 8, 84, 170)), (1, (30, 6, 66, 150))]),
                       _rr_mask(w, h, box, r))
        ring = _rr_mask(w, h, box, r, 2.0)
        col = _hgrad(w, h, [(0, CYAN), (0.45, MAGENTA), (1, (255, 80, 190))])
        glow = _masked(col, _halo(ring, ((2.4, 2.2),), _edge_fade(w, h, 6)))
        core = _masked(_hgrad(w, h, [(0, ICE), (0.45, (255, 190, 236)), (1, (255, 200, 240))]), _rr_mask(w, h, box, r, 0.8))
        return _over(fill, glow, _masked(col, ring), core)

    def divider(self):
        im = canvas(640, 4)
        line(ImageDraw.Draw(im), [(24, 2), (616, 2)], (255, 255, 255, 255), 1)
        col = _hgrad(640, 4, [(0, MAGENTA + (0,)), (0.15, MAGENTA + (100,)), (0.85, VIOLET + (100,)), (1, VIOLET + (0,))])
        return _masked(col, finish(im).split()[3])

    # ---- tiles ---------------------------------------------------------------------------
    def _panel(self, w, h, box, r, focused, glass=CARD_GLASS, grid=False, glow=1.0, ring_w=(2.2, 1.6)):
        """Dark glass with a neon edge: dim magenta, or bright cyan with a strong glow when focused.
        The glow stays outside the glass and fades out before the canvas edge."""
        shape = _rr_mask(w, h, box, r)
        top, bottom = ((44, 14, 92), (26, 6, 60)) if focused else ((26, 7, 56), (16, 4, 38))
        lift = 8 if focused else 0
        body = _vgrad(w, h, [(t, _mix(top, bottom, t) + (min(255, a + lift),)) for t, a in glass], box[1], box[3])
        warm = _vgrad(w, h, [(0, (255, 40, 160, 0)), (0.62, (255, 40, 160, 0)), (1, (255, 40, 160, 46 if focused else 26))])
        sheen = _vgrad(w, h, [(0, (255, 255, 255, 22)), (0.18, (255, 255, 255, 0)), (1, (255, 255, 255, 0))])
        out = _over(_masked(body, shape), _masked(warm, shape), _masked(sheen, shape))
        if grid:
            out = _over(out, self._mini_grid(w, h, box, focused))
        ring = _rr_mask(w, h, box, r, ring_w[0] if focused else ring_w[1])
        fade, outside = _edge_fade(w, h), ImageChops.invert(shape)
        if focused:
            halo = ImageChops.multiply(_halo(ring, ((6 * glow, 3.4), (2.5 * glow, 2.0)), fade), outside)
            inner = ImageChops.multiply(_blur(ring, 5 * glow), shape)
            return _over(_paint(halo, CYAN), out, _paint(inner, CYAN, 0.5), _paint(ring, CYAN),
                         _paint(_rr_mask(w, h, box, r, 0.9), ICE))
        halo = ImageChops.multiply(_halo(ring, ((3 * glow, 0.8),), fade), outside)
        return _over(_paint(halo, MAGENTA), out, _paint(ring, (210, 50, 160), 0.85))

    def _mini_grid(self, w, h, box, focused):
        """A small perspective floor in the bottom of a tile, below its label."""
        x0, y0, x1, y1 = box
        top = y1 - 46
        cx = (x0 + x1) / 2
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        for k in range(7):
            y = top + 54 / (k * 0.9 + 1) - 8
            if y > top + 1:
                line(d, [(x0, y), (x1, y)], (255, 255, 255, 255), 1.0)
        for i in range(-6, 7):
            line(d, [(cx + i * 4, top), (cx + i * 30, y1 + 16)], (255, 255, 255, 255), 1.0)
        line(d, [(x0, top), (x1, top)], (255, 255, 255, 255), 1.0)
        clip = ImageChops.multiply(_rr_mask(w, h, box, 14),
                                   _vgrad(w, h, [(0, (0, 0, 0, 0)), (1, (0, 0, 0, 255))], top, y1).split()[3])
        m = ImageChops.multiply(finish(im).split()[3], clip)
        c = CYAN if focused else MAGENTA
        return _over(_paint(_blur(m, 2), c, 0.6), _paint(m, c, 0.55 if focused else 0.4))

    def card(self, w, h, focused):
        return self._panel(w, h, (10, 10, w - 10, h - 10), 12, focused)

    def tile_base(self, focused):
        return self._panel(156, 278, (13, 12, 143, 266), 16, focused, TILE_GLASS, grid=True)

    def tile_glyph(self, key, focused):
        def draw(d, c):
            TILES[key](d, 78, 112, 37, c, 5.0)
        if focused:
            return _tube(156, 278, draw, CYAN, core=0.85, halo=((2.5, 1.3), (9, 1.0)))
        return _tube(156, 278, draw, PINK, core=0.45, halo=((2.2, 0.8), (7, 0.45)), body=0.92)

    def home_tile(self, key, focused):
        base = self.tile_base(focused)
        return _lean(Image.alpha_composite(base, self.tile_glyph(key, focused)) if key else base)

    # ---- buttons and small parts -----------------------------------------------------------
    BUTTON_COLORS = {"A": CYAN, "B": MAGENTA, "X": GOLD, "Y": VIOLET}

    def button(self, label, primary):
        n = 54
        c = self.BUTTON_COLORS.get(label, CYAN)
        base = canvas(n, n)
        circle(ImageDraw.Draw(base), n / 2, n / 2, 20, fill=rgba(INK, 235))
        ring = canvas(n, n)
        circle(ImageDraw.Draw(ring), n / 2, n / 2, 20, (255, 255, 255, 255), 2.6)
        ring = _faded(_neon(ring.split()[3], c, 0.6, ((2.2, 1.2), (4, 0.6))))
        tm = _text_mask(n, n, label, self.font(24), n / 2, n / 2 + 1, anchor="mm")
        return _over(finish(base), ring, _paint(_blur(tm, 2), c, 1.0), _paint(tm, _lighter(c, 0.8)))

    def pill(self, label, w, h=48):
        box = (6, 9, w - 6, h - 9)
        r = (box[3] - box[1]) / 2
        ring = _rr_mask(w, h, box, r, 1.8)
        tm = _text_mask(w, h, label, self.font(h * 0.3), w / 2, h / 2 + 0.5, anchor="mm")
        return _over(_paint(_halo(ring, ((2.5, 1.2),), _edge_fade(w, h, 6)), VIOLET), _paint(_rr_mask(w, h, box, r), INK, 0.92),
                     _paint(ring, (186, 140, 255)), _paint(_blur(tm, 1.5), VIOLET, 0.7), _paint(tm, TEXT))

    def dot(self, active):
        im = canvas(24, 14)
        d = ImageDraw.Draw(im)
        if active:
            rrect(d, (4.5, 5, 19.5, 9), 2, fill=(255, 255, 255, 255))
            return _neon(im.split()[3], CYAN, 0.8, ((1.5, 1.5),))
        circle(d, 12, 7, 3, fill=(255, 255, 255, 255))
        return _neon(im.split()[3], MAGENTA, 0.35, ((1.6, 0.9),), body=0.85)

    def toggle(self, on):
        w, h = 92, 44
        box = (8, 10, w - 8, h - 10)
        r = (box[3] - box[1]) / 2
        c = CYAN if on else (200, 50, 160)
        body = _paint(_rr_mask(w, h, box, r), c if on else INK, 0.28 if on else 0.9)
        ring = _rr_mask(w, h, box, r, 1.8)
        kn = canvas(w, h)
        circle(ImageDraw.Draw(kn), box[2] - r if on else box[0] + r, h / 2, r - 4, fill=(255, 255, 255, 255))
        knob = _neon(kn.split()[3], c, 0.85 if on else 0.3, ((2.5, 1.3),) if on else ((1.5, 0.5),), body=1.0 if on else 0.8)
        return _over(_paint(_blur(ring, 2.5), c, 1.2 if on else 0.6), body, _paint(ring, c, 1.0 if on else 0.8), knob)

    def arrow(self, left, size=24):
        k = size / 24
        xs = (0.6, 0.36) if left else (0.4, 0.64)

        def draw(d, c):
            line(d, [(size * xs[0], size * 0.24), (size * xs[1], size * 0.5), (size * xs[0], size * 0.76)], c, 2.6 * k)
        return _tube(size, size, draw, CYAN, core=0.75, halo=((1.4 * k, 1.3),))

    def panel_image(self, w, h):
        box = (6, 6, w - 6, h - 6)
        r = 16
        shape = _rr_mask(w, h, box, r)
        body = _masked(_vgrad(w, h, [(0, (30, 8, 62, 246)), (1, (16, 4, 36, 246))]), shape)
        warm = _masked(_vgrad(w, h, [(0, (255, 40, 160, 0)), (0.7, (255, 40, 160, 0)), (1, (255, 40, 160, 30))]), shape)
        ring = _rr_mask(w, h, box, r, 2.0)
        col = _hgrad(w, h, [(0, VIOLET), (0.5, MAGENTA), (1, CYAN)])  # reversed: a selected row's ends stay distinct
        inner = ImageChops.multiply(_blur(ring, 4), shape)
        return _over(_masked(col, _halo(ring, ((2.5, 2.0),), _edge_fade(w, h, 6))), body, warm,
                     _masked(col, inner.point(lambda v: v // 2)), _masked(col, ring))

    def folder_icon(self):
        return _tube(60, 60, lambda d, c: g_folder(d, 30, 31, 19, c, 3.4), GOLD, core=0.6, halo=((2, 1.2), (4.5, 0.5)))

    def favorite_mark(self):
        return _faded(_tube(34, 39, lambda d, c: g_star(d, 17, 20, 12.5, c, 2.6), GOLD, core=0.7, halo=((1.8, 1.3), (4, 0.5))))

    def battery(self, level, charging=False):
        c = (255, 70, 110) if level <= 0.2 else CYAN

        def shell(d, col):
            rrect(d, (7, 16, 39, 32), 3.5, col, 2.2)
            rrect(d, (39.5, 21, 42.5, 27), 1, fill=col)
        im = _tube(48, 48, shell, c, core=0.6, halo=((1.6, 1.1),))
        n = int(round(level * 4))
        if n:
            seg = canvas(48, 48)
            sd = ImageDraw.Draw(seg)
            for i in range(n):
                rrect(sd, (10.5 + i * 7, 19.5, 15.5 + i * 7, 28.5), 1, fill=(255, 255, 255, 255))
            im = _over(im, _neon(seg.split()[3], c, 0.5, ((1.5, 0.9),)))
        if charging:
            b = canvas(48, 48)
            poly(ImageDraw.Draw(b), [(26, 10), (17, 26), (23, 26), (20, 38), (31, 21), (25, 21)], fill=(255, 255, 255, 255))
            bm = finish(b).split()[3]
            im = _over(im, _paint(ImageChops.subtract(bm.filter(ImageFilter.MaxFilter(5)), bm), NIGHT),
                       _paint(_blur(bm, 2), GOLD, 1.2), _paint(bm, (255, 236, 150)))
        return im

    def preview_frame(self):
        w, h = 250, 360
        im = self._panel(w, h, (8, 8, w - 8, h - 8), 14, False, glass=((0, 170), (1, 190)))
        br = canvas(w, h)
        d = ImageDraw.Draw(br)
        n = 22
        for (x, y, sx, sy) in ((12, 12, 1, 1), (w - 12, 12, -1, 1), (12, h - 12, 1, -1), (w - 12, h - 12, -1, -1)):
            line(d, [(x, y + sy * n), (x, y), (x + sx * n, y)], (255, 255, 255, 255), 2.2)
        return _over(im, _neon(br.split()[3], CYAN, 0.7, ((2, 1.2),)))

    def key(self, focused):
        # small radius and a tight halo: MainUI stretches the key up to 3x wider
        im = self._panel(64, 52, (6, 6, 58, 46), 5, focused, glass=((0, 200), (1, 236)), glow=0.28, ring_w=(1.4, 1.4))
        if focused:  # lit glass: the key under the cursor stands out among dozens
            im = _over(im, _paint(_rr_mask(64, 52, (7, 7, 57, 45), 4), CYAN, 0.22))
        return im

    def input_box(self):
        w, h = 560, 60
        box = (7, 7, w - 7, h - 7)
        ring = _rr_mask(w, h, box, 10, 2.0)
        return _over(_paint(_halo(ring, ((2.5, 1.6),), _edge_fade(w, h, 6)), CYAN),
                     _paint(_rr_mask(w, h, box, 10), (10, 3, 26), 0.94), _paint(ring, CYAN), _paint(_rr_mask(w, h, box, 10, 0.8), ICE))

    # ---- system and app icons --------------------------------------------------------------
    ICON_K = 0.92   # drawings shrunk a little so their glow stays inside the icon
    # Icon palettes keep only ~20 colours, so the glow is drawn as a few flat rings that survive that:
    # (threshold on the blurred tube, alpha, colour) from the tube outwards.
    HALO_N = ((150, 150, PINK), (80, 104, (250, 70, 186)), (36, 68, MAGENTA), (14, 40, (200, 40, 170)))
    HALO_S = ((150, 196, CYAN), (80, 144, CYAN), (36, 96, (60, 200, 255)), (14, 52, (70, 150, 255)))

    def _hollow(self, tube_ss):
        """Parts thicker than about 7 px keep a 2.5 px tube outline around a glass core."""
        small = tube_ss.resize((120, 130), Image.BOX).point(lambda v: 255 if v > 128 else 0)
        if not small.filter(ImageFilter.MinFilter(7)).getbbox():
            return tube_ss, None
        n = 7 * SS // 2
        thick = _erode(tube_ss, n).filter(ImageFilter.MaxFilter(3))
        for _ in range(n - 1):
            thick = thick.filter(ImageFilter.MaxFilter(3))
        core = _erode(thick, int(2.5 * SS))
        return ImageChops.subtract(tube_ss, core), core.resize((120, 130), Image.LANCZOS)

    def icon_picture(self, sid, selected, cy):
        """Neon tubes for the strokes, tinted glass for the drawing's soft fills."""
        k = self.ICON_K
        im = canvas(120, 130)
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        draw(Pen(ImageDraw.Draw(im), 60 / k, cy / k, (255, 255, 255, 255), SS * k, w=3.0))
        a = im.split()[3]
        soft = a.point(lambda v: 255 if 20 < v <= 160 else 0).resize((120, 130), Image.LANCZOS)
        tube_ss, glass = self._hollow(a.point(lambda v: 255 if v > 160 else 0))
        if glass is not None:
            soft = ImageChops.lighter(soft, glass)
        tube = tube_ss.resize((120, 130), Image.LANCZOS)
        # outside (reached from the corner) glows in rings; enclosed areas get one flat tint instead,
        # where overlapping glows would otherwise break into blotches
        flood = tube.point(lambda v: 255 if v > 128 else 0)
        ImageDraw.floodfill(flood, (0, 0), 128)
        outside = flood.point(lambda v: 255 if v == 128 else 0)
        inside = flood.point(lambda v: 255 if v == 0 else 0)
        c, steps = (CYAN, self.HALO_S) if selected else (PINK, self.HALO_N)
        spread = _halo(tube, ((1.6, 1.6), (4.5, 1.6)))
        out = _clear(120, 130)
        out.paste(rgba(c, 44 if selected else 30), (0, 0), inside)
        for t, lv, col in steps[::-1]:
            out.paste(rgba(col, lv), (0, 0), ImageChops.multiply(spread.point(lambda v: 255 if v >= t else 0), outside))
        near = ImageChops.multiply(spread.point(lambda v: 255 if v >= steps[0][0] else 0), inside)
        out.paste(rgba(steps[1][2], steps[1][1]), (0, 0), near)
        if selected:
            out = _over(out, _paint(soft, MAGENTA, 0.5))
        else:
            out = _over(out, _paint(soft, VIOLET, 0.55))
        return _over(out, _neon(tube_ss, c, 0.8 if selected else 0.55, ()))

    def system_icon(self, sid, selected):
        # MainUI writes each system's name next to its icon, so the picture goes without a badge
        return _clean(self.icon_picture(sid, selected, 65))

    APP_COLORS = {"themes": MAGENTA, "pacman": GOLD, "gameswitcher": CYAN, "search": CYAN, "gallery": VIOLET,
                  "activity": GOLD, "retroarch": MAGENTA, "tweaks": VIOLET, "download": CYAN, "folder": GOLD}

    def app_icon(self, key):
        n = 96
        c = self.APP_COLORS.get(key, CYAN)
        box = (8, 8, n - 8, n - 8)
        ring = _rr_mask(n, n, box, 20, 2.0)
        body = _masked(_vgrad(n, n, [(0, (36, 10, 74, 245)), (1, (16, 4, 38, 245))]), _rr_mask(n, n, box, 20))
        g = _tube(n, n, lambda d, col: APPS[key](d, n / 2, n / 2, n * 0.25, col, n * 0.05), c, core=0.7,
                  halo=((1.8, 1.2), (4, 0.6)))
        return _clean(_over(_paint(_halo(ring, ((3, 1.0),), _edge_fade(n, n, 6)), c), body, _paint(ring, c, 0.9), g))

    # ---- sound -------------------------------------------------------------------------------
    def sound(self, path):
        """A short detuned square pluck sliding down, like an 80s synth blip."""
        rate = 22050
        n = int(rate * 0.05)
        frames = bytearray()
        ph1 = ph2 = prev = 0.0
        for i in range(n):
            t = i / rate
            f = 1320 * (1 - 0.18 * t / 0.05)
            ph1 += f / rate
            ph2 += f * 1.007 / rate
            s = (0.5 if ph1 % 1 < 0.5 else -0.5) + (0.5 if ph2 % 1 < 0.5 else -0.5)
            prev += (s - prev) * 0.35  # gentle low-pass
            env = min(1.0, t / 0.002) * math.exp(-t * 85)
            frames += struct.pack("<h", int(6200 * env * prev))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))
