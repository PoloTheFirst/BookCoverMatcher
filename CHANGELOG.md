# Experimental Image Search Feature with Open Library

## v2.1.0 (2026-10-21)

## Added

* **Panel 2.5 · Online search**, off by default. Two deliberately separate steps: **A · Read the cover (OCR)** and **B · Search the catalogue (product discovery)**.
* **Embedded Python via Chaquopy 17.1.0** (CPython 3.12, ABIs `arm64-v8a` + `x86_64`). The original `chrome-lens-py` 3.8.2 package (MIT) is installed unmodified by the build and runs inside the app's own process — no companion PC, no local server, no backend, no FastAPI, no LAN service.
* **`app/src/main/python/lens_bridge.py`** — the only Python in the app. Wraps `LensAPI.process_image()` and returns plain values, including each text block's glyph height (used to spot the title).
* **`online/` package** — `OnlineSearchController` (orchestration + state), `PythonRuntime` (owns the interpreter, reports why it cannot start), `ChromeLensPyBridge` (Kotlin ↔ Python, maps Python exceptions to named `OcrFailures`), `OcrTextSource` (contract), `OpenLibraryBooksSource`, `HttpJsonClient` (the only socket in the app, HTTPS only), `CoverImageLoader` (memory-only cache).
* **`core/online/` package** — plain JVM and unit-tested: `OcrQueryExtractor` (OCR text → title/author query), `OpenLibraryResponseParser`, `ProductSearchResult`, `ProductSearchDataSource` + `ProductSearchOutcome` / `OnlineFailure`.
* **UI & Data components** — `data/OnlineSearchPrefs.kt`, `OnlineSearchUi.kt`, `ui/OnlineSearchPanel.kt`, `ui/ProductResultCard.kt`.
* **Product discovery through Open Library Books API** — free, no API key, structured records, and every link is built by the app from the record's own key, so no URL is ever invented.
* **Tooling scripts** — `tools/lens_poc.py` (desktop proof of concept for the Python package) and `tools/online_search_check.py` (issues the exact catalogue request the app sends).
* **17 new unit tests** (84 in total).

## Changed

* **`INTERNET` permission added**, used only by panel 2.5. The app made no request before this release and still makes none while the switch is off.
* **`AndroidImageCodec` gains `encodeJpeg()**` — OCR needs a legible picture, not the 224-pixel thumbnail.
* **`UiState` gains one field**, `online`; `ScreenActions` gains `onOpenUrl`.
* **Build configuration** — Chaquopy plugin 17.1.0 in `gradle/libs.versions.toml`; a CPython 3.12 on the build host is now required (see `README.md` → *Quick start*).

## Fixed

* **Online cover recognition always failed with "Could not read the cover."** Three defects found and recorded in `ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md`:
* A Java `byte[]` was rejected before any work was done. `lens_bridge.py` validated the incoming image with `isinstance(x, (bytes, bytearray, memoryview))`, but Chaquopy delivers a Java array as its own `java.chaquopy.jarray` type — a buffer-protocol object that is none of those three. Every call raised `TypeError`. The bridge now tests for the buffer protocol with `memoryview()` and copies with `.tobytes()`, which also maps Java's signed bytes back to unsigned. A bare `bytes(value)` would have been worse than the bug: `bytes(5)` is five NUL bytes.
* Result fields were read with `getattr()` instead of dictionary lookup. `PyObject.get(String)` is documented as "Equivalent to Python `getattr()`", returning `null` for a missing attribute, so every field of the reply read as `null` — the app would have said "no text found" about a cover full of text. Dictionary access now goes through `asMap()`.
* `Bitmap.compress()`'s return value was discarded, so a failed encode produced an empty `ByteArray` that surfaced much later as a generic OCR error. The result is now checked, alpha is flattened onto white before encoding, and the bytes are validated before Python sees them.


* **One generic message was hiding every failure category.** The bridge now returns a coded envelope and classifies `chrome_lens_py` errors by exception type rather than by matching text in a traceback, so each category gets its own actionable sentence. "Could not read the cover" survives only as the genuinely-unexpected case.
* **Encoding failures were silently swallowed** by `runCatching { … }.getOrNull()` in `MainViewModel`; they are now reported as encoding failures, distinctly from OCR failures.
* **Logging** — The bridge now logs to Logcat under `OnlineSearch`, including the Python traceback the UI deliberately hides. Image bytes and OCR text are never logged.
* **Empty scan handling** — An empty OCR result is now unambiguously a successful scan of a cover with no writing on it.

## Tests

* **26 new tests, 110 in total:** `OcrResultParserTest` (20) covers the whole Kotlin↔Python contract without an interpreter, and `EncodedImageTest` (6) covers the encoder-validation rule.
* **`tools/lens_bridge_check.py`** exercises the real Python bridge against a stubbed `LensAPI` — 40 assertions, no network.
* The 67 pre-existing offline tests are unchanged and still pass.

## Notes and known issues

* **`chrome-lens-py` provides no product search.** Verified against the shipped package: `process_image()` returns OCR text, word geometry, detected language, translation and a single whole-image objects entry — no products, prices or shopping links. Product discovery is therefore a separate service, and nothing pretends otherwise.
* **On-device runtime behaviour is not verified.** Verified: the build, the packaging of the interpreter and `chrome_lens_py` into both APKs, the library's real API against the live Lens endpoint, the Open Library request, and all pure-Kotlin rules. Not verified: the on-device round-trip — no device or emulator was available.
* **`protobuf` is pinned to 6.33.6:** the 7.x line ships only platform wheels, and 6.33.6 is the newest release in `chrome-lens-py`'s range with a pure-Python wheel Chaquopy can install.
* **Cancelling an online search** stops the Kotlin side at once; a Lens request already in flight inside Python runs to completion (Chaquopy cannot preempt a running call).
* **The APK grows by roughly 30 MB.**
