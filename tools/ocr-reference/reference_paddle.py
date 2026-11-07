# SPDX-License-Identifier: GPL-3.0-or-later

"""Reference PP-OCRv6 medium pipeline, implementing the official PaddleOCR algorithm.

This is the accuracy *yardstick* for `native/ocr`, not a shipped component. It uses the very same
pinned ONNX files as the app and reproduces the official pre/post-processing with OpenCV, which is
what PaddleOCR itself uses:

    det:  DecodeImage(BGR) -> DetResizeForTest(limit_side_len=736, limit_type=min)
          -> NormalizeImage(1/255, mean, std) -> ToCHWImage -> det.onnx
          -> DBPostProcess(thresh 0.2, box_thresh 0.45, unclip_ratio 1.4, min_size 3)
    rec:  get_rotate_crop_image -> RecResizeImg([3,48,320]) -> rec.onnx -> CTCLabelDecode

Documented deviations from the official code (none of them affect what is compared here):
  * `pyclipper` is not installed, so `unclip` expands the min-area rectangle's sides by
    `area * unclip_ratio / perimeter` instead of offsetting the polygon. For a rectangle polygon —
    which the min-area box is — the two are identical.
  * crops are not rotated to landscape (the official predictor does not either, and the Rust engine
    does not, so the comparison stays stage-for-stage).

    python tools/ocr-reference/reference_paddle.py --image <png/jpg> [--json out.json]

Model and dictionary paths default to the app's packaged assets (`app/src/main/assets/ocr/ppocrv6`).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODELS = ROOT / "app" / "src" / "main" / "assets" / "ocr" / "ppocrv6"
DEFAULT_DICT = ROOT / "native" / "ocr" / "assets" / "ppocrv6_dict.txt"

DET_LIMIT_SIDE = 736
DET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
DET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
DB_THRESH = 0.2
DB_BOX_THRESH = 0.45
DB_UNCLIP_RATIO = 1.4
DB_MIN_SIZE = 3.0
DB_MAX_CANDIDATES = 3000
REC_HEIGHT = 48
REC_WIDTH = 320


def load_charset(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    chars = text.split("\n")
    if chars and chars[-1] == "":
        chars.pop()
    chars = [c[:-1] if c.endswith("\r") else c for c in chars]
    return ["blank"] + chars + [" "]


def det_preprocess(image: np.ndarray, limit_type: str = "min", order: str = "bgr"):
    """DetResizeForTest + NormalizeImage.

    `limit_type="min"` is PaddleOCR's default (`DetResizeForTest(limit_side_len=736, limit_type=min)`):
    the *short* side is brought to 736, so a portrait cover is upscaled. `"max"` brings the long side
    to 736, which is what `native/ocr` currently does. `order` selects whether the tensor is built from
    BGR (official `DecodeImage(img_mode='BGR')`) or RGB, so the effect of the channel order can be
    measured instead of assumed.
    """
    h, w = image.shape[:2]
    side = min(h, w) if limit_type == "min" else max(h, w)
    ratio = DET_LIMIT_SIDE / float(side) if side < DET_LIMIT_SIDE else 1.0
    if limit_type == "max" and side <= DET_LIMIT_SIDE:
        ratio = 1.0
    resize_h = max(int(round(h * ratio / 32) * 32), 32)
    resize_w = max(int(round(w * ratio / 32) * 32), 32)
    resized = cv2.resize(image, (resize_w, resize_h))
    if order == "rgb":
        resized = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
    data = resized.astype(np.float32) / 255.0
    data = (data - DET_MEAN) / DET_STD
    return data.transpose(2, 0, 1)[None].astype(np.float32), (h, w), ratio, (resize_w, resize_h)


def box_score_fast(prob: np.ndarray, box: np.ndarray) -> float:
    h, w = prob.shape[:2]
    box = box.copy()
    xmin = int(np.clip(np.floor(box[:, 0].min()), 0, w - 1))
    xmax = int(np.clip(np.ceil(box[:, 0].max()), 0, w - 1))
    ymin = int(np.clip(np.floor(box[:, 1].min()), 0, h - 1))
    ymax = int(np.clip(np.ceil(box[:, 1].max()), 0, h - 1))
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    shifted = box.copy()
    shifted[:, 0] -= xmin
    shifted[:, 1] -= ymin
    cv2.fillPoly(mask, shifted.reshape(1, -1, 2).astype(np.int32), 1)
    return float(prob[ymin : ymax + 1, xmin : xmax + 1][mask == 1].mean())


def get_mini_boxes(contour: np.ndarray):
    """PaddleOCR's `get_mini_boxes`: min-area rectangle as [top-left, top-right, bottom-right, bottom-left].

    The corner order matters: `rotate_crop` maps TL→(0,0), TR→(w,0), BR→(w,h), BL→(0,h). The
    two-index form below picks the upper of the two left points as TL and the upper of the two right
    points as TR; a naive `sorted(points, key=x)` order can hand back [TL, BL, TR, BR] instead and
    then every crop is sheared.
    """
    bounding_box = cv2.minAreaRect(contour)
    points = sorted(list(cv2.boxPoints(bounding_box)), key=lambda x: x[0])
    index_1, index_2, index_3, index_4 = 0, 1, 2, 3
    if points[1][1] > points[0][1]:
        index_1, index_4 = 0, 1
    else:
        index_1, index_4 = 1, 0
    if points[3][1] > points[2][1]:
        index_2, index_3 = 2, 3
    else:
        index_2, index_3 = 3, 2
    box = np.array([points[index_1], points[index_2], points[index_3], points[index_4]])
    return box, (float(bounding_box[1][0]), float(bounding_box[1][1]))


def unclip(box: np.ndarray, ratio: float) -> np.ndarray:
    """pyclipper-equivalent offset of the min-area rectangle."""
    rect = cv2.minAreaRect(box.reshape(-1, 1, 2).astype(np.float32))
    (cx, cy), (rw, rh), angle = rect
    area = float(rw * rh)
    perimeter = 2.0 * (float(rw) + float(rh))
    if perimeter <= 0:
        return box
    distance = area * ratio / perimeter
    return cv2.boxPoints(((cx, cy), (rw + 2 * distance, rh + 2 * distance), angle))


def db_postprocess(pred: np.ndarray, src_shape, ratio: float, unclip_ratio: float = 0.0) -> list[np.ndarray]:
    h, w = src_shape
    bitmap = (pred > DB_THRESH).astype(np.uint8)
    contours, _ = cv2.findContours(bitmap, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for contour in contours[:DB_MAX_CANDIDATES]:
        if len(contour) < 4:
            continue
        score = box_score_fast(pred, contour.reshape(-1, 2))
        if score < DB_BOX_THRESH:
            continue
        box, sside = get_mini_boxes(contour)
        if min(sside) < DB_MIN_SIZE:
            continue
        if unclip_ratio > 0.0:
            box = unclip(box, unclip_ratio)
            box, sside = get_mini_boxes(box.reshape(-1, 1, 2).astype(np.float32))
            if min(sside) < DB_MIN_SIZE + 2:
                continue
        box[:, 0] = np.clip(np.round(box[:, 0] / ratio), 0, w)
        box[:, 1] = np.clip(np.round(box[:, 1] / ratio), 0, h)
        boxes.append(box.astype(np.float32))
    return boxes


def rotate_crop(image: np.ndarray, points: np.ndarray):
    p = points.astype(np.float32)
    crop_w = int(max(np.linalg.norm(p[0] - p[1]), np.linalg.norm(p[2] - p[3])))
    crop_h = int(max(np.linalg.norm(p[0] - p[3]), np.linalg.norm(p[1] - p[2])))
    if crop_w < 2 or crop_h < 2:
        return None
    dst = np.float32([[0, 0], [crop_w, 0], [crop_w, crop_h], [0, crop_h]])
    matrix = cv2.getPerspectiveTransform(p, dst)
    return cv2.warpPerspective(
        image, matrix, (crop_w, crop_h), borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC
    )


def rec_preprocess(crop: np.ndarray):
    h, w = crop.shape[:2]
    ratio = w / float(h)
    img_w = min(max(int(REC_HEIGHT * ratio), 1), REC_WIDTH)
    resized = cv2.resize(crop, (img_w, REC_HEIGHT))
    normalised = (resized.astype(np.float32) / 255.0 - 0.5) / 0.5
    canvas = np.zeros((REC_HEIGHT, REC_WIDTH, 3), dtype=np.float32)
    canvas[:, :img_w, :] = normalised
    return canvas.transpose(2, 0, 1)[None], img_w


def ctc_decode(probs: np.ndarray, charset: list[str]):
    indices = probs.argmax(axis=1)
    scores = probs.max(axis=1)
    out, kept, previous = [], [], -1
    for index, score in zip(indices, scores):
        if index != 0 and index != previous:
            out.append(charset[index])
            kept.append(float(score))
        previous = index
    text = "".join(out)
    return text, (float(np.mean(kept)) if kept else 0.0)


def run(
    image_path: Path,
    models: Path,
    dictionary: Path,
    unclip_ratio: float = 0.0,
    limit_type: str = "min",
    det_order: str = "bgr",
) -> dict:
    charset = load_charset(dictionary)
    det = ort.InferenceSession(str(models / "det.onnx"), providers=["CPUExecutionProvider"])
    rec = ort.InferenceSession(str(models / "rec.onnx"), providers=["CPUExecutionProvider"])

    image = cv2.imread(str(image_path), cv2.IMREAD_COLOR)
    if image is None:
        raise SystemExit(f"cannot decode {image_path}")
    height, width = image.shape[:2]
    tensor, src_shape, ratio, det_size = det_preprocess(image, limit_type, det_order)
    pred = det.run(None, {det.get_inputs()[0].name: tensor})[0][0][0]
    boxes = db_postprocess(pred, src_shape, ratio, unclip_ratio)

    lines = []
    for box in boxes:
        crop = rotate_crop(image, box)
        if crop is None:
            continue
        rec_in, _ = rec_preprocess(crop)
        probs = rec.run(None, {rec.get_inputs()[0].name: rec_in})[0][0]
        text, score = ctc_decode(probs, charset)
        lines.append({"text": text, "score": round(score, 4), "box": box.astype(float).tolist()})
    return {
        "engine": "reference-paddleocr-python",
        "image": str(image_path),
        "image_size": [width, height],
        "det": {
            "limit_side": DET_LIMIT_SIDE,
            "limit_type": "min",
            "input": list(det_size),
            "map": [int(pred.shape[1]), int(pred.shape[0])],
            "regions": len(boxes),
        },
        "lines": lines,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True)
    parser.add_argument("--models", default=str(DEFAULT_MODELS))
    parser.add_argument("--dict", default=str(DEFAULT_DICT))
    parser.add_argument("--json", default="")
    parser.add_argument(
        "--unclip",
        type=float,
        default=0.0,
        help="DB unclip_ratio; 0 keeps the tight min-area box, which is what the Rust engine crops",
    )
    parser.add_argument("--limit-type", default="min", choices=["min", "max"])
    parser.add_argument("--det-order", default="bgr", choices=["bgr", "rgb"])
    args = parser.parse_args()
    result = run(
        Path(args.image), Path(args.models), Path(args.dict), args.unclip, args.limit_type, args.det_order
    )
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.json:
        Path(args.json).write_text(text, encoding="utf-8")
        print(f"{args.image}: {len(result['lines'])} line(s) -> {args.json}")
        for line in result["lines"]:
            print(f"  {line['score']:.3f}  {line['text']}")
    else:
        print(text)


if __name__ == "__main__":
    main()
