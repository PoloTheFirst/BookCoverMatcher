# Online search (panel 2.5)

How the optional online feature works, what it really uses, and what it cannot do.

* For the offline matcher, see [README.md](README.md).

---

## 1. What it does

A new panel, **2.5 · Online search**, sits between the workbook results and the wishlist. It is
**off by default** and everything in it is inert until you switch it on.

1. You scan a cover as usual (camera, Auto Scan, or a picked photo).
2. **A · Read the cover** — the captured picture is handed to the embedded
   `chrome-lens-py` package, which asks Google Lens for the text printed on the cover.
3. The recognised text is reduced to a search query (a title, plus an author when one is
   recognisable). The query is shown and is **editable** — OCR gets covers wrong and you know better.
4. **B · Search the catalogue** — the query is looked up in the **Open Library Books API** and the
   structured records are shown as cards: title, authors, year, publisher, languages, cover, and a
   link to the catalogue's own page.

The offline workflow is untouched: same capture, same embedding, same workbook matching, same
wishlist, and it all still works with the switch off and the phone in airplane mode.

---

## 2. The two halves are deliberately different services

| | A · OCR | B · Product search |
| :--- | :--- | :--- |
| Library / service | `chrome-lens-py` 3.8.2 (embedded, MIT) → Google Lens `crupload` | Open Library Books API |
| Runs | in-process Python (Chaquopy 17.1.0, CPython 3.12) | `HttpURLConnection` from Kotlin |
| Returns | text, per-word geometry, detected language, translation | title, authors, year, publisher, pages, languages, cover, page URL |
| Kotlin contract | `online/OcrTextSource.kt` | `core/online/ProductSearchDataSource.kt` |
| Implementation | `online/ChromeLensPyBridge.kt` | `online/OpenLibraryBooksSource.kt` |

### Why they are not the same call

**`chrome-lens-py` has no product search.** Its own README and API reference describe OCR,
translation, translation-drawn-onto-the-image and word geometry. This was verified against the
shipped package, not assumed: `LensAPI.process_image()` returns exactly

```
detected_language  image_size  objects  ocr_text | text_blocks | line_blocks | detailed_blocks
overlay_path  translated_text  translation_render_data  word_data
```

`objects` is a single `DetectedObject-WholeImage` bounding box — not a product match. There is no
shopping, price, or visual-search result anywhere in the response, so nothing in this app pretends
otherwise. The `SUPPORTS` / `DOES_NOT_SUPPORT` lists in `app/src/main/python/lens_bridge.py` say so
in the code, and the panel shows "OCR only, no product search".

Product discovery therefore uses a **separate, permitted source**.

### Why Open Library

* Free, **no API key**, no account, no secret to ship in the APK.
* Returns *structured* records, so the cards are real metadata rather than invented text.
* A library catalogue, not a shop — it never shows a price we would have to make up. Every result
  links to a page that exists on openlibrary.org, built by us from the record's own key.
* Its data is published under the Open Data License / public domain.

Verified live with `python tools/online_search_check.py`, which issues the exact request the app
builds and checks the response against the same rules the Kotlin parser applies.

**Limitation worth stating:** Open Library describes *books*. This app matches book covers, so that
is a good fit, but it is not a shop: there are no prices, no stock, and no buy links. If you need
those, this feature will not give them to you, and no source is being silently substituted to fake it.

---

## 3. Embedded Python: what was verified

| Question | Answer | How it was checked |
| :--- | :--- | :--- |
| Can the original package run on Android? | Yes, unmodified | `pip install chrome-lens-py==3.8.2` into the Chaquopy build; `chrome_lens_py` (84 files) confirmed inside `assets/chaquopy/requirements-common.imy` in the built APK |
| Version | **3.8.2** (pinned) | PyPI metadata |
| License | **MIT** | PyPI metadata + `LICENSE` in the wheel |
| Python required | `>=3.9` | `requires_python` |
| Native extensions in the package itself | **none** — `chrome_lens_py-3.8.2-py3-none-any.whl` | wheel filename + contents |
| Native extensions in dependencies | **Pillow** only | Chaquopy ships a prebuilt `pillow-11.0.0-0-cp312-cp312-android_24_{arm64_v8a,x86_64}.whl`; confirmed packaged in `requirements-arm64-v8a.imy` |
| Runtime | **Chaquopy 17.1.0**, CPython **3.12**, ABIs `arm64-v8a` + `x86_64` | Chaquopy supports AGP 7.3–9.4.x; this project is AGP 9.4.1 / Gradle 9.8.0 / minSdk 26 — all inside its stated range |
| Needs a companion device or server? | **No.** The interpreter runs in the app's own process | `Python.start(AndroidPlatform(context))` in `online/PythonRuntime.kt` |

### protobuf pin

`chrome-lens-py` asks for `protobuf>=6.33.5,<8`. The 7.x line publishes only platform-specific
wheels, which Chaquopy cannot install for Android. **6.33.6 is the newest release in the allowed
range that still ships a pure-Python `py3-none-any` wheel**, so it is pinned explicitly in
`app/build.gradle.kts`.

### Build-host requirement

Chaquopy needs a **CPython 3.12** on the machine that builds the app — its major.minor must match
`chaquopy.defaultConfig.version`. 3.12 is also the newest target with a *prebuilt* Pillow wheel,
which is why the app targets 3.12 rather than 3.14.

Tell Chaquopy where it is, in the git-ignored `local.properties`:

```properties
chaquopy.buildPython=C:/path/to/Python/3.12/python.exe
```

or pass `-PchaquopyBuildPython=<path>`. With neither, Chaquopy falls back to `py -3.12` on `PATH`.

---

## 4. Privacy

* `INTERNET` is now declared, and it is the only new permission. It exists **only** for this panel.
* The panel is **off by default**. The app made no request at all before this feature and still
  makes none while the switch is off.
* Exactly three hosts are ever contacted: the Lens endpoint (via `chrome-lens-py`),
  `openlibrary.org` and `covers.openlibrary.org`. `HttpJsonClient` refuses any non-`https` URL.
* The **cover photo itself** is sent to Google Lens when you press *Read cover text*. That is
  unavoidable — it is what OCR is.
* Only the text you can see in the query field is sent to Open Library. The workbook is never sent
  anywhere.
* Cover thumbnails are cached **in memory only** and are never written to disk.
* Catalogue links open in the system browser. The app renders no third-party page itself.
* `chrome-lens-py` uses a built-in public API key; you do not need an account. Its README states
  the project is for "educational and experimental purposes" and that use of Google's services must
  comply with their Terms of Service — worth reading before you rely on it.

---


## 5. Failure handling

Every failure has a name, and none of them is presented as a success. Reporting is in two tiers:
a short **toast** naming the category, and the full reason in the panel. The Python traceback goes
only to Logcat (`adb logcat -s OnlineSearch:V python.stderr:V`), never to the screen, and image
bytes and OCR text are never logged at all.

| Situation | Toast | Panel / log |
| :--- | :--- | :--- |
| Python runtime cannot start | *Python runtime unavailable* | red dot + the real reason; the rest of the app keeps working |
| `chrome_lens_py` cannot be imported | *OCR library not installed* | the import error, which names the module that failed |
| Cover could not be encoded to JPEG | *Could not read that picture* | the encoder's own reason — **never** reported as a Lens problem |
| Empty or oversized image reaches Python | *Could not read that picture* | rejected before any request is made |
| No network / DNS failure | *No connection to Google Lens* | "…check the connection — offline matching still works." |
| Lens 429 / 503 | *Google Lens is busy* | "Try again in a minute." |
| Lens rejects the key | *Google Lens refused the request* | "…not something you can fix." |
| Lens answered with an error | *Google Lens reported an error* | "Try again, or type the title below." |
| Cover has no text | *No text found on the cover.* | a **successful** scan, not a failure — type a title instead |
| Text, but no plausible title | *Text found, but no title could be picked out.* | the query field stays editable |
| Catalogue has no match | *No match in Open Library.* | empty is **not** an error |
| Catalogue 429 / 5xx / bad JSON | *Catalogue unavailable.* | a line naming the HTTP problem, plus "The offline matcher still works." |
| Anything genuinely unexpected | *Could not read the cover* | the only case that still uses this wording, and the only one that ever did before |

---

## 6. Files

| File | Responsibility |
| :--- | :--- |
| `app/src/main/python/lens_bridge.py` | the only Python in the app; wraps `LensAPI`, converts the incoming buffer, returns a coded envelope |
| `online/PythonRuntime.kt` | starts/owns the embedded interpreter, reports why it cannot |
| `online/ChromeLensPyBridge.kt` | the only code that touches Chaquopy types; dict → plain Kotlin via `asMap()` |
| `online/OcrResultParser.kt` | the envelope → Kotlin contract, and error-code → category. Pure, unit-tested |
| `online/OcrErrorMessages.kt` | the one sentence shown per failure category. Pure, unit-tested |
| `online/OnlineSearchLog.kt` | Logcat for the feature; never logs image bytes or OCR text |
| `online/OcrTextSource.kt` | OCR contract + `OcrText`, `OcrFailure`, `OcrAvailability` |
| `core/vision/EncodedImage.kt` | "did the JPEG encoder actually produce a picture?" Pure, unit-tested |
| `core/online/OcrQueryExtractor.kt` | OCR text → title/author query (pure JVM, unit-tested) |
| `core/online/ProductSearchResult.kt` | one catalogue record |
| `core/online/ProductSearchDataSource.kt` | catalogue contract + `ProductSearchOutcome` / `OnlineFailure` |
| `core/online/OpenLibraryResponseParser.kt` | Open Library JSON → records (pure JVM, unit-tested) |
| `online/OpenLibraryBooksSource.kt` | the Open Library request |
| `online/HttpJsonClient.kt` | the only socket in the app |
| `online/CoverImageLoader.kt` | in-memory cover cache |
| `online/OnlineSearchController.kt` | orchestrates both steps, owns the panel's state |
| `data/OnlineSearchPrefs.kt` | the panel's settings |
| `OnlineSearchUi.kt` | the panel's UI state |
| `ui/OnlineSearchPanel.kt`, `ui/ProductResultCard.kt` | the panel and its result cards |
| `tools/online_search_check.py` | desktop check of the catalogue request |
| `tools/lens_bridge_check.py` | desktop check of the bridge, with `LensAPI` stubbed — no network |
| `tools/lens_poc.py` | desktop proof of concept for the Python package |

### The two boundary rules worth remembering

Both bugs in
[ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md](ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md) came from assuming
how data crosses the Kotlin↔Python line. Neither assumption is obvious, so both are written down:

1. **A Java `byte[]` is not Python `bytes`.** It arrives as Chaquopy's own `java.chaquopy.jarray`,
   which supports the buffer protocol but fails `isinstance(x, (bytes, bytearray, memoryview))`.
   Test for the buffer protocol with `memoryview(value)`, then copy with `.tobytes()`. Never use a
   bare `bytes(value)`: `bytes(5)` is five NUL bytes.
2. **`PyObject.get(String)` is `getattr()`, not dictionary lookup.** The Chaquopy Javadoc says so
   and states it returns `null` for a missing attribute — so reading a `dict` that way yields `null`
   for every key, silently. Dictionary lookup goes through `asMap()`.

---

## 7. Known limitations

* **Runtime behaviour on a device is not verified.** The build, the packaging of the Python runtime
  and every pure-Kotlin rule are verified; no physical device or emulator was available, so the
  on-device Lens round-trip has never been executed. See [ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md](ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md) §15.
* Cancelling an online search stops the Kotlin side immediately, but a Lens request already in
  flight inside Python runs to completion — Chaquopy cannot preempt a running call.
* The title heuristic uses Lens's per-block glyph height, a good proxy for "the biggest type on the
  cover" but not a guarantee. The query is editable for exactly this reason.
* `chrome-lens-py` is an unofficial client for a Google endpoint. It can stop working at any time
  and no one is obliged to fix it.
* Open Library coverage is uneven for recent, non-English and small-press titles.
* The APK grows by roughly 30 MB (two ABIs of CPython, the standard library, Pillow and the
  dependencies).

