# Documentation sync report — v2.4.0-rc.7

Every document below was changed only where the rc.7 implementation made the old text wrong or
incomplete. No historical changelog section was rewritten, removed or restored.

| File | Change |
|---|---|
| `app/build.gradle.kts` | `versionCode 213 → 214`, `versionName "2.4.0" → "2.4.0-rc.7"` |
| `app/release/output-metadata.json` | The tracked release metadata mirrors the same two values |
| `CHANGELOG.md` | New `## v2.4.0-rc.7 (2026-11-05)` section above `## v2.4.0`: the two fixes, the two new test classes, the emulator verification table, and the remaining limitations. `## v2.4.0` and every older section are untouched |
| `README.md` | Header line now names the current development build (`2.4.0-rc.7`, versionCode 214); new "QA remediation" section describing both fixes; the verification table gains a `v2.4.0-rc.7` row (790 tests, 96 classes, APK 195,194,901 B, 214/2.4.0-rc.7) and an emulator row, with the `v2.4.0` row (781 tests, 213) kept |
| `USER_GUIDE.md` | Two new paragraphs: a blank row inside a catalogue is reported (with the exact wording the user sees) and the range a fresh install starts on (`A1:Z5000`, typed ranges kept). The existing "Dates and percentages" heading was restored intact after the insertion |
| `ARCHITECTURE.md` | New rc.7 bullet: `CatalogueTruncation`, `truncationBelow`, the three surfaces, `structure:blank-row:<sheet>`, `Config.DEFAULT_RANGE`, and the plan-is-per-anchor reason a wide range is free. The "five features are sub-tabs" bullet it displaced was restored |
| `CODING_AGENT_PROMPT.md` | Release line: `v2.4.0` → `2.4.0-rc.7` (2026-11-05, versionCode 214) |
| `BookCoverMatcher-QA/reports/v2.4.0-rc.7/*` | The 14 reports of this round (see `release-readiness-report.md`) |
| `BookCoverMatcher-QA/scripts/rc7_*.py` | The three scripts used for this round's evidence |

## Version references checked for agreement

| Location | Value |
|---|---|
| `app/build.gradle.kts` | `versionCode = 214`, `versionName = "2.4.0-rc.7"` |
| `app/release/output-metadata.json` | `214` / `2.4.0-rc.7` |
| `CHANGELOG.md` v2.4.0-rc.7 section | `214 / 2.4.0-rc.7` |
| `README.md` header | `2.4.0-rc.7`, versionCode 214 |
| `CODING_AGENT_PROMPT.md` | `2.4.0-rc.7`, versionCode 214 |
| Built APK (`app/build/outputs/apk/debug/output-metadata.json`) | produced by Gradle from the same declarations |

The application id, package name, signing configuration and every other build setting are unchanged.
`versionName` follows the repository's existing convention of omitting a `v` prefix (`2.4.0-dev.4`,
`2.4.0`), so it reads `2.4.0-rc.7`; a Git tag, when one is created later, would be `v2.4.0-rc.7`.

## Documents reviewed and deliberately left alone

* `ONLINE_SEARCH.md` — its title already says `v2.4.0` and nothing in rc.7 affects it. Bumping it to a
  patch identifier would make it disagree with the code it documents.
* `historical_prompt/*` — history; the rc.7 prompt is the user's file and was not edited.
* `BookCoverMatcher-QA/reports/initial-qa/*` and `.../deepseek-qa-recovery/*` — prior evidence; read only.
