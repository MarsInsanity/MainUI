"""Ember: charcoal and fire. Basalt slabs with chamfered corners, lit from below by a molten glow."""
import math
import random
import struct
import wave
from functools import lru_cache

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps

from theme_kit import APPS, GLYPHS, MENU_TILES as MENU_KEYS, SS, TILES, Pen, Style, canvas, finish, rgba

# ---- palette ------------------------------------------------------------------------------
CHAR = (20, 16, 16)
BASALT_HI = (40, 32, 30)
BASALT = (28, 22, 21)
DEEP_RED = (150, 22, 10)
EMBER_RED = (196, 44, 14)
ORANGE = (255, 106, 26)
AMBER = (255, 150, 44)
GOLD = (255, 196, 92)
YELLOW = (255, 226, 128)
WHITE_HOT = (255, 249, 228)
TEXT = (255, 240, 226)
MUTED = (204, 170, 150)
INK = (36, 14, 8)

# molten ramps, cool (0) to hot (1)
MOLTEN = [(0.0, DEEP_RED), (0.35, EMBER_RED), (0.65, ORANGE), (0.85, AMBER), (1.0, YELLOW)]
GOLD_LINE = [(0.0, (255, 214, 120)), (0.55, (255, 168, 64)), (1.0, (240, 104, 34))]
HOT_LINE = [(0.0, WHITE_HOT), (0.6, (255, 236, 160)), (1.0, (255, 196, 84))]
ICON_ZOOM = 1.1  # system pictures drawn a little larger: the badge is gone


# ---- small image helpers ------------------------------------------------------------------

def _c4(c):
    return tuple(c) if len(c) == 4 else tuple(c) + (255,)


def ramp_lut(stops):
    """256 RGBA entries along [(t, colour), ...] (t in 0..1, ascending)."""
    stops = [(t, _c4(c)) for t, c in stops]
    out = []
    for i in range(256):
        t = i / 255
        c = stops[-1][1]
        for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
            if t <= t1:
                u = 0.0 if t1 <= t0 else max(0.0, (t - t0) / (t1 - t0))
                c = tuple(c0[k] + (c1[k] - c0[k]) * u for k in range(4))
                break
        if t < stops[0][0]:
            c = stops[0][1]
        out.append(tuple(int(round(v)) for v in c))
    return out


def colorize(field, stops):
    """An L field mapped through a colour ramp (RGBA)."""
    lut = ramp_lut(stops)
    return Image.merge("RGBA", [field.point([c[k] for c in lut]) for k in range(4)])


def field(w, h, fn, step=2):
    """L image of fn(x, y) in 0..1 (pixel coordinates), sampled every [step] px, smoothed up."""
    sw, sh = max(2, w // step + 1), max(2, h // step + 1)
    im = Image.new("L", (sw, sh))
    px = im.load()
    for j in range(sh):
        for i in range(sw):
            v = fn(i * w / (sw - 1), j * h / (sh - 1))
            px[i, j] = int(255 * min(1.0, max(0.0, v)))
    return im.resize((w, h), Image.BILINEAR)


def vramp(w, h, stops, y0=0.0, y1=None):
    """Vertical colour ramp; t runs 0 at y0 to 1 at y1."""
    y1 = h if y1 is None else y1
    col = Image.new("L", (1, h))
    for y in range(h):
        col.putpixel((0, y), int(255 * min(1.0, max(0.0, (y + 0.5 - y0) / max(1e-6, y1 - y0)))))
    return colorize(col, stops).resize((w, h), Image.NEAREST)


def hramp(w, h, stops, x0=0.0, x1=None):
    x1 = w if x1 is None else x1
    row = Image.new("L", (w, 1))
    for x in range(w):
        row.putpixel((x, 0), int(255 * min(1.0, max(0.0, (x + 0.5 - x0) / max(1e-6, x1 - x0)))))
    return colorize(row, stops).resize((w, h), Image.NEAREST)


def masked(src, mask):
    """[src] with its alpha multiplied by [mask]."""
    out = src.copy()
    out.putalpha(ImageChops.multiply(src.getchannel("A"), mask))
    return out


def solid(w, h, c):
    return Image.new("RGBA", (w, h), _c4(c))


def halo(mask, color, radius, k=1.0):
    """A blurred copy of [mask] in [color]."""
    m = mask.filter(ImageFilter.GaussianBlur(radius))
    if k != 1.0:
        m = m.point(lambda v: min(255, int(v * k)))
    return masked(solid(mask.width, mask.height, color), m)


def comp(*layers):
    out = layers[0]
    for lay in layers[1:]:
        out = Image.alpha_composite(out, lay)
    return out


def chamfer(x0, y0, x1, y1, c):
    """Rectangle with 45-degree corner cuts: c, or (top-left, top-right, bottom-right, bottom-left)."""
    tl, tr, br, bl = c if isinstance(c, (tuple, list)) else (c,) * 4
    return [(x0 + tl, y0), (x1 - tr, y0), (x1, y0 + tr), (x1, y1 - br), (x1 - br, y1), (x0 + bl, y1),
            (x0, y1 - bl), (x0, y0 + tl)]


def chamfer_in(box, c, d):
    """The same chamfered shape moved [d] px inwards on every side."""
    cs = c if isinstance(c, (tuple, list)) else (c,) * 4
    k = d * (2 - math.sqrt(2))
    return chamfer(box[0] + d, box[1] + d, box[2] - d, box[3] - d, tuple(max(0.0, v - k) for v in cs))


def poly_mask(w, h, pts):
    m = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(m).polygon([(x * SS, y * SS) for x, y in pts], fill=255)
    return m.resize((w, h), Image.LANCZOS)


def ring_mask(w, h, box, c, width, inset=0.0):
    outer = poly_mask(w, h, chamfer_in(box, c, inset) if inset else chamfer(*box, c))
    inner = poly_mask(w, h, chamfer_in(box, c, inset + width))
    return ImageChops.subtract(outer, inner)


def fbm(w, h, seed, base=(5, 4), octaves=5, persistence=0.55):
    """Fractal value noise (L, stretched to 0..255)."""
    rnd = random.Random(seed)
    out, acc, amp = None, 0.0, 1.0
    bw, bh = base
    for o in range(octaves):
        sw, sh = bw * 2 ** o, bh * 2 ** o
        small = Image.frombytes("L", (sw + 3, sh + 3), rnd.randbytes((sw + 3) * (sh + 3)))
        big = small.resize((w, h), Image.BICUBIC, box=(1, 1, sw + 1, sh + 1))
        out = big if out is None else Image.blend(out, big, amp / (acc + amp))
        acc += amp
        amp *= persistence
    return ImageOps.autocontrast(out, cutoff=0.5)


def swirl(im, seed, amount, cells=(16, 12)):
    """Curls [im] with a smooth random mesh warp (vertices moved by up to [amount] px)."""
    rnd = random.Random(seed)
    w, h = im.size
    cx, cy = cells
    phase = [rnd.uniform(0, 6.3) for _ in range(4)]

    def moved(i, j):
        x, y = i * w / cx, j * h / cy
        a = phase[0] + 2.1 * math.sin(i * 0.7 + phase[1]) + 1.7 * math.cos(j * 0.9 + phase[2]) + rnd.uniform(-0.6, 0.6)
        r = amount * (0.5 + 0.5 * math.sin(i * 0.45 + j * 0.6 + phase[3]))
        return x + r * math.cos(a), y + r * math.sin(a)

    grid = [[moved(i, j) for j in range(cy + 1)] for i in range(cx + 1)]
    mesh = []
    for i in range(cx):
        for j in range(cy):
            box = (int(i * w / cx), int(j * h / cy), int((i + 1) * w / cx), int((j + 1) * h / cy))
            ul, ll, lr, ur = grid[i][j], grid[i][j + 1], grid[i + 1][j + 1], grid[i + 1][j]
            mesh.append((box, ul + ll + lr + ur))
    return im.transform(im.size, Image.MESH, mesh, Image.BICUBIC)


@lru_cache(maxsize=None)
def edge_fade(w, h, d):
    """L mask rising from 0 at the image's edges to 255 [d] px in."""
    return field(w, h, lambda x, y: min(x, y, w - x, h - y) / d, step=1)


def glyph_mask(w, h, fn):
    """Alpha mask of a drawing made by fn(draw, colour) on a supersampled canvas."""
    im = canvas(w, h)
    fn(ImageDraw.Draw(im), (255, 255, 255, 255))
    return finish(im).getchannel("A")


def flame_pts(cx, cy, w, h, lean=0.18, n=48):
    """A teardrop flame, tip up, its tip leaning right by [lean] of its width."""
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        y = -math.cos(t)
        x = math.sin(t) * math.sin(t / 2) ** 1.6 + lean * ((1 - y) / 2) ** 2.2
        pts.append((cx + x * w / 2, cy + y * h / 2))
    return pts


def text_mask(w, h, s, font, x, cy, spacing=0.0, anchor="l"):
    """Mask of [s] at 1x coordinates, letter-spaced; anchor l (left) or m (centre)."""
    im = Image.new("L", (w * SS, h * SS), 0)
    d = ImageDraw.Draw(im)
    widths = [d.textlength(ch, font=font) / SS for ch in s]
    total = sum(widths) + spacing * (len(s) - 1)
    xx = x - total / 2 if anchor == "m" else x
    for ch, cw in zip(s, widths):
        d.text((xx * SS, cy * SS), ch, font=font, fill=255, anchor="lm")
        xx += cw + spacing
    return im.resize((w, h), Image.LANCZOS)


# ---- the theme ------------------------------------------------------------------------------

class Theme(Style):
    name = "Ember"
    author = "MainUI"
    description = "Charcoal and fire: basalt slabs with chamfered corners, rim-lit by a molten glow rising from below."

    bg_top = (14, 11, 11)
    bg_bottom = CHAR
    accent = ORANGE
    accent2 = EMBER_RED
    highlight = GOLD
    text = TEXT
    muted = MUTED
    glyph_n = GOLD
    glyph_f = WHITE_HOT
    panel = (34, 26, 24)
    panel_alpha = 246
    bar = (12, 9, 9)
    bar_alpha = 232

    font_file = "Rajdhani-Bold.ttf"
    font_license = "OFL-Rajdhani.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 32, 30, 27, 27, 18, 21

    # ---- background --------------------------------------------------------------------------
    def background(self, w=640, h=480):
        rnd = random.Random(1451)
        im = vramp(w, h, [(0, (12, 10, 10)), (0.55, CHAR), (1, (26, 16, 14))])

        # smoke: ridged, vertically stretched noise, lit red-orange near the bottom
        n1 = fbm(w, h, 11, base=(4, 2), octaves=5)
        n2 = fbm(w, h, 12, base=(7, 3), octaves=4)
        ridge = ImageChops.multiply(n1.point(lambda v: 255 - abs(2 * v - 255)), n2)
        wisps = swirl(ridge.point(lambda v: max(0, v - 70) * 255 // 185), 31, 34)
        rise = field(w, h, lambda x, y: 0.45 + 0.55 * (y / h) ** 1.2, step=8)
        smoke_a = ImageChops.multiply(wisps, rise).point(lambda v: v * 72 // 255)
        im = comp(im, masked(solid(w, h, (112, 90, 84)), smoke_a))

        # molten glow from the bottom edge, kept low so list text stays readable
        blobs = Image.new("L", (w, h), 0)
        hd = ImageDraw.Draw(blobs)
        for cx, rx, v in ((60, 170, 255), (250, 150, 190), (420, 170, 240), (600, 150, 210)):
            hd.ellipse((cx - rx, h - 60, cx + rx, h + 60), fill=v)
        blobs = blobs.filter(ImageFilter.GaussianBlur(60)).point(lambda v: 150 + v * 105 // 255)
        rise = field(w, h, lambda x, y: max(0.0, (y - 225) / (h - 225)) ** 2.1, step=4)
        turb = fbm(w, h, 21, base=(9, 3), octaves=4).point(lambda v: 165 + v * 90 // 255)
        heat = ImageChops.multiply(ImageChops.multiply(rise, blobs), turb).point(lambda v: min(255, int(v * 1.3)))
        im = comp(im, colorize(heat, [(0, (60, 8, 4, 0)), (0.18, (110, 16, 6, 110)), (0.45, (178, 36, 10, 200)),
                                      (0.72, (238, 92, 20, 235)), (1.0, (255, 176, 70, 255))]))
        # smoke lit from below
        lit = ImageChops.multiply(ImageChops.multiply(wisps, heat), Image.new("L", (w, h), 120))
        im = comp(im, masked(solid(w, h, (255, 120, 40)), lit))

        im = comp(im, self._embers(w, h, rnd))

        vig = field(w, h, lambda x, y: min(1.0, ((x - w / 2) / (w * 0.62)) ** 2 + ((y - h * 0.62) / (h * 0.75)) ** 2) ** 1.3,
                    step=8)
        im = comp(im, masked(solid(w, h, (6, 4, 4)), vig.point(lambda v: v * 170 // 255)))
        return im

    def _embers(self, w, h, rnd):
        core = canvas(w, h)
        cd = ImageDraw.Draw(core)
        glow_l = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow_l)
        trails = canvas(w, h)
        td = ImageDraw.Draw(trails)
        bokeh = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        bd = ImageDraw.Draw(bokeh)
        for _ in range(14):
            x, y = rnd.uniform(0, w), h - h * rnd.random() ** 0.9 * 0.85
            r = rnd.uniform(3, 7)
            c = rgba(rnd.choice((ORANGE, AMBER, EMBER_RED)), rnd.randint(28, 60))
            bd.ellipse((x - r, y - r, x + r, y + r), fill=c)
        for _ in range(130):
            depth = rnd.random() ** 1.7          # 0 near the bottom edge, 1 high up
            x = rnd.uniform(4, w - 4)
            y = h - 6 - depth * (h - 70)
            r = 0.45 + 1.5 * rnd.random() ** 2.5 * (1.2 - depth)
            heat = rnd.random() * (1.1 - depth * 0.6)
            col = next(c for lim, c in ((0.85, WHITE_HOT), (0.6, YELLOW), (0.35, AMBER), (0.15, ORANGE), (-1, EMBER_RED))
                       if heat > lim)
            a = int(255 * (1 - depth * 0.55))
            cd.ellipse([v * SS for v in (x - r, y - r, x + r, y + r)], fill=rgba(col, a))
            gr = r * 3.2 + 1.5
            gd.ellipse((x - gr, y - gr, x + gr, y + gr), fill=rgba(ORANGE if heat > 0.3 else EMBER_RED, int(a * 0.45)))
            if rnd.random() < 0.32:               # a rising ember leaves a curved trail below it
                length = rnd.uniform(8, 26) * (1.2 - depth)
                drift, bend = rnd.uniform(-0.45, 0.45) * length, rnd.uniform(-1, 1) * length * 0.6
                steps = 10
                pts = [(x + drift * k / steps + bend * (k / steps) ** 2, y + length * k / steps) for k in range(steps + 1)]
                for k in range(steps):
                    (xa, ya), (xb, yb) = pts[k], pts[k + 1]
                    td.line([(xa * SS, ya * SS), (xb * SS, yb * SS)], fill=rgba(col, int(a * 0.7 * (1 - k / steps))),
                            width=max(1, int(r * 1.1 * SS)))
        out = bokeh.filter(ImageFilter.GaussianBlur(2.2))
        out = comp(out, glow_l.filter(ImageFilter.GaussianBlur(3)))
        out = comp(out, finish(trails).filter(ImageFilter.GaussianBlur(0.4)))
        return comp(out, finish(core))

    # ---- bars and logo -----------------------------------------------------------------------
    def _bar(self, fill, base, step):
        """A bar whose edge at y [base] steps by [step] (45 degrees) into a tab in the middle, traced in fire."""
        w, h = fill.size
        x0, x1 = 320 - 112, 320 + 112
        edge = [(x1 + abs(step), base), (x1, base + step), (x0, base + step), (x0 - abs(step), base)]
        far = 0 if step > 0 else h
        shape = [(0, far), (w, far), (w, base)] + edge + [(0, base)]
        body = masked(fill, poly_mask(w, h, shape))
        m = canvas(w, h)
        ImageDraw.Draw(m).line([(px * SS, py * SS) for px, py in [(w + 4, base)] + edge + [(-4, base)]],
                               fill=(255, 255, 255, 255), width=int(1.6 * SS))
        m = finish(m).getchannel("A")
        col = hramp(w, h, [(0, (150, 26, 10, 120)), (0.3, (236, 76, 18, 230)), (0.5, (255, 168, 64, 255)),
                           (0.7, (236, 76, 18, 230)), (1, (150, 26, 10, 120))])
        return comp(body, halo(m, (255, 96, 24), 4, 1.2), masked(col, m))

    def header_bar(self):
        return self._bar(vramp(640, 60, [(0, (9, 7, 7, 244)), (1, (18, 13, 12, 236))]), 53.5, 5)

    def footer_bar(self):
        fill = vramp(640, 60, [(0, (12, 9, 9, 236)), (0.6, (16, 11, 10, 222)), (1, (34, 12, 8, 200))])
        return self._bar(fill, 6.5, -5)

    def _flame(self, w, h, cx, cy, fw, fh):
        outer = poly_mask(w, h, flame_pts(cx, cy, fw, fh, 0.22))
        inner = poly_mask(w, h, flame_pts(cx + fw * 0.04, cy + fh * 0.16, fw * 0.5, fh * 0.6, 0.12))
        o = masked(vramp(w, h, [(0, YELLOW), (0.45, ORANGE), (1, EMBER_RED)], cy - fh / 2, cy + fh / 2), outer)
        i = masked(vramp(w, h, [(0, WHITE_HOT), (1, (255, 214, 110))], cy - fh * 0.15, cy + fh / 2), inner)
        return comp(halo(outer, ORANGE, 3), o, i)

    def logo(self):
        w, h = 164, 42
        box = (5, 6, 35, 36)
        plate = masked(vramp(w, h, [(0, (46, 30, 26)), (1, (70, 26, 14))], 6, 36), poly_mask(w, h, chamfer(*box, 8)))
        rim = masked(vramp(w, h, [(0, (150, 60, 30, 160)), (1, (255, 120, 40))], 6, 36), ring_mask(w, h, box, 8, 1.4))
        out = comp(plate, rim, self._flame(w, h, 20, 21.5, 15, 22))
        tm = text_mask(w, h, "EMBER", self.font(27), 44, 22, spacing=2.6)
        txt = masked(vramp(w, h, [(0, WHITE_HOT), (0.45, (255, 220, 140)), (1, AMBER)], 11, 33), tm)
        return comp(out, halo(tm, ORANGE, 4, 0.7), txt)

    # ---- lists --------------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        m = 5 if h < 70 else 7
        cut = 10 if h < 70 else 14
        box = (4, m, w - 4, h - m)
        shape = poly_mask(w, h, chamfer(*box, cut))
        fill = comp(hramp(w, h, [(0, (92, 30, 14, 240)), (0.25, (54, 24, 18, 236)), (1, (34, 22, 20, 228))]),
                    vramp(w, h, [(0, (0, 0, 0, 0)), (0.7, (0, 0, 0, 0)), (1, (150, 40, 12, 70))], m, h - m))
        body = masked(fill, shape)
        rim = ring_mask(w, h, box, cut, 1.8)
        rim_col = hramp(w, h, [(0, YELLOW), (0.06, ORANGE), (0.5, (236, 84, 22)), (1, (190, 50, 16))])
        out = comp(halo(rim, (255, 90, 20), 3.2, 1.3), body, masked(rim_col, rim))
        # the left end glows like a bar pulled from the forge (inside the part kept when stretched)
        hot_end = ImageChops.multiply(shape, field(w, h, lambda x, y: 1 - (x - 4) / 16, step=2))
        out = comp(out, masked(hramp(w, h, [(0, (255, 236, 160)), (0.4, ORANGE), (1, (200, 50, 14, 0))], 4, 20), hot_end))
        return out

    def divider(self):
        w, h = 640, 4
        m = canvas(w, h)
        ImageDraw.Draw(m).line([(20 * SS, 2 * SS), (620 * SS, 2 * SS)], fill=(255, 255, 255, 255), width=SS)
        col = hramp(w, h, [(0, (120, 60, 40, 0)), (0.12, (130, 70, 50, 54)), (0.5, (150, 80, 56, 70)),
                           (0.88, (130, 70, 50, 54)), (1, (120, 60, 40, 0))])
        return masked(col, finish(m).getchannel("A"))

    # ---- slabs ----------------------------------------------------------------------------------
    def slab(self, w, h, box, cut, focused, glow_r=6, under=True):
        """A basalt slab: chamfered and rim-lit from below; molten edged and glowing when focused."""
        bx0, by0, bx1, by1 = box
        shape = poly_mask(w, h, chamfer(*box, cut))
        if focused:
            body = vramp(w, h, [(0, (50, 28, 24)), (0.55, (62, 26, 18)), (1, (112, 36, 14))], by0, by1)
        else:
            body = vramp(w, h, [(0, BASALT_HI), (0.6, BASALT), (1, (34, 21, 18))], by0, by1)
        if under:  # heat reflected up from below
            cx = (bx0 + bx1) / 2
            hw, hh = (bx1 - bx0) * 0.75, (by1 - by0) * (0.5 if focused else 0.32)
            f = field(w, h, lambda x, y: 1 - math.hypot((x - cx) / hw, (y - by1) / hh), step=4)
            body = comp(body, masked(solid(w, h, (255, 92, 20) if focused else (200, 56, 16)),
                                     f.point(lambda v: v * (120 if focused else 60) // 255)))
        out = masked(body, shape.point(lambda v: v * 250 // 255))
        # faint top bevel and an inner chamfer line
        top = ring_mask(w, h, box, cut, 1.0)
        out = comp(out, masked(vramp(w, h, [(0, (190, 150, 130, 70)), (0.3, (190, 150, 130, 0))], by0, by1), top))
        inner = ring_mask(w, h, box, cut, 1.0, inset=5)
        out = comp(out, masked(solid(w, h, (255, 170, 120, 16 if not focused else 26)), inner))
        if focused:
            rim = ring_mask(w, h, box, cut, 3.2)
            hot = field(w, h, lambda x, y: 0.62 * (y - by0) / (by1 - by0) + 0.38 * (x - bx0) / (bx1 - bx0), step=4)
            col = colorize(hot, MOLTEN)
            inside = ImageChops.multiply(rim.filter(ImageFilter.GaussianBlur(glow_r * 0.8)), shape)
            outside = halo(rim, ORANGE, glow_r, 1.6)
            out = comp(outside, out, masked(solid(w, h, (255, 110, 30)), inside.point(lambda v: v * 120 // 255)),
                       masked(col, rim))
        else:
            rim = ring_mask(w, h, box, cut, 1.6)
            col = vramp(w, h, [(0, (150, 96, 76, 60)), (0.5, (214, 92, 40, 110)), (1, (255, 128, 44, 235))], by0, by1)
            out = comp(halo(ImageChops.multiply(rim, field(w, h, lambda x, y: (y - by0) / (by1 - by0), step=4)),
                            ORANGE, 3, 0.8), out, masked(col, rim))
        return out

    def card(self, w, h, focused):
        return self.slab(w, h, (6, 6, w - 6, h - 6), 14, focused, glow_r=4.5)

    def fissures(self, w, h, box, cut, focused, seed):
        """Hairline cracks near two corners of a slab, glowing from the heat inside."""
        rnd = random.Random(seed)
        bx0, by0, bx1, by1 = box
        m = canvas(w, h)
        d = ImageDraw.Draw(m)

        def crack(x, y, ang, n, width):
            pts = [(x, y)]
            for _ in range(n):
                ang += rnd.uniform(-0.55, 0.55)
                step = rnd.uniform(4, 8)
                x, y = x + step * math.cos(ang), y + step * math.sin(ang)
                pts.append((x, y))
                if rnd.random() < 0.18 and n > 3:
                    crack(x, y, ang + rnd.choice((-0.9, 0.9)), 2, width * 0.7)
            d.line([(px * SS, py * SS) for px, py in pts], fill=(255, 255, 255, 255), width=max(1, int(width * SS)),
                   joint="curve")

        # up from the bottom edge (below the label band) and in from the top corner
        crack(rnd.uniform(bx0 + cut + 4, bx0 + (bx1 - bx0) * 0.45), by1 + 1, -math.pi / 2 + rnd.uniform(0.2, 0.6),
              rnd.randint(5, 7), 1.4)
        crack(bx1 + 1, rnd.uniform(by0 + cut + 2, by0 + cut + 14), math.pi + rnd.uniform(0.15, 0.5), rnd.randint(3, 5), 1.1)
        mask = ImageChops.multiply(finish(m).getchannel("A"), poly_mask(w, h, chamfer(*box, cut)))
        if focused:
            return comp(halo(mask, ORANGE, 2.5, 1.6), masked(solid(w, h, (255, 200, 110)), mask))
        return comp(halo(mask, EMBER_RED, 2, 0.9), masked(solid(w, h, (226, 84, 30, 190)), mask))

    def tile_base(self, focused, seed=3):
        w, h = 156, 278
        box = (9, 9, w - 9, h - 9)
        out = comp(self.slab(w, h, box, 18, focused), self.fissures(w, h, box, 18, focused, seed))
        # forge mark under the label
        if focused:
            mark = poly_mask(w, h, chamfer(w / 2 - 22, 232, w / 2 + 22, 236, 2))
            col = hramp(w, h, [(0, ORANGE), (0.5, YELLOW), (1, ORANGE)], w / 2 - 22, w / 2 + 22)
            return comp(out, halo(mark, ORANGE, 4, 1.8), masked(col, mark))
        mark = poly_mask(w, h, chamfer(w / 2 - 12, 233, w / 2 + 12, 235, 1))
        return comp(out, masked(solid(w, h, (200, 90, 44, 120)), mark))

    def _ink(self, mask, focused, y0, y1, r=6, fade=None):
        """Glyph lines: gold to orange with a warm glow, or white-hot with a big heat glow.
        [fade]: a mask the glow is multiplied by (to keep it off a small canvas's edges)."""
        w, h = mask.size
        if focused:
            col = masked(vramp(w, h, HOT_LINE, y0, y1), mask)
            glows = comp(halo(mask, (255, 110, 24), r * 1.5, 1.4), halo(mask, (255, 190, 80), r / 2, 1.0))
        else:
            col = masked(vramp(w, h, GOLD_LINE, y0, y1), mask)
            glows = halo(mask, (255, 90, 20), r, 0.75)
        return comp(masked(glows, fade) if fade else glows, col)

    def tile_glyph(self, key, focused):
        w, h = 156, 278
        mask = glyph_mask(w, h, lambda d, c: TILES[key](d, 78, 112, 38, c, 5.6))
        return self._ink(mask, focused, 74, 150)

    def home_tile(self, key, focused):
        base = self.tile_base(focused, seed=3 + (MENU_KEYS.index(key) * 7 if key else 0))
        return comp(base, self.tile_glyph(key, focused)) if key else base

    def preview_frame(self):
        return self.slab(250, 360, (4, 4, 246, 356), 18, False)

    def key(self, focused):
        w, h = 64, 52
        box = (3, 3, w - 3, h - 3)
        if not focused:
            return self.slab(w, h, box, 8, False, under=False)
        shape = poly_mask(w, h, chamfer(*box, 8))
        body = masked(vramp(w, h, [(0, (96, 30, 12)), (1, (140, 40, 12))], 3, h - 3), shape)
        rim = ring_mask(w, h, box, 8, 2.2)
        return comp(halo(rim, ORANGE, 2.5, 1.4), body, masked(vramp(w, h, [(0, YELLOW), (1, ORANGE)]), rim))

    def input_box(self):
        w, h = 560, 60
        box = (2, 3, w - 2, h - 3)
        shape = poly_mask(w, h, chamfer(*box, 10))
        body = masked(vramp(w, h, [(0, (8, 6, 6, 246)), (1, (20, 13, 12, 240))]), shape)
        shade = ring_mask(w, h, box, 10, 5)
        rim = ring_mask(w, h, box, 10, 1.6)
        rim_col = vramp(w, h, [(0, (120, 60, 40, 140)), (0.6, (230, 90, 30, 200)), (1, (255, 140, 50, 255))], 3, h - 3)
        return comp(body, masked(solid(w, h, (0, 0, 0, 90)), shade.filter(ImageFilter.GaussianBlur(2))),
                    halo(rim, ORANGE, 3, 0.6), masked(rim_col, rim))

    def panel_image(self, w, h):
        cut = (6, 6, 16, 16) if h < 300 else 18
        box = (2, 2, w - 2, h - 2)
        shape = poly_mask(w, h, chamfer(*box, cut))
        body = vramp(w, h, [(0, (34, 26, 24)), (0.75, (28, 21, 20)), (1, (52, 22, 15))])
        out = masked(body, shape)
        rim = ring_mask(w, h, box, cut, 1.8)
        col = vramp(w, h, [(0, (170, 90, 60, 110)), (0.5, (220, 86, 30, 170)), (1, (255, 140, 48, 255))])
        return comp(out, halo(rim, ORANGE, 3, 0.7), masked(col, rim))

    # ---- buttons and small parts ------------------------------------------------------------------
    def button(self, label, primary):
        n = 54
        box = (3, 3, n - 3, n - 3)
        cut = 14
        shape = poly_mask(n, n, chamfer(*box, cut))
        rim = ring_mask(n, n, box, cut, 2.0)
        tm = text_mask(n, n, label, self.font(31), n / 2, n / 2 + 1, anchor="m")
        if primary:
            body = masked(vramp(n, n, [(0, YELLOW), (0.4, ORANGE), (1, EMBER_RED)], 4, n - 4), shape)
            hi = ring_mask(n, n, box, cut, 1.4, inset=1.5)
            return comp(body, masked(vramp(n, n, [(0, (255, 255, 230, 200)), (0.5, (255, 255, 230, 0))]), hi),
                        masked(solid(n, n, INK), tm))
        body = masked(vramp(n, n, [(0, (48, 36, 33)), (1, (24, 18, 17))], 4, n - 4), shape)
        rim_col = vramp(n, n, [(0, (200, 120, 70)), (0.5, (240, 100, 32)), (1, (255, 150, 50))], 4, n - 4)
        return comp(body, masked(rim_col, rim), masked(vramp(n, n, [(0, YELLOW), (1, AMBER)], 16, 40), tm))

    def pill(self, label, w, h=48):
        box = (2, 8, w - 2, h - 8)
        cut = 9
        shape = poly_mask(w, h, chamfer(*box, cut))
        body = masked(vramp(w, h, [(0, (48, 36, 33)), (1, (24, 18, 17))], 8, h - 8), shape)
        rim = ring_mask(w, h, box, cut, 1.6)
        rim_col = vramp(w, h, [(0, (200, 120, 70)), (0.5, (240, 100, 32)), (1, (255, 150, 50))], 8, h - 8)
        tm = text_mask(w, h, label, self.font(19), w / 2, h / 2 + 1, spacing=1.2, anchor="m")
        return comp(body, masked(rim_col, rim), masked(solid(w, h, TEXT), tm))

    def dot(self, active):
        w, h = 24, 14
        if active:
            m = poly_mask(w, h, chamfer(3, 3.5, 21, 10.5, 3))
            return comp(halo(m, (10, 4, 2), 1.6, 2.2), halo(m, ORANGE, 2.5, 1.2),
                        masked(hramp(w, h, [(0, YELLOW), (0.5, WHITE_HOT), (1, YELLOW)], 3, 21), m))
        m = poly_mask(w, h, [(12, 3), (16, 7), (12, 11), (8, 7)])
        inner = poly_mask(w, h, [(12, 5), (14, 7), (12, 9), (10, 7)])
        return comp(masked(solid(w, h, (16, 10, 9, 230)), m), masked(solid(w, h, (176, 128, 108, 235)), inner))

    def toggle(self, on):
        w, h = 92, 44
        box = (6, 11, w - 6, h - 11)
        track = poly_mask(w, h, chamfer(*box, 8))
        rim = ring_mask(w, h, box, 8, 1.6)
        kx = w - 6 - 13 if on else 6 + 13
        kbox = (kx - 12, h / 2 - 12, kx + 12, h / 2 + 12)
        knob = poly_mask(w, h, chamfer(*kbox, 6))
        kring = ring_mask(w, h, kbox, 6, 1.4)
        if on:
            body = masked(hramp(w, h, [(0, DEEP_RED), (0.5, ORANGE), (1, YELLOW)], 6, w - 6), track)
            k = masked(vramp(w, h, [(0, WHITE_HOT), (1, (255, 214, 150))], kbox[1], kbox[3]), knob)
            return comp(halo(track, ORANGE, 3, 1.1), body, halo(knob, (60, 10, 0), 2, 0.8), k,
                        masked(solid(w, h, (150, 60, 20, 200)), kring))
        body = masked(vramp(w, h, [(0, (22, 17, 16)), (1, (36, 26, 24))], 11, h - 11), track)
        k = masked(vramp(w, h, [(0, (134, 112, 104)), (1, (92, 74, 68))], kbox[1], kbox[3]), knob)
        return comp(body, masked(solid(w, h, (130, 90, 74, 200)), rim), halo(knob, (0, 0, 0), 2, 0.9), k)

    def arrow(self, left, size=24):
        s = size
        pts = [(0.30, 0.16), (0.50, 0.16), (0.76, 0.50), (0.50, 0.84), (0.30, 0.84), (0.56, 0.50)]
        if left:
            pts = [(1 - x, y) for x, y in pts]
        m = poly_mask(s, s, [(x * s, y * s) for x, y in pts])
        return comp(halo(m, ORANGE, s * 0.08, 1.0), masked(vramp(s, s, [(0, YELLOW), (1, ORANGE)], s * 0.16, s * 0.84), m))

    def folder_icon(self):
        n = 60
        pts = [(9, 18), (13, 14), (24, 14), (28, 19), (47, 19), (51, 23), (51, 43), (47, 47), (13, 47), (9, 43)]
        fill = poly_mask(n, n, pts)
        line_m = glyph_mask(n, n, lambda d, c: d.line([(x * SS, y * SS) for x, y in pts + [pts[0], pts[1]]], fill=c,
                                                       width=int(3.4 * SS), joint="curve"))
        flap = glyph_mask(n, n, lambda d, c: d.line([(10 * SS, 27 * SS), (50 * SS, 27 * SS)], fill=c, width=int(2.4 * SS)))
        ink = ImageChops.lighter(line_m, flap)
        return comp(masked(solid(n, n, (196, 50, 16, 110)), fill), self._ink(ink, False, 14, 47, r=3))

    def favorite_mark(self):
        w, h = 34, 39
        pts = []
        for i in range(10):
            r = 14.5 if i % 2 == 0 else 6.4
            a = math.radians(-90 + i * 36)
            pts.append((17 + r * math.cos(a), 21 + r * math.sin(a)))
        m = poly_mask(w, h, pts)
        edge = ImageChops.subtract(m, m.filter(ImageFilter.MinFilter(3)))
        fill = vramp(w, h, [(0, WHITE_HOT), (0.35, YELLOW), (0.75, ORANGE), (1, EMBER_RED)], 7, 33)
        return comp(halo(m, ORANGE, 3, 1.2), masked(fill, m), masked(solid(w, h, (120, 30, 8, 170)), edge))

    def battery(self, level, charging=False):
        n = 48
        box = (6, 15, 40, 33)
        body = ring_mask(n, n, box, 4, 2.2)
        cap = poly_mask(n, n, chamfer(40, 20, 44, 28, 1))
        out = masked(solid(n, n, rgba(TEXT, 235)), ImageChops.lighter(body, cap))
        if level > 0:
            x1 = 9.5 + 27 * level
            lm = poly_mask(n, n, chamfer(9.5, 18.5, x1, 29.5, 2))
            if level <= 0.2:
                col = solid(n, n, (236, 52, 30))
            else:
                col = hramp(n, n, [(0, EMBER_RED), (0.45, ORANGE), (1, YELLOW)], 9.5, 36.5)
            out = comp(out, halo(lm, ORANGE, 2, 0.6), masked(col, lm))
        if charging:
            bolt = [(25, 11), (16, 26), (23, 26), (20, 37), (31, 21), (24, 21)]
            bm = poly_mask(n, n, bolt)
            out = comp(out, halo(bm, (20, 6, 2), 1.6, 2.5), masked(vramp(n, n, [(0, WHITE_HOT), (1, YELLOW)], 11, 37), bm))
        return out

    # ---- system and app icons ------------------------------------------------------------------------
    def _system_masks(self, sid, cy):
        """(silhouette, lines) masks of a system drawing: soft fills appear only in the silhouette."""
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        out = []
        for full in (True, False):
            im = canvas(120, 130)
            pen = Pen(ImageDraw.Draw(im), 60 / ICON_ZOOM, cy / ICON_ZOOM, (255, 255, 255, 255), SS * ICON_ZOOM, w=3.4)
            if full:
                pen.soft_c = (255, 255, 255, 255)
            draw(pen)
            r, _, _, a = finish(im).split()
            out.append(ImageChops.multiply(a, r))   # (dark details, like android's eyes, cut through)
        sil, both = out
        soft = Pen(None, 0, 0, (255, 255, 255, 255), SS).soft_c[3] / 255
        lines = ImageChops.subtract(both, sil.point(lambda v: int(v * soft)), scale=1 - soft)
        return sil, lines

    def icon_picture(self, sid, selected, cy):
        w, h = 120, 130
        sil, lines = self._system_masks(sid, cy)
        fills = masked(vramp(w, h, [(0, (200, 60, 18, 150)), (1, (150, 26, 10, 170))], cy - 50, cy + 50), sil)
        return comp(fills, self._ink(lines, selected, cy - 50, cy + 50, fade=edge_fade(w, h, 7)))

    def system_icon(self, sid, selected):
        # no name badge: MainUI writes the system's name under every icon, so the picture is centred
        return self.icon_picture(sid, selected, 65)

    def app_icon(self, key):
        n = 96
        box = (5, 5, n - 5, n - 5)
        out = self.slab(n, n, box, 18, False)
        hot = ring_mask(n, n, box, 18, 2.4)
        out = comp(out, masked(vramp(n, n, [(0, (200, 80, 30, 120)), (1, AMBER)], 5, n - 5), hot))
        mask = glyph_mask(n, n, lambda d, c: APPS[key](d, n / 2, n / 2, n * 0.26, c, n * 0.058))
        return comp(out, self._ink(mask, False, 22, 74))

    # ---- sound --------------------------------------------------------------------------------------------
    def sound(self, path):
        """A short ember crackle: a noise tick over a warm tone."""
        rate = 22050
        rnd = random.Random(77)
        frames = bytearray()
        lp = 0.0
        for i in range(int(rate * 0.045)):
            t = i / rate
            lp += (rnd.uniform(-1, 1) - lp) * 0.45
            v = 6200 * math.exp(-t * 150) * math.sin(2 * math.pi * 740 * t) + 7000 * math.exp(-t * 700) * lp
            frames += struct.pack("<h", max(-32767, min(32767, int(v))))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))

    # ---- config ---------------------------------------------------------------------------------------------
    def config(self):
        cfg = super().config()
        # MainUI writes white on its accent (fallback keys, system tiles): a deeper orange keeps that legible
        cfg["mainui"]["accent"] = "#D2451A"
        return cfg
