# Third-party notices — BookCoverMatcher

BookCoverMatcher's own code is licensed under **GPL-3.0-or-later** (see `LICENSE`). The components below
are **not** covered by that licence: they keep their own terms, and nothing in this repository's
licence changes them.

Status legend: **Verified** = confirmed from the artifact, its metadata or the upstream project during
this audit; **Unresolved** = could not be established in this environment and needs a decision by the
project owner before distribution; **Flagged** = third-party content whose redistribution the project
cannot grant licences for.

## 1 · Bundled in the Android application

| Component | Version | Licence | Where it ships | Status / notes |
|---|---|---|---|---|
| ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`) | 1.20.0 | MIT | `libonnxruntime.so` (arm64-v8a, x86_64) + Java API, inside the APK | **Verified** (Microsoft's published licence). Attribution retained here. |
| PaddleOCR PP-OCRv6 medium detector `det.onnx` | revision `6132380…` | Apache-2.0 | `assets/ocr/ppocrv6/det.onnx` inside the APK | **Verified** (PaddlePaddle model release). |
| PaddleOCR PP-OCRv6 medium recogniser `rec.onnx` | revision `50c7eac…` | Apache-2.0 | `assets/ocr/ppocrv6/rec.onnx` | **Verified** |
| PaddleOCR recognition dictionary `ppocrv6_dict.txt` | 18,708 entries, git blob `270082aa…` | Apache-2.0 | `native/ocr/assets/ppocrv6_dict.txt` + APK asset | **Verified** |
| OpenCLIP code (`open_clip_torch`) | 3.3.0 | MIT | **Build time only** — `tools/export_openclip_onnx.py`; not shipped | **Verified** |
| OpenCLIP weights `ViT-B-32` / `laion2b_s34b_b79k`, quantised here to int8 ONNX | upstream repo `laion/CLIP-ViT-B-32-laion2B-s34B-b79K` @ `1a25a446`; our file sha256 `d0ee5e32…`, 96,919,211 B | **MIT** (declared by LAION: `cardData.license = "mit"`, and the model card shows "License: mit") | `assets/models/openclip_image.onnx` inside the APK | **Verified.** MIT allows redistribution and derivative works, so the int8 conversion is distributable - but the weights are **MIT, not GPL**: keep the LAION attribution and ship this notice with the APK (both are done). The LAION-2B *dataset* has its own terms; only the weights' declared licence is relied on. Conversion: `tools/export_openclip_onnx.py` (open_clip 3.3.0, torch 2.14.1), image tower only, 224x224 input, 512-d output, no calibration data. |
| `chrome-lens-py` | 3.8.2 | MIT | Python wheel inside the APK (Chaquopy) | **Verified** (PyPI metadata: "MIT License") |
| `protobuf` (Python) | 6.33.6 | BSD-3-Clause | Python wheel inside the APK | **Verified** (PyPI metadata) |
| Chaquopy (Gradle plugin + runtime) | 17.1.0 | MIT | Build plugin + runtime in the APK | **Unresolved (minor).** The published POM carries no licence block, so MIT rests on Chaquopy's own documentation; confirm against the version you redistribute. |
| AndroidX: Compose BOM 2024.10.01, activity-compose 1.9.3, lifecycle 2.8.7, CameraX 1.3.4, exifinterface 1.3.7 | as listed | Apache-2.0 | Inside the APK | **Verified** |
| Kotlin standard library 2.2.10, `kotlinx-coroutines-android` 1.9.0 | as listed | Apache-2.0 | Inside the APK | **Verified** |
| JUnit 4.13.2 | 4.13.2 | EPL-1.0 | Test classpath only | **Verified**; not in the release APK |
| `androidx.test:runner` 1.6.2, `androidx.test.ext:junit` 1.2.1 | as listed | Apache-2.0 | Test APK only | **Verified**; the test APK is not distributed |

## 2 · Build-time tools (not distributed)

| Component | Version | Licence | Notes |
|---|---|---|---|
| Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) | 9.8.0 | Apache-2.0 | Gradle's own files, kept verbatim and **excluded** from SPDX headers for that reason |
| Android Gradle Plugin | 9.5.1 | Apache-2.0 | Build only |
| Kotlin Gradle plugin | 2.2.10 | Apache-2.0 | Build only |
| Python desktop tooling (`tools/requirements.txt`) | unpinned | BSD-3-Clause / MIT / Apache-2.0 per package | Developer machines only; never packaged |

## 3 · Rust dependencies (`native/ocr/Cargo.lock`)

65 packages resolve from the lockfile. Licences below were read from the crates' own metadata
(`cargo metadata --offline`), not guessed. Every resolved licence is permissive and GPLv3-compatible:
MIT, Apache-2.0, `Apache-2.0 OR MIT`, BSD-2-Clause, BSD-3-Clause, ISC, Zlib, `Unlicense OR MIT`,
`0BSD OR MIT OR Apache-2.0`.

Regenerate the list with:

```powershell
cd native\ocr
cargo metadata --format-version 1 --offline | ConvertFrom-Json |
    Select-Object -ExpandProperty packages |
    Select-Object name, version, license | Sort-Object name
```

`native/ocr/Cargo.toml` now declares `license = "GPL-3.0-or-later"` for BookCoverMatcher's own crate

## 4 · Test fixtures — every one of them is generated

Three assets were carried for a long time without any established redistribution right. They have now
been replaced by generated files at the **same paths**, so the tests keep exercising the same production
code and no distributed artefact contains the third-party material.

| Path | What it was | What it is now | Produced by |
|---|---|---|---|
| `app/src/androidTest/assets/cover.jpg` | A photograph of a published book cover, copyright its publisher | A generated 600x850 synthetic cover: flat colour blocks, a border and large Latin text (`SYNTHETIC TEST COVER`, `BOOK COVER MATCHER`, `NOT A REAL BOOK`). No third-party artwork, still a real JPEG the OCR device tests can read | `tools/make-synthetic-fixtures.py` (Pillow) |
| `test_excel/doujin.xlsx` | A 40 MB personal catalogue workbook | **No longer in the repository.** The owner keeps the file locally and it is ignored, so the workbook tests still run on this machine and skip everywhere else. A compact generated workbook (`test_excel/synthetic_catalog.xlsx`, ~4.8 KB) carries the structure the reader needs: a `LIST` sheet with the 24 columns A-X, a header row and three records, date columns as serial numbers, a hyperlink, an embedded PNG behind a drawing anchor and a comment part | `tools/make-synthetic-fixtures.py` |
| `app/src/test/resources/fixtures/dlsite_books_search.html`, `dlsite_books_empty.html` | Captured DLsite search pages | Generated pages carrying the same DOM classes the production parser matches (`data-list_item_product_id`, `work_name`, `maker_name`, `work_price_base`/`_suffix`, the 240x240 thumbnail candidate) with invented ids (`RJ01000001`...), titles, circles and prices | `tools/make-synthetic-fixtures.py` |
| `app/src/test/resources/fixtures/melonbooks_search.html`, `melonbooks_empty.html` | Captured Melonbooks search pages | Generated pages with `item-list`, `product_<id>`, `item-ttl product_title`, `search-item-author-author`, `item-price` and the `data-src` resize_image URL, with invented ids, titles, circles, artists and prices, and `&amp;` kept in one title so the entity decoder is still tested | `tools/make-synthetic-fixtures.py` |

Every generated file carries the marker `Synthetic fixture, GPL-3.0-or-later` (or, for the binary
fixtures, is pinned by sha256 in `tools/check-licenses.py`) and is covered by the project's own
GPL-3.0-or-later terms like the rest of the tests. No page text, description, price history, tracking
markup or personal catalogue data is reproduced.

The migrated historical tag snapshots carry the same generated fixtures at the same paths: each tag's
additive licensing commit swaps the flagged blobs, so no released snapshot distributes them either.

## 5 · What GPL-3.0-or-later covers here, and what it does not

* **Covered:** every line written for this project — Kotlin, Rust, the Python bridge, the
  PowerShell/Python tooling, the Compose UI, the tests and the documentation.
* **Not covered:** everything in §1–§4. A GPLv3 licence on the project cannot grant rights in someone
  else's model weights, cover photographs or workbook data.

`LICENSE` and this file are packaged into the APK (`assets/licenses/`) and shown in
**More → Open-source licences**, so a binary distribution carries the same statements as the source.

(`bc_ocr`), which is why it appears without a third-party licence.
