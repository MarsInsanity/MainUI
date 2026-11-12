"""Approximate MainUI screens from a written theme folder, for looking at a theme quickly.

    python3 tools/theme/mock_screens.py THEME_DIR [OUT.png]

Draws the home screen, the Games grid, a game list and a pop-up menu the way MainUI lays
them out on a 640x480 screen (MainUI's own layout for themes with a "mainui" block) and
writes them side by side (1280x960). The emulator / a device is the final word; this is close.
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 640, 480
LABELS = ["Recents", "Favorites", "Games", "Emulators", "Expert", "Apps", "Settings"]
KEYS = ["recent", "favorite", "game", "emulators", "retroarch", "app", "setting"]
GRID = [("gb", "Game Boy"), ("gba", "Game Boy Advance"), ("snes", "Super Nintendo"), ("psx", "PlayStation"),
        ("ps2", "PlayStation 2"), ("n64", "Nintendo 64"), ("nds", "Nintendo DS"), ("switch", "Switch")]
GAMES = ["Advance Wars", "Castlevania - Aria of Sorrow", "Golden Sun", "Kirby & The Amazing Mirror",
         "Mario Kart - Super Circuit", "Metroid Fusion"]


class Theme:
    def __init__(self, d):
        self.d = Path(d)
        self.cfg = json.loads((self.d / "config.json").read_text()) if (self.d / "config.json").is_file() else {}
        self.cache = {}

    def img(self, name):
        if name not in self.cache:
            p = self.d / "skin" / f"{name}.png"
            self.cache[name] = Image.open(p).convert("RGBA") if p.is_file() else None
        return self.cache[name]

    def icon(self, sid):
        p = self.d / "icons" / f"{sid}.png"
        return Image.open(p).convert("RGBA") if p.is_file() else None

    def style(self, key, fallback=None):
        s = dict(self.cfg.get(fallback, {})) if fallback else {}
        s.update(self.cfg.get(key, {}))
        return s

    def font(self, key, size=None, fallback="title"):
        s = self.style(key, fallback)
        f = s.get("font", "")
        path = self.d / f
        if not path.is_file():
            path = Path(__file__).resolve().parents[2] / "app/src/main/assets/fonts" / Path(f).name
        sz = size if size is not None else s.get("size", 24)
        try:
            return ImageFont.truetype(str(path), max(1, int(sz)))
        except OSError:
            return ImageFont.load_default()

    def color(self, key, field="color", default="#FFFFFF", fallback="title"):
        s = self.style(key, fallback)
        v = s.get(field, default)
        v = v.lstrip("#")
        return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4)) + (255,)

    def mainui(self, key, default):
        v = self.cfg.get("mainui", {}).get(key)
        if not v:
            return default
        v = v.lstrip("#")
        if len(v) == 8:
            return (int(v[2:4], 16), int(v[4:6], 16), int(v[6:8], 16), int(v[0:2], 16))
        return (int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16), 255)


def paste(dst, im, x, y, w=None, h=None):
    if im is None:
        return
    if w is not None and h is not None and (int(w), int(h)) != im.size:
        im = im.resize((max(1, int(round(w))), max(1, int(round(h)))), Image.LANCZOS)
    dst.alpha_composite(im, (int(round(x)), int(round(y))))


def fit(dst, im, x, y, w, h):
    if im is None:
        return
    s = min(w / im.width, h / im.height)
    paste(dst, im, x + (w - im.width * s) / 2, y + (h - im.height * s) / 2, im.width * s, im.height * s)


def text(d, s, font, x, cy, color, align="left", maxw=None, bold=False):
    if maxw:
        while len(s) > 3 and d.textlength(s, font=font) > maxw:
            s = s[:-2] + "…" if not s.endswith("…") else s[:-2] + "…"
    anchor = {"left": "lm", "center": "mm", "right": "rm"}[align]
    # (MainUI draws list text bold: Android fakes it for fonts that aren't)
    kw = {"stroke_width": 1, "stroke_fill": color} if bold and "bold" not in str(getattr(font, "path", "")).lower() else {}
    d.text((x, cy), s, font=font, fill=color, anchor=anchor, **kw)
    return d.textlength(s, font=font)


def base(t, title=None, logo=False):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    bg = t.img("background")
    if bg is not None:
        paste(im, bg.rotate(180), 0, 0, W, H)
    else:
        im.paste(t.mainui("background", (30, 30, 32, 255)), (0, 0, W, H))
    paste(im, t.img("bg-title"), 0, 0, W, 60)
    d = ImageDraw.Draw(im)
    if logo and t.img("miyoo-topbar") is not None:
        lg = t.img("miyoo-topbar")
        paste(im, lg, 20, 30 - lg.height / 2)
    bat = t.img("power-full-icon")
    bx = W - 44
    if bat is not None:
        bs = t.cfg.get("batteryPercentage", {})
        if bs.get("visible"):
            f = t.font("batteryPercentage", bs.get("size", 18), "hint")
            tw = d.textlength("87%", font=f)
            left = bx - (bat.width + 5 + tw) / 2
            over = left + bat.width + 5 + tw - (W - 16)
            if over > 0:
                left -= over
            paste(im, bat, left, 30 - bat.height / 2)
            text(d, "87%", f, left + bat.width + 5, 30, t.color("batteryPercentage", default="#FFFFFF", fallback="hint"))
        else:
            paste(im, bat, bx - bat.width / 2, 30 - bat.height / 2)
    if title:
        text(d, title, t.font("title"), W / 2, 29, t.color("title"), "center")
    return im


def footer(t, im, hints, counter=None, covering=False):
    """MainUI's footer: hints fitted into the bar (tighter, then smaller, then fewer), then n/total."""
    if covering:
        bg = t.img("background")
        if bg is not None:
            full = bg.rotate(180).resize((W, H), Image.LANCZOS)
            im.paste(full.crop((0, H - 60, W, H)), (0, H - 60))
    paste(im, t.img("tips-bar-bg"), 0, H - 60, W, 60)
    d = ImageDraw.Draw(im)
    names = {"A": "icon-A-54", "B": "icon-B-54", "X": "icon-x", "Y": "icon-y"}
    hide = t.cfg.get("hideLabels", {}).get("hints", False)
    hs = t.style("hint", "title")
    size = hs.get("size", 30)
    col = t.color("hint", fallback="title")

    def icon_w(b, sc):
        ic = t.img(names[b])
        if ic is None:
            return 44 * sc
        return ic.width * sc if b in "AB" else ic.width * 44 / ic.height * sc

    def width(n, gap, sc):
        f = t.font("hint", size * sc, "title")
        return sum(icon_w(b, sc) + 5 * sc + (0 if hide else d.textlength(l, font=f)) for b, l in hints[:n]) + gap * max(0, n - 1)

    cw = 0
    if counter:
        f = t.font("hint", fallback="title")
        cw = d.textlength(counter[0] + "/" + counter[1], font=f)
    with_c = W - 40 - cw - (12 if cw else 0)
    n, gap, sc = len(hints), 30, 1.0
    done = False
    while n > 0 and not done:
        for g_, mins, room in ((30, 1.0, with_c), (16, 1.0, with_c), (16, 0.82, with_c), (16, 0.72, W - 40)):
            w1 = width(n, g_, 1.0)
            s_ = 1.0 if w1 <= room else min(1.0, (room - g_ * (n - 1)) / (w1 - g_ * (n - 1)))
            if s_ >= mins:
                gap, sc, done = g_, s_, True
                break
        if not done:
            if n <= 2:
                gap, sc, done = 16, 0.72, True
            else:
                n -= 1
    x = 20
    f = t.font("hint", size * sc, "title")
    for b, label in hints[:n]:
        ic = t.img(names[b])
        if ic is not None:
            h = ic.height * sc if b in "AB" else 44 * sc
            w = icon_w(b, sc)
            paste(im, ic, x, H - 30 - h / 2, w, h)
        x += icon_w(b, sc) + 5 * sc
        if not hide:
            x += text(d, label, f, x, H - 31, col)
        x += gap
    end = x - gap
    if counter:
        cur, tot = counter
        f = t.font("hint", fallback="title")
        if W - 20 - cw >= end + 12:
            tw = text(d, tot, f, W - 20, H - 31, t.color("total", default="#FFFFFF", fallback="hint"), "right")
            text(d, cur + "/", f, W - 20 - tw, H - 31, t.color("currentpage", default="#FFFFFF", fallback="hint"), "right")


def dots(t, im, count, active, cy):
    a, n = t.img("dot-a"), t.img("dot-n")
    dw = a.width if a else 14
    gap = 10
    total = count * dw + (count - 1) * gap
    x = W / 2 - total / 2
    for i in range(count):
        b = a if i == active else n
        if b is not None:
            paste(im, b, x, cy - b.height / 2)
        x += dw + gap


def home(st_or_none, d, selected=1):
    t = Theme(d)
    im = base(t, logo=True)
    dd = ImageDraw.Draw(im)
    g = t.cfg.get("grid", {})
    f = t.font("grid", g.get("grid1x4", 24))
    hide = t.cfg.get("hideLabels", {}).get("icons", False)
    for i in range(4):
        sel = i == selected
        tile = t.img(f"ic-{KEYS[i]}-{'f' if sel else 'n'}")
        x, y = 8 + i * 156, 90
        paste(im, tile, x, y, 156, 278)
        if not hide:
            text(dd, LABELS[i], f, x + 78, y + 195, t.color("grid", "selectedcolor" if sel else "color", fallback=None),
                 "center", maxw=148)
    dots(t, im, 7, selected, 396)
    footer(t, im, [("A", "SELECT")])
    return im


def grid(d, selected=2):
    t = Theme(d)
    im = base(t, "Games")
    dd = ImageDraw.Draw(im)
    g = t.cfg.get("grid", {})
    f = t.font("grid", g.get("grid3x4", 18))
    y0 = 60 + (330 - 340) / 2
    for i, (sid, name) in enumerate(GRID):
        sel = i == selected
        x, y = 10 + (i % 4) * 155, y0 + (i // 4) * 170
        paste(im, t.img("bg-game-item-f" if sel else "bg-game-item-n"), x, y, 155, 170)
        fit(im, t.icon(sid), x + 10, y + 8, 135, 124)
        text(dd, name, f, x + 77.5, y + 148, t.color("grid", "selectedcolor" if sel else "color", fallback=None),
             "center", maxw=147)
    dots(t, im, 4, 0, H - 60 - 18)
    footer(t, im, [("A", "SELECT"), ("B", "BACK")], ("3", "25"))
    return im


def selection(t, im, x, y, w, h, large=False):
    b = t.img("bg-list-l" if large else "bg-list-s")
    if b is None:
        return
    unit = 640 / b.width
    if abs(w - b.width * unit) < 1:
        paste(im, b, x, y, w, h)
        return
    cap = max(1, min(b.width * 24 // 640, max(1, b.width // 4)))
    capw = min(cap * unit, w / 2)
    paste(im, b.crop((0, 0, cap, b.height)), x, y, capw, h)
    paste(im, b.crop((cap, 0, b.width - cap, b.height)), x + capw, y, w - 2 * capw, h)
    paste(im, b.crop((b.width - cap, 0, b.width, b.height)), x + w - capw, y, capw, h)


def gamelist(d, selected=1):
    t = Theme(d)
    im = base(t, "Game Boy Advance")
    dd = ImageDraw.Draw(im)
    f = t.font("list", fallback="title")
    col = t.color("list", fallback="title")
    for i, name in enumerate(GAMES):
        y = 60 + i * 60
        paste(im, t.img("div-line-h"), 0, y, 640, 4)
        if i == selected:
            selection(t, im, 0, y + 4, 640, 56)
        x = 20
        if i == 0:
            fo = t.img("icon-folder")
            if fo is not None:
                fit(im, fo, x, y + 4 + 28 - 20, 40, 40)
                x += 57
        text(dd, name if i else "Hacks", f, x, y + 4 + 28, col, maxw=560, bold=True)
        if i == 3:
            mk = t.img("ic-favorite-mark")
            if mk is not None:
                paste(im, mk, 600 - mk.width, y + 32 - mk.height / 2)
    footer(t, im, [("A", "OPEN"), ("B", "BACK"), ("X", "SEARCH"), ("Y", "FAV")], ("2", "120"))
    return im


def popup(d):
    t = Theme(d)
    im = gamelist(d)
    im.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 110)))
    dd = ImageDraw.Draw(im)
    rows = 3
    w, h = 400, rows * 60 + 10
    x, top = (W - w) / 2, (H - h) / 2 + 20
    pan = t.mainui("panel", (24, 24, 26, 235))
    dk = tuple(int(c * 0.8) for c in pan[:3]) + (pan[3],)
    dd.rectangle((x, top - 50, x + w, top), fill=dk)
    tf = t.font("title")
    tf = t.font("title", int(t.style("title").get("size", 30) * 0.8))
    text(dd, "Golden Sun", tf, W / 2, top - 25, t.color("title"), "center")
    paste(im, t.img(f"bg-pop-menu-{rows}"), x, top, w, h)
    f = t.font("list", fallback="title")
    rh = (h - 10) / rows
    for i, s in enumerate(["Add to favorites", "Core: mGBA", "Scrape artwork"]):
        y = top + 5 + i * rh + (rh - 56) / 2
        if i == 0:
            selection(t, im, x, y, w, 56)
        text(dd, s, f, x + 20, y + 28, t.color("list", fallback="title"), maxw=w - 40, bold=True)
    footer(t, im, [("A", "SELECT"), ("B", "BACK")], covering=True)
    return im


def sheet(d):
    out = Image.new("RGBA", (W * 2, H * 2))
    out.alpha_composite(home(None, d), (0, 0))
    out.alpha_composite(grid(d), (W, 0))
    out.alpha_composite(gamelist(d), (0, H))
    out.alpha_composite(popup(d), (W, H))
    return out


if __name__ == "__main__":
    d = Path(sys.argv[1])
    o = Path(sys.argv[2]) if len(sys.argv) > 2 else d.parent / f"{d.name}-mock.png"
    sheet(d).convert("RGB").save(o)
    print(o)
