#!/usr/bin/env python3
"""Extract one frame per EV-bracket step from a recorded clip + AE CSV.

Usage: ./extract_bracket.py ae_YYYYmmdd_HHMMSS.csv DASHCAM_*.mp4 [outdir]
The app starts the bracket at +0.5 EV and changes every 5s; CSV gives the
wall-clock/elapsed mapping. Output: one annotated PNG per step + contact sheet.
"""
import csv
import os
import subprocess
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow is required: pip install pillow")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    csv_path, video = sys.argv[1], sys.argv[2]
    outdir = sys.argv[3] if len(sys.argv) > 3 else "bracket_frames"
    os.makedirs(outdir, exist_ok=True)

    rows = list(csv.DictReader(open(csv_path, newline="")))
    if not rows:
        sys.exit("empty csv")
    # contiguous runs of the same ev_steps
    runs = []
    for r in rows:
        ev = int(r.get("ev_steps") or 0)
        if not runs or runs[-1][0] != ev:
            runs.append([ev, []])
        runs[-1][1].append(r)

    frames = []
    for i, (ev, rs) in enumerate(runs):
        mid = rs[len(rs) // 2]
        t = float(mid.get("elapsed_ms") or 0) / 1000.0
        if len(rs) < 2:
            continue
        png = os.path.join(outdir, f"{i:02d}_ev{ev*0.5:+.1f}.png")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{t:.3f}", "-i", video,
                        "-frames:v", "1", png], check=True)
        im = Image.open(png).convert("RGB")
        d = ImageDraw.Draw(im)
        demoji = f"EV{ev*0.5:+.1f}  {mid.get('act_exp_ns','')}ns  ISO{mid.get('act_iso','')}"
        demoji += f"  scene={mid.get('scene','')}  clip={mid.get('roi_clip_pct','')}%"
        try:
            font = ImageFont.truetype(
                "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 46)
        except Exception:
            font = None
        bar = 78
        d.rectangle([0, 0, im.width, bar], fill=(0, 0, 0))
        d.text((18, 16), demoji, fill=(255, 255, 0), font=font)
        im.save(png)
        frames.append((png, demoji))

    if frames:
        thumbs = []
        for p, _ in frames:
            im = Image.open(p).convert("RGB")
            im.thumbnail((640, 360))
            thumbs.append(im)
        cols = min(3, len(thumbs))
        rows_n = (len(thumbs) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * 640, rows_n * 360), (0, 0, 0))
        for i, im in enumerate(thumbs):
            x, y = (i % cols) * 640, (i // cols) * 360
            sheet.paste(im, (x, y))
            try:
                small = ImageFont.truetype(
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
            except Exception:
                small = None
            d2 = ImageDraw.Draw(sheet)
            d2.text((x + 10, y + 330), frames[i][1], fill=(255, 255, 0), font=small)
        sheet_path = os.path.join(outdir, "bracket_sheet.jpg")
        sheet.save(sheet_path, quality=88)
        print("wrote", sheet_path)
    print(f"{len(frames)} frames -> {outdir}")


if __name__ == "__main__":
    main()
