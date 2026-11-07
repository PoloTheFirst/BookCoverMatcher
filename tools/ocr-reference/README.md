# OCR accuracy harness (`tools/ocr-reference`)

Development-only tooling for measuring PP-OCRv6 accuracy in `native/ocr`. Nothing here is packaged
in the APK, and no third-party cover image is committed.

## Why it exists

The user reported that PaddleOCR reads illustrated Japanese covers badly (substitutions, fragments, no
title) while Chrome Lens still returns something. "It compiles" and "it returns text" are not evidence,
so this harness produces the evidence: the same images through the same pinned models, scored against
hand-written ground truth, with each pipeline stage comparable on its own.

## Pieces

| File | Purpose |
|---|---|
| `make_fixtures.py` | Generates the deterministic synthetic fixture set + `truth.json` (hand-written text, language, boxes) |
| `reference_paddle.py` | Independent implementation of the **official PaddleOCR algorithm** (OpenCV preprocessing, DB post-processing, CTC decode) over the same `det.onnx`/`rec.onnx` |
| `reference_paddle.py --unclip/--limit-type/--det-order` | Switches one preprocessing variable at a time, so its effect can be measured instead of assumed |
| `metrics.py` | CER / exact-match / detection-recall scoring with documented Unicode normalisation |
| `run_matrix.ps1` | Runs the preprocessing matrix and prints one table per variant |
| `show_result.py` | Compact summary of any result JSON (reference or Rust) |
| `rec_on_crops.py` | Runs the reference recogniser on crops dumped by the Rust engine: separates detection from recognition |
| `probe_crop.py`, `probe_vertical.py`, `debug_rec.py` | Stage bisectors (naive crop vs perspective crop, rotation variants, preprocessing variants) |
| `../../native/ocr/examples/eval.rs` | Rust side: `cargo run --example eval -- --image X --json Y [--dump-crops DIR]` |

## Environment (measured on this machine)

* Python 3.14 with `onnxruntime 1.30.0`, `numpy 2.5.3`, `opencv-python 5.0.0`, `Pillow 12.3.0`
  (`pyclipper` and the `paddleocr` package are **not** installed; see the deviations below).
* Rust host runs need ONNX Runtime 1.20.0 and the packaged models:
  ```powershell
  $env:BC_OCR_MODELS = "app\src\main\assets\ocr\ppocrv6"
  $env:BC_OCR_DICT   = "native\ocr\assets\ppocrv6_dict.txt"
  $env:ORT_DYLIB_PATH = "$env:TEMP\ort120\onnxruntime-win-x64-1.20.0\lib\onnxruntime.dll"
  ```

## Reproduce

```powershell
python tools/ocr-reference/make_fixtures.py
powershell -ExecutionPolicy Bypass -File tools/ocr-reference/run_matrix.ps1   # reference, 4 variants
cd native\ocr
cargo run --example eval -- --image ..\..\tools\ocr-reference\out\fixtures\ja_vertical.png `
    --json ..\..\tools\ocr-reference\out\results\rust-after\ja_vertical.json
cd ..\..
python tools/ocr-reference/metrics.py --truth tools/ocr-reference/out/fixtures/truth.json `
    --results tools/ocr-reference/out/results/rust-after --label rust-after --verbose
```

## Deviations from the official code (documented, not hidden)

1. `paddleocr` is not installed here, so the reference is an independent implementation of the
   documented algorithm with the same pinned ONNX files — not the official package. Every stage was
   verified against the Rust engine at the recogniser boundary: the Python recogniser reads the Rust
   engine's dumped crops character-for-character identically (`rec_on_crops.py`).
2. `pyclipper` is not installed, so `unclip` expands the min-area rectangle's sides by
   `area * unclip_ratio / perimeter`. For a rectangle polygon — the min-area box is one — this is the
   same result. Unclip is **off by default** (`--unclip 0`) because that is what the Rust engine
   effectively crops; with `--unclip 1.4` the crops become looser and both pipelines read worse.
3. No crop rotation in either pipeline, so the vertical-column handling can be measured separately

## Measured results (this machine, 2026-11-07)

Fixture set: 7 synthetic covers — Japanese horizontal, Japanese vertical column, Japanese rotated,
Japanese scattered/decorative, Traditional Chinese, Simplified Chinese, English — rendered with Windows
fonts (`meiryo`, `msjh`, `msyh`, `arialbd`); ground truth hand-written in `make_fixtures.py`.

Reference pipeline, one variable at a time (overall CER, lower is better):

| Variant | det resize | det channels | overall CER |
|---|---|---|---|
| `ref-min-bgr` (PaddleOCR default) | short side → 736 | BGR | 0.161 |
| `ref-min-rgb` | short side → 736 | RGB | 0.172 |
| `ref-max-bgr` | long side → 736 (what the engine does) | BGR | 0.161 |
| `ref-max-rgb` | long side → 736 | RGB | **0.149** |

Conclusions these numbers actually support:

* **Resize policy is not the problem.** `min` and `max` score the same overall; the difference per
  fixture is one character here or there. The engine keeps `max` — its own long-standing behaviour and
  the one that reads the real Hobbit cover best — and does not adopt PaddleOCR's `min` default.
* **Channel order is a small, inconsistent effect.** RGB scored marginally better on this synthetic set
  (0.149 vs 0.161, about one character over ~40), while the official pipeline is BGR. The engine now
  feeds BGR to match the official preprocessing (`native/ocr/src/det.rs`) — a parity change, not an
  accuracy claim.

End-to-end, Rust engine before and after the crash-fix-9 changes (`rust-baseline` vs `rust-after`):

| Fixture | lang | baseline CER | after CER | after text |
|---|---|---|---|---|
| `ja_horizontal` | ja | 0.000 | 0.000 | `じれったい、2ギルR` |
| `ja_vertical` | ja | **1.000** | **0.000** | `縦書き小説` |
| `ja_rotated` | ja | 0.091 | 0.091 | `じれったい２` (full-width digit) |
| `ja_decorative` | ja | 0.000 | 0.200 | `じれつたい` |
| `zh_hant` | zh-Hant | 0.000 | 0.000 | `繁體中文測試封面` |
| `zh_hans` | zh-Hans | 0.000 | 0.000 | `简体中文测试封面` |
| `en` | en | 0.000 | 0.000 | `FINAL FANTASY SEVEN` / `Tifa Lockhart FAN BOOK` / `R-18` |
| **overall** | | 0.069 | **0.063** | |

The vertical column is the real defect found and fixed: a 73×347 column used to be squeezed into a
12-pixel-wide recogniser canvas and read as one stray character; a counter-clockwise quarter turn reads
it exactly. `probe_vertical.py` on that crop: as-is 0.074 `试验`, clockwise 0.313 `修青小站`,
**counter-clockwise 0.877 `縱書き小説`**, per-character cells exact `縦書き小説`.

## What the harness cannot tell you

* **No Chrome Lens baseline here.** The Python/Chaquopy path needs a device; Lens output is the user's
  report and is labelled as such in the crash-fix-9 report.
* **No result for the user's own cover.** It is copyrighted and was not supplied as a file, so the
  Tifa / FINAL FANTASY VII regression case is **blocked**, not passed.
* **Synthetic covers are cleaner than print.** They measure pipeline defects, not the model's real-world
  Japanese accuracy; absolute CER here is not a claim about real covers.

   (`probe_vertical.py`).
