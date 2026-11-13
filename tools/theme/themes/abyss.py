"""Abyss: deep ocean. Sunlit turquoise surface fading into the navy abyss, light rays,
caustics, rising bubbles and kelp; porthole capsules and aqua-and-white duotone art."""
import math
import random
import struct
import wave

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from theme_kit import (APPS, GLYPHS, SS, TILES, Pen, S, Style, arc, canvas, circle, finish, glow,
                       lerp, line, mask_rrect, poly, rgba, rrect)

SURF = (31, 181, 196)      # sunlit surface
NAVY = (2, 11, 31)         # the abyss
AQUA = (84, 240, 228)
AQUA_DEEP = (12, 128, 146)
TEAL = (10, 72, 100)
FOAM = (234, 253, 255)
MUTED = (150, 200, 216)
GLYPH_N = (172, 216, 228)
PANEL = (6, 30, 54)
SAND = (255, 188, 104)     # starfish (favourites)
CORAL = (255, 112, 96)     # low battery

W, H = 640, 480


# ---- helpers ----------------------------------------------------------------------------

def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def stops_at(stops, t):
    """Colour at [t] along [(t, rgba), ...], eased between stops."""
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            return lerp(c0, c1, smooth((t - t0) / max(1e-6, t1 - t0)))
    return stops[-1][1]


def vgrad(w, h, stops):
    col = Image.new("RGBA", (1, h))
    px = col.load()
    for y in range(h):
        px[0, y] = stops_at(stops, y / max(1, h - 1))
    return col.resize((w, h), Image.NEAREST)


def masked(im, mask):
    """[im] with its alpha multiplied by [mask] (L)."""
    out = im.copy()
    out.putalpha(ImageChops.multiply(im.getchannel("A"), mask))
    return out


def solid(size, color, mask):
    return masked(Image.new("RGBA", size, rgba(color, color[3] if len(color) > 3 else 255)), mask)


def scale_alpha(im, f):
    out = im.copy()
    out.putalpha(im.getchannel("A").point(lambda v: int(v * f)))
    return out


def clear_border(im):
    """[im] with its outermost pixel ring transparent: pieces MainUI lays edge to edge must not
    show blur or resampling residue along the seams."""
    a = Image.new("L", im.size, 0)
    a.paste(255, (1, 1, im.width - 1, im.height - 1))
    return masked(im, a)


def circle_mask(w, h, cx, cy, r):
    m = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(m).ellipse([S(cx - r), S(cy - r), S(cx + r), S(cy + r)], fill=255)
    return m.resize((w, h), Image.LANCZOS)


def radial_fill(w, h, cx, cy, r, inner, outer):
    """Radial gradient (computed small, scaled up: it is smooth)."""
    k = 4
    sw, sh = max(1, w // k), max(1, h // k)
    im = Image.new("RGBA", (sw, sh))
    px = im.load()
    for y in range(sh):
        for x in range(sw):
            t = min(1.0, math.hypot((x + 0.5) * k - cx, (y + 0.5) * k - cy) / r)
            px[x, y] = lerp(inner, outer, t)
    return im.resize((w, h), Image.BICUBIC)


def crescent(w, h, box, r, dx, dy):
    """The rim of a rounded shape left over when the shape moved by (dx, dy) is cut out."""
    a = mask_rrect(w, h, box, r)
    b = mask_rrect(w, h, (box[0] + dx, box[1] + dy, box[2] + dx, box[3] + dy), r)
    return ImageChops.subtract(a, b)


def bubble(d, cx, cy, r, a=1.0):
    """A bubble on a supersampled canvas: thin rim, faint body, a bright glint."""
    circle(d, cx, cy, r, fill=rgba(AQUA, int(26 * a)))
    circle(d, cx, cy, r, rgba(FOAM, int(150 * a)), max(0.8, r * 0.14))
    if r >= 2.2:
        arc(d, cx, cy, r * 0.68, 200, 255, rgba(FOAM, int(220 * a)), max(0.7, r * 0.16))
        arc(d, cx, cy, r * 0.74, 20, 70, rgba(FOAM, int(80 * a)), max(0.6, r * 0.1))
    else:
        circle(d, cx - r * 0.35, cy - r * 0.35, r * 0.35, fill=rgba(FOAM, int(200 * a)))


def caustic_light(w, h, scale, t0=23.0, sy=1.0, sharp=8, rows_used=None):
    """Wavy filaments of focused light (L): an iterated domain warp, as in the well-known
    tileable water caustic shader. [scale] px per radian, [sy] squashes it vertically."""
    im = Image.new("L", (w, h))
    px = im.load()
    ts = [t0 * (1.0 - 3.5 / (n + 1)) for n in range(5)]
    inten = 0.005
    for y in range(min(h, rows_used or h)):
        for x in range(w):
            # a slow, non-periodic drift so the pattern does not repeat every 2 pi [scale]
            p_x = x / scale - 250.0 + 0.9 * math.sin(y * sy / (scale * 3.7) + 1.3) + 0.6 * math.sin(x / (scale * 5.3))
            py = y * sy / scale - 250.0 + 0.8 * math.sin(x / (scale * 4.1)) + 0.5 * math.sin(y * sy / (scale * 2.9))
            ix, iy = p_x, py
            c = 1.0
            for t in ts:
                ix, iy = p_x + math.cos(t - ix) + math.sin(t + iy), py + math.sin(t - iy) + math.cos(t + ix)
                a, b = math.sin(ix + t) or 1e-9, math.cos(iy + t) or 1e-9
                c += 1.0 / (math.hypot(p_x * inten / a, py * inten / b) + 1e-9)
            c = 1.17 - (c / 5) ** 1.4
            px[x, y] = min(255, int(255 * abs(c) ** sharp))
    return im


def kelp(d, x0, y0, height, sway, phase, color, width, seed, side):
    """A kelp frond growing up from (x0, y0): a swaying stipe with blades and floats."""
    rnd = random.Random(seed)
    n = 48
    pts = []
    for i in range(n + 1):
        t = i / n
        x = x0 + sway * math.sin(t * 4.2 + phase) * t + side * 18 * t * t
        pts.append((x, y0 - height * t))
    line(d, pts, color, width)
    k = 1
    for i in range(4, n - 1, 3):
        t = i / n
        bx, by = pts[i]
        out = k * (1 if rnd.random() < 0.5 else -1) if i % 2 else -k
        k = -k
        ln = (34 - 16 * t) * rnd.uniform(0.8, 1.15)
        ang = math.radians(rnd.uniform(28, 52))
        tx, ty = bx + out * ln * math.sin(ang), by - ln * math.cos(ang)
        bw = (7.5 - 3 * t) * rnd.uniform(0.85, 1.1)
        left, right = [], []
        for s in range(13):
            u = s / 12
            cx = bx + (tx - bx) * u + out * 4 * math.sin(math.pi * u)
            cy = by + (ty - by) * u
            hw = bw * math.sin(math.pi * u) ** 0.7 * (1 - 0.3 * u)
            nx, ny = (ty - by), -(tx - bx)
            nl = math.hypot(nx, ny) or 1
            left.append((cx + nx / nl * hw, cy + ny / nl * hw))
            right.append((cx - nx / nl * hw, cy - ny / nl * hw))
        poly(d, left + right[::-1], fill=color)
        circle(d, bx + out * 2.5, by - 2, max(1.6, width * 0.8), fill=color)


def duo_body(pic, color):
    """[pic] with [color] filling the areas its closed outlines enclose (flood-filled from outside)."""
    a = pic.getchannel("A").point(lambda v: 255 if v > 60 else 0).filter(ImageFilter.MaxFilter(3))
    ImageDraw.floodfill(a, (0, 0), 128)
    inside = a.point(lambda v: 255 if v == 0 else 0).filter(ImageFilter.GaussianBlur(0.6))
    return Image.alpha_composite(solid(pic.size, color, inside), pic)


# ---- duotone menu pictures: the shared glyph lines over fills of the same shapes ---------

def _star_pts(cx, cy, s):
    pts = []
    for i in range(10):
        r = s if i % 2 == 0 else s * 0.46
        a = math.radians(-90 + i * 36)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def _gear_pts(cx, cy, s):
    pts = []
    teeth = 8
    for i in range(teeth * 4):
        a = math.radians(i * 360 / (teeth * 4) - 90 + 360 / teeth / 8)
        r = s if (i % 4) in (1, 2) else s * 0.76
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def _pad_pts(cx, cy, s):
    return [(cx - s, cy - s * 0.25), (cx - s * 0.75, cy - s * 0.55), (cx + s * 0.75, cy - s * 0.55),
            (cx + s, cy - s * 0.25), (cx + s * 1.05, cy + s * 0.45), (cx + s * 0.75, cy + s * 0.62),
            (cx + s * 0.45, cy + s * 0.3), (cx - s * 0.45, cy + s * 0.3), (cx - s * 0.75, cy + s * 0.62),
            (cx - s * 1.05, cy + s * 0.45)]


INVADER = ["..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.", "XXXXXXXXXXX",
           "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX..."]


def invader(d, cx, cy, s, grow, fill):
    """g_expert's pixel invader with every pixel grown by [grow] px (shrunk when negative)."""
    px = s * 2 / 11
    x0, y0 = cx - px * 5.5, cy - px * 4
    for j, row in enumerate(INVADER):
        for i, ch in enumerate(row):
            if ch == "X":
                rrect(d, (x0 + i * px - grow, y0 + j * px - grow, x0 + (i + 1) * px + grow,
                          y0 + (j + 1) * px + grow), px * 0.22 + max(0, grow) * 0.4, fill=fill)


def duo_fill(d, key, cx, cy, s, fill, deep):
    """The aqua areas under menu glyph [key] (same geometry as make_mainui_theme's)."""
    if key == "recent":
        circle(d, cx, cy, s * 0.86, fill=fill)
    elif key == "favorite":
        poly(d, _star_pts(cx, cy, s), fill=fill)
    elif key == "game":
        poly(d, _pad_pts(cx, cy, s), fill=fill)
    elif key == "emulators":
        rrect(d, (cx - s * 0.62, cy - s, cx + s * 0.62, cy + s), s * 0.18, fill=fill)
        rrect(d, (cx - s * 0.42, cy - s * 0.78, cx + s * 0.42, cy - s * 0.08), s * 0.06, fill=deep)
    elif key == "app":
        h = s * 0.36
        for dx, dy in ((-1, -1), (1, 1), (-1, 1)):
            x, y = cx + dx * s * 0.52, cy + dy * s * 0.52
            rrect(d, (x - h, y - h, x + h, y + h), h * 0.35, fill=fill)
        circle(d, cx + s * 0.52, cy - s * 0.52, h, fill=fill)
    elif key == "setting":
        poly(d, _gear_pts(cx, cy, s), fill=fill)
        circle(d, cx, cy, s * 0.3, fill=deep)
    elif key == "retroarch":    # g_expert's pixels grown until they merge into one body
        invader(d, cx, cy, s, 1.0, fill)


# ---- the theme ----------------------------------------------------------------------------

class Theme(Style):
    name = "Abyss"
    author = "MainUI"
    description = ("Deep ocean: a sunlit turquoise surface sinking into the navy abyss, "
                   "with porthole capsules and rising bubbles.")

    bg_top, bg_bottom = SURF, NAVY
    accent, accent2, highlight = AQUA, AQUA_DEEP, AQUA
    text, muted = FOAM, MUTED
    glyph_n, glyph_f = GLYPH_N, FOAM
    panel, panel_alpha = PANEL, 246

    font_file = "Kanit-SemiBold.ttf"
    font_license = "OFL-Kanit.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 28, 26, 24, 23, 17, 18
    icon_quant = 96

    # tile geometry (156 x 278): a capsule with a porthole where the picture goes
    CAP = (7, 18, 149, 266)
    PORT = (78, 96)
    GLASS_R, RING_R = 53, 61

    # ---- big pictures ---------------------------------------------------------------------
    def water(self, w, h):
        return vgrad(w, h, [(0.0, rgba(SURF)), (0.11, (10, 86, 112, 255)), (0.25, (5, 52, 84, 255)),
                            (0.5, (4, 34, 66, 255)), (0.76, (3, 18, 44, 255)), (1.0, rgba(NAVY))])

    def background(self, w=W, h=H):
        im = self.water(w, h)
        rnd = random.Random(7)

        def fade(stops):
            return vgrad(1, h, [(t, (0, 0, 0, a)) for t, a in stops]).getchannel("A").resize((w, h))

        def light(mask, color, pct):
            return Image.alpha_composite(im, solid((w, h), color, mask.point(lambda v: v * pct // 100)))

        # the sun above the surface, top right
        sun = Image.new("L", (w, h), 0)
        ImageDraw.Draw(sun).ellipse((w * 0.32, -h * 0.5, w * 1.02, h * 0.3), fill=255)
        im = light(sun.filter(ImageFilter.GaussianBlur(60)), (120, 235, 236), 24)

        # the underside of the surface: bright, squashed ripples along the top
        top = caustic_light(w, 80, 30, t0=31.0, sy=2.4, sharp=6)
        rip = Image.new("L", (w, h), 0)
        rip.paste(top.filter(ImageFilter.GaussianBlur(0.8)), (0, 0))
        im = light(ImageChops.multiply(rip, fade([(0.0, 255), (0.08, 140), (0.165, 0), (1.0, 0)])), (220, 255, 250), 55)

        # light rays fanning down from the sun, fading with depth
        rays = Image.new("L", (w, h), 0)
        rd = ImageDraw.Draw(rays)
        sx, sy = w * 0.64, -h * 0.55
        for i in range(11):
            a = math.radians(90 - 34 + i * 6.4 + rnd.uniform(-2.2, 2.2))
            spread = math.radians(rnd.uniform(0.7, 1.9))
            far = h * 2
            p1 = (sx + far * math.cos(a - spread), sy + far * math.sin(a - spread))
            p2 = (sx + far * math.cos(a + spread), sy + far * math.sin(a + spread))
            rd.polygon([(sx, sy), p1, p2], fill=rnd.randint(70, 150))
        rays = ImageChops.multiply(rays.filter(ImageFilter.GaussianBlur(6)),
                                   fade([(0.0, 255), (0.25, 170), (0.55, 50), (0.78, 0), (1.0, 0)]))
        im = light(rays, (200, 255, 248), 34)

        # caustic streaks in patches below the surface
        web = caustic_light(w, h, 38, t0=40.0, sharp=7, rows_used=int(h * 0.56))
        patches = Image.new("L", (7, 6))
        patches.putdata([rnd.choice((0, 0, 90, 200, 255)) for _ in range(42)])
        patches = patches.resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(30))
        web = ImageChops.multiply(ImageChops.multiply(web, patches),
                                  fade([(0.0, 200), (0.13, 170), (0.27, 190), (0.42, 80), (0.56, 0), (1.0, 0)]))
        im = light(web, (170, 250, 240), 34)

        # kelp at both edges: a far, hazy layer and a near, dark one
        for layer, (col, blur) in enumerate((((14, 78, 100, 120), 2.4), ((1, 16, 30, 225), 0.0))):
            k = canvas(w, h)
            kd = ImageDraw.Draw(k)
            fronds = [(-4, 330), (26, 268), (54, 200)] if layer == 0 else [(6, 260), (36, 196)]
            for j, (x, ht) in enumerate(fronds):
                kelp(kd, x, h + 6, ht, 9 + 4 * j, j * 1.7 + layer, col, 3.2 - layer * 0.5, 100 + j + 10 * layer, 1)
                kelp(kd, w - x, h + 6, ht - 14 * (j + 1), 9 + 3 * j, j * 2.3 + 1 + layer, col, 3.2 - layer * 0.5,
                     200 + j + 10 * layer, -1)
            k = finish(k)
            if blur:
                k = k.filter(ImageFilter.GaussianBlur(blur))
            im = Image.alpha_composite(im, k)

        # marine snow, and rising bubbles kept to the margins: list text, the favourite mark,
        # toggles and arrows cover x 14-618 between the bars, the page dots sit at y 372-420
        def clear(x, y, r=0):
            dots = 150 - r < x < 490 + r and 372 - r < y < 420 + r
            rows = 14 - r < x < 618 + r and 56 < y < 424
            return not (dots or rows)

        b = canvas(w, h)
        bd = ImageDraw.Draw(b)
        for _ in range(80):
            x, y = rnd.uniform(0, w), rnd.uniform(h * 0.2, h)
            if not (150 < x < 490 and 372 < y < 420):
                circle(bd, x, y, rnd.uniform(0.5, 1.0), fill=(200, 240, 250, rnd.randint(20, 60)))
        for sx_, sy_, n, amp in ((6, 470, 10, 4), (630, 470, 9, 2.5), (627, 300, 5, 2)):
            y = sy_
            for i in range(n):
                r = min(4.8, 1.3 + i * 0.45 + rnd.uniform(-0.3, 0.4))
                x = sx_ + amp * math.sin(i * 1.3 + sx_) + rnd.uniform(-1, 1)
                bubble(bd, x, y, r, 0.5 + 0.45 * (1 - y / h))
                y -= 13 + i * 5 + rnd.uniform(0, 8)
        n = 0
        while n < 12:
            x, y, r = rnd.uniform(4, w - 4), rnd.uniform(62, h - 62), rnd.uniform(1.2, 3.0)
            if clear(x, y, r + 2) and 0 < x - r and x + r < w:
                bubble(bd, x, y, r, rnd.uniform(0.35, 0.7))
                n += 1
        for x, y, r in ((4, 300, 10), (629, 228, 7), (628, 384, 9)):
            bubble(bd, x, y, r, 0.85)
        im = Image.alpha_composite(im, finish(b))
        # vignette
        v = Image.new("L", (w, h), 0)
        ImageDraw.Draw(v).ellipse((-w * 0.25, -h * 0.2, w * 1.25, h * 1.3), fill=255)
        v = ImageChops.invert(v.filter(ImageFilter.GaussianBlur(90)))
        im = Image.alpha_composite(im, solid((w, h), (0, 4, 14), v.point(lambda x: x * 55 // 100)))
        return im.convert("RGB").convert("RGBA")

    def header_bar(self):
        w, h = 640, 60
        wave_y = [54 + 1.6 * math.sin(x / 21.0) + 0.9 * math.sin(x / 7.3 + 1.1) for x in range(w + 1)]
        body = vgrad(w, h, [(0.0, (0, 30, 50, 170)), (1.0, (0, 38, 62, 150))])
        m = canvas(w, h)
        poly(ImageDraw.Draw(m), [(0, 0), (w, 0)] + [(x, wave_y[x]) for x in range(w, -1, -4)], fill=(255, 255, 255))
        im = masked(body, finish(m).getchannel("A"))
        sh = canvas(w, h)   # shimmer just under the waterline
        d = ImageDraw.Draw(sh)
        line(d, [(x, wave_y[x]) for x in range(0, w + 1, 2)], rgba(AQUA, 230), 1.5)
        line(d, [(x, wave_y[x] - 3.5) for x in range(0, w + 1, 2)], rgba(FOAM, 40), 1)
        return Image.alpha_composite(im, glow(finish(sh), rgba(AQUA, 140), 2))

    def footer_bar(self):
        w, h = 640, 60
        im = vgrad(w, h, [(0.0, (1, 12, 28, 214)), (1.0, (1, 6, 18, 240))])
        e = canvas(w, h)
        d = ImageDraw.Draw(e)
        for x in range(0, w, 2):
            a = int(150 * math.sin(math.pi * x / w) ** 0.6)
            line(d, [(x, 1), (x + 2, 1)], rgba(AQUA, a), 1.2)
        return Image.alpha_composite(im, finish(e))

    def logo(self):
        w, h = 160, 42
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        cx, cy, r = 20, 21, 15
        circle(d, cx, cy, r + 1.5, fill=(2, 20, 38, 200))
        circle(d, cx, cy, r, fill=rgba(TEAL))
        d.pieslice([S(cx - r), S(cy - r), S(cx + r), S(cy + r)], 0, 180, fill=rgba(AQUA_DEEP))
        line(d, [(cx - r + 1 + i, cy + 1.6 * math.sin(i / 3.2)) for i in range(0, int(2 * r - 1))], rgba(AQUA), 2)
        circle(d, cx, cy, r, rgba(AQUA), 2.6)
        for k in range(4):
            a = math.radians(45 + 90 * k)
            circle(d, cx + (r + 0.2) * math.cos(a), cy + (r + 0.2) * math.sin(a), 1.4, fill=rgba(FOAM))
        bubble(d, cx + 4, cy - 6, 2.6)
        bubble(d, cx - 3, cy - 10, 1.6)
        f = self.font(24)
        x = 42
        for ch in "ABYSS":
            d.text((S(x), S(22)), ch, font=f, fill=rgba(FOAM), anchor="lm")
            x += d.textlength(ch, font=f) / SS + 2.2
        return finish(im)

    # ---- lists --------------------------------------------------------------------------
    def selection(self, h):
        """A glass capsule filled with water: a wavy waterline near the top, bubbles in its right
        end (MainUI keeps the 24 px ends unstretched)."""
        w = 640
        r = min(20, (h - 10) / 2)
        box = (4, 5, w - 4, h - 5)
        m = mask_rrect(w, h, box, r)
        out = solid((w, h), (2, 18, 38, 185), m)
        wave_y = [11 + 1.6 * math.sin(x / 17.0) for x in range(w + 1)]
        water = canvas(w, h)
        poly(ImageDraw.Draw(water), [(x, wave_y[x]) for x in range(0, w + 1, 2)] + [(w, h), (0, h)],
             fill=(255, 255, 255, 255))
        water_m = ImageChops.multiply(m, finish(water).getchannel("A"))
        fill = vgrad(w, h, [(0.0, rgba(AQUA, 120)), (1.0, rgba(AQUA_DEEP, 150))])
        side = Image.new("L", (w, 1))
        for x in range(w):
            side.putpixel((x, 0), int(255 * (1 - 0.3 * x / w)))
        out.alpha_composite(masked(fill, ImageChops.multiply(water_m, side.resize((w, h)))))
        e = canvas(w, h)
        d = ImageDraw.Draw(e)
        line(d, [(x, wave_y[x] + 2.2) for x in range(0, w + 1, 2)], rgba(AQUA, 70), 3)
        line(d, [(x, wave_y[x]) for x in range(0, w + 1, 2)], rgba(FOAM, 190), 1.3)
        for x, y, rb in ((624, h * 0.68, 2.6), (629.5, h * 0.5, 1.9), (624.5, h * 0.35, 1.4)):
            circle(d, x, y, rb, fill=rgba(AQUA, 50))
            circle(d, x, y, rb, rgba(FOAM, 190), 0.9)
            circle(d, x - rb * 0.38, y - rb * 0.38, rb * 0.32, fill=rgba(FOAM, 230))
        out = Image.alpha_composite(out, masked(finish(e), m))
        e = canvas(w, h)
        rrect(ImageDraw.Draw(e), box, r, rgba(AQUA, 235), 1.5)
        return Image.alpha_composite(out, finish(e))

    def divider(self):
        im = canvas(640, 4)
        d = ImageDraw.Draw(im)
        for x in range(26, 616, 7):
            a = int(70 * math.sin(math.pi * (x - 26) / 590) ** 0.5)
            circle(d, x, 2, 0.9, fill=rgba(MUTED, a))
        return finish(im)

    # ---- tiles --------------------------------------------------------------------------
    def capsule(self, w, h, box, r, focused, halo=5.0):
        """Glass capsule body: deep fill, glass crescent, aqua rim; focused it is lit and glows,
        outwards by [halo] px, or (halo 0, for pieces laid edge to edge) only inside its rim."""
        m = mask_rrect(w, h, box, r)
        out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        if focused:
            if halo:
                glow_ = solid((w, h), rgba(AQUA), m).filter(ImageFilter.GaussianBlur(halo))
                out = Image.alpha_composite(out, scale_alpha(glow_, 0.85))
            fill = vgrad(w, h, [(0.0, (22, 150, 166, 240)), (0.5, (8, 80, 110, 242)), (1.0, (4, 40, 70, 246))])
        else:
            fill = vgrad(w, h, [(0.0, (14, 64, 94, 140)), (0.5, (6, 34, 62, 165)), (1.0, (3, 18, 40, 185))])
        out.alpha_composite(masked(fill, m))
        if focused and not halo:
            out.alpha_composite(self.inner_glow(w, h, box, r, m, 3, 2.5, 150))
        hi = crescent(w, h, box, r, 4, 5).filter(ImageFilter.GaussianBlur(0.8))
        out.alpha_composite(solid((w, h), (220, 255, 255, 110 if focused else 60), hi))
        e = canvas(w, h)
        if focused:
            rrect(ImageDraw.Draw(e), box, r, rgba(AQUA), 2.4)
        else:
            rrect(ImageDraw.Draw(e), box, r, (130, 220, 232, 85), 1.3)
        return Image.alpha_composite(out, finish(e))

    @staticmethod
    def inner_glow(w, h, box, r, m, width, blur, alpha):
        """Aqua light spilling inwards from the rim of [box], clipped to mask [m]."""
        g = canvas(w, h)
        rrect(ImageDraw.Draw(g), box, r, rgba(AQUA), width)
        g = finish(g).getchannel("A").filter(ImageFilter.GaussianBlur(blur))
        return solid((w, h), rgba(AQUA, alpha), ImageChops.multiply(g, m))

    def porthole(self, im, cx, cy, focused):
        """A riveted ring with dark glass inside, on [im] (156 x 278)."""
        w, h = im.size
        gr, rr = self.GLASS_R, self.RING_R
        ring_m = ImageChops.subtract(circle_mask(w, h, cx, cy, rr), circle_mask(w, h, cx, cy, gr))
        top, bot = ((150, 245, 240, 255), (14, 120, 140, 255)) if focused else ((70, 130, 152, 200), (16, 50, 74, 210))
        im.alpha_composite(masked(vgrad(w, h, [(0.0, top), (0.25, top), (0.55, bot), (1.0, bot)]), ring_m))
        inner = (12, 96, 122, 245) if focused else (8, 44, 72, 225)
        edge = (3, 30, 54, 250) if focused else (2, 14, 34, 235)
        im.alpha_composite(masked(radial_fill(w, h, cx, cy + 18, gr + 16, inner, edge), circle_mask(w, h, cx, cy, gr)))
        g = canvas(w, h)
        d = ImageDraw.Draw(g)
        circle(d, cx, cy, gr, (1, 10, 26, 200), 1.6)
        circle(d, cx, cy, rr, (2, 16, 34, 160), 1.2)
        for k in range(8):
            a = math.radians(22.5 + 45 * k)
            rx, ry = cx + (gr + rr) / 2 * math.cos(a), cy + (gr + rr) / 2 * math.sin(a)
            circle(d, rx, ry, 1.9, fill=(2, 22, 40, 200) if focused else (2, 14, 30, 200))
            circle(d, rx - 0.4, ry - 0.4, 1.3, fill=rgba(FOAM, 230) if focused else (170, 220, 230, 140))
        arc(d, cx, cy, gr - 6, 200, 250, (220, 255, 255, 70 if focused else 40), 3)
        arc(d, cx, cy, gr - 6, 258, 266, (220, 255, 255, 60 if focused else 34), 3)
        im.alpha_composite(finish(g))
        return im

    def tile_base(self, focused):
        w, h = 156, 278
        im = self.capsule(w, h, self.CAP, 68, focused, halo=3.2)
        self.porthole(im, *self.PORT, focused)
        if focused:     # bubbles rising below the label (and the row view's game count at y 222)
            b = canvas(w, h)
            d = ImageDraw.Draw(b)
            for x, y, r in ((70, 256, 3.0), (82, 246, 3.8), (75, 238, 2.0)):
                bubble(d, x, y, r, 1.0)
            im.alpha_composite(finish(b))
        return im

    def tile_glyph(self, key, focused):
        w, h = 156, 278
        cx, cy = self.PORT
        s = 31
        # aqua fills, lit from above; the "deep" parts are cut out to the porthole glass
        f = canvas(w, h)
        duo_fill(ImageDraw.Draw(f), key, cx, cy, s, (255, 255, 255, 255), (0, 0, 0, 0))
        top, bot = (cy - s) / h, (cy + s) / h
        hi, lo = ((120, 252, 238, 245), (16, 156, 170, 245)) if focused else (rgba(AQUA, 125), (30, 160, 176, 125))
        g = masked(vgrad(w, h, [(0, hi), (top, hi), (bot, lo), (1, lo)]), finish(f).getchannel("A"))
        ln = canvas(w, h)
        ink, width = (rgba(FOAM), 4.6) if focused else (rgba(GLYPH_N, 235), 4.4)
        if key == "retroarch":     # the invader's pixels as lit dots on its aqua body
            invader(ImageDraw.Draw(ln), cx, cy, s, -0.9, ink)
        else:
            TILES[key](ImageDraw.Draw(ln), cx, cy, s, ink, width)
        g = Image.alpha_composite(g, finish(ln))
        return glow(g, rgba(AQUA, 170), 6) if focused else g

    # ---- cards --------------------------------------------------------------------------
    def card(self, w, h, focused):
        """Games grid cell: cells touch, so it glows only inwards; the rim runs straight past
        the name (centred at y 148, up to 147 px wide)."""
        box, r = (1.5, 2, w - 1.5, h - 2), 16
        im = self.capsule(w, h, box, r, focused, halo=0)
        cy = 8 + 62 * 124 / 130     # a pool of light behind the system picture
        light = radial_fill(w, h, w / 2, cy, 62, (60, 200, 210, 70 if focused else 34), (60, 200, 210, 0))
        im.alpha_composite(masked(light, mask_rrect(w, h, box, r)))
        return clear_border(im)

    def preview_frame(self):
        """A thin bezel: MainUI draws it only 6 px larger than the picture on each side."""
        w, h = 250, 360
        box = (1, 1, w - 1, h - 1)
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        rrect(d, box, 5, fill=(3, 18, 40, 235))
        rrect(d, box, 5, rgba(AQUA, 200), 2)
        rrect(d, (6, 6, w - 6, h - 6), 2, (2, 14, 30, 220), 1)
        return finish(im)

    def key(self, focused):
        w, h = 64, 52
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        box = (3, 3, w - 3, h - 3)
        m = mask_rrect(w, h, box, 14)
        if focused:
            im.alpha_composite(masked(vgrad(w, h, [(0, (18, 130, 148, 250)), (1, (6, 70, 98, 250))]), m))
            im.alpha_composite(self.inner_glow(w, h, box, 14, m, 3, 2.2, 150))
        else:
            im.alpha_composite(masked(vgrad(w, h, [(0, (12, 58, 88, 225)), (1, (4, 28, 54, 235))]), m))
        im.alpha_composite(solid((w, h), (220, 255, 255, 120 if focused else 50), crescent(w, h, box, 14, 0, 2)))
        e = canvas(w, h)
        rrect(ImageDraw.Draw(e), box, 14, rgba(AQUA) if focused else (130, 220, 232, 80), 2 if focused else 1.2)
        return clear_border(Image.alpha_composite(im, finish(e)))

    def input_box(self):
        w, h = 560, 60
        box = (2, 3, w - 2, h - 3)
        m = mask_rrect(w, h, box, 27)
        im = masked(vgrad(w, h, [(0, (3, 22, 44, 240)), (1, (6, 36, 62, 240))]), m)
        e = canvas(w, h)
        d = ImageDraw.Draw(e)
        rrect(d, box, 27, rgba(AQUA, 220), 2)
        return Image.alpha_composite(im, finish(e))

    # ---- buttons and small parts ----------------------------------------------------------
    def sphere(self, n, cx, cy, r, primary, hi=(0.74, 200, 250)):
        """A glass bubble button; [hi] = the glint arc's radius (x r) and angles."""
        im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        m = circle_mask(n, n, cx, cy, r)
        if primary:
            fill = radial_fill(n, n, cx - r * 0.35, cy - r * 0.4, r * 1.6, (150, 255, 246, 255), (14, 150, 160, 255))
        else:   # an air bubble: clear in the middle, lit towards its rim
            fill = radial_fill(n, n, cx + r * 0.1, cy + r * 0.12, r * 1.05, (3, 26, 48, 245), (26, 120, 142, 245))
        im.alpha_composite(masked(fill, m))
        g = canvas(n, n)
        d = ImageDraw.Draw(g)
        circle(d, cx, cy, r, rgba(FOAM, 170) if primary else rgba(FOAM, 150), 1.6 if primary else 1.4)
        if not primary:
            circle(d, cx, cy, r - 1.4, rgba(AQUA, 120), 1)
            arc(d, cx, cy, r * 0.8, 25, 65, rgba(FOAM, 90), r * 0.07)
        arc(d, cx, cy, r * hi[0], hi[1], hi[2], rgba(FOAM, 200 if primary else 170), r * 0.13)
        return Image.alpha_composite(im, finish(g))

    def button(self, label, primary):
        n = 54
        im = self.sphere(n, n / 2, n / 2, n / 2 - 4, primary)
        t = canvas(n, n)
        ImageDraw.Draw(t).text((S(n / 2), S(n / 2 + 1)), label, font=self.font(25),
                               fill=(2, 30, 46, 255) if primary else rgba(FOAM), anchor="mm")
        return Image.alpha_composite(im, finish(t))

    def pill(self, label, w, h=48):
        box = (2, 7, w - 2, h - 7)
        r = (h - 14) / 2
        m = mask_rrect(w, h, box, r)
        im = masked(vgrad(w, h, [(0, (6, 40, 64, 245)), (1, (3, 24, 46, 245))]), m)
        im.alpha_composite(self.inner_glow(w, h, box, r, m, 3, 2.5, 120))
        im.alpha_composite(solid((w, h), (220, 255, 255, 120), crescent(w, h, box, r, 0, 2.5)))
        t = canvas(w, h)
        d = ImageDraw.Draw(t)
        rrect(d, box, r, rgba(FOAM, 150), 1.4)
        d.text((S(w / 2), S(h / 2 + 1)), label, font=self.font(h * 0.36), fill=rgba(FOAM), anchor="mm")
        return Image.alpha_composite(im, finish(t))

    def dot(self, active):
        im = canvas(24, 14)
        d = ImageDraw.Draw(im)
        if active:
            rrect(d, (4, 4, 20, 10), 3, fill=rgba(AQUA))
            line(d, [(7, 5.6), (12, 5.6)], rgba(FOAM, 220), 1.1)
            return glow(finish(im), rgba(AQUA, 160), 1.5)
        circle(d, 12, 7, 4, fill=rgba(MUTED, 50))
        circle(d, 12, 7, 4, rgba(MUTED, 200), 1.3)
        circle(d, 10.8, 5.8, 1, fill=rgba(FOAM, 220))
        return finish(im)

    def toggle(self, on):
        w, h = 92, 44
        box = (6, 9, w - 6, h - 9)
        r = (h - 18) / 2
        m = mask_rrect(w, h, box, r)
        if on:
            im = masked(vgrad(w, h, [(0, rgba(AQUA)), (1, (14, 150, 160, 255))]), m)
        else:
            im = masked(vgrad(w, h, [(0, (4, 24, 46, 255)), (1, (8, 40, 64, 255))]), m)
        g = canvas(w, h)
        d = ImageDraw.Draw(g)
        rrect(d, box, r, rgba(FOAM, 160) if on else rgba(MUTED, 150), 1.4)
        if on:
            for x, y, rr_ in ((20, 22, 2.4), (30, 18, 1.6), (34, 26, 1.3)):
                circle(d, x, y, rr_, rgba(FOAM, 200), 1)
        im = Image.alpha_composite(im, finish(g))
        kx = w - 6 - r if on else 6 + r
        kc = canvas(w, h)
        kd = ImageDraw.Draw(kc)
        circle(kd, kx, h / 2 + 1, r + 2.5, fill=(0, 10, 24, 90))
        circle(kd, kx, h / 2, r + 2, fill=rgba(FOAM) if on else (120, 160, 176, 255))
        arc(kd, kx, h / 2, (r + 2) * 0.7, 200, 250, (255, 255, 255, 230) if not on else rgba(AQUA, 200), 1.6)
        circle(kd, kx, h / 2, r + 2, rgba(AQUA_DEEP, 200) if on else (40, 70, 90, 220), 1.2)
        return Image.alpha_composite(im, finish(kc))

    def arrow(self, left, size=24):
        im = canvas(size, size)
        d = ImageDraw.Draw(im)
        xs = (0.62, 0.34) if left else (0.38, 0.66)
        pts = [(size * xs[0], size * 0.2), (size * xs[1], size * 0.5), (size * xs[0], size * 0.8)]
        line(d, pts, rgba(FOAM), size * 0.12)
        return glow(finish(im), rgba(AQUA, 170), size / 12)

    def panel_image(self, w, h):
        r = 22
        b = canvas(w, h)
        d = ImageDraw.Draw(b)
        if w == 640:    # dialog: a floating riveted plate
            box = (28, 8, w - 28, h - 8)
            m = mask_rrect(w, h, box, r)
            im = masked(vgrad(w, h, [(0.0, (10, 50, 78, 254)), (0.3, (6, 32, 58, 254)), (1.0, (3, 16, 38, 254))]), m)
            im.alpha_composite(solid((w, h), (220, 255, 255, 70), crescent(w, h, box, r, 0, 3)))
            rrect(d, box, r, rgba(AQUA, 190), 1.6)
            for x, y in ((box[0] + 14, box[1] + 14), (box[2] - 14, box[1] + 14),
                         (box[0] + 14, box[3] - 14), (box[2] - 14, box[3] - 14)):
                circle(d, x, y, 2.4, fill=(2, 14, 30, 220))
                circle(d, x - 0.4, y - 0.4, 1.6, fill=(170, 220, 230, 170))
        else:
            # pop-up menu: MainUI draws a square title strip in darken(panel, 0.2) right above it,
            # so the top is square, unrimmed and starts in the strip's colour
            box = (0.5, -2 * r, w - 0.5, h - 2)
            m = mask_rrect(w, h, box, r)
            strip = tuple(int(c * 0.8) for c in PANEL)
            im = masked(vgrad(w, h, [(0.0, rgba(strip, 254)), (min(0.5, 40 / h), (6, 32, 58, 254)),
                                     (1.0, (3, 16, 38, 254))]), m)
            rrect(d, box, r, rgba(AQUA, 190), 1.6)
            line(d, [(0.5, 0.75), (w - 0.5, 0.75)], rgba(AQUA, 120), 1.5)
            if h != 250:    # bg-pop-menu-4 is stretched for longer menus: no round bubbles there
                rnd = random.Random(w * 7 + h)
                for i in range(5):
                    bubble(d, w - 22 - rnd.uniform(0, 26), h - 14 - i * 9 - rnd.uniform(0, 4), 1.2 + i * 0.5, 0.45)
        return Image.alpha_composite(im, finish(b))

    def folder_icon(self):
        im = canvas(60, 60)
        d = ImageDraw.Draw(im)
        poly(d, [(9, 47), (9, 14), (23, 14), (27, 19), (51, 19), (51, 47)], fill=rgba(AQUA))
        poly(d, [(8, 48), (13, 26), (55, 26), (50, 48)], fill=rgba(FOAM))
        line(d, [(8, 48), (13, 26), (55, 26), (50, 48), (8, 48)], (2, 40, 60, 255), 1.4)
        return finish(im)

    def favorite_mark(self):
        w, h = 34, 39
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        cx, cy = 17, 20.5
        pts = []
        for i in range(60):
            a = math.radians(-90 + i * 6)
            k = (math.cos(5 * (a + math.pi / 2)) + 1) / 2
            rad = 6 + 9.5 * k ** 1.6
            pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
        poly(d, pts, fill=(80, 30, 20, 200))
        poly(d, [(x, y - 0.8) for x, y in pts], fill=rgba(SAND))
        for i in range(5):
            a = math.radians(-90 + 72 * i)
            for t in (4.5, 8.5):
                circle(d, cx + t * math.cos(a), cy - 0.8 + t * math.sin(a), 1.1, fill=(255, 236, 200, 255))
        circle(d, cx, cy - 0.8, 1.6, fill=(255, 140, 80, 255))
        return finish(im)

    def battery(self, level, charging=False):
        im = canvas(48, 48)
        d = ImageDraw.Draw(im)
        rrect(d, (5, 15, 41, 33), 9, (2, 14, 30, 120), 4)
        if level > 0:
            col = rgba(CORAL) if level <= 0.2 else rgba(AQUA)
            x1 = 9 + max(3.0, 28 * level)
            fill = canvas(48, 48)
            fd = ImageDraw.Draw(fill)
            pts = [(9, 18.5)] + [(x1 + 0.9 * math.sin(2 * math.pi * i / 23), 18.5 + i * 11 / 23)
                                 for i in range(24)] + [(9, 29.5)]
            poly(fd, pts, fill=col)
            m = Image.new("L", (48 * SS, 48 * SS), 0)
            ImageDraw.Draw(m).rounded_rectangle([S(9), S(18.5), S(37), S(29.5)], radius=S(5.5), fill=255)
            fill.putalpha(ImageChops.multiply(fill.getchannel("A"), m))
            im.alpha_composite(fill)
        rrect(d, (5, 15, 41, 33), 9, rgba(FOAM, 240), 2.2)
        rrect(d, (41, 20.5, 45, 27.5), 1.8, fill=rgba(FOAM, 240))
        if charging:
            bolt = [(26, 10), (17, 26), (23, 26), (20, 38), (31, 21), (25, 21), (28, 10)]
            poly(d, [(x + 0.6, y + 0.8) for x, y in bolt], fill=(2, 18, 34, 220))
            poly(d, bolt, fill=rgba(SAND))
        return finish(im)

    # ---- system and app icons -------------------------------------------------------------
    def icon_picture(self, sid, selected, cy):
        im = canvas(120, 130)
        pen = Pen(ImageDraw.Draw(im), 60, cy, rgba(FOAM, 250), SS, w=3.5)
        pen.soft_c = rgba(AQUA, 165 if not selected else 200)
        (GLYPHS.get(sid) or GLYPHS["fallback"])(pen)
        out = duo_body(finish(im), (44, 196, 204, 112))
        return glow(out, rgba(AQUA, 120 if selected else 80), 4)

    def system_icon(self, sid, selected):
        return self.icon_picture(sid, selected, 64)

    def app_icon(self, key):
        n = 96
        im = self.sphere(n, n / 2, n / 2, n / 2 - 5, False, hi=(0.86, 205, 240))
        g = canvas(n, n)
        APPS[key](ImageDraw.Draw(g), n / 2, n / 2 + 1, n * 0.25, rgba(FOAM), n * 0.05)
        g = duo_body(finish(g), (60, 214, 214, 150))
        return Image.alpha_composite(im, glow(g, rgba(AQUA, 150), 3))

    # ---- sound ----------------------------------------------------------------------------
    def sound(self, path):
        """A short bubble 'bloop': a sine whose pitch rises as it fades."""
        rate = 22050
        n = int(rate * 0.06)
        frames = bytearray()
        ph = 0.0
        for i in range(n):
            t = i / rate
            f = 520 + 1100 * (t / 0.06) ** 0.8
            ph += 2 * math.pi * f / rate
            env = min(1.0, t / 0.003, (n - 1 - i) / 66) * math.exp(-t * 60)
            frames += struct.pack("<h", int(8500 * env * math.sin(ph)))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))

    # ---- config ---------------------------------------------------------------------------
    def config(self):
        cfg = super().config()
        cfg["mainui"]["accent"] = "#088090"   # MainUI writes white on it: keep it deep enough
        return cfg
