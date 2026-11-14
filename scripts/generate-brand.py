#!/usr/bin/env python3
"""Export the approved pixel TV into density-specific Android assets and README art.
Requires ImageMagick 7. Source image remains unmodified; no runtime image library.
"""
import base64
import subprocess
import tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / 'assets/brand'
RES = ROOT / 'kotlin-tv/app/src/main/res'
BG = '#293B50'
def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text + '\n')
def magick(*args):
    subprocess.run(['magick', *map(str, args[:-1]), '-depth', '8', '-strip', str(args[-1])], check=True)
with tempfile.TemporaryDirectory() as directory:
    raw_sprite = Path(directory) / 'raw_sprite.png'
    sprite = Path(directory) / 'sprite.png'
    magick(BRAND / 'logo-pixel-source.png', '-trim', '+repage', raw_sprite)
    magick(raw_sprite, '-filter', 'point', '-resize', '384x320', BRAND / 'logo-pixel.png')
    # Strip <6% alpha matting dust before centering so launcher icons stay centered and fit circular clips while keeping RGBA alpha.
    magick(BRAND / 'logo-pixel.png', '-channel', 'A', '-black-threshold', '6%', '+channel', '-trim', '+repage', sprite)
    for qualifier, size in [('mdpi', 80), ('hdpi', 120), ('xhdpi', 160), ('xxhdpi', 240), ('xxxhdpi', 320)]:
        target = RES / ('mipmap-' + qualifier) / 'ic_launcher.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        art = round(size * .81)
        magick(sprite, '-filter', 'point', '-resize', f'{art}x{art}', '-background', 'none',
               '-gravity', 'center', '-extent', f'{size}x{size}', target)
    target = RES / 'drawable-nodpi/brand_mark.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    magick(raw_sprite, '-filter', 'point', '-resize', '144x120', target)
    # Compatibility alias for other in-app references; preserves RGBA transparency with no colored plate.
    magick(sprite, '-filter', 'point', '-resize', '130x130', '-background', 'none',
           '-gravity', 'center', '-extent', '160x160', RES / 'drawable-nodpi/app_icon.png')
    encoded = base64.b64encode((BRAND / 'logo-pixel.png').read_bytes()).decode()
    content = f'<path fill="{BG}" d="M0 0 H320 V180 H0 Z"/><image x="20" y="40" width="108" height="100" href="data:image/png;base64,{encoded}"/>'
    wordmark = (BRAND / 'hongguotv-wordmark-path.txt').read_text().strip()
    content += f'<path fill="#FFF8EF" d="{wordmark}"/>'
    for name, box, w, h in [('tv-banner.svg','0 0 320 180',320,180),('readme-banner.svg','0 28 320 124',960,372)]:
        write(BRAND / name, f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{w}" height="{h}" viewBox="{box}" role="img" aria-label="hongguoTV">{content.replace("href=", "xlink:href=")}</svg>')
    for qualifier, w, h in [('mdpi',160,90),('hdpi',240,135),('xhdpi',320,180),('xxhdpi',480,270),('xxxhdpi',640,360)]:
        target = RES / ('drawable-' + qualifier) / 'tv_banner.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        magick('-background','none', BRAND / 'tv-banner.svg', '-resize', f'{w}x{h}', target)
    magick('-background','none', BRAND / 'readme-banner.svg', BRAND / 'readme-banner.png')

# Keep the companion page logo in sync without another image request or image server.
import re
page = ROOT / 'kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PhoneControlPage.kt'
html = page.read_text()
encoded = base64.b64encode((RES / 'drawable-nodpi/brand_mark.png').read_bytes()).decode()
brand = '<!-- brand:start --><h1 style="display:flex;align-items:center;gap:10px"><img width="48" height="40" style="object-fit:contain;image-rendering:pixelated" src="data:image/png;base64,' + encoded + '" alt=""/>hongguoTV</h1><!-- brand:end -->'
if '<!-- brand:start -->' in html:
    html = re.sub(r'<!-- brand:start -->.*?<!-- brand:end -->', lambda _: brand, html)
else:
    html = html.replace('<h1>hongguoTV</h1>', brand)
page.write_text(html)

# Keep the legacy Android package aligned with the native TV branding.
import shutil
legacy = ROOT / 'android/app/src/main/res'
for qualifier in ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']:
    for name in ['ic_launcher.png', 'ic_launcher_round.png']:
        shutil.copyfile(RES / f'mipmap-{qualifier}/ic_launcher.png', legacy / f'mipmap-{qualifier}' / name)
shutil.copyfile(RES / 'drawable-xhdpi/tv_banner.png', legacy / 'drawable/tv_banner.png')
