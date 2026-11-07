# SPDX-License-Identifier: GPL-3.0-or-later

"""Sanity probe: naive rectangle crops versus the perspective crop, through the reference recogniser.

Development tool. Given an image and x0,y0,x1,y1 it prints what the recogniser reads from
(a) the raw rectangle and (b) the same rectangle run through `rotate_crop`, which is how a broken
perspective transform is told apart from a wrong box.

    python tools/ocr-reference/probe_crop.py --image PATH --box x0,y0,x1,y1
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
    parser.add_argument("--image", default="app/src/androidTest/assets/cover.jpg")
    parser.add_argument("--box", default="51,165,254,197")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    x0, y0, x1, y1 = (int(v) for v in args.box.split(","))
    charset = ref.load_charset(Path(ref.DEFAULT_DICT))
    rec = ort.InferenceSession(str(ref.DEFAULT_MODELS / "rec.onnx"), providers=["CPUExecutionProvider"])
    image = cv2.imread(args.image, cv2.IMREAD_COLOR)

    def read(name: str, crop) -> None:
        tensor, width = ref.rec_preprocess(crop)
        probs = rec.run(None, {rec.get_inputs()[0].name: tensor})[0][0]
        text, score = ref.ctc_decode(probs, charset)
        print(f"{name:12s} {crop.shape[1]:4d}x{crop.shape[0]:<4d} -> {score:.3f} {text!r}")

    naive = image[y0:y1, x0:x1]
    read("naive", naive)

    rounded = np.array(
        [[x0, y0], [x1, y0], [x1, y1], [x0, y1]], dtype=np.float32
    )
    read("quad", ref.rotate_crop(image, rounded))
    cv2.imwrite("tools/ocr-reference/out/probe_naive.png", naive)
    cv2.imwrite("tools/ocr-reference/out/probe_quad.png", ref.rotate_crop(image, rounded))


if __name__ == "__main__":
    main()
