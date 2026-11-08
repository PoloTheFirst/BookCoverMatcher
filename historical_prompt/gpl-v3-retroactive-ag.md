# GPL-3.0-or-later licensing migration — verification and correction report

**Executed:** 2026-11-08 · **Repository:** `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher`
(`https://github.com/PoloTheFirst/BookCoverMatcher.git`) · **Branches:** `online_improvement`, `main`,
`dev` (not pushed) · **Intended licence:** **GPL-3.0-or-later**.

---

## 1 · What the previous migration actually accomplished

Nothing was committed. Verified directly, not from a report (there was **no** previous agent report —
`historical_prompt/gpl-v3-retroactive-ag.md` did not exist):

| Claim | Actual state found |
|---|---|
| "GPL-3.0-only applied across the repository" | **No commit, no push, no history rewrite.** `HEAD` was still `2722bc0` (the v2.5.0 report commit) |
| Root LICENSE added | Present but **untracked**; byte-identical to the official text (35,149 B, blob `f288702d…`) |
| NOTICE / THIRD_PARTY_NOTICES.md | Present but **untracked**; both written for `GPL-3.0-only` |
| SPDX headers | **None.** `git grep SPDX -- '*.kt'` on `HEAD` matched nothing (only `gradlew`/`gradlew.bat`, which carry Gradle's own `Apache-2.0`) |
| Historical tags migrated | **No.** Of 22 tags only `v1.0.0` and `v1.1.0` contain a `LICENSE` |
| Audit tooling / in-app licences | **None existed** |

Two separate facts also came to light, both from history itself:

1. **The project was GPLv3 from the start.** `v1.0.0` (commit `1d2ceba`, "LICENSE Adding") and `v1.1.0`
   ship the canonical text (blob `f288702d2fa16d3cdf0035b15a9fcbc552cd88e7`). Commit `a6baaac`
   ("feat!: v2.0.0 native Android rewrite") **deleted it**, which is why `v2.0.0`…`v2.5.0` have no
   licence file. This migration is therefore a *restoration* plus a statement of the "or later" option.
2. **The project owner added `LICENSE` to the remote branches** while this task ran — `40fd553` and
   `c0f7cce` on `main`, `2179cb1` on `online_improvement`, `beb8b81` on `dev` — all carrying the same
   canonical blob. My first push was rejected as non-fast-forward; I did **not** force anything, merged
   both remote heads into the licence commit (`9c04a97`, `814af31`), re-ran the audit, and pushed
   normally. The remote history is therefore linear and contains every one of those commits.

## 2 · The correction: `GPL-3.0-only` → `GPL-3.0-or-later`

No committed file ever carried `GPL-3.0-only`, so the correction applied to the **working drafts** and
to the identifier chosen for the codebase:

* `NOTICE` — now reads "either version 3 of the License, or (at your option) any later version", and
  names `GPL-3.0-or-later` as the SPDX identifier used in the sources.
* `THIRD_PARTY_NOTICES.md` — every occurrence updated; the document now says explicitly that the
  project cannot relicense the third-party material.
* `README.md` — new "License" section stating GPLv3-or-later, the user's right to use any later version,
  where the full text lives, and which components it does **not** cover.
* `native/ocr/Cargo.toml` — `license = "GPL-3.0-or-later"`.
* **317 source files** received `SPDX-License-Identifier: GPL-3.0-or-later` (Kotlin, Gradle KTS, Rust,
  Python, PowerShell, ProGuard, Android XML).
* In-app: the More tab now has a **Licences** panel with the GPLv3 text and the third-party notices,
  read from the APK's own assets.

`LICENSE` itself needed no change — the SPDX "or later" option is expressed in the source and the
notices, not by editing the canonical GPLv3 text. It stays byte-identical to
`https://www.gnu.org/licenses/gpl-3.0.txt` (sha256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`),
now pinned in both the audit script and the unit test.

### Header insertion details

* Language-correct comment syntax; inserted **after** a shebang (`tools/*.py`) and after an XML
  declaration (`AndroidManifest.xml`, `res/values/*.xml`), so nothing became invalid.
* Idempotent: `tools/apply-license-headers.py` reports `added / corrected / already correct / excluded`.
* **Six files carried a UTF-8 BOM.** The first pass wrote the header *before* the BOM, which put a BOM
  on line 3 of `WishlistRepository.kt` and broke the Kotlin compile (998 errors). The BOMs were removed,
  `README.md`/`CHANGELOG.md` were handled (`CHANGELOG.md` restored byte-for-byte), and the audit now
  **fails** on any byte-order mark so the same mistake cannot recur.

## 3 · Files deliberately excluded from headers

| Excluded | Why |
|---|---|
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Gradle's own Apache-2.0 files; their identifier is preserved and the audit *requires* it |
| `native/ocr/assets/ppocrv6_dict.txt` | PaddleOCR Apache-2.0 data; must not claim the project's licence |
| `app/src/main/assets/models/openclip_image.json` | OpenCLIP metadata; third-party provenance |
| `native/ocr/Cargo.lock`, `app/release/output-metadata.json` | generated |
| `app/src/test/resources/fixtures/*.html` | captured DLsite / Melonbooks pages (third-party markup) |
| `app/src/androidTest/assets/cover.jpg`, `test_excel/doujin.xlsx` | third-party/copyrighted content (see §5) |

## 4 · Verification performed

| Check | Command | Result |
|---|---|---|
| Licensing audit (tree) | `python tools/check-licenses.py` | **passed** — LICENSE sha256 + 5 structural markers, 317 files with the identifier, 0 with `GPL-3.0-only`, Gradle wrapper still Apache-2.0, PaddleOCR dictionary + OpenCLIP metadata unlabelled, docs consistent |
| Licensing audit (refs) | `python tools/check-licenses.py --refs` | 22 tags / 3 branches: `LICENSE` present on `main`, `online_improvement`, `dev`, `v1.0.0`, `v1.1.0`; **absent on the other 20 tags**; SPDX headers absent on every historical ref (§6) |
| JVM test suite | `.\gradlew.bat :app:testDebugUnitTest` | **986 tests, 0 failures, 7 skipped** (968 before this task; +5 `LicensingPolicyTest`, existing suites unchanged). The green run was on `9e7bd31`; the two merges after it changed **only** `LICENSE` (byte-identical to what was already there), so no source or behaviour changed |
| Compile | included above | Kotlin main + unit-test sources compile; the earlier BOM breakage is fixed and guarded |

`LicensingPolicyTest` enforces the same invariants inside the ordinary test run (LICENSE hash, no
missing/old identifiers, third-party preservation, documentation wording, document list, and that the
More screen and the Gradle task are actually wired), so a regression fails CI rather than a manual check.

## 5 · Third-party audit findings (retained and re-verified)

| Component | Licence | Status |
|---|---|---|
| PaddleOCR PP-OCRv6 det/rec ONNX + dictionary | Apache-2.0 | Verified |
| ONNX Runtime 1.20.0 (`libonnxruntime.so`) | MIT | Verified |
| `chrome-lens-py` 3.8.2 / `protobuf` 6.33.6 | MIT / BSD-3-Clause | Verified (PyPI metadata) |
| AndroidX, CameraX, Kotlin, coroutines | Apache-2.0 | Verified |
| JUnit 4.13.2 | EPL-1.0 | Verified (tests only) |
| Rust dependencies (65 crates, `Cargo.lock`) | MIT / Apache-2.0 / BSD / ISC / Zlib / Unlicense — all permissive | Verified from `cargo metadata` |
| **OpenCLIP int8 weights** (`openclip_image.onnx`, LAION `laion2b_s34b_b79k`) | **no licence declared by the publisher** | **Unresolved** — shipped inside the APK; the owner must decide (keep with a note, replace, or stop shipping) before the next public release |
| Chaquopy 17.1.0 | MIT per upstream docs; the POM has no licence block | **Unresolved (minor)** |
| `app/src/androidTest/assets/cover.jpg` (published cover photo), `test_excel/doujin.xlsx` (personal catalogue), `src/test/resources/fixtures/*.html` | third-party / owner data | **Flagged** — not relicensed; replace or exclude before public distribution |

**No third-party file was relicensed.** The Gradle wrapper's `Apache-2.0` identifier is preserved and the
audit fails if it is removed or replaced.

## 6 · Historical tags — NOT migrated, and what that requires

The earlier task's "Approach B" (every historical snapshot carries the licence) is **not satisfied**.
Verified state of the 22 tags: `v1.0.0` and `v1.1.0` already contain the canonical `LICENSE`; the other
20 (`v2.0.0` … `v2.5.0`) contain **no licence file** and no SPDX headers.

This task's instruction is not to rewrite history again if a normal commit suffices, and to report the
exact operations and risks first. A commit does not suffice for those tags, so here is the plan — not an
executed rewrite:

1. `git fast-export --all --signed-tags=strip --tag-of-filtered-object=rewrite` over the 387 commits
   reachable from all refs; a transformer that (a) injects `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`
   into every commit, (b) inserts the SPDX header into project-owned blobs using the same exclusion list,
   (c) leaves `gradlew` and the PaddleOCR/OpenCLIP assets untouched; then `git fast-import`.
2. Recreate the 14 annotated and 8 lightweight tags against the migrated commits, run `git fsck`, then
   build and run the full suite from the rewritten tree.
3. **Force-update** only `refs/heads/main`, `refs/heads/online_improvement` and the 22 `refs/tags/*`
   (never `dev`, never `--all --tags`), checking each expected old value first.

Risks: every commit hash changes (387 commits, 22 tags); existing clones need a re-clone or reset;
GitHub may reject tag force-updates; the 40 MB workbook and 96 MB model blob make the rewrite slow; and
`dev` would be left inconsistent with the rewritten history. None of this was executed.

**Rollback material already in place** (outside the repository): a full-history bundle
`%TEMP%\bc-pre-gpl.bundle` (292.5 MB; `git bundle verify` → *"The bundle records a complete history"*)
and a refs dump `%TEMP%\bc-refs-before.txt` (30 refs with hashes, taken before any change).


## 7 · Git and publication

| Step | Result |
|---|---|
| Licence commit | **`9e7bd31`** *Adopt the GPL-3.0-or-later licence across the repository* — 319 files, 2,043 insertions |
| First push | **rejected** (non-fast-forward): the owner had pushed `LICENSE` commits to all three branches meanwhile. Nothing was forced |
| Merges (no source change) | `9c04a97` (merge `origin/online_improvement`), `814af31` (merge `origin/main`) |
| `origin/online_improvement` | `814af31` |
| `origin/main` | `814af31` (fast-forward from `c0f7cce`) |
| `origin/dev` | `beb8b81` — the owner's own commit; **not pushed by this task** and not modified |
| Uncommitted owner changes | `.idea/deploymentTargetSelector.xml`, `gradle/libs.versions.toml`, `BookCoverMatcher-QA/`, `hs_err_pid4316.log` — preserved, never staged |
| Report commit | this file, pushed to `online_improvement` and `main` |

One incidental effect: stripping byte-order marks touched one file inside the untracked
`BookCoverMatcher-QA/` folder (a QA XML report); it is untracked, so nothing was committed for it.

## 8 · Honest status

| Label | Applies to |
|---|---|
| Implemented | GPL-3.0-or-later identifiers (317 files), LICENSE/NOTICE/THIRD_PARTY_NOTICES.md, README section, `Cargo.toml`, in-app licences, audit tooling |
| Unit tested | 986 tests, 0 failures (5 of them licensing policy) |
| Verified | audit passes on the tree; the push was verified on both remote branches |
| Published | `online_improvement` and `main` at `814af31` (plus this report commit) |
| **Not done** | **historical tag migration** (§6) — needs the rewrite above and the owner's go-ahead |
| **Unresolved** | OpenCLIP weight licensing, Chaquopy's POM, and the three flagged third-party assets |
| Not tested | no Android APK build/install in this pass (JVM tests + compile only); `syncLicenseAssets` did run inside `preBuild` during the test build |

## 9 · What is now true, and what is not

**True:** every line of code written for this project, on `main` and `online_improvement`, carries
`SPDX-License-Identifier: GPL-3.0-or-later`; the repository root holds the complete unmodified GPLv3
text; the notices state the "or later" option and identify the third-party material that is *not*
covered; the APK carries both documents and shows them in-app; and two independent checks
(`tools/check-licenses.py`, `LicensingPolicyTest`) fail if any of that stops being true.

**Not true:** "the repository is entirely GPL-3.0-or-later". The models, the ONNX runtime, the Python
wheels, the Rust crates, the captured page fixtures, the cover photograph and the sample workbook keep
their own terms — and the OpenCLIP weights in particular have **no declared licence**, which is a
decision the owner must make. Nor is the history retroactively licensed: 20 of 22 tags still have no
licence file, and that gap is deliberate until the rewrite in §6 is authorised.

| `.idea/*`, `gradle/libs.versions.toml`, `*.md`, `*.txt` | IDE/build configuration and documentation (covered by LICENSE/NOTICE, not per-file headers); `libs.versions.toml` also carries an uncommitted owner change that must not be mixed in |

## 10 . Addendum: commits and final verification

Two facts recorded after the sections above were written, so this report matches the pushed state:

* The header pass initially missed six project-owned files that live outside `app/src` and
  `native/ocr/src` (root `build.gradle.kts`, `settings.gradle.kts`, `app/proguard-rules.pro`,
  `native/ocr/examples/eval.rs`, `native/ocr/tests/*.rs`). They are committed in **`3340731`**
  *licence: finish the SPDX header pass on the root build files, ProGuard and the Rust tests*
  (6 files, 12 insertions), so the pushed tree equals the audited tree.
* Verified on the pushed `HEAD`: **321 files carry `SPDX-License-Identifier: GPL-3.0-or-later`** and
  **4 name `GPL-3.0-only`** - the three allowlisted detectors (`apply-license-headers.py`,
  `check-licenses.py`, `LicensingPolicyTest.kt`) plus this report. No project-owned source file carries
  the previous identifier.

| Ref | Commit |
|---|---|
| `origin/online_improvement` | `3340731` |
| `origin/main` | `3340731` |
| `origin/dev` | `beb8b81` (owner's commit, untouched by this task) |
| licence commit | `9e7bd31` (319 files) |
| merges of the owner's LICENSE commits | `9c04a97`, `814af31` |
| report commit | `b0fbae5` |
| header completion | `3340731` |
