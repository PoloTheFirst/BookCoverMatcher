# CHANGELOG.md — current-stable-release only · agent report

**Executed:** 2026-11-08 · **Repository:** `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher`
**Scope:** documentation only — `CHANGELOG.md`, plus the release-guide bullet that states the new
convention and one README pointer that contradicted it. No code, build, version or licensing change.

## 1 · Previous structure

`CHANGELOG.md` was **843 lines / 77,831 bytes** and cumulative:

| Property | Value |
|---|---|
| First line | `\ufeff# Scope every report by worksheet, follow the workbook's own order, read dates as dates` — a BOM plus a *release title*, not `# Changelog` |
| Release headings | **10**, newest first: `v2.5.0`, `v2.4.0-rc.7`, `v2.5.0-dev.5`, (three untitled blocks that belong to the dev.4/dev.3 sequence), `v2.5.0-dev.2`, `v2.5.0-dev.1`, `v2.4.0`, `v2.4.0-dev.4`, `v2.4.0-dev.3`, `v2.4.0-dev.2`, `v2.4.0-dev.1` |
| Separators | each entry ended with a bare `---` |
| Encoding | UTF-8 **with BOM**, and the file itself carried double-encoded runs (`â€"`, `â†’`, `Ã—`, `Â§`, `ãƒ–ãƒ«ã‚¢ã‚«`) from the same Windows-1252 round-trip the release entry describes |

## 2 · New structure

`# Changelog` → one `## [2.5.0] — 2026-11-08` section → `### Added`, `### Fixed`, `### Tests`,
`### Verified`, `### Known limitations` → a three-line footer saying older releases live in the tags,
the GitHub Releases and the release commits. **5,177 bytes, UTF-8 without BOM, zero mojibake.**

What changed relative to the old v2.5.0 entry, and why:

1. **Heading** `## v2.5.0 (2026-11-08)` → `## [2.5.0] — 2026-11-08`, and the document title became
   `# Changelog`.
2. **Encoding repaired** (`→`, `—`, `×`, `§`, `ブルアカ`) with the same provable cp1252→UTF-8 round-trip
   used for `THIRD_PARTY_NOTICES.md`; 5 em dashes, 3 arrows, 1 `×`, 1 `§`, 2 `ブルアカ` now render.
3. **One new `### Added` section** (2 bullets) so the release's headline is not silently dropped: the two
   selectable OCR engines and the packaged PP-OCRv6 assets. Both are taken from the release's own
   records (§3) — the old entry mentioned the engines only in its opening paragraph.
4. **The quoted corrupted sample was reworded.** The old bullet embedded the literal damaged characters
   (`Ã¢â‚¬…`) as an example; that would have left mojibake in the new file, so the defect is now described
   in words (*"a mangled em dash … survived as three double-encoded characters"*) and the correct string
   is quoted instead. The meaning, the root cause, the constant name, the 109-lines/4-files repair and the
   guard test are all preserved verbatim.
5. Blank line added after the title and before the closing `---` — without it the last paragraph would
   have rendered as a setext H2 heading.

## 3 · Sources used to confirm the v2.5.0 content

| Source | What it established |
|---|---|
| `app/build.gradle.kts` | `versionName = "2.5.0"`, `versionCode = 221` — the heading and the version line |
| Tag `v2.5.0` → commit `ab9c69778e03…`, parent `ccb61811…` *"Release v2.5.0: original profile images, folder placeholders, readable cache-clear text"* | the release commit, and that the tag points at it |
| `historical_prompt/v2.5.0-ag.md` §11 | 981 JVM tests, 0 failures, 7 skipped, 119 classes (the `### Verified` figure) |
| `historical_prompt/v2.5.0-ag.md` §12 | 18 instrumentation testcases on the emulator; the routing suite proves Chrome Lens vs PP-OCRv6 selection, persistence and no fallback (basis of the first `Added` bullet) |
| `historical_prompt/v2.5.0-ag.md` §13 | packaged assets `det.onnx` 62,032,837 B, `rec.onnx` 76,554,979 B, `ppocrv6_dict.txt` 74,947 B, stored uncompressed (basis of the second `Added` bullet), ABIs `arm64-v8a`/`x86_64` |
| `historical_prompt/v2.5.0-ag.md` §15 | what the release itself said the changelog entry should contain: version, the four fixes with root causes, the new tests, what was verified, the limitations — i.e. the structure that was kept |
| The old `## v2.5.0` entry (author-written at release time) | the four fixes with root causes, the three test classes, the two known limitations — reused verbatim apart from the points in §2 |

Nothing in the new file is invented: every bullet is either the old entry's own text or a statement
directly traceable to the rows above. The older entries were **not** re-checked for accuracy — they were
removed, not reused.

## 4 · Documentation updated for the convention

Only one document states the policy, so it cannot drift into two versions:

* **`CODING_AGENT_PROMPT.md`** (the existing release/contributor guide, §*documentation to update*): the
  `CHANGELOG.md` bullet now reads *“the current stable release only, never a cumulative history”*, and
  spells out: replace the previous entry, one `## [<version>] — <date>` section, heading must match
  `versionName`, earlier releases live in the Git tags / GitHub Releases / release commits, those records
  are never deleted or rewritten to shorten the file, and nothing unimplemented may be listed.
* **`README.md` §10**: the sentence *“Later releases live in `CHANGELOG.md`”* was the one place that
  assumed a cumulative file. It now says the releases after 2.2.x *“live in the Git tags and the GitHub
  Releases”*. The 2.2.4/2.3.0/2.3.1 record that follows is unchanged.


## 5 · Verification results

| Check | Command / method | Result |
|---|---|---|
| Exactly one release entry | parse `^#{1,3} ` in the file | 7 headings: `# Changelog`, `## [2.5.0] — 2026-11-08`, `### Added`, `### Fixed`, `### Tests`, `### Verified`, `### Known limitations`; exactly **1** `## [<version>]` |
| No older releases | search headings for `2.[0-4]` | **none** |
| Encoding | first 3 bytes; strict UTF-8 decode | no BOM, decodes cleanly, **5,177 bytes** |
| Mojibake | cp1252→UTF-8 round-trip detector over every non-ASCII run | **0 runs**; only intended characters remain (`—` ×5, `→` ×3, `×` ×1, `§` ×1, `ブルアカ` ×2) |
| Markdown structure | code-fence parity, blank line after the title, blank line before the closing `---` | balanced; no accidental setext heading |
| Content consistency | cross-read against `historical_prompt/v2.5.0-ag.md` §11–§15 and the release commit | the four fixes, the three test classes, 981/0/7, the emulator run, both ABIs and the packaged model sizes all match |
| Conflicting cumulative assumptions | grep `cumulative`, `Later releases live`, `CHANGELOG` across the tracked `*.md` | the only two were `README.md` §10 and `CODING_AGENT_PROMPT.md` §*documentation*; both corrected |
| Nothing else touched | `git diff --stat`, `git diff --name-only -- app/build.gradle.kts` | 3 files, 79 insertions / 946 deletions; `app/build.gradle.kts` **unchanged**, `versionName` still `2.5.0` / `versionCode` 221 |
| History still retrievable | `git show <tag>:CHANGELOG.md`, `GET /releases` | `v2.5.0` 843 lines / 10 release headings, `v2.4.0` 494/6, `v2.3.0` 224/1, `v2.2.0` 84/4 — the removed entries are intact inside the tags; GitHub holds **21 Releases** for the historical tags |

No build or test suite was run: this task changes documentation only and no workflow validates
`CHANGELOG.md`.

## 6 · Files modified

| File | Change |
|---|---|
| `CHANGELOG.md` | rewritten: 843-line cumulative file → v2.5.0-only entry (79 insertions, 946 deletions by `git diff --stat`) |
| `README.md` | one sentence in §10: the "later releases live in CHANGELOG.md" pointer now names the tags and GitHub Releases |
| `CODING_AGENT_PROMPT.md` | the `CHANGELOG.md` bullet replaced with the single-current-release policy (5 lines → 7) |
| `historical_prompt/changelog-current-release-ag.md` | this report (new) |

Nothing else was staged or edited. Previous file kept for recovery at `%TEMP%\CHANGELOG.before.md`.

## 7 · Git state

* Branch: **`online_improvement`** at `3ee20c4` (*feat(licensing): rewrite the whole history, license
  dev, and record the final outcome*), in sync with `origin/online_improvement`.
* `git status --porcelain`:

```
 M .idea/deploymentTargetSelector.xml      <- pre-existing, the owner's
 M CHANGELOG.md                            <- this task
 M CODING_AGENT_PROMPT.md                  <- this task
 M README.md                               <- this task
 M gradle/libs.versions.toml               <- pre-existing, the owner's
?? BookCoverMatcher-QA/                    <- pre-existing, untracked
?? hs_err_pid4316.log                      <- pre-existing, untracked
?? historical_prompt/changelog-current-release-ag.md   <- this report
```

* **Nothing is staged, no commit was created and nothing was pushed** — the task did not authorise it.
  The two owner modifications and the untracked QA directory were left exactly as found.
* No tag, branch or historical commit was touched; the 22 rewritten tags from the previous migration
  still point where they did.

## 8 · Unresolved issues and assumptions

1. **The `### Added` section is authored, not copied.** It is derived from the release's own opening
   paragraph and from `v2.5.0-ag.md` §12–§13, but the exact sentences are mine. If the project prefers a
   verbatim-only changelog, delete those two bullets — nothing else depends on them.
2. **The corrupted sample is described, not quoted.** Keeping `Ã¢â‚¬…` verbatim would have satisfied
   "preserve the evidence" but violated "no mojibake"; I chose the readable file. The old text is
   recoverable from `%TEMP%\CHANGELOG.before.md` and from `git show v2.5.0:CHANGELOG.md`.
3. **GitHub Releases were verified, and one is missing.** `GET /repos/PoloTheFirst/BookCoverMatcher/releases`
   returns **21 releases** — every historical tag from `v1.0.0` to `v2.5.0-dev.4` has one — but
   `GET …/releases/tags/v2.5.0` returns **404**: the *current stable release* has **no GitHub Release**.
   The footer of `CHANGELOG.md` is therefore accurate (it speaks about **older** releases), while the
   record of v2.5.0 itself is the annotated tag `v2.5.0`, the release commit `ccb6181` and
   `historical_prompt/v2.5.0{,-ag}.md`. Creating that Release was outside this task's scope.
4. **`CODING_AGENT_PROMPT.md:213-214` still says** *"`git show HEAD:CHANGELOG.md` returns an empty file"*.
   That was true of the audit snapshot dated 2026-10-22 (`HEAD cb8a253`) and is false today. It is left
   untouched on purpose: it is explicitly a dated audit record, not a policy statement, and line 42 tells
   readers to trust the repository over that document. Flagged here so it is a decision, not an oversight.
5. **`v1.0.0` / `v1.1.0` never had a `CHANGELOG.md`** (`git show v1.0.0:CHANGELOG.md` is empty), so for
   the earliest releases there is no changelog history in any tag — only the commits.
6. The figures carried inside the release entry — "109 lines across 4 files" and the 981/7-skipped suite
   result — are the release's own recorded numbers; they were cross-checked against `v2.5.0-ag.md` §11
   but not re-measured here.

