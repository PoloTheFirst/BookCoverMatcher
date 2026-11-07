# SPDX-License-Identifier: GPL-3.0-or-later

"""Runs the reference recogniser on pre-cropped images, to separate detection from recognition.

    python tools/ocr-reference/rec_on_crops.py <dir-with-pngs> [--models DIR] [--dict PATH]

For every `*.png` in the directory it prints the decoded text, so the crops written by
`cargo run --example eval -- --dump-crops DIR` can be scored by the Python recogniser. If the Python
recogniser reads a Rust crop correctly but the Rust run did not, the defect is in the recogniser
stage; if it also fails, the crop itself is wrong and the defect is in detection/geometry.
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("directory")
    parser.add_argument("--models", default=str(ref.DEFAULT_MODELS))
    parser.add_argument("--dict", default=str(ref.DEFAULT_DICT))
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    charset = ref.load_charset(Path(args.dict))
    rec = ort.InferenceSession(str(Path(args.models) / "rec.onnx"), providers=["CPUExecutionProvider"])
    for path in sorted(Path(args.directory).glob("*.png")):
        crop = cv2.imread(str(path), cv2.IMREAD_COLOR)
        tensor, _ = ref.rec_preprocess(crop)
        probs = rec.run(None, {rec.get_inputs()[0].name: tensor})[0][0]
        text, score = ref.ctc_decode(probs, charset)
        print(f"{path.name:14s} {crop.shape[1]:4d}x{crop.shape[0]:<4d} {score:.3f} {text!r}")


if __name__ == "__main__":
    main()
