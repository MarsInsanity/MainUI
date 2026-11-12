"""Cartridge: an 80s home console in ivory plastic, deep red and gold trim."""
import math

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from theme_kit import (APPS, GLYPHS, SS, Pen, S, Style, canvas, finish, hexa, hexc, rgba, vgradient)

IVORY = (239, 230, 210)
CREAM = (250, 246, 236)
IVORY_D = (221, 208, 180)
RED = (164, 30, 34)
RED_HI = (196, 50, 50)
RED_D = (118, 18, 22)
GOLD = (201, 160, 74)
GOLD_HI = (232, 200, 122)
GOLD_D = (148, 110, 44)
INK = (59, 53, 51)
INK_HI = (86, 78, 74)
INK_D = (34, 30, 29)
SHADOW = (88, 60, 34)


# ---- masks and layers (all at supersampled size) -----------------------------------------

def blank(w, h, v=0):
    return Image.new("L", (w * SS, h * SS), v)


def solid(color, mask):
    """[color] where [mask] is set; the colour's alpha scales the mask."""
    im = Image.new("RGBA", mask.size, rgba(color))
    if len(color) > 3 and color[3] != 255:
        mask = mask.point(lambda v, a=color[3]: v * a // 255)
    im.putalpha(mask)
    return im


def layer(base, color, mask):
    return Image.alpha_composite(base, solid(color, mask))


def rr_mask(w, h, box, r, v=255, into=None):
    m = into if into is not None else blank(w, h)
    ImageDraw.Draw(m).rounded_rectangle([S(c) for c in box], radius=S(r), fill=v)
    return m


def circ_mask(w, h, cx, cy, r):
    m = blank(w, h)
    ImageDraw.Draw(m).ellipse([S(cx - r), S(cy - r), S(cx + r), S(cy + r)], fill=255)
    return m


def shift(mask, dx, dy):
    """[mask] moved by (dx, dy) 1x pixels, without wrapping."""
    out = Image.new(mask.mode, mask.size, 0)
    out.paste(mask, (int(S(dx)), int(S(dy))))
    return out


def edge(mask, dx, dy, blur):
    """The band of [mask] that is uncovered when it moves by (dx, dy): a lit or shaded edge."""
    e = ImageChops.subtract(mask, shift(mask, dx, dy))
    if blur:
        e = e.filter(ImageFilter.GaussianBlur(S(blur)))
    return ImageChops.multiply(e, mask)


def drop_shadow(mask, dx, dy, blur, alpha):
    sh = shift(mask, dx, dy).filter(ImageFilter.GaussianBlur(S(blur)))
    return solid(SHADOW + (alpha,), sh)


def ribs(w, h, box, step, mask, dark=60, light=130, width=1.2):
    """Horizontal grip ridges inside [box], clipped to [mask]."""
    lo, hi = blank(w, h), blank(w, h)
    dl, dh = ImageDraw.Draw(lo), ImageDraw.Draw(hi)
    x0, y0, x1, y1 = box
    y = y0
    while y <= y1:
        dl.rectangle([S(x0), S(y), S(x1), S(y + width)], fill=255)
        dh.rectangle([S(x0), S(y + width), S(x1), S(y + 2 * width)], fill=255)
        y += step
    return (ImageChops.multiply(lo, mask).point(lambda v: v * dark // 255),
            ImageChops.multiply(hi, mask).point(lambda v: v * light // 255))


# ---- duotone line art -----------------------------------------------------------------------

class DuoPen(Pen):
    """A Pen drawn [sc] times bigger whose soft fills go to their own mask (a second tone)
    instead of the line layer, and that can punch holes no fill covers."""

    def __init__(self, draw, soft_draw, cut_draw, cx, cy, w=3.4, sc=1.0):
        super().__init__(draw, cx, cy, (255, 255, 255, 255), SS, w)
        self.sd, self.cut, self.sc = soft_draw, cut_draw, sc

    def p(self, x, y):
        return ((self.cx + x * self.sc) * self.ss, (self.cy + y * self.sc) * self.ss)

    def _w(self, w):
        return max(1, int(round((self.w if w is None else w) * self.sc * self.ss)))

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

    # second-tone areas without an outline
    def tone_rr(self, x0, y0, x1, y1, r=3):
        self.sd.rounded_rectangle([*self.p(x0, y0), *self.p(x1, y1)], radius=r * self.sc * self.ss, fill=255)

    def tone_poly(self, pts):
        self.sd.polygon([self.p(x, y) for x, y in pts], fill=255)

    def tone_ci(self, x, y, r):
        self.sd.ellipse([*self.p(x - r, y - r), *self.p(x + r, y + r)], fill=255)

    def hole(self, x, y, r):
        """A see-through hole (no fill) with an outline."""
        self.cut.ellipse([*self.p(x - r, y - r), *self.p(x + r, y + r)], fill=255)
        self.ci(x, y, r)


def enclosed(walls, w, h):
    """Areas fully surrounded by [walls] (SS-size L mask), found at 2x: SS-size L mask."""
    w2, h2 = w * 2, h * 2
    wall = walls.resize((w2, h2), Image.BOX).point(lambda v: 255 if v > 40 else 0)
    free = ImageChops.invert(wall)
    seed = Image.new("L", (w2, h2), 0)
    ImageDraw.Draw(seed).rectangle([0, 0, w2 - 1, h2 - 1], outline=255)
    seed = ImageChops.darker(seed, free)
    grow = ImageFilter.BoxBlur(1)  # (with the threshold below: a fast 3 x 3 dilation)
    while True:
        prev = seed
        for _ in range(12):
            seed = ImageChops.darker(seed.filter(grow).point(lambda v: 255 if v else 0), free)
        if not ImageChops.difference(seed, prev).getbbox():
            break
    inside = ImageChops.subtract(free, seed)
    # (grown a little under the lines so no gap shows between fill and outline)
    inside = inside.filter(ImageFilter.MaxFilter(3))
    return inside.resize((w * SS, h * SS), Image.BILINEAR)


def duo(w, h, cx, cy, draw_fn, ink, fill, soft, stroke=3.4, sc=1.0, holes=None):
    """Line art from [draw_fn](pen) as duotone: [ink] lines over a [fill] body and [soft] details.
    Returns the 1x image. [holes]: colour for pixels drawn in another colour than the pen's."""
    lines = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
    soft_m, cut = blank(w, h), blank(w, h)
    draw_fn(DuoPen(ImageDraw.Draw(lines), ImageDraw.Draw(soft_m), ImageDraw.Draw(cut), cx, cy, stroke, sc))
    la = lines.split()[3]
    other = ImageChops.multiply(la, lines.convert("L").point(lambda v: 255 if v < 128 else 0))
    la = ImageChops.subtract(la.point(lambda v: 255 if v > 1 else 0), other)
    body = ImageChops.subtract(enclosed(ImageChops.add(la, other), w, h), cut)
    soft_m = ImageChops.subtract(soft_m, cut)
    out = layer(Image.new("RGBA", lines.size, (0, 0, 0, 0)), fill, body)
    out = layer(out, soft, soft_m)
    if holes is not None:
        out = layer(out, holes, other)
    out = layer(out, ink, la)
    return finish(out)


# ---- the main menu pictures (drawn with a DuoPen around 0, 0; about 90 x 90) ----------------

def m_recent(p):
    p.rr(-7, -47, 7, -39, 2, fill=True)                    # stopwatch crown
    p.ln((0, -39), (0, -33), w=5)
    p.ln((25, -32), (31, -38), w=5)
    p.ci(0, 0, 34, w=5.5)
    p.arc(0, 0, 25, 200, 330, w=4)                         # dial arc
    for a in range(0, 360, 90):
        x, y = 25 * math.cos(math.radians(a)), 25 * math.sin(math.radians(a))
        p.ci(x, y, 2.6, fill=True)
    p.ln((0, -18), (0, 0), (13, 9), w=5)
    p.ci(0, 0, 4, fill=True)


def m_favorite(p):
    pts = []
    for i in range(10):
        r = 40 if i % 2 == 0 else 18
        a = math.radians(-90 + i * 36)
        pts.append((r * math.cos(a), 3 + r * math.sin(a)))
    p.poly(pts, w=5.5)
    inner = [(x * 0.5, 3 + (y - 3) * 0.5) for x, y in pts]
    p.tone_poly(inner)


def m_game(p):
    p.rr(-46, -20, 46, 22, 7, w=5.5)                       # flat 80s pad
    p.ln((0, -20), (0, -30), (10, -38), w=4)               # cable
    p.tone_rr(-36, -12, -10, 14, 4)                        # d-pad well
    p.rr(-27, -7, -19, 9, 1.5, fill=True)                  # d-pad
    p.rr(-31, -3, -15, 5, 1.5, fill=True)
    p.rr(-6, 2, 2, 7, 2.5, fill=True)                      # select / start
    p.rr(5, 2, 13, 7, 2.5, fill=True)
    p.ci(22, 3, 6, fill=True)                              # B, A
    p.ci(36, 3, 6, fill=True)
    p.ln((-5, -10), (12, -10), w=3)


def m_emulators(p):
    p.rr(-24, -40, 24, 0, 3, w=5)                          # cartridge going in
    p.rr(-15, -34, 15, -14, 2, w=3, soft=True)
    p.rr(-46, -6, 46, 32, 5, w=5.5)                        # console
    p.ln((-46, 8), (46, 8), w=3.5)
    p.rr(-36, 16, -24, 23, 2, fill=True)                   # power / reset
    p.rr(-18, 16, -6, 23, 2, fill=True)
    p.rr(14, 15, 38, 25, 2, w=3, soft=True)                # ports


def m_expert(p):
    rows = ["..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.", "XXXXXXXXXXX",
            "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX..."]
    c = 8.4
    x0, y0 = -c * 5.5, -c * 4
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch == "X":
                xa, ya = x0 + i * c, y0 + j * c
                p.tone_rr(xa + 2.5, ya + 2.5, xa + c + 2.5, ya + c + 2.5, 0)  # offset print shadow
                p.rr(xa + 0.4, ya + 0.4, xa + c - 0.4, ya + c - 0.4, 1, fill=True)


def m_apps(p):
    s = 17
    for dx, dy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        x, y = dx * 21, dy * 21
        if (dx, dy) == (1, -1):
            p.ci(x, y, s, w=5, soft=True)
        else:
            p.rr(x - s, y - s, x + s, y + s, 5, w=5)


def m_setting(p):
    pts = []
    teeth = 8
    for i in range(teeth * 4):
        a = math.radians(i * 360 / (teeth * 4) - 90 + 360 / teeth / 8)
        r = 40 if (i % 4) in (1, 2) else 30
        pts.append((r * math.cos(a), r * math.sin(a)))
    p.poly(pts, w=5)
    p.tone_ci(0, 0, 19)
    p.hole(0, 0, 10)


MENU = {"recent": m_recent, "favorite": m_favorite, "game": m_game, "emulators": m_emulators,
        "retroarch": m_expert, "app": m_apps, "setting": m_setting}


def m_folder(p):
    p.poly([(-20, -14), (-6, -14), (-2, -9), (20, -9), (20, 15), (-20, 15)], w=3.6)
    p.poly([(-20, -2), (20, -2), (20, 15), (-20, 15)], w=3.6, soft=True)


def m_star(p):
    pts = []
    for i in range(10):
        r = 15 if i % 2 == 0 else 7
        a = math.radians(-90 + i * 36)
        pts.append((r * math.cos(a), 1.5 + r * math.sin(a)))
    p.poly(pts, w=2.6)


class Theme(Style):
    name = "Cartridge"
    author = "MainUI"
    description = "An 80s home console in ivory plastic with deep red and gold trim and chunky cartridge tiles."

    bg_top = IVORY
    bg_bottom = IVORY
    accent = RED
    accent2 = INK
    highlight = GOLD
    text = INK
    muted = INK
    glyph_n = INK
    glyph_f = IVORY
    panel = (248, 212, 126)    # 20 % darker it is the gold of the pop-up title strip
    panel_alpha = 248

    font_file = "Bungee-Regular.ttf"
    font_license = "OFL-Bungee.txt"
    list_font, list_license = "Righteous-Regular.ttf", "OFL-Righteous.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 26, 22, 23, 17, 13, 16
    sel_icons = True
    icon_quant = 64

    # ---- big pictures -------------------------------------------------------------------
    def background(self, w=640, h=480):
        im = vgradient(w, h, rgba((244, 237, 222)), rgba((232, 221, 197)))
        ov = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        for y in range(62, h, 4):                          # moulded ribbing
            d.line([(0, y), (w, y)], fill=(255, 255, 255, 70))
            d.line([(0, y + 1), (w, y + 1)], fill=SHADOW + (18,))
        im = Image.alpha_composite(im, ov)
        # soft light from the top left, shade to the bottom right
        for box, col, a in (((-30, -30, 40, 30), (255, 255, 255), 60), ((30, 26, 100, 80), SHADOW, 40)):
            m = Image.new("L", (64, 48), 0)
            ImageDraw.Draw(m).ellipse(box, fill=a)
            im = layer(im, col, m.filter(ImageFilter.GaussianBlur(10)).resize((w, h), Image.BILINEAR))
        ov = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        for i in range(6):                                 # the header's shadow
            d.line([(0, 60 + i), (w, 60 + i)], fill=SHADOW + (50 - i * 8,))
        d.rectangle((0, 411, w, 416), fill=rgba(RED))      # red stripe band over the footer
        d.line([(0, 411), (w, 411)], fill=rgba(RED_HI))
        d.rectangle((0, 418, w, 419), fill=rgba(RED))
        return Image.alpha_composite(im, ov)

    def header_bar(self):
        w, h = 640, 60
        im = vgradient(w, h, rgba((250, 246, 236)), rgba((236, 227, 206)))
        d = ImageDraw.Draw(im)
        d.rectangle((0, 46, w, 47), fill=rgba(GOLD))
        band = vgradient(w, 10, rgba(RED_HI), rgba(RED_D))
        im.alpha_composite(band, (0, 49))
        d.line([(0, 59), (w, 59)], fill=rgba(INK_D, 200))
        return im

    def footer_bar(self):
        w, h = 640, 60
        im = vgradient(w, h, rgba(INK_HI), rgba(INK_D))
        ov = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        for y in range(6, h, 3):                           # rubber texture
            d.line([(0, y), (w, y)], fill=(0, 0, 0, 22))
        d.rectangle((0, 0, w, 2), fill=rgba(GOLD))
        d.line([(0, 3), (w, 3)], fill=(0, 0, 0, 110))
        return Image.alpha_composite(im, ov)

    def logo(self):
        w, h = 150, 42
        m = rr_mask(w, h, (2, 5, 146, 37), 7)
        base = drop_shadow(m, 0, 1.5, 1.2, 90)
        plate = vgradient(w * SS, h * SS, rgba(RED_HI), rgba(RED_D))
        plate.putalpha(m)
        im = Image.alpha_composite(base, plate)
        im = layer(im, (255, 255, 255, 90), edge(m, 0, 1.5, 0.4))
        d = ImageDraw.Draw(im)
        d.rounded_rectangle([S(6), S(9), S(142), S(33)], radius=S(4.5), outline=rgba(GOLD), width=int(S(1.3)))
        f = self.font(18)
        d.text((S(74), S(21.5)), "MAINUI", font=f, fill=rgba(CREAM), anchor="mm")
        half = d.textlength("MAINUI", font=f) / SS / 2 + 7
        for y in (16.5, 21, 25.5):                         # nameplate speed stripes
            d.rectangle([S(12), S(y - 0.9), S(74 - half), S(y + 0.9)], fill=rgba(GOLD))
            d.rectangle([S(74 + half), S(y - 0.9), S(136), S(y + 0.9)], fill=rgba(GOLD))
        return finish(im)

    # ---- lists --------------------------------------------------------------------------
    def selection(self, h):
        w = 640
        box = (6, 4, w - 6, h - 4)
        m = rr_mask(w, h, box, 8)
        im = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        im = Image.alpha_composite(im, drop_shadow(m, 0, 1.5, 1.5, 70))
        fill = vgradient(w * SS, h * SS, rgba(GOLD_HI), rgba((214, 176, 94)))
        fill.putalpha(m)
        im = Image.alpha_composite(im, fill)
        im = layer(im, (255, 255, 255, 120), edge(m, 0, 2, 0.6))
        im = layer(im, GOLD_D + (160,), edge(m, 0, -2, 0.6))
        im = layer(im, RED, rr_mask(w, h, (9, 8, 15, h - 8), 2))
        d = ImageDraw.Draw(im)
        d.rounded_rectangle([S(c) for c in box], radius=S(8), outline=rgba(GOLD_D), width=int(S(1.4)))
        for x in (619, 623.5, 628):                        # grip ridges at the end (in the kept cap)
            d.line([S(x), S(14), S(x), S(h - 14)], fill=rgba(GOLD_D), width=int(S(1.6)))
            d.line([S(x + 1.6), S(14), S(x + 1.6), S(h - 14)], fill=rgba(CREAM, 160), width=int(S(1)))
        return finish(im)

    def divider(self):
        im = Image.new("RGBA", (640, 4), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.line([(16, 1), (623, 1)], fill=SHADOW + (60,))
        d.line([(16, 2), (623, 2)], fill=(255, 255, 255, 170))
        return im

    # ---- cartridges -----------------------------------------------------------------------
    @staticmethod
    def cart_mask(w, h, box, r, chamfer, inset=0):
        """A cartridge outline: angled top corners, rounded bottom ones; [inset] px smaller."""
        x0, y0, x1, y1 = box
        i = inset
        m = rr_mask(w, h, (x0 + i, y0 + i, x1 - i, y1 - i), max(1, r - i))
        c = chamfer + i * 1.414 + 12  # (the cut runs through (x0 + chamfer, y0) and (x0, y0 + chamfer))
        d = ImageDraw.Draw(m)
        d.polygon([(S(x0 - 12), S(y0 - 12)), (S(x0 + c), S(y0 - 12)), (S(x0 - 12), S(y0 + c))], fill=0)
        d.polygon([(S(x1 + 12), S(y0 - 12)), (S(x1 - c), S(y0 - 12)), (S(x1 + 12), S(y0 + c))], fill=0)
        return m

    def cartridge(self, w, h, box, focused, label_box, grip=None, stripe=None, pins=None, r=10, chamfer=11):
        """A chunky plastic cartridge (SS-size RGBA) with a recessed label: ivory, or red when focused.
        Returns it and the label's box."""
        outer = self.cart_mask(w, h, box, r, chamfer)
        inner = self.cart_mask(w, h, box, r, chamfer, 2)
        im = Image.new("RGBA", outer.size, (0, 0, 0, 0))
        if focused:
            im = Image.alpha_composite(im, drop_shadow(outer, 0, 7, 5, 120))
        else:
            im = Image.alpha_composite(im, drop_shadow(outer, 0, 3.5, 2.5, 90))
        im = layer(im, GOLD_D if not focused else RED_D, outer)
        im = layer(im, GOLD if not focused else GOLD_HI, self.cart_mask(w, h, box, r, chamfer, 1))
        top, bot = ((208, 48, 50), (142, 22, 26)) if focused else ((252, 248, 238), (229, 218, 193))
        body = vgradient(w * SS, h * SS, rgba(top), rgba(bot))
        body.putalpha(inner)
        im = Image.alpha_composite(im, body)
        im = layer(im, (255, 255, 255, 150 if focused else 230), edge(inner, 1.5, 2, 1.2))
        im = layer(im, (RED_D if focused else SHADOW) + (110 if focused else 70,), edge(inner, -1.5, -2, 1.5))
        if grip:
            lo, hi = ribs(w, h, grip, 4.5, inner, 130 if focused else 85, 110 if focused else 255, 1.3)
            im = layer(im, (90, 8, 12) if focused else SHADOW, lo)
            im = layer(im, (255, 200, 190) if focused else (255, 255, 255), hi)
        if stripe:
            ya, yb = stripe
            band = ImageChops.multiply(rr_mask(w, h, (box[0], ya, box[2], yb), 0), inner)
            im = layer(im, GOLD if focused else RED, band)
            im = layer(im, (255, 255, 255, 70), ImageChops.multiply(rr_mask(w, h, (box[0], ya, box[2], ya + 1), 0), inner))
            thin = ImageChops.multiply(rr_mask(w, h, (box[0], yb + 1.5, box[2], yb + 3), 0), inner)
            im = layer(im, CREAM if focused else GOLD, thin)
        if pins:
            px0, py0, px1, py1 = pins
            slot = rr_mask(w, h, pins, 3)
            im = layer(im, (255, 255, 255, 160 if focused else 255), shift(slot, 0, 1))
            im = layer(im, INK_D, slot)
            n = int((px1 - px0 - 6) // 6)
            x = (px0 + px1) / 2 - (n * 6 - 2) / 2
            for _ in range(n):
                im = layer(im, GOLD, rr_mask(w, h, (x, py0 + 3, x + 4, py1 - 1.5), 0.8))
                im = layer(im, GOLD_HI, rr_mask(w, h, (x, py0 + 3, x + 1.6, py1 - 1.5), 0.6))
                x += 6
        # recessed label well and the label in it
        well = rr_mask(w, h, label_box, 7)
        im = layer(im, (RED_D + (255,)) if focused else (IVORY_D + (255,)), well)
        im = layer(im, (0, 0, 0, 90) if focused else SHADOW + (80,), edge(well, 0, 2.5, 1))
        im = layer(im, (255, 255, 255, 60 if focused else 200), edge(well, 0, -1.5, 0.6))
        lx0, ly0, lx1, ly1 = label_box
        lab_box = (lx0 + 3, ly0 + 3.5, lx1 - 3, ly1 - 2.5)
        im = layer(im, GOLD if focused else GOLD_HI, rr_mask(w, h, lab_box, 5))
        lab = rr_mask(w, h, (lab_box[0] + 1, lab_box[1] + 1, lab_box[2] - 1, lab_box[3] - 1), 4)
        lab_fill = vgradient(w * SS, h * SS, rgba((152, 27, 31)), rgba((126, 20, 24))) if focused else \
            vgradient(w * SS, h * SS, rgba((254, 251, 244)), rgba((247, 241, 228)))
        lab_fill.putalpha(lab)
        return Image.alpha_composite(im, lab_fill), lab_box

    TILE_BOX = (8, 8, 148, 268)

    def tile_geometry(self, focused):
        dy = -5 if focused else 0
        x0, y0, x1, y1 = self.TILE_BOX
        return dict(box=(x0, y0 + dy, x1, y1 + dy), label_box=(x0 + 8, 55 + dy, x1 - 8, 240 + dy), chamfer=17,
                    grip=(x0 + 18, 16 + dy, x1 - 18, 32 + dy), stripe=(39 + dy, 45 + dy),
                    pins=(x0 + 26, 248 + dy, x1 - 26, 260 + dy))

    def tile_base(self, focused):
        im, _ = self.cartridge(156, 278, focused=focused, **self.tile_geometry(focused))
        return finish(im)

    def glyph_colors(self, focused):
        if focused:
            return rgba(CREAM), rgba(GOLD), rgba(RED_D)
        return rgba(INK), rgba(GOLD_HI), rgba(RED, 230)

    def tile_glyph(self, key, focused):
        ink, fill, soft = self.glyph_colors(focused)
        cy = 114 - (5 if focused else 0)
        g = duo(156, 278, 78, cy, MENU[key], ink, fill, soft)
        if focused:
            g = Image.alpha_composite(solid((60, 6, 8, 120), g.split()[3].filter(ImageFilter.GaussianBlur(2.5))), g)
        return g

    def card(self, w, h, focused):
        """Games grid cell: a short cartridge, the system on its label and the name below."""
        im, _ = self.cartridge(w, h, (6, 6, w - 6, h - 4), focused, (13, 12, w - 13, 134), r=10, chamfer=12)
        return finish(im)

    # ---- buttons and small parts ----------------------------------------------------------
    def button(self, label, primary):
        n = 54
        red = label in "AB"
        im = Image.new("RGBA", (n * SS, n * SS), (0, 0, 0, 0))
        c = n / 2
        ring = blank(n, n)
        ImageDraw.Draw(ring).ellipse([S(c - 24), S(c - 24), S(c + 24), S(c + 24)], fill=255)
        dome = blank(n, n)
        ImageDraw.Draw(dome).ellipse([S(c - 19.5), S(c - 20.5), S(c + 19.5), S(c + 18.5)], fill=255)
        im = layer(im, (0, 0, 0, 110), shift(ring, 0, 1.5).filter(ImageFilter.GaussianBlur(S(1.2))))
        im = layer(im, GOLD_D, ring)
        im = layer(im, GOLD, circ_mask(n, n, c, c - 0.6, 23))
        im = layer(im, INK_D, circ_mask(n, n, c, c, 20.5))
        top, bot = ((214, 58, 56), RED_D) if red else ((96, 88, 84), INK_D)
        g = vgradient(n * SS, n * SS, rgba(top), rgba(bot))
        g.putalpha(dome)
        im = Image.alpha_composite(im, g)
        hi = blank(n, n)
        ImageDraw.Draw(hi).ellipse([S(c - 13), S(c - 17), S(c + 7), S(c - 6)], fill=255)
        im = layer(im, (255, 255, 255, 70), hi.filter(ImageFilter.GaussianBlur(S(2))))
        d = ImageDraw.Draw(im)
        d.text((S(c), S(c)), label, font=self.font(23), fill=rgba(CREAM), anchor="mm")
        return finish(im)

    def pill(self, label, w, h=48):
        im = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        slot = rr_mask(w, h, (1, 5, w - 1, h - 5), (h - 10) / 2)
        im = layer(im, GOLD_D, slot)
        im = layer(im, IVORY_D, rr_mask(w, h, (2.2, 6.2, w - 2.2, h - 6.2), (h - 12.4) / 2))
        im = layer(im, SHADOW + (110,), edge(rr_mask(w, h, (2.2, 6.2, w - 2.2, h - 6.2), (h - 12.4) / 2), 0, 2.5, 1))
        pm = rr_mask(w, h, (6, 10, w - 6, h - 10), (h - 20) / 2)
        g = vgradient(w * SS, h * SS, rgba(INK_HI), rgba(INK_D))
        g.putalpha(pm)
        im = Image.alpha_composite(im, g)
        im = layer(im, (255, 255, 255, 60), edge(pm, 0, 1.5, 0.5))
        d = ImageDraw.Draw(im)
        d.text((S(w / 2), S(h / 2 + 0.5)), label, font=self.font(13), fill=rgba(CREAM), anchor="mm")
        return finish(im)

    def dot(self, active):
        im = Image.new("RGBA", (24 * SS, 14 * SS), (0, 0, 0, 0))
        if active:
            m = rr_mask(24, 14, (2, 2.5, 22, 11.5), 4.5)
            im = layer(im, GOLD_D, m)
            m2 = rr_mask(24, 14, (3.4, 3.9, 20.6, 10.1), 3.2)
            g = vgradient(24 * SS, 14 * SS, rgba(RED_HI), rgba(RED_D))
            g.putalpha(m2)
            im = Image.alpha_composite(im, g)
            im = layer(im, (255, 255, 255, 120), rr_mask(24, 14, (6, 5, 14, 6.6), 1))
        else:
            im = layer(im, (255, 255, 255, 200), circ_mask(24, 14, 12, 7.8, 4.2))
            im = layer(im, INK + (150,), circ_mask(24, 14, 12, 7, 4))
        return finish(im)

    def toggle(self, on):
        """A console's slide switch: a dark slot (red when on) and a ridged ivory knob."""
        w, h = 92, 44
        im = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        slot = rr_mask(w, h, (8, 11, w - 8, h - 11), 6)
        im = layer(im, (255, 255, 255, 200), shift(slot, 0, 1))
        im = layer(im, RED if on else INK, slot)
        im = layer(im, (0, 0, 0, 120), edge(slot, 0, 3, 1))
        kx = w - 8 - 32 if on else 8
        knob = rr_mask(w, h, (kx - 1, 6, kx + 33, h - 6), 5)
        im = Image.alpha_composite(im, drop_shadow(knob, 0, 2, 1.5, 120))
        im = layer(im, GOLD_D, knob)
        kb = rr_mask(w, h, (kx + 0.4, 7.4, kx + 31.6, h - 7.4), 4)
        g = vgradient(w * SS, h * SS, rgba(CREAM), rgba(IVORY_D))
        g.putalpha(kb)
        im = Image.alpha_composite(im, g)
        d = ImageDraw.Draw(im)
        for i in range(4):
            x = kx + 10 + i * 4.5
            d.line([S(x), S(14), S(x), S(h - 14)], fill=rgba(SHADOW, 140), width=int(S(1.4)))
            d.line([S(x + 1.4), S(14), S(x + 1.4), S(h - 14)], fill=(255, 255, 255, 220), width=int(S(1)))
        return finish(im)

    def arrow(self, left, size=24):
        im = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
        s = size
        pts = [(0.30, 0.5), (0.72, 0.2), (0.72, 0.8)] if left else [(0.70, 0.5), (0.28, 0.2), (0.28, 0.8)]
        pts = [(S(x * s), S(y * s)) for x, y in pts]
        tri = Image.new("L", im.size, 0)
        ImageDraw.Draw(tri).polygon(pts, fill=255)
        rim = tri.filter(ImageFilter.MaxFilter(int(S(s / 12)) | 1))
        im = layer(im, (0, 0, 0, 90), shift(rim, 0, s / 24).filter(ImageFilter.GaussianBlur(S(0.6))))
        im = layer(im, CREAM, rim)
        im = layer(im, RED, tri)
        return finish(im)

    def panel_image(self, w, h):
        dialog = h >= 200
        im = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        x0, y0, x1, y1 = (22, 6, w - 22, h - 10) if dialog else (0, 0, w, h)
        r = 9 if dialog else 7
        if dialog:
            im = Image.alpha_composite(im, drop_shadow(rr_mask(w, h, (x0, y0, x1, y1), r), 0, 4, 4, 130))
        im = layer(im, INK_D, rr_mask(w, h, (x0, y0, x1, y1), r))
        im = layer(im, GOLD, rr_mask(w, h, (x0 + 1.5, y0 + 1.5, x1 - 1.5, y1 - 1.5), r - 1))
        im = layer(im, GOLD_HI, rr_mask(w, h, (x0 + 2.2, y0 + 2.2, x1 - 3, y1 - 3), r - 1.5))
        im = layer(im, GOLD, rr_mask(w, h, (x0 + 3, y0 + 3, x1 - 3, y1 - 3), r - 2))
        inner = rr_mask(w, h, (x0 + 5, y0 + 5, x1 - 5, y1 - 5), r - 3)
        g = vgradient(w * SS, h * SS, rgba(CREAM), rgba((242, 234, 216)))
        g.putalpha(inner)
        im = Image.alpha_composite(im, g)
        im = layer(im, SHADOW + (70,), edge(inner, 0, 2, 1))
        if dialog:  # a red band over its title, a gold rule under it
            band = ImageChops.multiply(rr_mask(w, h, (x0, y0, x1, y0 + 26), 0), inner)
            g = Image.new("RGBA", im.size, (0, 0, 0, 0))
            g.alpha_composite(vgradient(w * SS, int(S(26)), rgba(RED_HI), rgba(RED_D)), (0, int(S(y0))))
            g.putalpha(band)
            im = Image.alpha_composite(im, g)
            d = ImageDraw.Draw(im)
            d.rectangle([S(x0 + 5), S(y0 + 27.5), S(x1 - 5), S(y0 + 29)], fill=rgba(GOLD))
            for y in (88, 91):
                d.rectangle([S(w / 2 - 70), S(y), S(w / 2 + 70), S(y + 1.2)], fill=rgba(GOLD))
            d.regular_polygon((S(w / 2), S(89.7), S(5)), 4, fill=rgba(RED))
        return finish(im)

    def folder_icon(self):
        return duo(60, 60, 30, 31, m_folder, rgba(INK), rgba(GOLD_HI), rgba(RED))

    def favorite_mark(self):
        return duo(34, 39, 17, 20, m_star, rgba(INK), rgba(RED), rgba(RED))

    def battery(self, level, charging=False):
        im = canvas(48, 48)
        d = ImageDraw.Draw(im)
        d.rounded_rectangle([S(6), S(15), S(40), S(33)], radius=S(3.5), outline=rgba(INK), width=int(S(2.4)))
        d.rounded_rectangle([S(40), S(20), S(44.5), S(28)], radius=S(1.5), fill=rgba(INK))
        if level > 0:
            n = max(1, int(math.ceil(level * 4 - 0.01)))
            col = rgba(RED) if level <= 0.2 else rgba(INK)
            for i in range(n):
                x = 9.6 + i * 7.1
                d.rounded_rectangle([S(x), S(18.6), S(x + 5.6), S(29.4)], radius=S(1), fill=col)
        if charging:
            bolt = [(26, 9), (16, 26), (23, 26), (20, 39), (32, 21), (25, 21)]
            d.polygon([(S(x), S(y)) for x, y in bolt], fill=rgba(CREAM))
            d.line([(S(x), S(y)) for x, y in bolt + [bolt[0]]], fill=rgba(INK), width=int(S(1.6)), joint="curve")
            inner = [(25.5, 12.5), (18.4, 24.6), (24.8, 24.6), (22.4, 34.4), (30, 22.4), (23.6, 22.4)]
            d.polygon([(S(x), S(y)) for x, y in inner], fill=rgba(GOLD))
        return finish(im)

    def preview_frame(self):
        w, h = 250, 360
        im = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        im = layer(im, INK_D, rr_mask(w, h, (0, 0, w, h), 5))
        im = layer(im, GOLD, rr_mask(w, h, (1.5, 1.5, w - 1.5, h - 1.5), 4))
        im = layer(im, GOLD_HI, rr_mask(w, h, (2.5, 2.5, w - 3.5, h - 3.5), 3.5))
        im = layer(im, INK_D, rr_mask(w, h, (4.5, 4.5, w - 4.5, h - 4.5), 2))
        return finish(im)

    def key(self, focused):
        w, h = 64, 52
        im = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        m = rr_mask(w, h, (2, 2, w - 2, h - 2), 7)
        im = Image.alpha_composite(im, drop_shadow(m, 0, 1.5, 1, 90))
        im = layer(im, GOLD if focused else INK_D, m)
        top = rr_mask(w, h, (4, 3.5, w - 4, h - 7), 6)
        top_c, bot_c = ((206, 50, 50), RED_D) if focused else ((94, 86, 82), (52, 47, 45))
        g = vgradient(w * SS, h * SS, rgba(top_c), rgba(bot_c))
        g.putalpha(top)
        im = Image.alpha_composite(im, g)
        im = layer(im, (255, 255, 255, 60), edge(top, 0, 1.5, 0.5))
        return finish(im)

    def input_box(self):
        w, h = 560, 60
        im = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        im = layer(im, INK, rr_mask(w, h, (2, 2, w - 2, h - 2), 9))
        im = layer(im, GOLD, rr_mask(w, h, (4, 4, w - 4, h - 4), 7))
        inner = rr_mask(w, h, (6.5, 6.5, w - 6.5, h - 6.5), 5)
        im = layer(im, (253, 251, 245), inner)
        im = layer(im, SHADOW + (90,), edge(inner, 0, 3, 1.2))
        return finish(im)

    # ---- system and app icons -----------------------------------------------------------
    def icon_picture(self, sid, selected, cy):
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        if selected:
            ink, fill, soft = rgba(CREAM), rgba(GOLD), rgba(RED_D)
        else:
            ink, fill, soft = rgba(INK), rgba(GOLD_HI), rgba(RED, 200)
        return duo(120, 130, 60, cy, draw, ink, fill, soft, sc=self.icon_scale(sid), holes=fill)

    _scales = {}

    def icon_scale(self, sid):
        """Up to 1.16 times the drawing's size, as long as it stays inside the icon."""
        if sid not in self._scales:
            lines = Image.new("RGBA", (160 * SS, 160 * SS), (0, 0, 0, 0))
            m = blank(160, 160)
            (GLYPHS.get(sid) or GLYPHS["fallback"])(DuoPen(ImageDraw.Draw(lines), ImageDraw.Draw(m), ImageDraw.Draw(m), 80, 80))
            x0, y0, x1, y1 = lines.split()[3].getbbox()
            hx, hy = max(80 - x0 / SS, x1 / SS - 80), max(80 - y0 / SS, y1 / SS - 80)
            self._scales[sid] = min(1.16, 57 / hx, 59 / hy)
        return self._scales[sid]

    def system_icon(self, sid, selected):
        # (no name badge: MainUI writes the name under every system picture, and they carry brand names)
        return self.icon_picture(sid, selected, 67)

    def app_icon(self, key):
        n = 96
        im = Image.new("RGBA", (n * SS, n * SS), (0, 0, 0, 0))
        m = rr_mask(n, n, (5, 4, n - 5, n - 6), 16)
        im = Image.alpha_composite(im, drop_shadow(m, 0, 2.5, 2, 110))
        im = layer(im, GOLD_D, m)
        im = layer(im, GOLD, rr_mask(n, n, (6, 5, n - 6, n - 7), 15))
        inner = rr_mask(n, n, (8.5, 7.5, n - 8.5, n - 9.5), 12.5)
        g = vgradient(n * SS, n * SS, rgba((204, 48, 48)), rgba(RED_D))
        g.putalpha(inner)
        im = Image.alpha_composite(im, g)
        im = layer(im, (255, 255, 255, 70), edge(inner, 0, 2, 0.8))
        gl = canvas(n, n)
        APPS[key](ImageDraw.Draw(gl), n / 2, n / 2 - 1, n * 0.25, rgba(CREAM), n * 0.06)
        sh = gl.split()[3]
        im = layer(im, (60, 6, 8, 150), shift(sh, 0, 2).filter(ImageFilter.GaussianBlur(S(1))))
        im = Image.alpha_composite(im, gl)
        return finish(im)

    # ---- sound and config ----------------------------------------------------------------
    def sound(self, path):
        self.change_sound(path, freq=1046, decay=110, length=0.04, volume=5200, square=True)

    def config(self):
        cfg = super().config()
        cfg["title"]["color"] = hexc(INK)
        cfg["hint"]["color"] = hexc(CREAM)
        cfg["list"].update(font=self.list_font, color=hexc(INK))
        cfg["batteryPercentage"]["color"] = hexc(INK)
        cfg["currentpage"]["color"] = hexc(GOLD_HI)
        cfg["total"]["color"] = hexc(CREAM)
        cfg["grid"]["color"] = hexc(INK)
        cfg["grid"]["selectedcolor"] = hexc(CREAM)
        cfg["mainui"] = {"accent": hexc(RED), "panel": hexa(self.panel, self.panel_alpha), "background": hexc(IVORY)}
        return cfg

    def fonts(self):
        # (build() copies one licence file: the list font's goes along as a "font")
        return super().fonts() + [self.list_license]
