#!/usr/bin/env python3
"""Export the approved pixel TV into density-specific Android assets and README art.
Requires ImageMagick 7. Source image remains unmodified; no runtime image library.
"""
import base64
import json
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
    subprocess.run(['magick', *map(str, args[:-1]), '-strip', str(args[-1])], check=True)
with tempfile.TemporaryDirectory() as directory:
    sprite = Path(directory) / 'sprite.png'
    magick(BRAND / 'logo-pixel-source.png', '-trim', '+repage', sprite)
    magick(sprite, '-filter', 'point', '-resize', '384x320', BRAND / 'logo-pixel.png')
    for qualifier, size in [('mdpi', 80), ('hdpi', 120), ('xhdpi', 160), ('xxhdpi', 240), ('xxxhdpi', 320)]:
        target = RES / ('mipmap-' + qualifier) / 'ic_launcher.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        art = round(size * .80)
        magick(sprite, '-filter', 'point', '-resize', f'{art}x{art}', '-background', BG,
               '-gravity', 'center', '-extent', f'{size}x{size}', target)
    # 108 dp adaptive layer, artwork fits inside the safe central circle.
    for qualifier, size in [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]:
        target = RES / ('drawable-' + qualifier) / 'app_icon_foreground.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        art = round(size * .52)
        magick(sprite, '-filter', 'point', '-resize', f'{art}x{art}', '-background', 'none',
               '-gravity', 'center', '-extent', f'{size}x{size}', target)
    target = RES / 'drawable-nodpi/brand_mark.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    magick(sprite, '-filter', 'point', '-resize', '144x120', target)
    # Compatibility alias for other in-app references; navigation uses the tightly cropped mark.
    magick(sprite, '-filter', 'point', '-resize', '128x128', '-background', BG,
           '-gravity', 'center', '-extent', '160x160', RES / 'drawable-nodpi/app_icon.png')
    text = json.loads((BRAND / 'wordmark-paths.json').read_text())
    encoded = base64.b64encode((BRAND / 'logo-pixel.png').read_bytes()).decode()
    content = f'<path fill="{BG}" d="M0 0 H320 V180 H0 Z"/><image x="20" y="40" width="108" height="100" href="data:image/png;base64,{encoded}"/>'
    for key, color in [('name','#FFF8EF'),('tv','#FFF8EF'),('tagline','#D2DFEB')]:
        content += f'<path fill="{color}" d="{text[key]}"/>'
    for name, box, w, h in [('tv-banner.svg','0 0 320 180',320,180),('readme-banner.svg','0 28 320 124',960,372)]:
        write(BRAND / name, f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{w}" height="{h}" viewBox="{box}" role="img" aria-label="红果 TV，好故事，大屏看">{content.replace("href=", "xlink:href=")}</svg>')
    for qualifier, w, h in [('mdpi',160,90),('hdpi',240,135),('xhdpi',320,180),('xxhdpi',480,270),('xxxhdpi',640,360)]:
        target = RES / ('drawable-' + qualifier) / 'tv_banner.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        magick('-background','none', BRAND / 'tv-banner.svg', '-resize', f'{w}x{h}', target)
    magick('-background','none', BRAND / 'readme-banner.svg', BRAND / 'readme-banner.png')
write(RES / 'values/brand.xml', f'<resources><color name="app_icon_background">{BG}</color></resources>')
write(RES / 'mipmap-anydpi-v26/ic_launcher.xml', '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/app_icon_background"/><foreground android:drawable="@drawable/app_icon_foreground"/></adaptive-icon>')

# Keep the companion page logo in sync without another image request or image server.
import re
page = ROOT / 'kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PhoneControlPage.kt'
html = page.read_text()
encoded = base64.b64encode((RES / 'drawable-nodpi/brand_mark.png').read_bytes()).decode()
brand = '<!-- brand:start --><h1 style="display:flex;align-items:center;gap:10px"><img width="48" height="40" style="object-fit:contain;image-rendering:pixelated" src="data:image/png;base64,' + encoded + '" alt=""/>红果 TV</h1><!-- brand:end -->'
if '<!-- brand:start -->' in html:
    html = re.sub(r'<!-- brand:start -->.*?<!-- brand:end -->', lambda _: brand, html)
else:
    html = html.replace('<h1>红果 TV</h1>', brand)
page.write_text(html)
