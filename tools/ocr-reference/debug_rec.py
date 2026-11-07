"""Bisects the recogniser preprocessing: which exact input does rec.onnx expect?

Development tool. It crops the largest detector region once, then feeds the *same crop* through
several plausible preprocessings and prints what the recogniser decodes for each, so a parity defect
can be identified instead of guessed.

    python tools/ocr-reference/debug_rec.py [--image PATH]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reference_paddle as ref  # noqa: E402


def build_variant(crop, *, order: str, pad: str, norm: str) -> np.ndarray:
    h, w = crop.shape[:2]
    img = crop if order == "bgr" else cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)
    ratio = w / float(h)
    img_w = min(max(int(ref.REC_HEIGHT * ratio), 1), ref.REC_WIDTH)
    if pad == "stretch":
        resized = cv2.resize(img, (ref.REC_WIDTH, ref.REC_HEIGHT))
        img_w = ref.REC_WIDTH
    else:
        resized = cv2.resize(img, (img_w, ref.REC_HEIGHT))
    canvas = np.zeros((ref.REC_HEIGHT, ref.REC_WIDTH, 3), dtype=np.float32)
    if norm == "pm1":
        body = (resized.astype(np.float32) / 255.0 - 0.5) / 0.5
        canvas[:, :img_w, :] = body
    elif norm == "01":
        canvas[:, :img_w, :] = resized.astype(np.float32) / 255.0
    elif norm == "imagenet":
        body = resized.astype(np.float32) / 255.0
        body = (body - ref.DET_MEAN) / ref.DET_STD
        canvas[:, :img_w, :] = body
    return canvas.transpose(2, 0, 1)[None]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", default="app/src/androidTest/assets/cover.jpg")
    parser.add_argument("--models", default=str(ref.DEFAULT_MODELS))
    parser.add_argument("--dict", default=str(ref.DEFAULT_DICT))
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    charset = ref.load_charset(Path(args.dict))
    det = ort.InferenceSession(str(Path(args.models) / "det.onnx"), providers=["CPUExecutionProvider"])
    rec = ort.InferenceSession(str(Path(args.models) / "rec.onnx"), providers=["CPUExecutionProvider"])
    dtype = rec.get_inputs()[0].type
    shape = rec.get_inputs()[0].shape
    print(f"rec input {rec.get_inputs()[0].name} type={dtype} shape={shape}")

    image = cv2.imread(args.image, cv2.IMREAD_COLOR)
    tensor, src_shape, ratio, det_size = ref.det_preprocess(image)
    pred = det.run(None, {det.get_inputs()[0].name: tensor})[0][0][0]
    boxes = ref.db_postprocess(pred, src_shape, ratio)
    boxes.sort(key=lambda b: -abs((b[1][0] - b[0][0]) * (b[2][1] - b[1][1])))
    crop = ref.rotate_crop(image, boxes[0])
    cv2.imwrite("tools/ocr-reference/out/debug_crop.png", crop)
    print(f"largest crop {crop.shape[1]}x{crop.shape[0]} -> tools/ocr-reference/out/debug_crop.png")

    variants = [
        ("bgr", "pad", "pm1"),
        ("rgb", "pad", "pm1"),
        ("bgr", "stretch", "pm1"),
        ("bgr", "pad", "01"),
        ("bgr", "pad", "imagenet"),
    ]
    for order, pad, norm in variants:
        data = build_variant(crop, order=order, pad=pad, norm=norm)
        probs = rec.run(None, {rec.get_inputs()[0].name: data})[0][0]
        text, score = ref.ctc_decode(probs, charset)
        top = probs.max(axis=1)
        print(f"{order:4s} {pad:8s} {norm:9s} score={score:.3f} mean_top1={top.mean():.3f} {text!r}")


if __name__ == "__main__":
    main()
