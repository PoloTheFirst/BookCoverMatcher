"""Prints a compact summary of one OCR result JSON produced by the reference or by the Rust engine.

    python tools/ocr-reference/inspect.py <result.json> [...]

Kept as a file (not a one-liner) because the console here mangles inline quoting and cp1252.
"""

from __future__ import annotations

import io
import json
import sys


def main(paths: list[str]) -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    for path in paths:
        data = json.load(io.open(path, encoding="utf-8"))
        det = data.get("det", {})
        print(f"--- {data.get('engine')} | {data.get('image')}")
        print(f"    image {data.get('image_size')} det input {det.get('input')} map {det.get('map')} regions {det.get('regions')}")
        for line in data.get("lines", []):
            box = line.get("box") or []
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            span = f"[{min(xs):.0f},{min(ys):.0f}..{max(xs):.0f},{max(ys):.0f}]" if box else "-"
            print(f"    {line.get('score', 0):.3f} {span:24s} {line.get('text')!r}")
        regions = data.get("det_regions") or []
        if regions:
            print(f"    -- {len(regions)} detector region(s)")
            for region in regions[:6]:
                box = region.get("box") or []
                xs = [p[0] for p in box]
                ys = [p[1] for p in box]
                span = f"[{min(xs):.0f},{min(ys):.0f}..{max(xs):.0f},{max(ys):.0f}]" if box else "-"
                print(f"       {region.get('score', 0):.3f} {span:24s}")


if __name__ == "__main__":
    main(sys.argv[1:])
