#!/usr/bin/env python3
"""Reproduce launcher assets from docs/brand/icon-source.png (Pillow required).

Keep the supplied artwork intact: remove only the connected white surround,
never re-draw the wallet. The full tile is inset on the adaptive background;
its wallet/chart bounding box fits within Android's 66dp safe circle.
"""
from collections import deque
from pathlib import Path
import argparse
import hashlib
import json

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
BACKGROUND = (0, 58, 49, 255)


def tile_from_source(source):
    image = source.convert('RGBA')
    pixels = image.load()
    width, height = image.size
    seen = bytearray(width * height)
    queue = deque([(0, 0)])
    # Flood only the outside: pale wallet/chart pixels are enclosed and retained.
    while queue:
        x, y = queue.popleft()
        if x < 0 or y < 0 or x >= width or y >= height:
            continue
        index = y * width + x
        if seen[index]:
            continue
        seen[index] = 1
        r, g, b, _ = pixels[x, y]
        if g > r * 1.4 and g > b * 1.02:
            continue
        pixels[x, y] = (0, 0, 0, 0)
        queue.extend(((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)))
    return image.crop(image.getbbox())


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)  # No inherited text/EXIF metadata.


def adaptive(tile, size=432):
    layer = Image.new('RGBA', (size, size))
    inset_tile = tile.resize((int(size * 62 / 108),) * 2, Image.Resampling.LANCZOS)
    layer.alpha_composite(inset_tile, ((size - inset_tile.width) // 2,) * 2)
    return layer


def mask(shape, size):
    result = Image.new('L', (size, size))
    draw = ImageDraw.Draw(result)
    if shape == 'circle':
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    elif shape == 'rounded-square':
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=size * .22, fill=255)
    else:
        # Superellipse x^4 + y^4 = 1.
        points = []
        import math
        for i in range(721):
            angle = i * math.tau / 720
            c, s = math.cos(angle), math.sin(angle)
            points.append(((1 + math.copysign(abs(c)**.5, c)) * (size - 1)/2,
                           (1 + math.copysign(abs(s)**.5, s)) * (size - 1)/2))
        draw.polygon(points, fill=255)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    source_path = ROOT / 'docs/brand/icon-source.png'
    tile = tile_from_source(Image.open(source_path))
    foreground = adaptive(tile)
    save(foreground, RES / 'drawable-nodpi/ic_launcher_foreground.png')
    save(tile.resize((256, 256), Image.Resampling.LANCZOS), RES / 'drawable-nodpi/tutorial_icon.png')
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        legacy = tile.resize((size, size), Image.Resampling.LANCZOS)
        save(legacy, RES / f'mipmap-{density}/ic_launcher.png')
        round_icon = Image.new('RGBA', (size * 4, size * 4), BACKGROUND)
        round_icon.alpha_composite(adaptive(tile, size * 4))
        round_icon.putalpha(mask('circle', size * 4))
        save(round_icon.resize((size, size), Image.Resampling.LANCZOS), RES / f'mipmap-{density}/ic_launcher_round.png')
    xml = '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'''
    for name in ['ic_launcher', 'ic_launcher_round']:
        path = RES / f'mipmap-anydpi-v26/{name}.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(xml)
    colors = RES / 'values/icon_colors.xml'
    colors.write_text('<resources>\n    <color name="ic_launcher_background">#003A31</color>\n</resources>\n')
    # Adaptive visible viewport is 72 of 108dp, not the entire layer canvas.
    composite = Image.new('RGBA', foreground.size, BACKGROUND)
    composite.alpha_composite(foreground)
    visible = composite.crop((72, 72, 360, 360))
    sheet = Image.new('RGB', (900, 670), '#F4F4F4')
    draw = ImageDraw.Draw(sheet)
    for row, color in enumerate(['#F4F4F4', '#171E1C']):
        draw.rectangle((0, row * 335, 900, (row + 1) * 335), fill=color)
        for column, shape in enumerate(['circle', 'squircle', 'rounded-square']):
            icon = visible.resize((240, 240), Image.Resampling.LANCZOS)
            icon.putalpha(mask(shape, 240))
            sheet.paste(icon, (column * 300 + 30, row * 335 + 35), icon)
            draw.text((column * 300 + 30, row * 335 + 290), shape, fill='#739C8D')
    save(sheet, args.evidence / 'icon-mask-preview.png')
    # Source glyph bbox manually inspected at full source resolution. The safe
    # circle check is deliberately conservative: all four bbox corners must fit.
    source = Image.open(source_path)
    source_tile = tile_from_source(source)
    # Trimmed tile origin from the connected-background alpha mask.
    origin = (84, 84)
    glyph_bbox = (230, 225, 1025, 978)
    scale = 62 / source_tile.width
    corners = [((x - origin[0]) * scale + 23 - 54, (y - origin[1]) * scale + 23 - 54)
               for x in [glyph_bbox[0], glyph_bbox[2]] for y in [glyph_bbox[1], glyph_bbox[3]]]
    assert all(x*x + y*y < 33*33 for x, y in corners), 'Glyph exceeds safe circle'
    metadata = {'source_sha256': hashlib.sha256(source_path.read_bytes()).hexdigest(),
                'source_size': source.size, 'trimmed_tile_size': source_tile.size,
                'adaptive_canvas_dp': 108, 'tile_dp': 62, 'safe_zone_diameter_dp': 66,
                'glyph_source_bbox': glyph_bbox, 'glyph_bbox_corner_radii_dp': [(x*x+y*y)**.5 for x,y in corners],
                'monochrome': 'Omitted: supplied raster has no separate glyph alpha; do not invent a redrawn glyph.'}
    (args.evidence / 'icon-provenance.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()
