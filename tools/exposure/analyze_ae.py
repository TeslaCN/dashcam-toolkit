#!/usr/bin/env python3
"""Summarize AE telemetry CSV produced by the app (Settings -> 记录曝光遥测 CSV).

Usage: ./analyze_ae.py /path/ae_YYYYmmdd_HHMMSS.csv [--scene N]
"""
import argparse
import csv
import statistics
from collections import defaultdict


def f(row, key):
    try:
        return float(row.get(key) or 0)
    except ValueError:
        return 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--scene", type=int, default=None, help="only this scene code (1=day 2=dusk 3=night)")
    args = ap.parse_args()

    groups = defaultdict(list)
    with open(args.csv, newline="") as fh:
        for r in csv.DictReader(fh):
            try:
                scene = int(r.get("scene") or -1)
            except ValueError:
                scene = -1
            if args.scene is not None and scene != args.scene:
                continue
            key = (r.get("mode", "?"), scene, int(r.get("ev_steps") or 0))
            groups[key].append(r)

    print(f"{'mode':9} {'scene':5} {'EV':>5} {'n':>4} {'exp_ms':>8} {'iso':>6} "
          f"{'mean':>6} {'p50':>5} {'p90':>5} {'p99':>5} {'clip%':>6} {'plateP90':>8}")
    for key in sorted(groups, key=lambda k: (k[0], k[1], k[2])):
        rows = groups[key]
        n = len(rows)
        avg = lambda k: sum(f(x, k) for x in rows) / n
        print(f"{key[0]:9} {key[1]:5} {key[2]*0.5:>5.1f} {n:>4} "
              f"{avg('act_exp_ns')/1e6:>8.2f} {avg('act_iso'):>6.0f} "
              f"{avg('roi_mean'):>6.0f} {avg('roi_p50'):>5.0f} {avg('roi_p90'):>5.0f} "
              f"{avg('roi_p99'):>5.0f} {avg('roi_clip_pct'):>6.1f} {avg('plate_p90'):>8.0f}")


if __name__ == "__main__":
    main()
