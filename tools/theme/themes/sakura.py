"""Sakura: a light spring theme. A pink-to-cream sky with drifting cherry petals, white rounded
cards edged in pink, plum ink and rose duotone drawings."""
import math
import random
import struct
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from theme_kit import (APPS, GLYPHS, SS, TILES, Pen, S, Style, canvas, circle, finish, g_folder, line, poly, rgba,
                       rrect)

PLUM = (91, 42, 69)        # text
INK = (112, 46, 84)        # line art
MUTED = (135, 84, 112)     # unselected labels
PINK = (244, 167, 185)     # card edges
ROSE = (234, 128, 160)
DEEP = (214, 92, 133)
RASP = (194, 69, 111)      # accent: strokes, current page
WHITE = (255, 255, 255)
BARK = (104, 58, 74)
GOLD = (232, 176, 84)
SHADE = (150, 60, 100)     # shadows


# ---- small image helpers ------------------------------------------------------------------

def place(dst, src, x, y):
    """alpha_composite [src] at (x, y), which may be partly outside [dst]."""
    layer = Image.new("RGBA", dst.size, (0, 0, 0, 0))
    layer.paste(src, (int(round(x)), int(round(y))))
    dst.alpha_composite(layer)


def place_center(dst, src, cx, cy):
    place(dst, src, cx - src.width / 2, cy - src.height / 2)


def lgrad(w, h, c0, c1, angle=90.0):
    """Linear RGBA gradient from c0 to c1; angle 90 = top to bottom, 0 = left to right."""
    a = math.radians(angle)
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    p = xs * math.cos(a) + ys * math.sin(a)
    t = (p - p.min()) / max(1e-6, p.max() - p.min())
    c0, c1 = np.array(rgba(c0) if len(c0) == 3 else c0, np.float32), np.array(rgba(c1) if len(c1) == 3 else c1, np.float32)
    arr = c0 + (c1 - c0) * t[..., None]
    return Image.fromarray(np.clip(arr + 0.5, 0, 255).astype(np.uint8), "RGBA")


def rgrad(w, h, cx, cy, r, stops):
    """Radial RGBA gradient; stops = [(t, rgba), ...] with t in 0..1 of [r]."""
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    t = np.clip(np.hypot(xs - cx, ys - cy) / r, 0, 1)
    out = np.zeros((h, w, 4), np.float32)
    ts = [s[0] for s in stops]
    cs = [np.array(s[1], np.float32) for s in stops]
    for ch in range(4):
        out[..., ch] = np.interp(t, ts, [c[ch] for c in cs])
    return Image.fromarray(np.clip(out + 0.5, 0, 255).astype(np.uint8), "RGBA")


def with_mask(fill, mask):
    """[fill] (RGBA) shown through [mask] (L), its own alpha kept."""
    out = fill.copy()
    a = np.asarray(fill.getchannel("A"), np.float32) * np.asarray(mask, np.float32) / 255
    out.putalpha(Image.fromarray(a.astype(np.uint8), "L"))
    return out


def fade(im, k):
    """[im] with its alpha scaled by k."""
    out = im.copy()
    out.putalpha(im.getchannel("A").point(lambda v: int(v * k)))
    return out


def _solid(size, c, a):
    out = Image.new("RGBA", size, rgba(c[:3]))
    out.putalpha(a)
    return out


def soft_shadow(shape, color=SHADE, alpha=60, blur=5, dy=3):
    """A blurred tinted copy of [shape]'s alpha, moved down by dy."""
    a = shape.getchannel("A").point(lambda v: v * alpha // 255)
    sh = _solid(shape.size, color, a).filter(ImageFilter.GaussianBlur(blur))
    out = Image.new("RGBA", shape.size, (0, 0, 0, 0))
    place(out, sh, 0, dy)
    return out


def rr_mask(w, h, box, r):
    """Anti-aliased rounded rectangle mask (1x)."""
    m = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle([v * SS for v in box], radius=r * SS, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def silhouette(alpha, grow=2):
    """[alpha]'s shape with its holes filled (a sticker body behind line art), as L."""
    m = alpha.point(lambda v: 255 if v > 70 else 0)
    if grow:  # round dilation
        m = m.filter(ImageFilter.GaussianBlur(grow * 0.75)).point(lambda v: 255 if v > 28 else 0)
    pad = Image.new("L", (m.width + 2, m.height + 2), 0)
    pad.paste(m, (1, 1))
    ImageDraw.floodfill(pad, (0, 0), 128)
    filled = pad.point(lambda v: 0 if v == 128 else 255).crop((1, 1, m.width + 1, m.height + 1))
    return filled.filter(ImageFilter.GaussianBlur(0.7))


# ---- cherry blossoms ----------------------------------------------------------------------

def petal_pts(bx, by, length, angle, width=0.7, notch=0.11, m=0.9, n=60):
    """A cherry petal outline: base at (bx, by), its notched round tip [length] away at [angle]
    degrees (0 = up, clockwise). [m] sharpens the base."""
    a = math.radians(angle)
    dx, dy = math.sin(a), -math.cos(a)        # along the petal
    px, py = math.cos(a), math.sin(a)         # across it
    peak = max(math.sin(t) * math.sin(t / 2) ** m for t in (i * math.pi / 90 for i in range(91)))
    k = width / 2 / peak
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        u = (1 - math.cos(t)) / 2 - notch * math.exp(-((t - math.pi) / 0.33) ** 2)
        v = math.sin(t) * abs(math.sin(t / 2)) ** m * k
        pts.append((bx + (u * dx + v * px) * length, by + (u * dy + v * py) * length))
    return pts


def blossom_img(r, rot=0.0, tip=(255, 246, 249, 255), mid=(250, 200, 214, 255), heart=(233, 120, 155, 255),
                edge=(226, 120, 152, 150), stamens=True, center=True):
    """A five-petal cherry blossom of radius [r] (1x), radially shaded, centred in its image."""
    s = int(math.ceil(r * 2.2)) + 4
    c = s / 2
    m = Image.new("L", (s * SS, s * SS), 0)
    md = ImageDraw.Draw(m)
    polys = []
    for k in range(5):
        ang = rot + k * 72
        bx = c + math.sin(math.radians(ang)) * r * 0.06
        by = c - math.cos(math.radians(ang)) * r * 0.06
        pts = [(x * SS, y * SS) for x, y in petal_pts(bx, by, r, ang)]
        polys.append(pts)
        md.polygon(pts, fill=255)
    fill = rgrad(s * SS, s * SS, c * SS, c * SS, r * SS, [(0, heart), (0.42, mid), (1, tip)])
    im = with_mask(fill, m)
    d = ImageDraw.Draw(im)
    if edge:
        for pts in polys:
            d.line(pts + [pts[0]], fill=edge, width=max(1, int(SS * max(0.6, r / 22))))
    if center:
        cr = r * 0.16
        d.ellipse([(c - cr) * SS, (c - cr) * SS, (c + cr) * SS, (c + cr) * SS], fill=heart)
    if stamens and r >= 6:
        for k in range(10):
            ang = math.radians(rot + 18 + k * 36)
            rr_ = r * (0.34 if k % 2 else 0.42)
            x1, y1 = c + math.sin(ang) * rr_, c - math.cos(ang) * rr_
            d.line([(c * SS, c * SS), (x1 * SS, y1 * SS)], fill=(196, 80, 120, 230), width=max(1, int(SS * r / 26)))
            dr = max(0.6, r * 0.05)
            d.ellipse([(x1 - dr) * SS, (y1 - dr) * SS, (x1 + dr) * SS, (y1 + dr) * SS], fill=rgba(GOLD, 255))
    return finish(im)


def petal_img(length, rot, base=(240, 150, 178, 255), tip=(255, 236, 242, 255), edge=None):
    """A single loose petal, centred on its middle, [rot] degrees from upright."""
    s = int(math.ceil(length * 1.3)) + 4
    c = s / 2
    a = math.radians(rot)
    bx, by = c - math.sin(a) * length / 2, c + math.cos(a) * length / 2
    pts = [(x * SS, y * SS) for x, y in petal_pts(bx, by, length, rot, width=0.62, notch=0.09, m=0.7)]
    m = Image.new("L", (s * SS, s * SS), 0)
    ImageDraw.Draw(m).polygon(pts, fill=255)
    fill = rgrad(s * SS, s * SS, bx * SS, by * SS, length * SS, [(0, base), (1, tip)])
    im = with_mask(fill, m)
    if edge:
        ImageDraw.Draw(im).line(pts + [pts[0]], fill=edge, width=SS)
    # a crease down the middle
    ImageDraw.Draw(im).line([(bx * SS, by * SS), ((bx + math.sin(a) * length * 0.55) * SS, (by - math.cos(a) * length * 0.55) * SS)],
                            fill=base[:3] + (90,), width=max(1, int(SS * length / 16)))
    return finish(im)


def grow_limb(d, rnd, x, y, ang, length, w0, w1, depth, tips, color, step=4.0):
    """A kinked, tapering cherry limb stamped as discs on a supersampled ImageDraw (1x
    coordinates), with side twigs; collects the twig ends and a few nodes in [tips]."""
    n = max(2, int(length / step))
    pts, angs = [(x, y)], [ang]
    ang0 = ang
    for _ in range(n):
        ang += rnd.uniform(-20, 20) if rnd.random() < 0.16 else rnd.uniform(-3, 3)
        ang += (ang0 - ang) * 0.12    # kinks, but it keeps its heading
        x += math.cos(math.radians(ang)) * step
        y += math.sin(math.radians(ang)) * step
        pts.append((x, y))
        angs.append(ang)
    for i in range(n):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        for k in range(3):
            t = (i + k / 3) / n
            r = (w0 + (w1 - w0) * t) / 2
            cx, cy = x0 + (x1 - x0) * k / 3, y0 + (y1 - y0) * k / 3
            d.ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=color)
    tips.append(pts[-1])
    if depth > 0:
        for _ in range(rnd.randint(2, 3)):
            i = rnd.randint(n // 5, n - 3)
            t = i / n
            side = rnd.choice((-1, 1))
            grow_limb(d, rnd, *pts[i], angs[i] + side * rnd.uniform(28, 55), length * rnd.uniform(0.28, 0.45),
                      max(1.4, (w0 + (w1 - w0) * t) * 0.62), 1.2, depth - 1, tips, color, step)
            if rnd.random() < 0.5:
                tips.append(pts[i])


def blossom_lines(w, h, cx, cy, R, col, lw):
    """Line art of a blossom (its outline, stamens and centre) as a w x h layer, lines [lw] wide."""
    m = Image.new("L", (w * SS, h * SS), 0)
    md = ImageDraw.Draw(m)
    for k in range(5):
        a = math.radians(k * 72)
        pts = petal_pts(cx + math.sin(a) * R * 0.05, cy - math.cos(a) * R * 0.05, R, k * 72, width=0.74, notch=0.12)
        md.polygon([(x * SS, y * SS) for x, y in pts], fill=255)
    # outline = (union grown by lw/2) minus (union shrunk by lw/2), via blur and two thresholds
    b = m.filter(ImageFilter.GaussianBlur(lw * SS / 2.38))
    ring = np.asarray(b.point(lambda v: 255 if v > 30 else 0), np.int16) - np.asarray(b.point(lambda v: 255 if v > 225 else 0), np.int16)
    im = Image.new("RGBA", m.size, rgba(col[:3], 0))
    im.paste(Image.new("RGBA", m.size, col), (0, 0), Image.fromarray(ring.clip(0, 255).astype(np.uint8)))
    d = ImageDraw.Draw(im)
    d.ellipse([S(cx - 3.6), S(cy - 3.6), S(cx + 3.6), S(cy + 3.6)], fill=col)
    for k in range(5):
        a = math.radians(k * 72)
        x1, y1 = cx + math.sin(a) * R * 0.42, cy - math.cos(a) * R * 0.42
        d.line([S(cx), S(cy), S(x1), S(y1)], fill=col, width=int(lw * 0.42 * SS))
        d.ellipse([S(x1 - 2.4), S(y1 - 2.4), S(x1 + 2.4), S(y1 + 2.4)], fill=col)
    return finish(im)


def bud_img(length, rot):
    """A closed bud: a deep pink drop on a short stem."""
    s = int(length * 2.4) + 4
    im = canvas(s, s)
    d = ImageDraw.Draw(im)
    c = s / 2
    a = math.radians(rot)
    ux, uy = math.sin(a), -math.cos(a)
    line(d, [(c - ux * length * 0.9, c - uy * length * 0.9), (c, c)], (150, 86, 104, 200), max(0.8, length * 0.12))
    pts = petal_pts(c, c, length, rot, width=0.62, notch=0.0, m=1.4)
    d.polygon([(x * SS, y * SS) for x, y in pts], fill=(232, 112, 150, 255))
    pts = petal_pts(c, c, length * 0.8, rot, width=0.4, notch=0.0, m=1.4)
    d.polygon([(x * SS, y * SS) for x, y in pts], fill=(246, 160, 186, 255))
    return finish(im)


# ---- the theme ------------------------------------------------------------------------------

class Theme(Style):
    name = "Sakura"
    author = "MainUI"
    description = "Spring in pastel pink: drifting cherry petals, soft white cards edged in pink and plum ink."

    # (the builders below draw everything; these feed config.json and what MainUI draws itself)
    bg_bottom = (252, 232, 238)   # light, so MainUI's own fallbacks pick dark ink
    accent = RASP                 # toast edges, progress bars, fallbacks with white text
    highlight = RASP              # current page number
    text = PLUM
    muted = MUTED
    panel = (252, 214, 226)       # toasts; MainUI darkens it for pop-up titles
    panel_alpha = 246

    font_file = "VarelaRound-Regular.ttf"
    font_license = "OFL-VarelaRound.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 28, 27, 24, 23, 15, 18
    icon_quant = 64
    sel_icons = True    # white line art on the focused (pink) cells, like the menu tiles

    # ---- background --------------------------------------------------------------------
    def background(self, w=640, h=480):
        rnd = random.Random(2024)
        bg = lgrad(w, h, (250, 206, 221), (255, 247, 236), angle=62)
        bg.alpha_composite(rgrad(w, h, w * 0.58, h * 0.36, 320, [(0, (255, 255, 255, 130)), (1, (255, 255, 255, 0))]))
        bg.alpha_composite(rgrad(w, h, -30, h + 20, 280, [(0, (244, 170, 196, 120)), (1, (244, 170, 196, 0))]))
        bg.alpha_composite(rgrad(w, h, w + 30, -20, 260, [(0, (244, 170, 196, 90)), (1, (244, 170, 196, 0))]))

        # faint branches: one reaching in from the top right, one rising from the bottom left
        br = canvas(w, h)
        bd = ImageDraw.Draw(br)
        tips_hi, tips_lo = [], []
        grow_limb(bd, rnd, w + 12, 48, 172, 290, 15, 3, 2, tips_hi, rgba(BARK))
        grow_limb(bd, rnd, -12, h - 26, -17, 230, 12, 2.5, 2, tips_lo, rgba(BARK))
        bg.alpha_composite(fade(finish(br).filter(ImageFilter.GaussianBlur(0.6)), 0.2))

        # blossoms and buds along them, softer than the loose ones in front
        cl = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        for tx, ty in tips_hi + tips_lo:
            for _ in range(rnd.randint(1, 3)):
                place_center(cl, blossom_img(rnd.uniform(7, 13), rnd.uniform(0, 72)), tx + rnd.uniform(-11, 11),
                             ty + rnd.uniform(-9, 9))
            if rnd.random() < 0.6:
                place_center(cl, bud_img(rnd.uniform(4, 6), rnd.uniform(-60, 60) + 180), tx + rnd.uniform(-12, 12),
                             ty + rnd.uniform(4, 12))
        bg.alpha_composite(fade(cl, 0.72))

        def spot():
            # petals gather towards the edges; the middle (where list text runs) stays calmer
            while True:
                x, y = rnd.uniform(-8, w + 8), rnd.uniform(-8, h + 8)
                edge = min(x, w - x, y, h - y)
                if 196 < x < 444 and 378 < y < 416:   # page dots
                    continue
                if rnd.random() < (1.0 if edge < 60 else 0.55 if edge < 110 else 0.22):
                    return x, y

        lay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        tones = [(240, 150, 178, 255), (244, 167, 185, 255), (236, 136, 168, 255), (248, 186, 204, 255), (232, 122, 158, 255)]
        for _ in range(84):
            x, y = spot()
            p = petal_img(rnd.uniform(5.5, 12.5), rnd.uniform(0, 360), rnd.choice(tones))
            place_center(lay, fade(p, rnd.uniform(0.6, 0.95)), x, y)
        for _ in range(14):
            x, y = spot()
            place_center(lay, fade(blossom_img(rnd.uniform(6, 11.5), rnd.uniform(0, 72)), rnd.uniform(0.8, 1.0)), x, y)
        bg.alpha_composite(lay)

        # out of focus, in front
        blur = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        soft = dict(tip=(253, 222, 232, 255), mid=(248, 190, 208, 255), heart=(240, 156, 184, 255), edge=None,
                    stamens=False, center=False)
        for x, y, kind, sz, bl, a in ((614, 372, "f", 30, 6, 0.55), (22, 92, "p", 22, 4, 0.5), (606, 96, "p", 15, 3, 0.55),
                                      (34, 318, "f", 19, 4.5, 0.5), (262, 470, "p", 19, 4, 0.45), (452, 70, "p", 11, 2.2, 0.6),
                                      (176, 72, "f", 12, 3, 0.45), (500, 410, "p", 13, 2.6, 0.5)):
            im = blossom_img(sz, rnd.uniform(0, 72), **soft) if kind == "f" else \
                petal_img(sz, rnd.uniform(0, 360), (242, 160, 186, 255), (252, 214, 226, 255))
            pad = Image.new("RGBA", (im.width + 40, im.height + 40), (246, 180, 200, 0))  # pink, not black, bleeds in
            pad.alpha_composite(im, (20, 20))
            place_center(blur, fade(pad.filter(ImageFilter.GaussianBlur(bl)), a), x, y)
        bg.alpha_composite(blur)
        return bg

    # ---- bars ----------------------------------------------------------------------------
    def _bar(self, top):
        """Frosted white with a pink rule along the inner edge."""
        im = lgrad(640, 60, (255, 255, 255, 212), (255, 250, 252, 186), angle=270 if top else 90)
        y = 58.6 if top else 1.4
        d = canvas(640, 60)
        dd = ImageDraw.Draw(d)
        if top:   # a blossom perched on the rule, under the title
            line(dd, [(0, y), (309, y)], rgba(PINK, 255), 1.6)
            line(dd, [(331, y), (640, y)], rgba(PINK, 255), 1.6)
        else:
            line(dd, [(0, y), (640, y)], rgba(PINK, 255), 1.6)
        im.alpha_composite(finish(d))
        if top:
            place_center(im, blossom_img(7, 0, tip=(255, 236, 242, 255), mid=(246, 168, 192, 255), heart=(214, 92, 133, 255),
                                         edge=(214, 92, 133, 170), stamens=False), 320, 52.5)
        return im

    def header_bar(self):
        return self._bar(True)

    def footer_bar(self):
        return self._bar(False)

    def logo(self):
        w, h = 160, 42
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        place_center(im, blossom_img(15, 8, edge=(214, 92, 133, 200)), 18, 21)
        t = canvas(w, h)
        ImageDraw.Draw(t).text((S(38), S(22)), "Sakura", font=self.font(25), fill=rgba(PLUM), anchor="lm")
        im.alpha_composite(finish(t))
        return im

    # ---- lists ---------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        r = min(24, (h - 9) / 2)
        box = (7, 4, w - 7, h - 5)
        m = rr_mask(w, h, box, r)
        body = with_mask(lgrad(w, h, (251, 196, 212), (245, 168, 190), angle=0), m)
        out = soft_shadow(body, alpha=55, blur=2.5, dy=2)
        out.alpha_composite(body)
        gl = canvas(w, h)
        gd = ImageDraw.Draw(gl)
        rrect(gd, box, r, rgba(DEEP, 150), 1.4)
        line(gd, [(box[0] + r, box[1] + 2.2), (box[2] - r, box[1] + 2.2)], (255, 255, 255, 150), 1.4)
        out.alpha_composite(finish(gl))
        # kept clear of the arrows, toggles and marks MainUI ends at x 620
        place_center(out, blossom_img(5.5, 10, tip=(255, 255, 255, 255), mid=(255, 238, 243, 255), edge=None, stamens=False),
                     w - 14.5, h / 2)
        return out

    def divider(self):
        im = canvas(640, 4)
        d = ImageDraw.Draw(im)
        for i in range(60):
            x = 26 + i * 10
            a = int(150 * min(1, i / 8, (59 - i) / 8))
            circle(d, x, 2, 1.1, fill=rgba(PINK, a))
        return finish(im)

    # ---- cards and tiles -------------------------------------------------------------------
    def _card(self, w, h, focused, box, r):
        m = rr_mask(w, h, box, r)
        if focused:
            fill = lgrad(w, h, (249, 186, 206), (238, 132, 166))
        else:
            fill = lgrad(w, h, (255, 255, 255, 250), (255, 248, 250, 250))
        body = with_mask(fill, m)
        out = soft_shadow(body, alpha=90 if focused else 55, blur=5 if focused else 4, dy=3)
        if focused:
            halo = _solid((w, h), (246, 150, 180), m.filter(ImageFilter.MaxFilter(3))).filter(ImageFilter.GaussianBlur(4))
            out.alpha_composite(fade(halo, 0.8))
        out.alpha_composite(body)
        e = canvas(w, h)
        ed = ImageDraw.Draw(e)
        if focused:   # a white inner ring
            rrect(ed, (box[0] + 2.5, box[1] + 2.5, box[2] - 2.5, box[3] - 2.5), r - 2.5, (255, 255, 255, 190), 1.6)
            rrect(ed, box, r, rgba(DEEP, 200), 1.6)
        else:
            rrect(ed, box, r, rgba(PINK, 255), 2)
        out.alpha_composite(finish(e))
        if focused:
            sheen = rgrad(w, h, w * 0.3, box[1], h * 0.55, [(0, (255, 255, 255, 80)), (1, (255, 255, 255, 0))])
            out.alpha_composite(with_mask(sheen, m))
        return out

    def card(self, w, h, focused):
        r = min(22, w / 4, h / 4)
        return self._card(w, h, focused, (6, 5, w - 6, h - 8), r)

    def tile_base(self, focused):
        im = self._card(156, 278, focused, (7, 6, 149, 266), 24)
        # a large pale blossom tucked into the lower corner, cut by the card
        wm = Image.new("RGBA", (156, 278), (0, 0, 0, 0))
        tones = dict(tip=(255, 255, 255, 255), mid=(255, 255, 255, 255), heart=(255, 255, 255, 255)) if focused else \
            dict(tip=(253, 232, 239, 255), mid=(251, 220, 230, 255), heart=(248, 204, 218, 255))
        place_center(wm, blossom_img(38, 8, edge=None, stamens=False, center=False, **tones), 128, 246)
        im.alpha_composite(with_mask(fade(wm, 0.28 if focused else 0.9), rr_mask(156, 278, (8, 7, 148, 265), 23)))
        if focused:
            # a blossom under the label, and a couple of petals drifting across the card
            place_center(im, blossom_img(7.5, 0, tip=(255, 255, 255, 255), mid=(255, 240, 245, 255), heart=(214, 92, 133, 255),
                                         edge=None, stamens=False), 78, 236)
            for x, y, ln, rot in ((128, 30, 9, 40), (138, 52, 6, 130), (24, 150, 7, 250)):
                place_center(im, fade(petal_img(ln, rot, (255, 255, 255, 255), (255, 255, 255, 255)), 0.75), x, y)
        return im

    def _duotone(self, lines, fill, offset=(3, 3), grow=2):
        sil = silhouette(lines.getchannel("A"), grow)
        body = _solid(lines.size, fill[:3], sil.point(lambda v: v * fill[3] // 255))
        out = Image.new("RGBA", lines.size, (0, 0, 0, 0))
        place(out, body, *offset)
        out.alpha_composite(lines)
        return out

    def tile_glyph(self, key, focused):
        col = rgba(WHITE if focused else INK)
        if key == "favorite":   # a blossom, like the favourite mark in lists
            lines = blossom_lines(156, 278, 78, 112, 39, col, 5.2)
        else:
            im = canvas(156, 278)
            TILES[key](ImageDraw.Draw(im), 78, 112, 36, col, 5.2)
            lines = finish(im)
        # the same two inks either way: plum lines on a pink body, or white lines on a rose one
        if focused:
            return Image.alpha_composite(soft_shadow(lines, (170, 50, 96), 70, 2, 2), self._duotone(lines, (214, 92, 133, 200), (4, 4)))
        return self._duotone(lines, rgba(PINK, 150), (4, 4))

    # ---- buttons -------------------------------------------------------------------------
    def _flower_shape(self, n, cx, cy, R):
        m = Image.new("L", (n * SS, n * SS), 0)
        d = ImageDraw.Draw(m)
        d.ellipse([S(cx - R * 0.74), S(cy - R * 0.74), S(cx + R * 0.74), S(cy + R * 0.74)], fill=255)
        for k in range(5):
            a = math.radians(k * 72)
            x, y = cx + math.sin(a) * R * 0.6, cy - math.cos(a) * R * 0.6
            d.ellipse([S(x - R * 0.4), S(y - R * 0.4), S(x + R * 0.4), S(y + R * 0.4)], fill=255)
        return m.resize((n, n), Image.LANCZOS)

    def button(self, label, primary):
        n = 54
        m = self._flower_shape(n, n / 2, n / 2 + 0.5, 25)
        if primary:
            body = with_mask(lgrad(n, n, (228, 110, 150), (190, 64, 108)), m)
        else:
            body = with_mask(lgrad(n, n, (255, 255, 255), (255, 241, 245)), m)
        out = soft_shadow(body, alpha=70, blur=1.6, dy=1.2)
        out.alpha_composite(body)
        e = canvas(n, n)
        d = ImageDraw.Draw(e)
        ring = self._flower_shape(n * SS, n * SS / 2, (n / 2 + 0.5) * SS, 25 * SS)
        edge = ring.filter(ImageFilter.MinFilter(1 + 2 * int(SS * (1.0 if primary else 1.6))))
        edge = Image.fromarray((np.asarray(ring, np.int16) - np.asarray(edge, np.int16)).clip(0, 255).astype(np.uint8))
        e.paste(Image.new("RGBA", e.size, (255, 255, 255, 170) if primary else rgba(ROSE, 255)), (0, 0), edge)
        d.text((S(n / 2), S(n / 2 + 1.5)), label, font=self.font(25), fill=rgba(WHITE if primary else PLUM), anchor="mm")
        out.alpha_composite(finish(e))
        return out

    def pill(self, label, w, h=48):
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        rrect(d, (2, 7, w - 2, h - 6), (h - 13) / 2, fill=rgba(SHADE, 40))
        rrect(d, (2, 6, w - 2, h - 7), (h - 13) / 2, fill=(255, 255, 255, 255))
        rrect(d, (2, 6, w - 2, h - 7), (h - 13) / 2, rgba(ROSE, 255), 1.6)
        d.text((S(w / 2), S(h / 2)), label, font=self.font(h * 0.34), fill=rgba(PLUM), anchor="mm")
        return finish(im)

    def dot(self, active):
        im = Image.new("RGBA", (20, 20), (0, 0, 0, 0))
        if active:
            place_center(im, blossom_img(9, 0, tip=(246, 160, 186, 255), mid=(236, 120, 156, 255), heart=(196, 70, 112, 255),
                                         edge=(194, 69, 111, 200), stamens=False), 10, 10)
        else:
            place_center(im, petal_img(9, 30, (226, 150, 176, 255), (240, 190, 206, 255), edge=(214, 132, 160, 200)), 10, 10)
        return im

    def toggle(self, on):
        w, h = 92, 44
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        box = (8, 10, w - 8, h - 10)
        r = (box[3] - box[1]) / 2
        if on:
            rrect(d, box, r, fill=rgba(ROSE))
            rrect(d, box, r, rgba(DEEP), 1.4)
        else:
            rrect(d, box, r, fill=(236, 218, 226, 255))
            rrect(d, box, r, (205, 170, 186, 255), 1.4)
        cx = box[2] - r if on else box[0] + r
        circle(d, cx, h / 2 + 1, r + 3, fill=rgba(SHADE, 50))
        circle(d, cx, h / 2, r + 3, fill=(255, 255, 255, 255))
        circle(d, cx, h / 2, r + 3, rgba(DEEP if on else (205, 170, 186)), 1.3)
        out = finish(im)
        if on:
            place_center(out, blossom_img(7, 0, tip=(246, 160, 186, 255), mid=(236, 120, 156, 255), heart=(194, 69, 111, 255),
                                          edge=None, stamens=False), cx, h / 2)
        return out

    def arrow(self, left, size=24):
        im = canvas(size, size)
        d = ImageDraw.Draw(im)
        xs = (0.62, 0.34) if left else (0.38, 0.66)
        line(d, [(size * xs[0], size * 0.22), (size * xs[1], size * 0.5), (size * xs[0], size * 0.78)], rgba(PLUM), size * 0.14)
        return finish(im)

    # ---- panels ----------------------------------------------------------------------------
    def panel_image(self, w, h):
        r = 22 if w > 500 else 18
        box = (3, 3, w - 3, h - 4)
        m = rr_mask(w, h, box, r)
        body = with_mask(lgrad(w, h, (255, 252, 253, 250), (255, 240, 245, 250)), m)
        out = soft_shadow(body, alpha=60, blur=3, dy=2)
        out.alpha_composite(body)
        # a pale blossom in the corner, cut by the panel's edge
        wm = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        R = min(60, h * 0.55)
        place_center(wm, blossom_img(R, 14, tip=(253, 228, 236, 255), mid=(250, 210, 222, 255), heart=(246, 186, 204, 255),
                                     edge=None, stamens=False), w - R * 0.45, h - R * 0.4)
        out.alpha_composite(with_mask(fade(wm, 0.7), m))
        e = canvas(w, h)
        rrect(ImageDraw.Draw(e), box, r, rgba(PINK, 255), 2)
        out.alpha_composite(finish(e))
        return out

    def preview_frame(self):
        """A white mat: MainUI stretches it to the picture plus 6 px a side, so only its rim shows."""
        im = canvas(250, 360)
        d = ImageDraw.Draw(im)
        rrect(d, (0.5, 0.5, 249.5, 359.5), 12, fill=(255, 255, 255, 255))
        rrect(d, (0.5, 0.5, 249.5, 359.5), 12, rgba(ROSE, 255), 2.2)
        return finish(im)

    def key(self, focused):
        w, h = 64, 52
        box = (3, 3, w - 3, h - 4)
        m = rr_mask(w, h, box, 12)
        fill = lgrad(w, h, (206, 84, 126), (172, 52, 94)) if focused else lgrad(w, h, (160, 90, 124), (136, 72, 104))
        body = with_mask(fill, m)
        out = soft_shadow(body, alpha=60, blur=2, dy=1.5)
        out.alpha_composite(body)
        e = canvas(w, h)
        d = ImageDraw.Draw(e)
        if focused:
            rrect(d, box, 12, (255, 255, 255, 230), 2)
        else:
            line(d, [(box[0] + 10, box[1] + 1.8), (box[2] - 10, box[1] + 1.8)], (255, 255, 255, 70), 1.2)
        out.alpha_composite(finish(e))
        return out

    def input_box(self):
        w, h = 560, 60
        box = (2, 3, w - 2, h - 4)
        m = rr_mask(w, h, box, 22)
        body = with_mask(lgrad(w, h, (255, 250, 252), (255, 255, 255)), m)
        out = soft_shadow(body, alpha=45, blur=2, dy=1.5)
        out.alpha_composite(body)
        e = canvas(w, h)
        rrect(ImageDraw.Draw(e), box, 22, rgba(ROSE, 255), 2)
        out.alpha_composite(finish(e))
        return out

    # ---- small marks -----------------------------------------------------------------------
    def folder_icon(self):
        im = canvas(60, 60)
        g_folder(ImageDraw.Draw(im), 30, 31, 19, rgba(INK), 3.6)
        out = self._duotone(finish(im), rgba(PINK, 170), (2, 2))
        place_center(out, blossom_img(7, 20, edge=(214, 92, 133, 220), stamens=False), 42, 41)
        return out

    def favorite_mark(self):
        im = Image.new("RGBA", (34, 39), (0, 0, 0, 0))
        b = blossom_img(14, 0, tip=(246, 150, 180, 255), mid=(232, 104, 146, 255), heart=(186, 54, 100, 255),
                        edge=(255, 255, 255, 255), stamens=False)
        place_center(im, soft_shadow(b, alpha=90, blur=1.5, dy=1), 17, 20)
        place_center(im, b, 17, 20)
        return im

    def battery(self, level, charging=False):
        im = canvas(48, 48)
        d = ImageDraw.Draw(im)
        rrect(d, (5, 15, 40, 33), 6, rgba(PLUM), 2.2)
        rrect(d, (40.5, 20.5, 44, 27.5), 1.6, fill=rgba(PLUM))
        if level > 0:
            col = (220, 70, 70, 255) if level <= 0.2 else rgba(ROSE)
            rrect(d, (8.5, 18.5, 8.5 + 28 * level, 29.5), 3.4, fill=col)
        if charging:
            pts = [(25, 10), (16, 26), (23, 26), (20, 38), (31, 21), (24, 21)]
            poly(d, pts, (255, 255, 255, 255), 3.2)
            poly(d, pts, fill=rgba(PLUM))
        return finish(im)

    # ---- system and app icons ---------------------------------------------------------------
    def icon_picture(self, sid, selected, cy):
        im = canvas(120, 130)
        pen = Pen(ImageDraw.Draw(im), 60, cy, rgba(WHITE if selected else INK), SS, w=3.2)
        pen.soft_c = rgba(ROSE, 150)
        (GLYPHS.get(sid) or GLYPHS["fallback"])(pen)
        if sid == "android":   # its eyes are drawn in a fixed navy
            for ex in (-13, 13):
                pen.d.ellipse([*pen.p(ex - 4.5, -12.5), *pen.p(ex + 4.5, -3.5)], fill=rgba(DEEP if selected else WHITE))
        lines = finish(im)
        return self._duotone(lines, (214, 92, 133, 190) if selected else rgba(PINK, 140), (3, 3))

    def badge(self, label, selected):
        im = canvas(120, 130)
        d = ImageDraw.Draw(im)
        f = self.font(14)
        tw = d.textlength(label, font=f) / SS
        bw = min(116, tw + 18)
        x0 = (120 - bw) / 2
        rrect(d, (x0, 105, x0 + bw, 126), 10.5, fill=(255, 255, 255, 255))
        rrect(d, (x0, 105, x0 + bw, 126), 10.5, rgba(ROSE, 255), 1.4)
        d.text((S(60), S(115.8)), label, font=f, fill=rgba(PLUM), anchor="mm")
        return finish(im)

    def app_icon(self, key):
        """A small white card like the menu tiles (flat colours: icons are palette-quantized)."""
        n = 96
        box = (5, 4, n - 5, n - 7)
        m = rr_mask(n, n, box, 26)
        body = _solid((n, n), WHITE, m)
        out = soft_shadow(body, alpha=60, blur=2.5, dy=2)
        out.alpha_composite(body)
        e = canvas(n, n)
        rrect(ImageDraw.Draw(e), box, 26, rgba(PINK, 255), 2.4)
        out.alpha_composite(finish(e))
        g = canvas(n, n)
        APPS[key](ImageDraw.Draw(g), n / 2, n / 2 - 2, n * 0.25, rgba(INK), n * 0.055)
        out.alpha_composite(self._duotone(finish(g), rgba(PINK, 170), (3, 3)))
        return out

    # ---- sound ------------------------------------------------------------------------------
    def sound(self, path):
        """A soft wind-chime tick: two bell partials, quickly fading."""
        rate = 22050
        frames = bytearray()
        for i in range(int(rate * 0.09)):
            t = i / rate
            env = math.exp(-t * 55) * min(1.0, t / 0.002)
            s = 0.7 * math.sin(2 * math.pi * 1568 * t) + 0.3 * math.sin(2 * math.pi * 2349 * t + 0.6)
            frames += struct.pack("<h", int(7200 * env * s))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))
