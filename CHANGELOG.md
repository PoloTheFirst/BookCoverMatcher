# Changelog

All notable changes to BookCover Matcher. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/) (the app's matching engine and platform changed, hence a major version).

## [2.0.0] — 2026-10-06

A native Android rewrite. Same functions as v1, a new matching engine, no online features, and a reworked UI.

### Added

- **Native Android app** — Kotlin, Jetpack Compose (Material 3), CameraX, ONNX Runtime Mobile.
  Replaces the WebView/HTML app. `minSdk 26`, portrait, ABIs `arm64-v8a` + `x86_64`.
- **OpenCLIP image matching.** Each cover (scan and workbook pictures) becomes a 512-dimensional
  OpenCLIP ViT-B-32 (LAION-2B) embedding, computed on-device with ONNX Runtime.
- **Exact inner-product index** (`FlatIpIndex`) with the semantics of `faiss.IndexFlatIP`
  (L2-normalised vectors, exact search, ranked by cosine). Verified for identical ranking against real FAISS.
- **Embedding cache.** Workbook embeddings are stored on disk, keyed by workbook SHA-256, range, model
  fingerprint and floating-image flag (last 8 kept). Repeat searches skip the embedding step.
- **Model tooling** — `tools/export_openclip_onnx.py` exports the OpenCLIP image tower to ONNX (int8 or fp32),
  verifies it against PyTorch, measures fp32-vs-int8 drift on your covers and refuses to write a model that
  drifted too far.
- **In-app model setup** — a setup screen to import the model file when it is not bundled in the APK.
- **`tools/benchmark.py`** — compares the v1 pHash pipeline with OpenCLIP (via real `faiss`) on your own
  catalogue using simulated phone photos; reports top-1 / top-5 / MRR, speed and score distributions.
- **`tools/make_sample_workbook.py`** — builds a sample catalogue with covers stored as cell-note picture fills.
- **Cancel button** while a search is running; progress labels *Reading spreadsheet…*, *Embedding images… i/N*, *Ranking…*.
- **Auto Scan feedback** — phase badge (*Hold still…*, *Too dark*, *Too bright*, *Stabilizing… n/3*,
  *Same cover — waiting*, *Captured ✓*), phase-coloured guide and corner brackets, scan line,
  stability pips, **Sharp / Light / Steady** chips, capture flash and haptic tick.
- **Tap-to-focus** on the camera preview.
- Camera starts automatically when permission is already granted and the model is ready.
- Wishlist: rename now uses an in-app dialog with Save / Cancel (v1 used the browser's `window.prompt`).
- `Config.EXTRACT_FLOATING_IMAGES` switch (see *Notes*).
- 67 JVM unit tests (`./gradlew testDebugUnitTest`).

### Changed

- **Matching engine: 64-bit pHash → OpenCLIP embeddings.** The score is now cosine similarity.
- **Similarity percentage is relative to your workbook**: `(cos − baseline) / (1 − baseline)` where the
  baseline is the median cosine between different pictures in the workbook (default 0.5 for fewer than 3).
  The "Hamming N/64" figure is replaced by the raw cosine.
- **Match levels** are now High ≥ 70 %, Mid ≥ 45 %, Low otherwise (was distance ≤ 5 / ≤ 12). These are
  heuristic starting points; tune `ScoreScale` with `tools/benchmark.py`.
- **Image preprocessing** — whole image, Pillow-compatible anti-aliased bicubic resize to 224×224,
  CLIP mean/std normalisation; transparent pictures flattened on white.
- **Thumbnails** are rendered at 2× (`THUMB_PIXEL_SCALE`) so they stay sharp on dense screens.
- **Wishlist storage** — `localStorage` (`bcm_wishlist_v1`) → `records.json` plus one JPEG per thumbnail in the
  app's private storage. Same fields, same `#N` default names, same `YYYY-MM-DD HH:MM` timestamps.
  v1 data is **not** migrated (it lives in a different app).
- **Auto Scan** — thresholds and order of checks unchanged. `graceFrames = 1`: a single bad frame no longer
  resets the stability streak.
- **UI refresh** — Material 3 components, gradient buttons, animated result rows, dark navy palette carried over
  from v1, edge-to-edge display with system-bar insets, larger touch targets.
- Excel files are copied to app cache once per selection and validated immediately.
- **Build toolchain:** Android Gradle Plugin **9.4.1** and Gradle **9.8.0** (JDK 17 or newer).

### Removed

- **All online searching** — the "2.5 · Online Search Results" panel, the search that v1 triggered after every
  capture (manual, auto or from file), and the `online.js` calls are gone, together with every network code path.
- **`INTERNET` permission** — the manifest now declares only `CAMERA`.
- **CDN fallbacks** for OpenCV, ExcelJS and JSZip (`OPENCV_URL`, `EXCELJS_URL`, `JSZIP_URL`).
- **Vendored JavaScript libraries** (OpenCV.js, ExcelJS, JSZip) and the WebView / `WebViewAssetLoader` setup.
- `WISHLIST_STORAGE_KEY` (storage is no longer `localStorage`).

### Fixed

- **Auto Scan armed state.** In v1 the saved "Auto on" preference was shown as *on* after a restart but the
  timer was never started. v2 arms Auto Scan immediately when the preference is on.

### Unchanged (by request)

- **The Excel workflow.** Cell-note pictures are read exactly as before: `xl/drawings/vmlDrawing*.vml`
  (case-insensitive, central-directory order), `<v:shape>` cell addresses from `<x:Row>` / `<x:Column>`,
  relationship-id lookup order (`v:fill o:relid` → `v:imagedata r:id` → `v:fill r:id` → `v:imagedata o:relid`),
  rels at `xl/drawings/_rels/<vml>.rels`, first-sheet floating pictures, 300-picture cap, first-address-wins
  de-duplication, case-insensitive `A1:B20` range syntax with optional `$`.
  The Kotlin port was compared with the original `excel.js` on 102 generated workbooks and gave identical results.
- Guide geometry (`GUIDE_W 0.70`, `GUIDE_H 0.78`), top-5 results, wishlist behaviour (green 5-second prompt with
  Remove / ✕, rename, expand, delete with confirmation) and the Auto Scan layout.

### Notes and known issues

- **Floating pictures.** You described the workflow as reading only pictures embedded in cell notes. The v1 code
  also reads anchored (floating) pictures of the first sheet. To keep the workflow identical this is preserved;
  set `Config.EXTRACT_FLOATING_IMAGES = false` for cell-note pictures only.
- **Unreviewed Android layer.** The plain-Kotlin core (`core/`) is compiled and covered by 67 unit tests. The
  Android layer (Compose UI, CameraX, ONNX Runtime wrapper, ViewModel, Gradle build) was written without a
  compiler (no access to Maven repositories) and did not receive an independent code review; it was then built
  with AGP 9.4.1 / Gradle 9.8.0.
- **OpenCLIP accuracy is unmeasured on real weights and covers**, as is int8 quantisation drift. On synthetic
  phone photos the v1 pHash baseline reached top-1 ≈ 44 % / top-5 ≈ 74 %. Run `tools/benchmark.py` on your
  catalogue to confirm the improvement.
- Volumes of one series with identical layouts can embed very closely; the right title is more likely to
  appear in the top 5 than at rank 1.
- The model (~97 MB int8) is not included in the repository; create it with `tools/export_openclip_onnx.py`.

## [1.0.0]

Initial WebView release: camera guide capture, 64-bit pHash (OpenCV.js with a pure-JS fallback), Excel
reading via ExcelJS and JSZip, Hamming-distance top-5 ranking, Auto Scan, local wishlist, and an online
search panel ("2.5 · Online Search Results").
