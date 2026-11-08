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

## §I — Final completion of outstanding work

This section closes the five items §H listed as open. Where something still could not be executed, it
says so rather than implying success.

### I·A · `dev` is licensed, and it was licensed by rewriting its own commits

| | Value |
|---|---|
| Original `dev` tip | `631aef22bf652678cafaf3b17f6564732f841c7a` (ahead 1 / behind 1 vs `origin/dev`) |
| Rewritten `dev` tip | `4c9f64cf08b3bf86a90c0c369c6e8f4877ae1491` |
| `dev` LICENSE | blob `f288702d2…` — the canonical GPLv3 text, in every one of its commits |
| Headers on `dev` | added per snapshot by the same rewrite (634 project-owned blobs relabelled across the history) |
| Third-party files | untouched: `gradlew` still Apache-2.0, the PaddleOCR dictionary and OpenCLIP metadata still carry no project header |
| Published | yes, `refs/heads/dev` → `4c9f64c` (force, single refspec, `dev` was **not** merged into anything) |

`dev` was not merged from `main`: its own history — including the one local commit `origin/dev` did not
have (`fix: report catalogue truncation, widen the default range (v2.4.0-rc.7)`) — was rewritten in
place, so every `dev` commit keeps its author, message, timestamp and parent while gaining the licence.

### I·B · Complete Git-history rewrite

| | Before | After |
|---|---|---|
| Reachable commits (3 branches + 22 tags) | 124 | **124** (same count, same topology) |
| Branches | `dev 631aef2`, `main 2722bc0`, `online_improvement f5b733c` | `dev 4c9f64c`, `main 5041fbb`, `online_improvement 89db295` |
| Annotated tags | 22 | **22**, recreated with the same tagger, date and message |
| Trees rewritten | — | 1,110 |
| Source blobs relabelled | — | 634 |
| Flagged assets replaced | — | 8 |
| GPG signatures | 24 signed commits existed | **23 stripped** (see below) |

The full old→new mapping (3 branches, 22 tags, 124 commits) is committed as
`historical_prompt/gpl-v3-rewrite-map.csv`; the script that produced it is `tools/rewrite-history.py`,
and it refuses to run twice against the same clone.

**What is preserved:** author name/email/date, committer name/email/date, message bytes, parent lists
and therefore merges, branch topology, tag annotations (tagger, date, message), file modes, submodule
pointers and every third-party blob. **What changes:** every commit hash, every tag object hash, and the
SPDX/Licence content of the trees.

**Signatures:** 24 commits in the original history carry a GitHub GPG signature. A signature covers the
tree and metadata, so a commit whose tree changed cannot keep a valid one; 23 were stripped (the 24th is
not reachable from the maintained refs). They are not "lost": the signed objects remain in
`%TEMP%\bc-pre-rewrite.bundle`, and re-signing is possible but was not attempted because no signing key
is available to this environment.

**Tooling notes (both found the hard way):** `git update-index --index-info` must be NUL-terminated on
this Git build (as the earlier tag migration established), and `git mktag` here rejects even a
byte-identical copy of the repository's own tags with `badObjectSha1: invalid 'object' line format`.
The rewrite therefore writes tag objects with `hash-object -t tag --literally` from a payload rebuilt
out of the original object, and `git fsck` afterwards is clean.

### I·C · Historical asset cleanup

The six originals are identified **by blob id**, so a rename in an old commit cannot hide one, and by
path name as a second line of defence:

| Original path | Original blob | Size | Now |
|---|---|---|---|
| `app/src/androidTest/assets/cover.jpg` | `0cba0f17def5ff080b440d5cb75bd36e447920f5` | 37,706 B | generated cover `84c95786…` |
| `test_excel/doujin.xlsx` | `f5ba892b7cf124af7e354dd9e52075352f2c06de` | 40,483,600 B | generated workbook `68592fd6…` |
| `…/fixtures/dlsite_books_search.html` | `2bf85930a19ef8b4261b0b320d4e80a0f6946e2b` | 17,089 B | generated page |
| `…/fixtures/dlsite_books_empty.html` | `7f8e8068c46a6b2cc02b2a33fe5fc3c19a5a9435` | 7,742 B | generated page |
| `…/fixtures/melonbooks_search.html` | `1f2c55641a12516164af1dd903b5e229485ceae6` | 14,755 B | generated page |
| `…/fixtures/melonbooks_empty.html` | `b9c5b4777c67c73fa87c85190781f3ac46920494` | 1,237 B | generated page |

Verification after the rewrite: `git rev-list --objects` over all maintained refs yields **0 hits** for
those six ids, and 124/124 commits carry the canonical LICENSE. The originals survive only outside the
repository (`%TEMP%\bc-flagged-assets-backup\`) and inside the pre-rewrite bundle, which is what makes
recovery possible. Synthetic replacements remain at the same paths, so the historical tests that read
them still resolve their fixtures.

### I·D · Mojibake repaired

| File | Runs repaired | Result |
|---|---|---|
| `THIRD_PARTY_NOTICES.md` | 13 (`Â·`→`·`, `â€”`→`—`, `â€¦`→`…`, `Â§`→`§`, `â€œÂ§`→`–§`, `â†’`→`→`) | 8,194 B, valid UTF-8, no BOM |
| `README.md` | 267 (dashes, arrows, box-drawing, `ツイッター`, `ミスキー`, `出版日付`, `購入日`, `円`, `ブルアカ`) | 82,279 B, valid UTF-8, no BOM |
| `NOTICE`, `LICENSE`, `native/ocr/README.md`, `USER_GUIDE.md`, `ONLINE_SEARCH.md` | 0 | already clean |

The repair only touches maximal non-ASCII runs that *provably* round-trip (`run.encode('cp1252')
.decode('utf-8')`), which is impossible for genuinely intended characters such as `·` (a lone 0xB7 is
not valid UTF-8) or `…` (not in cp1252), so nothing legal was rewritten. Re-running the repair script
now reports 0 runs in every file. The packaged APK copy is byte-identical to the repaired source
(8,194 B) and contains no mojibake.


#### The five items §H listed before this pass (kept verbatim; I·E–I·I answer them)

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

### I·E · Instrumented Android tests — executed

The environment had a usable emulator all along, so these were run rather than excused.

| | Value |
|---|---|
| Device | `Medium_Phone_API_37.0(AVD)`, `emulator-5554`, model `sdk_gphone64_x86_64`, Google |
| Android | 17 (API 37), ABI **x86_64** |
| Command | `.\gradlew.bat :app:connectedDebugAndroidTest --console=plain` |
| Result | **21 tests, 0 failures, 0 skipped** — `NativeOcrDeviceTest` 10, `OcrDeviceTest` 3, `OcrEngineRoutingTest` 8 |

The **first** run failed 3 tests and that is the useful part: they still asserted that the bundled cover
says "HOBBIT", i.e. they were pinned to the third-party photograph that this migration removed. The
expectations were corrected to the generated cover's own text (`BOOKCOVER`, whitespace-insensitive) —
the tests still prove that the native engine and the Python/Chrome-Lens bridge actually read a cover —
and the suite then passed. Nothing was skipped or deleted to make it green.

### I·F · Rust model tests — executed against the real models

| | Value |
|---|---|
| Command | `cargo test --test real_models -- --ignored --test-threads=1` (in `native/ocr`) |
| Models | `det.onnx` 62,032,837 B and `rec.onnx` 76,554,979 B — exactly the sizes pinned in `native/ocr/README.md`; dictionary `assets/ppocrv6_dict.txt` 74,947 B (blob `270082aa…`) |
| Runtime | ONNX Runtime **1.20.0** (`%TEMP%\ort120\onnxruntime-win-x64-1.20.0\lib\onnxruntime.dll`), CPU |
| Result | **3 passed, 0 failed, 0 ignored** — `ppocrv6_medium_reads_a_real_cover`, `…_reads_multilingual_crops`, `…_detector_reads_a_real_cover_and_the_class_layout_is_reported` |

The first run gave 2 passed / 1 failed, again on the stale "hobbit" ground truth. The engine's own output
on the generated cover is the evidence that the fixture is adequate: 7 regions, 7 lines, 98–100 %
confidence, reading `SYNTHETIC`, `TEST COVER`, `BOOK COVER`, `MATCHER`, `SAMPLE 1`, `NOT A REAL BOOK`,
`GPL 3.0 OR LATER`. The assertion now checks those lines, and the class/dictionary contract
(18,710 classes = 18,708 entries + blank + space) still holds.

### I·G · APK verification (rebuilt after every source change)

| APK | Size | Licence documents | Flagged originals |
|---|---|---|---|
| `app-debug.apk` | 336,170,667 B | `assets/licenses/GPL-3.0.txt` (sha256 `3972dc97…`) + `assets/licenses/THIRD_PARTY_NOTICES.md` (8,194 B, no mojibake) | no `cover.jpg`, no `.xlsx` |
| `app-release.apk` | 331,150,600 B | same two documents, same hashes | no `cover.jpg`, no `.xlsx` |
| `app-debug-androidTest.apk` | 458,654 B | none (test APK) | only the generated cover, sha256 `23897f16…` |

Both shipping APKs contain `assets/models/openclip_image.onnx`, and their notices carry the OpenCLIP MIT
row, PaddleOCR, ONNX Runtime and the new §4. `:app:testDebugUnitTest` in the same build remains
**986 tests, 0 failures, 7 skipped**.

### I·H · Publication

| Ref | Final |
|---|---|
| `main` | `5041fbbfd34a73e5002c2ccfaa40bd9aa7c3b3fa` |
| `online_improvement` | `89db295bad67355c45b84464ae49c39581d35098` |
| `dev` | `4c9f64cf08b3bf86a90c0c369c6e8f4877ae1491` |
| 22 tags | all rewritten; `git ls-remote --tags` compared tag-by-tag: **0 mismatches** |

Published from the scratch clone with one `git push --force <url>` carrying **only** the 25 refspecs
above (`+refs/heads/{dev,main,online_improvement}` and `+refs/tags/<t>` ×22) — no `--all`, no `--mirror`,
no `--tags`. The local repository was then moved to the rewritten history with
`git fetch --prune --force --tags`, `git reset --mixed origin/online_improvement`, `git branch -f main
origin/main`, `git branch -f dev origin/dev`, which leaves the working tree untouched: `git status` still
shows only the owner's `.idea/deploymentTargetSelector.xml` and `gradle/libs.versions.toml` as modified.

### I·I · Final status — what is verified, and what is not

**Fully completed and verified:** licensing of `main`, `online_improvement` and `dev` in *every* reachable
commit (124/124 carry the canonical LICENSE, 634 source blobs carry the SPDX identifier); the 22 tags,
recreated and published; the six flagged originals removed from all reachable history (0 blob hits) with
generated fixtures in their place; the mojibake repaired in both damaged documents; the instrumented
suite (21/21) and the Rust model suite (3/3) executed and green; APK contents inspected; remote refs
verified.

**Verified but not applicable:** tag/branch signing — 23 GitHub signatures were stripped by necessity and
none was re-created, because a signature over changed content would be a lie.

**Not executed, with the reason:** the `chrome-lens-py` network path was exercised on the emulator and
passed, but no test was run on a *physical* device (none connected); nothing was run against a 32-bit
ABI (the emulator is x86_64 and the project's only other target is arm64, which no emulator image here
provides); the `BookCoverMatcher-QA` reports were not regenerated.

**Left unresolved on purpose:** the old objects (including the signed commits and the flagged originals)
still exist in `%TEMP%\bc-pre-rewrite.bundle` and `%TEMP%\bc-pre-gpl.bundle`, as backups must; a
`git gc --prune` on GitHub's side may keep them reachable from the reflog for a while. Publishing the
rewritten history cannot erase copies that others already fetched.

