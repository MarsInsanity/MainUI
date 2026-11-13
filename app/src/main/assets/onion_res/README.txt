OnionOS's own files (https://github.com/OnionUI/Onion), GPL-3.0 like OnionOS and MainUI:

- toggle-on.png, toggle-off.png: static/build/.tmp_update/res
- arrowLeft.png, arrowRight.png, gs-legend.png, lum0.png..lum10.png: src/gameSwitcher/res
  (installed to .tmp_update/res)
- config.json: static/build/miyoo/app/config.json, the settings of OnionOS's default theme
  (Silky, with OnionOS's own keys such as batteryPercentage), so written for OnionOS. Miyoo's
  stock skin and sounds next to it in that folder (skin/, sound/change.wav) are not bundled.

As on a Miyoo, an OnionOS theme without skin/extra/<name>.png gets the picture of that name here,
and a theme folder without config.json gets this config.json.
