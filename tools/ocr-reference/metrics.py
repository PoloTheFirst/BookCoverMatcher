# SPDX-License-Identifier: GPL-3.0-or-later

"""Scores OCR results against the fixture ground truth: CER, exact match, detection recall.

    python tools/ocr-reference/metrics.py --truth <truth.json> --results <dir> [--label NAME]

`--results` holds one `<fixture>.json` per fixture, produced by either engine
(`reference_paddle.py --json`, or `cargo run --example eval -- --json`). Every ground-truth region is
matched to the recognised line with the smallest edit distance, so a missing line costs its full
length and an extra hallucinated line shows up in the noise count instead of hiding inside a
concatenation.

Scoring normalisation (documented because it changes the numbers): Unicode NFKC, then all whitespace
removed. Raw text is reported alongside, and NFKC is the only transformation — no character folding,
no punctuation stripping, no script-specific rules.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import unicodedata
from pathlib import Path


def edit_distance(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(
                min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb))
            )
        previous = current
    return previous[-1]


def normalise(text: str) -> str:
    return "".join(unicodedata.normalize("NFKC", text).split())


def iou(box_a, box_b) -> float:
    ax0, ay0 = min(p[0] for p in box_a), min(p[1] for p in box_a)
    ax1, ay1 = max(p[0] for p in box_a), max(p[1] for p in box_a)
    bx0, by0 = min(p[0] for p in box_b), min(p[1] for p in box_b)
    bx1, by1 = max(p[0] for p in box_b), max(p[1] for p in box_b)
    inter_w = max(0.0, min(ax1, bx1) - max(ax0, bx0))
    inter_h = max(0.0, min(ay1, by1) - max(ay0, by0))
    inter = inter_w * inter_h
    union = (ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter
    return inter / union if union > 0 else 0.0


def as_points(box):
    """Ground truth uses [x0,y0,x1,y1]; engines emit four [x,y] points."""
    if len(box) == 4 and all(isinstance(v, (int, float)) for v in box):
        x0, y0, x1, y1 = box
        return [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]
    return box


def score_fixture(entry: dict, result: dict) -> dict:
    lines = result.get("lines", [])
    rows = []
    for region in entry["regions"]:
        truth = region["text"]
        best = min(
            lines,
            key=lambda l: edit_distance(normalise(l.get("text", "")), normalise(truth)),
            default=None,
        )
        got = (best or {}).get("text", "")
        distance = edit_distance(normalise(got), normalise(truth))
        rows.append(
            {
                "truth": truth,
                "got": got,
                "cer": distance / max(len(normalise(truth)), 1),
                "exact": normalise(got) == normalise(truth),
                "detected": any(
                    iou(as_points(l.get("box") or []), as_points(region["box"])) >= 0.3
                    for l in lines
                    if l.get("box")
                ),
                "score": round(float((best or {}).get("score", 0.0)), 3),
            }
        )
    matched = {id(r) for r in rows}
    unmatched_lines = max(0, len(lines) - len(matched))
    chars = sum(max(len(normalise(r["truth"])), 1) for r in rows)
    errors = sum(edit_distance(normalise(r["got"]), normalise(r["truth"])) for r in rows)
    return {
        "name": entry["name"],
        "lang": entry["lang"],
        "regions": rows,
        "cer": errors / max(chars, 1),
        "exact_rate": sum(1 for r in rows if r["exact"]) / max(len(rows), 1),
        "recall": sum(1 for r in rows if r["detected"]) / max(len(rows), 1),
        "extra_lines": unmatched_lines,
        "title_exact": any(
            normalise(r["got"]) == normalise(entry["title"]) for r in rows
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--truth", required=True)
    parser.add_argument("--results", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    truth = json.load(io.open(args.truth, encoding="utf-8"))
    results_dir = Path(args.results)
    label = args.label or results_dir.name

    print(f"== {label}")
    print(f"{'fixture':15s} {'lang':8s} {'CER':>6s} {'exact':>6s} {'recall':>7s} {'extra':>6s} {'title':>6s}")
    total_errors = total_chars = 0
    scored = 0
    for entry in truth["fixtures"]:
        path = results_dir / f"{entry['name']}.json"
        if not path.is_file():
            print(f"{entry['name']:15s} {'':8s} {'(no result)':>6s}")
            continue
        result = json.load(io.open(path, encoding="utf-8"))
        row = score_fixture(entry, result)
        scored += 1
        total_errors += sum(
            edit_distance(normalise(r["got"]), normalise(r["truth"])) for r in row["regions"]
        )
        total_chars += sum(max(len(normalise(r["truth"])), 1) for r in row["regions"])
        print(
            f"{row['name']:15s} {row['lang']:8s} {row['cer']:6.3f} "
            f"{row['exact_rate']:6.2f} {row['recall']:7.2f} {row['extra_lines']:6d} "
            f"{('yes' if row['title_exact'] else 'no'):>6s}"
        )
        if args.verbose:
            for region in row["regions"]:
                mark = "ok " if region["exact"] else "FAIL"
                print(f"    {mark} {region['score']:.3f} truth={region['truth']!r} got={region['got']!r}")
    if scored:
        print(f"overall CER {total_errors / max(total_chars, 1):.3f} over {scored} fixture(s)")


if __name__ == "__main__":
    main()
