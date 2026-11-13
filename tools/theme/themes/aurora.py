"""Aurora: polar night sky with teal-violet aurora ribbons and frosted-glass UI."""
import math
import random
import struct
import wave

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

from theme_kit import APPS, BADGES, GLYPHS, SS, TILES, Pen, S, Style, canvas, finish, g_folder, g_star, hexc, line, rgba

TEAL = (59, 232, 196)
VIOLET = (142, 108, 255)
MINT = (176, 255, 234)
INK = (10, 11, 32)
WHITE = (255, 255, 255)
CORAL = (255, 112, 140)
UI_ACCENT = (124, 92, 240)
SEL_LABEL = (198, 255, 241)
SKY = (120, 200, 250)
ICON_COLOURS = 40   # per system icon, chosen by _kquant
ALPHA_GAMMA = 0.6
FROST = (22, 22, 54)
# the kit's badges for these spell brand names; no badge centres the picture
BADGE_FIX = {"xbox": None, "segacd": "MD-CD"}

# gradient stops (t, rgb) for edges, fills and glyph lines
AURORA = [(0.0, TEAL), (1.0, VIOLET)]
GLYPH_GRAD = [(0.0, (150, 250, 224)), (0.45, (140, 190, 255)), (1.0, (165, 125, 255))]


# ---- gradient, mask and palette helpers ---------------------------------------------------

_RAMPS = {}


def _ramp(w, h, fx=0.0, fy=1.0):
    """L image 0..255 rising along (fx, fy) across the box."""
    key = (fx, fy)
    if key not in _RAMPS:
        n = 128
        base = Image.new("L", (n, n))
        px = base.load()
        lo = min(0, fx) + min(0, fy)
        span = abs(fx) + abs(fy)
        for y in range(n):
            for x in range(n):
                px[x, y] = int(round(255 * ((fx * x + fy * y) / (n - 1) - lo) / span))
        _RAMPS[key] = base
    return _RAMPS[key].resize((w, h), Image.BILINEAR)


def _stop(stops, t):
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            k = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(c0[i] + (c1[i] - c0[i]) * k for i in range(len(c0)))
    return stops[-1][1]


def _colorize(ramp, stops, alpha=255):
    """RGBA image: [ramp] (L) mapped through colour [stops] (RGB or RGBA); [alpha] scales it."""
    stops = [(t, tuple(c) + (255,) * (4 - len(c))) for t, c in stops]
    luts = [[], [], [], []]
    for i in range(256):
        c = _stop(stops, i / 255)
        for k in range(4):
            luts[k].append(int(round(c[k] * (alpha / 255 if k == 3 else 1))))
    return Image.merge("RGBA", [ramp.point(luts[k]) for k in range(4)])


def _grad(w, h, stops, fx=0.0, fy=1.0, alpha=255):
    return _colorize(_ramp(w, h, fx, fy), stops, alpha)


def _solid(w, h, c, a=255):
    return Image.new("RGBA", (w, h), rgba(c, a))


def _rr_mask(w, h, box, r, width=0):
    """Anti-aliased rounded-rect mask (L, 1x); [width] > 0 gives an outline centred on the box."""
    m = Image.new("L", (w * SS, h * SS), 0)
    d = ImageDraw.Draw(m)
    if width:
        e = width / 2
        b = (box[0] - e, box[1] - e, box[2] + e, box[3] + e)
        d.rounded_rectangle([v * SS for v in b], radius=(r + e) * SS, outline=255, width=max(1, int(width * SS)))
    else:
        d.rounded_rectangle([v * SS for v in box], radius=r * SS, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def _mask(im, m, k=1.0):
    """[im] with its alpha multiplied by mask [m] (L) and factor [k]."""
    out = im.copy()
    a = ImageChops.multiply(im.getchannel("A"), m)
    if k != 1.0:
        a = a.point(lambda v: min(255, int(v * k)))
    out.putalpha(a)
    return out


def _fade(im, k):
    return _mask(im, Image.new("L", im.size, 255), k)


def _over(*layers):
    out = layers[0]
    for l in layers[1:]:
        out = Image.alpha_composite(out, l)
    return out


def _blur(im, r):
    # premultiplied, so faint halo pixels keep their colour instead of fading to black
    if im.mode == "RGBA":
        return im.convert("RGBa").filter(ImageFilter.GaussianBlur(r)).convert("RGBA")
    return im.filter(ImageFilter.GaussianBlur(r))


def _tinted(im, c, k=1.0):
    """[im]'s shape in colour [c] (or a gradient image of the same size)."""
    src = c if isinstance(c, Image.Image) else _solid(im.width, im.height, c)
    return _mask(src, im.getchannel("A"), k)


def _hfade_mask(w, h, edge=0.18, floor=0.0):
    """L mask: full in the middle, fading towards both ends."""
    m = Image.new("L", (w, 1))
    px = m.load()
    for x in range(w):
        t = min(x, w - 1 - x) / (w * edge)
        px[x, 0] = int(255 * (floor + (1 - floor) * min(1.0, t)))
    return m.resize((w, h))


def _edge_fade(w, h, n=4):
    """L mask: 0 on the outer pixel ring, rising to full over [n] px, so halos never hit the edge."""
    def prof(k):
        return [int(255 * min(1.0, min(i, k - 1 - i) / n)) for i in range(k)]
    hx, vy = Image.new("L", (w, 1)), Image.new("L", (1, h))
    hx.putdata(prof(w))
    vy.putdata(prof(h))
    return ImageChops.darker(hx.resize((w, h)), vy.resize((w, h)))


def _hl_line(w, h, x0, x1, y, k):
    """A 1 px light line from x0 to x1 that fades in and out over a quarter of its length."""
    im = canvas(w, h)
    ImageDraw.Draw(im).line([x0 * SS, y * SS, x1 * SS, y * SS], fill=(255, 255, 255, 255), width=SS)
    span = max(1.0, (x1 - x0) * 0.25)
    m = Image.new("L", (w, 1))
    m.putdata([int(255 * max(0.0, min(1.0, (x - x0) / span, (x1 - x) / span))) for x in range(w)])
    return _mask(finish(im), m.resize((w, h)), k)


def _kquant(im, n, seed=1, iters=6, sample=3500):
    """[im] reduced to at most [n] RGBA colours by k-means in premultiplied space, so soft
    glows keep smooth alpha steps (the kit's octree quantizer turns them into blotches)."""
    a = np.asarray(im.convert("RGBA"), dtype=np.float32).reshape(-1, 4)
    vis = a[:, 3] > 0
    # alpha on a gamma curve so faint halo levels get their share of the palette
    ag = 255 * (a[vis, 3:4] / 255) ** ALPHA_GAMMA
    X = np.concatenate([a[vis, :3] * (ag / 255), ag], 1)
    if len(X) == 0:
        return im
    rng = np.random.default_rng(seed)
    smp = X[rng.choice(len(X), sample, replace=False)] if len(X) > sample else X
    k = min(n - 1, len(np.unique(smp, axis=0)))

    def nearest(P, C):  # plain differences, no BLAS: the same labels on every machine
        return ((P[:, None, :] - C[None, :, :]) ** 2).sum(2).argmin(1)
    C = [smp[rng.integers(len(smp))]]
    d2 = ((smp - C[0]) ** 2).sum(1)
    for _ in range(1, k):
        C.append(smp[rng.choice(len(smp), p=d2 / d2.sum())] if d2.sum() > 0 else smp[rng.integers(len(smp))])
        d2 = np.minimum(d2, ((smp - C[-1]) ** 2).sum(1))
    C = np.array(C)
    for _ in range(iters):
        lab = nearest(smp, C)
        cnt = np.bincount(lab, minlength=k)
        for c in range(4):
            sums = np.bincount(lab, weights=smp[:, c], minlength=k)
            C[:, c] = np.where(cnt > 0, sums / np.maximum(cnt, 1), C[:, c])
    ag = np.clip(C[:, 3], 1, 255)
    alpha = 255 * (ag / 255) ** (1 / ALPHA_GAMMA)
    pal = np.concatenate([np.clip(C[:, :3] * 255 / ag[:, None], 0, 255), alpha[:, None]], 1).round().astype(np.uint8)
    out = np.zeros(a.shape, dtype=np.uint8)
    out[vis] = pal[nearest(X, C)]
    return Image.fromarray(out.reshape(im.height, im.width, 4), "RGBA")


_BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) / 16 - 15 / 32


def _dither(im, amp=12):
    """Ordered dither on RGB: the kit's octree quantizer merges colours closer than 16 levels,
    which turns smooth gradients into blotches; a fine pattern survives it and reads smooth."""
    a = np.asarray(im.convert("RGBA")).astype(np.float32)
    t = np.tile(_BAYER, (im.height // 4 + 1, im.width // 4 + 1))[:im.height, :im.width]
    a[..., :3] = np.clip(a[..., :3] + t[..., None] * amp, 0, 255)
    return Image.fromarray(a.round().astype(np.uint8), "RGBA")


def _smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def _sparkle(d, cx, cy, r, c, waist=0.18):
    """Four-pointed star (polar star) as a filled polygon on a supersampled canvas."""
    pts = []
    for i in range(16):
        a = math.radians(i * 22.5 - 90)
        k = 1.0 if i % 4 == 0 else (waist if i % 2 else waist * 1.25)
        pts.append((cx + r * k * math.cos(a), cy + r * k * math.sin(a)))
    d.polygon([(x * SS, y * SS) for x, y in pts], fill=c)


def _curtain(w, h, seed, base, height, power, cols, x_fade=None, hem=0.55):
    """One aurora curtain as additive light (RGB): irregular rays rising from a bright lower
    hem at y = base(x); [cols] runs from the hem (0) to the top of the rays (1)."""
    rr = random.Random(seed)
    rays = [0.0] * w
    for _ in range(w // 7):
        c, s, a = rr.uniform(-20, w + 20), rr.uniform(1.2, 6.5), rr.uniform(0.1, 0.45)
        for x in range(max(0, int(c - 3 * s)), min(w, int(c + 3 * s) + 2)):
            rays[x] += a * math.exp(-((x - c) / s) ** 2)
    ph = [rr.uniform(0, 6.28) for _ in range(4)]
    n = 256
    body, hemp = [], []
    for i in range(n):
        t = i / (n - 1)               # 0 = top of the rays, 1 = below the hem
        cut = max(0.0, 1 - (t - 0.95) / 0.05) ** 1.3 if t > 0.95 else 1.0
        body.append(0.62 * t ** 2.2 * cut)
        hemp.append(math.exp(-((t - 0.95) / 0.032) ** 2) * cut)
    prof_b, prof_h, col = Image.new("L", (1, n)), Image.new("L", (1, n)), Image.new("RGB", (1, n))
    for i in range(n):
        prof_b.putpixel((0, i), int(255 * body[i] / 0.62))
        prof_h.putpixel((0, i), int(255 * hemp[i]))
        col.putpixel((0, i), tuple(int(c) for c in _stop(cols, 1 - i / (n - 1))))
    layer = Image.new("RGB", (w, h), (0, 0, 0))
    for x in range(w):
        r = min(1.0, 0.3 + rays[x])
        fold = 0.75 + 0.25 * math.sin(x / 57 + ph[0]) * math.sin(x / 23 + ph[1])
        env = power(x) * fold * (1.0 if x_fade is None else x_fade(x))
        kb = max(0.0, min(1.0, env * r * 0.62 / (0.62 + hem)))
        kh = max(0.0, min(1.0, env * hem / (0.62 + hem) * (0.6 + 0.4 * math.sin(x / 29 + ph[2]) * math.sin(x / 11 + ph[3]))
                          * (0.7 + 0.3 * r)))
        hh = max(8, int(height(x) * (0.72 + 0.45 * min(1.0, rays[x]))))
        y0 = int(base(x) - hh)
        ys = max(0, y0)
        if ys >= h or env <= 0:
            continue
        p = ImageChops.add(prof_b.resize((1, hh), Image.BILINEAR).point(lambda v: int(v * kb)),
                           prof_h.resize((1, hh), Image.BILINEAR).point(lambda v: int(v * kh)))
        strip = ImageChops.multiply(col.resize((1, hh), Image.BILINEAR), Image.merge("RGB", (p, p, p)))
        layer.paste(strip.crop((0, ys - y0, 1, hh)), (x, ys))
    soft = layer.resize((w // 3, h), Image.BOX).resize((w, h), Image.BILINEAR).filter(ImageFilter.GaussianBlur(1.2))
    wide = layer.filter(ImageFilter.GaussianBlur(14)).point(lambda v: int(v * 0.9))
    haze = layer.filter(ImageFilter.GaussianBlur(36)).point(lambda v: min(255, int(v * 1.5)))
    return ImageChops.add(ImageChops.add(soft, wide), haze)


class _ThinPen(Pen):
    """Pen with every stroke scaled by k (the glyphs pass many widths explicitly)."""
    k = 0.82

    def _w(self, w):
        return max(1, int(round((self.w if w is None else w) * self.k * self.ss)))


class Theme(Style):
    name = "Aurora"
    author = "MainUI"
    description = "Polar night: teal and violet aurora ribbons over a starry sky, with frosted glass cards and glowing line art."

    bg_top = (22, 18, 58)
    bg_bottom = (5, 6, 15)
    accent = TEAL
    accent2 = VIOLET
    highlight = MINT
    text = (242, 246, 255)
    muted = (184, 193, 226)
    glyph_n = (150, 226, 214)
    glyph_f = (232, 255, 250)
    panel = (17, 16, 44)
    panel_alpha = 250
    bar = (9, 9, 26)
    bar_alpha = 205
    sky_mid = (11, 11, 34)

    font_file = "Quicksand-Bold.ttf"
    font_license = "OFL-Quicksand.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 28, 26, 24, 23, 17, 18

    icon_quant = 64     # system icons arrive reduced by _kquant, app icons dithered (see both)
    sel_icons = True

    # ---- glass ---------------------------------------------------------------------------
    def glass(self, w, h, r, focused, pad=6, body=None, glow=5, tint=34, smoke=None, frost=None):
        """A frosted glass card: translucent white body darkening towards the bottom, a soft
        sheen, a light edge; focused, brighter, with a teal-to-violet edge and a glow around it."""
        box = (pad, pad, w - pad, h - pad)
        shape = _rr_mask(w, h, box, r)
        layers = [Image.new("RGBA", (w, h), (0, 0, 0, 0))]
        diag = _grad(w, h, AURORA, 1, 1)
        if focused and glow:
            ring = _rr_mask(w, h, box, r, 3)
            halo = _over(_fade(_blur(_mask(diag, ring), glow), 1.4), _fade(_blur(_mask(diag, ring), glow * 0.45), 0.8))
            layers.append(_mask(halo, _edge_fade(w, h)))
        # frost: a flat night-sky veil flattens what shows through before the white body lifts it
        frost = (48 if focused else 64) if frost is None else frost
        if frost:
            layers.append(_mask(_solid(w, h, FROST, frost), shape))
        top, bot = body or ((50, 24) if focused else (34, 18))
        layers.append(_mask(_grad(w, h, [(0, WHITE + (top,)), (1, WHITE + (bot,))]), shape))
        # smoke in the lower part keeps labels readable over the brightest aurora
        sm = (40 if focused else 52) if smoke is None else smoke
        if sm:
            layers.append(_mask(_grad(w, h, [(0.4, INK + (0,)), (1, INK + (sm,))]), shape))
        if focused and tint:
            layers.append(_mask(_grad(w, h, AURORA, 1, 1, tint), shape))
        # sheen: a faint diagonal light band across the upper left
        sh = Image.new("L", (w, h), 0)
        ImageDraw.Draw(sh).polygon([(0, 0), (w * 0.9, 0), (0, h * 0.5)], fill=255)
        sh = _blur(sh, min(w, h) * 0.12)
        layers.append(_mask(_solid(w, h, WHITE, 16 if focused else 11), ImageChops.multiply(sh, shape)))
        # edge: light from above on the plain card, the aurora gradient on the focused one
        ew = 2.0 if focused else 1.0
        ring = _rr_mask(w, h, box, r, ew)
        if focused:
            layers.append(_mask(diag, ring))
        else:
            layers.append(_mask(_grad(w, h, [(0, WHITE + (88,)), (1, WHITE + (34,))]), ring))
        # inner highlight just inside the top edge (too fussy on small keys)
        if w >= 80:
            layers.append(_hl_line(w, h, box[0] + r, box[2] - r, box[1] + ew + 0.8, 0.32 if focused else 0.2))
        return _over(*layers)

    def aurora_line(self, w, h, y, x0, x1, thick=2.0, glow=3):
        """A short horizontal teal-to-violet bar with a glow."""
        m = Image.new("L", (w * SS, h * SS), 0)
        ImageDraw.Draw(m).rounded_rectangle([x0 * SS, (y - thick / 2) * SS, x1 * SS, (y + thick / 2) * SS],
                                            radius=thick / 2 * SS, fill=255)
        m = m.resize((w, h), Image.LANCZOS)
        g = _grad(w, h, AURORA, 1, 0)
        core = _mask(g, m)
        return _over(_fade(_blur(core, glow), 1.2), core)

    # ---- big pictures ---------------------------------------------------------------------
    def background(self, w=640, h=480):
        rnd = random.Random(7)
        sky = _grad(w, h, [(0, (24, 19, 62)), (0.42, (12, 12, 38)), (0.8, (6, 7, 20)), (1, (4, 5, 12))])
        haze = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        hd = ImageDraw.Draw(haze)
        hd.ellipse((w * 0.5, -h * 0.35, w * 1.25, h * 0.35), fill=(110, 70, 210, 60))
        hd.ellipse((-w * 0.3, h * 0.45, w * 0.55, h * 1.0), fill=(20, 90, 110, 40))
        sky = _over(sky, _blur(haze, 80))

        # stars, behind the aurora
        st = canvas(w, h)
        sd = ImageDraw.Draw(st)
        for _ in range(110):
            x, y = rnd.uniform(0, w), rnd.uniform(0, h * 0.8) ** 1.08
            fall = max(0.0, 1 - y / (h * 0.78))
            a = int(rnd.uniform(60, 200) * fall)
            r = rnd.choice((0.55, 0.6, 0.7, 0.8, 0.8, 1.0, 1.2))
            c = rnd.choice(((255, 255, 255), (210, 230, 255), (220, 210, 255), (200, 255, 240)))
            sd.ellipse(((x - r) * SS, (y - r) * SS, (x + r) * SS, (y + r) * SS), fill=c + (a,))
        stars = finish(st)
        bright = canvas(w, h)
        bd = ImageDraw.Draw(bright)
        for x, y, r in ((92, 74, 6), (548, 238, 5), (306, 34, 4.5), (604, 96, 3.5), (36, 262, 3.5)):
            _sparkle(bd, x, y, r, (235, 245, 255, 210), 0.13)
            bd.ellipse(((x - 1.3) * SS, (y - 1.3) * SS, (x + 1.3) * SS, (y + 1.3) * SS), fill=(255, 255, 255, 255))
        bright = finish(bright)
        sky = _over(sky, stars, _fade(_blur(bright, 2.5), 1.2), bright)

        # aurora curtains, added as light
        ribs = [
            _curtain(w, h, 11, lambda x: 172 - 0.13 * x + 38 * math.sin(x / 130 + 0.6) + 7 * math.sin(x / 33 + 2.0),
                     lambda x: 96 + 24 * math.sin(x / 70 + 1.0), lambda x: 0.62,
                     [(0, (70, 255, 205)), (0.12, (50, 220, 190)), (0.5, (60, 170, 220)), (1, (130, 90, 240))], hem=0.34),
            _curtain(w, h, 23, lambda x: 84 + 0.17 * x + 16 * math.sin(x / 75 + 1.2),
                     lambda x: 72 + 18 * math.sin(x / 50), lambda x: 0.55,
                     [(0, (120, 150, 255)), (0.4, (140, 100, 250)), (1, (175, 80, 230))],
                     lambda x: _smooth(min(x / 150, (420 - x) / 100)), hem=0.35),
            _curtain(w, h, 37, lambda x: 262 + 0.16 * x + 8 * math.sin(x / 48 + 0.3),
                     lambda x: 56, lambda x: 0.3,
                     [(0, (60, 230, 190)), (1, (110, 100, 230))],
                     lambda x: _smooth(1.25 - x / 240), hem=0.4),
        ]
        light = ribs[0]
        for r_ in ribs[1:]:
            light = ImageChops.add(light, r_)
        sky_rgb = ImageChops.add(sky.convert("RGB"), light)

        # horizon: a faint teal haze and two dark ridges
        out = sky_rgb.convert("RGBA")
        hz = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(hz).ellipse((-60, 360, w + 60, 420), fill=(40, 170, 160, 36))
        out = _over(out, _blur(hz, 18))
        def ridge(y0, k, ph, fill, rim, rim_a):
            # rolling sines plus |sin| cusps for peaks, no random jitter
            def y(x):
                return y0 - k * (16 * math.sin(x / 140 + ph) + 8 * math.sin(x / 61 + 2.1 * ph)
                                 + 10 * (1 - abs(math.sin(x / 41 + 3.3 * ph))) + 2.5 * math.sin(x / 13 + 5.1 * ph)
                                 + math.sin(x / 5 + 7 * ph))
            pts = [(x, y(x)) for x in range(0, w + 3, 2)]
            m = Image.new("L", (w * SS, h * SS), 0)
            ImageDraw.Draw(m).polygon([(x * SS, y * SS) for x, y in pts + [(w + 2, h), (0, h)]], fill=255)
            edge = Image.new("L", (w * SS, h * SS), 0)
            ImageDraw.Draw(edge).line([(x * SS, y * SS) for x, y in pts], fill=255, width=int(1.2 * SS))
            m, edge = m.resize((w, h), Image.LANCZOS), edge.resize((w, h), Image.LANCZOS)
            lit = _mask(fill, m)
            return _over(lit, _mask(_solid(w, h, rim), _blur(edge, 3), rim_a * 1.3), _mask(_solid(w, h, rim), edge, rim_a))

        far = ridge(384, 1.0, 0.9, _grad(w, h, [(0.74, (22, 24, 56)), (0.9, (11, 12, 31))]), (90, 230, 205), 0.2)
        near = ridge(410, 0.75, 2.4, _solid(w, h, (6, 7, 18)), (150, 130, 245), 0.16)
        return _over(out, far, near)

    def _bar(self, top_line):
        w, h = 640, 60
        a0, a1 = (self.bar_alpha, self.bar_alpha - 40) if not top_line else (self.bar_alpha - 40, self.bar_alpha)
        im = _grad(w, h, [(0, self.bar + (a0,)), (1, self.bar + (a1,))])
        y = 0.5 if top_line else h - 1.5
        m = Image.new("L", (w * SS, h * SS), 0)
        ImageDraw.Draw(m).rectangle((0, int(y * SS), w * SS, int((y + 1.2) * SS)), fill=255)
        m = ImageChops.multiply(m.resize((w, h), Image.LANCZOS), _hfade_mask(w, h, 0.35, 0.25))
        edge = _mask(_grad(w, h, AURORA, 1, 0), m)
        return _over(im, _fade(_blur(edge, 3), 1.6), edge)

    def header_bar(self):
        return self._bar(False)

    def footer_bar(self):
        return self._bar(True)

    def logo(self):
        w, h = 160, 42
        mark = canvas(w, h)
        md = ImageDraw.Draw(mark)
        _sparkle(md, 15, 22, 13, (255, 255, 255, 255), 0.14)
        _sparkle(md, 27.5, 9.5, 4.2, (255, 255, 255, 230), 0.2)
        mark = _tinted(finish(mark), _grad(w, h, [(0, (236, 255, 250)), (0.6, MINT), (1, TEAL)], 0, 1))
        txt = canvas(w, h)
        ImageDraw.Draw(txt).text((S(35), S(22)), "aurora", font=self.font(25), fill=(255, 255, 255, 255), anchor="lm")
        txt = _tinted(finish(txt), _grad(w, h, [(0.22, (120, 246, 214)), (0.5, (140, 196, 255)), (0.8, (176, 146, 255))], 1, 0))
        glow = _over(_fade(_blur(_tinted(mark, TEAL), 3), 1.3), _fade(_blur(_tinted(txt, TEAL), 4), 0.45))
        return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), glow, mark, txt)

    # ---- lists ----------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        box = (8, 7, w - 8, h - 7)
        r = 13 if h < 70 else 17
        shape = _rr_mask(w, h, box, r)
        ring = _rr_mask(w, h, box, r, 1.6)
        g = _grad(w, h, [(0, TEAL), (0.4, (110, 150, 245)), (1, VIOLET)], 1, 0)
        body = _over(_mask(_solid(w, h, INK, 84), shape),
                     _mask(_grad(w, h, [(0, WHITE + (40,)), (1, WHITE + (20,))]), shape),
                     _mask(_grad(w, h, [(0, TEAL + (44,)), (0.45, (110, 150, 245, 22)), (1, VIOLET + (52,))], 1, 0), shape))
        hl = _hl_line(w, h, box[0] + r, box[2] - r, box[1] + 2.2, 0.3)
        edge = _mask(g, ring)
        halo = _mask(_fade(_blur(edge, 2.5), 1.5), _edge_fade(w, h))
        return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), halo, body, hl, edge)

    def divider(self):
        w, h = 640, 4
        m = Image.new("L", (w, h), 0)
        ImageDraw.Draw(m).line((24, 2, 616, 2), fill=255)
        m = ImageChops.multiply(m, _hfade_mask(w, h, 0.25))
        return _mask(_solid(w, h, (200, 210, 255), 30), m)

    # ---- tiles ----------------------------------------------------------------------------
    def card(self, w, h, focused):
        big = min(w, h) > 100
        return self.glass(w, h, 16 if big else 10, focused, pad=7 if big else 5,
                          glow=3 if big else 1.5)

    def tile_base(self, focused):
        w, h = 156, 278
        pad = 8
        im = self.glass(w, h, 22, focused, pad=pad, glow=4)
        if focused:
            # an aurora band inside the glass (vertical only: far cheaper as PNG than blurred blobs)
            shape = _rr_mask(w, h, (pad, pad, w - pad, h - pad), 22)
            wash = _grad(w, h, [(0.14, TEAL + (0,)), (0.36, TEAL + (70,)), (0.56, VIOLET + (64,)), (0.8, VIOLET + (0,))])
            im = _over(im, _mask(wash, shape), self.aurora_line(w, h, 228, 60, 96, 3.0, 3))
        return im

    def glyph_layer(self, w, h, draw, y0, y1, focused, stops=GLYPH_GRAD):
        """Line art drawn by [draw](ImageDraw) in the aurora gradient (from y0 to y1), glowing if focused."""
        im = canvas(w, h)
        draw(ImageDraw.Draw(im))
        art = finish(im)
        ramp = Image.new("L", (1, h))
        for y in range(h):
            ramp.putpixel((0, y), int(255 * min(1, max(0, (y - y0) / (y1 - y0)))))
        grad = _colorize(ramp.resize((w, h)), stops)
        if focused:
            core = _tinted(art, _colorize(ramp.resize((w, h)), [(0, (236, 255, 250)), (1, (236, 230, 255))]))
            return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), _tinted(_blur(art, 9), grad, 0.9),
                         _tinted(_blur(art, 3.5), grad, 1.6), core)
        return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), _tinted(_blur(art, 4), grad, 0.25), _tinted(art, grad, 0.92))

    def tile_glyph(self, key, focused):
        return self.glyph_layer(156, 278, lambda d: TILES[key](d, 78, 112, 36, (255, 255, 255, 255), 3.6),
                                72, 152, focused)

    def home_tile(self, key, focused):
        base = self.tile_base(focused)
        return _over(base, self.tile_glyph(key, focused)) if key else base

    # ---- buttons and small parts ------------------------------------------------------------
    def button(self, label, primary):
        n = 54
        cx = cy = n / 2
        r = 21.5
        m = Image.new("L", (n * SS, n * SS), 0)
        ImageDraw.Draw(m).ellipse(((cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS), fill=255)
        disc = m.resize((n, n), Image.LANCZOS)
        ring = Image.new("L", (n * SS, n * SS), 0)
        ImageDraw.Draw(ring).ellipse(((cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS), outline=255,
                                     width=int(1.6 * SS))
        ring = ring.resize((n, n), Image.LANCZOS)
        diag = _grad(n, n, AURORA, 1, 1)
        txt = canvas(n, n)
        ImageDraw.Draw(txt).text((S(cx), S(cy + 0.5)), label, font=self.font(25), fill=(255, 255, 255, 255), anchor="mm")
        txt = finish(txt)
        if primary:
            # the disc spans t 0.2..0.8 of the canvas diagonal: violet fills its lower-right third
            fill = _mask(_grad(n, n, [(0.2, (120, 248, 220)), (0.42, TEAL), (0.78, VIOLET)], 1, 1), disc)
            hl = _mask(_grad(n, n, [(0, WHITE + (50,)), (0.45, WHITE + (0,)), (1, WHITE + (0,))]), disc)
            sp = canvas(n, n)
            _sparkle(ImageDraw.Draw(sp), cx - 10.5, cy - 10.5, 4.6, (255, 255, 255, 255), 0.2)
            sp = finish(sp)
            return _over(Image.new("RGBA", (n, n), (0, 0, 0, 0)), _fade(_blur(fill, 3), 0.9), fill, hl,
                         _fade(_blur(sp, 1.5), 0.8), sp, _tinted(txt, INK))
        body = _over(_mask(_solid(n, n, INK, 120), disc),
                     _mask(_grad(n, n, [(0.2, VIOLET + (0,)), (1, VIOLET + (78,))]), disc),
                     _mask(_grad(n, n, [(0, WHITE + (40,)), (1, WHITE + (10,))]), disc))
        edge = _mask(_grad(n, n, [(0, (206, 196, 255)), (1, VIOLET)]), ring)
        return _over(Image.new("RGBA", (n, n), (0, 0, 0, 0)), body, edge, _tinted(txt, self.text))

    def pill(self, label, w, h=48):
        box = (3, 9, w - 3, h - 9)
        r = (box[3] - box[1]) / 2
        shape = _rr_mask(w, h, box, r)
        ring = _rr_mask(w, h, box, r, 1.3)
        txt = canvas(w, h)
        ImageDraw.Draw(txt).text((S(w / 2), S(h / 2 + 0.5)), label, font=self.font(15), fill=rgba(self.text), anchor="mm")
        return _over(_mask(_solid(w, h, INK, 130), shape),
                     _mask(_grad(w, h, [(0.2, VIOLET + (0,)), (1, VIOLET + (70,))]), shape),
                     _mask(_grad(w, h, [(0, WHITE + (40,)), (1, WHITE + (10,))]), shape),
                     _mask(_grad(w, h, [(0, (206, 196, 255)), (1, VIOLET)]), ring, 0.95), finish(txt))

    def dot(self, active):
        w, h = 24, 14
        if active:
            return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), self.aurora_line(w, h, 7, 4, 20, 5, 2))
        im = canvas(w, h)
        ImageDraw.Draw(im).ellipse((S(12 - 3), S(7 - 3), S(12 + 3), S(7 + 3)), fill=(200, 210, 245, 110))
        return finish(im)

    def toggle(self, on):
        w, h = 92, 44
        box = (8, 10, w - 8, h - 10)
        r = (box[3] - box[1]) / 2
        shape = _rr_mask(w, h, box, r)
        ring = _rr_mask(w, h, box, r, 1.2)
        kr = r - 3.5
        kx = box[2] - r if on else box[0] + r
        knob_m = Image.new("L", (w * SS, h * SS), 0)
        ImageDraw.Draw(knob_m).ellipse(((kx - kr) * SS, (h / 2 - kr) * SS, (kx + kr) * SS, (h / 2 + kr) * SS), fill=255)
        knob_m = knob_m.resize((w, h), Image.LANCZOS)
        if on:
            track = _mask(_grad(w, h, [(0, TEAL), (1, VIOLET)], 1, 0, 235), shape)
            knob = _mask(_solid(w, h, (250, 255, 254)), knob_m)
            return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), _fade(_blur(_tinted(track, TEAL), 4), 0.8), track,
                         _mask(_solid(w, h, WHITE, 60), ring), _fade(_blur(_tinted(knob, INK), 2), 0.5), knob)
        track = _over(_mask(_solid(w, h, INK, 130), shape), _mask(_solid(w, h, WHITE, 30), shape))
        knob = _mask(_solid(w, h, self.muted), knob_m)
        return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), track, _mask(_solid(w, h, WHITE, 80), ring), knob)

    def arrow(self, left, size=24):
        xs = (0.6, 0.34) if left else (0.4, 0.66)
        pts = [(size * xs[0], size * 0.2), (size * xs[1], size * 0.5), (size * xs[0], size * 0.8)]
        return self.glyph_layer(size, size, lambda d: line(d, pts, (255, 255, 255, 255), size * 0.11),
                                size * 0.2, size * 0.8, False, [(0, MINT), (1, (200, 220, 255))])

    def panel_image(self, w, h):
        r = 18
        box = (2, 2, w - 2, h - 2)
        shape = _rr_mask(w, h, box, r)
        ring = _rr_mask(w, h, box, r, 1.5)
        body = _grad(w, h, [(0, (26, 24, 64, self.panel_alpha)), (1, (13, 13, 36, self.panel_alpha))])
        glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        gd.ellipse((w * 0.55, -h * 0.6, w * 1.2, h * 0.5), fill=VIOLET + (40,))
        gd.ellipse((-w * 0.2, -h * 0.5, w * 0.45, h * 0.35), fill=TEAL + (26,))
        return _over(_mask(body, shape), _mask(_blur(glow, 40), shape),
                     _hl_line(w, h, box[0] + r, box[2] - r, box[1] + 2, 0.22), _mask(_grad(w, h, AURORA, 1, 0.4), ring))

    def folder_icon(self):
        n = 60

        def draw(d):
            g_folder(d, 30, 31, 19, (255, 255, 255, 255), 3.0)
        art = self.glyph_layer(n, n, draw, 14, 46, False)
        fill = canvas(n, n)
        s = 19
        ImageDraw.Draw(fill).polygon([(S(30 - s), S(31 + s * 0.7)), (S(30 - s), S(31 - s * 0.4)),
                                      (S(30 + s), S(31 - s * 0.4)), (S(30 + s), S(31 + s * 0.7))],
                                     fill=(255, 255, 255, 255))
        fill = _tinted(finish(fill), _grad(n, n, [(0, TEAL + (40,)), (1, VIOLET + (64,))], 0, 1))
        return _over(fill, art)

    def favorite_mark(self):
        w, h = 34, 39
        m = canvas(w, h)
        d = ImageDraw.Draw(m)
        pts = []
        for i in range(10):
            rr = 14 if i % 2 == 0 else 6.6
            a = math.radians(-90 + i * 36)
            pts.append((17 + rr * math.cos(a), 20.5 + rr * math.sin(a)))
        d.polygon([(S(x), S(y)) for x, y in pts], fill=(255, 255, 255, 255))
        star = finish(m)
        fill = _tinted(star, _grad(w, h, [(0, (190, 255, 236)), (1, (170, 150, 255))], 0, 1))
        edge = canvas(w, h)
        g_star(ImageDraw.Draw(edge), 17, 20.5, 14, (255, 255, 255, 200), 1.2)
        return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), _fade(_blur(_tinted(star, TEAL), 3), 1.3), fill, finish(edge))

    def battery(self, level, charging=False):
        n = 48
        body = (6, 15, 40, 33)
        outline = canvas(n, n)
        d = ImageDraw.Draw(outline)
        d.rounded_rectangle([v * SS for v in body], radius=5 * SS, outline=rgba(self.text, 235), width=int(2.0 * SS))
        d.rounded_rectangle([v * SS for v in (40.5, 20.5, 44, 27.5)], radius=1.5 * SS, fill=rgba(self.text, 235))
        layers = [Image.new("RGBA", (n, n), (0, 0, 0, 0)), finish(outline)]
        if level > 0:
            fm = _rr_mask(n, n, (9.5, 18.5, 9.5 + 27 * level, 29.5), 2.5)
            if level <= 0.2:
                fill = _mask(_solid(n, n, CORAL), fm)
            else:
                fill = _mask(_grad(n, n, AURORA, 1, 0), fm)
            layers += [_fade(_blur(fill, 2.5), 0.9), fill]
        if charging:
            b = canvas(n, n)
            ImageDraw.Draw(b).polygon([(S(x), S(y)) for x, y in
                                       [(26, 10), (16, 26), (23, 26), (20, 38), (31, 21), (24, 21)]], fill=(255, 255, 255, 255))
            b = finish(b)
            layers += [_tinted(_blur(b, 2), INK, 1.5), _tinted(b, (255, 250, 210))]
        return _over(*layers)

    def preview_frame(self):
        return self.glass(250, 360, 18, False, pad=4)

    def key(self, focused):
        return self.glass(64, 52, 10, focused, pad=5, glow=1.5, body=(64, 36) if focused else (34, 18), tint=84, smoke=0)

    def input_box(self):
        w, h = 560, 60
        box = (2, 2, w - 2, h - 2)
        shape = _rr_mask(w, h, box, 14)
        ring = _rr_mask(w, h, box, 14, 1.5)
        return _over(_mask(_solid(w, h, INK, 200), shape), _mask(_solid(w, h, WHITE, 16), shape),
                     _mask(_grad(w, h, AURORA, 1, 0), ring))

    # ---- system and app icons ---------------------------------------------------------------
    def icon_picture(self, sid, selected, cy):
        w, h = 120, 130
        im = canvas(w, h)
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        draw(_ThinPen(ImageDraw.Draw(im), 60, cy, (255, 255, 255, 255), SS))
        a = im.getchannel("A")
        lines = a.point(lambda v: 255 if v > 150 else 0)
        soft = a.point(lambda v: 255 if 0 < v <= 150 else 0)
        ramp = Image.new("L", (1, h * SS))
        for y in range(h * SS):
            ramp.putpixel((0, y), int(255 * min(1, max(0, (y / SS - (cy - 44)) / 74))))
        ramp = ramp.resize((w * SS, h * SS))
        grad = _colorize(ramp, GLYPH_GRAD)
        pic = finish(_over(_mask(grad, soft, 0.34), _mask(grad, lines)))
        # single-hue glows: the palette quantizer turns multi-hue soft alpha into blotches
        if selected:
            glow = _over(_tinted(_blur(pic, 6), VIOLET, 1.4), _tinted(_blur(pic, 2.5), SKY, 1.3))
            core = _over(pic, _tinted(pic, WHITE, 0.35))
            return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), glow, core)
        return _over(Image.new("RGBA", (w, h), (0, 0, 0, 0)), _tinted(_blur(pic, 2.2), SKY, 0.45), pic)

    def system_icon(self, sid, selected):
        label = BADGE_FIX[sid] if sid in BADGE_FIX else BADGES.get(sid, sid[:7].upper())
        out = self.icon_picture(sid, selected, 52 if label else 62)
        out = _over(out, self.badge(label, selected)) if label else out
        return _kquant(out, ICON_COLOURS)

    def badge(self, label, selected):
        w, h = 120, 130
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        f = self.font(13.5)
        tw = d.textlength(label, font=f) / SS
        bw = min(116, tw + 18)
        x0 = (w - bw) / 2
        box = (x0, 105, x0 + bw, 126)
        shape = _rr_mask(w, h, box, 10.5)
        ring = _rr_mask(w, h, box, 10.5, 1.1)
        d.text((S(w / 2), S(115.5)), label, font=f, fill=(232, 250, 246, 255), anchor="mm")
        edge = _mask(_grad(w, h, AURORA, 1, 0), ring) if selected else \
            _mask(_grad(w, h, [(0.78, WHITE + (110,)), (1, WHITE + (40,))]), ring)
        return _over(_mask(_solid(w, h, INK, 150), shape),
                     _mask(_grad(w, h, [(0.78, WHITE + (40,)), (1, WHITE + (16,))]), shape), edge, finish(im))

    def app_icon(self, key):
        n = 96
        box = (5, 5, n - 5, n - 5)
        shape = _rr_mask(n, n, box, 24)
        ring = _rr_mask(n, n, box, 24, 1.4)
        body = _mask(_grad(n, n, [(0, (24, 92, 104)), (0.5, (30, 52, 112)), (1, (66, 40, 140))], 1, 1, 245), shape)
        sheen = _mask(_grad(n, n, [(0, WHITE + (40,)), (0.5, WHITE + (0,)), (1, WHITE + (0,))]), shape)
        art = self.glyph_layer(n, n, lambda d: APPS[key](d, n / 2, n / 2, n * 0.26, (255, 255, 255, 255), n * 0.045),
                               20, 76, True)
        return _dither(_over(Image.new("RGBA", (n, n), (0, 0, 0, 0)), body, sheen,
                             _mask(_grad(n, n, [(0, WHITE + (130,)), (1, WHITE + (40,))]), ring), art))

    def config(self):
        cfg = super().config()
        cfg["grid"]["selectedcolor"] = hexc(SEL_LABEL)
        # MainUI fills its own icons and tiles in this accent under white (4.5:1), next to the violet app icons
        cfg["mainui"]["accent"] = hexc(UI_ACCENT)
        cfg["mainui"]["background"] = hexc(self.sky_mid)
        return cfg

    # ---- sound ----------------------------------------------------------------------------
    def sound(self, path):
        """A short glassy chime: three bell partials with a soft attack."""
        rate = 22050
        frames = bytearray()
        for i in range(int(rate * 0.09)):
            t = i / rate
            env = min(1.0, t / 0.002)
            s = (math.sin(2 * math.pi * 2093 * t) * math.exp(-t * 55)
                 + 0.45 * math.sin(2 * math.pi * 3136 * t) * math.exp(-t * 80)
                 + 0.18 * math.sin(2 * math.pi * 5274 * t) * math.exp(-t * 130))
            frames += struct.pack("<h", int(5200 * env * s))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))
