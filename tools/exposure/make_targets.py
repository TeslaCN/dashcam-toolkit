#!/usr/bin/env python3
"""Generate printable license-plate exposure targets (A4 landscape, 300 dpi).

The printed plate is 260 mm wide (~0.59x a real 440 mm plate), so to emulate a
real plate at distance D, place it at D * 0.59 (e.g. 10 m -> 5.9 m), or just use
the relative distances consistently.

Outputs: targets/plate_blue.png, plate_yellow.png, plate_green.png,
targets/plate_white.png and targets/plate_targets_A4.pdf (2 pages).
"""
import os

from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "targets")
DPI = 300
PAGE_W, PAGE_H = 3508, 2480          # A4 landscape @300dpi
PLATE_W, PLATE_H = 3071, 977          # 260 x 82.7 mm
FONT = "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc"

PLATES = [
    ("沪B·AT158", (20, 62, 140), (245, 245, 245)),   # blue plate, white glyphs
    ("苏E·6TE86", (245, 190, 20), (25, 25, 25)),     # yellow plate, black glyphs
    ("沪A·D12345", (70, 190, 110), (20, 20, 20)),    # green plate, black glyphs
    ("京A·12345", (245, 245, 245), (20, 20, 20)),    # white plate, black glyphs
]


def plate_image(text, bg, fg):
    im = Image.new("RGB", (PLATE_W, PLATE_H), bg)
    d = ImageDraw.Draw(im)
    # border
    d.rectangle([0, 0, PLATE_W - 1, PLATE_H - 1], outline=fg, width=10)
    # bolt holes
    for x in (70, PLATE_W - 70):
        d.ellipse([x - 18, PLATE_H // 2 - 18, x + 18, PLATE_H // 2 + 18], fill=fg)
    font = ImageFont.truetype(FONT, 470, index=0)
    bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text(((PLATE_W - tw) / 2 - bbox[0], (PLATE_H - th) / 2 - bbox[1]), text, font=font, fill=fg)
    return im


def page(plates, title):
    page = Image.new("RGB", (PAGE_W, PAGE_H), (255, 255, 255))
    d = ImageDraw.Draw(page)
    font = ImageFont.truetype(FONT, 54, index=0)
    small = ImageFont.truetype(FONT, 36, index=0)
    d.text((80, 50), title, font=font, fill=(0, 0, 0))
    d.text((80, 120), "打印后按实际宽度 260mm 校准；等效 440mm 车牌距离 = 实测距离 × 0.59",
           font=small, fill=(40, 40, 40))
    y = 210
    for im in plates:
        page.paste(im, ((PAGE_W - PLATE_W) // 2, y))
        y += PLATE_H + 120
    d.text((80, PAGE_H - 70), "WWJ dashcam AE target - 2026", font=small, fill=(90, 90, 90))
    return page


def main():
    os.makedirs(OUT, exist_ok=True)
    pages = []
    colors = ["blue", "yellow", "green", "white"]
    for i, (text, bg, fg) in enumerate(PLATES):
        im = plate_image(text, bg, fg)
        im.save(os.path.join(OUT, f"plate_{i+1}_{colors[i]}.png"))
        pages.append(im)
    p1 = page(pages[:2], "车牌曝光靶标 1/2：蓝牌 / 黄牌")
    p2 = page(pages[2:], "车牌曝光靶标 2/2：绿牌 / 白牌")
    p1.save(os.path.join(OUT, "plate_targets_A4.pdf"), save_all=True, append_images=[p2], resolution=DPI)
    p1.save(os.path.join(OUT, "page1.png"))
    p2.save(os.path.join(OUT, "page2.png"))
    print("wrote", OUT)


if __name__ == "__main__":
    main()
