#!/usr/bin/env python3
"""Generate app/src/main/assets/systems.json from the dArkOS wiki.

Usage:
    git clone --depth 1 https://github.com/christianhaitian/dArkOS.wiki.git /tmp/dArkOS.wiki
    python3 tools/gen_systems.py /tmp/dArkOS.wiki/dArkOS-Emulators-and-Ports-information.md \
        app/src/main/assets/systems.json

The wiki gives us the canonical dArkOS rom folder names, display names, file
extensions and the libretro cores in their default-first order. Everything that
is Android specific (core .so names, standalone Android emulators, ScreenScraper
system ids and the OnionOS theme icon names) lives in the tables below so it
can be reviewed and edited by hand.
"""
import json
import re
import sys

# dArkOS "lr-xxx" name -> RetroArch for Android core basename
# (<basename>_libretro_android.so). None = core not available on Android.
CORES = {
    'lr-81': '81', 'lr-Azahar': None,  # Azahar standalone is preferred on Android
    'lr-DoubleCherryGB': 'DoubleCherryGB',
    'lr-a5200': 'a5200', 'lr-applewin': None, 'lr-ardens': 'ardens',
    'lr-arduous': 'arduous', 'lr-atari800': 'atari800', 'lr-b2': None,
    'lr-bluemsx': 'bluemsx', 'lr-cap32': 'cap32', 'lr-coolcv': None,
    'lr-crocods': 'crocods', 'lr-dosbox': 'dosbox_svn', 'lr-dosbox_pure': 'dosbox_pure',
    'lr-doukutsu-rs': 'nxengine', 'lr-duckstation': 'swanstation', 'lr-easyrpg': 'easyrpg',
    'lr-ecwolf': 'ecwolf', 'lr-emuscv': 'emuscv', 'lr-ep128emu': 'ep128emu_core',
    'lr-fMSX': 'fmsx', 'lr-fake08': 'fake08', 'lr-fbalpha2012': 'fbalpha2012',
    'lr-fbalpha2016': None, 'lr-fbalpha2018': None, 'lr-fbneo': 'fbneo',
    'lr-fceumm': 'fceumm', 'lr-flycast': 'flycast', 'lr-flycast_xtreme': None,
    'lr-freechaf': 'freechaf', 'lr-freeintv': 'freeintv', 'lr-freej2me': 'freej2me',
    'lr-freej2me-plus': None, 'lr-fuse': 'fuse', 'lr-gambatte': 'gambatte',
    'lr-genesis_plus_gx': 'genesis_plus_gx', 'lr-genesis_plus_gx_wide': 'genesis_plus_gx_wide',
    'lr-geolith': 'geolith', 'lr-gpsp': 'gpsp', 'lr-gw': 'gw', 'lr-handy': 'handy',
    'lr-hatari': 'hatari', 'lr-lowresnx': 'lowresnx', 'lr-mame': 'mame',
    'lr-mame2003-plus': 'mame2003_plus', 'lr-mame2010': 'mame2010',
    'lr-mednafen-ngp': 'mednafen_ngp', 'lr-mednafen-pce': 'mednafen_pce',
    'lr-mednafen-pce-fast': 'mednafen_pce_fast', 'lr-mednafen-pcfx': 'mednafen_pcfx',
    'lr-mednafen-supergrafx': 'mednafen_supergrafx', 'lr-mednafen-vb': 'mednafen_vb',
    'lr-mednafen-wswan': 'mednafen_wswan', 'lr-mednafen_lynx': 'mednafen_lynx',
    'lr-mess': 'mame', 'lr-mgba': 'mgba', 'lr-minivmac': 'minivmac', 'lr-mu': 'mu',
    'lr-mupen64plus': 'mupen64plus_next_gles3', 'lr-mupen64plus_next': 'mupen64plus_next_gles3',
    'lr-nekop2': 'nekop2', 'lr-nekop2kai': 'np2kai', 'lr-neocd': 'neocd',
    'lr-nestopia': 'nestopia', 'lr-o2em': 'o2em', 'lr-onscripter': 'onscripter',
    'lr-onsyuri': None, 'lr-opera': 'opera', 'lr-parallel-n64': 'parallel_n64',
    'lr-pcsx-rearmed': 'pcsx_rearmed', 'lr-picodrive': 'picodrive', 'lr-pokemini': 'pokemini',
    'lr-potator': 'potator', 'lr-ppsspp': 'ppsspp', 'lr-prboom': 'prboom',
    'lr-prosystem': 'prosystem', 'lr-puae': 'puae', 'lr-puzzlescript': 'puzzlescript',
    'lr-px68k': 'px68k', 'lr-reicast_xtreme': None, 'lr-same_cdi': 'same_cdi',
    'lr-sameduck': 'sameduck', 'lr-scummvm': 'scummvm', 'lr-snes9x': 'snes9x',
    'lr-snes9x2002': 'snes9x2002', 'lr-snes9x2005': 'snes9x2005', 'lr-snes9x2010': 'snes9x2010',
    'lr-stella': 'stella', 'lr-stella2014': 'stella2014', 'lr-swanstation': 'swanstation',
    'lr-tgbdual': 'tgbdual', 'lr-theodore': 'theodore', 'lr-tic80': 'tic80',
    'lr-uae4arm': 'uae4arm', 'lr-uzem': 'uzem', 'lr-vba_next': 'vba_next', 'lr-vbam': 'vbam',
    'lr-vecx': 'vecx', 'lr-vemulator': 'vemulator', 'lr-vice_x128': 'vice_x128',
    'lr-vice_x64': 'vice_x64', 'lr-vice_xplus4': 'vice_xplus4', 'lr-vice_xvic': 'vice_xvic',
    'lr-vircon32': 'vircon32', 'lr-wasm4': 'wasm4', 'lr-x1': 'x1',
    'lr-yabasanshiro': 'yabasanshiro', 'lr-yabause': 'yabause',
}

# dArkOS standalone emulator name -> id in emulators.json (Android equivalent)
STANDALONE = {
    'drastic standalone': 'drastic', 'ppsspp': 'ppsspp', 'ppsspp standalone': 'ppsspp',
    'ppsspp-go standalone': 'ppsspp', 'Duckstation Standalone (except for 351v)': 'duckstation',
    'Dolphin': 'dolphin', 'yabasanshiro standalone': 'yabasanshiro2',
    'standalone(mupen64plus)': 'm64plusfz', 'lr-Azahar': 'azahar',
}

# Extra Android-only standalone emulators per dArkOS folder (appended after
# the dArkOS list, in preference order). Ids refer to emulators.json, whose
# recipes follow ES-DE's Android rules.
EXTRA_STANDALONE = {
    'nds': ['drastic', 'melonds', 'melonds_nightly'],
    'n64': ['m64plusfz'],
    'psp': ['ppsspp'], 'pspminis': ['ppsspp'],
    'psx': ['duckstation'],
    'gc': ['dolphin', 'dolphin_mmjr'],
    '3ds': ['azahar', 'lime3ds', 'citra', 'citra_mmj'],
    'dreamcast': ['redream', 'flycast'], 'naomi': ['flycast'], 'atomiswave': ['flycast'],
    'saturn': ['yabasanshiro2'],
    'arcade': ['mame4droid'], 'mame': ['mame4droid'],
}

# Extra RetroArch for Android cores per dArkOS folder.
EXTRA_CORES = {
    '3ds': ['citra'],
    'nds': ['melondsds', 'melonds', 'desmume'],
    'psp': ['ppsspp'],
    'gc': ['dolphin'],
    'saturn': ['yabasanshiro', 'mednafen_saturn', 'yabadabadoo'],
    'atarijaguar': ['virtualjaguar'],
}

# Systems that dArkOS cannot run but a Snapdragon Android handheld can. They
# follow the same lowercase folder naming scheme (and ES-DE's).
ANDROID_EXTRA_SYSTEMS = [
    dict(id='ps2', name='PlayStation 2', folders=['ps2'],
         ext='.iso .chd .cso .ciso .bin .img .mdf .nrg .elf .gz .zso .isz .m3u .dump',
         standalone=['armsx2', 'nethersx2']),
    dict(id='ps3', name='PlayStation 3', folders=['ps3'], ext='.iso .ps3', standalone=['armsx3', 'rpcsx']),
    dict(id='psvita', name='PlayStation Vita', folders=['psvita'], ext='.psvita', standalone=['vita3k']),
    # hakuX opens .iso .xiso .cso .cci; MainUI unpacks the image out of a .zip before launching.
    dict(id='xbox', name='Microsoft Xbox', folders=['xbox'], ext='.iso .xiso .cso .cci .zip', standalone=['hakux'],
         unzip=['iso', 'xiso', 'cso', 'cci']),
    dict(id='wii', name='Nintendo Wii', folders=['wii'], ext='.iso .rvz .wbfs .wia .gcz .ciso .wad .dol .elf .m3u',
         standalone=['dolphin', 'dolphin_mmjr'], cores=['dolphin']),
    dict(id='switch', name='Nintendo Switch', folders=['switch'], ext='.nsp .xci .nca .nro .nso',
         standalone=['eden', 'kenjinx']),
]

# ScreenScraper system ids (https://api.screenscraper.fr/api2/systemesListe.php)
SSID = {
    '3do': 29, '3ds': 17, 'advision': 78, 'amiga': 64, 'amigacd32': 130, 'amstradcpc': 65,
    'gx4000': 87, 'apple2': 86, 'vmac': 146, 'arcade': 75, 'arduboy': 263, 'astrocde': 44,
    'atomiswave': 53, 'atari800': 43, 'atari2600': 26, 'atari5200': 40, 'atari7800': 41,
    'atarijaguar': 27, 'atarilynx': 28, 'atarist': 42, 'atarixegs': 43, 'bbcmicro': 37,
    'cdimono1': 133, 'coleco': 48, 'c16': 99, 'c64': 66, 'c128': 66, 'vic20': 73,
    'cps1': 6, 'cps2': 7, 'cps3': 8, 'daphne': 49, 'doom': 135, 'dragon32': 91,
    'dreamcast': 23, 'vmu': 23, 'easyrpg': 231, 'channelf': 80, 'fds': 106, 'gb': 9,
    'gba': 12, 'gameandwatch': 52, 'gbc': 10, 'gamegear': 21, 'gc': 13, 'megadrive': 1,
    'intellivision': 115, 'j2me': None, 'lowresnx': 244, 'mame2003': 75, 'mame': 75,
    'mastersystem': 2, 'msumd': 1, 'megaduck': 90, 'msx': 113, 'msx2': 116, 'naomi': 56,
    'neogeo': 142, 'neogeocd': 70, 'ngp': 25, 'ngpc': 82, 'n64': 14, 'n64dd': 122,
    'nds': 15, 'nes': 3, 'odyssey2': 104, 'openbor': 214, 'palm': 219, 'pc98': 208,
    'dos': 135, 'pcengine': 31, 'pcenginecd': 114, 'pcfx': 72, 'pico-8': 234,
    'psx': 57, 'psp': 61, 'pspminis': 172, 'pokemonmini': 211, 'puzzlescript': None,
    'satellaview': 107, 'scummvm': 123, 'sega32x': 19, 'segacd': 20, 'pico': 250,
    'saturn': 22, 'sg-1000': 109, 'x1': 220, 'x68000': 79, 'solarus': 223, 'sufami': 108,
    'scv': 67, 'sgb': 127, 'supergrafx': 105, 'snes': 4, 'snesmsu1': 4, 'snes-hacks': 4,
    'coco3': 144, 'thomson': 141, 'tic80': 222, 'tigerlcd': None, 'ti99': 205,
    'uzebox': 216, 'vectrex': 102, 'videopac': 104, 'tvc': None, 'vircon32': None,
    'virtualboy': 11, 'onscripter': None, 'wasm4': 262, 'supervision': 207, 'wolf': 135,
    'wonderswan': 45, 'wonderswancolor': 46, 'zx81': 77, 'zxspectrum': 76,
    'enterprise': None, 'cavestory': 135, 'alg': 75, 'mv': None, 'piece': None,
    'ps2': 58, 'ps3': 59, 'xbox': 32, 'wii': 16, 'wiiu': 18, 'switch': 225, 'psvita': 62, 'windows': 138,
    'love2d': None,
}

# OnionOS theme icon basenames (Themes/<theme>/icons/<name>.png). A theme icon
# named exactly like the dArkOS folder always wins; these are fallbacks so
# existing Onion themes light up without renaming anything.
ONION_ICON = {
    'sega32x': '32X', 'atari5200': '5200', 'atari7800': '7800', 'amiga': 'amiga',
    'amigacd32': 'amiga', 'arcade': 'arcade', 'mame': 'arcade', 'mame2003': 'arcade',
    'atari2600': 'atari', 'atari800': 'atari', 'atarixegs': 'atari', 'atarist': 'atarist',
    'c64': 'c64', 'c128': 'c64', 'c16': 'c64', 'vic20': 'c64', 'coleco': 'col',
    'amstradcpc': 'cpc', 'gx4000': 'cpc', 'cps1': 'cps1', 'cps2': 'cps2', 'cps3': 'cps3',
    'dos': 'dos', 'channelf': 'fairchild', 'nes': 'fc', 'famicom': 'fc', 'fds': 'fds',
    'gb': 'gb', 'gba': 'gba', 'gbc': 'gbc', 'gamegear': 'gg', 'gameandwatch': 'gw',
    'intellivision': 'itv', 'atarilynx': 'lynx', 'megadrive': 'md', 'genesis': 'md',
    'msumd': 'md', 'megaduck': 'megaduck', 'mastersystem': 'ms', 'msx': 'msx', 'msx2': 'msx',
    'nds': 'nds', 'neogeocd': 'neocd', 'neogeo': 'neogeo', 'ngp': 'ngp', 'ngpc': 'ngp',
    'odyssey2': 'ody', 'pcengine': 'pce', 'turbografx': 'pce', 'pcenginecd': 'pcecd',
    'turbografxcd': 'pcecd', 'pico-8': 'pico', 'pokemonmini': 'poke', 'psx': 'ps',
    'satellaview': 'satella', 'scummvm': 'scummvm', 'segacd': 'segacd', 'sg-1000': 'segasgone',
    'snes': 'sfc', 'sfc': 'sfc', 'snes-hacks': 'sfc', 'snesmsu1': 'sfc', 'sgb': 'sgb',
    'supergrafx': 'sgfx', 'sufami': 'sufami', 'supervision': 'supervision', 'tic80': 'tic',
    'virtualboy': 'vb', 'videopac': 'vdp', 'vectrex': 'vectrex', 'wonderswan': 'ws',
    'wonderswancolor': 'ws', 'zxspectrum': 'zxs', 'zx81': 'zxs', 'psp': 'psp',
    'pspminis': 'psp', 'n64': 'n64', 'dreamcast': 'dc', 'saturn': 'saturn',
    'atarijaguar': 'jaguar', 'naomi': 'naomi', 'atomiswave': 'atomiswave', '3do': '3do',
}

# Short labels used by the built-in procedural icons (no theme icon present).
SHORT = {
    'nes': 'NES', 'famicom': 'FC', 'snes': 'SNES', 'sfc': 'SFC', 'n64': 'N64', 'gb': 'GB',
    'gbc': 'GBC', 'gba': 'GBA', 'nds': 'NDS', '3ds': '3DS', 'gc': 'GC', 'wii': 'Wii',
    'wiiu': 'WiiU', 'switch': 'NSW', 'psx': 'PS1', 'ps2': 'PS2', 'psp': 'PSP',
    'psvita': 'Vita', 'ps3': 'PS3', 'xbox': 'XBOX', 'megadrive': 'MD', 'genesis': 'GEN', 'mastersystem': 'SMS',
    'gamegear': 'GG', 'segacd': 'SCD', 'sega32x': '32X', 'saturn': 'SAT', 'dreamcast': 'DC',
    'arcade': 'ARC', 'mame': 'MAME', 'neogeo': 'NEO', 'pcengine': 'PCE',
}


def parse_wiki(path):
    text = open(path, encoding='utf-8').read()
    text = text.split('# Emulators', 1)[1]
    text = text.split('\n# Ports', 1)[0]
    out = []
    for sec in re.split(r'\n### ', text)[1:]:
        name = sec.split('\n', 1)[0].strip()
        folder = re.search(r'Rom Folder:\s*(.+?)\s*\\?\n', sec)
        if not folder:
            continue
        ext = re.search(r'Exten[a-z]*:\s*(.+?)\s*\\?\n', sec)  # wiki has typos
        emu = re.search(r'Emulators?:\s*(.+?)\s*\\?\n', sec)
        out.append(dict(name=name, folder=folder.group(1), ext=ext.group(1) if ext else '',
                        emu=emu.group(1) if emu else ''))
    return out


def clean_name(name):
    name = re.sub(r'\s*\((?:Best|Available|RK3566|Deprecated)[^)]*\)', '', name)
    name = re.sub(r'\s*\(RK3566[^)]*\)', '', name)
    return name.strip()


def parse_ext(ext):
    ext = re.sub(r'~~[^~]*~~', '', ext)          # struck-out extensions
    ext = re.sub(r'\([^)]*\)', '', ext)          # notes in parentheses
    found = re.findall(r'\.([A-Za-z0-9_\-]+)', ext)
    seen, res = set(), []
    for e in found:
        e = e.lower()
        if e not in seen:
            seen.add(e)
            res.append(e)
    return res


def parse_emulators(emu):
    """Return ordered emulator refs (ra:<core> / sa:<id>), default first."""
    refs = []
    emu = re.sub(r'\(http[^)]*\)', '', emu)
    tokens = re.findall(r'\[([^\]]+)\]|\b(lr-[\w\-]+)', emu)
    for a, b in tokens:
        n = (a or b).strip()
        key = re.sub(r'\s*\(.*$', '', n) if n.startswith('lr-') else n
        if key in CORES:
            core = CORES[key]
            if core:
                refs.append('ra:' + core)
        elif n in STANDALONE:
            refs.append('sa:' + STANDALONE[n])
    seen, res = set(), []
    for r in refs:
        if r not in seen:
            seen.add(r)
            res.append(r)
    return res


def main():
    wiki, out_path = sys.argv[1], sys.argv[2]
    systems = []
    for s in parse_wiki(wiki):
        folders = [f.strip() for f in re.split(r'\s+or\s+', s['folder'])]
        sid = folders[0].split('/')[0]
        if sid in ('love2d',):
            continue
        emus = parse_emulators(s['emu'])
        for extra in EXTRA_STANDALONE.get(sid, []):
            if 'sa:' + extra not in emus:
                emus.append('sa:' + extra)
        for core in EXTRA_CORES.get(sid, []):
            if 'ra:' + core not in emus:
                emus.append('ra:' + core)
        icons = []
        for f in folders:
            f = f.split('/')[0]
            icons.append(f)
            if f in ONION_ICON:
                icons.append(ONION_ICON[f])
        systems.append(dict(
            id=sid, name=clean_name(s['name']), folders=folders, extensions=parse_ext(s['ext']),
            emulators=emus, screenscraper=SSID.get(sid), icons=list(dict.fromkeys(icons)),
            short=SHORT.get(sid, sid.split('/')[0][:4].upper()),
        ))
    for x in ANDROID_EXTRA_SYSTEMS:
        systems.append(dict(
            id=x['id'], name=x['name'], folders=x['folders'], extensions=parse_ext(x['ext']),
            emulators=['sa:' + e for e in x['standalone']] + ['ra:' + c for c in x.get('cores', [])],
            screenscraper=SSID.get(x['id']),
            icons=[x['id']], short=SHORT.get(x['id'], x['id'][:4].upper()),
        ))
        if x.get('unzip'):
            systems[-1]['unzip'] = x['unzip']
    systems.sort(key=lambda s: s['name'].lower())
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump({'version': 1, 'source': 'dArkOS wiki: Emulators and Ports information',
                   'systems': systems}, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print('wrote %d systems to %s' % (len(systems), out_path))


if __name__ == '__main__':
    main()
