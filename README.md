# BookCoverMatcher

An offline Android WebView app that scans a book cover with your phone camera
and finds it inside an Excel spreadsheet by perceptual image matching.

No accounts. No server. No internet required after the first load.

---

## Table of contents

- [What it does](#what-it-does)
- [Features](#features)
- [Screens and flow](#screens-and-flow)
- [Project layout](#project-layout)
- [Requirements](#requirements)
- [Android integration](#android-integration)
- [How to use](#how-to-use)
- [Configuration](#configuration)
- [Reading a match](#reading-a-match)
- [How pHash works](#how-phash-works)
- [How the workbook is read](#how-the-workbook-is-read)
- [Wishlist](#wishlist)
- [Limitations](#limitations)
- [Troubleshooting](#troubleshooting)
- [Security and privacy](#security-and-privacy)
- [Building from source](#building-from-source)
- [Release notes](#release-notes)
- [License](#license)

---

## What it does

1. **Scan a book cover** with the camera, or upload one from your device.
2. The app computes a **64-bit perceptual hash (pHash)** of the cover.
3. You point it at an **`.xlsx` / `.xlsm`** workbook and a **cell range**
   such as `A1:B20`.
4. The app extracts every image inside that range — both floating images and
   images used as **comment fills** — hashes each one, and ranks them by
   Hamming distance against your scan.
5. It shows the **top 5 closest matches** with cell addresses, thumbnails,
   Hamming distance, and a similarity percentage.
6. You can save the scan and its matches into a **local wishlist** for later.

---

## Features

### Camera

- Live preview with a fixed guide rectangle.
- Guide crop is captured from the exact region shown inside the guide.
  The `object-fit: cover` behaviour of the `<video>` element is reproduced
  in JavaScript so the pHash is computed on the same pixels the user framed.
- Restart button to re-open the stream if it drops.
- Automatic stop and resume when the app is backgrounded and foregrounded.

### Perceptual hashing

- Uses **OpenCV.js** when the runtime is available.
- Falls back to a **pure-JavaScript DCT pipeline** when OpenCV cannot load,
  so the app still works offline.
- Hamming distance and a **correct similarity mapping** — two unrelated
  pHashes average 32/64 differences, and are mapped to 0%, not 50%.
- Match levels: **High** (distance ≤ 5), **Mid** (≤ 12), **Low** (rest).

### Spreadsheet reading

- Reads `.xlsx` and `.xlsm` entirely in the browser via **ExcelJS**.
- Extracts **floating (anchored) images** inside the chosen range.
- Extracts **comment-fill images** by parsing each sheet's
  `vmlDrawing*.vml` part via **JSZip** and resolving relationship targets
  to the correct media file.
- Cell range parser accepts `A1:B20` (case-insensitive, `$` allowed).
- Ranks every extracted image against the scan and returns the top 5.

### Wishlist

- Yellow **⭐ Add to Wishlist** button in the third panel.
- On add, a **green prompt** appears for 5 seconds with:
  - **Remove** — deletes the record you just added.
  - **✕** — dismisses the prompt, keeps the record.
- Header **⭐ star button** opens the wishlist overlay.
- Each record shows:
  - The captured cover thumbnail.
  - A renameable name (defaults to `#1`, `#2`, …) with a **pen** button.
  - A **down-arrow** that expands the record to reveal the top 5 matches.
  - A timestamp in `YYYY-MM-DD HH:MM` format.
  - A red **✕** in the bottom-left to delete.
- Deleting opens a **blocking confirmation modal** with grey **Cancel** and
  red **Confirm**. Nothing else is clickable while it is open.
- Records persist in `localStorage` under `bcm_wishlist_v1`.

### UI

- Dark navy theme, single-column phone layout, max width 560 px.
- Sticky toast for success and error messages.
- Safe-area aware; large touch targets.
- No external CSS frameworks, no build step.

---

## Screens and flow
<img width="228" height="580" alt="preview" src="https://github.com/user-attachments/assets/f0586a10-1332-4751-8008-093db4983894" />

Flow:

1. Scan a cover → hash is computed → preview appears.
2. Choose a workbook and range → tap **Link & Find Top 5**.
3. Inspect the ranked matches.
4. Save to the wishlist if desired.

---
- `app/src/main/assets/`
  - `index.html`
  - `css/`
    - `app.css`
  - `js/`
    - `config.js` — constants and library URLs
    - `dom.js` — cached DOM references
    - `state.js` — mutable application state
    - `utils.js` — toast, badge, busy state, script loader
    - `libraries.js` — ExcelJS / JSZip / OpenCV bootstrap
    - `camera.js` — camera start, stop, and guide-crop capture
    - `phash.js` — pHash (OpenCV + JS fallback), distances
    - `image.js` — canvas helpers and thumbnail generation
    - `excel.js` — range parsing and image extraction
    - `matcher.js` — top-N ranking
    - `ui.js` — scan preview and top-5 rendering
    - `wishlist.js` — wishlist storage, overlay, confirm modal
    - `main.js` — event wiring and boot
  - `libs/`
    - `opencv.js`
    - `exceljs.min.js`
    - `jszip.min.js`
  
The three files under `libs/` are vendored dependencies and are **not**
modified. They are loaded by local name first; if a file is missing, the
app tries the matching public CDN URL as a fallback (see `js/config.js`).

---

## Requirements

- Android WebView with ES module support (Android 8+ is safe in practice).
- A working camera for scanning. The device-image upload path works without
  a camera.
- Assets served from a secure origin (see the next section) so that ES
  modules load correctly.

---

## Android integration

Loading `file:///android_asset/index.html` directly blocks ES modules in
modern WebView. Use `WebViewAssetLoader` so the app is served from
`https://appassets.androidplatform.net/`.

```java
WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
    .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
    .build();

webView.setWebViewClient(new WebViewClientCompat() {
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return assetLoader.shouldInterceptRequest(request.getUrl());
    }
});

webView.getSettings().setJavaScriptEnabled(true);
webView.getSettings().setDomStorageEnabled(true);   // required for wishlist
webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
```
---
## Manifest
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```
Request the CAMERA permission at runtime before the first scan.
## How to use
1. Open the app. Tap 📸 Scan Book Cover.
2. Point the camera at the cover so that it fits inside the dashed guide.
3. Tap 📸 Scan Book Cover again to capture. The scan preview and its 64-bit pHash appear in the sheet panel.
4. Under 1 · Spreadsheet & range:

        Pick an .xlsx or .xlsm file.

        Type a range such as A1:B20.
5. Tap 🔗 Link & Find Top 5.
6. The top 5 matching cells appear with thumbnails, distances, and percentages.
7. Tap ⭐ Add to Wishlist to save the scan and its matches.
8. Use the star button in the header to reopen the wishlist at any time.

Alternatively, import an image from your device to load a cover
from the gallery instead of the camera.
## Configuration
| Constant | Default | Purpose |
| :--- | :--- | :--- |
| `GUIDE_W` / `GUIDE_H` | `0.70` / `0.78` | Guide rectangle as a fraction of the camera viewport. Must match `.guide { width:70%; height:78%; }` in `css/app.css`. |
| `THUMB_SCAN_W` / `THUMB_SCAN_H` | `112 × 152` | Scan preview thumbnail size. |
| `THUMB_MATCH_W` / `THUMB_MATCH_H` | `88 × 118` | Match result thumbnail size. |
| `MAX_IMAGE_DECODE` | `800` | Max long edge when decoding a user-supplied image. |
| `MAX_BUFFER_DECODE` | `640` | Max long edge when decoding an image from the workbook. |
| `MAX_EXTRACTED_IMAGES` | `300` | Hard cap on extracted workbook images per run. |
| `TOP_MATCHES` | `5` | Number of results shown. |
| `WISHLIST_PROMPT_MS` | `5000` | Green prompt duration in milliseconds. |
| `WISHLIST_STORAGE_KEY` | `bcm_wishlist_v1` | `localStorage` key for the wishlist. |
| `OPENCV_URL` | `local then CDN` | OpenCV.js source list. |
| `EXCELJS_URL` | `local then CDN` | ExcelJS source list. |
| `JSZIP_URL` | `local then CDN` | JSZip source list. |

## Reading a match
- **Hamming N/64** — The number of differing bits between the two 64-bit hashes. Lower is better.
- **Similarity %** — 100 × (1 − N / 32), clamped to 0–100. Two unrelated pHashes average N ≈ 32, so N = 32 is 0%, not 50%.
- **Match level** — Colour of the percentage:
    - **High (green)** — N ≤ 5. Almost certainly the same image.
    - **Mid (accent)** — N ≤ 12. Same cover, different capture.
    - **Low (warn)** — Otherwise. Treat as a weak suggestion.
- **Source** — floating for anchored images, comment-fill for images stored inside cell comments.
  
## Limitations
- Only the first worksheet is scanned for floating images.
- Comment-fill images are read from every vmlDrawing*.vml part in the workbook, but cells are labelled by their A1 address only — there is no sheet qualifier, and duplicate addresses across sheets are silently collapsed.
- The cell range is applied to every worksheet for comment-fills and to thefirst worksheet for floating images.
- pHash is not invariant to large rotations, perspective, or heavy perspective distortion. Keep the cover roughly flat and upright in the guide.
- Very dark, very bright, or heavily blurred captures reduce match quality.
- Image formats supported are those the WebView can decode via <img> (PNG, JPEG, GIF, BMP, WebP in most builds).
- The wishlist is stored in the WebView's localStorage. Clearing app data clears the wishlist.
- No cloud sync, no accounts, no external uploads.
## Troubleshooting

| Symptom | Likely cause |
| :--- | :--- |
| Camera does not start | `CAMERA` permission missing, or another app is using the camera. |
| Badge shows "No camera API" | WebView origin not allowed to use `getUserMedia`; enable media playback and secure origin. |
| Libraries never load | Running from `file://` without `WebViewAssetLoader`. Switch to the HTTPS asset origin. |
| "No images found inside ..." | The range is correct but the range contains no floating images and no comment-fill images; or the images sit outside the chosen rectangle. |
| Matches look random | pHash distance for unrelated images clusters around 32; check that the workbook images are genuinely comparable thumbnails. |
| Two sheets have an image in `B4` and only one appears | Comment-fill dedupe collapses same-address entries across sheets. |

## Security and privacy
- All processing happens on-device.
- The workbook and images never leave the WebView.
- The only network requests are the three CDN fallbacks, and only if the matching local file under libs/ is missing.
