"""Pocket: a 4-shade green LCD handheld homage. Everything is drawn on a coarse grid of cells,
scaled up with hard edges and kept to the four shades; the selected thing is inverted."""
import functools
import math
import struct
import wave

from PIL import Image, ImageChops, ImageDraw, ImageFont

from theme_kit import APPS, FONTS, GLYPHS, SS, Pen, Style, hexa, hexc, rgba

D0 = (0x0F, 0x38, 0x0F)   # ink
D1 = (0x30, 0x62, 0x30)   # shade
L1 = (0x8B, 0xAC, 0x0F)   # light shade
L0 = (0x9B, 0xBC, 0x0F)   # paper
INVERT = {D0: L0, D1: L1, L1: D1, L0: D0}
CHARS = {"#": D0, "=": D1, "+": L1, ".": L0, "*": L0}
SHINE = "*"  # paper in every state: kept when a sprite is remapped
CLEAR = (0, 0, 0, 0)
# Stepped corners: how far each row from a corner is inset, by radius (cells).
CORNER = {0: [], 1: [1], 2: [2, 1], 3: [3, 1, 1], 4: [4, 2, 1, 1], 5: [5, 3, 2, 1, 1]}
FONT = "PressStart2P-Regular.ttf"


@functools.lru_cache(None)
def font8(size=8):
    """The pixel font at its native size: one font pixel per cell."""
    return ImageFont.truetype(str(FONTS / FONT), size)


class Px:
    """A canvas of u x u cells; out() scales it up with nearest neighbour to w x h pixels."""

    def __init__(self, w, h, u=2):
        self.w, self.h, self.u = w, h, u
        self.cw, self.ch = -(-w // u), -(-h // u)
        self.im = Image.new("RGBA", (self.cw, self.ch), CLEAR)
        self.d = ImageDraw.Draw(self.im)
        self.d.fontmode = "1"

    def rect(self, x0, y0, x1, y1, c):
        """Inclusive cell rectangle; c an RGB shade or CLEAR."""
        if x1 >= x0 and y1 >= y0:
            self.d.rectangle((x0, y0, x1, y1), fill=rgba(c) if len(c) == 3 else c)

    def box(self, x0, y0, x1, y1, c, r=0):
        steps = CORNER[r]
        for i, inset in enumerate(steps):
            self.rect(x0 + inset, y0 + i, x1 - inset, y0 + i, c)
            self.rect(x0 + inset, y1 - i, x1 - inset, y1 - i, c)
        self.rect(x0, y0 + len(steps), x1, y1 - len(steps), c)

    def frame(self, x0, y0, x1, y1, c, fill, r=0, t=1):
        self.box(x0, y0, x1, y1, c, r)
        self.box(x0 + t, y0 + t, x1 - t, y1 - t, fill, max(0, r - t))

    def text(self, xy, s, c, anchor="mm", size=8):
        self.d.text(xy, s, font=font8(size), fill=rgba(c), anchor=anchor)

    def paste(self, im, x, y):
        self.im.alpha_composite(im, (int(x), int(y)))

    def out(self):
        return self.im.resize((self.cw * self.u, self.ch * self.u), Image.NEAREST).crop((0, 0, self.w, self.h))


def remap(im, mapping):
    """Swap shades (RGB -> RGB) in a four-shade image, alpha kept."""
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a:
                c = mapping.get((r, g, b))
                if c:
                    px[x, y] = c + (a,)
    return out


def invert(im):
    return remap(im, INVERT)


def scale(im, k):
    return im.resize((im.width * k, im.height * k), Image.NEAREST)


# ---- sprites ------------------------------------------------------------------------------

class Grid:
    """Character grid for a sprite: '#' ink, '=' shade, '+' light shade, '.' paper, '*' shine, ' ' clear."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.c = [[" "] * w for _ in range(h)]

    def fill(self, inside, ch="."):
        for y in range(self.h):
            for x in range(self.w):
                if inside(x + 0.5, y + 0.5):
                    self.c[y][x] = ch
        return self

    def on(self, x, y):
        return 0 <= x < self.w and 0 <= y < self.h and self.c[y][x] != " "

    def outline(self, shade=True):
        """Ink on every filled cell next to a clear one; light shade along the inner bottom-right rim."""
        edge = [[self.on(x, y) and not all(self.on(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                 for x in range(self.w)] for y in range(self.h)]
        for y in range(self.h):
            for x in range(self.w):
                if edge[y][x]:
                    self.c[y][x] = "#"
                elif shade and self.on(x, y) and ((x + 1 < self.w and edge[y][x + 1]) or (y + 1 < self.h and edge[y + 1][x])):
                    self.c[y][x] = "+"
        return self

    def put(self, x, y, rows):
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch != "_" and 0 <= x + i < self.w and 0 <= y + j < self.h:
                    self.c[y + j][x + i] = ch
        return self

    def image(self, mapping=None):
        """The sprite, its shades swapped by [mapping] (RGB -> RGB) except the shine."""
        im = Image.new("RGBA", (self.w, self.h), CLEAR)
        px = im.load()
        for y in range(self.h):
            for x in range(self.w):
                ch = self.c[y][x]
                if ch in CHARS:
                    c = CHARS[ch]
                    if mapping and ch != SHINE:
                        c = mapping.get(c, c)
                    px[x, y] = rgba(c)
        return im


def rows_grid(rows):
    g = Grid(max(len(r) for r in rows), len(rows))
    return g.put(0, 0, [r.replace(" ", "_") for r in rows])


def rows_image(rows):
    return rows_grid(rows).image()


def cutout(rows):
    """Ink rows with a one-cell paper rim around them."""
    g = rows_grid(rows)
    out = Grid(g.w + 2, g.h + 2)
    ink = [(x, y) for y in range(g.h) for x in range(g.w) if g.c[y][x] == "#"]
    for x, y in ink:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                out.c[y + 1 + dy][x + 1 + dx] = "."
    for x, y in ink:
        out.c[y + 1][x + 1] = "#"
    return out


BOLT = ["___###", "__###_", "_###__", "_##___", "######", "___##_", "__###_", "__##__", "_##___", "_#____"]


def mirrored(half):
    """Rows of a left-right symmetric sprite from their left halves."""
    return [r.ljust(len(half[0])) + r.ljust(len(half[0]))[::-1] for r in half]


def s_clock():
    """Recents: an alarm clock."""
    g = Grid(20, 20)

    def face(x, y, r):
        return (x - 10) ** 2 + (y - 10.5) ** 2 <= r * r

    g.fill(lambda x, y: face(x, y, 7.6), "=")
    g.fill(lambda x, y: (x - 3.6) ** 2 + (y - 3.6) ** 2 <= 2.9 ** 2, "=")
    g.fill(lambda x, y: (x - 16.4) ** 2 + (y - 3.6) ** 2 <= 2.9 ** 2, "=")
    g.outline(shade=False)
    g.fill(lambda x, y: face(x, y, 5.7))
    g.fill(lambda x, y: face(x, y, 5.7) and (x - 10) + (y - 10.5) > 3.8, "+")
    g.put(5, 17, ["#"]).put(4, 18, ["#"]).put(14, 17, ["#"]).put(15, 18, ["#"])
    g.put(9, 7, ["#", "#", "#", "#"]).put(10, 10, ["###"])
    g.put(9, 5, ["=="]).put(14, 10, ["="]).put(9, 16, ["=="]).put(5, 10, ["="])
    return g


def s_heart():
    """Favorites: a heart, shaded on the right, with a shine on its left lobe."""
    g = rows_grid(mirrored([
        "  #####   ",
        " #=====#  ",
        "#=======# ",
        "#========#",
        "#=========",
        "#=========",
        "#=========",
        "#=========",
        " #========",
        " #========",
        "  #=======",
        "   #======",
        "    #=====",
        "     #====",
        "      #===",
        "       #==",
        "        #=",
        "         #",
    ]))
    for y in range(3, 17):
        x = max(i for i in range(g.w) if g.c[y][i] == "#") - 1
        g.c[y][x] = "+"
        if 4 <= y <= 9:
            g.c[y][x - 1] = "+"
    return g.put(2, 2, ["_**", "**", "*"])


def s_pad():
    """Games: a gamepad."""
    return rows_grid([
        "   ################   ",
        "  #................#  ",
        " #..................# ",
        "#...#............==..#",
        "#...#............==.+#",
        "#.#####.......==....+#",
        "#...#.........==....+#",
        "#...#..==.==........+#",
        "#...................+#",
        "#.......######.....++#",
        "#.....+#      #.....+#",
        "#.....+#      #.....+#",
        " #++++#        #++++# ",
        "  ####          ####  ",
    ])


def s_handheld():
    """Emulators: a pocket console."""
    return rows_grid([
        " ############# ",
        "#.............#",
        "#.===========.#",
        "#.=+++++++++=.#",
        "#.=+++++++++=.#",
        "#.=+++++++++=.#",
        "#.=+++++++++=.#",
        "#.=+++++++++=.#",
        "#.===========.#",
        "#............+#",
        "#............+#",
        "#..#.........+#",
        "#.###...==.==+#",
        "#..#....==.==+#",
        "#............+#",
        "#............+#",
        "#....==.==...+#",
        "#............+#",
        "#.........===+#",
        "#+++++++++++++#",
        " ############# ",
    ])


def s_joystick():
    """Expert: an arcade stick."""
    return rows_grid([
        "      ######      ",
        "     #=**===#     ",
        "    #=**=====#    ",
        "    #=*======#    ",
        "    #========#    ",
        "    #=======+#    ",
        "     #=====+#     ",
        "      ######      ",
        "        ##        ",
        "        ##        ",
        "        ##        ",
        "       #==#       ",
        "  ##############  ",
        " #..............# ",
        " #..........##..# ",
        "#..........#==#..#",
        "##################",
        "#++++++++++++++++#",
        "#++++++++++++++++#",
        "##################",
    ])


def s_apps():
    """Apps: four tiles, one round, one dark."""
    g = Grid(20, 20)
    for ox, oy in ((0, 0), (0, 11), (11, 11)):
        g.fill(lambda x, y, ox=ox, oy=oy: ox < x < ox + 9 and oy < y < oy + 9 and not (
            (x < ox + 1 or x > ox + 8) and (y < oy + 1 or y > oy + 8)))
    g.fill(lambda x, y: (x - 15.5) ** 2 + (y - 4.5) ** 2 <= 4.6 ** 2)
    g.outline()
    g.fill(lambda x, y: 12 < x < 19 and 12 < y < 19, "=")
    return g


def s_gear(n=20, teeth=8, r0=6.7, r1=9.7, hole=2.6):
    """Settings: a gear with tapered teeth, sampled 6 x 6 per cell so the steps come out even."""
    c = n / 2

    def inside(x, y):
        dx, dy = x - c, y - c
        r = math.hypot(dx, dy)
        if r < hole or r > r1:
            return False
        if r <= r0:
            return True
        a = (math.degrees(math.atan2(dy, dx)) - 22.5) % (360 / teeth)
        return min(a, 360 / teeth - a) <= 17 - 6 * (r - r0) / (r1 - r0)

    g = Grid(n, n)
    for y in range(n):
        for x in range(n):
            if sum(inside(x + (i + 0.5) / 6, y + (j + 0.5) / 6) for i in range(6) for j in range(6)) >= 18:
                g.c[y][x] = "."
    g.outline(shade=False)
    for y in range(n):
        for x in range(n):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            if g.c[y][x] == "." and math.hypot(dx, dy) <= hole + 1.9:
                g.c[y][x] = "="
            elif g.c[y][x] == "." and dx + dy > 6:
                g.c[y][x] = "+"
    return g


def s_palette():
    """Themes app: a paint palette with a thumb notch and four wells."""
    g = Grid(22, 18)
    g.fill(lambda x, y: ((x - 11) / 10.6) ** 2 + ((y - 9) / 8.6) ** 2 <= 1 and (x - 21.6) ** 2 + (y - 11.2) ** 2 > 3.4 ** 2, "+")
    g.outline(shade=False)
    for x, y in ((4, 8), (6, 4), (11, 3), (7, 12)):
        g.put(x, y, ["##", "##"])
    return g


def s_picture():
    """Gallery app: a framed picture, a sun and two peaks."""
    g = Grid(22, 18).fill(lambda x, y: True, "+").outline(shade=False)
    g.put(16, 3, ["##", "##"])
    for y in range(4, 17):
        for x in range(1, 21):
            if y - 4 >= abs(x - 7) or y - 8 >= abs(x - 15):
                g.c[y][x] = "#" if g.c[y - 1][x] == "+" or y == 4 else "="
    return g


# Selected tiles invert their sprite; these turn the light shading into the dark body instead,
# so the clock face and the gear stay whole.
SOLID = {D0: L0, D1: L1, L1: D0, L0: D0}
FOCUS_MAP = {"recent": SOLID, "setting": SOLID}
APP_SPRITES = {"retroarch": s_joystick, "themes": s_palette, "gallery": s_picture}
SPRITES = {"recent": s_clock, "favorite": s_heart, "game": s_pad, "emulators": s_handheld,
           "retroarch": s_joystick, "app": s_apps, "setting": s_gear}


# ---- system pictures ------------------------------------------------------------------------

def glyph_cells(draw_fn, cy, cell=2, w=4.0):
    """A line drawing (GLYPHS / APPS style) sampled onto cells: 'line' and 'soft' masks."""
    k = 4
    big = Image.new("RGBA", (120 * k, 130 * k), CLEAR)
    pen = Pen(ImageDraw.Draw(big), 60, cy, (255, 0, 0, 255), k, w=w)
    pen.soft_c = (0, 255, 0, 255)  # direct writes: red = line, green = soft fill
    draw_fn(pen)
    small = big.resize((120 // cell, 130 // cell), Image.BOX)
    r, g, _, _ = small.split()
    line = r.point(lambda v: 255 if v >= 80 else 0)
    soft = g.point(lambda v: 255 if v >= 120 else 0)
    return line, soft


def enclosed(mask):
    """Cells [mask] encloses (not reachable from outside without crossing it)."""
    m = Image.new("L", (mask.width + 2, mask.height + 2), 0)
    m.paste(mask, (1, 1))
    ImageDraw.floodfill(m, (0, 0), 128)
    return m.point(lambda v: 255 if v == 0 else 0).crop((1, 1, mask.width + 1, mask.height + 1))


def compose_cells(line, soft, ink=D0, body=L1, dark=D1):
    """A four-shade sprite: ink lines, the enclosed body in a light shade, soft areas dark."""
    out = Image.new("RGBA", line.size, CLEAR)
    out.paste(rgba(body), (0, 0), enclosed(ImageChops.lighter(line, soft)))
    out.paste(rgba(dark), (0, 0), soft)
    out.paste(rgba(ink), (0, 0), line)
    return out


class Theme(Style):
    name = "Pocket"
    author = "MainUI"
    description = "A four-shade green LCD handheld: hard pixels, dot-matrix paper and inverted selections."

    bg_top = bg_bottom = L0
    accent = D0
    accent2 = D1
    highlight = D0
    text = D0
    muted = D0
    glyph_n = D0
    glyph_f = L0
    # MainUI fills the pop-up title strip with the panel colour darkened by 20 %: this gives
    # exactly the light shade. Toasts show it as is, a touch brighter than the paper.
    panel = (0xAE, 0xD7, 0x13)
    panel_alpha = 255
    bar = L1

    font_file = FONT
    font_license = "OFL-PressStart2P.txt"
    label_font, label_license = "Silkscreen-Bold.ttf", "OFL-Silkscreen.txt"
    title_size, hint_size, list_size, grid1x4, grid3x4, battery_size = 24, 16, 16, 16, 12, 16

    sel_icons = True
    icon_quant = 8

    # ---- big pictures ---------------------------------------------------------------------
    def background(self, w=640, h=480):
        im = Image.new("RGBA", (w, h), rgba(L0))
        d = ImageDraw.Draw(im)
        for y in range(3, h, 4):
            for x in range(3, w, 4):
                d.point((x, y), fill=rgba(L1))
        return im

    def header_bar(self):
        p = Px(640, 60)
        p.rect(0, 0, 319, 26, L1)
        p.rect(0, 27, 319, 27, D0)
        p.rect(0, 28, 319, 28, D1)
        return p.out()

    def footer_bar(self):
        return self.header_bar().transpose(Image.FLIP_TOP_BOTTOM)

    def logo(self):
        p = Px(160, 40)
        hh = Grid(9, 13)
        hh.fill(lambda x, y: x <= 9 and y <= 13 and not (x > 7 and y > 11)).outline(shade=False)
        hh.put(1, 1, ["=======", "=+++++=", "=+++++=", "=+++++=", "======="])
        hh.put(1, 8, ["_#_", "###", "_#_"]).put(6, 8, ["_#", "#_"])
        p.paste(hh.image(), 1, 3)
        p.text((14, 10), "POCKET", D0, anchor="lm")
        return p.out()

    # ---- lists ------------------------------------------------------------------------------
    def selection(self, h):
        """A light bar with an inverted tab holding the cursor; ends kept, middle stretched.
        Inset 8 px so it sits inside the pop-up's double rule."""
        p = Px(640, h)
        ch = p.ch
        p.box(5, 2, 315, ch - 1, D1, 2)
        p.frame(4, 1, 314, ch - 2, D0, L1, r=2)
        p.box(4, 1, 8, ch - 2, D0, 2)
        p.rect(7, 1, 8, ch - 2, D0)
        cy = ch // 2
        for i, n in enumerate((1, 2, 3, 2, 1)):
            p.rect(5, cy - 2 + i, 5 + n - 1, cy - 2 + i, L0)
        return p.out()

    def divider(self):
        p = Px(640, 4)
        for x in range(6, 314, 4):
            p.rect(x, 1, x, 1, D1)
        return p.out()

    # ---- tiles ------------------------------------------------------------------------------
    def window(self, w, h, focused, screen=None):
        """A pixel window w x h px (a Px); focused ones are inverted. [screen]: cell box of an inset LCD."""
        p = Px(w, h)
        x1, y1 = p.cw - 3, p.ch - 3
        p.box(3, 3, x1 + 1, y1 + 1, D1, 2)  # shadow
        if focused:
            p.frame(2, 2, x1, y1, D0, L0, r=2)
            p.frame(3, 3, x1 - 1, y1 - 1, L0, D0, r=1)
        else:
            p.frame(2, 2, x1, y1, D0, L0, r=2)
        if screen:
            sx0, sy0, sx1, sy1 = screen
            p.frame(sx0, sy0, sx1, sy1, *((L1, D1) if focused else (D1, L1)))
        return p

    TILE_SCREEN = (9, 14, 68, 86)

    def tile_base(self, focused):
        """A pocket console: the LCD on top, the label below it, its controls at the bottom."""
        p = self.window(156, 278, focused, self.TILE_SCREEN)
        c = D1 if focused else L1  # moulded into the body, quieter than the picture
        p.rect(17, 117, 19, 125, c)
        p.rect(14, 120, 22, 122, c)
        for bx, by in ((53, 120), (60, 116)):
            p.box(bx, by, bx + 5, by + 5, c, 1)
        p.box(33, 128, 37, 129, c, 1)
        p.box(41, 128, 45, 129, c, 1)
        p.rect(5, 24, 6, 25, L0 if focused else L1)  # power LED: lit on the selected one
        return p.out()

    def tile_glyph(self, key, focused):
        sp = scale(SPRITES[key]().image(FOCUS_MAP.get(key, INVERT) if focused else None), 4)
        im = Image.new("RGBA", (156, 278), CLEAR)
        x0, y0, x1, y1 = (v * 2 for v in self.TILE_SCREEN)
        im.alpha_composite(sp, ((156 - sp.width) // 2 // 2 * 2, ((y0 + y1 + 2) // 2 - sp.height // 2) // 2 * 2))
        return im

    def card(self, w, h, focused):
        """Games grid cell: the frame one cell further out than a tile's, clear of the name's box."""
        p = Px(w, h)
        x1, y1 = p.cw - 2, p.ch - 3
        p.box(2, 2, x1 + 1, y1 + 1, D1, 2)
        p.frame(1, 1, x1, y1, D0, D0 if focused else L0, r=2)
        return p.out()

    def preview_frame(self):
        p = Px(250, 360)
        p.frame(0, 0, p.cw - 1, p.ch - 1, D0, L0, r=1)
        p.frame(2, 2, p.cw - 3, p.ch - 3, D1, L1)
        return p.out()

    # ---- buttons and small parts ------------------------------------------------------------
    def button(self, label, primary):
        p = Px(40, 44)
        cx, cy, r = 10, 11, 9.3
        for y in range(p.ch):
            for x in range(p.cw):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if d <= r:
                    p.rect(x, y, x, y, D0 if (primary or d > r - 1.1) else L0)
        p.text((cx, cy), label, L0 if primary else D0)
        return p.out()

    def pill(self, label, w, h=48):
        cw = int(font8().getlength(label)) + 8
        p = Px(cw * 2, 44)
        p.frame(0, 4, cw - 1, 17, D0, L0, 3)
        p.text((cw / 2, 11), label, D0)
        return p.out()

    def dot(self, active):
        p = Px(12, 12)
        if active:
            p.box(0, 0, 5, 5, D0, 1)
        else:
            p.frame(0, 0, 5, 5, D1, CLEAR, 1)
        return p.out()

    def toggle(self, on):
        p = Px(64, 32)
        if on:
            p.frame(0, 2, 31, 13, D0, D0, 2)
            p.box(17, 4, 29, 11, L0, 1)
        else:
            p.frame(0, 2, 31, 13, D0, L1, 2)
            p.box(2, 4, 14, 11, D1, 1)
        return p.out()

    def arrow(self, left, size=24):
        """The 36 px ones (on MainUI's dark name bar) get a paper badge; the small ones a paper rim,
        for the slot preview's dark strip."""
        p = Px(size, size)
        if size >= 36:
            p.box(0, 0, 17, 17, D0, 3)
            p.box(1, 1, 16, 16, L0, 2)
            for i in range(5):
                p.rect(6 + i, 8 - i, 6 + i, 9 + i, D0)
        else:
            tri = cutout(["".join("#" if abs(j - 4.5) <= i + 0.5 else "_" for i in range(5)) for j in range(10)])
            p.paste(tri.image(), 2, 0)
        im = p.out()
        return im if left else im.transpose(Image.FLIP_LEFT_RIGHT)

    def panel_image(self, w, h):
        """A double-ruled window. Pop-up menus fill it; the dialog (640 wide) is inset with a shadow."""
        p = Px(w, h)
        x0, y0, x1, y1 = 0, 0, p.cw - 1, p.ch - 1
        if w == 640:
            x0, y0, x1, y1 = 8, 4, p.cw - 10, p.ch - 6
            p.box(x0 + 2, y0 + 2, x1 + 2, y1 + 2, D1, 2)
        p.frame(x0, y0, x1, y1, D0, L0, r=2)
        p.frame(x0 + 2, y0 + 2, x1 - 2, y1 - 2, D0, L0, r=1)
        if w == 640:
            for x in range(x0 + 16, x1 - 15, 4):
                p.rect(x, 44, x, 44, D1)  # under the title, which MainUI writes at y 60
        return p.out()

    def folder_icon(self):
        p = Px(40, 40)
        p.paste(rows_image([
            "#######             ",
            "#=====#             ",
            "#======############ ",
            "#=================# ",
            "#==#################",
            "#=#................#",
            "#=#................#",
            "##.................#",
            "##.................#",
            "##.................#",
            "##.................#",
            "##................+#",
            "##...............++#",
            "####################",
        ]), 0, 3)
        return p.out()

    def favorite_mark(self):
        p = Px(40, 40)
        p.paste(s_heart().image(), 0, 1)
        return p.out()

    def battery(self, level, charging=False):
        p = Px(36, 40)  # MainUI writes the percentage 5 px after the canvas
        p.frame(0, 6, 14, 14, D0, L0, 1)
        p.rect(15, 8, 16, 12, D0)
        if charging:
            p.paste(cutout(BOLT).image(), 3, 4)
            return p.out()
        bars = 0 if level < 0.1 else (1 if level < 0.3 else (2 if level < 0.6 else (3 if level < 0.9 else 4)))
        if not bars:  # empty: a '!' instead of four unlit segments
            p.rect(7, 8, 7, 10, D0)
            p.rect(7, 12, 7, 12, D0)
        for i in range(4 if bars else 0):
            p.rect(2 + i * 3, 8, 3 + i * 3, 12, D0 if i < bars else L1)
        return p.out()

    def key(self, focused):
        p = Px(64, 52)
        if focused:  # light rim on the outside: the long labels (19 px, set by MainUI) reach the sides
            p.frame(0, 0, 31, 25, L0, D0, 1)
        else:
            p.frame(0, 0, 31, 25, D0, D1, 1)
        return p.out()

    def input_box(self):
        p = Px(560, 60)
        p.frame(0, 2, 279, 27, D0, L0, 1)
        p.rect(1, 3, 278, 3, L1)
        p.rect(1, 3, 1, 26, L1)
        return p.out()

    # ---- system and app icons ---------------------------------------------------------------
    def icon_cells(self, sid):
        draw = GLYPHS.get(sid) or GLYPHS["fallback"]
        line, soft = glyph_cells(draw, 66)
        return compose_cells(line, soft)

    def system_icon(self, sid, selected):
        im = self.icon_cells(sid)
        if selected:
            im = invert(im)
        return scale(im, 2)

    def app_icon(self, key):
        p = Px(96, 96, 3)
        p.frame(1, 1, 30, 30, D0, D0, 3)
        if key in APP_SPRITES:  # drawn on cells, inverted like a selected tile
            sp = APP_SPRITES[key]().image(INVERT)
            p.paste(sp, (32 - sp.width) // 2, (33 - sp.height) // 2)
            return p.out()
        big = Image.new("RGBA", (96 * SS, 96 * SS), CLEAR)
        APPS[key](ImageDraw.Draw(big), 48, 48, 96 * 0.27, (255, 255, 255, 255), 96 * 0.06)
        line = big.resize((32, 32), Image.BOX).split()[3].point(lambda v: 255 if v >= 90 else 0)
        p.paste(compose_cells(line, Image.new("L", line.size, 0), ink=L0, body=D1), 0, 0)
        return p.out()

    def fonts(self):
        # (build() copies one licence; the label font's goes along as a listed file)
        return super().fonts() + [self.label_license]

    # ---- sound ----------------------------------------------------------------------------
    def sound(self, path):
        """A square-wave blip: two quick notes, falling off."""
        rate = 22050
        frames = bytearray()
        n = int(rate * 0.05)
        for i in range(n):
            t = i / rate
            f = 988 if t < 0.018 else 1319
            s = 1.0 if (t * f) % 1.0 < 0.5 else -1.0
            env = 1.0 - (i / n) ** 1.5
            frames += struct.pack("<h", int(4200 * env * s))
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wv:
            wv.setnchannels(1)
            wv.setsampwidth(2)
            wv.setframerate(rate)
            wv.writeframes(bytes(frames))

    # ---- config ---------------------------------------------------------------------------
    def config(self):
        cfg = super().config()
        cfg["batteryPercentage"]["color"] = hexc(D0)
        cfg["grid"]["font"] = self.label_font
        cfg["list"]["font"] = self.label_font  # MainUI fixes some list sizes (keyboard keys, values): the narrower face fits
        cfg["grid"]["color"] = hexc(D0)
        cfg["grid"]["selectedcolor"] = hexc(L0)
        cfg["currentpage"]["color"] = hexc(D0)
        cfg["total"]["color"] = hexc(D0)
        cfg["mainui"] = {"accent": hexc(D0), "panel": hexa(self.panel, 255), "background": hexc(L0)}
        return cfg
