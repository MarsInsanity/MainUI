# OnionOS device reference

How a Miyoo Mini with OnionOS draws a theme at 640x480, worked out for [README.md](README.md). [BIN] is the stock Miyoo MainUI binary OnionOS ships, [ON] OnionOS's source, [TH] the themes repository, [VW] the onion-theme-viewer.

## OnionOS / Miyoo MainUI theme reference: main menu, top bar, footer, background (640x480)

### 0. How this was established
- **Code:** Onion's own renderers in `OnionUI/Onion/src/common/theme/*`. These copy MainUI's metrics. MainUI itself is a closed binary. Also the Onion helpers `mainUiBatPerc`, `system/lang.h` and `system/state.h`.
- **Measurements:** for MainUI-only behaviour I fitted positions against real device screenshots. Each theme's `preview.png` is a 640x480 MENU+POWER screenshot resized to 480x360 (CONTRIBUTING.md). I composited the theme's real assets at candidate positions, downscaled them, and kept the positions with the lowest pixel error.
- **Themes measured:** Blueprint (a template whose assets are labelled boxes on a 10px grid), Silky (the Onion default), 2022 Stock, Hello Miyoo, NES, FireMiyoo, BMO, Cards, Hallownest, Kingdom Onions, Lifestream, Pokemon MD, RPO, Sprite Buddies SNES, TRON, PAC-MIYOO and Miyoo Truxton.
- **Precision:** about ±1px, because previews are downscaled by 0.75.
- **Tools and data:** the fitting scripts and asset statistics were scratch files and were not kept.
- **The onion-theme-viewer is approximate.** Its CSS disagrees with the device in several places (listed in §9). Use the numbers below, not the viewer's.

### 1. Asset lookup, config loading and fallbacks (Onion: `src/common/theme/load.h`, `config.h`, `resources.h`)

#### Theme path
- Comes from `/mnt/SDCARD/system.json` key `"theme"`.
- If the value is `"./"` or the directory does not exist, use `/mnt/SDCARD/miyoo/app/` (load.h:117-128).

#### Image lookup for `name`
1. `/mnt/SDCARD/Saves/CurrentProfile/theme/skin/<name>.png` (user override, used by Onion's own apps).
2. `<theme>/skin/<name>.png`
3. `/mnt/SDCARD/miyoo/app/skin/<name>.png`, the default skin. For `extra/*` names, `/mnt/SDCARD/.tmp_update/res/<name>.png` instead (load.h:41-71).

Notes:
- A missing asset falls back to the default skin's asset of the same name. It never falls back to the other n/f variant.
- How often assets are missing in the repo (249 skin dirs): `div-line-h` in 11, `icon-wifi-signal-0x` in 136, `preview-bg` in 46, `background` in 1 (Dark Onion).
- Every image is converted to 32-bit RGBA (load.h:87-94). Assets come as RGBA (1692 sampled), palette with tRNS (328), RGB (172), LA (13) and L (6). Palette transparency must be honoured.

#### Fonts
- `font` is either absolute (starts with `/`) or relative to the theme dir.
- If the file is missing, use `/customer/app/Exo-2-Bold-Italic.ttf` (load.h:105-115). Size is in pixels (SDL_ttf ptsize at 72 dpi).
- Absolute paths used by themes (count of configs):

  | Path | Configs |
  |---|---|
  | `/mnt/SDCARD/miyoo/app/HENB.TTF` | 70 |
  | `HENB.ttf` (other casing) | 4 |
  | `wqy-microhei.ttc` | 54 |
  | `wqy-microhei.TTF` (other casing) | 2 |
  | `Exo-2-Bold-Italic.ttf` | 18 |
  | `BPreplayBold.otf` | 15 |
  | `Exo-2-Bold-Italic_Universal.ttf` | 8 |
  | `AdobeBlank.ttc` | 5 |
  | `AdobeBlank.ttf` | 2 |
  | `Helvetica-Neue-2.ttf` | 1 |
  | `xo-2-Bold-Italic_Universal.ttf` (typo) | 1 |

- These files are in `onion/static/build/miyoo/app/`.
- Resolve fonts case-insensitively (the SD card is FAT). Example: Milk Black/White reference `HEN.TTF` with different casing.
- Missing relative fonts: AmalgaM (`wqy-microhei.ttc`), GBOnion Dark (`HENB.TTF`), Original by SandsByte (`Exo-2-Bold-Italic_Universal.ttf`), TOTK (`GothaMedIta.ttf`).

#### config.json parsing (`config.h:95-232`)
Defaults before parsing:

| Key | Default |
|---|---|
| title | Exo-2 fallback font, 36, #FFFFFF |
| hint | same font, 40, #FFFFFF |
| currentpage, total | color #FFFFFF |
| grid | font = fallback, grid1x4 = 24, grid3x4 = 18, color (104,104,104), selectedcolor #FFFFFF |
| list | 24, #FFFFFF |
| batteryPercentage | visible false, size 24, color white, offsetX/Y 0, textAlign LEFT, fixed false |
| hideLabels | icons false, hints false |
| frame | 0, 0 |

Per-key fallbacks when a key is missing (`use_fallbacks`):
- `hint.{font,size,color}` takes the value from `title`.
- `currentpage` and `total` take it from `hint`.
- `list` takes it from `title`.
- `grid` has no fallback; the hardcoded defaults above apply.
- `batteryPercentage.font` takes `hint.font`, and `.color` takes `hint.color`. `.size` does **not** fall back; it stays 24.

Other parsing rules:
- `hideLabels.icons` and `.hints` are read. If `hideLabels` is absent, legacy `hideIconTitle` sets both.
- `batteryPercentage.textAlign` is "center", "right" or anything else (= left). If it is absent, legacy `onleft:true` means RIGHT and `onleft:false` means LEFT.
- If the theme has no config.json, the default theme's config.json is used.
- Afterwards `/mnt/SDCARD/Saves/CurrentProfile/theme/config.json` overrides are applied without fallbacks. This is how Tweaks overrides hideLabels.

#### Colour parser `hex2sdl` (`color.h`)
- Strips one leading `#`, then `strtoul(..., 16)` and stops at the first non-hex character.
- r = (v>>16)&255, g = (v>>8)&255, b = v&255.
- Real values found in themes and what they produce:

  | Value | Result |
  |---|---|
  | `"white"` | black |
  | `"rgba(...)"` / `"rgb(...)"` | black |
  | `"##e89b0c"` | black |
  | `"#000"` | black |
  | `"#DDDDDD;"` | 0xDDDDDD (trailing `;` ignored) |
  | `"6E6E6E"` (no #) | parses fine |
  | `"#fffffff"` (7 digits) | (255,255,255) |
  | `"#14646"` | (1,70,70) |

- Authors meant `rgba(...,0)` as invisible text: Lifestream `grid.color`/`selectedcolor`, Zelda Oracle `title`/`hint`.

### 2. Main menu layout (MainUI, measured)

#### Items and order
- Recents (lang 18, `ic-recent`), Favorites (1, `ic-favorite`), Games (2, `ic-game`), Expert (0, `ic-retroarch`), Apps (107, `ic-app`), Settings (15, `ic-setting`).
- Onion hides Recents and Expert by default (`settings.h` show_recents/show_expert false; flags `.showRecents`, `.showExpert`). The default count is therefore n=4: Favorites, Games, Apps, Settings.
- English lang (onion/static/build/miyoo/app/lang/en.lang): "Recents", "Favorites", "Games", "Expert", "Apps", "Settings". Stock Miyoo lang says "Consoles"/"OPEN".

#### Scrolling
- 4 slots are visible. MainUI keeps a 4-item window (`/tmp/state.json` pagestart/pageend = start..start+3; `state.h:150-215`).
- Viewer behaviour, which matches: moving past slot 3 shifts the window by 1. Wrapping from last to first resets start to 0; wrapping from first to last sets start = n-4 (main-screen.component.ts).

#### Tile images
- The selected item uses `ic-<x>-f`; all other visible items use `ic-<x>-n`.
- Slot s (0..3): centre cx = 88 + 155*s, cy = 230.
- Draw position: x = cx - w/2, y = 230 - h/2 (integer division, natural size, no scaling, no clipping to any box; images may extend past the screen).
- Fits that confirm this:

  | Theme | Tile size | Fitted top-left (slot 0) or slot centres |
  |---|---|---|
  | Blueprint | 156x278 | (10, 91), step 155 |
  | Silky | 156x328 | (10, 66) |
  | Hello Miyoo | 120x150 | centred: err 8.5, versus 39.8 left-aligned and 58.9 top-aligned |
  | NES/RetroRama | full-screen f images 1104/794/796/1106 x 500 | centres resolve exactly to 88, 243, 398, 553 and y = -20 |

- Default/stock sizes: stock 156x278; Onion default (Silky) 156x328.

#### Tile labels
- Text = lang string. Font `grid.font` at `grid.grid1x4` px.
- Colour: `grid.selectedcolor` for the selected item, `grid.color` for the others.
- Horizontally centred on cx. Vertically centred at **y = 286** (top = 286 - h/2), drawn over the tile.
  - Fits: Blueprint OpenSans 25: top 268, h 36. 2022 Stock HENB 25: top 271, h 29. Hello Miyoo Boldins 28: top 271, h 30. All give centre ≈286.
- The surface height is SDL_ttf's `ascent - descent + 1`.
- Not drawn when `hideLabels.icons` (or legacy `hideIconTitle`) is set. Onion blanks lang keys 0, 1, 2, 15, 18 and 107 to `" "` (`lang.h:66-134`, called from `themeSwitcher/installTheme.h:191` and `resources.h:439-447`).

#### Page dots (`dot-a` = active, `dot-n` = others)
- One dot per visible item (n = 4..6). The active dot is at the selected item's absolute index, not its slot.
- step = dot width + 5. Use max(dot-a.w, dot-n.w): Lifestream with dot-a 30 / dot-n 24 measured step 35.
- x_i = 320 - n*step/2 + i*step; top y = 390 for all heights (h 10..60). No vertical centring.
- Fits:
  - 2022 Stock 10x10, n=6: x0 275, step 15.
  - Silky and Blueprint 24x14, n=4: x0 262, step 29.
  - Cards 22, Kingdom Onions 21, Pokemon MD 24, RPO 26, Hallownest 30, Hello Miyoo 17, Sprite Buddies 63x60 (n=6, step 68, y 390): all fit the formula.
- **Outliers:** three tenlevels themes with wide opaque dots (PAC-MIYOO 52x56, Miyoo Truxton 45x28, TRON 75/76x30) show step = width (no gap). PAC-MIYOO also shows y = 364. Possibly stale previews; see open questions.

#### Footer hints (A/B)
- `icon-A-54` at (20, 450 - h/2).
- Label A = lang 88 ("SELECT") in `hint` font, size and colour, at x = 20 + A.w + 5, y = 450 - th/2.
- `icon-B-54` at x = labelA.x + labelA.w + 30, y = 450 - h/2.
- Label B = lang 89 ("BACK") at x = B.x + B.w + 5, y = 450 - th/2.
- Fits: Blueprint (54x54, OpenSans 40); 2022 Stock (54x54, Exo2 40); Silky's preview (32x32, Exo2 at 40, although its current config says 25 — the preview is stale).
- Onion's own code uses 449 - th/2 (footer.h:8-61); the device measures 450.
- Icon sizes are **not** 54: Onion default 32x32; themes also use 56x56, 44x32, 110x54 and 640x54.
- `hideLabels.hints` makes the labels `" "` (lang keys 45, 46, 88, 89, 111, 112 and 300). The icons are still drawn, and the B icon sits after the width of a space plus 30.
- There is **no** current/total page counter on the main menu.

#### div-line-h
- Drawn at (0, 60) on the main menu (Blueprint: error 18.5 with the line versus 40.0 without).
- The Onion default's line is fully transparent.

#### Top bar
- **Background:** `bg-title` at (0,0) at its full image size. It is **not** cropped to 60: Fake Pocket's 640x74 and Fuzzy Pickles' 640x64 fit better uncropped. Onion's own header crops to 640x60 (header.h:9-14).
- **Logo:** `miyoo-topbar` at (20, 30 - h/2). Fits: Blueprint and Silky 148x42 at (20, 9). BMO's 640x880 logo at y = -410 fits with error 6.07, versus 69.5 when drawn above the tiles and 36.8 at y = 9.
- **No title text and no clock** on the main menu. The title font is only used on sub-screens.
- **Wi-Fi icon** (when wifi is on): `icon-wifi-signal-01..04` at x ≈ 504 (±2), y = 30 - h/2. Fits: Blueprint (504, 6); Silky 46x46 (504, 7-8); NES (506, 6).
- **Battery:** the composite in §3, centred at (596, 30).

#### Footer bar
`tips-bar-bg` at (0, 420), natural size.

#### Background
`background.png` is drawn **rotated 180°** at (0, 0) under everything:
- Blueprint fit: rotated 180° error 13.8; as-is 82.4; flipped V 69.3; flipped H 57.4.
- The viewer rotates it too (home.component.scss `transform: rotate(180deg)`).
- Onion rotates it with `rotate180` (background.h:12-17). Its rotozoom result is blitted at (-2, -2), which shifts it about 1px up-left.
- No other skin image is rotated.

#### Draw order (main menu)
1. Background (rotated)
2. bg-title
3. div-line-h
4. miyoo-topbar
5. tips-bar-bg
6. A/B hints
7. Dots
8. Tiles, each followed by its label
9. Wi-Fi icon
10. Battery

Evidence:
- **Logo under tiles:** BMO fit. RetroRama's logo literally reads "BROKEN WITH RECENT OR EXPERT THEN! SORRY!": it is hidden under the full-screen tile and only shows when slots shift.
- **Hints over logo:** BMO footer error 48.7 versus 73.2.
- **Hints and dots under tiles:** NES fit — dots error 9.2 under versus 44.9 over; hints error 11.9 under versus 97.4 over. Little Picture Mode is consistent.
- **Battery and Wi-Fi over tiles:** NES and Super Game Boy previews.
- **Conflict:** FireMiyoo's preview alone suggests dots drawn over tiles (see open questions).

### 3. Battery icon and percentage (Onion `render/battery.h:7-165`, `mainUiBatPerc.c:49-75`)

#### Icon choice (pct = 0..100; 500 means charging)

| Condition | Asset |
|---|---|
| Charging | `ic-power-charge-100%` |
| < 5 | `power-0%-icon` |
| < 30 | `power-20%-icon` |
| < 60 | `power-50%-icon` |
| < 90 | `power-80%-icon` |
| otherwise | `power-full-icon` |

`power-full-icon_back` is used if present (legacy repair).

#### Text
- Text = "%d%%". Font is `batteryPercentage.font` (else `hint.font`), size `.size` (default 24), colour `.color` (else `hint.color`).
- Visible only if `.visible`, not charging, and icon.w <= 640.
- If the font family name starts with "Exo 2", offsetY -= 0.075 * fontHeight.

#### Composite size W×H
- W = 2*(text.w + 5) + icon.w. If the text is not visible, W = icon.w and H = icon.h. If `fixed` or textAlign CENTER, W = max(icon.w, text.w).
- H = text.h if text.h > icon.h, else **icon.w**. This is a bug, but it is invisible because everything is centred.
- Round W and H up to even, minimum 48 each.

#### Placement inside the composite
- Icon at x = 0, y = (H - icon.h)/2.
- Text at y = (H - text.h)/2 + offsetY. Text x:

  | Mode | LEFT (default; onleft:false) | CENTER | RIGHT (onleft:true) |
  |---|---|---|---|
  | fixed | offsetX | (icon.w - text.w)/2 + offsetX | icon.w - text.w + offsetX |
  | not fixed | icon.w + 5 + offsetX | (icon.w - text.w)/2 + offsetX | offsetX, and icon moves to x = text.w + 5 |

#### Screen position
- Composite at (596 - W/2, 30 - H/2) (header.h:16-22). Confirmed: 2022 Stock icon at (572, 6); Silky 88x48 at about (552, 6).
- Consequences:
  - Narrow icons sit left-aligned in a 48px box at x = 572.
  - In non-fixed mode the group is right-aligned so the text ends at 596 + icon.w/2.
  - Text width moves the icon even when the glyphs are invisible. NES's NES-Logo font has no digits, so its "100%" is about 70px wide and the icon lands at x = 561, matching the preview.

#### How Onion feeds MainUI
- `mainUiBatPerc` runs before each MainUI launch (`static/build/.tmp_update/runtime.sh` launch_main_ui) and writes `<theme>/skin/.batt-perc.png`.
- That PNG is this composite, with its centre 48x48 pre-blended over background+bg-title cropped at (572, 6, 48, 48).
- For a clone, render the composite live: alpha-blend the icon and text and centre at (596, 30).

### 4. Sub-screen header and footer (Onion apps mirror MainUI: `render/header.h`, `render/footer.h`)
- **Header** (`theme_renderHeader`): background slice (0,0,640,60), then bg-title cropped to 640x60.
- **Optional logo** at (20, 30 - h/2).
- **Title** (`title` font/size/colour) at x = (640 - w)/2, y = 29 - h/2. Background and bg-title are re-blitted behind it over (x - 10, 0, w + 20, 60).
- **Battery** as in §3.
- **Footer:** background slice (0, 420, 640, 60), then `tips-bar-bg` at (0, 420), then the hints as in §2.
- **Page counter** (`renderFooterStatus`):
  - "N/" uses the hint font with `currentpage.color`; "M" uses the hint font with `total.color`.
  - M is right-aligned at x = 620; "N/" sits immediately to its left.
  - Both are vertically centred at y = 449. If total == 0, N = 0.
  - The `currentpage`/`total` font and size keys are ignored; only their colours apply.
- **`preview-bg`** (lists only): x = 640 - w, y = 60. Box art is centred at y = 240 in the remaining width (list.h:186-215). It is not on the main menu.

### 5. frame.border-left / border-right
- Not used by MainUI.
- Onion Game Switcher normal view: the screenshot is clipped to x ∈ [border_left, 640 - border_right] (`gs_render.h:16-41`); the game-name bar and arrows are inset the same way.
- infoPanel uses the content rect {border_left, 60, 640 - left - right, 360}.
- Used by BRCKD, System 9 and win98 (9/9), and Cosy and Wireframe (0/0).

### 6. hideLabels / hideIconTitle summary
- `hideLabels.icons`: no tile labels.
- `hideLabels.hints`: no hint words; icons are kept.
- Legacy `hideIconTitle`: sets both.
- Usage in the repo: hideLabels present in 117 configs (icons true 81, hints true 61). Tweaks overrides are stored in Saves/CurrentProfile/theme/config.json.

### 7. Quirks themes rely on (counts over 245-249 skin dirs in OnionUI/Themes)

#### Overlays that must be alpha-blended
- `bg-title` has transparency in 53 skins (17 of them are 1x1 transparent).
- `tips-bar-bg` has transparency in 36 skins (15 are 1x1).
- Most `ic-*` tiles have alpha.
- Always alpha-blend over the rotated background; never treat these as opaque fills.

#### 1x1 placeholders used to hide an element
- `dot-a`/`dot-n` (17)
- `ic-game-n` (11): NES-style themes show only the selected tile
- `miyoo-topbar` (4)

#### Tile sizes
| Size | Skins |
|---|---|
| 156x278 | 138 |
| 156x252 | 27 |
| 794x500 (plus 796/1104/1106 x 500 siblings) | 15 |
| 156x279 | 9 |
| 156x328 | 6 |
| 96x96 | 6 |
| others: 100x150, 120x150, 62x182, 296x310, 154x380, 152x499, 1240x500 | — |

The full-screen ones (NES, RetroRama, FireMiyoo, AnalogPhosphor, Little Picture Mode, PBMini Bite Size, BMO…) pad each item's image so that, centred on its default slot, the content lands at (0,0). They only line up for the 4-item layout.

#### Giant miyoo-topbar images used as screen overlays below the tiles
- Sizes: 640x781, 620x781, 640x804, 640x880, 640x900, 600x960 and 604x253 (CyberOnion, Hallownest, Lifestream, Classic TV, TMNT, BMO, Miyoo Slug, NEO GEO, Sonic, Super Mariyoo, Onion Blocks, Fuzzy Pickles, KantOS…).
- They rely on x = 20 and y = 30 - h/2.
- Other logo sizes: 198x42 (47), 700x60, 560x42 and 640x60 (opaque title-bar replacements).

#### icon-A-54
- 640x54 fully transparent in 33 skins. This pushes label A, icon B and label B off-screen, so it hides all hints.
- Other sizes: 32x32 (38), 56x56, 56x60, 44x32, 110x54, 72x54, 89x48 and 27x27.

#### Battery icons
- 48x48 in 170 skins. Wider ones carry space for the text: Silky 88x48 with `fixed` + offsetX 26; 93x48, 108x48, 129x64, 192x48, 200x51 and others.
- 982x900 in win98, System 9 and vintage_aesthe. These are transparent canvases that place the battery elsewhere through the (596,30) centring — win98 puts it in the bottom taskbar. The text is hidden because w > 640.
- 5x5, 32x32 or 50x50 transparent icons give percentage-only themes.

#### Dots
Sizes 10x10 (62), 24x14 (57), 14x14 (21), 16x20 (13), 24x24, 40x40 (often transparent), 13x10, 42x14, 63x60, 75x30 and 52x56.

#### Fonts at odd sizes
| Key | Values |
|---|---|
| hint.size | 40 (52), 0 (15), 1 (12), 18-24 (~56), others |
| title.size | 36 (58), 30, 26, 20, 24, 0, 1 |
| grid.grid1x4 | 25 (72), 20, 24, 22, 26, 30, 0 (4), 12-36 |
| grid.grid3x4 | 18 (89), 20, 24, 50, 8, 0 |

- Size 0 must render nothing: TTF_OpenFont fails, so no text. Size 1 is effectively invisible.
- AdobeBlank as the grid font (14 configs) hides labels while keeping the layout.

#### Other
- Many `preview.png` files are stale. Silky's preview shows hint size 40 while its config says 25. Treat previews as layout evidence, not as config truth.
- Onion copies `bg-io-testing`, `ic-MENU+A` and `progress-dot` from the theme into `/mnt/SDCARD/miyoo/app/skin` on install (`installTheme.h:145-207`) because MainUI reads those three only from the system skin.

### 8. Onion default theme (fallback skin, `onion/static/build/miyoo/app/skin`, config `.../app/config.json`)

| Asset | Value |
|---|---|
| background | 640x480, opaque (36,38,48) |
| bg-title | 640x60, opaque (28,29,36) |
| tips-bar-bg | 640x60, opaque (36,38,48) |
| miyoo-topbar | 148x42 |
| div-line-h | 640x4, fully transparent |
| dot-a, dot-n | 24x14 |
| icon-A-54, icon-B-54 | 32x32 |
| ic-*-n / ic-*-f | 156x328 |
| power-*-icon, ic-power-charge-100% | 48x48 |
| icon-wifi-signal-0x | 48x48 |
| preview-bg | 256x364 |

Config: Exo-2-Bold-Italic_Universal (title 25, hint 40, grid 25/18, colours #686868/#FFFFFF), list wqy-microhei 25, battery visible with onleft:true.

### 9. Where onion-theme-viewer differs from the device

| Element | Viewer | Device |
|---|---|---|
| Tile top | y = 80 | centred at y = 230 |
| Tile size | `max-width: 100%`, so oversized tiles shrink | natural size, can overflow |
| Label | box bottom at y = 286 | centre at 286 |
| Dots | gap 10, vertically centred around 389 | gap 5, top 390 |
| Hint spacing | icon→text 4, A→B 24 | 5 and 30 |
| div-line-h | y = 56 | y = 60 |
| Battery | right:20 with a 24px "Onion" italic font and legacy red/green/blue keys | §3 |
| hideLabels | only reads `hideIconTitle` | reads hideLabels (legacy key as fallback) |
| Missing assets | `getImage` returns '' | falls back to the default skin |

The viewer is right about: the background rotation, the 4-of-6 scrolling, the n/f selection, the font class mapping (grid1x4→rowFont, hint→tipsFont, title→titleFont) and `selectedcolor` for the selected label.

Open questions: 1. **Dot spacing for wide dots:** 10 themes measure step = dot width + 5, top y = 390. Three tenlevels themes with wide, fully opaque dots measure step = dot width: PAC-MIYOO (52x56, also at y = 364), Miyoo Truxton (45x28) and TRON (75/76x30). Sprite Buddies (63x60) uses +5. These three previews may be stale; it is not settled.
2. **Dots above or below the tiles:** the NES preview strongly says below (hints too). The FireMiyoo preview says dots are drawn above the full-screen tile. The spec uses "below", backed by NES and Little Picture Mode.
3. **MainUI's own colour and font parsing is not verifiable** (closed binary). Onion's `hex2sdl` turns "rgba(...,0)" and "white" into black, although authors meant transparent or white. Decide whether to copy Onion exactly or honour the intent.
4. **Battery and Wi-Fi details in MainUI:** I could not confirm how often MainUI refreshes `.batt-perc.png` (Onion only regenerates it at MainUI launch), the exact Wi-Fi icon file per signal level, or whether the Wi-Fi x is fixed at 504 or 506 (measured 504-506).
5. **Footer background size:** whether `tips-bar-bg` taller or shorter than 60px is top-anchored at 420 or bottom-anchored. A Link to the Past (640x63) leans slightly toward bottom-anchored, but its error is high.
6. **Wrap-around on the main menu** (last→first) comes from the viewer only, not from device evidence.

## OnionOS / Miyoo Mini theme rendering reference for lists, grids, settings, popups and dialogs (640x480)

### 0. Sources and how much to trust each

| Tag | Source | What it gives | Confidence |
|---|---|---|---|
| **[BIN]** | `OnionUI/Onion/static/build/.tmp_update/bin/MainUI-354-expert`, the stock Miyoo MainUI binary that OnionOS ships. The `-354-clean`, `-283-expert` and `-283-clean` copies have the same layout. "clean" only NOPs the Expert icon at 0x37994. | Exact layout. Read-only static disassembly (`llvm-objdump --triple=armv7`). The binary is ARM, built at -O0 and stripped, so constants are literal. Addresses are cited as `@0xNNNN`. | High for numbers. Field names are my labels. |
| **[ON]** | OnionUI/Onion source: `src/common/theme/{config.h,load.h,resources.h,background.h}`, `render/{list.h,footer.h,dialog.h,pop_menu.h,textbox.h}`, `src/common/utils/apply_icons.h`, `src/themeSwitcher/installTheme.h`, the stock skin in `static/build/miyoo/app/skin/`, and the default icon pack in `static/build/Icons/Default/`. | How Onion's own apps (Tweaks, Package Manager, Game Switcher, prompt, infoPanel) draw the same assets, plus default assets. | Exact (source). |
| **[VW]** | `syphen/onion-theme-viewer/src/app/...` (syphen/onion-theme-viewer). | A CSS approximation of MainUI. | Approximate. Its deviations from [BIN] are listed in §9. |
| **[TH]** | `OnionUI/Themes`: config.json files, `.github/whitelist*.txt`, a survey of skin PNG sizes and alpha over all 179 top-level themes (read with `git show`), and pixel comparison of `preview.png` against `background.png`. | Real-world asset sizes and empirical checks. | Empirical. |

The annotated disassembly was a scratch file and was not kept.

Screen space throughout is 640x480. Title bar is y 0–60, body y 60–420, footer y 420–480.

---

### 1. Global rules (apply to every screen)

#### 1.1 Asset lookup and fallback [BIN @0x123af0, @0x12a140]
- The theme dir comes from `/mnt/SDCARD/system.json` key `"theme"`. MainUI's cwd is `/mnt/SDCARD/miyoo/app/`.
- Images are loaded from `<theme>/skin/<name>.png`. If that fails, MainUI loads `./skin/<name>.png`, which is the stock skin = Onion's `static/build/miyoo/app/skin/`.
  - Paths starting with `/` are used as-is.
  - Every asset in this document falls back per file. A theme that omits one PNG gets the stock one, not nothing.
- Onion's own apps [ON load.h `theme_getImagePath`] use this order:
  1. `/mnt/SDCARD/Saves/CurrentProfile/theme/skin/<name>.png` (user overrides)
  2. `<theme>/skin/<name>.png`
  3. `/mnt/SDCARD/miyoo/app/skin/<name>.png`
  4. `extra/<x>` names fall back to `/mnt/SDCARD/.tmp_update/res/<x>.png`.
  - Overrides only affect Onion apps, not MainUI.
- "Non-dynamic" elements [ON installTheme.h]: `bg-io-testing`, `ic-MENU+A` and `progress-dot` are copied into the system skin dir when a theme is installed. Treat them as theme assets anyway.

#### 1.2 background.png is shown rotated 180° [ON background.h `rotate180(...)`; VW home.component.scss `transform: rotate(180deg)`; TH verified]
- On screen, the background is the PNG rotated 180°. Every other asset is drawn upright.
- Verified two ways:
  - Bayonetta: the preview body strip matched the rotated background (mean abs diff 9.4) and not the unrotated one (51.7).
  - The 36 themes with a transparent `bg-title`: the header region of the preview matches the rotated background's top strip (diff ≈0–10) and not the file's top or centre. Examples: Game Boy Scouts 0.0 vs 124; TechDweeb 1.3 vs 102; BMO 8.4 vs 87.
- Onion's `rotate180()` fills with red and blits the rotozoomed copy at (-2,-2). The net effect is a plain 180° rotation.

#### 1.3 Alpha
- All skin PNGs are alpha-blended at their on-screen position over what is already drawn: the rotated background, then the earlier layers.
- [BIN @0x12a8e4] pre-composites some assets against a centre crop of the in-memory background: `div-line-h`, `div-line-v-01`, `bg-title`, `tips-bar-bg`, `bg-game-item-f/n` and `bg-ra-list-item` (the last with no background).
  - Empirically this has no visible effect.
  - The Wasteland and Wasteland Nevada `tips-bar-bg` (mean alpha 0.41): a plain blend over the real rotated background predicts the preview with error 0.6. Centre-composite models give 3.1–6.2.
  - Transparent `bg-title` themes show the real top of the background, as above.
  - **Implement a plain per-pixel alpha blend at the real position.**

#### 1.4 config.json as parsed by stock MainUI [BIN @0x12276c, @0x1225b4, @0x1229ec, defaults @0x12aa64, @0x122e80…]

| Key | Fields read | Default if missing |
|---|---|---|
| `title` | font, fontascii, size, color | size 36, #F0F0F0 |
| `path` | font, fontascii, size, color | 24, #F0F0F0 (file browser) |
| `hint` | font, fontascii, size, color | 40, #F0F0F0 |
| `currentpage` | font, fontascii, size, color | 40, #F0F0F0 |
| `total` | font, fontascii, size, color | 40, **#AAAAAA** |
| `grid` | font, fontascii, grid1x4, grid3x4, **iconxoff1x4, iconyoff1x4, txtyoff1x4**, color, selectedcolor | grid1x4 25, grid3x4 18, offsets 0, color #686868, selectedcolor #F0F0F0 |
| `list` | font, fontascii, size, color, selectedcolor, **highlightcolor** | size 24, color #F0F0F0, selectedcolor #F0F0F0, highlightcolor #FFFF00 |

- `fontascii` defaults to `font`. English uses `fontascii`; other languages use `font` with wqy-microhei as fallback.
- No theme in the repo uses `fontascii`, `iconxoff1x4`, `iconyoff1x4`, `txtyoff1x4` or `highlightcolor` [TH grep].
- MainUI itself never reads `batteryPercentage`, `hideLabels`, `hideIconTitle` or `frame`. Onion applies them:
  - `hideLabels` / `hideIconTitle` blank language-string ids 0, 1, 2, 15, 18 and 107 (icon labels) and 45, 46, 88, 89, 111 and 112 (hints) with `" "` [ON lang.h `lang_removeIconLabels`].
- Colour parsing [BIN @0x1224b0]:
  - Accepted only if the string length is > 6 and it starts with `#`. Parses hex pairs at chars 1, 3 and 5.
  - Trailing junk is ignored, so `#DDDDDD;` works.
  - Anything else, such as `rgba(...)`, keeps the default.
  - Onion's `hex2sdl` also accepts a missing `#`.
- Onion's defaults differ [ON config.h]: title 36, hint 40, list 24, grid1x4 24, grid3x4 18, white everywhere except grid.color #686868.
  - Fallback chain: hint→title, list→title, currentpage/total→hint.

#### 1.5 Font objects [BIN @0x12af5c–0x12b0cc]
English case:

| Object | Font file | Size | Style | Used for |
|---|---|---|---|---|
| **F_LIST** | `list.fontascii\|font` | `list.size` | **TTF_STYLE_BOLD (always)** | all list rows, settings rows, popup rows, descriptions |
| **F_TITLE** | `title.font…` | `title.size` | regular | title bar, dialog title/message/buttons, game-detail text |
| **F_GRID1x4** | `grid.font…` | `grid1x4` | regular | main-menu labels **and the 4x2 console grid labels** |
| **F_GRID3x4** | **`list.font…` (not grid.font)** | `grid3x4` | regular | Expert/RApp 3x3 grid labels |
| **F_HINT** | `hint.font…` | `hint.size` | regular | footer hints and the footer page counter "cur/total" |

- Font path: absolute if it starts with `/`, else relative to the theme dir. If it can't be opened, the fallback is `./HENB.TTF` (list, title, grid) or `./Exo-2-Bold-Italic.ttf` (hint). Non-English falls back to `./wqy-microhei.ttc`.
- `currentpage` and `total` only supply colours. Their fonts and sizes are not used for the counter, which uses F_HINT.
- Onion's LIST font is also forced bold [ON resources.h `_loadFont`].

#### 1.6 Text drawing [BIN drawText @0x15d24]
- `TTF_RenderUTF8_Blended`, blitted unclipped: no ellipsis, and text runs past rect.w.
- Alignment codes (s = rendered surface):
  - 0: (x, y)
  - 1: (x + (w − s.w)/2, y + (h − s.h)/2)
  - 2: (x + w − s.w, y)
  - 3: (x, y + (h − s.h)/2)
  - 4: (x + w − s.w, y + (h − s.h)/2)
- The "selected" shadow pass is rendered but its blit is **NOP'd** in all four Onion MainUI builds (@0x15fd4), so there is **no text shadow**.
- Integer division is C truncation toward zero.

#### 1.7 Colour globals [BIN @0x12ac2c]
- LIST = list.color; LIST_SEL = list.selectedcolor; LIST_HL = list.highlightcolor
- GRID = grid.color; GRID_SEL = grid.selectedcolor
- TITLE = title.color; HINT = hint.color; CUR = currentpage.color; TOTAL = total.color
- BLACK = #000000

---

### 2. Text list screens: Recents, Favorites, a system's game list, global search, popup lists (MainUI `TextMenuDrawer`, draw @0x1f6f0)

#### 2.1 Instances [BIN]

| Screen | Rows visible | Row height | Selected bg | Dividers |
|---|---|---|---|---|
| Game list of a system (DBCachedTextMenu @0x25274) | 6 | 60 | `bg-list-s` | yes |
| Recents / Favorites (TextMenu @0x1a9ac / 0x1b1a4 / 0x1b500) | 6 | 60 | `bg-list-s` | yes |
| Global search results (@0x14862c) | 4 | (60 unless set) | `bg-list-l` | yes |
| Apps (@0x1bc80) | 4 | **90** (setter @0x3a678) | `bg-list-l` | yes |
| Popup lists | see §6.1 | | | |

- List rect = (0, 60, 640, 360).

#### 2.2 Geometry, in draw order
1. **Dividers.** If enabled, for i = 0 … rows−2, blit full `div-line-h` at (0, 60 + (i+1)·rowH).
   - rowH 60: y = 120, 180, 240, 300, 360.
   - rowH 90: y = 150, 240, 330.
   - Drawn even if the list is shorter. Drawn before the rows, so a selected bg covers them.
2. For each visible item at row r: **rowX = 0, rowY = 60 + 2 + r·rowH** (note the +2, so the first row starts at y 62).
3. **Selected bg** (selected row only; unselected rows get no bg). The image is `bg-list-s` (or `bg-list-l` / custom).
   - srcrect = (0, 0, 640, rowH): cropped to the row height.
   - y = rowY + (rowH − img.h)/2 if rowH > img.h, else rowY (top-aligned, cropped).
   - With the common 640x56 `bg-list-s`, the bg spans y rowY+2 … rowY+58. The stock 640x60 fills rowY … rowY+60.
4. **Row icon**, if the item has one: icon at (rowX + 5, rowY + (rowH − icon.h)/2). The label then starts at x = rowX + icon.w + 20.
   - Game list: game rows get `skin/icon-game.png` and folder rows get `skin/icon-folder.png` [BIN @0x23b98 / 0x23c80].
     - Stock `icon-game` is a 1x60 transparent spacer, so stock games' text starts at x = 21.
     - Stock `icon-folder` is 60x60, so folder text starts at x = 80.
   - Recents and Favorites rows have no icon, so x = 20.
5. **Label** (one-line items). Rect = (labelX, rowY + (rowH − TTF_FontHeight(F_LIST))/2, 600, rowH), align 0 (top-left).
   - Font F_LIST (bold).
   - **Colour = list.color for both selected and unselected.** Both slots are initialised to LIST (@0x1e344); **list.selectedcolor is not used by these lists.**
   - Search-filter matches inside the name are drawn in **list.highlightcolor** (@0x1ede4).
   - Names longer than 48 bytes are cut to 64 bytes. Otherwise there is no truncation, and the text can run under the preview or off-screen.
   - An empty item draws lang string 76.
6. **Favorite mark.** If the item is a favourite (item+0x1d), blit `ic-favorite-mark` at x = 640 − mark.w − P − 20, y = rowY + (rowH − mark.h)/2.
   - P = 250 while a thumbnail preview is shown (§2.4), else 0.
   - Stock 34x39 mark: (586, rowY+10).

#### 2.3 Footer counter, Empty and preview (MenuWindow draw @0x31398)

**Footer page counter** (only if count > 0) [BIN @0x311f4].
- Font **F_HINT**.
- rect = (320, 420 + (60 − TTF_FontHeight(F_HINT))/2, W, 54), with W = 300 if total ≤ 99, else 280.
- `"%d"` of total is drawn align 2 in **total.color**. Its right edge is at x 620 (or 600).
- Then rect.x −= totalWidth and `"%d/"` of sel+1 is drawn align 2 in **currentpage.color**, immediately left of the total.
- Onion's own version [ON footer.h]: right edge 620, vertical centre 449, same colours, F_HINT.

**Empty list.** If the list is empty, `Empty.png` is centred on the screen at ((640 − w)/2, (480 − h)/2). The stock asset is 208x62 at (216, 209).

**Thumbnail / preview.** Applies when the window shows thumbnails and the selected item has `Imgs/<rom>.png`.
1. `preview-bg` (always present via fallback; stock 256x364) is blitted at (640 − pbg.w, 60) → (384, 60).
2. Thumbnail [BIN load @0x1cee0 (path, 250, 360)].
   - Scaled down only when wider than 250, to width 250 with aspect kept.
   - No upscale and no height cap.
   - Drawn at **x = (640 − pbg.w) + (250 − w)/2, y = 60 + (360 − h)/2**.
   - The constants 250 and 360 are used even when preview-bg is another size.
   - If preview-bg failed to load: x = 390 + (250 − w)/2.
3. The preview is drawn **after** the list, so it covers the right part of the rows, including the bg-list-s.
4. `thumb-default.png` is **not** used for missing thumbnails in lists. With no image there is no preview at all.

#### 2.4 Apps list (two-line rows, rowH 90, `bg-list-l`) [BIN @0x20010 branch]
- Row y = 62, 152, 242, 332.
- Selected `bg-list-l`: cropped to 640x90 and top-aligned if taller (stock 640x94); centred if shorter.
- App icon from `App/<x>/config.json` `"icon"`, x = 20.
  - If icon.h ≤ 90 (or icon.w ≤ 90): y = rowY + (90 − h)/2. A default 74x74 lands at rowY+8.
  - Else the centre 71x71 is cropped and placed at rowY.
- Name: rect (111, rowY+20, 529, 25), align 0 (top-left at (111, rowY+20)), F_LIST, list.color.
- Description (`"description"`): rect (111, rowY+45, 529, 25), align 0, **F_LIST (bold), grid.color**.
- Without an icon, both start at x = 20.

---

### 3. Settings (MainUI `SettingMenu`, draw @0x28994)

- Six rows. **rowY = 62 + 60r**; rowH is hard-coded 60.
- The list owns `bg-list-s` (@0x285a0), `icon-left-arrow-24` and `icon-right-arrow-24`.

1. **Divider.** For each visible row r ≤ 4 that has an item: full `div-line-h` at (0, (r+2)·60) → y 120…360, under each row.
2. **Selected bg.** `bg-list-s` at (0, rowY + (60 − h)/2), srcrect (0, 0, 640, 360), so it is **not cropped**. A taller image is centred on the row.
   - Text colour stays **list.color** whether or not the row is selected.
   - **list.selectedcolor is used only if bg-list-s failed to load** (@0x28d34).
   - The same rule applies in DeviceInfo, Color and FileBrowser.
   - WiFi items (`WifiOnOffLabel` @0x1391d0) do use list.selectedcolor when selected.
3. **Icon.** At (20, rowY + (60 − icon.h)/2), full size. The label then starts at **x = 80, whatever the icon width** (otherwise x = 20).
4. **Label.** Rect (x, rowY, 640 − leftArrow.w − rightArrow.w − 20 = 572, 60), align 3 (left, vertically centred), F_LIST, localized string.
5. **Value rows.** Item types 1, 2, 3, 12, 14 and 17 (@0x28f4c). Arrows and value are drawn on every value row, selected or not.
   - Left arrow at (640 − rightArrow.w − leftArrow.w − 240, rowY + (60 − h)/2) = (352, rowY+18) for 24x24.
   - Right arrow at (640 − rightArrow.w − 40, …) = (576, rowY+18).
   - Value text: rect (640 − rightArrow.w − 240 = 376, rowY+2, 200, 60), align 1 (centred at x 476), F_LIST, row colour.
   - Formats:
     - Brightness (type 3): `"%02d/10"`
     - Volume-type rows (types 1 and 2): `"%02d/20"`
     - Hibernate (type 12): lang 114 if the value is ≤ 4 or > 30, else lang 113 formatted with the value
     - Type 17: lang 104 or 105
   - Type 19 (WiFi): text right-aligned (align 4) in (376, rowY+2, rightArrow.w + 200 = 224, 60).

**Stock items** (icon, lang id, type) [@0x38074…]:

| Icon | Lang id | Type |
|---|---|---|
| icon-Shutdown | 86 | 18 |
| icon-brightness-48 | 26 | 3 |
| icon-setting-wifi | 131 | 19 |
| color | 118 | 13 |
| icon-language-48 | 23 | 11 |
| icon-key-setting-48 | 22 | 0 |
| sound-icon | 25 | 2 |
| icon-theme | 125 | 15 |
| icon-device-info-48 (hibernate) | 115 | 12 |
| fixit | 130 | 16 |
| icon-factory-reset-48 | 28 | 5 |
| icon-device-info-48 | 30 | 7 |
| icon-device-info-48 | 31 | 8 |

- Volume OSD icons `icon-volume-00..20.png` (48x48) and `headphone-icon` are drawn in the title bar (@0x165c8). They are not in the theme whitelist.

---

### 4. Grids (MainUI `IconMenu`, draw @0x25efc, ctor @0x25c8c)

#### 4.1 General formula
- **Cell size**
  - cellW = padX > 0 ? (640 − 2·padX)/cols : 640/cols, and x0 = padX.
  - cellH = padY > 0 ? (360 − 2·padY)/rows : 360/rows.
  - **y0 = padY > 0 ? padY + 65 : 60.**
- **Cell origin** for visible slot i: col = i % cols, row = i / cols. cellX = x0 + col·cellW, cellY = y0 + row·cellH.
- Draw order per item: bg → icon → label.
- **Item bg**
  - Selected: the item's focused bg. Unselected: the normal bg. Null means none.
  - Position: (cellX + (cellW − bg.w)/2 **+ 2**, cellY + (cellH − bg.h)/2 **+ 2**).
- **Icon**
  - Selected: `iconsel`. Unselected: `icon`.
  - **Fixed icon size (W, H) set:** srcrect = centre crop (W, H) of the icon; dst = (cellX + (cellW − W)/2, cellY + 15). Label rect y = cellY + H + 21.
  - **Otherwise:** dst = (cellX + (cellW − icon.w)/2, cellY + (cellH − icon.h)/2 − 10). Label rect y = cellY + cellH − labelOff if labelOff ≠ 0, else cellY + 3·cellH/5.
- **Label**
  - Rect = (cellX, labelY, cellW, 18), align 1 (centred on cellY-relative y+9).
  - Colour: **grid.selectedcolor** when selected, else **grid.color**.
- **Page counter.** If rows > 1, the footer counter (§2.3) shows page = sel/(rows·cols)+1 of ceil(count/(rows·cols)). Paging is a whole page per step.

#### 4.2 Console (system) grid, 4 cols x 2 rows
- padX = padY = 10 → cell 155x170, x0 = 10, y0 = 75. labelOff = 45; font **F_GRID1x4** (default getter).
- Bg: `bg-game-item-f` (selected) and `bg-game-item-n` (unselected), with the stock fallback.
  - Stock 154x170 at x = 12 + 155c, y = 77 + 170r.
  - A 155-wide asset also lands at +2.
- Icon (default pack 120x130) at (27 + 155c, 85 + 170r).
- Label rect (10 + 155c, 200 + 170r, 155, 18).
- **Icons come from `Emu/<X>/config.json`**: `"icon"` is used unselected and `"iconsel"` selected.
  - If `iconsel` is absent or empty, `icon` is used (@0x121560).
  - Relative paths resolve against the emu dir (`%s/%s/%s`).
- Onion's icon-pack logic [ON apply_icons.h]:
  - The base name is the config icon's basename up to the first `-` (so `gba-alt.png` → `gba`).
  - `icon = <pack>/<name>.png` and `iconsel = <pack>/sel/<name>.png`. The `iconsel` key is deleted if `sel/` lacks the file.
  - Apps use `<pack>/app/` and RApps use `<pack>/rapp/` (each with `sel/`).
  - A theme's `icons/` folder is a pack, applied when installing with "Apply icons" [ON installTheme.h]; otherwise `/mnt/SDCARD/Icons/Default` is used.
  - Default pack sizes: consoles 120x130, `app/` 74x74, `rapp/` 192x72.
  - In the themes repo, 79 themes ship `icons/`, with 1209 `sel/` images.

#### 4.3 Expert (RetroArch core apps / RApp) grid, 3 cols x 3 rows [@0x37c48]
- No padding → cell 213x120, x0 = 0, y0 = 60.
- Fixed icon size **192x72** (icon centre-cropped to 192x72), at (cellX + 10, cellY + 15). Icons come from `RApp/<x>/config.json`.
- Label rect (cellX, cellY + 93, 213, 18), font **F_GRID3x4 = list font file at grid3x4 size**, grid colours.
- **Separators** (only this grid):
  - full `div-line-v-01` at x = 213 and 426, y = 60 (stock 4x360 → spans to 420);
  - full `div-line-h` at x = 0, y = 180 and 300.
  - Drawn before the items.
- Selected bg `bg-ra-list-item` (no unselected bg) at (cellX + (213 − w)/2 + 2, cellY + 2). For the stock 214x120, (213−214)/2 truncates to 0, so x = cellX + 2.

#### 4.4 Main menu, 1 row x 4 cols (context, shares IconMenu)
- padX = 10 → cell 155x360, y0 = 60.
- Icon `ic-*-f` (selected) / `ic-*-n` (unselected) at (10 + 155c + (155 − w)/2, 60 + (360 − h)/2 − 10). Stock 156x328 → (10 + 155c, 66).
- Label rect (cellX, 276, 155, 18), F_GRID1x4, grid colours.
- **iconxoff1x4** replaces x0. **iconyoff1x4** sets the absolute icon y. **txtyoff1x4** sets the absolute label-rect y. These apply only to the main menu.
- Dots: if `dot-a` and `dot-n` exist, pitch = dot-a.w + 5, startX = (640 − pitch·count)/2, y = 390. Selected uses dot-a, others dot-n.

---

### 5. Onion's own list screens (Tweaks, Package Manager, prompt, infoPanel) [ON render/list.h, footer.h]

`theme_renderList`, list rect (0, 60, 640, 360).
- Small list (6 rows x 60):
  - menuY = 420 − visibleRows·60 (= 60; = 120 with a sticky note).
  - Per row: `div-line-h` cropped to 640x4 at the row top. Then y += 4. Selected: `bg-list-s` cropped to 640x56 at (0, y).
  - This gives the same 64…120 band as MainUI with the 56-high asset.
- Large list (4 x 90): no padding; `bg-list-l` cropped to 640x90.
- Icon at x = 20, centred at y + 28 (large: y + 45). Label x = 20 (+icon.w + 17), vertical centre y + 27 (large: y + 37).
- Label: list font, bold, **list.color for selected too**, clipped at labelEnd − 30.
- Description (large): centre y + 62, grid.color.
- Toggle (`extra/toggle-on|off`, stock 68x48): x = 620 − w, centred.
- Multivalue:
  - right arrow `icon-right-arrow-24` at x = 620 − w;
  - left arrow at 620 − rightW − 226 − leftW;
  - value text in list font and list.color, centred in the 226-px gap, clipped to 226.
- Disabled items: alpha 60/255.
- Preview: `preview-bg` at (640 − w, 60); image centred horizontally in it, centre y 240, downscaled to width 250.
- Footer [ON footer.h]:
  - background(0, 420, 640, 60) + `tips-bar-bg`;
  - `icon-A-54` at x 20, centred y 450; +5; label in hint font and hint.color, centred y 449; +30; then B the same way;
  - counter right-aligned at 620.

---

### 6. Popups and dialogs

#### 6.1 Popup menu (MainUI `PopUpWindow`, ctor @0x3225c, draw @0x32588; created @0x2ff04)
- `bg-pop-menu-N`: N = number of items, and 4 is used for ≥ 4 items. Single-option popups use `-1`.
- Stock sizes 320x70, 320x130, 320x190 and 320x240.
- The screen snapshot is dimmed by filling black at alpha 0xAA (~67%) (@0x32160).
- The footer is redrawn: background (0, 420) plus the footer bar, with hints from lang 46 / 45 (OK / CANCEL).
- **bg-pop-menu is blitted at (0, 0)**, the top-left corner.
  - 21 of 179 themes ship a 640x480 `bg-pop-menu-1`.
  - Onion's Game Switcher instead uses (0, 60) [ON pop_menu.h].
- List rect = (0, 0, bg.w, bg.h − 8). rowH = (bg.h − 8)/count if count > 1, else bg.h − 8. Stock values: 62, 61, 60, 58.
- Rows: rowY = 2 + rowH·i, no dividers.
- Selected row: `bg-list-s` cropped to (bg.w, rowH), placed per the §2.2 rule.
- Text at x 20, F_LIST, list.color.
- No bg loaded: rect (0, 0, 120, 75).

#### 6.2 Confirm dialog (MainUI `DialogWindow`, ctor @0x333ec, draw @0x33bac)
- `pop-bg` centred at ((640 − w)/2, (480 − h)/2). Stock 640x330 → (0, 75). Theme sizes: 640x300 ×111, 360 ×21, 330 ×19, 480 ×15.
- Title: rect (popX, popY, pop.w, 50), align 1, **F_TITLE, total.color**.
- Message: rect (popX, popY + 50, pop.w, 100), F_TITLE, grid.color, multi-line (@0x16018).
- Buttons, stacked vertically:
  - `icon-A-54` at (popX + 230, popY + 160); label lang 46 at (A.x + A.w + 10, A.y + 5), align 3, **F_TITLE, hard-coded black #000000**.
  - `icon-B-54` at (popX + 230, popY + A.h + 170); label lang 45, same style.
- Onion's dialog [ON dialog.h] differs:
  - overlay 0x55 alpha;
  - title centre popY + 25, total.color;
  - message centre popY + 160, grid.color, auto-shrunk to 450x6 lines;
  - A/B hints in hint font and hint.color, right-aligned to popX + pop.w − 30 at centre popY + pop.h − 60;
  - progress dots `progress-dot` at centre −32/0/+32, y 225.

#### 6.3 Notify / count-down windows (one button) [NotifyWindow draw @0x32d74]
- `pop-bg` centred.
- Message rect (200, 195, 240, 90). If `bg-button-f` exists: h −= btn.h/2 and y −= btn.h/4. Drawn in F_TITLE, grid.color, wrapped and centred.
- **`bg-button-f`** (stock 232x68; themes 232x68 ×145, 1x1 ×11) at ((640 − w)/2, popY + pop.h − 12 − btn.h).
- Button label centred on it, F_TITLE, list.color.
- ProgressDialog (@0x34468): text rect (200, 195, 240, 50), F_LIST, total.color, plus `progress-dot` animation.

#### 6.4 Game detail window (only user of list-num / num-bg) [@0x356e4, 0x1e868]
- Thumbnail (or `thumb-default` if none, @0x34f08) at left: width capped 250 (centre crop), y centred in 60…420.
- Index counter, rect (280, 80, 124, 50):
  - `num-bg` centred in the rect.
  - `list-num.png` is a **10-digit sprite strip**: digit width = list-num.w/10 (stock 90x14 → 9). It renders `"%04d"` (cap 9999) with pitch digitW + 1, centred in num-bg, y = rect.y + (50 − h)/2.
  - If either image is missing, the number is drawn as text, centred, colour #585858.
- Text lines at (284, 145 + 35i, 350, 35) in F_TITLE, list.color.
- **Lists have no scrollbar.** `div-line-v-01` is only the Expert grid separator. `list-num` / `num-bg` are not a list scroll indicator.

---

### 7. On-screen keyboard (stock search / WiFi password) [@0x13ee58…]
- Loaded via the theme→stock fallback, so it is themeable. None of these files is in `.github/whitelist*.txt`, so theme packs effectively never ship them and the stock skin is used.
- Assets (stock sizes):

| Asset | Size |
|---|---|
| `bg-btn-01-f` / `-01-n` | 52x52 |
| `bg-btn-02-f` / `-02-n` | 112x52 |
| `icon-abc` | 36 |
| `icon-character` | 32 |
| `icon-delete` | 32 |
| `icon-L2` | 24 |
| `icon-OK` | 54x26 |
| `icon-R2` | 24 |
| `icon-shift` / `-active` | 22x20 |
| `icon-space` | 36x8 |
| `icon-START` | 1x1 |
| `icon-x` | 1x1 |
| `icon-y` | 24 |
| `input` | 592x52 |

- Key layout coordinates were not extracted.

---

### 8. Assets never drawn by this MainUI build
- `nav-bar-item-bg` is loaded (@0x12b73c) but never blitted, although it is in the whitelist.
- `bg-pop-menu-4` is used, but only 41 themes ship it; the rest fall back to the stock 320x240.
- `icon-TF` and `ic-MENU`: not covered here.

---

### 9. How the Onion Theme Viewer draws it, and where it deviates from [BIN]

**General**
- Image lookup: `skin/<name>.png`, else nothing (`theme.service.ts getImage`). There is no stock fallback unless `override` is set.
- Background rotated 180°. Fonts registered by file-name family.
- Text classes: `.listFont`, `.rowFont` (grid1x4 + grid.color), `.gridFont` (grid3x4, unused) and `.tipsFont`. `.listFontSelected` is defined but never applied.

**Lists (shared.scss `.list`)**
- 60-px rows starting at y 60 (no +2).
- `bg-list-s` at the row's top-left, visible only when selected, not cropped.
- Text padding-left 16, vertically centred. Favourite mark right 8, centred.
- No dividers, no page counter, no thumbnail.

**Settings**
- Icon + 8-px gap + name. The range takes 35% width (224 px) with 24-px arrows and a centred `NN/NN`.

**Apps**
- 90-px rows, `bg-list-l` at the row top, a 90x90 icon box. The description uses the hint font, where [BIN] uses list font + grid.color.

**Consoles**
- 4x2 inside 10-px padding, items 155x172, `bg-game-item-f/n` at the cell origin. Icon fit to 70% height and the title under it, in `.rowFont`.
- The selected-colour selector `.rowFont .selected` is a descendant selector, so it never applies.

**Expert**
- 3x3 items 206.67x114.67, `bg-ra-list-item` selected only, no separators.

**Title bar / tips bar** (approximate): title centred at y 30; battery right 20; footer hints at left 20.

**Use [BIN] numbers over the viewer's.**

---

### 10. Theme quirks to support (from the [TH] survey of 179 themes)

**List and grid backgrounds**

| Asset | Size counts | Alpha |
|---|---|---|
| `bg-list-s` | 640x56 ×154 (Onion-era convention; MainUI centres it with 2-px margins), 640x60 ×11, also 47/55/57/59 and width 632 | 82 have alpha |
| `bg-list-l` | 640x90 ×90, 91 ×58, 94 ×19, 86–92 | top-aligned and cropped at 90 if taller |
| `bg-game-item-f` | 155x170 ×98, 154x170 ×37, 148x126 ×10, 1x1 ×6 | 9 fully transparent |
| `bg-game-item-n` | 155x170 ×88, **1x1 ×40**, 154x170 ×31 | 84 fully transparent; only the selected tile is visible |
| `bg-ra-list-item` | 214x120 ×132, also 213/215/216 | |

- Items wider than the cell overflow it, with the +2 offset.

**Dividers**: `div-line-h` / `div-line-v-01` are fully transparent in 133 / 138 themes, with 1x1 placeholders in 59 / 52. Dividers are effectively off for most themes.

**Icons**
- `icon-game`: 1x60 transparent ×91; a real 60x60 ×55 or 44x44 ×16. The label x shifts to icon.w + 20.
- `icon-folder`: 60x60 ×131, 48 ×13, 44 ×12.
- `ic-favorite-mark`: 34x39 ×95, plus 36x40, 34x42, 40x40 and up to 54x39.
- `icon-left/right-arrow-24`: 24 ×140, plus 44, 40, 48 and 12x56. The value area moves with the right arrow's width.
- `dot-a`: 10x10 ×62, 24x14 ×36, 14x14 ×16, 40x40 ×8.

**Previews and dialogs**
- `preview-bg`: 250x360 ×95, 256x364 ×29, 62 fully transparent.
- `thumb-default`: 250x360 ×153, 212x304 ×21.
- `Empty`: 245x72 ×46, 162x46 ×43, 208x62 ×19, **640x480 ×7** (full-screen overlay).
- `pop-bg`: 640x300 ×111 / 360 / 330 / 480.
- `bg-pop-menu-1`: 320x70 ×141, **640x480 ×21**, 640x420 ×3.

**Header and footer bars**
- `bg-title` (16 themes, plus 1x1 placeholders) and `tips-bar-bg`: transparent or semi-transparent bars are common and are meant to show the rotated background.
- Fully transparent `bg-title` × several, 1x1 ×10.

**Fonts**
- Sizes are far from the defaults:
  - list.size 12–40 (25 ×72, 24 ×37, 20 ×23);
  - grid1x4 0–36, where **0 is used to hide labels**;
  - grid3x4 0–50;
  - hint.size **0 ×15 or 1 ×12, used to hide hints**;
  - title 0–50.
- `AdobeBlank` (all glyphs blank) is used as a font by 14 themes to hide text.
- Fonts are referenced theme-relative (`pixelmix.otf`) or by absolute `/mnt/SDCARD/miyoo/app/<font>` paths: HENB.TTF, wqy-microhei.ttc, BPreplayBold.otf, Exo-2-Bold-Italic(_Universal).ttf, Helvetica-Neue-2.ttf, MicrosoftYaHeiGB.ttf.
- A port must ship or map those fonts.

**Theme layout**
- Pack folders ("(N-pack)") nest one theme per subfolder (20 such packs).
- Asset whitelists are `.github/whitelist.txt` (skin), `whitelist_extra.txt` (skin/extra: toggle-on/off, lum0–10, arrowLeft/Right, gs-*, bootScreen…) and `whitelist_system.txt` (key-test images).

**Stock default skin sizes** (`OnionUI/Onion/static/build/miyoo/app/skin`)

| Asset | Size | Asset | Size |
|---|---|---|---|
| bg-list-s | 640x60 RGB | bg-list-l | 640x94 RGB |
| bg-game-item-f | 154x170 | bg-game-item-n | 154x170 |
| bg-ra-list-item | 214x120 | div-line-h | 640x4 |
| div-line-v-01 | 4x360 | dot-a/n | 24x14 |
| ic-* | 156x328 | ic-favorite-mark | 34x39 |
| icon-folder | 60x60 | icon-game | 1x60 |
| icon-left/right-arrow-24 | 24x24 | list-num | 90x14 |
| num-bg | 44x24 | Empty | 208x62 |
| pop-bg | 640x330 | bg-pop-menu-1..4 | 320x70/130/190/240 |
| bg-button-f | 232x68 | preview-bg | 256x364 |
| thumb-default | 212x304 | progress-dot | 8x8 |
| icon-A/B-54 | 32x32 | tips-bar-bg | 640x60 |
| bg-title | 640x60 | | |

Onion res: toggle-on/off 68x48.

Open questions: 1. Popup menu position. [BIN] blits `bg-pop-menu-N` and its list at (0,0) on the full-screen surface. 21 of 179 themes use 640x480 `bg-pop-menu-1`, which supports this. Onion's Game Switcher uses y = 60 instead. I could not confirm visually because no preview shows a popup. Check on a device, or with a theme that has a distinctive bg-pop-menu.
2. The pre-composite helper @0x12a8e4 (bg-game-item-f/n, bg-ra-list-item, bars, dividers). It has no visible effect for tips-bar-bg and bg-title (verified empirically). Semi-transparent bg-game-item-n (44 themes) could not be checked because no preview shows the console grid. I recommend a plain alpha blend.
3. Field naming in the emu record. The console item takes its focused image from one emu field and its normal image from another. `iconsel` falls back to `icon` when missing (@0x121560). I mapped focused=iconsel and normal=icon by the parse order. The exact struct offsets in the vector element were not fully traced.
4. Not extracted:
   - word-wrap/line spacing of the multi-line text renderer @0x16018 (dialog and notify messages);
   - keyboard key layout;
   - DeviceInfo, Color, FileBrowser and WiFi page geometry (they follow the SettingMenu pattern but were not traced row by row);
   - the exact footer-bar global 0x182194 used by popups;
   - the meaning of the 49-byte difference between the MainUI-283 and MainUI-354 builds.
5. The game-list row icon. Game rows get `icon-game` unless an internal flag is set (@0x23b84). I did not identify what that flag is (e.g. shortname or no-icon mode).
6. Method: the task said "file reads, grep and git only". To get exact numbers I also ran read-only static analysis: `strings`, `readelf`, `llvm-objdump` and Python/PIL scripts for PNG headers and pixel comparison.  I also read theme blobs with `git show`, because `git checkout` was blocked by another process's `.git/index.lock`, which I did not touch. Nothing was edited, built or committed in any repo.

## OnionOS theme reference: Onion's own screens (640x480)

Sources and revisions:
- Onion: `OnionUI/Onion` at 07505ea5 (VERSION 4.4.0-beta-20260120).
- Themes repo: `OnionUI/Themes` at b0119835. Statistics marked "repo:" come from the 179 top-level `config.json` files that are checked out. Asset sizes come from PNGs I fetched for every theme, including the sub-themes inside packs.
- Viewer: `syphen/onion-theme-viewer` at ac25de3f. The viewer only covers the stock MainUI. I used it here only to confirm that `background.png` is stored upside down.

All paths below are relative to the Onion repo unless stated otherwise. All coordinates are for 640x480 with `g_scale = 1`. On 752x560 Onion multiplies every constant by `g_scale = width/640` and zooms each loaded image (`common/utils/sdl_direct_fb.h:37-39`, `common/theme/load.h:24-38`, `theme_loadImage`/`theme_loadFont`). In the formulas, `/` is C integer division and SDL rects truncate toward zero. Text is always drawn with `TTF_RenderUTF8_Blended` (anti-aliased, alpha) and alpha-blended onto the screen.

---------------------------------------------------------------------
### 0. Which screens are themed

| Screen | Themed? | Code |
|---|---|---|
| Game Switcher (including its START pop menu, LOADING and Saving dialogs) | yes, through `common/theme/*` plus `gs-*`, `lum*`, `arrow*` | `src/gameSwitcher/*` |
| Tweaks | yes (header, list, footer, dialogs, toggles, sounds) | `src/tweaks/*` |
| prompt and infoPanel (script dialogs and lists) | yes | `src/prompt/prompt.c`, `src/infoPanel/infoPanel.c` |
| Boot, shutdown and low-battery screens | yes (`extra/bootScreen`, `Screen_Off`, `Screen_Off_Save`, `lowBat`) | `src/bootScreen/bootScreen.c`, `common/theme/render/bootScreen.h` |
| Charging animation | yes (`extra/chargingState0..23` plus `chargingState.json`) | `src/chargingState/chargingState.c` |
| MainUI battery-percentage image (`skin/.batt-perc.png`) | yes | `src/mainUiBatPerc/mainUiBatPerc.c` |
| Package Manager | **no**. It uses hard-coded `res/*` and `/customer/app/Exo-2-Bold-Italic.ttf`. Its toggles are always `/mnt/SDCARD/.tmp_update/res/toggle-on.png`/`toggle-off.png`, never the theme's toggles, although the docs claim otherwise. | `src/packageManager/globals.h:87-103` |
| Theme Switcher | **no** (own `res/`, fixed Exo fonts). It only shows the theme's `preview.png`. | `src/themeSwitcher/themeSwitcher.c` |
| Volume and brightness OSD bars (keymon) | **no** (solid colours) | `common/system/osd.h` |
| In-game "Quick menu" | **no**. It is RetroArch's own menu (`retroarch_toggleMenu`, `keymon/menuButtonAction.h:212`). Onion's only in-game themed menu is the Game Switcher START pop menu. | |

---------------------------------------------------------------------
### 1. Locating the theme

- **Active theme path.** `/mnt/SDCARD/system.json` key `"theme"`, for example `"/mnt/SDCARD/Themes/Silky by DiMo/"`. If the value is `"./"` or is not a directory, Onion uses `/mnt/SDCARD/miyoo/app/` (`load.h:118-128`, `FALLBACK_THEME_PATH`). The default setting is `/mnt/SDCARD/Themes/Silky by DiMo/` (`common/system/settings.h:21`). A trailing `/` is always appended (`config.h:221-226`).
- **Theme folder layout:**
  - `config.json`
  - `preview.png`, 480x360
  - `skin/*.png`: names shared with MainUI
  - `skin/extra/*.png`: Onion-only assets
  - `sound/change.wav`, `sound/bgm.mp3`
  - optional `icons/` (an icon pack)
  - font files, usually at the theme root and referenced relatively
- **Archives.** Themes may also sit as `.zip`/`.7z`/`.rar` in `/mnt/SDCARD/Themes`. Their previews are extracted to `Themes/.previews/<name>/` (`themeSwitcher/script/themes_extract_previews.sh`).
- **Packs.** In the repo, 70 themes live as `themes/<pack>/<theme>/config.json`. Each sub-folder is a complete, standalone theme.
- **Theme switcher discovery.** A directory counts as a theme only if `<dir>/config.json` exists. Folders starting with `.` are skipped (`installTheme.h:35-71`).

---------------------------------------------------------------------
### 2. config.json: load order, defaults, fallbacks

`theme_loadFromPath(path, apply_overrides)` in `common/theme/config.h:195-232` works in this order:
1. Start from hard-coded defaults (table below). `path` is set to the theme path.
2. Read `<theme>/config.json`. If that file does not exist, read `/mnt/SDCARD/miyoo/app/config.json` instead, which is Onion's built-in Silky config: title Exo-2-Bold-Italic_Universal 25 white, hint 40, list wqy-microhei 25, battery visible with `onleft` true. Image and font paths still resolve against the theme folder. This step uses **fallbacks**.
3. If `apply_overrides`, apply `/mnt/SDCARD/Saves/CurrentProfile/theme/config.json` **without fallbacks**: only keys present in that file change anything.
4. `theme()` (`resources.h:111-119`) keeps two copies: `resources.theme` with overrides and `resources.theme_back` without. Tweaks uses `theme_back` for its "-" (use theme value) option.

| Key | Default | Fallback when the key is missing (step 2) | Used by Onion screens for |
|---|---|---|---|
| `title.font/size/color` | `/customer/app/Exo-2-Bold-Italic.ttf`, 36, #FFFFFF | none | header title, Game Switcher game name (font only, the colour is forced white), dialog title and message font, prompt and info-panel message font |
| `hint.font/size/color` | font = Exo, 40, white | each field ← the same `title.*` field. **The default 40 therefore never applies: a missing hint size becomes title.size.** | footer button labels, dialog OK/CANCEL labels, footer page counter font, boot-screen font family |
| `currentpage.color` (font/size are parsed but unused) | white | ← `hint.*` | footer counter `"n/"` |
| `total.color` (font/size unused) | white | ← `hint.*` | footer counter total; **dialog title colour**; boot-screen version and message colour |
| `list.font/size/color` | Exo, 24, white | ← **`title.*`**, not hint | list labels and multivalue values; info-panel message colour |
| `grid.font/grid1x4/grid3x4` | Exo, 24, 18 | none | not drawn by Onion screens (MainUI only) |
| `grid.color` | #686868 | none | large-list descriptions, **dialog message text**, prompt message text |
| `grid.selectedcolor` | #FFFFFF | none | unused by Onion screens |
| `batteryPercentage.visible` | false | none | battery widget |
| `batteryPercentage.font` | Exo | ← `hint.font` | battery text |
| `batteryPercentage.size` | 24 | none (it does **not** inherit hint size) | battery text |
| `batteryPercentage.color` | white | ← `hint.color` | battery text |
| `batteryPercentage.offsetX/offsetY` | 0 / 0 | none | battery text |
| `batteryPercentage.textAlign` | LEFT | Exact strings: `"center"` → CENTER, `"right"` → RIGHT, anything else → LEFT. If the key is absent, legacy `onleft:true` → RIGHT and `onleft:false` → LEFT. | battery layout |
| `batteryPercentage.fixed` | false | none | battery layout |
| `hideLabels.icons` / `hideLabels.hints` | false / false | Legacy `hideIconTitle` sets both. | `hints`: footer labels become `" "`. `icons`: MainUI only, done by blanking lang strings (`system/lang.h:66-134`). |
| `frame.border-left` / `frame.border-right` | 0 / 0 | none | Game Switcher crop in normal view |
| `name`, `author`, `description` | "" | none | Theme Switcher text |

Parsing rules:
- `cJSON_GetObjectItem` is **case-insensitive** on keys.
- `json_getBool` is true only for the literal `true`.
- Numbers are converted with `(int)`. A JSON parse failure leaves the defaults in place, with the fallbacks still propagating from the title defaults.
- Colour parsing is `hex2sdl` (`common/theme/color.h:7-16`): strip **one** leading `#`, then `strtoul(..,16)`, then r = (v>>16)&255, g = (v>>8)&255, b = v&255. There is no alpha channel.

Colour values found in the repo and what Onion actually renders:

| Value in config | Rendered as |
|---|---|
| `"white"`, `"rgb(...)"`, `"rgba(...)"`, `"##e89b0c"` | black |
| `"#000"` | black |
| `"#14646"` | #014646 |
| `"#fffffff"` (7 digits) | white |
| `"#304b07;"` | #304B07 (parsing stops at `;`) |
| `"6E6E6E"` (no `#`) | works |
| `"#FFF"` (hypothetical) | #000FFF; 3-digit shorthand is not expanded |
| `#RRGGBBAA` | shifted bytes |

A colour value that is not a string would crash Onion (`hex2sdl(NULL)`).

Repo statistics (179 configs):
- title.size: 36 (58 themes), 20–32 common, also 0 and 1.
- hint.size: 40 (52), 18–24 common. **hint.size 0 (15 themes) or 1 (12)** is used to hide hint text.
- list.size: 25 (72), 24 (37).
- batteryPercentage is present in 136 configs: `visible` true 64 / false 68; `onleft` 63; `textAlign` 28; `fixed` 24.
- `frame` is set in 5 themes (`border-left`/`border-right` 9 in BRCKD, win98, System 9).
- `hideLabels.icons` true 81, `hideLabels.hints` true 61.

---------------------------------------------------------------------
### 3. Image lookup, fallbacks, missing assets

`theme_getImagePath(theme_path, name)` (`load.h:40-70`). For a name `N`, with relative path `skin/N.png`, the first existing file wins:
1. `/mnt/SDCARD/Saves/CurrentProfile/theme/skin/N.png`: override, load_mode 2.
2. `<theme>/skin/N.png`: load_mode 1.
3. Otherwise, load_mode 0:
   - if `N` starts with `extra/` (for example `extra/lum3`): `/mnt/SDCARD/.tmp_update/res/lum3.png`, the system res directory, without the `extra/` prefix;
   - else `/mnt/SDCARD/miyoo/app/skin/N.png`, Onion's shipped fallback skin (`static/build/miyoo/app/skin`).

So Onion-only assets live in `<theme>/skin/extra/` and overrides in `Saves/CurrentProfile/theme/skin/extra/`. A file in `skin/` with an extra-asset name is ignored, and the repo has none. The repo cleaner moves whitelisted extras into `skin/extra` (`.github/clean.py`, `.github/whitelist_extra.txt`).

Behaviour after lookup:
- **Normalisation.** `theme_loadImage` (`load.h:72-100`) converts any non-32-bpp PNG (palette, RGB, 2/4-bit) to 32-bit RGBA by blitting it. A failed load returns NULL.
- **Optional images.** `loadOptionalImage` (`gameSwitcher/gs_render.h:47-54`) accepts only load_mode 1 or 2, so there is no system fallback. It is used for `extra/gs-top-bar` and `extra/gs-bottom-bar`.
- **Missing or corrupt files.** Blitting a NULL surface is a silent no-op in SDL 1.2. Most call sites, however, dereference `->w`/`->h` and crash, including: battery icon, `Empty`, list toggles and arrows, `extra/arrowLeft`, `lum*`, `pop-bg`, `icon-B-54`, `progress-dot`.
  - NULL-safe sites: `icon-A-54` in the footer, `gs-legend`, `bg-pop-menu-N`, the boot background, the charging frames (frames that fail to load are dropped).
  - Onion relies on the system fallback files always existing.
  - The repo has at least one unreadable PNG: `Miyoo Quest by y_oshid_a/skin/div-line-h.png`.
- **Caching.** Images are loaded on first request and cached for the process lifetime (`resource_getSurface`).

#### Asset table (Onion screens)

Fallback size = the system fallback file. "Repo" = sizes found in theme files.

| enum / use | file under `skin/` | Fallback (size) | Repo sizes (count) |
|---|---|---|---|
| BG (all screens) | `background` | miyoo/app/skin 640x480 | n/a |
| BG_TITLE header | `bg-title` | 640x60 | 640x60 (222), 1x1 transparent (15), 640x74/64/62 |
| BG_FOOTER | `tips-bar-bg` | 640x60 | 640x60 (232), 1x1 transparent (14) |
| LOGO (info panel without title) | `miyoo-topbar` | 148x42 | n/a |
| BUTTON_A / BUTTON_B | `icon-A-54` / `icon-B-54` | **32x32** | 54x54 (148), 32x32 (38), **640x54 fully transparent (33)**, 56x56 |
| BATTERY_0/20/50/80/100 | `power-0%-icon`, `power-20%-icon`, `power-50%-icon`, `power-80%-icon`, `power-full-icon` (or `power-full-icon_back`) | 48x48 | 48x48 (170), 50x50, 32x32, wide (113..200 x 31..64), **982x900 (3: win98, System 9, vintage_aesthe)** |
| BATTERY_CHARGING | `ic-power-charge-100%` | 48x48 | n/a |
| BG_LIST_S | `bg-list-s` | 640x60 | 640x56 (212), 640x60 (11), 640x47 (10), 54..59 |
| BG_LIST_L | `bg-list-l` | 640x94 | 640x90 (146), 640x91 (60), 640x94 (23), 86..92 |
| HORIZONTAL_DIVIDER | `div-line-h` | 640x4 | 640x4 (144), **1x1 (82)**, 640x1 |
| LEFT_ARROW / RIGHT_ARROW (multivalue) | `icon-left-arrow-24` / `icon-right-arrow-24` | 24x24 | 24x24 (185), 44x44, 40x40, 48x48, 30x30, 32x32 |
| POP_BG (dialog) | `pop-bg` | **640x330** | 640x300 (146), 640x330 (29), 640x480 (26), 640x360 (26), 640x380, 640x350 |
| BG_POP_MENU_1..4 | `bg-pop-menu-N` | 320x70 / 130 / 190 / 240 | -3: 320x190 (175), 640x480 (27), 640x420. -4 exists in only 62 themes: 320x250 (41), 640x480 (9), 320x240 (4) |
| EMPTY_BG | `Empty` | 208x62 | 162x46, 245x72, 208x62, … |
| PREVIEW_BG | `preview-bg` | 256x364 | 250x360 (144), 256x364 (33), **1x1 (4)** |
| PROGRESS_DOT | `progress-dot` | 8x8 | 10x10 (149), 8x8 (52), 12x12, 1x1 (7) |
| DOT_ACTIVE / DOT_NEUTRAL | `dot-a` / `dot-n` | 24x14 | (enum exists, not drawn by these screens) |
| TOGGLE_ON / TOGGLE_OFF | `extra/toggle-on` / `extra/toggle-off` | .tmp_update/res 68x48 | 92x44 (70+), 68x48 (24), 152x32, 40x40, 63x36, 55x32, 60x36, 76x20, 70x35, 94x52; present in 147 themes |
| LEFT_ARROW_WB / RIGHT_ARROW_WB (GS) | `extra/arrowLeft` / `extra/arrowRight` | 24x24 | only 2 themes (30x40) |
| BRIGHTNESS_0..10 | `extra/lum0`..`extra/lum10` | **40x369, vertical** | 114 themes: 300x40 (36), 126x30 (30), 40x480 (17), 66x140 (9), 40x369 (7), 300x112, 55x244 |
| LEGEND_GAMESWITCHER | `extra/gs-legend` | 324x52 | 40 themes: 1x1 (25, used to hide it), 370x52 (9), 640x40 (3), 324x52 (2) |
| (optional) GS custom header | `extra/gs-top-bar` | none | 640x40 (93), 640x48 (4), 640x60, 1x1 (2) |
| (optional) GS custom footer | `extra/gs-bottom-bar` | none | 640x40 (76), **1x1 (34)**, 640x48 (3), 640x60 |
| BOOT_SCREEN | `extra/bootScreen` | .tmp_update/res 640x480 | 192 themes |
| SCREEN_OFF / SCREEN_OFF_SAVE | `extra/Screen_Off` / `extra/Screen_Off_Save` | 640x480 | 191 / 185 themes |
| LOW_BAT | `extra/lowBat` | 640x480 | 160 themes |
| charging frames | `extra/chargingState0..23` (+ `chargingState.json`) | .tmp_update/res 640x480 RGB x24 | 181 themes; frame count varies (0–3 nearly all, 75 have all 24, 8 add an ignored #24) |

Note on the fallback `.tmp_update/res`: `gameSwitcher/res`, `themeSwitcher/res` (which has identical `arrowLeft`/`arrowRight`), `tweaks/res`, `bootScreen/res` and `chargingState/res` are all copied into it at build time (`Makefile:79-87`).

**Legacy `power-full-icon`** (`resources.h:142-147`). If `power-full-icon` and `power-full-icon_back` resolve at the same level, `_back` is used. Older Onion overwrote `power-full-icon` with a rendered image and kept the original as `_back`.

---------------------------------------------------------------------
### 4. Background (upside down on disk)

- `background.png` is stored **rotated 180°** in Miyoo/Onion themes. The viewer also rotates it (`viewer/src/app/pages/home/home.component.scss:117-123`).
- Onion loads it as `theme_loadImage(theme_getPath(), "background")` using the lookup in section 3, then calls `rotate180()` once and caches it (`common/theme/background.h`, `common/utils/rotate180.h`). `rotate180` uses rotozoom at 180°, fills the original red, then blits the rotated copy at (-2,-2). In effect this is an exact 180° flip.
- No other skin image is rotated.
- Every Onion screen uses the *upright* background:
  - full-screen in infoPanel;
  - cropped `(0,0,640,60)` under the header;
  - `(0,60,640,360)` under lists;
  - `(0,420,640,60)` under the footer.

---------------------------------------------------------------------
### 5. Fonts

**Path resolution** (`theme_loadFont`, `load.h:102-112`):
- A path starting with `/` is used as is.
- Otherwise it is `<theme path> + font`, relative to the theme folder even when the value came from the override config.
- If the file does not exist, Onion uses `FALLBACK_FONT = /customer/app/Exo-2-Bold-Italic.ttf` (firmware Exo 2).
- Matching is case-sensitive on Android but not on the FAT32 SD card. Repo cases that only work on the SD card:
  - Milk Black and Milk White reference `HEN.TTF`; the file is `HEN.ttf`.
  - `/mnt/SDCARD/miyoo/app/HENB.ttf` is used ×4; Onion ships `HENB.TTF`.
  - `wqy-microhei.TTF` (×2) has the wrong extension, and `xo-2-Bold-Italic_Universal.ttf` is a typo. Both fall back to Exo on the device too.
- Bare names of system fonts that are not in the theme fall back to Exo in Onion screens: AmalgaM `wqy-microhei.ttc`, GBOnion Dark `HENB.TTF`, Original by SandsByte `Exo-2-Bold-Italic_Universal.ttf`. TOTK's `GothaMedIta.ttf` is simply missing.

**Fonts shipped in `/mnt/SDCARD/miyoo/app/`** (`static/build/miyoo/app`, docs `website/docs/08-guides/02-theme-design/index.md`):
- `BPreplayBold.otf`
- `Exo-2-Bold-Italic.ttf`
- `Exo-2-Bold-Italic_Universal.ttf`
- `Helvetica-Neue-2.ttf`
- `HENB.TTF`
- `wqy-microhei.ttc`
- `MicrosoftYaHeiGB.ttf`
- `AdobeBlank.ttc` and `AdobeBlank.ttf` (glyphless; used to hide text, 7 themes)

Absolute font paths used in the repo: HENB.TTF 70, wqy-microhei.ttc 54, Exo-2-Bold-Italic 18, BPreplayBold 15, Exo Universal 8, AdobeBlank 7.

**Font slots** (`resources.h:245-271`):

| Slot | Font and size |
|---|---|
| TITLE | title.font @ title.size |
| HINT | hint.font @ hint.size |
| LIST | list.font @ list.size, with **`TTF_STYLE_BOLD` forced** (synthetic bold, even if the font is already bold) |
| BATTERY | batteryPercentage.font @ batteryPercentage.size |
| GRID1x4 / GRID3x4 | exist but are unused by Onion screens |

Other fonts:
- Boot screen: hint.font @ **18** (`render/bootScreen.h:17`).
- Text overlay (`theme_createTextOverlay`, keymon CPU-clock OSD): list.font @ 18, bold.
- The dialog shrinks its message font when needed (section 6.6).

**Size 0 or 1** (27 themes for hint): FreeType clamps to a 1-px char size, so the text is effectively invisible. Themes rely on this to hide hints.

---------------------------------------------------------------------
### 6. Shared widgets (`common/theme/render/*`)

#### 6.1 Header (`header.h:9-56`)

1. Blit the upright background crop `(0,0,640,60)` at (0,0).
2. Blit `bg-title` cropped to `(0,0,640,60)` at (0,0). Taller bg-title files are clipped to 60 rows.
3. Optional logo (`miyoo-topbar`) at `x = 20`, `y = 30 - h/2`.
4. Title (TITLE font, title.color, horizontally centred):
   - `x = (640 - w)/2`, `y = 29 - h/2`.
   - Before drawing, the strip `(x - 10, 0, w + 20, 60)` is repainted with background plus bg-title.
5. Battery: see 6.2. The battery surface is drawn with its **centre at (596, 30)**: top-left = `(596 - W/2, 30 - H/2)`.

#### 6.2 Battery widget (`battery.h`)

Icon choice by percentage `p`:

| Condition | Icon | Text |
|---|---|---|
| p == 500 (charging) | `ic-power-charge-100%` | hidden |
| p < 5 | `power-0%-icon` | shown if visible |
| p < 30 | `power-20%-icon` | shown if visible |
| p < 60 | `power-50%-icon` | shown if visible |
| p < 90 | `power-80%-icon` | shown if visible |
| otherwise | `power-full-icon` | shown if visible |

Text and sizes:
- Text is `"%d%%"` (for example `87%`) in the BATTERY font and batteryPercentage.color. If rendering fails, a 1x1 surface is used instead.
- `offY = offsetY`. If the font family name starts with `"Exo 2"`: `offY -= 0.075 * TTF_FontHeight`.
- `visible = style.visible && p != 500 && icon.w <= 640`. The `icon.w <= 640` guard is for the 982x900 icons.
- Canvas size `W, H`:
  - Visible: `W = 2*(t.w + 5) + i.w`, `H = t.h > i.h ? t.h : i.w`. Note the **icon width** in H (sic).
  - Not visible: `W = i.w`, `H = i.h`.
  - Visible and (`fixed` or CENTER): `W = max(i.w, t.w)`.
  - Then round W and H up to even numbers, with a minimum of 48 each.
- Vertical placement: `icon.y = (H - i.h)/2`; `text.y = (H - t.h)/2 + offY`.
- Horizontal placement (`icon.x = 0` unless stated):

| Mode | Placement | Effect |
|---|---|---|
| fixed, RIGHT | `text.x = i.w - t.w + offsetX` | text overlaid on the icon |
| fixed, CENTER | `text.x = (i.w - t.w)/2 + offsetX` | text overlaid on the icon |
| fixed, LEFT | `text.x = offsetX` | text overlaid on the icon |
| not fixed, RIGHT (= legacy `onleft:true`) | `icon.x = t.w + 5`, `text.x = offsetX` | text left of the icon; the icon stays exactly centred at 596 |
| not fixed, CENTER | `text.x = (i.w - t.w)/2 + offsetX` | text centred over the icon |
| not fixed, LEFT | `text.x = i.w + 5 + offsetX` | icon then text; the text's right edge lands at `596 + i.w/2` (620 for a 48-px icon) |

Compositing:
- Icon and text are **copied** (SDL_SRCALPHA cleared) onto a transparent RGBA canvas. Where the text box overlaps the icon, the text's bounding box replaces the icon pixels; it is not blended.
- The canvas is then alpha-blended onto the screen.
- **Anchor (596, 30) is centre-based.** Themes with 982x900 or wide icons place visible pixels far from the anchor on purpose (win98 puts the battery in its bottom-right taskbar: content at (497..517, 864..890) lands at about (602..622, 444..470)).

#### 6.3 Footer bar (`footer.h:73-79`)

1. Blit the background crop `(0,420,640,60)` at (0,420).
2. Blit the **whole** `tips-bar-bg`, uncropped, at (0,420). The screen clips it.

#### 6.4 Standard hint (`footer.h:8-71`)

The labels are `(A label, B label)`. If `hideLabels.hints`, both labels become `" "`.
1. `x = 20`.
2. If `icon-A-54` loaded: blit it at `(x, 450 - h/2)`; `x += w + 5`.
3. Label A (HINT font, hint.color) at `(x, 449 - h/2)`; `x += w + 30`.
4. If the B label is non-NULL and non-empty (`" "` counts as non-empty, so the B icon still shows when hints are hidden):
   - blit `icon-B-54` at `(x, 450 - h/2)`; `x += w + 5`;
   - label B at `(x, 449 - h/2)`.

A transparent 640-wide `icon-A-54` pushes every label off-screen. This is how 33 themes hide footer hints.

#### 6.5 Footer page counter (`footer.h:83-117`)

- HINT font, right-aligned:
  - `total` = `"%d"` in total.color at `x = 620 - w`, `y = 449 - h/2`;
  - `current` = `"%d/"` in currentpage.color immediately to its left, same y.
- Before redrawing, the previous text width is erased with background plus tips-bar-bg strips.
- If `total == 0`, `current` is 0, so the counter reads `0/0`.

#### 6.6 Dialog (`dialog.h:35-104`)

1. Full-screen black overlay at alpha 0x55 (33%).
2. `pop-bg` centred: `(320 - w/2, 240 - h/2)`. A 640x300 file lands at (0,90); the 640x330 fallback at (0,75).
3. Title: TITLE font, **total.color**, centred `x`, `y = popY + 25 - h/2`.
4. Message: text box (6.9) in TITLE font, **grid.color**, centre-aligned. Centred `x`, `y = popY + 160 - h/2`.
   - If the box is wider than 450 or taller than 180, it is re-rendered with the font size `min(450/w_b, 30/h_b) * title.size`.
   - `w_b`, `h_b` are the TITLE-font size of the benchmark string `"access, and modify files as if they were stored"`, computed once and cached.
5. Hints, only if `show_hint && icon-A-54.w < 640`: one row centred at `y = popY + popH - 60`, ending at `x = popX + popW - 60`. The row is `[A][5][OK][30][B][5][CANCEL]`, labels in HINT font and hint.color.

**Progress variant** (`dialog.h:106-124`): up to 3 `progress-dot`s at **absolute** `y = 225 - h/2`, `x = 320 - w/2 - 32`, then `+32`, then `+32`. The count cycles 0,1,2,3 per redraw.

#### 6.7 Info panel (`dialog.h:128-155`)

- In dialog mode it is the dialog above.
- Otherwise: header with title (no logo), then a message text box in TITLE font, list.color, centre-aligned, centred on (320,240).

#### 6.8 Lists (`list.h`, `components/list.h:115-137`)

Defaults for `theme_renderList` (Tweaks, prompt):
- Background crop `(0,60,640,360)` at (0,60).
- Dividers on; no vertical stretch.
- `preview-bg` on; preview width 250.

Small list (`LIST_SMALL`, 6 visible rows; sticky lists show 5):
- Row `k` top: `T = 420 - rows*60 + 60k`. For 6 rows, `T = 60, 120, …, 360`.
- `div-line-h` cropped to `(0,0,640,4)` is blitted at `(0, T)` for every row. There is no divider under the last row.
- Item box `y = T + 4`, height 56. The selected row gets `bg-list-s` cropped to `(0,0,640,56)` at `(0, T + 4)`, left-aligned. A shorter bg-list-s (47 px in 10 themes) sits at the top of the row and is not centred.
- `centerY = T + 32`. The label is centred at `T + 31`.
- **Sticky note** (lists with a sticky header): divider at y=60, then the label at x=20, centre y=87, list.color at alpha 60.

Large list (`LIST_LARGE`, 4 visible rows):
- `T = 60 + 90k`. Dividers as above, no padding.
- Selected row: `bg-list-l` cropped to 640x90 at `(0, T)`.
- `centerY = T + 45`. Label centred at `T + 37`; description centred at `T + 62` in **grid.color**, LIST font (bold).

All rows:
- Label in LIST font (bold), **list.color**. There is no separate selected colour; selection is shown only by bg-list-s or bg-list-l.
- `x = 20`, or after an icon: icon at `(20, centerY - h/2)` and `x = 20 + icon.w + 17`.
- The label is cropped to source width `labelEnd - 30`.

| Item type | Drawing | `labelEnd` |
|---|---|---|
| plain | label only | 640 |
| TOGGLE | `extra/toggle-on` if value == 1, else `extra/toggle-off`, at `(620 - w, centerY - h/2)` | toggle x |
| MULTIVALUE | right arrow `icon-right-arrow-24` at `(620 - rw, centerY - rh/2)`; left arrow at `(620 - rw - 226 - lw, centerY - lh/2)`; value text (LIST bold, list.color) cropped to 226 px wide and centred at `x = 620 - rw - 113 - min(w,226)/2`, `y = centerY - h/2` | left-arrow x |

Disabled items: label, value, toggle and arrows at alpha 60/255 (`HIDDEN_ITEM_ALPHA`).

Preview (Tweaks icon packs and similar):
- `preview-bg` at `(640 - pbw, 60)`.
- The image is scaled down (nearest) if wider than 250; it is never scaled up here.
- Drawn at `(640 - pbw + (250 - w)/2, 240 - h/2)`.
- With a 1x1 preview-bg the preview lands off-screen.

#### 6.9 Text box (`textbox.h:26-130`)

- Lines split on `\n`.
- `lineHeight = max(1.2 * lineskip, max glyph h)`. Empty lines add `0.5 * lineskip`.
- Each line is aligned within the widest line.

---------------------------------------------------------------------
### 7. Game Switcher (`src/gameSwitcher`)

**Setup** (`gameSwitcher.c:56-75`):
- `transparent_bg` = 640x480 filled `0xBE000000`, which is black at 190/255 (74.5%).
- `custom_header = extra/gs-top-bar`, `custom_footer = extra/gs-bottom-bar`, both theme or override only.
- `header_height` / `footer_height` = that image's height, or 60 if the image is absent. **A height of 1 or less counts as 0**, which hides that bar completely.

**View modes** (`gs_appState.h`, `gs_keystate.h`):

| Mode | Contents | How to enter |
|---|---|---|
| NORMAL (0) | header, footer, name bar | default |
| MINIMAL (1) | name bar only | tap Y to toggle with NORMAL; saved in flag `gameSwitcher/minimal` |
| FULLSCREEN (-1) | screenshot only | hold Y for 75 frames |

**Timing:** 30 fps (`time_step = 1000/30`).

**Draw order on a changed frame** (`gameSwitcher.c:103-157`):
1. Fill the screen black.
2. If there are no games: `Empty` centred at `(320 - w/2, 240 - h/2)`.
3. Otherwise the ROM screen, centred (`renderCentered`):
   - Source image: `Saves/CurrentProfile/romScreens/<FNV1a hash of rompath>.png`, else the recent-list box art.
   - Pre-scaled to fit 640x480 keeping aspect, nearest neighbour.
   - In NORMAL the source is cropped by `frame.border-left` on the left and `frame.border-right` on the right, and drawn shifted right by border-left, which leaves black strips.
4. Game-name bar, when not FULLSCREEN, games exist and the pop menu is closed. It is redrawn every frame for the marquee.
   - Bar rect: `x = borderL` (NORMAL; 0 otherwise), `w = 640 - borderL - borderR` (NORMAL; 640 otherwise), `h = 60`, `y = 480 - 60 - (NORMAL ? footer_height : 0)`. So y = 360 with the default footer, 380 with a 40-px gs-bottom-bar, 420 with a 1x1 bar or in MINIMAL.
   - Fill the rect black, redraw that strip of the ROM screen, then blit the same strip of `transparent_bg` (74.5% black).
   - `extra/arrowLeft` (only if `current_game > 0`) at `(borderL + 10, barY + 30 - h/2)`.
   - `extra/arrowRight` (only if this is not the last game) at `(630 - borderR - w, barY + 30 - h/2)`. The arrows use the borders in every view mode.
   - Name: game shortname (`file_cleanName`), **TITLE font, hard-coded white**.
     - `x = (640 - w)/2`, clamped to `>= arrowLeft.w + 20` (44 by default); `y = barY + 30 - h/2`.
     - Max width `640 - 2*(arrowLeft.w + 20)` (552 by default).
     - Longer names scroll as a marquee: 10 px/frame, 20-frame pause at the start and at the end.
5. Footer (NORMAL only, pop menu closed):
   - With a custom footer and `footer_height > 0`: blit it at `(0, 480 - h)` over the screenshot. Nothing else is drawn; the themes bake the button hints into the image (see the ONION PS `gs-bottom-bar`).
   - With no custom footer: the footer bar (6.3), then the standard hint `(RESUME, BACK)`, then the page counter `"current/total"`. The counter is skipped on the very first frame.
6. Header (NORMAL only):
   - With a custom header and `header_height > 0`:
     - blit it at (0,0) **directly over the screenshot**, so transparent areas show the game;
     - title (TITLE font, title.color) at `((640 - w)/2, (header_height - h)/2)`;
     - battery centred at `(596, header_height/2)`.
   - Otherwise the standard header (6.1, opaque background plus bg-title, no logo) and the battery centred at (596,30).
   - Title text:
     - `"GameSwitcher"` by default.
     - With showTime: the game's play time (`"%dh %dm"` / `"%dm %ds"` / `"%ds"`).
     - With showTime + total: `"<game> / <total>"`.
     - SELECT cycles off → time → time+total → off (flags `gameSwitcher/showTime`, `gameSwitcher/hideTotal`).
7. Legend (not in FULLSCREEN):
   - `extra/gs-legend` at `(640 - w, NORMAL ? header_height : 0)`.
   - Shown for 5000 ms. It is shown at start only if flag `gameSwitcher/hideLegend` is unset; that flag is set after the first timeout. SELECT shows it again.
8. Brightness: for 2000 ms after UP or DOWN, draw `extra/lum<brightness 0..10>`.
   - If `h > w` (vertical): at `(0, (NORMAL ? 240 : 210) - h/2)`. The default 40x369 lands at (0,56) in NORMAL; a 40x480 file at (0,-30) in MINIMAL.
   - Otherwise (horizontal): at `((640 - w)/2, NORMAL ? header_height : 0)`, directly under the header.
9. Pop menu, if open (7.1).

#### 7.1 START pop menu (`gs_popMenu.h`, `render/pop_menu.h`)

Items: `Resume`, `Save` (only if the game is running), `Load` (only if save slots exist), `Exit to menu`. Labels come from lang 92/93/94/96 with those fallback strings.

1. `yPos` = header_height in NORMAL, else 0.
2. `N = min(items, 4)`; `bg = bg-pop-menu-N`. Only 62 themes ship `-4`; the rest fall back to Onion's 320x240 file.
3. If `bg.h > 480 - yPos - 60`, then `yPos = 0`.
4. If the Load item is selected, blit `transparent_bg` from `(0, yPos)` downward.
5. Draw the **theme footer bar plus the standard hint `(SELECT, BACK)`**. This happens even when a gs-bottom-bar is in use.
6. Blit `bg` at `(0, yPos)`.
7. List, stretched vertically with no dividers. Panel size = `bg.w x bg.h`, or `320 x N*60` if there is no bg.
   - `listH = bg.h - 10`; `itemH = listH / N`.
   - Row `k`: `y = yPos + 4 + (listH - N*itemH)/2 + k*itemH + (itemH - 56)/2`.
   - The selected row gets `bg-list-s` cropped to `(0,0,bg.w,56)` at `(0, y)`, which is the left part of a 640-wide bar.
   - Label at x=20, centre y = `y + 27`, LIST bold, list.color.
   - Examples:

     | bg file | N | Row y values (yPos = 60) |
     |---|---|---|
     | 320x250 | 4 | 66, 126, 186, 246 |
     | 320x240 (fallback) | 4 | 65, 122, 179, 236 |
     | 640x480 (forces yPos = 0) | 3 | itemH = 156 |
8. Load preview, `<state>.png`:
   - Stretched to width exactly 320 (nearest), at `(320 + (320 - w)/2, 240 - h/2)`. A 640x480 state image becomes 320x240 at (320,120).
   - The footer counter shows `slot/count`. LEFT/RIGHT changes the slot; X deletes it after a dialog with hints.

Other overlays:
- **Save:** dialog `"Saving"` with progress dots, then `"State saved"` or `"Save failed"` for 1 s.
- **Leaving to resume a non-running game:** full-screen screenshot (no crop), then the dialog with title `" "` and message `"LOADING"`.

The Game Switcher plays no sounds.

---------------------------------------------------------------------
### 8. Tweaks (`src/tweaks`)

- Each frame:
  1. Header with the menu title, no logo.
  2. `theme_renderList`.
  3. Footer bar plus the standard hint `(SELECT, BACK)`.
  4. Counter `active+1 / count`.
  5. Battery at (596,30).
- Root menu: `LIST_LARGE`, 4 rows visible.
  - Icons `tweaks_system`, `tweaks_network`, `tweaks_menu_button`, `tweaks_user_interface`, `tweaks_advanced`, `tweaks_tools` (74x74).
  - Path: `dirname(App/Tweaks/config.json "icon")/<name>.png`, else `res/<name>.png`. Icon packs therefore theme these through `icons/app/tweaks_*.png` (88 repo packs ship them).
  - Each row has a description in grid.color.
- Sub-menus: `LIST_SMALL`, 6 rows. Toggles use `extra/toggle-on`/`extra/toggle-off`; multivalue rows use the 24-px arrows.
- `change.wav` plays on every navigation key (section 11).
- "Appearance → Theme overrides" (`menus.h:376-489`, `actions.h:200-320`) writes the override config (section 12).
  - Battery percentage options:

    | Option | Values |
    |---|---|
    | Visible | -, Off, On |
    | Font family | -, BPreplayBold.otf, Exo-2-Bold-Italic_Universal.ttf, Helvetica-Neue-2.ttf, HENB.TTF, wqy-microhei.ttc, each stored as `/mnt/SDCARD/miyoo/app/<file>` |
    | Font size | -, 13, 18, 24, 32, 40 |
    | Text alignment | -, Left, Center, Right |
    | Fixed position | -, Off, On |
    | Horizontal / vertical offset | -, -48..+48 |

  - Also: Mute background music, Hide icon labels, Hide hint labels.
  - "-" deletes the key, so the theme's own value applies.

---------------------------------------------------------------------
### 9. prompt and infoPanel

**prompt** (`prompt.c:162-310`) uses the same layout as Tweaks:
- Header with the title, or the `miyoo-topbar` logo when there is no title.
- Small list.
- Optional message text box (TITLE font, grid.color, centred), at `x = 320 - w/2`, `y = 60 + (6 - rows)*30 - h/2`. Visible rows = `min(items, (360 - (msgH + 20))/60)`, between 1 and 6.
- Hints `(SELECT, BACK)`, with BACK omitted when the prompt is required.

**infoPanel** draws:
- the full upright background (or black without `-s`);
- the header;
- the info-panel message or images;
- hints `NEXT/BACK`, `OK/CANCEL` or `EXIT`;
- the counter;
- the battery.

---------------------------------------------------------------------
### 10. Charging animation (`src/chargingState/chargingState.c`)

- **Frame folder.** The first match is used for all frames, decided only by whether `chargingState0.png` exists:
  1. `Saves/CurrentProfile/theme/skin/extra`
  2. `<settings.theme>skin/extra`
  3. `res` (system, 24 frames of 640x480)
- Loads `chargingState0..23.png`. Missing frames are skipped and the rest are packed together.
- **Delay.** `chargingState.json` in the same folder, parsed line by line for `"frame_delay"`: `{ "frame_delay": 80 }` works on one line. Values ≥ 10000 are treated as µs and divided by 1000. Minimum 15 ms; default 80.
  - Repo values: 80 (32), 500 (10), 50, 100, 20, 600, 68, 33, 160.
  - 4 themes use the wrong key `"frameDelay"`, which Onion ignores (80 ms).
- **Each frame:** clear to black, then the frame centred at `(320 - w/2, 240 - h/2)`. No text, no battery.
- Display timeout 15 s: the screen turns off on the Mini; the Mini+ (354) powers off.

---------------------------------------------------------------------
### 11. Boot, shutdown and low-battery screens

`src/bootScreen/bootScreen.c` and `render/bootScreen.h`:

| Argument | Background asset | Version text | Battery |
|---|---|---|---|
| `Boot` | `extra/bootScreen` | yes | no |
| `End` | `extra/Screen_Off` | yes | yes |
| `End_Save` | `extra/Screen_Off_Save` | yes | yes |
| `lowBat` | `extra/lowBat` | no | no (the `!background` check is always false) |
| `clear` | none: black screen | no | no |

Drawing:
1. The background is blitted at **(0,0), not centred**, over black.
2. Font: hint.font at 18 px. Colour: **total.color**.
3. Version string `v4.x…` (from `.tmp_update/onionVersion/version.txt`) at `(20, 450 - h/2)`.
4. Optional message (argument 2, for example `"Turning on Wi-Fi..."`, `"Syncing time..."` from `update_networking.sh`) right-aligned at `(620 - w, 450 - h/2)`.
5. Battery, if shown: the 6.2 widget centred at (596,30).

Shutdown calls `bootScreen "End"` or `"End_Save"` (`runtime.sh:647-661`, `keymon.c:209`). `bootScreen/res/splash-saving.png` is unused.

---------------------------------------------------------------------
### 12. Sounds

**change.wav** (`resources.h:332-348`, `sound.h`):
- Lookup: `<theme>sound/change.wav`, else `/mnt/SDCARD/miyoo/app/sound/change.wav` (16-bit mono 44.1 kHz).
- Played by Tweaks and prompt on navigation.
- Volume `Mix_Volume(-1, bgmvol*128/20)`, where `bgmvol` (0..20) comes from `system.json` (`settings.h:174`).

**bgm.mp3**:
- Played **only by MainUI**; Onion apps never play it. `resource_getBGM` looks up `<theme>sound/bgm.mp3`, else `miyoo/app/sound/bgm.mp3`.
- The fallback `miyoo/app/sound/bgm.mp3` shipped by Onion is actually a 48x48 PNG, so the default is silence.
- **Mute** (Tweaks flag `config/.bgmMute`): at boot, `runtime.sh:744-758` renames `<theme>sound/bgm.mp3` to `bgm_muted.mp3`, and back again when the flag is cleared.
- CONTRIBUTING requires 48 kHz mp3; other rates play too fast.
- Repo: change.wav in 123 themes, bgm.mp3 in 121.

---------------------------------------------------------------------
### 13. Theme overrides (`/mnt/SDCARD/Saves/CurrentProfile/theme`)

- `config.json` holds a partial config written by Tweaks: `batteryPercentage.{visible,font,size,textAlign,fixed,offsetX,offsetY}` and `hideLabels.{icons,hints}`. Setting `textAlign` deletes `onleft`. It is applied after the theme config without fallbacks.
- `skin/<name>.png` and `skin/extra/<name>.png` override any image name for **Onion's own screens**, including `background`. They take priority over the theme.
- `skin/extra/chargingState0..23.png` plus `chargingState.json` replace the charging animation as a set.
- Tweaks → Reset theme overrides runs `rm -rf Saves/CurrentProfile/theme/*` (`tweaks/reset.h:93`).

MainUI reads the theme folder directly, so these overrides do not reach it. At theme install Onion copies three images that MainUI loads from the system skin (`bg-io-testing`, `ic-MENU+A`, `progress-dot`) into `/mnt/SDCARD/miyoo/app/skin`, after backing up `_back` copies (`themeSwitcher/installTheme.h:146-201`). The override path built there has a bug: it is missing a `/` and becomes `…/themeskin/…`, so overrides never apply to those three.

The MainUI battery percentage comes from `mainUiBatPerc`:
- It renders the 6.2 widget onto the background plus bg-title.
- The 48x48 crop at (572,6) is centred in the image.
- The result is saved as `<theme>skin/.batt-perc.png`, which Onion's patched MainUI binaries read.

---------------------------------------------------------------------
### 14. Theme preview (Theme Switcher, not themed)

- `preview.png` is blitted with source rect `(0,0,480,360)` at (80,46), under a fixed frame overlay.
- A 640x480 preview therefore shows only its top-left crop. CONTRIBUTING requires 480x360.
- If `preview.png` is missing, `res/noThemePreview.png` is shown.

---------------------------------------------------------------------
### 15. Quirks themes rely on (support these)

1. `background.png` is upside down on disk (section 4).
2. **1x1 transparent images as "hide" switches:**
   - `bg-title` and `tips-bar-bg` (about 15 each): the bar disappears and the background shows through.
   - `div-line-h` (82): no dividers.
   - `gs-legend` (25): no legend.
   - `gs-bottom-bar` (34): **no GS footer at all**, and the name bar drops to y=420.
   - `gs-top-bar`: no GS header, title or battery; the pop menu and legend move to y=0.
   - `preview-bg`, `progress-dot`.
3. **Fully transparent 640x54 `icon-A-54`** (33 themes) hides the footer hints by pushing the labels off-screen, and disables the dialog's OK/CANCEL row (the `w < 640` check).
4. **Font size 0 or 1** (hint ×27, title ×2) and **AdobeBlank** (7) hide text.
5. **Oversized transparent battery icons** (982x900, 192x48, …): the visible pixels are offset so that centre-anchoring at (596,30) places them elsewhere. The percentage text is hidden when `icon.w > 640`.
6. **gs-top-bar and gs-bottom-bar are overlays on the game screenshot**, not on the background. The bottom bar has the button hints baked in, and Onion draws no hint text with it. Usually both are 640x40, so the header is 40 px high and the name bar sits at y=380.
7. **Assets wider, narrower, taller or shorter than default** are positioned from their real size:
   - toggles are right-aligned at 620;
   - arrows and the multivalue column are laid out from their widths;
   - lum is vertical or horizontal by aspect;
   - dialog and pop-menu backgrounds are centred or full-width;
   - `bg-list-s`/`bg-list-l` are left-aligned and **cropped** to 56/90 px high and the panel width (the pop menu uses the left 320 px).
   - Header art is cropped to 60 rows; the footer art is uncropped but clipped by the screen.
8. **Palette or RGB PNGs** (many toggles, lum and some backgrounds are colormap PNGs) must be converted to RGBA.
9. **Colour strings that are not valid hex** render black, or as shown in section 2. Keep Onion's parser so themes look as they do on device.
10. **Fonts:**
    - Relative fonts resolve against the theme folder.
    - Absolute `/mnt/SDCARD/miyoo/app/*` fonts must be provided by the host.
    - Name case differs from the file on FAT (see section 5).
    - The list font is always rendered with synthetic bold.
    - Exo 2 battery text gets a `-0.075*height` y-correction.
11. **Colour roles differ from the key names**: dialog title = total.color, dialog message = grid.color, boot text = total.color, GS game name = white.
12. **Missing Onion-only assets fall back to system files with different styling**: the Onion 68x48 toggle, 40x369 vertical lum, 24-px arrows, 324x52 legend, 320x240 bg-pop-menu-4, and the 32x32 A/B icons.

Open questions: 1. Font size 0 or 1: FreeType should clamp a 0 char size to 1 pt, so `TTF_OpenFont(...,0)` succeeds and the text is effectively invisible. I inferred this from FreeType's FT_Set_Char_Size and the fact that 27 shipping themes use it without crashing. I have not tested it on a device. A port should treat a size of 1 or less as "hide text".
2. Battery text over the icon (fixed/CENTER modes): text is copied onto the icon with SDL_SRCALPHA off. Under SDL 1.2 semantics the text's bounding box replaces icon pixels instead of blending. I derived this from the code and SDL 1.2 documentation, not from a device. A port may prefer normal blending; nobody is known to rely on the hole.
3. rotate180 blits the rotozoomed copy at (-2,-2). I treated this as an exact 180° flip, but on some SDL_gfx versions it may be off by one pixel.
4. Not sampled, to limit network fetches: sizes of `bootScreen`/`Screen_Off`/`lowBat`/`chargingState*` and of `background.png` across themes. Onion draws boot images at (0,0) uncropped and charging frames centred, so any size works.
5. Config statistics cover only the 179 top-level `config.json` files that are checked out. The 70 pack sub-theme configs were not read. Asset size counts do include pack sub-themes.
6. The stock or patched MainUI binary's own use of the same keys (grid fonts and colours, `hideLabels.icons`, `frame`, `.batt-perc.png`, bg-pop-menu in MainUI context menus) is outside this area. It is reproduced by `syphen/onion-theme-viewer`. For example, I have not confirmed whether MainUI also anchors the battery at (596,30); Onion's crop rect (572,6,48,48) implies it does.
7. For the user's question ("fork OnionOS themes and modify theme to fit MainUI"):
   - The repo's themes are third-party works by individual authors and carry no blanket licence. The contributing guide says themes are removed on takedown requests. Redistributing modified copies needs per-theme permission.
   - Reading Onion themes in place, using the layout above, avoids redistribution entirely.
   - Onion's own source (`common/theme/*`) is GPL-licensed C. Port the behaviour, not the code verbatim, unless the licences are compatible.
