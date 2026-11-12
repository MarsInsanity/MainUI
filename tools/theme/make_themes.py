#!/usr/bin/env python3
"""Build the MainUI themes in tools/theme/themes/ (one module per theme, see theme_kit.py).

    python3 tools/theme/make_themes.py [ID ...] [--out DIR] [--mock DIR]

ID is a module name in tools/theme/themes/ (all of them by default). Each theme is written to
DIR/<theme name> (default app/src/main/assets/themes, where MainUI bundles them). --mock DIR
also writes DIR/<id>-mock.png: the home screen, Games grid, a list and a pop-up as MainUI
lays them out. Prints any problem theme_kit.check() finds. Requires Pillow.
"""
import argparse
import importlib
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import mock_screens  # noqa: E402
import theme_kit  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="*")
    ap.add_argument("--out", default=str(theme_kit.ASSETS / "themes"))
    ap.add_argument("--mock")
    a = ap.parse_args()
    ids = a.ids or sorted(p.stem for p in (HERE / "themes").glob("*.py") if not p.stem.startswith("_"))
    bad = 0
    for i in ids:
        mod = importlib.import_module(f"themes.{i}")
        st = mod.Theme()
        out = Path(a.out) / st.name
        theme_kit.build(st, out)
        probs = theme_kit.check(out)
        size = sum(p.stat().st_size for p in out.rglob("*") if p.is_file()) // 1024
        print(f"{i}: {out} ({size} KB){'' if not probs else ' PROBLEMS: ' + '; '.join(probs)}")
        bad += bool(probs)
        if a.mock:
            Path(a.mock).mkdir(parents=True, exist_ok=True)
            mock_screens.sheet(out).convert("RGB").save(Path(a.mock) / f"{i}-mock.png")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
