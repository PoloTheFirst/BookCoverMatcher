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

### 1.1 · Microsoft sign-in (MSAL) — added in v2.6.0

The OneDrive import needs Microsoft's MSAL for Android. It is a public client (authorization code +
PKCE, no client secret) and requests only the delegated Graph scopes `Files.Read` and `User.Read`.

| Component | Version | Licence | Status / notes |
|---|---|---|---|
| `com.microsoft.identity.client:msal` | 8.6.0 | **MIT** | **Verified** — the published POM carries `<licenses><license><name>MIT License</name>`, and `AzureAD/microsoft-authentication-library-for-android` publishes the same MIT `LICENSE` (Copyright (c) Microsoft Corporation). Ships in the APK. |
| `com.microsoft.identity:common` | 25.0.0 | **MIT** | **Verified** — POM `<name>MIT License</name>`; the submodule's own repository `AzureAD/microsoft-authentication-library-common-for-android` publishes an MIT `LICENSE`. Ships in the APK. |
| `com.microsoft.identity:common4j` | 25.0.0 | **MIT** | **Verified in Phase 8** — the cached POM carries `<name>MIT License</name>` (the Phase 3 note said "not separately verified"; it now is). Ships in the APK. |
| `com.microsoft.device.display:display-mask` | 0.3.0 | **Unresolved — release blocker** | **Could not be established from any authoritative declaration available to this audit.** Evidence, all re-checked in Phase 8: the published POM (the 421-byte file Gradle cached from Microsoft's feed) contains **only** coordinates — no `<licenses>`, no `<url>`, no `<developers>`; the artifact is a 1,591-byte JAR holding a single class (`DisplayMask`) with **no `META-INF/LICENSE*` or `NOTICE*`** and a manifest that says only `Created-By: soong_zip`; Maven Central publishes **nothing** under the `com.microsoft.device.display` group (`search.maven.org` → `numFound: 0`), so there is no Central metadata either; it exists only on Microsoft's public `MicrosoftDeviceSDK/DuoSDK-Public` "Duo-SDK-Feed" Azure Artifacts feed, which exposes no licence metadata. The two Microsoft repositories that could plausibly own it were checked and **neither contains it**: `microsoft/surface-duo-sdk` has no `display-mask` path (HTTP 404; its modules are bottomnavigation, fragmentshandler, inksdk, layouts, navigation, navigationrail, recyclerview, screenmanager, snackbar, tabs, utils) and `AzureAD/microsoft-authentication-library-common-for-android` includes only common, testutils, uiautomationutilities, common4j, keyvault, labapi, LabApiUtilities. Both of those repositories declare **MIT**, which licenses *their* code — it does **not** license this artifact, and this audit does not infer one from the other. It arrives as a runtime dependency of `com.microsoft.identity:common` (`:app:dependencies --configuration debugRuntimeClasspath`) and it **is packaged**: the string `com/microsoft/device/display/DisplayMask` occurs in `classes18.dex` and `classes19.dex` of the debug APK. Phase 9 re-checked every one of those findings and added one it had not established before: the class is **on the interactive sign-in path**. `com.microsoft.identity.common.internal.ui.DualScreenActivity` is the **superclass** of `com.microsoft.identity.common.internal.providers.oauth2.AuthorizationActivity` — an activity this app's *merged* manifest declares — and `DualScreenActivity#getHinge` calls `com.microsoft.device.display.DisplayMask.fromResourcesRect(...)` (read with `javap` out of the cached `common-25.0.0.aar`; `AuthorizationActivity.class` and `CurrentTaskAuthorizationActivity.class` both name `DualScreenActivity`). Excluding the artifact would therefore remove a class the sign-in UI can reach, so exclusion is **not** demonstrably safe without a real-device sign-in test — and no such test was performed. **Owner decision required before distributing a release** (option: keep the dependency and accept the risk, or ask Microsoft/publish a notice, or exclude it only after a device test proves MSAL works without it). |
| `androidx.webkit` 1.14.0, `androidx.datastore:datastore-preferences` 1.0.0, `androidx.activity` 1.8.2, `androidx.appcompat` 1.1.0, `androidx.browser` 1.7.0, `androidx.core` 1.5.0, `androidx.constraintlayout` 1.1.3, `androidx.fragment` 1.3.2, `androidx.credentials` 1.2.2 (+`credentials-play-services-auth`), `androidx.lifecycle:lifecycle-runtime-ktx` 2.5.1 | as listed | **Apache-2.0** | **Verified row by row in Phase 8** — each POM's `<licenses><license><name>The Apache Software License, Version 2.0</name>` was read either from the Gradle cache (`webkit` 1.14.0, `browser` 1.7.0, `core` 1.5.0, `constraintlayout(-solver)` 1.1.3, `fragment` 1.3.2, `credentials` 1.2.2 and `credentials-play-services-auth` 1.2.2, `datastore`/`datastore-core`/`datastore-preferences`/`datastore-preferences-core` 1.0.0) or from the publisher's own repository `dl.google.com/dl/android/maven2` when Gradle had cached only module metadata (`activity` 1.8.2, `appcompat` 1.1.0, `lifecycle-runtime-ktx` 2.5.1). |
| `com.squareup.moshi` 1.15.2 (+`moshi-adapters`), `com.squareup.okio` 3.7.0 (+`okio-jvm`), `com.google.code.gson:gson` 2.8.9, `com.nimbusds:nimbus-jose-jwt` 10.0.2, `org.apache.httpcomponents.core5:httpcore5` 5.4.3, `io.opentelemetry:opentelemetry-api` 1.62.0 (+`-common`, `-context`, `-extension-kotlin` 1.18.0), `com.yubico.yubikit:android` 2.5.0 (+`core`, `piv`), `com.google.android.libraries.identity.googleid:googleid` 1.1.0, `com.github.stephenc.jcip:jcip-annotations` 1.0-1, `org.jspecify:jspecify` 1.0.0, `org.slf4j:slf4j-api` 2.0.9 | as listed | **Apache-2.0**, except `slf4j-api` (**MIT**) | **Verified row by row in Phase 8.** POM `<licenses>` read for every artifact above except two, whose licence was taken from the shipped artifact itself: `httpcore5-5.4.3.jar` carries `META-INF/LICENSE` ("Apache License") plus `NOTICE`/`DEPENDENCIES` because its POM has no `<licenses>` block, and `slf4j-api-2.0.9.jar` carries the MIT `META-INF/LICENSE.txt` ("Copyright (c) 2004-2022 QOS.ch Sarl"). `jspecify` 1.0.0's POM was read from Maven Central. |
| `com.google.android.gms:play-services-auth` 20.7.0, `-auth-api-phone` 18.0.1, `-auth-base` 18.0.4, `-base` 18.0.1, `-basement` 18.2.0, `-fido` 20.1.0, `-tasks` 18.0.1 | as listed | **Android Software Development Kit License** (not Apache-2.0) | **Verified from the POMs** in Phase 8. This is Google's own SDK licence, not an open-source one — recorded as declared, not re-licensed by this project. |
| AndroidX/Compose/Camera set shared with the pre-existing app (Compose BOM 2024.10.01, CameraX 1.3.4, lifecycle 2.8.7, and the rest of the transitive AARs) | as listed | **Apache-2.0** | **Sampled and spot-verified in Phase 8** (`androidx.compose.ui:ui` 1.7.5 read from `dl.google.com`), and the same declaration was already verified for the rows above. Gradle had cached module metadata (not POMs) for many of these, so a full row-by-row pass would mean fetching ~200 POMs; the declaration is uniform across the AOSP-published set (`<name>The Apache Software License, Version 2.0</name>`, organisation "The Android Open Source Project") and **one** family (Compose) was verified to confirm it. Carried to Phase 9 as "complete the row-by-row pass" rather than claimed as done. |

Enumerate the exact runtime set with:

```powershell
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
```

The MSAL stack was **not** present in v2.5.0; it entered with the OneDrive feature and is only
initialised when the user asks for OneDrive.


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

**Release gate (Phase 8):** `com.microsoft.device.display:display-mask:0.3.0` is the only packaged
component whose licence this audit could not establish (see §1.1). Everything else that ships is either
GPL-3.0-or-later (this project) or a licence verified from an authoritative declaration. A distribution
of v2.6.0 is therefore **not** licence-clear until the project owner decides what to do about that one
artifact; this document does not claim otherwise.

**Release gate (Phase 9 — re-checked, still open).** The `display-mask` row above was re-verified, not
re-labelled: the POM in the Gradle cache is still the 421-byte coordinates-only file, the artifact is still a
1,591-byte JAR holding only `DisplayMask.class` and a `soong_zip` manifest with no `META-INF/LICENSE*` or
`NOTICE*`, `search.maven.org` still reports `numFound: 0` for `com.microsoft.device.display`, and
`microsoft/surface-duo-sdk` still has no `displaymask` path (HTTP 404). Phase 9 also re-derived the reason the
artifact cannot simply be excluded: `javap` on the cached `common-25.0.0.aar` shows
`AuthorizationActivity extends DualScreenActivity`, and `DualScreenActivity` bytecode calls
`com/microsoft/device/display/DisplayMask.fromResourcesRect(...)` and
`DisplayMask.getBoundingRectsForRotation(...)`. The class is on the interactive sign-in path, so exclusion
remains unprovable as safe without a real sign-in on a device. The row-by-row pass over the shared
AndroidX/Compose/Play-services set is also still outstanding. In Phase 9 a **debug** APK was built for the
emulator run and does contain the class in `classes18.dex`/`classes19.dex`; **no release or distributable
artifact was built, inspected as a release, or distributed.**

The on-device OCR library (`libbc_ocr.so`, built from the project's own `bc_ocr` Rust crate in `native/ocr`)
is the project's own GPL-3.0-or-later code (`native/ocr/Cargo.toml`), which is why it appears in the
inventory without a third-party licence of its own. (This sentence replaces an orphaned fragment that had
read only "(`bc_ocr`), which is why it appears without a third-party licence." since the first release.)
