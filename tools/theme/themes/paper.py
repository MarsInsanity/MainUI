"""Paper: a notebook page. Ruled cream paper, navy ink drawings, index cards and a yellow highlighter."""
import math
import random
import struct
import wave
import zlib

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from theme_kit import APPS, GLYPHS, SS, TILES, Pen, Style, canvas, finish, g_folder, hexc, rgba, tint

PAPER = (244, 239, 230)
CARD = (255, 254, 250)
INK = (34, 48, 74)
TEXT = (42, 42, 42)
MUTED = (84, 90, 104)
HL = (255, 228, 92)
RULE = (126, 162, 212)
MARGIN = (222, 104, 98)
RED_INK = (178, 56, 46)
TAPE = (232, 222, 190)
SHADE = (78, 60, 34)
WASH = (194, 213, 238)  # watercolour blue, opaque so it reads the same on white and yellow


def seed_of(*parts):
    return zlib.crc32("/".join(str(p) for p in parts).encode())


def S(v):
    return v * SS


def noise_l(size, seed):
    """Seeded uniform noise (L)."""
    return Image.frombytes("L", size, random.Random(seed).randbytes(size[0] * size[1]))


def grain(im, amount=6, seed=0, density=0.05):
    """Paper grain: sparse light and dark specks (sparse so the PNG stays small), alpha untouched."""
    rgb, a = im.convert("RGB"), im.getchannel("A")
    lo, hi = 256 * density, 256 * (1 - density)
    n = noise_l(im.size, seed).point(lambda v: 128 - amount if v < lo else 128 + amount * 2 // 3 if v >= hi else 128)
    out = ImageChops.add(rgb, Image.merge("RGB", (n, n, n)), 1, -128).convert("RGBA")
    out.putalpha(a)
    return out


def wobble(im, amp, wave_len, seed):
    """Hand-drawn jitter: [im] displaced smoothly by about [amp] px (all in [im]'s pixels)."""
    rnd = random.Random(seed)

    def comps():
        out = []
        for _ in range(3):
            a = rnd.uniform(0, math.tau)
            k = math.tau / (wave_len * rnd.uniform(0.7, 1.4))
            out.append((math.cos(a) * k, math.sin(a) * k, rnd.uniform(0, math.tau)))
        return out

    cx, cy = comps(), comps()

    def f(x, y, cs):
        return amp * sum(math.sin(x * kx + y * ky + p) for kx, ky, p in cs) / 1.7

    st = max(4, int(wave_len / 6))
    xs = list(range(0, im.width, st)) + [im.width]
    ys = list(range(0, im.height, st)) + [im.height]
    pt = {(x, y): (x + f(x, y, cx), y + f(x, y, cy)) for x in xs for y in ys}
    data = []
    for x0, x1 in zip(xs, xs[1:]):
        for y0, y1 in zip(ys, ys[1:]):
            data.append(((x0, y0, x1, y1), pt[x0, y0] + pt[x0, y1] + pt[x1, y1] + pt[x1, y0]))
    return im.transform(im.size, Image.Transform.MESH, data, Image.Resampling.BILINEAR)


class Sketch:
    """Pen strokes on a supersampled canvas (1x coordinates): wobbly, a little pressure."""

    def __init__(self, im, seed):
        self.d = ImageDraw.Draw(im)
        self.rnd = random.Random(seed)

    def path(self, pts, c, w, wob=0.5, taper=True):
        dense = []
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            n = max(1, int(math.hypot(x1 - x0, y1 - y0) / 0.4))
            dense += [(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n) for i in range(n)]
        dense.append(pts[-1])
        p1, p2, p3 = (self.rnd.uniform(0, math.tau) for _ in range(3))
        n = len(dense)
        for i, (x, y) in enumerate(dense):
            a, b = dense[max(0, i - 2)], dense[min(n - 1, i + 2)]
            tx, ty = b[0] - a[0], b[1] - a[1]
            tl = math.hypot(tx, ty) or 1
            s = i * 0.4
            off = wob * (0.6 * math.sin(s / 9 + p1) + 0.4 * math.sin(s / 23 + p2))
            x, y = x - ty / tl * off, y + tx / tl * off
            t = i / max(1, n - 1)
            pr = min(1.0, 0.55 + t * 5, 0.55 + (1 - t) * 5) if taper else 1.0
            r = w * pr * (0.92 + 0.08 * math.sin(s / 13 + p3)) / 2
            self.d.ellipse((S(x - r), S(y - r), S(x + r), S(y + r)), fill=c)

    def loop(self, cx, cy, rx, ry, c, w, over=0.45, a0=None):
        """A pen circle: one loop that overshoots its start, slightly spiralling."""
        a0 = self.rnd.uniform(-2.6, -1.9) if a0 is None else a0
        ph = self.rnd.uniform(0, math.tau)
        n = 120
        pts = []
        for i in range(n + 1):
            t = i / n
            a = a0 + t * (math.tau + over)
            k = (1 + 0.035 * math.sin(2 * a + ph)) * (0.97 + 0.07 * t)
            pts.append((cx + rx * k * math.cos(a), cy + ry * k * math.sin(a)))
        self.path(pts, c, w, wob=0.3)

    def box(self, x0, y0, x1, y1, r, c, w, over=8):
        """A rounded box drawn in one stroke, its end running a little past its start."""
        pts = []

        def corner(cx, cy, a0):
            for i in range(7):
                a = math.radians(a0 + i * 15)
                pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))

        start = (x0 + r + 2, y0 - 0.6)
        pts.append(start)
        corner(x1 - r, y0 + r, -90)
        corner(x1 - r, y1 - r, 0)
        corner(x0 + r, y1 - r, 90)
        corner(x0 + r, y0 + r, 180)
        pts.append((x0 + r + 2 + over, y0 + 0.8))
        self.path(pts, c, w, wob=0.45)

    def blob(self, cx, cy, r, c, lumpy=0.06):
        """A filled, slightly lumpy disc (an ink dot or a dab of marker)."""
        pts = []
        ph = self.rnd.uniform(0, math.tau)
        for i in range(48):
            a = i / 48 * math.tau
            k = 1 + lumpy * math.sin(3 * a + ph) + self.rnd.uniform(-0.03, 0.03)
            pts.append((S(cx + r * k * math.cos(a)), S(cy + r * k * math.sin(a))))
        self.d.polygon(pts, fill=c)


def highlighter(w, h, bands, seed, alpha=0.86, color=HL, slant=0.22, streaky=1.0):
    """Yellow marker strokes (1x RGBA, w x h). bands: (x0, x1, y0, y1) per stroke, drawn in order.

    Rows carry the felt tip's streaks; ends are slanted, ragged and a little darker where the
    marker paused; overlapping strokes build up like real highlighter ink.
    """
    rnd = random.Random(seed)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for (x0, x1, y0, y1) in bands:
        lay = Image.new("L", (w * SS, h * SS), 0)
        d = ImageDraw.Draw(lay)
        rows = int((y1 - y0) * SS)
        streak = 1.0
        sl = (y1 - y0) * slant
        e0, e1 = rnd.uniform(0, 2), rnd.uniform(0, 2)
        for j in range(rows):
            t = j / max(1, rows - 1)
            if j % SS == 0:
                streak = 1 - streaky * (0.12 * rnd.random() if rnd.random() < 0.8 else 0.28)
            edge = min(1.0, t * 7, (1 - t) * 7)  # soft top / bottom
            a = alpha * streak * (0.65 + 0.35 * edge)
            xa = x0 + sl * (1 - t) + e0 + rnd.uniform(-0.6, 0.6) + (rnd.uniform(0, 4) if rnd.random() < 0.08 else 0)
            xb = x1 - sl * t - e1 + rnd.uniform(-0.6, 0.6) - (rnd.uniform(0, 4) if rnd.random() < 0.08 else 0)
            y = y0 * SS + j
            d.line((S(xa), y, S(xb), y), fill=int(255 * a))
            # pooled ink where the tip landed and lifted
            d.line((S(xa), y, S(xa + 2.2), y), fill=int(255 * min(1, a + 0.1)))
            d.line((S(xb - 2.2), y, S(xb), y), fill=int(255 * min(1, a + 0.1)))
        m = lay.resize((w, h), Image.LANCZOS)
        band = Image.new("RGBA", (w, h), rgba(color, 255))
        band.putalpha(m)
        out = Image.alpha_composite(out, band)
    return out


def card_layer(w, h, box, r=3, lift=1.0, fill=CARD, crisp=False):
    """A sheet of card with a soft shadow under it (1x RGBA). crisp: a short, hard shadow that
    survives palette quantization."""
    x0, y0, x1, y1 = box
    m = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle([S(x0), S(y0), S(x1), S(y1)], radius=S(r), fill=255)
    m = m.resize((w, h), Image.LANCZOS)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sh = Image.new("RGBA", (w, h), rgba(SHADE, 0))
    if crisp:
        sh.putalpha(m.point(lambda v: v * 60 // 255))
        out.alpha_composite(sh.filter(ImageFilter.GaussianBlur(0.6)), (1, 2))
    else:
        sh.putalpha(m.point(lambda v: v * (52 + 18 * lift) // 255))
        sh = sh.filter(ImageFilter.GaussianBlur(2.2 + 2.2 * lift))
        out.alpha_composite(sh, (int(round(0.6 * lift)), int(round(1.5 + 2 * lift))))
    body = Image.new("RGBA", (w, h), rgba(fill))
    body.putalpha(m)
    out = Image.alpha_composite(out, body)
    e = canvas(w, h)
    ImageDraw.Draw(e).rounded_rectangle([S(x0), S(y0), S(x1), S(y1)], radius=S(r), outline=(150, 132, 104, 70), width=int(S(1)))
    return Image.alpha_composite(out, finish(e))


def rules(w, h, ys, x0, x1, color=RULE, a=150, width=1.1, red=None):
    """Printed lines: blue at each y in ys, a red one at [red]."""
    im = canvas(w, h)
    d = ImageDraw.Draw(im)
    for y in ys:
        d.line((S(x0), S(y), S(x1), S(y)), fill=rgba(color, a), width=int(S(width)))
    if red is not None:
        d.line((S(x0), S(red), S(x1), S(red)), fill=rgba(MARGIN, 190), width=int(S(1.3)))
    return finish(im)


def tape(w, h, cx, cy, tw, th, angle, seed, color=TAPE, a=200):
    """A strip of masking tape with torn, zigzag ends (1x RGBA)."""
    rnd = random.Random(seed)
    big = Image.new("RGBA", (int(tw + 8) * SS, int(th + 8) * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    x0, x1, y0, y1 = 4, 4 + tw, 4, 4 + th
    pts = []
    n = max(2, int(th / 4))
    for i in range(n + 1):  # right end, top to bottom
        pts.append((x1 - (rnd.uniform(1.2, 2.4) if i % 2 else 0), y0 + th * i / n))
    for i in range(n + 1):  # left end, bottom to top
        pts.append((x0 + (rnd.uniform(1.2, 2.4) if i % 2 else 0), y1 - th * i / n))
    d.polygon([(S(x), S(y)) for x, y in pts], fill=rgba(color, a))
    big = big.rotate(angle, Image.BICUBIC, expand=True)
    big = big.resize((big.width // SS, big.height // SS), Image.LANCZOS)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.alpha_composite(big, (int(round(cx - big.width / 2)), int(round(cy - big.height / 2))))
    return out


class InkPen(Pen):
    """Pen with every stroke a little heavier, like a felt-tip fineliner."""
    k = 1.22

    def _w(self, w):
        return max(1, int(round((self.w if w is None else w * self.k) * self.ss)))


class Theme(Style):
    name = "Paper"
    author = "MainUI"
    description = "A notebook page: ruled cream paper, navy ink drawings, index cards and a yellow highlighter."

    bg_top = PAPER
    bg_bottom = PAPER
    accent = INK
    accent2 = RED_INK
    highlight = HL
    text = TEXT
    muted = MUTED
    glyph_n = INK
    glyph_f = INK
    panel = (255, 253, 247)
    panel_alpha = 248
    bar = CARD
    bar_alpha = 255

    font_file = "PatrickHand-Regular.ttf"
    font_license = "OFL-PatrickHand.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 32, 28, 26, 26, 19, 20
    icon_quant = 96

    # ---- page --------------------------------------------------------------------------
    def background(self, w=640, h=480):
        rnd = random.Random(7)
        im = Image.new("RGBA", (w, h), rgba(PAPER))
        # uneven fibre density, very faint
        blot = noise_l((w // 20, h // 20), 11).filter(ImageFilter.GaussianBlur(1)).resize((w, h), Image.BICUBIC)
        blot = blot.point(lambda v: int(128 + (v - 128) * 0.06))
        im = ImageChops.add(im.convert("RGB"), Image.merge("RGB", (blot,) * 3), 1, -128).convert("RGBA")
        lay = canvas(w, h)
        d = ImageDraw.Draw(lay)
        for y in range(72, h, 30):
            d.line((0, S(y), S(w), S(y)), fill=rgba(RULE, 120), width=int(S(1.1)))
        d.line((S(13), 0, S(13), S(h)), fill=rgba(MARGIN, 150), width=int(S(1.3)))
        for _ in range(140):  # stray fibres
            x, y = rnd.uniform(0, w), rnd.uniform(0, h)
            a, ln = rnd.uniform(0, math.tau), rnd.uniform(3, 9)
            c = (255, 255, 255, 40) if rnd.random() < 0.5 else rgba(SHADE, 16)
            d.line((S(x), S(y), S(x + ln * math.cos(a)), S(y + ln * math.sin(a))), fill=c, width=int(S(0.6)))
        im = Image.alpha_composite(im, finish(lay))
        # warmer, slightly aged corners
        v = Image.new("L", (w // 8, h // 8), 0)
        ImageDraw.Draw(v).ellipse((-w // 40, -h // 40, w // 8 + w // 40, h // 8 + h // 40), fill=255)
        v = v.filter(ImageFilter.GaussianBlur(6)).resize((w, h), Image.BICUBIC)
        edge = Image.new("RGBA", (w, h), rgba((176, 146, 98), 0))
        edge.putalpha(v.point(lambda p: (255 - p) * 16 // 255))
        im = Image.alpha_composite(im, edge)
        return grain(im, 7, 3, 0.06)

    def _strip(self, top_edge, seed):
        """A paper strip across the screen: torn at the bottom (header) or along a perforation (footer)."""
        w, h = 640, 60
        rnd = random.Random(seed)
        pts = []
        if top_edge:
            base = 8
            for i in range(0, w + 4, 4):
                pts.append((i, base - (1.6 if (i // 4) % 2 else 0) - rnd.uniform(0, 0.5)))
            pts += [(w, h), (0, h)]
        else:
            y, base = 0.0, 52
            for i in range(0, w + 3, 3):
                y = max(-2.6, min(2.6, y + rnd.uniform(-1.1, 1.1)))
                pts.append((i, base + y + rnd.uniform(-0.6, 0.6)))
            pts += [(w, 0), (0, 0)]
        m = Image.new("L", (w * SS, h * SS), 0)
        ImageDraw.Draw(m).polygon([(S(x), S(y)) for x, y in pts], fill=255)
        m = m.resize((w, h), Image.LANCZOS)
        out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        sh = Image.new("RGBA", (w, h), rgba(SHADE, 0))
        sh.putalpha(m.point(lambda v: v * 70 // 255))
        sh = sh.filter(ImageFilter.GaussianBlur(2.5))
        out.alpha_composite(sh, (0, 2 if not top_edge else -1))
        body = grain(Image.new("RGBA", (w, h), rgba(CARD)), 4, seed, 0.03)
        body.putalpha(m)
        out = Image.alpha_composite(out, body)
        if not top_edge:  # torn fibres: a whiter, ragged lip under the edge
            lip = canvas(w, h)
            d = ImageDraw.Draw(lip)
            for x, y in pts[:-2]:
                d.line((S(x), S(y - 0.5), S(x + rnd.uniform(-1, 1)), S(y + rnd.uniform(0.6, 1.8))), fill=(255, 255, 255, 170), width=int(S(1)))
            out = Image.alpha_composite(out, finish(lip))
        else:  # the perforation line it was torn from
            dl = canvas(w, h)
            d = ImageDraw.Draw(dl)
            for x in range(6, w, 9):
                d.ellipse((S(x - 1.1), S(12.6), S(x + 1.1), S(14.8)), fill=rgba(SHADE, 40))
            out = Image.alpha_composite(out, finish(dl))
        return out

    def header_bar(self):
        return self._strip(False, 21)

    def footer_bar(self):
        return self._strip(True, 22)

    def logo(self):
        w, h = 160, 42
        im = canvas(w, h)
        sk = Sketch(im, 5)
        d = sk.d
        # paper plane
        c = rgba(INK)
        sk.path([(4, 22), (30, 9), (17, 31), (13, 24), (4, 22)], c, 2.2, taper=False)
        sk.path([(13, 24), (30, 9)], c, 1.8)
        sk.path([(13, 24), (13, 30), (17, 27)], c, 1.8)
        f = self.font(30)
        tw = d.textlength("Main", font=f) / SS
        end = 37 + tw + d.textlength("UI", font=f) / SS
        hl = highlighter(w, h, [(33, end + 5, 21, 35)], 3, 0.85, slant=0.3)
        d.text((S(37), S(21)), "Main", font=f, fill=rgba(INK), anchor="lm")
        d.text((S(37 + tw + 1), S(21)), "UI", font=f, fill=rgba(RED_INK), anchor="lm")
        return Image.alpha_composite(hl, finish(im))

    # ---- lists -------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        pad = 8 if h < 70 else 10
        return highlighter(w, h, [(5, 635, pad, h - pad)], seed_of("sel", h), 0.9)

    def divider(self):
        return Image.new("RGBA", (640, 4), (0, 0, 0, 0))

    # ---- cards -------------------------------------------------------------------------
    def _wash(self, w, h, box, seed):
        """Highlighter strokes across a card, a little inside its edges."""
        x0, y0, x1, y1 = box
        rnd = random.Random(seed)
        n = max(1, round((y1 - y0) / 48))
        bh = (y1 - y0 - 8) / n
        bands = []
        for i in range(n):
            a, b = y0 + 4 + i * bh - (2.5 if i else 0), y0 + 4 + (i + 1) * bh + (2.5 if i < n - 1 else 0)
            bands.append((x0 + 4 + rnd.uniform(-1, 1.5), x1 - 4 - rnd.uniform(-1, 1.5), a, b))
        return highlighter(w, h, bands, seed, 0.8, slant=0.07, streaky=0.5)

    def card(self, w, h, focused):
        box = (6, 5, w - 6, h - 7)
        im = card_layer(w, h, box, 3, 1.8 if focused else 1.0)
        if focused:
            im = Image.alpha_composite(im, self._wash(w, h, box, seed_of("wash", w, h)))
        return im

    def tile_base(self, focused):
        w, h = 156, 278
        box = (8, 8, 148, 266)
        im = card_layer(w, h, box, 3, 1.9 if focused else 1.0)
        im = Image.alpha_composite(im, rules(w, h, [206.5, 232.5], box[0] + 1, box[2] - 1, a=140, red=34))
        if focused:
            im = Image.alpha_composite(im, self._wash(w, h, box, seed_of("tilewash")))
        return im

    def _tile_wash(self, d, key, cx, cy, s):
        """The colour under each menu drawing (same geometry as make_mainui_theme's glyphs)."""
        c = rgba(WASH)

        def pg(pts):
            d.polygon([(S(x), S(y)) for x, y in pts], fill=c)

        if key == "favorite":
            pg([(cx + (s if i % 2 == 0 else s * 0.46) * math.cos(math.radians(-90 + i * 36)),
                 cy + (s if i % 2 == 0 else s * 0.46) * math.sin(math.radians(-90 + i * 36))) for i in range(10)])
        elif key == "game":
            pg([(cx + s * x, cy + s * y) for x, y in ((-1, -0.25), (-0.75, -0.55), (0.75, -0.55), (1, -0.25), (1.05, 0.45),
                                                      (0.75, 0.62), (0.45, 0.3), (-0.45, 0.3), (-0.75, 0.62), (-1.05, 0.45))])
        elif key == "emulators":
            d.rounded_rectangle([S(cx - s * 0.62), S(cy - s), S(cx + s * 0.62), S(cy + s)], radius=S(s * 0.18), fill=c)
        elif key == "app":
            h = s * 0.36
            for dx in (-1, 1):
                for dy in (-1, 1):
                    x, y = cx + dx * s * 0.52, cy + dy * s * 0.52
                    d.rounded_rectangle([S(x - h), S(y - h), S(x + h), S(y + h)], radius=S(h * (1 if (dx, dy) == (1, -1) else 0.35)), fill=c)
        elif key == "retroarch":
            d.ellipse([S(cx - s * 0.95), S(cy - s * 0.78), S(cx + s * 0.95), S(cy + s * 0.78)], fill=c)
        else:  # recent, setting: round
            d.ellipse([S(cx - s * 0.9), S(cy - s * 0.9), S(cx + s * 0.9), S(cy + s * 0.9)], fill=c)

    def tile_glyph(self, key, focused):
        cx, cy, s = 78, 114, 42
        wash = canvas(156, 278)
        self._tile_wash(ImageDraw.Draw(wash), key, cx + 3, cy + 3, s)
        im = canvas(156, 278)
        TILES[key](ImageDraw.Draw(im), cx, cy, s, rgba(INK), 6.2)
        im = Image.alpha_composite(wobble(wash, S(1.2), S(40), seed_of("wash", key)), wobble(im, S(0.9), S(30), seed_of("tile", key)))
        return finish(im)

    # ---- buttons and small parts -----------------------------------------------------------
    def button(self, label, primary):
        n = 54 if label in "AB" else 44
        im = canvas(n, n)
        sk = Sketch(im, seed_of("btn", label))
        r = 18.5
        if primary:
            sk.blob(n / 2 + 0.5, n / 2 + 0.5, r - 1.5, rgba(HL, 235))
        sk.loop(n / 2, n / 2, r, r * 0.94, rgba(INK), 2.4)
        sk.d.text((S(n / 2), S(n / 2 + 0.5)), label, font=self.font(25), fill=rgba(INK), anchor="mm")
        return finish(im)

    def pill(self, label, w, h=48):
        im = canvas(w, h)
        sk = Sketch(im, seed_of("pill", label))
        sk.box(4, 11, w - 4, h - 11, 6, rgba(INK), 2.0)
        sk.d.text((S(w / 2), S(h / 2 + 0.5)), label, font=self.font(20), fill=rgba(INK), anchor="mm")
        return finish(im)

    def dot(self, active):
        im = canvas(24, 14)
        sk = Sketch(im, seed_of("dot", active))
        if active:
            sk.blob(12, 7, 5.4, rgba(INK))
        else:
            sk.loop(12, 7, 3.4, 3.4, rgba(INK, 150), 1.4, over=0.3)
        return finish(im)

    def toggle(self, on):
        w, h = 92, 44
        im = canvas(w, h)
        sk = Sketch(im, seed_of("toggle", on))
        if on:
            im.alpha_composite(highlighter(w, h, [(10, 82, 11, 33)], 4, 0.95).resize((w * SS, h * SS), Image.LANCZOS))
        sk.box(10, 11, 82, 33, 11, rgba(INK), 2.2, over=10)
        if on:
            sk.blob(70, 22, 8.2, rgba(INK))
        else:
            sk.loop(22, 22, 7.5, 7.5, rgba(INK, 200), 2.0)
        return finish(im)

    def arrow(self, left, size=24):
        """Ink chevrons. MainUI also lays them on dark bars (game switcher, save slots), so the
        small one has a paper-white rim and the big one sits on a paper disc."""
        im = canvas(size, size)
        sk = Sketch(im, seed_of("arrow", left, size))
        k = 0.62 if size > 24 else 1.0
        xs = [0.5 + (v - 0.5) * k for v in ((0.62, 0.34) if left else (0.38, 0.66))]
        ys = [0.5 + (v - 0.5) * k for v in (0.2, 0.52, 0.8)]
        pts = [(size * xs[0], size * ys[0]), (size * xs[1], size * ys[1]), (size * xs[0], size * ys[2])]
        if size > 24:
            sk.blob(size / 2, size / 2 + 0.5, size / 2 - 2, rgba(SHADE, 60))
            sk.blob(size / 2, size / 2 - 0.5, size / 2 - 2.5, rgba(CARD))
            sk.loop(size / 2, size / 2 - 0.5, size / 2 - 3.5, size / 2 - 3.5, rgba(INK), 1.6, over=0.3)
            sk.path(pts, rgba(INK), 3.2, wob=0.2)
            return finish(im)
        sk.path(pts, rgba(INK), size * 0.14, wob=0.3)
        ink = finish(im)
        rim = tint(ink, rgba(CARD, 235)).filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(0.6))
        return Image.alpha_composite(rim, ink)

    def panel_image(self, w, h):
        if w == 400:  # pop-up menu: MainUI puts its title bar right on top, so the card is full width
            box = (1, 0, w - 1, h - 6)
            im = card_layer(w, h, box, 2, 1.0, fill=self.panel)
            ys = [5 + 60 * i + 45.5 for i in range(max(1, (h - 10) // 60))]
            im = Image.alpha_composite(im, rules(w, h, ys, box[0] + 1, box[2] - 1, a=120))
            ln = canvas(w, h)
            ImageDraw.Draw(ln).line((S(13), S(box[1]), S(13), S(box[3])), fill=rgba(MARGIN, 150), width=int(S(1.2)))
            return Image.alpha_composite(im, finish(ln))
        box = (16, 14, w - 16, h - 12)
        im = card_layer(w, h, box, 3, 1.6, fill=self.panel)
        # under the dialog's title and its message lines (Gfx.dialog / paragraph, list font)
        ys = [104.5 + 32.5 * i for i in range(6) if 104.5 + 32.5 * i < h - 24]
        im = Image.alpha_composite(im, rules(w, h, ys, box[0] + 1, box[2] - 1, a=110, red=75))
        im = Image.alpha_composite(im, tape(w, h, 34, 19, 42, 14, 32, seed_of("tape", w, h, 1)))
        return Image.alpha_composite(im, tape(w, h, w - 34, 19, 42, 14, -30, seed_of("tape", w, h, 2)))

    def folder_icon(self):
        im = canvas(60, 60)
        d = ImageDraw.Draw(im)
        s = 19
        cx, cy = 30, 31
        body = [(cx - s, cy + s * 0.7), (cx - s, cy - s * 0.65), (cx - s * 0.3, cy - s * 0.65),
                (cx - s * 0.1, cy - s * 0.4), (cx + s, cy - s * 0.4), (cx + s, cy + s * 0.7)]
        d.polygon([(S(x + 1.2), S(y + 1)) for x, y in body], fill=rgba((230, 196, 128), 230))
        g_folder(d, cx, cy, s, rgba(INK), 3.2)
        return finish(wobble(im, S(0.6), S(20), 3))

    def favorite_mark(self):
        im = canvas(34, 39)
        pts = []
        for i in range(11):
            r = 14 if i % 2 == 0 else 6.2
            a = math.radians(-92 + i * 36)
            pts.append((17 + r * math.cos(a), 20 + r * math.sin(a)))
        d = ImageDraw.Draw(im)
        d.polygon([(S(x + 0.8), S(y + 0.8)) for x, y in pts[:-1]], fill=rgba(HL, 230))
        Sketch(im, 9).path(pts + [(pts[1][0] * 0.5 + pts[0][0] * 0.5, pts[1][1] * 0.5 + pts[0][1] * 0.5)], rgba(RED_INK), 2.4, wob=0.25, taper=False)
        return finish(im)

    def battery(self, level, charging=False):
        im = canvas(48, 48)
        sk = Sketch(im, seed_of("bat", level, charging))
        col = rgba(RED_INK) if level <= 0.2 else rgba(INK)
        if level > 0:
            x1 = 10 + 25 * level
            for x in range(10, int(x1) + 1, 3):  # pen shading
                sk.path([(x, 29), (min(x1, x + 4), 18.5)], col, 1.6, wob=0.15, taper=False)
        sk.box(6, 15, 40, 33, 3, rgba(INK), 2.3, over=6)
        sk.path([(42.5, 20.5), (42.5, 27.5)], rgba(INK), 2.6, taper=False)
        if charging:
            bolt = [(26, 9), (16, 26), (23, 26), (19, 39), (32, 20), (25, 20), (29, 9)]
            ImageDraw.Draw(im).polygon([(S(x), S(y)) for x, y in bolt], fill=rgba(HL))
            sk.path(bolt + [bolt[0]], rgba(INK), 1.6, wob=0.1, taper=False)
        return finish(im)

    def preview_frame(self):
        """A photo print's white border: MainUI lays the picture over all but its outer 6 px."""
        w, h = 250, 360
        im = card_layer(w, h, (1, 1, w - 2, h - 3), 2, crisp=True)
        e = canvas(w, h)
        Sketch(e, 4).box(1.5, 1.5, w - 2.5, h - 3.5, 2, rgba(INK, 150), 1.2, over=14)
        return Image.alpha_composite(im, finish(e))

    def key(self, focused):
        """Typewriter keys: MainUI writes key labels in white, so the keys are inked."""
        w, h = 64, 52
        im = canvas(w, h)
        d = ImageDraw.Draw(im)
        box = (5, 4, w - 5, h - 7)
        d.rounded_rectangle([S(v + (1 if i % 2 == 0 else 2.5)) for i, v in enumerate(box)], radius=S(9), fill=rgba(SHADE, 70))
        d.rounded_rectangle([S(v) for v in box], radius=S(9), fill=rgba(RED_INK if focused else INK))
        d.rounded_rectangle([S(v) for v in (8, 7, w - 8, h - 10)], radius=S(6.5), outline=(255, 255, 255, 46), width=int(S(1.2)))
        if focused:
            d.rounded_rectangle([S(v) for v in (2, 1, w - 2, h - 4)], radius=S(11), outline=rgba(HL), width=int(S(2.6)))
        return finish(wobble(im, S(0.5), S(18), seed_of("key", focused)))

    def input_box(self):
        w, h = 560, 60
        im = card_layer(w, h, (4, 4, w - 4, h - 7), 3, 0.8)
        return Image.alpha_composite(im, rules(w, h, [41.5], 16, w - 16, a=170))

    # ---- system and app icons ------------------------------------------------------------
    def _glyph_layers(self, draw, cx, cy, k):
        """(ink, wash mask) of a system drawing [k] times its usual size, centred on (cx, cy).

        The soft (duotone) fills go to their own layer, so they can sit under the ink as a wash.
        """
        out = []
        for c, soft in ((rgba(INK), (0, 0, 0, 0)), ((0, 0, 0, 0), (255, 255, 255, 255))):
            im = canvas(120, 130)
            p = InkPen(ImageDraw.Draw(im), cx / k, cy / k, c, SS * k, w=3.4 * InkPen.k)
            p.soft_c = soft
            draw(p)
            out.append(im)
        return out

    def icon_picture(self, sid, selected, cy):
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        # as big as fits: up to 12% larger than the drawings are made, with room for the wobble
        x0, y0, x1, y1 = (v / SS for v in self._glyph_layers(draw, 60, cy, 1.0)[0].getchannel("A").getbbox())
        k = min(1.12, 54 / max(60 - x0, x1 - 60), (cy - 6) / (cy - y0), (124 - cy) / (y1 - cy))
        ink, wash = self._glyph_layers(draw, 60, cy, k)
        sd = seed_of("icon", sid)
        ink = finish(wobble(ink, S(0.7), S(26), sd))
        m = finish(wobble(wash, S(0.7), S(26), sd)).getchannel("A")
        m = m.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5))  # close where outlines cut it
        fill = Image.new("RGBA", ink.size, rgba(WASH, 0))
        fill.putalpha(m)
        out = Image.new("RGBA", ink.size, (0, 0, 0, 0))
        out.alpha_composite(fill, (1, 1))  # coloured a little off the lines, by hand
        return Image.alpha_composite(out, ink)

    def system_icon(self, sid, selected):
        # No name badge: MainUI writes the name under every icon, and badges would spell brands.
        return self.icon_picture(sid, selected, 64)

    def app_icon(self, key):
        n = 96
        im = card_layer(n, n, (8, 8, n - 9, n - 10), 3, crisp=True)
        cols = [(150, 190, 230), (236, 150, 140), (150, 206, 170), (244, 214, 120), (196, 170, 224)]
        col = cols[seed_of("appc", key) % len(cols)]
        im = Image.alpha_composite(im, tape(n, n, n / 2, 10, 40, 13, -4 + seed_of("ta", key) % 9, seed_of("apptape", key), col, 210))
        g = canvas(n, n)
        APPS[key](ImageDraw.Draw(g), n / 2, n / 2 + 3, n * 0.24, rgba(INK), 4.4)
        g = finish(wobble(g, S(0.6), S(22), seed_of("appg", key)))
        # wash inside the drawing's closed shapes
        m = g.getchannel("A").point(lambda v: 255 if v > 60 else 0)
        ImageDraw.floodfill(m, (0, 0), 128)
        m = m.point(lambda v: 255 if v == 0 else 0).filter(ImageFilter.MaxFilter(3))
        wash = Image.new("RGBA", (n, n), rgba(WASH, 0))
        wash.putalpha(m)
        im.alpha_composite(wash, (2, 2))
        return Image.alpha_composite(im, g)

    # ---- sound ----------------------------------------------------------------------------
    def sound(self, path):
        """A soft pencil tap: a short burst of filtered noise."""
        rate = 22050
        rnd = random.Random(5)
        frames = bytearray()
        lp = 0.0
        for i in range(int(rate * 0.03)):
            t = i / rate
            lp += 0.35 * (rnd.uniform(-1, 1) - lp)
            s = lp * 0.7 + 0.3 * math.sin(2 * math.pi * 900 * t)
            frames += struct.pack("<h", int(14000 * math.exp(-t * 160) * s))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))

    # ---- config ---------------------------------------------------------------------------
    def config(self):
        cfg = super().config()
        cfg["currentpage"] = {"color": hexc(RED_INK)}
        return cfg
