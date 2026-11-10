"""One line-art drawing per game system for the MainUI theme.

Every system MainUI knows gets its own picture: the console, handheld or
computer itself (or, for game engines and ports, something that stands for
them), so no two systems share an icon. Only generic shapes are drawn, no
logos. Coordinates are in icon pixels relative to the drawing's center; the
drawing area is about 104 x 90.

Used by make_mainui_theme.py.
"""
import math

GLYPHS = {}


def glyph(*ids):
    def deco(f):
        for i in ids:
            GLYPHS[i] = f
        return f
    return deco


class Pen:
    """Thin drawing helper: relative coordinates, supersampled output."""

    def __init__(self, draw, cx, cy, color, ss, w=3.4):
        self.d, self.cx, self.cy, self.c, self.ss, self.w = draw, cx, cy, color, ss, w
        self.soft_c = color[:3] + (int(color[3] * 0.30),)

    def p(self, x, y):
        return ((self.cx + x) * self.ss, (self.cy + y) * self.ss)

    def _w(self, w):
        return max(1, int(round((self.w if w is None else w) * self.ss)))

    # ---- shapes -----------------------------------------------------------------------
    def rr(self, x0, y0, x1, y1, r=3, w=None, fill=False, soft=False):
        r = min(r, abs(x1 - x0) / 2, abs(y1 - y0) / 2)
        box = [*self.p(x0, y0), *self.p(x1, y1)]
        if fill or soft:
            self.d.rounded_rectangle(box, radius=r * self.ss, fill=self.c if fill else self.soft_c)
        if not fill:
            self.d.rounded_rectangle(box, radius=r * self.ss, outline=self.c, width=self._w(w))

    def ln(self, *pts, w=None):
        pts = [self.p(x, y) for x, y in pts]
        self.d.line(pts, fill=self.c, width=self._w(w), joint="curve")
        rad = self._w(w) / 2
        for x, y in (pts[0], pts[-1]):
            self.d.ellipse((x - rad, y - rad, x + rad, y + rad), fill=self.c)

    def poly(self, pts, w=None, fill=False, soft=False):
        if fill or soft:
            self.d.polygon([self.p(x, y) for x, y in pts], fill=self.c if fill else self.soft_c)
        if not fill:
            self.ln(*(list(pts) + [pts[0]]), w=w)

    def ci(self, x, y, r, w=None, fill=False, soft=False):
        box = [*self.p(x - r, y - r), *self.p(x + r, y + r)]
        if fill or soft:
            self.d.ellipse(box, fill=self.c if fill else self.soft_c)
        if not fill:
            self.d.ellipse(box, outline=self.c, width=self._w(w))

    def el(self, x0, y0, x1, y1, w=None, fill=False, soft=False):
        box = [*self.p(x0, y0), *self.p(x1, y1)]
        if fill or soft:
            self.d.ellipse(box, fill=self.c if fill else self.soft_c)
        if not fill:
            self.d.ellipse(box, outline=self.c, width=self._w(w))

    def arc(self, x, y, r, a0, a1, w=None):
        self.d.arc([*self.p(x - r, y - r), *self.p(x + r, y + r)], a0, a1, fill=self.c, width=self._w(w))

    def ellarc(self, x0, y0, x1, y1, a0, a1, w=None):
        self.d.arc([*self.p(x0, y0), *self.p(x1, y1)], a0, a1, fill=self.c, width=self._w(w))

    # ---- details ----------------------------------------------------------------------
    def dpad(self, x, y, s, w=None):
        w = w or self.w * 0.8
        self.ln((x - s, y), (x + s, y), w=w)
        self.ln((x, y - s), (x, y + s), w=w)

    def dots(self, pts, r=2.2):
        for x, y in pts:
            self.ci(x, y, r, fill=True)

    def ab(self, x, y, r=3.2, dx=8, dy=-5):
        """Two face buttons, the second up and to the right."""
        self.dots([(x, y), (x + dx, y + dy)], r)

    def diamond(self, x, y, s=5, r=2.0, filled=True):
        for dx, dy in ((0, -s), (s, 0), (0, s), (-s, 0)):
            if filled:
                self.ci(x + dx, y + dy, r, fill=True)
            else:
                self.ci(x + dx, y + dy, r, w=1.4)

    def keys(self, x0, y0, x1, y1, cols, rows, gap=1.2):
        """Keyboard: rows of small filled keys."""
        kw = (x1 - x0) / cols
        kh = (y1 - y0) / rows
        for j in range(rows):
            off = (kw * 0.35) if j % 2 else 0
            for i in range(cols):
                xa = x0 + i * kw + off
                if xa + kw - gap > x1 + 0.1:
                    continue
                self.rr(xa + gap / 2, y0 + j * kh + gap / 2, xa + kw - gap / 2, y0 + (j + 1) * kh - gap / 2, r=0.8, fill=True)

    def grid_dots(self, x0, y0, x1, y1, cols, rows, r=1.6):
        for j in range(rows):
            for i in range(cols):
                x = x0 + (x1 - x0) * (i / (cols - 1) if cols > 1 else 0.5)
                y = y0 + (y1 - y0) * (j / (rows - 1) if rows > 1 else 0.5)
                self.ci(x, y, r, fill=True)

    def vents(self, x0, y0, x1, y1, n, vertical=False, w=1.6):
        for i in range(n):
            t = i / (n - 1) if n > 1 else 0.5
            if vertical:
                x = x0 + (x1 - x0) * t
                self.ln((x, y0), (x, y1), w=w)
            else:
                y = y0 + (y1 - y0) * t
                self.ln((x0, y), (x1, y), w=w)

    def screen(self, x0, y0, x1, y1, r=2, color=False, w=2.2):
        self.rr(x0, y0, x1, y1, r=r, w=w, soft=color)

    def pixels(self, x0, y0, cell, rows, filled_alpha=True):
        """Pixel art: rows of strings, 'X' filled, 'o' soft."""
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch in "Xo":
                    xa, ya = x0 + i * cell, y0 + j * cell
                    self.rr(xa + 0.3, ya + 0.3, xa + cell - 0.3, ya + cell - 0.3, r=0.4, fill=ch == "X", soft=ch == "o")

    def stick(self, x, y0, y1, ball=4.0, w=None):
        """Joystick: shaft from (x, y0) up to (x, y1) with a ball on top."""
        self.ln((x, y0), (x, y1), w=w or self.w * 0.9)
        self.ci(x, y1, ball, fill=True)

    def cart(self, x0, y0, x1, y1, label=True, r=2.5):
        self.rr(x0, y0, x1, y1, r=r, w=2.6)
        if label:
            m = (x1 - x0) * 0.18
            self.rr(x0 + m, y0 + (y1 - y0) * 0.2, x1 - m, y1 - (y1 - y0) * 0.3, r=1, w=1.6, soft=True)

    def disc(self, x, y, r):
        self.ci(x, y, r)
        self.ci(x, y, r * 0.28, w=2.0)


# =====================================================================================
# Nintendo
# =====================================================================================

@glyph("nes")
def nes(p):
    p.rr(-48, -20, 48, 26, 3)
    p.ln((-48, -6), (48, -6), w=2.2)                       # ridge between top and front
    p.rr(-34, 0, 12, 18, 1.5, w=2.2)                      # front-loading door
    p.rr(20, 6, 28, 11, 1, fill=True)                     # power / reset
    p.rr(32, 6, 40, 11, 1, fill=True)
    p.rr(-40, 20, -32, 24, 1, fill=True)                  # controller ports
    p.rr(-28, 20, -20, 24, 1, fill=True)
    p.vents(-40, -16, 40, -16, 1, w=1.4)


@glyph("fds")
def fds(p):
    p.poly([(-30, -36), (24, -36), (30, -30), (30, 36), (-30, 36)])   # floppy with clipped corner
    p.rr(-14, -36, 14, -12, 1, w=2.4, soft=True)          # shutter
    p.rr(2, -32, 8, -18, 0.5, fill=True)
    p.rr(-22, 4, 22, 30, 2, w=1.8)                        # label
    p.vents(-16, 12, 16, 24, 3, w=1.4)


@glyph("snes")
def snes(p):
    p.rr(-48, -14, 48, 28, 14)
    p.rr(-18, -30, 18, -12, 3, w=2.6)                     # cartridge
    p.rr(-12, -26, 12, -18, 1, w=1.4, soft=True)
    p.rr(-38, -4, -30, 2, 1.5, fill=True)                 # power / reset sliders
    p.rr(-26, -4, -18, 2, 1.5, fill=True)
    p.rr(24, -4, 32, 2, 1.5, fill=True)                   # eject
    p.dots([(-12, 22), (12, 22)], 2.2)                    # ports


@glyph("snes-hacks")
def snes_hacks(p):
    p.rr(-38, -30, 38, 30, 10)                            # SNES cartridge
    p.rr(-28, -22, 28, 10, 2, w=1.8)
    p.vents(-24, 18, 24, 24, 2, w=1.6)
    # wrench across the label
    p.ln((-14, 4), (10, -16), w=4.0)
    p.ci(13, -19, 6, w=3.0)
    p.ci(-16, 6, 3.2, fill=True)


@glyph("snesmsu1")
def snesmsu1(p):
    p.rr(-40, -26, 26, 30, 10)                            # SNES cartridge
    p.rr(-32, -18, 18, 12, 2, w=1.8)
    p.ln((-6, 6), (-6, -12), (6, -15), (6, 3), w=2.6)     # music note
    p.ci(-9, 6, 3.4, fill=True)
    p.ci(3, 3, 3.4, fill=True)
    p.disc(36, 4, 14)                                     # CD audio


@glyph("satellaview")
def satellaview(p):
    p.arc(-6, -10, 28, 100, 280, w=3.6)                   # dish
    p.ln((-1, -38), (-11, 18), w=2.6)
    p.ln((-8, -10), (22, -18), w=2.4)                     # feed arm
    p.ci(24, -18, 4, fill=True)
    p.ln((-6, 18), (-6, 26), w=3.0)
    p.rr(-34, 26, 34, 42, 4)                              # receiver
    p.dots([(20, 34), (27, 34)], 1.8)


@glyph("sufami")
def sufami(p):
    p.rr(-44, 4, 44, 36, 8)                               # adapter
    p.cart(-36, -32, -4, 8)                               # two mini cartridges
    p.cart(4, -32, 36, 8)
    p.rr(-20, 26, 20, 30, 1, fill=True)


@glyph("sgb")
def sgb(p):
    p.rr(-40, -28, 40, 30, 10)                            # SNES-sized cartridge
    p.rr(-14, -20, 14, 22, 3, w=2.2)                      # Game Boy inside
    p.screen(-9, -15, 9, 0, r=1, w=1.6)
    p.dpad(-5, 10, 3.0, w=1.6)
    p.dots([(4, 11), (8, 8)], 1.5)
    p.vents(-34, -12, -22, -12, 1, w=1.4)
    p.vents(22, -12, 34, -12, 1, w=1.4)


@glyph("n64")
def n64(p):
    p.poly([(-48, 8), (-36, -12), (36, -12), (48, 8), (48, 26), (-48, 26)])
    p.cart(-14, -34, 14, -10)                             # cartridge on top
    p.rr(-30, -10, -20, -6, 1, fill=True)                 # power / reset
    p.rr(20, -10, 30, -6, 1, fill=True)
    for x in (-21, -7, 7, 21):                            # 4 controller ports
        p.rr(x - 4, 14, x + 4, 21, 1, w=1.8)


@glyph("n64dd")
def n64dd(p):
    p.rr(-48, -2, 48, 32, 5)                              # drive under the console
    p.ln((-30, 16), (30, 16), w=3.2)                      # disk slot
    p.rr(36, 10, 42, 22, 1, fill=True)
    p.rr(-18, -36, 18, 8, 3, w=2.6)                       # disk going in
    p.rr(-10, -36, 10, -22, 1, w=1.8, soft=True)
    p.ci(0, -6, 5, w=1.8)


@glyph("gc")
def gc(p):
    p.rr(-22, -42, 22, -26, 5, w=3.0)                     # carrying handle
    p.rr(-34, -30, 34, 36, 8)
    p.ci(0, 2, 17)                                        # disc lid
    p.ci(0, 2, 4, fill=True)
    for x in (-26, -16, -6, 4):                           # controller ports
        p.rr(x - 3, 28, x + 3, 32, 1, fill=True)


@glyph("wii")
def wii(p):
    p.rr(-30, -40, -10, 40, 4)                            # slim upright console
    p.ln((-20, -32), (-20, 10), w=2.2)                    # disc slot
    p.rr(-28, 40, -12, 44, 1, fill=True)                  # stand
    p.rr(6, -34, 26, 40, 8)                               # motion remote
    p.dpad(16, -22, 4)
    p.ci(16, -6, 3.6, fill=True)
    p.dots([(16, 16), (16, 26)], 1.8)


@glyph("switch")
def switch(p):
    p.rr(-50, -26, 50, 26, 12)
    p.ln((-32, -26), (-32, 26), w=2.4)
    p.ln((32, -26), (32, 26), w=2.4)
    p.screen(-26, -19, 26, 19, r=2)
    p.ci(-41, -10, 4.5, w=2.0)
    p.diamond(-41, 10, 3.8, 1.5)
    p.diamond(41, -10, 3.8, 1.5)
    p.ci(41, 10, 4.5, w=2.0)


@glyph("gb")
def gb(p):
    # DMG brick, rounded bottom-right corner
    p.ln((-26, -42), (26, -42), (26, 26), w=None)
    p.arc(10, 26, 16, 0, 90)
    p.ln((10, 42), (-26, 42), (-26, -42))
    p.rr(-20, -36, 20, -6, 3, w=2.4)
    p.screen(-13, -31, 13, -11, r=1, w=1.8)
    p.dpad(-12, 12, 6)
    p.ab(6, 16, 3.6, 9, -6)
    p.ln((-6, 28), (-2, 26), w=2.0)
    p.ln((2, 28), (6, 26), w=2.0)
    for i in range(3):                                    # speaker
        p.ln((10 + i * 4, 36), (16 + i * 4, 30), w=1.6)


@glyph("gbc")
def gbc(p):
    p.rr(-23, -40, 23, 42, 8)
    p.rr(-6, -43, 6, -40, 1, fill=True)                   # infrared port
    p.rr(-18, -34, 18, -6, 4, w=2.4)
    p.screen(-12, -29, 12, -11, r=1, color=True, w=1.8)
    p.dpad(-10, 12, 5.5)
    p.ab(5, 15, 3.4, 9, -4)
    p.grid_dots(8, 28, 18, 36, 3, 3, 1.1)


@glyph("gba")
def gba(p):
    p.rr(-50, -24, 50, 24, 18)
    p.ellarc(-50, -24, -22, 4, 200, 270, w=3.0)           # shoulder buttons
    p.ellarc(22, -24, 50, 4, 270, 340, w=3.0)
    p.screen(-22, -16, 22, 14, r=3, color=True)
    p.dpad(-36, -2, 6)
    p.ab(30, 2, 3.6, 9, -6)
    p.dots([(-36, 14), (-30, 14)], 1.3)


@glyph("nds")
def nds(p):
    # the two halves meet at the hinge: one shared line, notched by the rounded corners
    p.rr(-36, -42, 36, 0, 6)
    p.rr(-36, 0, 36, 42, 6)
    p.screen(-20, -35, 20, -8, r=1.5)
    p.grid_dots(-30, -27, -26, -17, 2, 3, 0.9)            # speakers
    p.grid_dots(26, -27, 30, -17, 2, 3, 0.9)
    p.screen(-16, 7, 16, 34, r=1.5)
    p.dpad(-27, 20, 4.5)
    p.diamond(27, 20, 4, 1.5)


@glyph("3ds")
def three_ds(p):
    p.rr(-38, -42, 38, 0, 6)
    p.rr(-38, 0, 38, 42, 6)
    p.screen(-28, -35, 28, -8, r=1.5, color=True)         # wide 3D screen
    p.ci(0, -39, 1.3, fill=True)
    p.screen(-16, 7, 16, 34, r=1.5)
    p.ci(-28, 12, 4.6, w=2.0)                             # circle pad
    p.dpad(-28, 30, 3.6)
    p.diamond(28, 20, 4, 1.5)


@glyph("virtualboy")
def virtualboy(p):
    p.rr(-46, -32, 46, 4, 12)
    p.ci(-20, -14, 9, w=2.6)
    p.ci(20, -14, 9, w=2.6)
    p.ln((-18, 4), (-26, 42), w=3.0)                      # stand legs
    p.ln((18, 4), (26, 42), w=3.0)
    p.ln((-30, 42), (30, 42), w=2.6)


@glyph("pokemonmini")
def pokemonmini(p):
    p.rr(-28, -30, 28, 32, 14)
    p.screen(-16, -20, 16, 2, r=2)
    p.dpad(-11, 18, 4.5)
    p.ab(6, 20, 3.0, 8, -4)
    p.ci(20, 26, 1.8, fill=True)


@glyph("gameandwatch")
def gameandwatch(p):
    p.rr(-50, -30, 50, 30, 6)
    p.rr(-24, -22, 24, 20, 2, w=2.6)
    # LCD scene: a little figure and a ball arc
    p.ln((-10, 12), (-10, 2), w=1.8)
    p.ci(-10, -1, 2.4, fill=True)
    p.ln((-16, 6), (-4, 6), w=1.6)
    p.ellarc(-12, -16, 14, 8, 200, 340, w=1.4)
    p.dpad(-38, 6, 6)
    p.ci(38, 8, 5.5, fill=True)
    p.rr(32, -24, 38, -20, 1, fill=True)
    p.rr(42, -24, 48, -20, 1, fill=True)


# =====================================================================================
# Sony, Microsoft
# =====================================================================================

@glyph("psx")
def psx(p):
    p.rr(-50, -22, 50, 26, 5)
    p.ci(-16, 0, 17)                                      # round disc lid
    p.ci(-16, 0, 4, w=2.0)
    p.el(16, -10, 26, -4, fill=True)                      # power / reset
    p.el(30, -10, 40, -4, fill=True)
    p.ci(28, 8, 4.5, w=2.0)                               # open button
    p.rr(-40, 20, -30, 24, 1, fill=True)
    p.rr(-26, 20, -16, 24, 1, fill=True)


@glyph("ps2")
def ps2(p):
    p.rr(-20, -44, 20, 44, 3)                             # upright tower
    for y in (-36, -30, -24, -18):                        # ribs
        p.ln((-12, y), (12, y), w=1.8)
    p.ln((12, -6), (12, 34), w=2.4)                       # disc tray
    p.ci(-8, 6, 2, fill=True)
    p.ci(-8, 14, 2, fill=True)
    p.rr(-12, 24, -4, 32, 1, w=1.6)


@glyph("ps3")
def ps3(p):
    # original PS3, front view: wide slab with a gently curved top,
    # slot-loading drive, touch power/eject on the right, USB ports at the front
    p.ln((-50, -4), (-50, 22), (50, 22), (50, -4))
    p.ellarc(-50, -16, 50, 8, 180, 360)
    p.ln((-46, 14), (46, 14), w=1.4)                      # lower body seam
    p.ln((-30, 0), (14, 0), w=2.8)                        # disc slot
    p.rr(24, -2, 32, 2, 1.5, fill=True)                   # power / eject
    p.rr(36, -2, 44, 2, 1.5, fill=True)
    for x in (-42, -35, -28, -21):                        # USB ports
        p.rr(x, 16, x + 4, 20, 0.6, fill=True)


@glyph("psp")
def psp(p):
    p.rr(-50, -22, 50, 22, 22)
    p.screen(-26, -15, 26, 15, r=1.5)
    p.dpad(-38, -4, 5)
    p.diamond(38, -4, 5, 1.7, filled=False)
    p.ci(-38, 12, 2.6, fill=True)                         # analog nub


@glyph("pspminis")
def pspminis(p):
    # slider: screen half slid up over the controls
    p.ln((-44, 2), (-44, 26), w=None)
    p.ellarc(-44, 10, -20, 36, 90, 180)
    p.ln((-32, 36), (32, 36))
    p.ellarc(20, 10, 44, 36, 0, 90)
    p.ln((44, 26), (44, 2))
    p.dpad(-32, 22, 4.5)
    p.diamond(32, 22, 4, 1.5, filled=False)
    p.rr(-40, -38, 40, 8, 16)
    p.screen(-26, -30, 26, 0, r=1.5)


@glyph("psvita")
def psvita(p):
    p.rr(-52, -24, 52, 24, 22)
    p.screen(-28, -17, 28, 17, r=2, color=True)
    p.dpad(-40, -9, 4.2)
    p.ci(-38, 11, 4.4, w=2.0)                             # two sticks
    p.diamond(40, -9, 4.2, 1.5, filled=False)
    p.ci(38, 11, 4.4, w=2.0)


@glyph("xbox")
def xbox(p):
    p.rr(-50, -22, 50, 28, 6)
    p.ci(0, -6, 11)                                       # jewel on top
    p.ci(0, -6, 5, fill=True)
    p.ln((-34, 16), (-6, 16), w=2.6)                      # disc tray
    for x in (12, 21, 30, 39):                            # 4 controller ports
        p.rr(x - 3, 13, x + 3, 19, 1, w=1.6)


# =====================================================================================
# Sega
# =====================================================================================

@glyph("sg-1000")
def sg1000(p):
    p.poly([(-48, 24), (-48, -2), (-38, -14), (22, -14), (22, 24)])
    p.rr(-30, -20, -2, -12, 1.5, w=2.2)                   # cartridge slot
    p.rr(-40, 8, -30, 14, 1, fill=True)
    p.rr(28, 12, 48, 24, 3)                               # joystick
    p.stick(38, 12, -14, 4.5)
    p.ci(44, 8, 2, fill=True)


@glyph("mastersystem")
def mastersystem(p):
    p.poly([(-48, 10), (-38, -14), (38, -14), (48, 10), (48, 26), (-48, 26)])
    p.rr(-16, -24, 16, -12, 1.5, w=2.4)                   # cartridge slot
    p.ln((-38, 16), (-18, 16), w=2.2)                     # card slot
    p.rr(18, 12, 26, 18, 1, fill=True)
    p.rr(30, 12, 38, 18, 1, fill=True)
    p.ln((-48, 10), (48, 10), w=1.6)


@glyph("megadrive")
def megadrive(p):
    p.rr(-48, -14, 48, 28, 8)
    p.ci(0, 4, 19)                                        # the big ring
    p.rr(-13, 0, 13, 8, 1.5, fill=True)                   # cartridge slot
    p.ln((-40, -4), (-30, -4), w=2.2)                     # volume slider
    p.rr(28, 16, 36, 20, 1, fill=True)
    p.rr(28, 6, 36, 10, 1, fill=True)


@glyph("msumd")
def msumd(p):
    p.rr(-30, -40, 30, 34, 4)                             # Mega Drive cartridge
    p.rr(-22, -32, 22, 10, 2, w=1.8, soft=True)
    p.ln((-6, 4), (-6, -18), (8, -22), (8, 0), w=2.6)     # music note
    p.ci(-9, 4, 3.6, fill=True)
    p.ci(5, 0, 3.6, fill=True)
    p.vents(-20, 22, 20, 28, 2, w=1.6)


@glyph("segacd")
def segacd(p):
    p.rr(-44, -40, 44, -8, 6)                             # Mega Drive on top
    p.ci(0, -24, 12)
    p.rr(-8, -27, 8, -21, 1, fill=True)
    p.rr(-48, -4, 48, 38, 6)                              # CD unit below
    p.ln((-34, 24), (20, 24), w=2.8)                      # tray
    p.ci(34, 24, 4, w=2.0)


@glyph("sega32x")
def sega32x(p):
    p.rr(-48, 6, 48, 38, 6)                               # Mega Drive
    p.ci(0, 22, 12, w=2.4)
    p.poly([(-16, 10), (-26, -18), (26, -18), (16, 10)])  # the "mushroom"
    p.ellarc(-26, -32, 26, -4, 180, 360)
    p.rr(-10, -30, 10, -22, 1, fill=True)


@glyph("saturn")
def saturn(p):
    p.rr(-50, -24, 50, 26, 18)
    p.rr(-26, -16, 26, 16, 10, w=2.6)                     # big lid
    p.ci(0, 0, 7, w=2.0)
    p.dots([(-38, -4), (-38, 6)], 2.2)                    # power / reset
    p.rr(32, -4, 42, 2, 1, fill=True)                     # open button


@glyph("dreamcast")
def dreamcast(p):
    p.rr(-42, -34, 42, 36, 10)
    p.ci(0, -6, 20)                                       # lid
    p.ci(0, -6, 6, w=2.0)
    p.ci(-32, -24, 2.4, fill=True)                        # power / open
    p.ci(32, -24, 2.4, fill=True)
    for x in (-27, -9, 9, 27):                            # 4 controller ports
        p.rr(x - 5, 24, x + 5, 30, 1.5, w=1.6)


@glyph("vmu")
def vmu(p):
    p.ln((-26, 8), (-26, -38), (26, -38), (26, 8))
    p.ellarc(-26, -18, 26, 44, 0, 180)                    # rounded bottom
    p.screen(-18, -30, 18, -6, r=2)
    p.dpad(-11, 12, 5.5)
    p.ab(6, 16, 3.2, 9, -5)


@glyph("gamegear")
def gamegear(p):
    p.rr(-52, -26, 52, 26, 12)
    p.screen(-22, -18, 22, 16, r=3, color=True)
    p.ci(-38, 0, 9, w=2.4)                                # round d-pad
    p.dpad(-38, 0, 4.0, w=1.8)
    p.ab(32, 6, 3.8, 10, -8)
    p.rr(32, -18, 40, -14, 1, fill=True)                  # start


@glyph("pico")
def pico(p):
    # Sega Pico: picture book on top, pad and pen below
    p.poly([(-44, -34), (0, -28), (0, 6), (-44, 0)])
    p.poly([(44, -34), (0, -28), (0, 6), (44, 0)])
    p.ln((-36, -22), (-8, -18), w=1.4)
    p.ln((8, -18), (36, -22), w=1.4)
    p.rr(-46, 10, 46, 38, 8)
    p.ci(-30, 24, 6, w=2.0)
    p.ln((18, 30), (34, 14), w=3.6)                       # magic pen
    p.ci(16, 32, 2.2, fill=True)


@glyph("naomi")
def naomi(p):
    p.rr(-48, -28, 48, 30, 4)                             # arcade board in its case
    p.ci(22, 0, 16)                                       # cooling fan
    for a in range(0, 360, 60):
        r = math.radians(a)
        p.ln((22, 0), (22 + 13 * math.cos(r), 13 * math.sin(r)), w=1.8)
    p.ci(22, 0, 3, fill=True)
    p.rr(-40, -20, -10, -4, 1.5, w=2.0)                   # chips
    p.rr(-40, 4, -22, 20, 1.5, w=2.0)
    p.rr(-18, 4, -6, 20, 1.5, w=2.0)


@glyph("atomiswave")
def atomiswave(p):
    p.rr(-36, -40, 36, 38, 4)                             # big arcade cartridge
    p.rr(-26, -30, 26, 8, 2, w=2.0, soft=True)
    p.rr(-16, 18, 16, 26, 3, w=2.4)                       # grip
    p.vents(-30, 32, 30, 32, 1, w=1.4)


# =====================================================================================
# NEC, SNK, Bandai, others (handhelds)
# =====================================================================================

@glyph("pcengine")
def pcengine(p):
    p.rr(-30, -28, 30, 26, 4)                             # tiny square console
    p.vents(-22, -20, 22, -10, 3, w=1.8)
    p.ln((-30, -4), (30, -4), w=1.6)
    p.cart(-18, 14, 18, 40, label=False, r=2)             # HuCard going in
    p.vents(-12, 24, 12, 32, 2, w=1.4)


@glyph("pcenginecd")
def pcenginecd(p):
    p.rr(-46, -28, 46, 30, 6)
    p.ci(16, -2, 18)                                      # CD lid
    p.ci(16, -2, 5, w=2.0)
    p.ln((-38, 20), (-12, 20), w=2.6)                     # HuCard slot
    p.ci(-30, -16, 2.4, fill=True)
    p.ci(-20, -16, 2.4, fill=True)


@glyph("supergrafx")
def supergrafx(p):
    p.rr(-48, -12, 48, 30, 6)
    p.ln((22, -12), (34, -34), w=3.4)                     # power lever
    p.ci(36, -36, 5, fill=True)
    p.vents(-40, -4, -12, -4, 1, w=1.6)
    p.vents(-40, 2, -12, 2, 1, w=1.6)
    p.ln((-36, 20), (-4, 20), w=2.6)                      # HuCard slot
    p.rr(8, 2, 40, 10, 1.5, w=1.8)


@glyph("pcfx")
def pcfx(p):
    p.rr(-22, -44, 22, 44, 6)                             # upright tower
    p.ci(0, -16, 13)
    p.ci(0, -16, 3.5, w=1.8)
    p.ln((-12, 12), (12, 12), w=2.2)
    p.dots([(-8, 24), (0, 24), (8, 24)], 2.0)
    p.ln((-12, 34), (12, 34), w=1.6)


@glyph("neogeo")
def neogeo(p):
    p.rr(-50, 0, 50, 32, 6)                               # big AES console
    p.rr(-20, -42, 20, 4, 3, w=2.8)                       # very tall cartridge
    p.vents(-12, -34, 12, -10, 4, w=1.6)
    p.rr(-42, 18, -30, 24, 1, fill=True)
    p.rr(30, 18, 42, 24, 1, fill=True)


@glyph("neogeocd")
def neogeocd(p):
    p.rr(-48, -22, 48, 30, 6)
    p.rr(-34, -16, 18, 22, 4, w=2.6)                      # rectangular hinged lid
    p.ci(-8, 3, 9, w=2.0)
    p.ci(32, -10, 3.6, fill=True)                         # open button
    p.rr(26, 14, 34, 20, 1, fill=True)
    p.rr(38, 14, 44, 20, 1, fill=True)


@glyph("ngp")
def ngp(p):
    p.rr(-48, -28, 48, 28, 10)
    p.screen(-18, -20, 20, 16, r=2)
    p.ci(-34, 0, 8, w=2.2)                                # clicky stick
    p.ci(-34, 0, 3.4, fill=True)
    p.ab(30, 4, 3.4, 10, -6)


@glyph("ngpc")
def ngpc(p):
    p.rr(-50, -28, 50, 28, 18)
    p.screen(-18, -20, 20, 16, r=2)
    p.pixels(-10, -12, 5, ["XoX", "oXo", "XoX"])          # color screen
    p.ci(-35, 0, 8, w=2.2)
    p.ci(-35, 0, 3.4, fill=True)
    p.ab(30, 4, 3.4, 10, -6)
    p.ci(36, -16, 1.8, fill=True)


@glyph("wonderswan")
def wonderswan(p):
    p.rr(-50, -24, 50, 24, 8)
    p.screen(-24, -16, 24, 14, r=2)
    p.diamond(-38, 0, 5, 1.8)                             # X buttons
    p.ab(32, 6, 3.4, 9, -6)


@glyph("wonderswancolor")
def wonderswancolor(p):
    p.rr(-50, -24, 50, 24, 14)
    p.screen(-24, -16, 24, 14, r=2)
    p.pixels(-12, -9, 6, ["XoX", "oXo", "XoX"])
    p.diamond(-38, -6, 4, 1.5)                            # X and Y button sets
    p.diamond(-38, 12, 4, 1.5, filled=False)
    p.ab(32, 6, 3.4, 9, -6)


@glyph("atarilynx")
def atarilynx(p):
    p.poly([(-52, -16), (-44, -24), (44, -24), (52, -16), (52, 16), (40, 24), (-40, 24), (-52, 16)])
    p.screen(-22, -16, 22, 14, r=2, color=True)
    p.dpad(-38, 0, 6)
    p.dots([(32, -8), (42, -2), (32, 6), (42, 12)], 2.8)  # A/B twice (ambidextrous)


@glyph("supervision")
def supervision(p):
    p.rr(-30, -42, 30, 4, 4)                              # tilting screen part
    p.screen(-22, -34, 22, -2, r=1.5)
    p.ln((-26, 8), (26, 8), w=2.4)                        # hinge
    p.rr(-38, 10, 38, 42, 8)
    p.dpad(-22, 26, 5)
    p.ab(14, 30, 3.2, 9, -6)


@glyph("megaduck")
def megaduck(p):
    p.rr(-26, -42, 26, 42, 8)
    p.rr(-20, -36, 20, -6, 3, w=2.4)
    p.screen(-14, -31, 14, -11, r=1, w=1.8)
    p.dpad(-12, 10, 5.5)
    p.ab(6, 12, 3.4, 9, -6)
    p.ci(10, 30, 7, w=2.0)                                # round speaker
    p.grid_dots(7, 27, 13, 33, 2, 2, 1.0)


@glyph("mv")
def microvision(p):
    p.rr(-28, -44, 28, 42, 6)
    p.screen(-10, -36, 10, -18, r=1)                      # tiny square LCD
    p.grid_dots(-12, -8, 12, 18, 3, 4, 2.0)               # 12-key pad
    p.ci(0, 32, 5.5, w=2.2)                               # paddle knob
    p.ln((0, 32), (0, 27), w=1.8)


@glyph("arduboy")
def arduboy(p):
    p.rr(-24, -42, 24, 42, 3)                             # credit-card thin
    p.screen(-17, -34, 17, -10, r=0.8)
    p.dpad(-10, 14, 5.5)
    p.ab(8, 16, 3.2, 8, -5)
    p.dots([(-12, 34), (-6, 34), (0, 34), (6, 34)], 1.0)


@glyph("piece")
def piece(p):
    p.rr(-40, -22, 40, 22, 8)
    p.ci(-34, -16, 2.6, w=1.4)                            # strap hole
    p.screen(-16, -14, 22, 14, r=1.5)
    p.dpad(-28, 2, 5)
    p.dots([(30, -6), (30, 6)], 3.0)


@glyph("tigerlcd")
def tigerlcd(p):
    p.rr(-30, -44, 30, 44, 10)
    p.rr(-22, -36, 22, 10, 2, w=2.6)
    p.ln((-16, 2), (-8, -6), (0, 0), (8, -10), (16, -4), w=1.4)   # LCD scenery
    p.ci(-6, -22, 3, fill=True)
    for x in (-15, -5, 5, 15):
        p.ci(x, 24, 3.2, fill=True)
    p.vents(-12, 34, 12, 38, 2, w=1.4)


@glyph("palm")
def palm(p):
    p.rr(-28, -44, 22, 44, 8)
    p.screen(-22, -38, 16, 12, r=1.5)
    p.rr(-22, 16, 16, 26, 1, w=1.4)                       # writing area
    p.dots([(-16, 34), (-6, 34), (4, 34), (12, 34)], 2.2)
    p.ln((32, -40), (32, 32), w=2.6)                      # stylus
    p.ln((32, 32), (32, 38), w=1.4)


@glyph("j2me")
def j2me(p):
    p.rr(-20, -40, 20, 44, 10)
    p.ln((12, -40), (12, -48), w=3.0)                     # antenna
    p.screen(-14, -32, 14, -6, r=1.5)
    p.grid_dots(-9, 6, 9, 36, 3, 4, 2.0)


# =====================================================================================
# Atari
# =====================================================================================

@glyph("atari2600")
def atari2600(p):
    p.poly([(-48, -6), (-42, -18), (42, -18), (48, -6), (48, 22), (-48, 22)])
    for x in range(-38, -6, 5):                           # ribbed top
        p.ln((x, -14), (x, -2), w=1.6)
    for x in (4, 14, 24, 34):                             # switches
        p.rr(x - 3, -14, x + 3, -6, 1, fill=True)
    p.rr(-12, -26, 12, -16, 1.5, w=2.2)                   # cartridge
    p.ln((-48, 6), (48, 6), w=1.6)


@glyph("atari5200")
def atari5200(p):
    p.poly([(-50, 24), (-50, 0), (-32, -18), (50, -18), (50, 24)])
    p.rr(-20, -26, 12, -16, 1.5, w=2.2)                   # cartridge
    p.rr(12, -10, 44, 18, 3, w=2.2)                       # controller storage
    p.grid_dots(20, -2, 36, 12, 3, 3, 1.4)
    p.ln((-50, 8), (6, 8), w=1.6)


@glyph("atari7800")
def atari7800(p):
    p.rr(-50, -4, 50, 24, 6)
    p.poly([(-38, -4), (-28, -16), (28, -16), (38, -4)])  # raised top
    p.rr(-12, -24, 12, -14, 1.5, w=2.2)
    p.dots([(-32, 12), (-22, 12)], 2.2)
    p.rr(28, 9, 42, 15, 1, fill=True)


@glyph("atari800")
def atari800(p):
    p.poly([(-50, 26), (-50, -2), (-42, -20), (42, -20), (50, -2), (50, 26)])
    p.rr(-30, -18, 30, -6, 2, w=2.2, soft=True)           # cartridge door
    p.keys(-42, 2, 42, 22, 12, 3)


@glyph("atarist")
def atarist(p):
    p.rr(-50, -14, 50, 28, 3)
    for i in range(4):                                    # diagonal vents, top right
        p.ln((22 + i * 6, -10), (27 + i * 6, -4), w=1.6)
    p.keys(-44, 0, 22, 24, 10, 3)
    p.keys(28, 0, 44, 24, 3, 3)
    p.rr(-30, -42, -10, -22, 8)                           # mouse
    p.ln((-20, -42), (-20, -34), w=1.4)
    p.ln((-20, -22), (-20, -14), w=1.6)


@glyph("atarixegs")
def atarixegs(p):
    p.rr(-30, -40, 30, -10, 4)                            # console
    p.rr(-14, -46, 14, -40, 1, w=2.0)
    p.dots([(-20, -26), (20, -26)], 2.4)
    p.ln((0, -10), (0, 2), w=1.6)                         # cable
    p.rr(-50, 4, 50, 34, 3)                               # detachable keyboard
    p.keys(-44, 10, 44, 30, 12, 3)


@glyph("atarijaguar")
def atarijaguar(p):
    p.ln((-50, 26), (50, 26))
    p.ln((-50, 26), (-50, 8))
    p.ln((50, 26), (50, 8))
    p.ellarc(-50, -22, 50, 38, 180, 360)                  # curved dome
    p.rr(-14, -32, 14, -10, 2, w=2.6)                     # cartridge
    p.ci(-30, 12, 2.6, fill=True)
    p.rr(20, 10, 34, 16, 1, fill=True)


# =====================================================================================
# Other consoles
# =====================================================================================

@glyph("3do")
def threedo(p):
    p.poly([(-50, 24), (-50, -4), (-44, -14), (50, -14), (50, 24)])
    p.ln((-36, 8), (8, 8), w=2.8)                         # front tray
    p.rr(-30, 2, 2, 8, 1, w=1.6)
    p.ci(26, 8, 3, fill=True)
    p.ci(38, 8, 3, fill=True)
    p.ln((-44, -4), (50, -4), w=1.6)


@glyph("cdimono1")
def cdimono1(p):
    p.rr(-52, -16, 52, 22, 3)                             # hi-fi style box
    p.ln((-40, -4), (0, -4), w=2.8)                       # disc drawer
    p.rr(10, -10, 42, 0, 1, w=1.8, soft=True)             # display
    p.grid_dots(-40, 12, 40, 12, 7, 1, 1.8)               # buttons


@glyph("amigacd32")
def amigacd32(p):
    p.poly([(-48, 26), (-48, -14), (-40, -22), (48, -22), (48, 26)])
    p.ci(-14, 2, 17)
    p.ci(-14, 2, 4, w=2.0)
    for i in range(4):                                    # grille
        p.ln((14 + i * 7, -10), (22 + i * 7, 14), w=1.8)


@glyph("intellivision")
def intellivision(p):
    p.rr(-52, -4, 18, 22, 3)                              # long console
    p.ln((-52, 6), (18, 6), w=1.8)
    p.rr(-40, -10, -16, -4, 1, w=1.8)
    p.rr(24, -34, 50, 36, 4)                              # controller: keypad + disc
    p.grid_dots(30, -26, 44, 4, 3, 4, 1.6)
    p.ci(37, 22, 7.5, w=2.2)


@glyph("coleco")
def coleco(p):
    p.rr(-52, -2, 18, 26, 4)
    p.cart(-36, -20, -10, 0)                              # cartridge on top
    p.vents(-44, 12, 8, 12, 1, w=1.6)
    p.rr(24, -30, 50, 38, 4)                              # controller: knob + keypad
    p.stick(37, -10, -22, 4.0, w=2.6)
    p.grid_dots(30, 2, 44, 30, 3, 4, 1.6)


@glyph("channelf")
def channelf(p):
    p.rr(-52, -10, 16, 22, 4)
    p.ln((-40, 6), (-8, 6), w=2.6)                        # cartridge slot
    for i in range(4):
        p.ln((-44 + i * 6, -6), (-40 + i * 6, -2), w=1.4)
    # stick controller with its triangular cap
    p.rr(28, -8, 40, 36, 4)
    p.poly([(24, -8), (34, -24), (44, -8)], fill=True)


@glyph("odyssey2")
def odyssey2(p):
    # flat console: membrane keyboard in front, cartridge slot and joystick cable at the back
    p.rr(-50, -12, 50, 26, 6)
    p.rr(-40, -4, 40, 20, 2, w=1.8)
    p.grid_dots(-34, 2, 34, 14, 10, 3, 1.3)
    p.rr(18, -22, 42, -12, 1.5, w=2.2)
    p.ln((-30, -12), (-34, -24), (-44, -30), w=2.0)
    p.stick(-44, -30, -42, 3.0, w=2.0)


@glyph("videopac")
def videopac(p):
    p.rr(-30, -10, 30, 26, 4)
    p.keys(-24, -2, 24, 20, 7, 3)
    for sx in (-1, 1):                                    # two joysticks
        x = 42 * sx
        p.rr(x - 8, 14, x + 8, 24, 3)
        p.stick(x, 14, -14, 4.0, w=2.6)


@glyph("vectrex")
def vectrex(p):
    p.rr(-28, -44, 28, 30, 8)
    p.rr(-20, -36, 20, 14, 3, w=2.4)
    p.ln((-12, -18), (0, -26), (12, -4), (-6, 4), w=1.8)  # vector graphics
    p.rr(-24, 32, 24, 44, 4)                              # controller
    p.dots([(-14, 38), (2, 38), (10, 38), (18, 38)], 1.8)


@glyph("astrocde")
def astrocde(p):
    p.poly([(-50, 24), (-50, -4), (-32, -18), (24, -18), (24, 24)])
    p.grid_dots(-40, -2, -6, 14, 5, 3, 1.6)               # keypad
    # pistol-grip controller
    p.poly([(32, -22), (46, -22), (46, -12), (44, 26), (34, 26), (34, -12)])
    p.ci(39, -28, 4.2, fill=True)
    p.ln((34, -6), (28, -2), w=2.2)


@glyph("advision")
def advision(p):
    p.rr(-50, -32, 50, 30, 4)
    p.rr(-28, -24, 28, 8, 2, w=2.8)                       # deep screen hood
    p.screen(-20, -18, 20, 2, r=1, color=True)
    p.ci(-38, 18, 6, w=2.2)
    p.ci(38, 18, 6, w=2.2)
    p.dots([(-14, 20), (-4, 20), (6, 20), (16, 20)], 2.0)


@glyph("gx4000")
def gx4000(p):
    p.poly([(-50, 26), (-42, -4), (-22, -18), (22, -18), (42, -4), (50, 26)])
    p.rr(-14, -28, 14, -16, 1.5, w=2.2)
    for sx in (-1, 1):
        for i in range(3):
            x = sx * (26 + i * 6)
            p.ln((x, 2), (x - sx * 4, 18), w=1.6)
    p.ci(0, 10, 4, w=2.0)


@glyph("scv")
def scv(p):
    p.rr(-50, -6, 50, 26, 6)
    p.rr(-14, -16, 14, -6, 1.5, w=2.2)
    p.stick(-36, -6, -30, 4.2)                            # built-in joysticks
    p.stick(36, -6, -30, 4.2)
    p.dots([(-36, 12), (-26, 12), (26, 12), (36, 12)], 2.2)


@glyph("uzebox")
def uzebox(p):
    p.rr(-48, -32, 48, 32, 3)                             # open circuit board
    p.dots([(-42, -26), (42, -26), (-42, 26), (42, 26)], 1.8)
    p.rr(-34, -20, -8, 0, 1, w=2.0)
    p.rr(-34, 8, -20, 22, 1, w=2.0)
    p.rr(-14, 8, 2, 22, 1, w=2.0)
    p.poly([(14, -20), (38, -20), (38, 14), (20, 14), (14, 8)])   # SD card
    p.vents(20, -16, 32, -16, 1, w=1.4)


@glyph("vircon32")
def vircon32(p):
    p.rr(-48, -6, 48, 28, 8)
    p.rr(-22, -34, 22, -2, 3, w=2.6)                      # cartridge
    p.pixels(-9, -26, 6, ["XoX", "oXo", "XoX"])
    p.ci(-36, 12, 2.6, fill=True)
    p.rr(20, 10, 38, 16, 1, fill=True)


@glyph("arcade")
def arcade(p):
    pts = [(-26, -44), (26, -44), (26, -30), (20, -2), (34, 10), (30, 44), (-30, 44),
           (-34, 10), (-20, -2), (-26, -30)]
    p.poly(pts)
    p.ln((-26, -34), (26, -34), w=2.2)
    p.screen(-16, -27, 16, -6, r=1.5, color=True)
    p.stick(-14, 14, 4, 3.8, w=2.4)
    p.dots([(6, 12), (16, 12)], 2.8)


@glyph("mame")
def mame(p):
    p.rr(-50, -12, 50, 24, 8)                             # control panel
    p.ln((-50, -4), (50, -4), w=1.6)
    p.rr(-40, 4, -20, 12, 3, w=2.2)
    p.stick(-30, 6, -34, 5.0)
    for i, x in enumerate((0, 14, 28)):                   # six buttons
        p.ci(x, 0, 4.2, fill=True)
        p.ci(x + 6, 14, 4.2, w=2.0)


@glyph("mame2003")
def mame2003(p):
    p.rr(-46, -32, 46, 28, 12)                            # cocktail table, top view
    p.screen(-20, -18, 20, 14, r=2, color=True)
    for sx in (-1, 1):
        p.stick(sx * 34, 0, -14, 3.6, w=2.4)
        p.ci(sx * 34, 12, 2.6, fill=True)
    p.ln((-30, 28), (-30, 40), w=3.0)                     # legs
    p.ln((30, 28), (30, 40), w=3.0)


@glyph("cps1")
def cps1(p):
    for y0 in (-30, 4):                                   # two stacked boards
        p.rr(-46, y0, 46, y0 + 26, 3)
        p.vents(-36, y0 + 26, 36, y0 + 26, 1, w=1.4)
    p.rr(-36, -24, -16, -10, 1, fill=True)
    p.rr(-10, -24, 10, -10, 1, w=1.8)
    p.rr(16, -24, 36, -10, 1, w=1.8)
    p.rr(-36, 10, -6, 24, 1, w=1.8)
    p.rr(4, 10, 36, 24, 1, fill=True)


@glyph("cps2")
def cps2(p):
    p.rr(-44, -16, 32, 34, 3)                             # boxed board, 3D
    p.poly([(-44, -16), (-30, -32), (46, -32), (32, -16)])
    p.poly([(32, -16), (46, -32), (46, 18), (32, 34)])
    p.rr(-34, -6, 22, 14, 2, w=1.8, soft=True)
    p.rr(-30, 22, -10, 28, 1, fill=True)


@glyph("cps3")
def cps3(p):
    p.disc(-14, 0, 28)                                    # CD
    p.rr(14, -26, 46, 30, 3, fill=False)                  # security cartridge
    p.rr(20, -18, 40, 6, 1.5, w=1.6, soft=True)
    p.vents(20, 18, 40, 24, 2, w=1.4)


@glyph("daphne")
def daphne(p):
    p.ci(0, 0, 40)                                        # LaserDisc
    p.ci(0, 0, 14, w=2.2)
    p.ci(0, 0, 3, fill=True)
    p.ellarc(-30, -30, 30, 30, 200, 250, w=1.6)           # shine


@glyph("alg")
def alg(p):
    # light gun
    p.poly([(-46, -18), (30, -18), (34, -14), (34, -6), (30, -2), (-6, -2), (-12, 26), (-30, 26), (-24, -2), (-46, -2)])
    p.ln((-6, -2), (-2, 10), (-12, 10), w=2.2)            # trigger guard
    p.rr(-42, -24, -34, -18, 1, fill=True)                # rear sight
    p.rr(22, -23, 27, -18, 1, fill=True)                  # front sight
    p.ln((-40, -10), (20, -10), w=1.4)


# =====================================================================================
# Computers
# =====================================================================================

@glyph("c64")
def c64(p):
    p.rr(-50, -16, 50, 22, 12)                            # breadbin
    p.keys(-42, -8, 30, 14, 9, 3)
    for y in (-8, -2, 4, 10):                             # function keys
        p.rr(36, y, 44, y + 4, 1, fill=True)


@glyph("vic20")
def vic20(p):
    p.rr(-50, -8, 50, 28, 12)
    p.keys(-42, 0, 30, 20, 9, 3)
    for y in (0, 6, 12):
        p.rr(36, y, 44, y + 4, 1, fill=True)
    p.cart(14, -30, 42, -6)                               # cartridge at the back


@glyph("c128")
def c128(p):
    p.poly([(-52, 24), (-52, -6), (-46, -18), (46, -18), (52, -6), (52, 24)])
    p.grid_dots(-42, -12, 42, -12, 12, 1, 1.4)            # top key row
    p.keys(-44, -4, 24, 18, 9, 3)
    p.keys(30, -4, 46, 18, 3, 3)                          # numeric pad


@glyph("c16")
def c16(p):
    p.rr(-46, -14, 46, 20, 4)
    p.keys(-40, -8, 18, 14, 8, 3)
    p.diamond(33, 3, 6, 2.4)                              # arrow keys


@glyph("amiga")
def amiga(p):
    p.poly([(-50, 30), (-50, 8), (-44, -2), (44, -2), (50, 8), (50, 30)])
    p.keys(-42, 8, 30, 26, 9, 3)
    p.ln((36, 12), (46, 12), w=2.2)                       # floppy slot
    p.ci(-22, -26, 14)                                    # checkered ball
    p.ellarc(-36, -40, -8, -12, 0, 360, w=1.4)
    p.ellarc(-30, -40, -14, -12, 0, 360, w=1.4)
    p.ln((-36, -26), (-8, -26), w=1.4)
    p.ln((-34, -33), (-10, -33), w=1.2)
    p.ln((-34, -19), (-10, -19), w=1.2)


@glyph("apple2")
def apple2(p):
    p.rr(-30, -44, 30, -4, 4)                             # monitor on top
    p.screen(-24, -38, 24, -10, r=2, color=True)
    p.poly([(-46, 34), (-46, 10), (-38, 0), (38, 0), (46, 10), (46, 34)])
    p.keys(-38, 14, 38, 30, 10, 2)


@glyph("vmac")
def vmac(p):
    p.rr(-28, -44, 28, 38, 6)                             # all-in-one
    p.screen(-22, -38, 22, -4, r=2)
    p.ln((6, 14), (20, 14), w=2.4)                        # floppy slot
    p.ln((-22, 26), (22, 26), w=1.4)
    p.rr(-26, 38, 26, 44, 1, fill=True)


@glyph("bbcmicro")
def bbcmicro(p):
    p.rr(-52, -16, 52, 22, 3)
    for i in range(10):                                   # function key strip
        x = -44 + i * 8.8
        p.rr(x, -12, x + 6.6, -7, 1, fill=True)
    p.keys(-44, -2, 44, 18, 11, 3)


@glyph("amstradcpc")
def amstradcpc(p):
    p.rr(-52, -18, 52, 22, 3)
    p.keys(-46, -10, 6, 16, 7, 3)
    p.rr(12, -12, 46, 16, 2, w=2.2)                       # built-in tape deck
    p.ci(22, 2, 4.5, w=1.8)
    p.ci(36, 2, 4.5, w=1.8)
    p.ln((22, 2), (36, 2), w=1.2)


@glyph("zxspectrum")
def zxspectrum(p):
    p.rr(-50, -22, 50, 24, 4)
    p.grid_dots(-41, -14, 41, 4, 10, 4, 1.8)              # rubber keys
    for i in range(4):                                    # rainbow stripes, bottom-right corner
        p.ln((16 + i * 6, 19), (24 + i * 6, 10), w=2.4)


@glyph("zx81")
def zx81(p):
    p.poly([(-44, 20), (-40, -18), (40, -18), (44, 20)])
    p.rr(-36, -10, 36, 14, 1, w=1.6)                      # membrane keyboard grid
    for x in range(-29, 36, 7):
        p.ln((x, -10), (x, 14), w=1.0)
    for y in (-4, 2, 8):
        p.ln((-36, y), (36, y), w=1.0)


@glyph("msx")
def msx(p):
    p.rr(-52, -6, 52, 26, 3)
    p.keys(-46, 2, 46, 22, 12, 3)
    p.cart(16, -30, 42, -4)                               # cartridge


@glyph("msx2")
def msx2(p):
    p.rr(-52, -6, 52, 26, 3)
    p.keys(-46, 2, 26, 22, 9, 3)
    p.cart(-36, -28, -14, -4)                             # two cartridge slots
    p.cart(-8, -28, 14, -4)
    p.ln((32, 8), (46, 8), w=2.4)                         # built-in floppy
    p.rr(36, 14, 42, 18, 1, fill=True)


@glyph("coco3")
def coco3(p):
    p.rr(-50, -14, 32, 22, 3)
    p.keys(-44, -6, 24, 16, 8, 3)
    p.cart(30, -10, 52, 14)                               # side cartridge


@glyph("dragon32")
def dragon32(p):
    p.rr(-50, -26, 50, -2, 3)                             # tall rear section
    p.vents(-40, -20, -10, -8, 3, w=1.6)
    p.rr(-50, -2, 50, 26, 3)
    p.keys(-44, 4, 44, 22, 11, 3)


@glyph("enterprise")
def enterprise(p):
    # sloped dark wedge with the joystick built into the right-hand side
    p.poly([(-52, 24), (-46, -8), (24, -8), (30, -16), (52, -16), (52, 24)])
    p.keys(-44, -2, 20, 18, 8, 3)
    p.ci(40, 4, 9, w=2.4)
    p.dpad(40, 4, 5, w=2.0)
    p.ln((-46, -8), (24, -8), w=1.6)


@glyph("pc98")
def pc98(p):
    p.rr(-30, -44, 30, -4, 4)                             # monitor
    p.screen(-24, -38, 24, -10, r=2)
    p.rr(-48, 4, 48, 30, 3)                               # desktop unit
    p.ln((-38, 14), (-12, 14), w=2.2)                     # twin floppy drives
    p.ln((-38, 22), (-12, 22), w=2.2)
    p.ci(34, 18, 3, fill=True)


@glyph("x1")
def x1(p):
    p.poly([(-46, 0), (-40, -34), (40, -34), (46, 0)])    # slanted unit
    p.rr(-26, -26, 6, -8, 2, w=2.0)                       # tape window
    p.ci(-18, -17, 3.5, w=1.6)
    p.ci(-2, -17, 3.5, w=1.6)
    p.dots([(20, -18), (30, -18)], 2.2)
    p.rr(-50, 8, 50, 34, 3)                               # keyboard
    p.keys(-44, 14, 44, 30, 12, 2)


@glyph("x68000")
def x68000(p):
    p.ellarc(-26, -50, 26, -30, 180, 360, w=3.0)          # carrying handle
    p.rr(-30, -40, -2, 44, 3)                             # twin towers
    p.rr(2, -40, 30, 44, 3)
    for y in (-26, -16):                                  # floppy slots
        p.ln((-24, y), (-8, y), w=2.0)
        p.ln((8, y), (24, y), w=2.0)
    p.ci(16, 30, 2.4, fill=True)


@glyph("ti99")
def ti99(p):
    p.rr(-52, -16, 52, 22, 3)
    p.ln((-52, -6), (52, -6), w=2.0)                      # metal top plate
    p.keys(-46, 0, 18, 18, 8, 3)
    p.cart(26, -32, 48, -4)                               # cartridge on the right
    p.rr(28, 4, 46, 16, 2, w=1.6)


@glyph("thomson")
def thomson(p):
    p.rr(-52, -2, 52, 28, 3)
    p.keys(-46, 4, 46, 24, 12, 3)
    p.ln((-36, -36), (4, -14), w=4.2)                     # light pen
    p.ci(-38, -37, 2.2, fill=True)
    p.ellarc(4, -26, 30, 2, 270, 90, w=1.6)               # cable


@glyph("tvc")
def tvc(p):
    p.rr(-30, -42, 30, -6, 4)                             # tape recorder
    p.rr(-20, -34, 20, -18, 2, w=1.8)
    p.ci(-10, -26, 4, w=1.6)
    p.ci(10, -26, 4, w=1.6)
    p.dots([(-16, -11), (-6, -11), (4, -11), (14, -11)], 1.6)
    p.rr(-52, 2, 52, 32, 3)                               # computer
    p.keys(-46, 8, 46, 28, 12, 3)


@glyph("dos")
def dos(p):
    p.rr(-42, -40, 42, 18, 4)                             # monitor
    p.screen(-36, -34, 36, 12, r=2)
    p.ln((-28, -24), (-20, -18), (-28, -12), w=2.4)       # prompt  >_
    p.ln((-16, -10), (-4, -10), w=2.4)
    p.ln((-6, 18), (-10, 26), w=2.4)
    p.ln((6, 18), (10, 26), w=2.4)
    p.rr(-46, 28, 46, 38, 3)                              # keyboard
    p.vents(-40, 33, 40, 33, 1, w=1.4)


# =====================================================================================
# Fantasy consoles, game engines, ports
# =====================================================================================

@glyph("pico-8")
def pico8(p):
    p.rr(-40, -40, 40, 40, 10)
    p.pixels(-28, -24, 8, [".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."])


@glyph("tic80")
def tic80(p):
    p.rr(-44, -36, 44, 30, 6)                             # tiny computer
    p.pixels(-20, -26, 5, ["..XXXX..", ".X....X.", "X.X..X.X", "X......X", "X.X..X.X", "X..XX..X", ".X....X.", "..XXXX.."])
    p.rr(-30, 30, 30, 40, 2)


@glyph("wasm4")
def wasm4(p):
    p.rr(-40, -40, 40, 40, 6)
    p.rr(-30, -30, -2, -2, 2, fill=True)                  # 4-color palette
    p.rr(2, -30, 30, -2, 2, soft=True)
    p.rr(-30, 2, -2, 30, 2, soft=True)
    p.rr(2, 2, 30, 30, 2, w=2.4)


@glyph("lowresnx")
def lowresnx(p):
    p.rr(-44, -38, 44, 24, 12)                            # rounded CRT
    p.pixels(-32, -24, 6, ["..........", "...XX.....", "..XXXX..X.", ".XXXXXXXXX", "XXXXXXXXXX"])
    p.ln((-10, 24), (-14, 36), w=2.6)
    p.ln((10, 24), (14, 36), w=2.6)
    p.ln((-24, 38), (24, 38), w=2.6)


@glyph("puzzlescript")
def puzzlescript(p):
    p.rr(-34, -34, 34, 34, 3)                             # crate
    p.rr(-26, -26, 26, 26, 1, w=2.2)
    p.ln((-26, -26), (26, 26), w=2.6)
    p.ln((26, -26), (-26, 26), w=2.6)


@glyph("solarus")
def solarus(p):
    p.ci(0, 0, 16)
    p.ci(0, 0, 6, fill=True)
    for a in range(0, 360, 45):
        r = math.radians(a)
        p.ln((24 * math.cos(r), 24 * math.sin(r)), (36 * math.cos(r), 36 * math.sin(r)), w=3.2)


@glyph("openbor")
def openbor(p):
    # a fist
    p.rr(-26, -24, 30, 18, 8)
    for x in (-18, -4, 10, 24):
        p.ln((x - 2, -24), (x - 2, -6), w=2.0)
    p.rr(-34, -10, -20, 14, 5, w=2.8)                     # thumb
    p.rr(-18, 18, 22, 40, 3)                              # wrist
    p.ln((-44, -30), (-36, -24), w=2.2)                   # impact lines
    p.ln((-46, -12), (-38, -12), w=2.2)
    p.ln((40, -34), (34, -28), w=2.2)


@glyph("onscripter")
def onscripter(p):
    p.rr(-46, -36, 46, 20, 10)                            # speech bubble
    p.poly([(-24, 20), (-30, 38), (-8, 20)])
    p.vents(-34, -22, 34, -22, 1, w=2.4)
    p.vents(-34, -10, 34, -10, 1, w=2.4)
    p.ln((-34, 2), (8, 2), w=2.4)


@glyph("scummvm")
def scummvm(p):
    # point-and-click cursor over a verb panel
    p.poly([(-30, -42), (-30, 8), (-18, -4), (-8, 18), (0, 14), (-10, -8), (6, -8)])
    for i, (x, y) in enumerate(((-44, 26), (-14, 26), (16, 26))):
        p.rr(x, y, x + 26, y + 14, 3, w=2.2)
    p.rr(10, -30, 44, 8, 3, w=2.0)                        # inventory box
    p.ci(27, -11, 6, w=1.8)


@glyph("easyrpg")
def easyrpg(p):
    p.ln((-30, 34), (28, -28), w=5.0)                     # sword blade
    p.ln((28, -28), (36, -38), w=3.0)
    p.ln((-26, 16), (-10, 32), w=4.0)                     # cross-guard
    p.ln((-36, 40), (-28, 32), w=4.0)                     # hilt
    p.ci(-38, 42, 3.2, fill=True)


@glyph("doom")
def doom(p):
    # skull
    p.ellarc(-34, -42, 34, 22, 160, 380)
    p.ln((-32, 0), (-24, 18), (-24, 32), (24, 32), (24, 18), (32, 0))
    p.el(-24, -14, -6, 4, fill=True)
    p.el(6, -14, 24, 4, fill=True)
    p.poly([(-4, 18), (0, 8), (4, 18)], fill=True)
    p.vents(-12, 24, 12, 32, 1, w=1.0)
    for x in (-12, -4, 4, 12):
        p.ln((x, 24), (x, 32), w=1.8)


@glyph("wolf")
def wolf(p):
    # first-person corridor
    p.rr(-46, -38, 46, 38, 3)
    p.ln((-46, -38), (-16, -12))
    p.ln((46, -38), (16, -12))
    p.ln((-46, 38), (-16, 12))
    p.ln((46, 38), (16, 12))
    p.rr(-16, -12, 16, 12, 1, w=2.4, soft=True)
    p.vents(-36, -22, -36, 22, 1, vertical=True, w=1.6)
    p.vents(36, -22, 36, 22, 1, vertical=True, w=1.6)
    p.vents(-26, -14, -26, 14, 1, vertical=True, w=1.4)
    p.vents(26, -14, 26, 14, 1, vertical=True, w=1.4)


@glyph("cavestory")
def cavestory(p):
    # cave mouth with stalactites
    p.ln((-50, 36), (-40, -6), (-24, -28), (0, -36), (24, -28), (40, -6), (50, 36))
    p.ln((-50, 36), (50, 36), w=2.6)
    for x, h in ((-20, 12), (-6, 18), (10, 14), (22, 10)):
        p.poly([(x - 5, -28 if abs(x) > 15 else -32), (x, -28 + h), (x + 5, -28 if abs(x) > 15 else -32)], fill=True)
    p.ci(0, 22, 5, fill=True)                             # a little hero
    p.ln((0, 27), (0, 34), w=2.4)


@glyph("ports")
def ports(p):
    # an anchor: games brought into port
    p.ci(0, -34, 7, w=3.6)                                # ring
    p.ln((0, -27), (0, 34), w=4.6)                        # shank
    p.ln((-18, -14), (18, -14), w=4.2)                    # stock
    p.arc(0, 2, 34, 25, 155, w=4.6)                       # arms
    for sx in (-1, 1):                                    # flukes
        x, y = sx * 31, 16
        p.poly([(x - sx * 1, y - 12), (x + sx * 9, y + 4), (x - sx * 9, y + 6)], fill=True)


# =====================================================================================
# Not systems of their own
# =====================================================================================

@glyph("android")
def android(p):
    d = p.d
    d.pieslice([*p.p(-34, -30), *p.p(34, 38)], 180, 360, fill=p.c)
    for ex in (-13, 13):
        d.ellipse([*p.p(ex - 4.5, -12.5), *p.p(ex + 4.5, -3.5)], fill=(12, 28, 64, 255))
    p.ln((-20, -26), (-30, -42))
    p.ln((20, -26), (30, -42))
    p.rr(-34, 10, 34, 40, 6, fill=True)


@glyph("fallback")
def fallback(p):
    """Systems added to systems.json later, until they get their own picture."""
    p.cart(-26, -36, 26, 36)
    p.vents(-16, 24, 16, 30, 2, w=1.6)


@glyph("search")
def search(p):
    p.ci(-8, -8, 24, w=4.4)
    p.ln((10, 10), (34, 34), w=6.0)
