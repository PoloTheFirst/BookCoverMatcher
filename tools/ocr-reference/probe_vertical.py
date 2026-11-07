"""Probes how a vertical Japanese column should be fed to the recogniser.

    python tools/ocr-reference/probe_vertical.py --crop <png> [--models DIR] [--dict PATH]

Tries the crop as-is, rotated 90° clockwise, rotated 90° counter-clockwise, rotated 180°, and
finally character-by-character (splitting the column into square cells, which is how vertical CJK is
laid out). Prints what the recogniser reads for each, so the pipeline can adopt the variant that
actually works instead of assuming one.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reference_paddle as ref


def decode(rec, charset, crop) -> tuple[str, float]:
    if crop is None or crop.size == 0:
        return "", 0.0
    tensor, _ = ref.rec_preprocess(crop)
    probs = rec.run(None, {rec.get_inputs()[0].name: tensor})[0][0]
    return ref.ctc_decode(probs, charset)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--crop", required=True)
    parser.add_argument("--models", default=str(ref.DEFAULT_MODELS))
    parser.add_argument("--dict", default=str(ref.DEFAULT_DICT))
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    charset = ref.load_charset(Path(args.dict))
    rec = ort.InferenceSession(str(Path(args.models) / "rec.onnx"), providers=["CPUExecutionProvider"])
    crop = cv2.imread(args.crop, cv2.IMREAD_COLOR)
    print(f"crop {crop.shape[1]}x{crop.shape[0]}")
    variants = {
        "asis": crop,
        "cw": cv2.rotate(crop, cv2.ROTATE_90_CLOCKWISE),
        "ccw": cv2.rotate(crop, cv2.ROTATE_90_COUNTERCLOCKWISE),
        "180": cv2.rotate(crop, cv2.ROTATE_180),
    }
    for name, variant in variants.items():
        text, score = decode(rec, charset, variant)
        print(f"  {name:5s} {score:.3f} {text!r}")

    # Character cells: a vertical column of n characters is n cells tall.
    h, w = crop.shape[:2]
    cells = max(1, round(h / max(w, 1)))
    texts = []
    for index in range(cells):
        y0 = int(index * h / cells)
        y1 = int((index + 1) * h / cells)
        text, _ = decode(rec, charset, crop[y0:y1])
        texts.append(text.strip())
    print(f"  cells ({cells}) {''.join(texts)!r}")


if __name__ == "__main__":
    main()
