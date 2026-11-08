# GPL-3.0-or-later — final migration status (OpenCLIP resolved, historical tags NOT migrated)

**Executed:** 2026-11-08 · **Repository:** `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher`
(`https://github.com/PoloTheFirst/BookCoverMatcher.git`) · **Intended licence:** `GPL-3.0-or-later`
for project-owned code only.

> **Outcome in one line:** the OpenCLIP weights are now **verified MIT** (the biggest open question is
> closed) and the flagged assets are audited; the historical-tag migration is **not done** — the tool
> that performs it exists and analyses all 22 tags, but its tree rebuild does not take effect, so no tag
> was moved and nothing was force-pushed. The public distribution audit therefore remains **incomplete**.

## A · Verified current state (checked, not taken from the previous report)

| Check | Result |
|---|---|
| Branch | `online_improvement` @ `0732a0a`, in sync with `origin/online_improvement` |
| `origin/main` | `0732a0a` (local `main` was 9 behind; fast-forwarded) |
| `origin/dev` | `beb8b81` — the owner's LICENSE commit, **untouched, not pushed** |
| Tags | 22 local, 22 remote (36 remote refs including peeled `^{}`) |
| Worktree | only the owner's `.idea/deploymentTargetSelector.xml` and `gradle/libs.versions.toml`, plus untracked `BookCoverMatcher-QA/` and `hs_err_pid4316.log` — none staged |
| Backup bundle | `%TEMP%\bc-pre-gpl.bundle`, **306,757,411 bytes**, `git bundle verify` → "records a complete history" |
| Ref inventory | `%TEMP%\bc-refs-before.txt` (30 refs with hashes) |
| Licensing tooling | `tools/apply-license-headers.py`, `tools/check-licenses.py`, `LicensingPolicyTest`, `syncLicenseAssets`, More → Licences — all present |
| Previous report | `historical_prompt/gpl-v3-retroactive-ag.md` present and consistent with the above |

## B · OpenCLIP int8 weights — RESOLVED as MIT

**Identification** (from `app/src/main/assets/models/openclip_image.json`, not from the filename):

| Field | Value |
|---|---|
| Architecture / pretrained | `ViT-B-32` / `laion2b_s34b_b79k` |
| Upstream repository | `laion/CLIP-ViT-B-32-laion2B-s34B-b79K`, revision `1a25a446712ba5ee05982a381eed697ef9b435cf` |
| Declared licence | **`mit`** — `cardData.license = "mit"`, the `license:mit` tag, and "License: mit" on the model card (queried 2026-11-08) |
| Upstream size | 151,277,439 parameters, `isQuantized: false` upstream |
| Our artefact | `openclip_image.onnx`, sha256 `d0ee5e32…`, 96,919,211 bytes, int8, 224×224 input, 512-d output |
| Conversion | `tools/export_openclip_onnx.py` — open_clip 3.3.0, torch 2.14.1 (open_clip code MIT); **image tower only**, no calibration dataset, no extra third-party material |
| Distributed? | Yes — inside the APK at `assets/models/openclip_image.onnx` |

**Decision: Case A — redistribution rights verified.** MIT permits redistribution and derivative works,
so the int8 conversion may ship. Conditions and caveats, stated honestly:

* The weights are **MIT, not GPL-3.0-or-later**; they are documented as such and never relabelled.
* MIT requires the copyright/permission notice: the APK ships `THIRD_PARTY_NOTICES.md`, and the OpenCLIP
  row now carries LAION's attribution and the upstream revision.
* The licence is LAION's *declaration* on a model repository (that repo has no `LICENSE` file in its file
  listing); the model card/metadata is the authoritative statement relied on.
* LAION-2B, the training dataset, has its own terms. Only the weights' declared licence is relied on;
  no dataset content is redistributed.

`THIRD_PARTY_NOTICES.md` was updated accordingly: the row moved from **Unresolved** to **Verified (MIT)**

## C · Other flagged assets — audited, replacements NOT produced

| Asset | Origin | Redistribution | In APK | In tags | Status |
|---|---|---|---|---|---|
| `app/src/androidTest/assets/cover.jpg` | Photograph of a published book cover (OCR device-test fixture) | Third-party copyright; no permission on file | No (test APK only) | Yes, from the tag that introduced it | **Unresolved** — replace with an original synthetic cover before public distribution |
| `test_excel/doujin.xlsx` (40 MB) | Owner's personal catalogue workbook | Owner's own data, not project code | No | Yes | **Flagged** — keep out of public distribution, or ship a generated synthetic workbook |
| `app/src/test/resources/fixtures/*.html` (4) | Captured DLsite / Melonbooks search pages | Third-party markup; no licence | No | Yes | **Unresolved (low risk)** — replace with hand-written minimal HTML preserving the parse cases |

None was relicensed, none was removed (that would delete real test coverage), and no replacement fixtures
were produced in this pass: the parser tests depend on the captured markup and the workbook fixture feeds
the workbook/orchestration tests. **This is the second reason the distribution audit is incomplete.**

## D · Historical tags — NOT migrated (blocked; nothing pushed)

Verified per tag: `LICENSE` exists only on `v1.0.0` and `v1.1.0`; the other 20 (`v2.0.0` …
`v2.5.0-dev.4`) have no licence file and no SPDX headers. Unchanged by this pass.

**Strategy implemented in `tools/migrate-historical-license.py`:** an *additive* per-tag licensing commit
instead of a 387-commit `fast-export`/`fast-import` rewrite — build a new tree from the tag's own tree
plus `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` and the SPDX headers, commit it with the tagged commit
as parent, and move the tag to it. Why: no branch history is rewritten (the already-published licensing
work on `main`/`online_improvement` is preserved), ancestry stays intact, and the operation is trivially
reversible. Consequences to accept: a tag then points at a licensing child of the release commit rather
than the release commit itself, and the 14 annotated tags get new tag object hashes (their original
messages are preserved inside the new tag message).

**What actually happened:** the script runs and analyses every tag correctly (35 / 52 / 78 … 315 headers
needed per snapshot) but **no tag changed** — `git write-tree` keeps returning the original tree, so the
rebuilt temporary index is not being used. Two bugs were found and fixed (`git cat-file --batch` needed
byte input; `git update-index --index-info` needs the stage field) and a third remains: the
`GIT_INDEX_FILE` plumbing does not take effect as written. Because the tool is unproven, **nothing was
pushed** — no tag ref, no branch, no force-update; `git ls-remote` still shows the 22 original tags.

**To finish:** either fix the temporary-index handling (prove it on one tag first: `git read-tree`,
`git update-index --index-info`, `git write-tree` with `GIT_INDEX_FILE`), or switch to the
`fast-export | transform | fast-import` rewrite, which changes all 387 commit hashes and every tag hash.
Either way the gates stand: `LICENSE` sha256 `3972dc97…` in every tag, one header-bearing file spot-checked
per tag, `gradlew` still `Apache-2.0`, then a per-tag scoped
`git push --force origin refs/tags/<name>:refs/tags/<name>` — and never `dev`.

## E · Validation performed in this pass

| Check | Command | Result |
|---|---|---|
| Licensing audit | `python tools/check-licenses.py` | passed with the previous notices file; the OpenCLIP row edit does not touch any checked invariant (`Unresolved` still appears for Chaquopy and the flagged assets) |
| Historical analysis | `python tools/migrate-historical-license.py` (dry run **and** `--apply`) | all 22 tags analysed; **0 migrated** (§D) |
| OpenCLIP provenance | Hugging Face model API + model card | MIT, revision `1a25a446…` |
| Unit tests / APK build | **not run in this pass** | no application source changed (only `THIRD_PARTY_NOTICES.md` and a new tool script); the previous run — 986 tests, 0 failures, 7 skipped — is reported as history, not re-claimed |
| Remote state | `git ls-remote` | `main` = `online_improvement` = the previous line, `dev` = `beb8b81` |

## F · What is true, and what is not

**True:** the OpenCLIP weights are MIT (declared by LAION) and ship with attribution and the notice file;
project-owned code on `main` and `online_improvement` is `GPL-3.0-or-later`; the third-party inventory
distinguishes verified, flagged and unresolved material; the backup bundle is intact.

**Not true:** "every historical release carries the licence" — 20 of 22 tags do not; "the repository is
entirely GPL-3.0-or-later" — models, binaries, dependencies and the three flagged assets keep their own
terms; "the APK is ready for public distribution" — the test cover photograph, the personal workbook and
the captured page fixtures still have no established redistribution right.

**Recovery:** original state is restorable from `%TEMP%\bc-pre-gpl.bundle` (verified complete) via
`git clone bc-pre-gpl.bundle <dir>`, with `%TEMP%\bc-refs-before.txt` listing the 30 original refs. No ref
was rewritten in this pass, so no recovery is currently needed.

with provenance and conversion details, and the summary line no longer calls the weights unresolved.
