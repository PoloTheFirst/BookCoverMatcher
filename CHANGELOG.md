# Changelog

## [2.5.0] — 2026-11-08

**Version code/name: 221 / 2.5.0.** The first stable release of the 2.5 line. It carries two OCR engines
(Chrome Lens and the native PP-OCRv6 medium engine, both selectable per READ THE COVER request), and this
release fixes four user-reported defects on top of that work. No feature was redesigned and no parsing,
matching or catalogue rule was changed.

### Added

* **Two OCR engines, chosen per request.** *READ THE COVER* can run through Chrome Lens (the embedded
  Python bridge) or through the native `PP-OCRv6` medium engine (`native/ocr`: Rust core plus the
  shipped ONNX Runtime), the choice is remembered, and a request never falls back to the other engine
  silently — `OcrEngineRoutingTest` pins the routing, the persistence across controller recreation and
  the absence of a fallback.
* **The PP-OCRv6 medium models ship inside the APK** (`assets/ocr/ppocrv6/`: detector 62,032,837 B,
  recogniser 76,554,979 B, dictionary 74,947 B), stored uncompressed and re-hashed out of the built
  archive, so the native engine works offline on a clean install.

### Fixed

* **The cache-clear confirmation is readable again.** *More → Clear online session cache* printed a
  mangled em dash between "cleared" and "saved": the Kotlin source had been read as Windows-1252 and
  written back as UTF-8, so the em dash (U+2014) survived as three double-encoded characters. The text is
  now `Session cache cleared — saved bookmarks and wishlist items are untouched.`, the sentence lives in
  `SessionCacheMessages.CLEARED`, and the same repair was applied to every other occurrence of the defect
  in `app/src` and `ONLINE_SEARCH.md` (109 lines across 4 files). A unit test pins the exact sentence,
  refuses the damaged characters and scans the packaged sources for the double-encoding signature so it
  cannot come back quietly.
* **A saved result profile keeps its original picture.** A profile used to keep only the 44×58 row
  thumbnail — and an online result profile kept no image at all — so opening a profile inside its folder
  showed no picture. The original captured or uploaded image is now JPEG-encoded at its own resolution
  (quality 92; `Config.MAX_IMAGE_DECODE` still bounds a picked photo) and stored as its own file in
  `originals/`, referenced by a nullable `originalImage` field, written atomically (temp file + rename)
  and deleted with the profile only when that profile owns it. Copying a folder duplicates the image
  rather than sharing one file. Profiles saved before this release have no reference and say so instead
  of showing a thumbnail in the original's place.
* **The opened profile shows that picture at the top.** The result-profile detail view inside a wishlist
  folder now draws the original image above the saved results and their metadata, aspect ratio preserved
  (`ContentScale.Fit`, never cropped into a tile), height capped at 240 dp, and tappable to open it full
  size. Folder and session rows keep their existing thumbnails.
* **Search → Add entire result profile** placeholder is `e.g. ブルアカ` (was `e.g. Doujinshi`).
* **Wishlist → Create folder** placeholder is `e.g. ブルアカ` (the `e.g. ` prefix had been lost). Both
  dialogs now read the same `BookmarkForm.FOLDER_NAME_PLACEHOLDER`, so they cannot drift apart again.

### Tests

* `OriginalProfileImageTest` (8 tests): the original is its own file with its own bytes, separate from
  the thumbnail; it survives a reopened repository; two profiles never share one; deleting a profile
  removes exactly its own files; a profile without an image still loads; a `records.json` written before
  the field loads; a storage failure leaves the library readable and records nothing; a copied folder
  gets its own copy of the file.
* `SessionCacheMessageTest` (5 tests): the exact sentence, no damaged characters, the packaged sources
  carry no double-encoded text, and the folder placeholder carries its `e.g. ` prefix.
* `BookmarkFormTest` updated for the new placeholder text.

### Verified

* Full JVM suite: **981 tests, 0 failures, 7 skipped** (see `README.md` §7 for the exact command).
* Emulator `Medium_Phone_API_37.0` (Android 17, x86_64): cache-clear text, both folder placeholders, and a
  camera-captured profile showing its original image at the top of its detail view, still there after a
  process restart. See `historical_prompt/v2.5.0-ag.md` for the scenario-by-scenario record.
* Release APK built with the packaged PP-OCRv6 assets and both ABIs; contents, version and signature
  reported in the same document.

### Known limitations

* The pipeline holds decoded pixels, not the camera's own file, so a saved original is a re-encoded JPEG
  rather than the untouched original file. A picked photo is bounded by `Config.MAX_IMAGE_DECODE`.
* Profiles saved before v2.5.0 have no original image; one cannot be recovered from a thumbnail, and the
  detail view says so instead of inventing one.

---

Older releases (`2.5.0-dev.*`, `2.4.0` and earlier) are recorded in the Git tags, the GitHub Releases
and the release commits themselves; this file describes the current stable release only.
