#!/usr/bin/env python3
"""Generate JoynCheck's adaptive launcher foreground and monochrome PNGs.

Usage: python3 scripts/make_icon.py [assets/icon_color.PNG]

Same artwork as JoynCon (keyed and fitted exactly like JoyMerge's scripts/make_icon.py), with a
big green checkmark drawn over it so the two apps are distinguishable on a home screen.
"""
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "assets/icon_color.PNG"

# Foreground canvas is 108dp; artwork fits a box this fraction of it (inside the 66dp safe zone).
CONTENT_FRACTION = 0.575
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
EDGE = 2  # ignore stray pixels at the source's border
SOFT = 60  # channel value at which a pixel becomes fully opaque

MASTER = 432 * 4  # draw once at high resolution, then downsample for every density
CHECK_POINTS_DP = [(35, 56), (49, 70), (75, 40)]  # stays inside the 33dp-radius safe circle
CHECK_WIDTH_DP = 10
OUTLINE_DP = 3.5
CHECK_GREEN = (0x22, 0xC5, 0x5E, 255)
OUTLINE = (0x1C, 0x1C, 0x1C, 255)  # matches ic_launcher_background


def key_out_black(src):
    w, h = src.size
    px = src.load()
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    opx = out.load()
    for y in range(EDGE, h - EDGE):
        for x in range(EDGE, w - EDGE):
            r, g, b, a = px[x, y]
            m = max(r, g, b)
            alpha = min(255, m * 255 // SOFT) * a // 255
            if alpha:
                k = 255 / min(255, m * 255 // SOFT)
                opx[x, y] = (min(255, round(r * k)), min(255, round(g * k)), min(255, round(b * k)), alpha)
    return out.crop(out.getchannel("A").getbbox())


def stroke_mask(size, width_dp):
    """A round-capped, round-jointed polyline through CHECK_POINTS_DP as an L-mode mask."""
    scale = size / 108
    pts = [(x * scale, y * scale) for x, y in CHECK_POINTS_DP]
    width = width_dp * scale
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.line(pts, fill=255, width=round(width))
    r = width / 2
    for x, y in pts:
        draw.ellipse((x - r, y - r, x + r, y + r), fill=255)
    return mask


art = key_out_black(Image.open(SRC).convert("RGBA"))
master = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
scale = MASTER * CONTENT_FRACTION / max(art.size)
scaled = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
master.paste(scaled, ((MASTER - scaled.width) // 2, (MASTER - scaled.height) // 2), scaled)

outline_mask = stroke_mask(MASTER, CHECK_WIDTH_DP + 2 * OUTLINE_DP)
check_mask = stroke_mask(MASTER, CHECK_WIDTH_DP)
master.paste(Image.new("RGBA", master.size, OUTLINE), (0, 0), outline_mask)
master.paste(Image.new("RGBA", master.size, CHECK_GREEN), (0, 0), check_mask)

# Themed icons (Android 13+) only use alpha, so the outline becomes a transparent gap there —
# otherwise the check would merge into the artwork under it.
mono_alpha = ImageChops.lighter(ImageChops.subtract(master.getchannel("A"), outline_mask), check_mask)

for name, size in DENSITIES.items():
    fg = master.resize((size, size), Image.LANCZOS)
    dest = RES / f"mipmap-{name}/ic_launcher_foreground.png"
    fg.save(dest, optimize=True)
    print(dest.relative_to(ROOT), fg.size)

    mono = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    mono.putalpha(mono_alpha.resize((size, size), Image.LANCZOS))
    dest = RES / f"mipmap-{name}/ic_launcher_monochrome.png"
    mono.save(dest, optimize=True)
    print(dest.relative_to(ROOT), mono.size)
