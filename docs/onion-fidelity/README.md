# OnionOS theme fidelity

Where MainUI draws an OnionOS theme differently from a Miyoo Mini running OnionOS, and what
to do about each. The findings came from reading OnionOS's source, disassembling the stock
Miyoo MainUI binary that OnionOS ships, and fitting theme assets against real device
screenshots (the themes' `preview.png`). [reference.md](reference.md) is that device reference:
positions, sizes, draw order and fallbacks, screen by screen.

Every finding was checked by a second reviewer; *Verifier's corrections* are its changes to
the proposed fix. A finding names MainUI code as it was at 0.1.119, so look the lines up again.

Sources: [OnionUI/Onion](https://github.com/OnionUI/Onion) at 07505ea5,
[OnionUI/Themes](https://github.com/OnionUI/Themes) at b0119835 and
[syphen/onion-theme-viewer](https://github.com/syphen/onion-theme-viewer) at ac25de3f
(approximate; see reference.md).

A Retroid Pocket 2+ has the Miyoo's 640x480 screen, so it shows these themes at the size
they were drawn for.

## Status

| Lane | Scope | State |
|---|---|---|
| A | theme loading, fallbacks, colours, fonts | done and reviewed (48d8615, d4fcc8a) |
| B | main menu, header, footer, battery, background, Games grid | done (5f79e05, 123de35), not reviewed yet |
| C | lists, pop-ups, dialogs, info panels, settings rows, keyboard, Theme Downloader list | open |
| D | Game Switcher, in-game menu, Theme Switcher | open; uses lane C's helpers, so after C |

Files per lane: A `theme/*`, `store/ThemeStore.kt`, `core/Settings.kt`, assets; B `ui/Gfx.kt`
(background, bars, battery, tiles, dots, hints), `ui/scenes/HomeScenes.kt`; C `ui/Gfx.kt` (list,
selection, popPanel, dialog, infoPanel, arrows, toggles, keyboard), `ui/Widgets.kt` and the list
scenes; D `ui/scenes/SwitcherScenes.kt`, `ui/scenes/InGameMenuScene.kt`.

Keep MainUI's own themes looking as they do: these changes apply to OnionOS themes
(no `mainui` block in config.json).

## Done

### Lane A

- `home:M3` Colour parsing uses Color.parseColor instead of hex2sdl, so real theme values get the wrong colour
- `home:M4` Font fallbacks differ: BPreplay or condensed sans instead of Exo 2, and regular system fonts are mapped to bold
- `home:L7` A theme folder without config.json gets MainUI's own config (with a 'mainui' block, so onionLayout=false) instead of Onion's default config
- `home:L8` Pack folders ('(N-pack)') copied as-is are not listed
- `home:M1` No per-file fallback to the stock/Onion default skin: missing assets are drawn procedurally (partly: OnionOS's own fallback files are bundled; the stock Miyoo skin is not, so other missing files are still drawn by MainUI)
- `lists:L2` A missing skin file gets procedural drawing instead of the stock or Onion fallback image (partly, as home:M1)
- `lists:L14` Colour strings with trailing junk or a short form are rejected, and the key silently takes the title colour
- `onion:G1` Missing theme assets are drawn procedurally instead of falling back to Onion's stock skin and res files (partly, as home:M1)
- `onion:G7` The colour parser rejects values that the device parses, so the theme's colour is replaced by a fallback
- `onion:G10` App icons have no Onion Default-pack fallback; procedural glyphs are drawn instead
- `onion:G11` Featured-only catalog entries are dropped, including Silky by DiMo, Onion's default theme
- `onion:G14` The fallback font is BPreplay or sans-serif-condensed, where the device uses Exo-2-Bold-Italic
- `onion:G23` The default click is MainUI's own sound, and theme music is off by default (partly: the theme's music is offered once when a theme with music is applied; the click stays MainUI's, since the fallback sound is Miyoo's, not OnionOS's own)
- Nested theme packs, themes without config.json, and the upside-down background used as a preview

### Lane B

Named in the lane's commits:

- `home:H1` Tiles are stretched to 156x278 instead of drawn at natural size, centred on each slot
- `home:H2` Wrong draw order: footer, hints and dots go over the tiles, and the battery goes under them
- `home:H3` bg-title and tips-bar-bg are stretched to W x 60: 1x1 placeholders become solid bars, and tall or short bars are squashed
- `home:H4` The battery composite does not copy Onion's geometry, and a 16-px edge clamp moves wide icons and canvas-style icons
- `home:H5` The 4x2 console grid is 20 px too high and adds page dots the device does not draw
- `home:H6` Background 'cover' scaling crops the header and footer bands and enlarges the art against 1:1 tiles
- `home:M5` The main-menu footer has no B BACK hint
- `home:M6` Page dots are centred on y 396 instead of top-aligned at 390, are hidden with 4 or fewer tiles, and sit 2.5 px right
- `home:M9` Theme system icons are shrunk (label-collision rule, or fitted when larger than the cell) instead of drawn at natural size
- `home:L1` div-line-h is not drawn at (0,60) on the main menu
- `home:L2` The Wi-Fi icon is never drawn
- `home:L4` Tile labels are ellipsized at 148 px; the device does not clip them
- `home:W1` On purpose: header and footer elements sit at the screen edges while the content is centred; use the theme's bar art as a centred 640 column instead

Also assigned to lane B but not named in its commits; check the code before counting them done:

- `home:M2` hideLabels.icons hides the system names in the Games grid, but on the device it only blanks the main-menu labels
- `home:M8` Giant miyoo-topbar overlays and the logo are placed at screen coordinates, not relative to the centred 640x480 content
- `home:L3` The row views mix two cell styles and fit padded full-screen tile art as a tiny picture
- `home:L5` hideLabels.hints handling: the B icon skips the space width, and a transparent A icon of any size is treated as 'hints in art'
- `home:L6` Bilinear filtering of all skin art blurs pixel-art themes on upscaled screens
- `home:L9` Grid labels shrink to fit and marquee; the device draws grid1x4 unclipped
- `onion:G9` The battery group is clamped to 16 px from the edge and its composite width is wrong, which moves Silky's battery and breaks oversized battery canvases

## Declined

- `home:M7` Default item set and order differ from Onion: Recents, Expert and an extra Emulators tile are on by default, which breaks slot-designed art. *Declined:* the default main menu items are a product decision.
- `home:W2` On purpose: the Emulators tile has no Onion art and is drawn as a procedural card plus glyph. *Declined:* MainUI's Emulators tile has no OnionOS counterpart; it is drawn from the theme's Games tile.
- `onion:G5` START toggles full screen instead of opening the themed pop menu, which contradicts the theme's gs-legend art; hold-Y full screen is missing. *Declined:* changing START in the Game Switcher changes behaviour.
- `onion:G24` No screen writes theme overrides, although Theme.load reads them. *Declined:* needs a new settings screen.

## Open

### Lane C (do first)

- `lists:L1` bg-pop-menu-N art is stretched into a 400 x (rows*60+10) box; full-screen popup art gets squashed
- `lists:L3` Dialog text is placed for a fixed 560x280 box, not for pop-bg; wrong text colours and font; buttons outside the box
- `lists:L4` ic-favorite-mark is drawn as a shrunken left row icon, not at the right end of the row
- `lists:L5` icon-game is never drawn and icon-folder is shrunk to 40 px at x=20; label x is wrong
- `lists:L6` bg-list-s and bg-list-l are scaled to 56/90 px tall instead of cropped or centred at natural height
- `lists:L7` preview-bg is stretched around the box art instead of drawn at natural size at the right edge
- `lists:L8` Empty.png is never drawn; MainUI writes the word "Empty"
- `lists:L9` The value row ignores the arrow widths: left arrow and value are misplaced, and wide arrows overlap the value
- `lists:L10` infoPanel is a procedural rounded box that ignores pop-bg and progress-dot
- `lists:L11` Large rows have no grid.color description line; icons are capped at 64 px; built-in apps show generated glyphs
- `lists:L12` div-line-h is forced to W x 4 px, drawn at y=60 under the header, and only for existing rows
- `lists:L13` Settings rows have no icons, although almost every theme ships settings and Tweaks icons
- `lists:L16` Keyboard looks up invented asset names, and hard-coded white key labels are unreadable on light themes
- `lists:L17` One-button dialogs ignore bg-button-f
- `lists:L18` Search matches are not highlighted in list.highlightcolor
- `lists:L19` Theme names are cut to about 130 px by the preview column plus right-aligned author text
- `onion:G6` The dialog uses the wrong colour roles, a fixed 560x280 layout instead of pop-bg's real box, and footer hints instead of the in-box OK/CANCEL row
- `onion:G20` Saving, loading and applying show a procedural panel instead of Onion's pop-bg dialog with progress dots

### Lane D

- `onion:G2` bg-pop-menu-4 is stretched into a 314 x rows*56 box (or replaced by a flat panel), and row geometry differs from Onion's pop menu
- `onion:G3` Header and footer bars are drawn over the game screenshot with no theme background under them; bg-title is squashed to 60 px instead of cropped
- `onion:G4` A 1-px gs-top-bar / gs-bottom-bar is used as a 1-px bar; Onion treats it as 'no bar' (no title, no battery)
- `onion:G8` Previews are upscaled to the full screen and half covered by the current theme's header, name bar and footer
- `lists:L15` The preview is upscaled to full screen and covered by the current theme's header, footer and a 70-px name bar; the background fallback is upside down
- `onion:G15` An empty history shows a text line on the theme background instead of the theme's Empty image on black
- `onion:G16` The brightness slider ignores the minimal-view offset, the 2-s timeout and level 0, and has a procedural fallback
- `onion:G17` frame.border-left and border-right are parsed but never used
- `onion:G18` Name-bar arrows use the theme's list arrow, and the bar is lighter than Onion's
- `onion:G19` The slot preview is framed with preview-bg stretched to landscape; Onion shows the state picture 320 px wide with no frame
- `onion:G25` The legend shows on every open, and play time drops seconds

### Not in a lane

- `onion:G22` Some catalog entries have no preview or no zip

## Lane C in full

### `lists:L1` bg-pop-menu-N art is stretched into a 400 x (rows*60+10) box; full-screen popup art gets squashed

*high* · Pop-up menus (game options on SELECT, app options, category picker, Create folders)

**On the device**

Stock MainUI PopUpWindow ([BIN] §6.1): draws bg-pop-menu-N (N = item count, 4 for 4 or more) at natural size, list rect (0,0,bg.w,bg.h-8), rowH = (bg.h-8)/N, rows at x 20, dims the screen with 0xAA. Onion theme_renderPopMenu (render/pop_menu.h:13-52): size = min(rows,4), bg at natural size at (0, y), where y = header height unless bg.h > 480-y-60 (then 0); list width = bg.w; itemH = (bg.h-10)/N; footer hints SELECT/BACK. Neither scales the art, and neither draws a title bar.

**MainUI (at 0.1.119; line numbers may have moved)**

Widgets.kt:177-192 (PopMenuScene.draw): w=400, h=rows*60+10 with rows up to 6, centred, and a procedural title strip (fillRect darken(panel) plus the title font at 0.8). Gfx.kt:739-742 (popPanel) calls bmp(b,x,y,w,h), which scales bg-pop-menu-${rows.coerceIn(1,4)} to that box; without the image it falls back to fillRect(theme.panel). dim(110).

**Proposed fix**

Rewrite Gfx.popPanel as popMenu(n): RectF. Load bg-pop-menu-min(n,4) and draw it with bmp(b, cx0, y, b.width, b.height), never scaled. y = HEADER, or 0 when b.height > H-HEADER-FOOTER (Onion pop_menu.h:18-20). Return the rect. In PopMenuScene, use visible rows = min(size,4) and let the list scroll past 4. Call g.list(list, rect.top+4, rect.bottom-6, rect.left, rect.right, dividers=false, stretch=true), so rows are (bg.h-10)/N tall and bg.w wide. Remove the procedural title strip and show the title in a header drawn after dim(): header(title) over the dim, just as the footer uses covering. Use dim 170 (stock 0xAA). Draw the procedural panel only when no image exists, after the stock fallback in L2.

**Verifier's corrections**

As proposed, with these corrections. (1) Gfx.list derives rows from (bottom-top)/60. Called with top+4 and bottom-6, the stock 320x240 bg-pop-menu-4 (the fallback for ~183 themes after L2) gives 3 rows instead of 4. A 640x480 popup gives 7 and a 640x178 popup gives 2. Add an explicit rows parameter to Gfx.list and pass N=min(size,4), so effectiveItemH = (bg.h-10)/N. (2) PopMenuScene.onTap must use the returned rect and N. (3) InGameMenuScene.kt:133 also calls popPanel(4, ...), so keep that caller working when renaming. (4) For y, stock blits at (0,0) and Onion's Game Switcher at (0,HEADER) unless the art is too tall; the fix follows Onion, which is fine, but note the difference. (5) Stock squeezes all items into the art (rowH=(bg.h-8)/count) instead of scrolling. Scrolling past 4 is a MainUI choice.

**Themes affected**

Every theme: 320-wide art is stretched 1.25x horizontally. With 5-6 rows, bg-pop-menu-4 (320x250) is also stretched about 1.5x vertically. 31 themes ship opaque full-screen 640x480/640x420 bg-pop-menu-1..4 (HAZARD, Blank Space x2, Aubergine, BMO x2, GBOnion x3, Onion Turismo 2/3/4, PICO-8 x3, tenlevels themes...). These are squashed into a 400-px box, which distorts logos and hazard stripes. bg-pop-menu-4 is missing in 183 of 245 themes, which then get a flat fill (see L2).

### `lists:L3` Dialog text is placed for a fixed 560x280 box, not for pop-bg; wrong text colours and font; buttons outside the box

*high* · Confirm dialogs (DialogScene: Remove from history, Delete ROM, Game info, Download cores...)

**On the device**

Onion theme_renderDialog (render/dialog.h:88-160): dims 0x55. pop-bg centred at ((640-w)/2, (480-h)/2). Title: TITLE font in total.color, centred at popY+25. Message: TITLE font in grid.color, centred at popY+160; it shrinks to fit 450 px x 6 lines. A/B hints sit inside the box: a row centred at y = popY+popH-60 that ends 60 px left of the box's right edge, in hint font and hint.color, and only if icon-A-54.w < 640. Stock DialogWindow ([BIN] §6.2): title rect (popX,popY,pop.w,50) in F_TITLE and total.color; message rect (popX,popY+50,pop.w,100) in F_TITLE and grid.color; buttons inside the box at (popX+230, popY+160).

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:748-764. dim(120). pop-bg is drawn at y = (H-h)/2 - 10, 10 px too high. Title at y + 50 and message at y + 100, where y comes from the procedural 560x280 box (always 140 and 190 on 640x480, whatever the pop-bg size). Title uses theme.title in title.color. Message uses theme.list (bold list font, list.color, list size) with 5 lines max. Widgets.kt:125-128 puts the CONFIRM/CANCEL hints in the screen footer.

**Proposed fix**

In Gfx.dialog: popX = (W-w)/2 and popY = (H-h)/2 with no -10. Title: theme.title typeface and size, colour theme.total.color, centred at popY+25. Message: theme.title typeface, colour theme.grid.color, wrapped at min(450, w-60) and centred at popY+160. If it is wider than 450 or taller than 180, shrink it like Onion __get_font_size (min(450/w_b, 30/h_b)*title.size). Use the list font when title.size <= 1 so the text never disappears. Draw the A/B hints inside the box as in dialog.h:120-158, and register their tap rects in pendingHints. Drop footer(covering) or keep it only as a bar without labels. dim(85).

**Verifier's corrections**

As proposed. Note that the message centre popY+160 and the in-box hints follow Onion's dialog.h; stock centres the message at popY+100. Hint code is at dialog.h:65-104 in this checkout. Onion does not word-wrap, so wrapping at min(450, w-60) is a MainUI addition.

**Themes affected**

Every theme with pop-bg (242). Common 640x300 pop-bg: the title is 35 px lower than on the device. The 22 themes with a 640x480 pop-bg (Aubergine...) get their card art shifted 10 px and the message pushed off the card. total.color differs from title.color in 70 of 249 configs. grid.color differs from list.color in 164. Title and list fonts differ in 91.

### `lists:L4` ic-favorite-mark is drawn as a shrunken left row icon, not at the right end of the row

*high* · Game lists, Recents, Favorites, search results

**On the device**

Stock TextMenuDrawer ([BIN] §2.2 step 6): for a favourite, ic-favorite-mark is drawn at natural size at x = 640 - mark.w - P - 20, y = rowY + (60 - mark.h)/2, where P = 250 while the thumbnail preview is shown. The label is not moved. Stock 34x39 lands at (586, rowY+10).

**MainUI (at 0.1.119; line numbers may have moved)**

GameScenes.kt:43 (GameItems.icon) returns ic-favorite-mark as the row icon. Gfx.kt:688-695 scales it to max 40 px, draws it at x = 20, and moves the label to 20 + w + 17 = 71. In Favorites every row gets it (EntryListScene uses GameItems.item, GameScenes.kt:340), so labels jump between 20 and 71 inside the same list.

**Proposed fix**

Remove the favourite branch from GameItems.icon. Add UiItem.favorite and set it in GameItems.item. In Gfx.list, before the label, draw skin("ic-favorite-mark") at natural size at (labelEnd - mark.w - 20, cy - mark.h/2), where labelEnd is the list's right edge, or the preview's left edge when a preview is shown, and subtract mark.w + 20 from end. Don't set the flag in FavoritesScene: stock TextMenu Favorites rows carry no icon.

**Verifier's corrections**

As proposed. Wording: in Favorites every row has the mark, so labels sit at 71 (77 for Android/port rows with app icons). The 20↔71 jump happens in Recents, game lists and search results.

**Themes affected**

224 themes ship a visible mark (34x39 x108, 36x40 x11, 34x42 x10, 40x40 x9...). Material x6 ship a 620x50 band with stars at x≈40 and x≈290, designed for x = 0 and x = -250; MainUI shrinks it to a 40x3 sliver.

### `lists:L5` icon-game is never drawn and icon-folder is shrunk to 40 px at x=20; label x is wrong

*medium* · A system's game list (GameListScene)

**On the device**

Stock game list ([BIN] §2.2 step 4): game rows get skin/icon-game and folder rows get skin/icon-folder. The icon is drawn at natural size at (5, rowY + (60-h)/2), and the label starts at x = icon.w + 20. So x = 21 for the stock 1x60 spacer and x = 80 for 60x60 art. Recents and Favorites have no icon (x = 20).

**MainUI (at 0.1.119; line numbers may have moved)**

GameScenes.kt:38-45: games get no icon (null), so the label is at x = 20. Folders get icon-folder, else a glyph. Gfx.kt:690-694 caps small-list icons at 40 px (maxIcon), draws them at x = 20, and puts the label at 20 + w + 17.

**Proposed fix**

In GameListScene items, set icon = skin("icon-game") for games and skin("icon-folder") for folders. Mark both as 'row art' (e.g. UiItem.rowIcon = true). In Gfx.list, draw row art at natural size at x = left + 5, y = cy - h/2, and set the label x to left + icon.w + 20. Keep the 40-px cap only for Android app icons. Use the stock fallback from L2 when icon-folder is missing.

**Verifier's corrections**

As proposed.

**Themes affected**

104 themes have visible icon-game art (60x60 x73, 44x44 x16, 50x30 x6...): 2021/2022 Stock by Miyoo, 3DS mini, A Link to the Past, BMO by OjninJo, Cards... 241 ship icon-folder (60x60 x155, which MainUI draws at 40x40). vintage_aesthe's 640x60 folder row overlay shrinks to a sliver.

### `lists:L6` bg-list-s and bg-list-l are scaled to 56/90 px tall instead of cropped or centred at natural height

*medium* · Every list (selected row), popups

**On the device**

Stock ([BIN] §2.2 step 3): selected bg y = rowY + (rowH - h)/2 if shorter than the row, else rowY with src cropped to rowH (rowY = 62 + 60r; 640x56 lands at 64 + 60r). Apps/large ([BIN] §2.4): bg-list-l cropped to 90, top-aligned when taller, centred when shorter. Onion list.h:63 and 117: cropped to 640x56 / 640x90, never scaled.

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:616-633 (selection) draws the whole bitmap into (x, y, w, h) with h = bgH = 56 or 90 (Gfx.kt:647, 684). Every height is scaled vertically, and the 3-slice also scales vertically.

**Proposed fix**

In Gfx.selection, use srcH = min(b.height, rowH) (60 small, 90 large). For dst: if b.height < rowH, y = rowTop + (rowH - b.height)/2; otherwise y = rowTop with the source cropped to the top rowH rows. dst height = srcH. Keep the horizontal 3-slice for narrower or wider rows. Here rowTop = T + 2 for the stock game-list rule; 640x56 still lands at T + 4.

**Verifier's corrections**

As proposed. Stock Settings [BIN] §3 centres bg-list-s without cropping, so taller art spills; the crop rule fits the stock game lists.

**Themes affected**

bg-list-s is not 56 px tall in 33 themes: 640x60 x11 squashed, 640x47 x10 stretched 19%, vintage_aesthe 640x30 stretched 1.87x, plus 54/55/57/59. bg-list-l is not 90 px tall in 99: 640x91 x60, 640x94 x23 squashed; 640x86/87 x10 stretched; PSX Theme's 640x56 stretched 1.6x.

### `lists:L7` preview-bg is stretched around the box art instead of drawn at natural size at the right edge

*medium* · Game lists with box art, Theme Downloader, App detail

**On the device**

Stock ([BIN] §2.3): preview-bg at natural size at (640 - pbg.w, 60). The thumbnail is scaled down only when wider than 250 and drawn at x = (640 - pbg.w) + (250 - w)/2, y = 60 + (360 - h)/2. It is drawn after the list, over the rows. Onion list.h:186-216 does the same (centre y 240).

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:660-672: art is scaled to fit 250x340 (upscaled as well). px = right - pw - 14. preview-bg is drawn with bmp(pbg, px-6, py-6, pw+12, ph+12), so the 250x360 frame is stretched to the art's aspect (for example 262x200 for landscape art).

**Proposed fix**

Draw pbg at natural size at (right - pbg.w, top). Fit the art to 250 wide (keep the 340 height cap; upscaling small art is an acceptable Android choice) and centre it at x = right - pbg.w + (250 - w)/2, y = (top+bottom)/2 - h/2. Set labelEnd = right - pbg.w, and stop the selection bar and dividers there. With no pbg, use right - 250 - 6. On wide canvases this keeps the panel against the right edge.

**Verifier's corrections**

As proposed, except that with no preview-bg Onion and stock use right-250 (x=390), not right-250-6. After L2 a stock 256x364 panel is always available.

**Themes affected**

116 themes have a visible preview-bg (250x360 x83, 256x364 x23, 256x360 x7...): Animiyoo, BMO, BRCKD, Bayonetta, Blueprint family, Cards, Cave+... 42 have none and should show the stock 256x364 panel (L2).

### `lists:L8` Empty.png is never drawn; MainUI writes the word "Empty"

*medium* · Empty Recents, Favorites and search results

**On the device**

Stock MenuWindow ([BIN] §2.3): an empty list shows Empty.png at natural size, centred on the screen at ((640-w)/2, (480-h)/2). Onion Game Switcher (gameSwitcher.c, reference §7 step 2) also centres Empty.

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:727-729: text("Empty", theme.list, centre, alpha 160).

**Proposed fix**

In Gfx.list, when list.size == 0, draw skin("Empty") at natural size at ((W - w)/2, (H - h)/2). Fall back to the text only when there is no image even after the stock fallback.

**Verifier's corrections**

As proposed. For full-screen Empty art, note that ListScene.draw draws the header and footer after Gfx.list.

**Themes affected**

244 of 245 themes ship Empty.png (162x46 x72, 245x72 x46, 208x62 x23...). 13 are full-screen 640x480/640x360 art: Aubergine, GBOnion x3, GBMini, Super Mariyoo, Hallownest, LPM x2, CyberOnion x2, MiyooCarPlay, PIXELPUNK.

### `lists:L9` The value row ignores the arrow widths: left arrow and value are misplaced, and wide arrows overlap the value

*medium* · Settings / Device / UI settings (MULTI rows)

**On the device**

Onion list.h:148-173: right arrow at 620 - rw; left arrow at 620 - rw - 226 - lw; value clipped to 226 and centred at 620 - rw - 113; label ends at the left arrow. With 24-px arrows that gives 346/596, value centre 483.

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:710-718: right arrow right edge at end-20; left arrow at the fixed x = end - 20 - 226 - 24 = 370; value centred at end - 20 - 12 - 113 = 495 with maxW 206. The real arrow bitmap widths are never used (Gfx.arrow, Gfx.kt:585-588).

**Proposed fix**

Make arrow() return the bitmap width, or read the widths first. Place the right arrow at x = end - 20 - rw and the left arrow at x = end - 20 - rw - 226 - lw. Centre the value at end - 20 - rw - 113 with maxW 226, and set end = the left arrow's x. Also use Onion's disabled alpha of 60 (Gfx.kt:686 uses 90).

**Verifier's corrections**

As proposed.

**Themes affected**

With 24-px arrows (185 themes) the left arrow sits 24 px right of Onion's and the value 12 px right. 57 themes have bigger arrows (44x44 x16, 40x40 x9, 48x48 x8, 30x30 x7, 32x32 x5, 42x42 x4, 19x32 x3, 12x56 x2). There the value text runs under both arrows (for 44 px: value spans 392-598 against a right arrow at 576).

### `lists:L10` infoPanel is a procedural rounded box that ignores pop-bg and progress-dot

*medium* · Busy/progress overlays (Loading..., Searching..., downloads, Applying theme) and toasts

**On the device**

Onion theme_renderDialogProgress (dialog.h:162-180): the dialog (dim 0x55, pop-bg centred, message in grid.color at popY+160) plus up to 3 progress-dot images at y = 225 - h/2, x = 320 - w/2 - 32 + 32k, cycling 0-3. Stock ProgressDialog ([BIN] §6.3): pop-bg, text in F_LIST with total.color in rect (200,195,240,50), plus progress-dot. Onion infoPanel (dialog.h:184-211) is header plus message in TITLE font and list.color over the background.

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:768-789 draws fillRect(theme.panel, r12) plus strokeRect(theme.accent) with list-font text. It is used by BusyScene (Widgets.kt:156-160), GameScenes.kt:251 and 408, StoreScenes busy, SwitcherScenes.kt:314, and every toast (MainUIView.kt:143-147).

**Proposed fix**

Add Gfx.progressDialog(message) built on the L3 dialog layout: pop-bg, message, and animated progress-dot (use redrawAfter(250) for the cycle). Use it for BusyScene and the Loading/Searching/Applying overlays. Toasts are Android-only, so keep them small but themed: draw bg-pop-menu-1 (the single-row popup art, 212 themes) 3-sliced horizontally like Gfx.selection, sized to the text, with the message in list font and list.color. Draw the procedural panel only when neither image exists.

**Verifier's corrections**

As proposed for progressDialog, with one correction for toasts. bg-pop-menu-1 is tall in about 35 roots (640x480 x28, 640x420 x3, 320x480 x2, 640x300, 640x180). A horizontal 3-slice at natural height would turn a toast into a full-screen panel there. Use bg-pop-menu-1 only when it is at most ~100 px tall, otherwise fall back to pop-bg or the procedural panel.

**Themes affected**

242 themes ship pop-bg and 244 ship progress-dot (10x10 x149, 8x8 x52), so every busy screen and toast is unthemed today. Skip progress-dot images of 2 px or less (7 are 1x1 placeholders).

### `lists:L11` Large rows have no grid.color description line; icons are capped at 64 px; built-in apps show generated glyphs

*medium* · Apps, Hidden apps, App Store, Emulators (large lists)

**On the device**

Stock Apps ([BIN] §2.4): rowH 90; icon at x=20, natural size if ≤ 90 (74x74 lands at rowY+8); name top-left at (111, rowY+20); description at (111, rowY+45) in F_LIST and grid.color. Onion large list (list.h:69, 179-183): label centre T+37, description centre T+62 in list font and grid.color, label x = 20 + icon.w + 17. Onion apply_icons: apps without theme art use Icons/Default/app/<name>.png (74x74).

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:690 caps icons at 64 px, so the label sits at 101. Gfx.kt:685 and 725 centre a single label at T+45. Sub-text is right-aligned in list.color at alpha 200 and 0.8 size (Gfx.kt:719-723). AppsScenes.kt:155 uses theme.appIcon(name) ?: Glyphs.get(...); Theme.appIcon (Theme.kt:316) has no Onion fallback.

**Proposed fix**

Add UiItem.description, or use sub as the description in large lists. In Gfx.list with large=true: label centred at T+37, description centred at T+62 in theme.list typeface with theme.grid.color, icons at natural size up to 90 px (Android app icons scaled to 74). Bundle onion/static/build/Icons/Default/app/*.png (25 names: themes, gameswitcher, search, tweaks, retroarch, pacman, gallery, activity...) as assets/onion_icons/app/. Make Theme.appIcon fall back to it like onionDefaultIcon before Glyphs. Give the AppsScene built-ins real descriptions instead of sub = "MainUI".

**Verifier's corrections**

As proposed. Theme Downloader's icon name 'download' has no Onion default, so it keeps a glyph.

**Themes affected**

Every theme for the layout. 154 of 245 themes have no icons/app/, and their built-in apps become accent-coloured glyph squares instead of Onion's default icons. The 91 that do ship app icons (mostly 74x74) see them shrunk to 64.

### `lists:L12` div-line-h is forced to W x 4 px, drawn at y=60 under the header, and only for existing rows

*low* · Game lists / settings (dividers)

**On the device**

Stock game lists ([BIN] §2.2 step 1): the whole div-line-h at natural size at (0, 60 + (i+1)*60) for i = 0..rows-2, so y = 120..360, drawn even when the list is shorter. Stock settings ([BIN] §3): under each existing row, y = (r+2)*60. Neither draws a line at y=60.

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:674-681: bmp(div, left, y, width, 4f) at the top of every visible row (y = 60, 120, ...) and only for rows that have items. The image is stretched to the full width and 4 px tall.

**Proposed fix**

Draw the divider at natural height. Stretch only the width, and only when b.width == 640, for wide canvases. For stock-screen lists (GameListScene, Recents, Favorites, search), draw it at top + rowH*(i+1) for i in 0 until rows-1, whatever the item count. Skip images of 2 px or less in both directions (placeholders).

**Verifier's corrections**

As proposed.

**Themes affected**

48 themes have visible dividers (640x4 x42). Stardust Crusaders ships a 1x1 opaque black pixel, which MainUI turns into a 4-px black line across every row. Fake Pocket (640x1) and Onion Turismo 3 (640x2) are drawn 4 px thick. The other ~189 themes have transparent dividers and are unaffected.

### `lists:L13` Settings rows have no icons, although almost every theme ships settings and Tweaks icons

*medium* · Settings root, Device, User interface (Android-only settings screens)

**On the device**

Stock SettingMenu ([BIN] §3): an icon per row (icon-brightness-48, sound-icon, icon-setting-wifi, icon-theme, icon-device-info-48, icon-key-setting-48, color, fixit, icon-factory-reset-48...) at (20, rowY + (60-h)/2), natural size, label at x = 80. Onion Tweaks root (reference §8): LIST_LARGE with icons/app/tweaks_system|network|menu_button|user_interface|advanced|tools (fallback Onion src/tweaks/res) and description lines in grid.color.

**MainUI (at 0.1.119; line numbers may have moved)**

SettingsScenes.kt:36-55 (SettingsScene.build): a small list with right-aligned sub-text and no icon. DeviceScene.kt:33-91 and UiSettingsScene (SettingsScenes.kt:61-92): no icons.

**Proposed fix**

These screens are Android-only, so keep their content but use the theme's art. Make SettingsScene a large list (as Tweaks): Device→app/tweaks_system, Theme→app/themes, User interface→app/tweaks_user_interface, Controls→app/tweaks_menu_button, Library & storage→app/tweaks_tools, Emulators & cores→app/retroarch, Scraper→app/gallery, Process manager→app/activity, Permissions/Android settings→app/tweaks_advanced, Updates→app/otaupdate. Fall back to bundled Onion tweaks res, and turn sub into a grid.color description line (L11). In DeviceScene, give rows the stock icons: Brightness→icon-brightness-48, Volume→sound-icon, Wi-Fi→icon-setting-wifi, Screen timeout→icon-device-info-48, Bluetooth/Battery saver→icon-device-info-48. Draw them at natural size at x = 20 with the label at x = 80 (stock-skin fallback from L2).

**Verifier's corrections**

As proposed.

**Themes affected**

icon-brightness-48 242, icon-device-info-48 242, icon-language-48 242, sound-icon 241, color 236, icon-key-setting-48 236, icon-TF 194, fixit 172, icon-setting-wifi 111, icon-theme 41 (stock fallback otherwise); icons/app/tweaks_* 88, app/themes|retroarch|gallery|activity 91.

### `lists:L16` Keyboard looks up invented asset names, and hard-coded white key labels are unreadable on light themes

*low* · On-screen keyboard (search, repository URL, scraper credentials)

**On the device**

Stock keyboard ([BIN] §7): input (592x52), bg-btn-01-f/n (52x52), bg-btn-02-f/n (112x52), icon-shift/-active, icon-delete, icon-space..., looked up theme first, then stock. No theme in the repo ships them, so a Miyoo shows the stock art.

**MainUI (at 0.1.119; line numbers may have moved)**

Widgets.kt:265 uses skin("bg-input") and Widgets.kt:291 uses skin("btn-bg-f"/"btn-bg-n"). No theme ships these names, so drawing is always procedural: accent for the selected key, 35% white for the others (Widgets.kt:293), labels always Color.WHITE (Widgets.kt:296-297).

**Proposed fix**

Look up the stock names: input, bg-btn-01-f/n for 1-wide keys, bg-btn-02-f/n for the specials. Draw them 9-sliced to the key size, with the stock art bundled through L2. Draw key labels in theme.list.color. For procedural keys, choose the key tint so it contrasts with theme.list.color (dark keys for light text, light keys for dark text).

**Verifier's corrections**

As proposed: switch to input and bg-btn-01/02-f/n, use L2's stock fallback, and draw labels in list.color with contrast-aware procedural key tints. Without the rename, L2's fallback alone would draw the stock bg-input/btn-bg art stretched into the wrong boxes.

**Themes affected**

Light themes: Blank Space (background 246,241,241, list #424242), Milk White and others get white text on near-white keys. All 245 themes lack keyboard art, so the stock art would be the faithful fallback.

### `lists:L17` One-button dialogs ignore bg-button-f

*low* · OK-only dialogs (Game info, errors, update results)

**On the device**

Stock NotifyWindow ([BIN] §6.3): pop-bg centred. Message rect (200,195,240,90), raised by btn.h/4 when bg-button-f exists, in F_TITLE and grid.color. bg-button-f at ((640-w)/2, popY + pop.h - 12 - btn.h) with the label centred on it in F_TITLE and list.color.

**MainUI (at 0.1.119; line numbers may have moved)**

Widgets.kt:125-128 / Gfx.kt:748-764: an OK-only dialog only gets an "OK" footer hint.

**Proposed fix**

In DialogScene, when cancelLabel == null, draw skin("bg-button-f") at natural size at ((W-w)/2, popY + popH - 12 - h) with confirmLabel centred on it (title font, list.color). Treat taps on it as A. Skip images of 2 px or less.

**Verifier's corrections**

As proposed.

**Themes affected**

232 themes ship bg-button-f (232x68 x169). 13 ship 1x1 placeholders, and 7 of those are opaque (Classic TV, NanoSwitch x3, Spider-Miyoo, SOES x2), so the image must never be stretched.

### `lists:L18` Search matches are not highlighted in list.highlightcolor

*low* · Search results

**On the device**

Stock TextMenuDrawer ([BIN] §2.2 step 5, §1.4): the part of a name that matches the search filter is drawn in list.highlightcolor, default #FFFF00, since no theme sets it.

**MainUI (at 0.1.119; line numbers may have moved)**

SearchResultsScene (GameScenes.kt:381-430) draws plain labels through Gfx.list:725. Theme.kt does not parse list.highlightcolor.

**Proposed fix**

Parse list.highlightcolor in Theme.load (default #FFFF00). Add UiItem.highlight: IntRange set by SearchResultsScene. In Gfx.text or Gfx.list, draw the label in up to 3 runs, with the matched run in that colour (not while it marquees).

**Verifier's corrections**

As proposed.

**Themes affected**

All themes (yellow by default).

### `lists:L19` Theme names are cut to about 130 px by the preview column plus right-aligned author text

*low* · Theme Downloader list (StoreScenes.kt, MainUI-only)

**On the device**

No Onion equivalent (Android-only screen). Onion's large list puts secondary text on a second line in grid.color (list.h:179-183).

**MainUI (at 0.1.119; line numbers may have moved)**

StoreScenes.kt:46-56 uses a small list with sub = "installed/featured Author" and a preview. Gfx.kt:719-723 takes up to 220 px for the sub, so with the preview's labelEnd ≈ 366 the label is left about 130-150 px (ellipsized).

**Proposed fix**

Make ThemeStoreScene a large list (bg-list-l) with the author/status as the description line in grid.color (L11), or omit the right-aligned sub when a preview is shown.

**Verifier's corrections**

As proposed.

**Themes affected**

All themes (the downloader shows the current theme's list art).

### `onion:G6` The dialog uses the wrong colour roles, a fixed 560x280 layout instead of pop-bg's real box, and footer hints instead of the in-box OK/CANCEL row

*medium* · Onion dialog (Game Switcher 'Remove from history', in-game confirmations)

> Same dialog as lists:L3.

**On the device**

dialog.h:35-104: full-screen black at alpha 0x55. pop-bg at ((640-w)/2, (480-h)/2): 640x300 lands at y 90, the 640x330 fallback at 75. Title: TITLE font in total.color, centred at y = popY+25. Message: TITLE font in grid.color, centred multi-line at y = popY+160; if wider than 450 px or taller than 180, re-rendered at a smaller size. Hints (unless icon-A-54.w >= 640): [A]5[OK]30[B]5[CANCEL] in the hint font and hint.color, centred at y = popY+popH-60, row ending at popX+popW-60.

**MainUI (at 0.1.119; line numbers may have moved)**

Gfx.kt:748-764: dim 120. pop-bg sits 10 px above centre. The title is at a fixed y = (H-280)/2-10+50 = 140 in title.color, whatever the pop-bg size. The message starts at y 190 in the list font and list.color. Widgets.kt:125-128 DialogScene draws CONFIRM/CANCEL in the footer bar instead of inside the box.

**Proposed fix**

In Gfx.dialog, when pop-bg exists: popX = (W-w)/2 and popY = (H-h)/2 (no -10). Title centred at popY+25 with theme.total.color. Message drawn with paragraph() in theme.title's typeface and theme.grid.color, centred on popY+160, shrinking the size until it fits 450 px x 6 lines. Hint row drawn inside the box as above (labels "OK"/"CANCEL"; skip when theme.hintsInArt or icon-A-54 is >= 640 wide). DialogScene then omits the footer hints and keeps only the dim.

**Verifier's corrections**

As proposed (box at (W-w)/2, (H-h)/2; title on popY+25 in total.color; message in the title typeface and grid.color on popY+160, shrunk to fit 450 px x 180 px; OK/CANCEL row inside the box), with one correction. Onion hides the in-box row only when icon-A-54 is 640 px wide or more (dialog.h:66). It does not check hideLabels, so key the row off icon-A-54's width alone, not theme.hintsInArt. Use dim alpha 85.

**Themes affected**

grid.color differs from list.color in 114 of 179 configs; total.color differs from title.color in 41. pop-bg is 640x480 in 22 themes and 640x360 in 26, so the fixed 140-px title misses the art.

### `onion:G20` Saving, loading and applying show a procedural panel instead of Onion's pop-bg dialog with progress dots

*low* · In-game menu busy state; Theme Switcher 'Applying theme'

> Lane C adds the progress dialog helper; lane D's in-game menu uses it.

**On the device**

gs_popMenu.h:281-297: Save shows theme_renderDialogProgress("Saving"), i.e. the pop-bg dialog plus up to 3 progress-dot images at (320-w/2-32+32k, 225-h/2), cycling 0..3, then theme_renderDialog("Saving", "State saved"/"Save failed") for 1 s. LOADING uses theme_renderInfoPanel(use_dialog=true), i.e. pop-bg with the message at popY+160 (dialog.h:106-155).

**MainUI (at 0.1.119; line numbers may have moved)**

InGameMenuScene.kt:174 and SwitcherScenes.kt:314 call g.infoPanel, which (Gfx.kt:768-789) draws a rounded theme.panel rect with an accent stroke and list-font text.

**Proposed fix**

Add Gfx.progressDialog(title, msg) built on the G6 dialog, drawing progress-dot × ((now/300) % 4) at cy0+225. Use it for the InGameMenu busy labels and ThemeSwitcher apply. Keep infoPanel for short toasts.

**Verifier's corrections**

Add Gfx.progressDialog(title, msg) on top of the G6 dialog, with progress-dot drawn ((now/300)%4) times at cy0+225 starting at x 320-w/2-32 every 32 px. Use it for the in-game menu's Saving/Loading busy states, then show the result dialog for 1 s. For ThemeSwitcher apply, Onion parity is a black screen with centred white 'Installing...' text, not the dialog.

**Themes affected**

pop-bg in 242+ themes, progress-dot in 244 (10x10 in 149).

## Lane D in full

### `onion:G2` bg-pop-menu-4 is stretched into a 314 x rows*56 box (or replaced by a flat panel), and row geometry differs from Onion's pop menu

*high* · In-game menu (InGameMenuScene), modelled on Onion's Game Switcher START pop menu

**On the device**

render/pop_menu.h:81-121 with list.h:52-127 (stretch_y). bg = bg-pop-menu-N with N = min(items,4), blitted unscaled at (0,yPos); yPos = 0 when bg.h > 480-yPos-60. Footer bar (background slice + tips-bar-bg) and SELECT/BACK hints are drawn before the bg, so 640x480 art covers them. Panel = bg.w x bg.h; listH = bg.h-10; itemH = listH/N. Row y = yPos+4+(listH-N*itemH)/2+k*itemH+(itemH-56)/2. Selected row: bg-list-s cropped to (0,0,bg.w,56) at (0,y), not scaled. Label at x=20, centred on y+27, LIST font (bold), list.color. No dividers.

**MainUI (at 0.1.119; line numbers may have moved)**

InGameMenuScene.kt:128-139: rowH 56, panelW 300, top 0. g.popPanel(4, 0, 0, 314, rows*56) -> Gfx.kt:739-742 draws bg-pop-menu-4 scaled to that box: 320x250 becomes 314x336 for 6 rows, and 640x480 full-screen art is squashed to 314 px wide. It fills theme.panel when the theme has no -4, even if it ships -1..-3. Rows sit at y=56*i with no 4-px inset. g.selection (Gfx.kt:616-633) 3-slices bg-list-s and scales it to 56 tall, so 640x60 and 640x47 bars are distorted. Text at x=22. Footer at :172 has no covering, so a transparent tips-bar-bg shows the game.

**Proposed fix**

Add Gfx.onionPopMenu(items, active, yPos) and use it here and in the GS START menu (G5). Choose bg-pop-menu-min(n,4); if missing, the next lower N the theme ships; then the stock file (G1). If bg.w >= 600 (full-screen art), blit it unscaled at (0,0) and use Onion's itemH = (bg.h-10)/n. Otherwise keep its natural width bg.w. When n*60+10 > bg.h (6-7 in-game items), 3-slice it vertically: top and bottom 16 px kept, middle stretched to n*60+10. Rows: y = 4 + k*itemH + (itemH-56)/2. Selection: bg-list-s with src (0,0,bg.w,min(h,56)), top-aligned, unscaled. Label at x 20, centred on y+27. Draw g.footer("SELECT","BACK", covering=true) before the panel.

**Verifier's corrections**

As proposed (Gfx.onionPopMenu shared with the GS START menu, natural width, unscaled bg-list-s crop, label at x 20 centred on y+27, footer with covering drawn before the panel, vertical 3-slice when 6-7 rows don't fit). One correction to the choice of image: on the device a theme without bg-pop-menu-4 gets the stock Silky bg-pop-menu-4 (320x240) through the load.h fallback, not its own -3. To match Onion, use bg-pop-menu-min(n,4) from the theme, then the stock file (G1). Falling back to the theme's lower N first is a deliberate deviation and should be labelled as one.

**Themes affected**

bg-pop-menu-4 is shipped by only 62/245 (41 at 320x250, 9 at 640x480). About 27 themes use 640x480 for bg-pop-menu-1..3. bg-list-s is 640x60 in 11 themes and 640x47 in 10.

### `onion:G3` Header and footer bars are drawn over the game screenshot with no theme background under them; bg-title is squashed to 60 px instead of cropped

*medium* · Game Switcher (normal view, no gs-top-bar / gs-bottom-bar)

**On the device**

header.h:9-14: blit the background crop (0,0,640,60), then bg-title cropped to 640x60. footer.h:73-79: blit the background crop (0,420,640,60), then the whole tips-bar-bg at (0,420), clipped by the screen. Used from gs_render.h:187-189 and :201-203. A transparent bar therefore shows the theme's own (rotated) background, never the game.

**MainUI (at 0.1.119; line numbers may have moved)**

SwitcherScenes.kt:124 g.header(title) and :126 g.footer("RESUME","BACK",…) without covering=true. Gfx.kt:323 and :439 stretch bg-title and tips-bar-bg to W x 60 directly over the bmpCover screenshot. Transparent bars show the game, and taller art is squashed: 640x74/64/62 bg-title, 640x63 tips-bar-bg.

**Proposed fix**

In GameSwitcherScene.draw (view 0, no topBar): c.save(); c.clipRect(0,0,W,60); g.background(); c.restore(); then g.header(title). Call g.footer(..., covering = true). In Gfx.header, draw bg-title with src Rect(0,0,w,min(h,60)) to a 60-px-high destination (crop, not scale). In Gfx.footer, draw tips-bar-bg at its natural height from y = H-60.

**Verifier's corrections**

As proposed (background clipped under the header in view 0; footer(..., covering=true)), with one correction. Do not stretch either bar to the destination box: draw it at its natural size (bg-title cropped to its top 60 px, tips-bar-bg from y=H-60 at its natural height), scaled only horizontally by W/640 for 640-wide art. The fix as worded (src (0,0,w,min(h,60)) into a 60-px-high box) still blows a 1x1 image up into a full bar. Three themes have opaque 1x1 bars: bg-title in Nokiyoo (38,75,128) and Wiiyoo (white), tips-bar-bg in Pocket Gank (black). Onion draws one pixel there; MainUI draws a solid 640x60 bar, on every screen.

**Themes affected**

bg-title has transparency in 53 skins (17 are 1x1); tips-bar-bg has transparency in 36 (15 are 1x1). bg-title is 640x74 in Fake DMG and Fake Pocket, 640x64 in 2 themes and 640x62 in 1.

### `onion:G4` A 1-px gs-top-bar / gs-bottom-bar is used as a 1-px bar; Onion treats it as 'no bar' (no title, no battery)

*medium* · Game Switcher

**On the device**

gs_render.h:62-66 getHeightOrDefault: header_height/footer_height = h > 1 ? h : 0. gs_render.h:172-186: with a custom header and header_height 0, nothing is drawn (no title, no battery). The name bar is at y = 480-60-footer_height (420 with a 1x1 bottom bar). The legend and horizontal lum sit at y = header_height (0).

**MainUI (at 0.1.119; line numbers may have moved)**

SwitcherScenes.kt:107-108: headerH = topBar.height (1) and footerH = bottomBar.height (1). :120-123 still stretches the 1x1 bar and draws the title and battery centred at y=0.5, half off-screen. :132 puts the name bar at H-61. The legend and lum sit at y=1.

**Proposed fix**

Compute the heights as Onion does: headerH = if (view==0) topBar?.let { if (it.height > 1) it.height.toFloat() else 0f } ?: 60f else 0f, and the same for footerH. When topBar != null && headerH == 0, skip the bar, title and battery. When bottomBar != null && footerH == 0, skip the bar and draw no hints.

**Verifier's corrections**

As proposed: headerH/footerH = if (h > 1) h else 0 when the custom bar exists, else 60. Skip the bar, title and battery (or the bar and hints) when a custom bar exists with height 0.

**Themes affected**

gs-top-bar is 1x1 in 2 themes (a visibly broken title). gs-bottom-bar is 1x1 in 34 themes (1-px offset).

### `onion:G8` Previews are upscaled to the full screen and half covered by the current theme's header, name bar and footer

*medium* · Theme Switcher

> Same as lists:L15.

**On the device**

themeSwitcher.c:175-180, 329-333: preview.png is blitted 1:1 (src 0,0,480,360) at (80,46), with only the frame and arrows (24,210)/(588,210) around it; the name is drawn above it at (77,7). CONTRIBUTING requires a 480x360 preview, which is a 0.75 scale of a 640x480 MENU screenshot.

**MainUI (at 0.1.119; line numbers may have moved)**

SwitcherScenes.kt:298 bmpFit scales the preview to W x H (1.33x). Then :303-309 draw a 70-px name bar over y 350-420, and :311-312 draw the current theme's header (0-60) and footer (420-480). Together these hide the previewed theme's top bar, logo and battery, its page dots (preview y 292 -> 390) and its footer hints.

**Proposed fix**

Draw the preview unscaled at (cx0+80, cy0+60). 480x360 exactly fills the space between the 60-px header and the footer at 420. Put the theme name in the header title (and the author as a second, smaller line or in the footer), and the arrows (extra/arrowLeft or Onion res) in the 80-px side margins at y 240. Remove the name bar. Scale only non-480x360 previews into that box: 640x480 ones at 0.75.

**Verifier's corrections**

As proposed: draw a 480x360 preview unscaled at (cx0+80, cy0+60), between the 60-px header and the footer at 420 (Onion uses y 46 with its own frame), and scale other sizes into that box. Put the name and author in the header or footer, the arrows in the 80-px side margins at y 240, and remove the name bar.

**Themes affected**

All 241 standard 480x360 previews. 640x480 in 3 themes (A Link to the Past, Power Onions OS, StarOnion64); 482x360 in BRCKD.

### `lists:L15` The preview is upscaled to full screen and covered by the current theme's header, footer and a 70-px name bar; the background fallback is upside down

*medium* · Theme Switcher (SwitcherScenes.kt, Android/MainUI-specific)

> Same as onion:G8.

**On the device**

Onion themeSwitcher.c:175-186 and 329-356: preview.png at native size, src (0,0,480,360) at (80,46), no scaling and nothing drawn over it; arrows at (24,210)/(588,210); name above the preview at (77,7). The switcher itself is not themed. background.png is stored rotated 180° (load/background.h).

**MainUI (at 0.1.119; line numbers may have moved)**

SwitcherScenes.kt:297-313: bmpFit(prev, 0,0,W,H) upscales 480x360 to 640x480. A black name bar at H-130..H-60 (y 350..420) hides the preview's tile bottoms and page dots. header() and footer() of the current theme hide the previewed theme's own bg-title, logo, battery, tips bar and hints. ThemeManager.kt:181-191 falls back to skin/background.png without rotating it.

**Proposed fix**

Draw the preview unscaled at ((W-480)/2, HEADER + (H-HEADER-FOOTER-360)/2), which is Onion's 480x360 box at (80,60) on 640x480, and draw nothing over it. Put the theme name in the header title, and author/description as a marquee line below the preview when H > 480 (else inside the header room). Put the arrows in the side margins at y = H/2. In ThemeManager.preview, rotate the background.png fallback by 180°. The switcher may keep the current theme's header and footer because Onion's switcher is not themed.

**Verifier's corrections**

As proposed. The formula gives (80,60) on 640x480, which suits MainUI's 60-px header, but Onion's box is at (80,46), not (80,60).

**Themes affected**

Every theme preview: 241 previews are 480x360. The background fallback only applies to user themes without a preview.png.

### `onion:G15` An empty history shows a text line on the theme background instead of the theme's Empty image on black

*low* · Game Switcher (no recent games)

**On the device**

gameSwitcher.c:113-117: screen filled black, then the `Empty` skin image centred at ((640-w)/2, (480-h)/2). Header ('GameSwitcher' + battery) and footer (RESUME/BACK + 0/0) are drawn as in the normal view.

**MainUI (at 0.1.119; line numbers may have moved)**

SwitcherScenes.kt:88-93: g.background() and the list-font text 'No recently played games'.

**Proposed fix**

Fill black; if skin("Empty") exists (or the stock file, G1), draw it at ((W-w)/2, (H-h)/2); then draw the header and footer as in view 0, with footerStatus(0, 0).

**Verifier's corrections**

Fill black, draw skin('Empty') (or the stock 208x62 file, G1) centred, then, in view 0 only, the header and footer('RESUME','BACK') with footerStatus(0,0).

**Themes affected**

Empty is shipped by 244/245 themes; it is a 640x480 full-screen overlay in 7.

### `onion:G16` The brightness slider ignores the minimal-view offset, the 2-s timeout and level 0, and has a procedural fallback

*low* · Game Switcher

**On the device**

gs_render.h:226-239: a vertical lum is drawn at (0, (NORMAL ? 240 : 210) - h/2), a horizontal one at ((640-w)/2, NORMAL ? header_height : 0). Shown for 2000 ms; any other key hides it (gs_keystate.h:160-161). Levels 0..10, so lum0 is used.

**MainUI (at 0.1.119; line numbers may have moved)**

SwitcherScenes.kt:151 always puts a vertical lum at H/2 - h/2. :257 shows it for 1500 ms. :255 clamps the level to 1..10. :153-156 draws an accent-coloured bar when the theme has no lum.

**Proposed fix**

Vertical y = cy0 + (if (view == 0) 240 else 210) - h/2. Show it for 2000 ms and clear brightnessUntil on any non-UP/DOWN key. When the theme has no lum, use Onion's res lum (G1). Clamping at 1 can stay (on purpose: Android dims below 10%).

**Verifier's corrections**

As proposed: y = cy0 + (if (view==0) 240 else 210) - h/2; show for 2000 ms; clear brightnessUntil on any other key; use Onion's res lum when the theme has none. Clamping at 1 is a deliberate choice.

**Themes affected**

114 themes ship lum (vertical 40x480 in 17, 40x369 in 7, 55x244, 66x140 show the offset); the other 131 get Onion's res.

### `onion:G17` frame.border-left and border-right are parsed but never used

*low* · Game Switcher

**On the device**

gs_render.h:16-41 and :77-114: in NORMAL view the screenshot is cropped to x in [border_left, 640-border_right], leaving black strips. The name bar is x = border_left, w = 640-bl-br. Arrows are at border_left+10 and 630-border_right-w, in every view.

**MainUI (at 0.1.119; line numbers may have moved)**

Theme.kt:44-45 and :124-126 store frameLeft/frameRight, but nothing reads them (grep). SwitcherScenes.kt:99 (screenshot), :133 (bar spans 0..W), :136-137 (arrows at 10 and W-10).

**Proposed fix**

View 0: clipRect(frameLeft, 0, W-frameRight, H) around the screenshot draw. Name bar fillRect(frameLeft, y, W-frameLeft-frameRight, 60). Arrows at frameLeft+10 and W-10-frameRight in every view.

**Verifier's corrections**

As proposed: in view 0, clipRect(frameLeft, 0, W-frameRight, H) around the screenshot; name bar at frameLeft with width W-frameLeft-frameRight in view 0; arrows at frameLeft+10 and W-10-frameRight in every view.

**Themes affected**

BRCKD, System 9 and win98 (9/9); Cosy and Wireframe (0/0).

### `onion:G18` Name-bar arrows use the theme's list arrow, and the bar is lighter than Onion's

*low* · Game Switcher name bar

**On the device**

gs_render.h:68-114: arrows are LEFT_ARROW_WB/RIGHT_ARROW_WB = extra/arrowLeft/arrowRight, else Onion res (white 24x24 chevrons). The bar is the 640x60 strip of transparent_bg 0xBE000000 (alpha 190, gameSwitcher.c:68-69). The name marquee moves 10 px per frame at 30 fps with 20-frame pauses at both ends.

**MainUI (at 0.1.119; line numbers may have moved)**

SwitcherScenes.kt:134 and Gfx.kt:587 fall back to icon-left/right-arrow-24, the settings multivalue arrow (24, 44, 40, 48 or 12x56, often coloured). :133 fills with alpha 150. The marquee in Gfx.kt:241-242 runs at 40 px/s with 1.2-s pauses.

**Proposed fix**

Arrow lookup: extra/arrowLeft, then Onion res arrowLeft (G1); never icon-left-arrow-24. Fill with Color.argb(190,0,0,0). Optionally set the marquee speed to 300 px/s with 667-ms pauses for the GS name.

**Verifier's corrections**

As proposed: extra/arrowLeft, then Onion's res arrow (never icon-left-arrow-24); fill argb(190,0,0,0); GS-name marquee at 300 px/s with ~667 ms pauses.

**Themes affected**

243/245 have no extra/arrowLeft. icon-left-arrow-24 is not 24x24 in about 60.

### `onion:G19` The slot preview is framed with preview-bg stretched to landscape; Onion shows the state picture 320 px wide with no frame

*low* · In-game menu (Save/Load slot preview)

**On the device**

pop_menu.h:103-113 (preview_bg false, preview_width 320, preview_stretch) with list.h:186-217: state PNG scaled to 320 wide at (320+(320-w)/2, 240-h/2). While Load is selected, the screen is dimmed 74.5% from yPos down (gs_popMenu.h:435-440) and the footer counter shows slot/count (:442-444).

**MainUI (at 0.1.119; line numbers may have moved)**

InGameMenuScene.kt:157-170: preview-bg (a portrait 250x360 box-art frame) is stretched to 282x214 as a frame around a 270x202 thumb. A 40-px 'Slot N' strip and arrows are drawn inside the picture, and 'Empty' text when there is no thumb. No dim.

**Proposed fix**

While Save/Load is selected: fillRect(0,0,W,H, argb(190,0,0,0)) behind the panel, then draw the thumb scaled to 320 wide centred at (W*3/4, cy0+240) with no preview-bg. Draw icon-left/right-arrow-24 beside it and g.footerStatus(slot+1, slotCount) in place of the 'Slot N' strip.

**Verifier's corrections**

As proposed (no preview-bg; thumbnail scaled to 320 wide centred at (cx0+480, cy0+240); arrows beside it; footerStatus(slot+1, slotCount) instead of the strip), with one correction. Draw the alpha-190 dim only while Load is selected, from the panel's y down, as Onion does. Showing the thumbnail on Save is MainUI's own extra.

**Themes affected**

preview-bg is portrait in every theme that ships it (250x360 in 144, 256x364 in 33), fully transparent in 62 and 1x1 in 4.

### `onion:G25` The legend shows on every open, and play time drops seconds

*low* · Game Switcher

**On the device**

gameSwitcher.c:59-62 and :86-90: the legend shows only until its first 5-s timeout ever (flag gameSwitcher/hideLegend); SELECT shows it again. str.c:178-193 formats time as '%dh %dm', '%dm %ds' or '%ds'.

**MainUI (at 0.1.119; line numbers may have moved)**

SwitcherScenes.kt:69 shows the legend for 5 s on every onEnter. Launch.kt:198-202 formats '%dh %dm' or '%dm' only.

**Proposed fix**

Keep a 'gs_legend_seen' pref, set when the 5 s expire; SELECT still re-shows the legend. Format time as str_serializeTime does.

**Verifier's corrections**

As proposed: a persistent 'gs_legend_seen' pref set when the 5 s expire, with SELECT still showing the legend; format times like str_serializeTime.

**Themes affected**

All themes.

## Not in a lane, in full

### `onion:G22` Some catalog entries have no preview or no zip

*low* · Theme Downloader

**On the device**

Repo: PSPmini, PSPmini Blue and Scallion (2-pack) have no themes/<name>/preview.png. 'FF VIII by Inheritor' and 'Rick and Morty Mini by zooloo631' are in custom.txt with no release zip and no theme dir. release/icons/*.zip holds 9 icon packs.

**MainUI (at 0.1.119; line numbers may have moved)**

ThemeStore.kt:36 builds previews only from themes/<name>/preview.png, and :62-66 shows nothing on 404. :90-99 fails at download time for the 2 dead entries.

**Proposed fix**

On a preview 404, query api.github.com/repos/OnionUI/Themes/contents/themes/<name> and use the first subdir's preview.png. On catalog refresh, HEAD release/<name>.zip and mark or drop 404s. Optionally list release/icons/*.zip as icon packs.

**Verifier's corrections**

As proposed: on a preview 404, use the first subfolder's preview.png (from the GitHub contents API, or from the release zip's root-level variant folders); HEAD-check release/<name>.zip on catalog refresh and drop or mark 404s.

**Themes affected**

3 pack previews; 2 dead entries.
