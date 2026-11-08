# GPL-3.0-or-later — historical migration and distribution cleanup: final report

**Executed:** 2026-11-08 · **Repository:** `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher`
(`https://github.com/PoloTheFirst/BookCoverMatcher.git`) · **Licence:** `GPL-3.0-or-later` for
project-owned code only; third-party components keep their own terms.

> **Result:** all **22 historical release tags** now carry the canonical GPLv3 `LICENSE`, `NOTICE`,
> `THIRD_PARTY_NOTICES.md` and the SPDX header on project-owned sources, in additive commits whose
> parent is the original release commit — no branch history was rewritten, `dev` untouched. The three
> flagged asset groups are replaced by generated fixtures, the personal workbook is out of the
> repository, and every branch and tag was published and verified against the remote.

## A · Historical tag migration (all 22)

Each tag moved to a **new licensing commit whose parent is the original release commit**, so `v2.5.0^`
is still the release that shipped.

| Tag | Original release commit | Licensing commit (tag now) | SPDX headers | Flagged assets replaced |
|---|---|---|---|---|
| `v1.0.0` | `1d2ceba74` | `18a95a3b4` | 35 | 0 |
| `v1.1.0` | `4c7327adb` | `4acc8c4c0` | 35 | 0 |
| `v2.0.0` | `a6baaace6` | `5716b8996` | 52 | 0 |
| `v2.0.0-dev.1` | `cd1277132` | `a8b6c928a` | 41 | 0 |
| `v2.1.0` | `cb8a25361` | `056744f7a` | 78 | 0 |
| `v2.2.0` | `b0b0623dd` | `7dd8f57b9` | 90 | 1 |
| `v2.2.1` | `4ebfe739a` | `3b51bd1ac` | 96 | 3 |
| `v2.2.2` | `4613629d4` | `e974e93a6` | 113 | 5 |
| `v2.2.3` | `a87653930` | `62f9c4738` | 115 | 5 |
| `v2.2.4` | `0e29be0a3` | `5f9028a0f` | 116 | 5 |
| `v2.3.0` | `213e4b083` | `550f5c78d` | 164 | 5 |
| `v2.3.1` | `445cd7a75` | `5dbb9bdd2` | 167 | 5 |
| `v2.4.0` | `c35dda700` | `786ea722b` | 229 | 6 |
| `v2.4.0-dev.1` | `1bec629bf` | `ae08d1ad8` | 170 | 5 |
| `v2.4.0-dev.2` | `50fab0202` | `73e6fa260` | 191 | 5 |
| `v2.4.0-dev.3` | `b246db8ed` | `5f1918ce9` | 199 | 5 |
| `v2.4.0-dev.4` | `e751450f3` | `99723fe5a` | 206 | 5 |
| `v2.5.0` | `e98671349` | `75cb78f5a` | 315 | 6 |
| `v2.5.0-dev.1` | `c35dda700` | `0c1c871c7` | 229 | 6 |
| `v2.5.0-dev.2` | `c35dda700` | `89b050869` | 229 | 6 |
| `v2.5.0-dev.3` | `c35dda700` | `893ced499` | 229 | 6 |
| `v2.5.0-dev.4` | `c35dda700` | `2213ab6cb` | 229 | 6 |

Machine-readable (`historical_prompt/gpl-v3-retag-report.csv`): tag, original commit, licensing
commit, LICENSE blob, canonical sha256, header count, substituted paths, result.

**Verification** — `python tools/check-licenses.py --refs` ends `licensing audit passed`; all 22 tags
report `LICENSE ok`, `header ok`, and no flagged asset:

* `LICENSE` in every tag is blob `f288702d2…`: canonical text, sha256 `3972dc9744f6499f…`, 35,149 B.
* A project-owned Kotlin file sampled per snapshot carries `SPDX-License-Identifier: GPL-3.0-or-later`
  (the sample is the first `.kt` under `app/src/main/java` that the snapshot really has, so a 1.0-era
  layout is not misjudged).
* Third-party files are byte-identical to the release: `gradlew` `23d15a9367…` before and after on
  `v2.0.0`; the PaddleOCR dictionary and OpenCLIP metadata still carry no project header.
* `v1.0.0`/`v1.1.0` already had `LICENSE` but no headers on 35 sources — a real deficiency, so they
  were migrated too rather than skipped.
* All 22 were annotated tags; each was recreated with its original message appended under
  `Original tag message:`. No tag was signed, so no signature was invalidated.

## B · The migration script and the `GIT_INDEX_FILE` bug

**Root cause (reproduced, not guessed).** `git update-index --index-info` reading newline-terminated
records is accepted and then discarded by Git 2.56.0.windows.1: exit 0, `Ignoring path …` on stderr,
index unchanged. `write-tree` therefore returned exactly the tree `read-tree` had put in — the
original. The temporary index was correct, absolute, initialised from the right tree and shared by
all three commands; the entries simply never landed. `-z` (NUL-terminated) is honoured:

| Invocation | entry in index | tree changed |
|---|---|---|
| `--index-info` | no | no |
| `--add --index-info` | no | no |
| `--replace --index-info` | no | no |
| `-z --index-info` | yes | yes |

**Fixes:** NUL-terminated `-z` writes with only the changed paths; the index is read back and the
script **fails immediately** (tag, counts, first offenders) if any expected path is missing; a tree
that comes back unchanged, or a new tree without the canonical LICENSE blob, is a hard error;
`cat-file --batch` gets byte input; records carry the stage field; output is decoded as UTF-8. It is
idempotent (re-run: `0 of 22 tag(s) need migration`) and `--tag <name>` proves one tag at a time.

**Single-tag proof (`v2.0.0`):** parent `a6baaace6` = the release, tree `17b0f836d` ≠ `d8d7853c2`,
`LICENSE` canonical, `gradlew` unchanged, `MainActivity.kt` starts with the SPDX line, original
commit still reachable, re-run `already compliant`.

**Rollback one tag:** `git tag -f -a <tag> <original commit> -m "<original message>"` — the message is
kept inside the new tag object; the pre-migration commits are parents, not orphans, so no reflog work
is needed. **Rollback everything:** `git clone %TEMP%\bc-pre-gpl.bundle`.


## C · OpenCLIP — MIT, unchanged by this pass, now machine-checked

Re-verified only by reading what is in the tree; the finding was not re-investigated because nothing in
the repository invalidated it.

* Identity: `ViT-B-32` / `laion2b_s34b_b79k` → `laion/CLIP-ViT-B-32-laion2B-s34B-b79K`, revision
  `1a25a446712ba5ee05982a381eed697ef9b435cf`.
* Licence: **MIT** — `cardData.license = "mit"`, the `license:mit` tag and the model card all declare
  it. That is LAION's declaration on a model repository that carries no `LICENSE` file; the model
  metadata is the authoritative statement relied on. `LAION-2B` *dataset* terms are separate and no
  dataset content is redistributed.
* Our artefact: `app/src/main/assets/models/openclip_image.onnx`, `d0ee5e32…`, 96,919,211 bytes, int8,
  224×224 → 512-d, produced by `tools/export_openclip_onnx.py` (open_clip 3.3.0, MIT code, image tower
  only, no calibration data).
* Shipped in the APK at `assets/models/openclip_image.onnx` with attribution in
  `THIRD_PARTY_NOTICES.md` (`assets/licenses/THIRD_PARTY_NOTICES.md` inside the APK). It is **never**
  labelled GPL-3.0-or-later, and this pass did not touch the attribution.
* `tools/check-licenses.py` now fails if the OpenCLIP row disappears or stops mentioning MIT.

## D · Flagged asset replacements

Originals preserved outside the repository in `%TEMP%\bc-flagged-assets-backup\` (cover 37,706 B, the
40,483,600 B workbook, four pages), plus the pre-migration bundle.

| Original path | Why flagged | Replacement | Produced by | Tests preserved |
|---|---|---|---|---|
| `app/src/androidTest/assets/cover.jpg` | Photograph of a published cover, publisher's copyright | Same path, generated 600×850 cover: colour blocks, border, large Latin text (`SYNTHETIC TEST COVER`, `BOOK COVER MATCHER`, `NOT A REAL BOOK`, `GPL 3.0 OR LATER`); 22,068 B, sha256 `23897f16…` | `tools/make-synthetic-fixtures.py` (Pillow); still a JPEG the OCR bridge decodes | `NativeOcrDeviceTest` (4 uses), `OcrDeviceTest`, `OcrEngineRoutingTest` — same code paths, no expectation changed |
| `test_excel/doujin.xlsx` | 40 MB personal catalogue workbook, not project code | **Removed from the repository** (`git rm --cached`, `.gitignore` entry, file kept on disk) + new `test_excel/synthetic_catalog.xlsx` (4,805 B) | generated with `zipfile`: `LIST` sheet, the same 24 columns A–X, header + 3 records, dates as serial numbers, a hyperlink, an embedded PNG behind a `oneCellAnchor`, a `comments1.xml` part | `SampleWorkbookColumnsTest` and `RealWorkbookIntegrationTest` still run **locally** on the real workbook — they are path-based and skip when it is absent, so coverage on the owner's machine is intact and neither test needs the file distributed |
| `app/src/test/resources/fixtures/dlsite_books_search.html`, `dlsite_books_empty.html` | Captured DLsite search pages | Generated pages with the same DOM the parser matches (`data-list_item_product_id`, `work_name`, `maker_name`, `work_price_base`/`_suffix`, 240×240 thumbnail candidate) and invented `RJ01000001…`, titles, circles, prices | `tools/make-synthetic-fixtures.py` | `DlsitePageParserTest` (8) + `HtmlTextTest` (2) — pass |
| `app/src/test/resources/fixtures/melonbooks_search.html`, `melonbooks_empty.html` | Captured Melonbooks search pages | Generated pages (`item-list`, `product_<id>`, `item-ttl product_title`, `search-item-author-author`, `item-price`, `data-src` resize URL), invented ids/titles/circles/artists/prices, `&amp;` kept in one title | `tools/make-synthetic-fixtures.py` | `MelonbooksPageParserTest` (7) — pass |

Expectations in `DlsiteProviderTest.kt` and `MelonbooksProviderTest.kt` were updated to the invented
values (ids, titles, circles, authors, cover URLs; the entity case is now `「引用」&「記号」`) and their
KDoc says *synthetic* instead of *captured*. No fixture was replaced by an empty or trivial file and no
test was deleted: the same parser paths run on content this project owns.

**Coverage-parity evidence:** before the swap both parser classes passed on the captured pages (baseline
986/0/7); after it, the same classes — same count, same assertions, now synthetic — pass, and the whole
suite is again **986 tests, 0 failures, 7 skipped**. The limitation to state plainly: a generated page
cannot reproduce the *volume* and incidental nesting of a real shop page (three items, as before, but no
adverts, no 100-item list). That is why the empty-page and "not a result page" cases were kept
explicitly, so a parser that silently accepted anything would still fail.


## E · Distribution verification (actual archives, not Gradle config)

Built with `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
:app:assembleDebugAndroidTest` → **BUILD SUCCESSFUL in 50s**. Every APK was then opened as a zip:

| APK | Size | Licence documents inside | OpenCLIP | cover.jpg | workbook |
|---|---|---|---|---|---|
| `app-debug.apk` | 336,170,667 B | `assets/licenses/GPL-3.0.txt` (sha256 `3972dc97…`, canonical) + `assets/licenses/THIRD_PARTY_NOTICES.md` | `assets/models/openclip_image.onnx` present | absent | absent |
| `app-release.apk` | 331,150,624 B | `assets/licenses/GPL-3.0.txt` (sha256 `3972dc97…`) + `assets/licenses/THIRD_PARTY_NOTICES.md` | `assets/models/openclip_image.onnx` present | absent | absent |
| `app-debug-androidTest.apk` | 458,654 B | none (a test APK ships no licence assets by design) | n/a | `assets/cover.jpg` sha256 `23897f16…` = **the generated cover** | absent |

The packaged `THIRD_PARTY_NOTICES.md` in both shipping APKs contains the new §4
(`make-synthetic-fixtures.py`) and the `| OpenCLIP | … MIT …` row, so it was regenerated by
`syncLicenseAssets` rather than left stale. No `.xlsx` exists anywhere in `assets/`, so the personal
workbook cannot reach a device through an APK, and the only `cover.jpg` in the build output is the
generated one in the test APK. `NOTICE` is deliberately not packaged — it states the project's own
copyright, not a third-party obligation — while `LICENSE` reaches the APK as `GPL-3.0.txt`, which is
what `LicenseDocuments.kt` and **More → Open-source licences** read.

**Tests actually executed:** `:app:testDebugUnitTest` — **986 tests in 120 classes, 0 failures,
0 errors, 7 skipped**, parsed from `app/build/test-results/testDebugUnitTest/*.xml`. That equals the
historical baseline (986/0/7) with the generated fixtures in place. The seven skips are the by-design
conditional cases. **Not run:** the instrumented `androidTest` suite (needs a connected device or
emulator — the long-standing gap) and the Rust `real_models` tests (need the PaddleOCR models plus
`onnxruntime.dll`). They are reported as not run, never as passing.

## F · Git publication

| Ref | Before | After | How |
|---|---|---|---|
| `main` | `75425d3` | `25ed62d` | `git push origin HEAD:main` |
| `online_improvement` | `75425d3` | `25ed62d` | `git push origin online_improvement` |
| `dev` | `beb8b81` | `beb8b81` | **not pushed, not touched** |
| 22 tags | original release commits | the licensing commits in §A | one `git push --force origin` with 22 explicit `+refs/tags/<t>:refs/tags/<t>` refspecs — no `--tags`, no branch refspec, no `--all` |

Verified with `git ls-remote` afterwards: a tag-by-tag comparison of all 22 local tag objects against
the remote gave **0 mismatches**, and the branch tips read back as `dev beb8b81`, `main 25ed62d`,
`online_improvement 25ed62d`. Commit `25ed62d` carries the notices, the generated fixtures, the
untracked workbook, the two updated test classes, the two tools and the CSV report. The owner's
`.idea/deploymentTargetSelector.xml`, `gradle/libs.versions.toml`, `BookCoverMatcher-QA/` and
`hs_err_pid4316.log` were left un-staged and un-committed.

## G · Recovery

* Bundle: `%TEMP%\bc-pre-gpl.bundle` — 306,757,411 B, `git bundle verify` reports a complete history;
  restore with `git clone %TEMP%\bc-pre-gpl.bundle <dir>`.
* Ref inventory from before any of this work: `%TEMP%\bc-refs-before.txt` (30 refs with hashes), which
  is what the "Before" column above is checked against.
* Flagged originals: `%TEMP%\bc-flagged-assets-backup\` (cover, four pages, the 40 MB workbook).
* One tag can be restored with `git tag -f -a <tag> <original commit> -m "<original message>"`. The
  release commits are the *parents* of the licensing commits, so nothing needs a reflog, and re-running
  `tools/migrate-historical-license.py` is a no-op on a compliant tag.

## H · What remains open (stated, not hidden)

1. **`dev` is untouched and still unlicensed** (`beb8b81`): no `LICENSE`, no SPDX headers. It is the
   owner's branch and was explicitly out of scope.
2. **Instrumented and Rust model tests were not run** (no device; no local PaddleOCR models), so the OCR
   device path is evidenced only by the unchanged fixture path and a decodable JPEG.
3. **The full 387-commit history still contains** the cover photograph, the personal workbook and the
   captured pages, and pre-migration commits carry no licence file. Removing them from history needs the
   rewrite this task forbade: the tags now advertise licensing correctly, the history does not.
4. **`THIRD_PARTY_NOTICES.md` carries pre-existing mojibake** (`Â·`, `â€”`) from an earlier encoding
   round-trip. It does not affect the statements and was not rewritten wholesale here, to avoid churn.
5. `BookCoverMatcher-QA/` (untracked QA reports) still mentions the workbook path. It documents past
   runs, is not distributed material and was left alone.
