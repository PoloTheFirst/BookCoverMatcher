# Changelog Writing Guidelines for Coding Agents

## 1. Purpose

Every release must have a clear, accurate, and self-contained changelog entry.

The changelog must explain what the release delivers, what changed, what was fixed, how it was tested, what was independently verified, and what limitations remain.

These guidelines apply to **both stable and development versions**.

The repository's `CHANGELOG.md` is a single-current-release document, not a cumulative historical changelog. It must contain only the entry for the current release being documented. Historical release information remains available through Git tags, GitHub Releases, release commits, and agent reports.

## 2. Required format

Every changelog entry must follow this exact Markdown structure:

```markdown
## vX.Y.Z (YYYY-MM-DD)

# [Release Heading]

[Brief conclusion describing the release, its purpose, and its most significant changes.]

### Added

- [New features, capabilities, components, or supported workflows.]

### Changed

- [Changes to existing functionality, behaviour, architecture, UI, performance, or configuration.]

### Fixed

- [Bugs and defects resolved, including their previous behaviour and corrected behaviour.]

### Removed

- [Features, dependencies, code paths, or behaviours deliberately removed.]

### Tests

- [Automated tests added, modified, or executed, with exact results.]

### Verified

- [Manual, emulator, physical-device, APK, integration, packaging, or other independent verification.]

### Known limitations

- [Remaining limitations, unsupported cases, untested environments, compatibility constraints, or known defects.]

**Version code/name: XXX / X.Y.Z.**

```

### 2.1 Development versions in the two version fields

`vX.Y.Z` and `X.Y.Z` above stand for the **complete release version**, not only a three-number stable version. When
the release is a development build whose version name carries a suffix, the suffix belongs to both fields, and the
two must print the same string:

```markdown
## v2.6.0-dev.1 (2026-11-09)

# Development Checkpoint: …

…sections…

**Version code/name: 222 / 2.6.0-dev.1.**
```

Rules:

- The heading is always `v` + the complete `versionName`: `v2.6.0-dev.1`.
- The final line prints `versionCode` / `versionName` exactly as configured, suffix included: `222 / 2.6.0-dev.1`.
- A stable release has no suffix, so it reads `## vX.Y.Z (YYYY-MM-DD)` and `**Version code/name: XXX / X.Y.Z.**`.
- The two must never disagree. A heading of `v2.6.0-dev.1` beside a final line of `2.5.0` means one of them is
  wrong, and the entry cannot be published until they agree.
- Never invent a version code for a development build. Raise `versionCode` in the build configuration, rebuild, and
  report the value the artifact actually carries.

All headings and sections are required, even when a section has no applicable content.

For an empty section, write:

`- None.`

For Known limitations, if there are genuinely no known limitations after appropriate investigation, write:

`- None known.`

Do not silently omit a required section.

## 3. Version heading, release title, and conclusion

These are three distinct elements. Do not combine them.

### 3.1 Version and date heading

Use this exact pattern:

`## vX.Y.Z (YYYY-MM-DD)`

Requirements:

- Use the `##` Markdown heading level.
- Include the `v` prefix.
- Use the actual release version.
- Use the actual release date in `YYYY-MM-DD` format.
- Do not guess the date from the machine clock.
- For development releases, use the actual development version identifier consistently with the repository's versioning convention. The heading is `v` plus the complete `versionName`, so a build named `2.6.0-dev.1` is headed `## v2.6.0-dev.1 (YYYY-MM-DD)` and never `## v2.6.0` or `## v2.5.0`.

### 3.2 Release title

Place a separate `#` heading immediately after the version/date heading.

Example:

`# Stable Release of the 2.5 Line`

Requirements:

- Use exactly one `#` heading.
- Describe the main purpose or identity of the release.
- Keep it concise, informative, and technically accurate.
- Avoid promotional language, exaggerated claims, or vague titles such as "Major Improvements".
- Do not simply repeat the version number.
- Do not put the conclusion inside this heading.

Examples:

```markdown
# Stable Release of the 2.5 Line
```

```markdown
# Native OCR Integration and Online Search Improvements
```

```markdown
# Development Release: OCR Reliability Improvements
```

The title should reflect the actual contents of the release, not merely the intended roadmap.

### 3.3 Brief conclusion

Place a separate introductory paragraph immediately below the release title.

Normally use 2–4 sentences.

It should explain:

1. What this release represents.
2. Its most important new capabilities or improvements.
3. The major fixes or changes included.
4. Whether existing functionality or architecture was intentionally preserved, when relevant.

Example:

```markdown
## v2.5.0 (2026-11-08)

# Stable Release of the 2.5 Line

The first stable release of the 2.5 line introduces two selectable OCR engines: Chrome Lens and native PP-OCRv6 Medium. It also fixes four user-reported defects involving session-cache messages, saved profile images, and wishlist dialog placeholders. Existing parsing, matching, and catalogue rules remain unchanged.
```

Avoid generic statements such as "This release includes various improvements."

Do not claim that a release is stable unless it is actually a stable release.

## 4. Section-specific writing rules

### 4.1 Added

Document newly introduced capabilities.

For each significant feature, explain:

- What was added.
- Where users can access or use it.
- How it works at a high level.
- Important implementation details when they affect functionality.
- Relevant restrictions or behaviour users should understand.

Example:

- **Two selectable OCR engines.** READ THE COVER now supports Chrome Lens and native PP-OCRv6 Medium. Users can select the engine for each request, and the selection is persisted. A request does not silently fall back to the other engine.

Do not classify an existing feature as Added merely because its implementation was rewritten.

### 4.2 Changed

Document modifications to existing functionality.

Explain:

- Previous behaviour.
- New behaviour.
- Why the change matters.
- Whether existing data, settings, or workflows remain compatible.

Example:

- **OCR model packaging.** PP-OCRv6 Medium detector and recognizer models are now bundled in the APK instead of requiring manual installation. The engine can initialize on a clean installation without a separate model download.

Do not repeat the same change in multiple sections.

### 4.3 Fixed

Document actual defects that were corrected.

Each significant entry should describe:

1. The previous incorrect behaviour.
2. Its root cause, if established.
3. The correction.
4. A regression test or other evidence that prevents recurrence.

Example:

- **Cache-clear confirmation text.** The confirmation message previously displayed a corrupted em dash due to Windows-1252/UTF-8 double encoding. The message now displays the correct text, and a regression test checks the exact sentence and rejects the corrupted characters.

Do not claim a root cause unless it was investigated and confirmed.

### 4.4 Removed

Document intentionally removed functionality or dependencies.

Explain:

- What was removed.
- Why it was removed.
- Whether users need to migrate data, settings, or workflows.
- Whether the removal affects compatibility.

Do not list temporary development files, generated build artifacts, or unrelated cleanup as user-facing removals.

If nothing was removed, write:

`- None.`

### 4.5 Tests

This section records **automated test activity**, not general claims that the application works.

For every test suite, state:

- Suite or test name.
- What it covers, where useful.
- Number passed.
- Number failed.
- Number skipped or ignored.
- Exact command or report location when useful.

Example:

- Full JVM suite: **986 passed, 0 failed, 7 skipped**.
- `OriginalProfileImageTest`: **8 passed**, covering original-image persistence, isolation, deletion, legacy records, and folder copying.
- Rust OCR real-model suite: **3 passed, 0 failed**, using the pinned PP-OCRv6 Medium models.

Rules:

- Never count skipped tests as passed.
- Never report tests that were merely written as tests that were executed.
- Clearly distinguish unit, integration, instrumented, emulator, and real-model tests.
- Record environmental blockers and explain why a suite could not run.
- Do not conceal failures or change test counts to make a release look better.
- If a test suite was not run, state that explicitly.
- Do not imply that a test passed merely because its test code compiled.

### 4.6 Verified

This section records **independent verification beyond ordinary automated test execution**.

Examples include:

- Successful installation and launch of the release APK.
- Manual verification of a user workflow.
- Emulator or physical-device verification.
- Confirmation that required assets are packaged inside the APK.
- Confirmation of APK version name, version code, ABI contents, and signature.
- Verification that an OCR engine actually used the intended native implementation.
- Verification that persisted data survives process termination and restart.
- Verification of remote Git refs, release tags, or published artifacts.

Example:

- Release APK: verified that PP-OCRv6 Medium detector, recognizer, and dictionary are packaged and available on a clean installation.
- Emulator: verified that a captured profile displays its original image after application process restart.

Do not duplicate the entire Tests section here. Use this section for verification that provides additional evidence.

A successful build alone does not prove that a feature works.

### 4.7 Known limitations

Document remaining limitations that users, testers, or maintainers should know about.

Include, where applicable:

- Known defects that remain unresolved.
- Unsupported devices, architectures, or Android versions.
- Features that have not been verified.
- Data migration limitations.
- Performance or resource constraints.
- Model or provider restrictions.
- Compatibility limitations.
- Test suites skipped because the required environment was unavailable.

Be precise about the difference between:

- Known to be unsupported.
- Known to be defective.
- Not yet tested.
- Not applicable.

Do not describe an untested feature as broken, and do not describe an untested feature as verified.

Example:

- Profiles saved before v2.5.0 do not have an original-image reference. The original cannot be reconstructed from the thumbnail.
- Physical-device testing was not performed because no physical device was available.

If there are no known limitations, write `- None known.` only after appropriate investigation.

## 5. Technical detail and writing quality

### 5.1 Be specific

Prefer concrete descriptions over vague statements.

Weak:

- Improved OCR.
- Fixed image handling.
- Updated UI.
- Improved performance.

Good:

- Native PP-OCRv6 Medium now performs detector and recognizer inference through the bundled ONNX Runtime on Android.
- Original captured profile images are stored separately from 44×58 row thumbnails and survive repository reload.
- The profile detail view displays the original image without cropping, with its height capped at 240 dp.

### 5.2 Include implementation details selectively

Include technical details when they help explain:

- A significant architectural change.
- A compatibility decision.
- A performance or storage trade-off.
- A security or licensing consideration.
- An important implementation constraint.

Use actual class names, module names, file paths, model versions, and dependency versions when useful.

Do not turn the changelog into a complete implementation report.

Detailed code changes, commit hashes, full test logs, benchmark data, debugging history, and extensive implementation details belong in the agent report.

### 5.3 Avoid duplication

Each change should have one primary category:

- New capability → Added.
- Modification of existing behaviour → Changed.
- Correction of incorrect behaviour → Fixed.
- Deliberately discontinued capability → Removed.
- Automated test execution → Tests.
- Independent confirmation → Verified.
- Remaining constraint → Known limitations.

A single feature may be referenced in more than one section only when each reference communicates different information.

### 5.4 Preserve factual accuracy

Every statement must be supported by actual repository changes, test output, build artifacts, or verification records.

Never:

- Invent test results.
- Claim an APK contains an asset without inspecting it.
- Claim a feature works merely because its code compiles.
- Claim a model is used merely because its files exist.
- Claim a device or ABI is supported without adequate evidence.
- Describe a planned feature as implemented.
- Present an assumption as an established root cause.
- Hide failed, skipped, or unexecuted tests.
- Claim a feature is complete when only part of its implementation has been delivered.

When evidence is unavailable, state the uncertainty explicitly.

### 5.5 Readability and length

The changelog should be detailed enough to explain meaningful changes but concise enough for users and maintainers to read.

- Use bullet points for individual changes.
- Use bold lead-in phrases to make significant changes easy to scan.
- Use inline code formatting for class names, paths, identifiers, commands, and technical values.
- Avoid unnecessarily long implementation narratives.
- Combine closely related changes when doing so improves clarity.
- Do not remove important compatibility or limitation information merely to shorten the document.

## 6. Version code and version name

The final metadata line must use:

`**Version code/name: XXX / X.Y.Z.**`

Here `XXX` is the actual `versionCode`, and `X.Y.Z` is the **complete `versionName`**, including any development
suffix: a build whose `versionName` is `2.6.0-dev.1` and `versionCode` is `222` ends with
`**Version code/name: 222 / 2.6.0-dev.1.**`. Do not shorten the suffix away, and do not print a stable-looking
`2.6.0` for a development build.

Verify both values against the actual Android build configuration and release artifact.

Remember:

- `versionCode` is an integer.
- `versionName` is a string.
- The version code must not be guessed from the version name.
- Development releases must report their actual version code and version name.
- Do not copy metadata from an earlier release without verification.

If the project has multiple build variants, identify which artifact and version configuration the reported values refer to.

## 7. Stable and development release handling

These guidelines apply equally to stable and development releases.

### 7.1 Stable release

- Document the actual stable release.
- Do not include changes that belong only to an unreleased development branch.
- Use the stable version's verified version code and version name.
- Describe the release accurately without overstating its maturity.
- Include only functionality that is actually present in the released build.
- Disclose known limitations relevant to users.

### 7.2 Development release

- Document the actual development version.
- Include implemented development changes, even if they are not yet part of a stable release.
- Clearly identify incomplete features, experimental behaviour, skipped verification, and unresolved migration work.
- Do not imply that a development feature is already included in the current stable version.
- Do not copy stable-release claims into a development entry unless they remain accurate for that development build.
- Distinguish implementation progress from a fully integrated and verified feature.

The same heading structure and all seven sections are required for development releases.

Do not append statements about these writing guidelines to CHANGELOG.md. Stable/development applicability belongs in this guideline document, not in a release log.

## 8. Workflow for coding agents

Before writing the changelog:

1. Inspect the current branch and working-tree status.
2. Identify the target release version and date.
3. Read the version configuration.
4. Review the actual changes since the previous relevant release.
5. Inspect relevant commits and implementation files.
6. Review the agent report and test outputs.
7. Inspect build artifacts when the changelog makes packaging or distribution claims.
8. Separate completed work from incomplete work.
9. Identify changes that belong to stable releases versus development-only work.
10. Draft the changelog using the required structure.
11. Cross-check every factual statement against evidence.

After writing:

1. Verify that all required headings exist and appear in the correct order.
2. Verify that the version/date heading is correct.
3. Verify that the release title is a separate `#` heading.
4. Verify that the conclusion is a separate paragraph beneath the title.
5. Verify that the version code/name match the actual configuration and artifact.
6. Check test counts against actual test output.
7. Check that no development-only changes are incorrectly attributed to a stable release.
8. Check that no unsupported claims or fabricated details exist.
9. Verify UTF-8 encoding without BOM.
10. Review the final Markdown for readability, consistency, and technical accuracy.

## 9. CHANGELOG.md maintenance policy

The repository's `CHANGELOG.md` must contain only the current release entry.

When preparing a new release:

- Replace the previous entry with the new release entry.
- Do not append the new release below older entries.
- Do not retain previous version headings in `CHANGELOG.md`.
- Preserve historical release information through Git tags, GitHub Releases, release commits, and agent reports.
- Do not delete historical release records elsewhere.
- Verify that the previous release has been published or otherwise preserved before replacing its changelog entry.

The same policy applies when updating the changelog for a development release, subject to the repository's chosen current-release documentation convention.

Do not create a cumulative changelog unless the project owner explicitly changes this policy.

## 10. Definition of Done

A changelog is complete only when:

- It follows the required Markdown structure.
- The version/date heading is correct.
- A separate, meaningful release title appears immediately below it.
- The conclusion is a separate paragraph below the release title.
- The version in the heading and the version in the final metadata line are the same string as `versionName`,
  including any development suffix.
- It documents only changes belonging to that release.
- Added, Changed, Fixed, and Removed entries are correctly categorized.
- Test results are accurate and distinguish passed, failed, and skipped tests.
- Verification claims are supported by actual evidence.
- Known limitations and untested areas are disclosed.
- Version code/name are verified.
- Stable and development release distinctions are respected.
- The content is readable, concise, technically meaningful, and factually accurate.
- `CHANGELOG.md` contains only the current release entry.
- Historical release records remain preserved elsewhere.
- The GitHub release body exactly equals the matching CHANGELOG.md text, verified through the API.
- The published APK uses bookcovermatcher-v<complete-versionName>.apk and its digest matches the intended artifact.


## 11. GitHub release log and APK filename

These rules apply to every stable and development release.

### 11.1 Release log is the exact changelog

The GitHub Release body's complete text must be **identical to the matching release's `CHANGELOG.md` contents**. Use that file directly as the release body; do not write a shorter summary, add an introduction, append installation/signing notes, generate release notes automatically, or omit any section. Required artifact/signing warnings belong in CHANGELOG.md itself if they need to appear in the release log.

Preserve the complete version suffix, Markdown, ordering, whitespace and final newline. After creating or updating the release, fetch its body through the GitHub API and compare the entire returned string against the file's UTF-8 text. Visual similarity or checking only headings is insufficient. A later version replacing the repository's single-current-release changelog does not authorize overwriting older releases; each release keeps its own version's published changelog snapshot.

If the current release's changelog is intentionally corrected, synchronize that same release's body and verify equality again.

### 11.2 Public APK filename

Use this exact distribution filename:

`bookcovermatcher-v<complete-versionName>.apk`

Examples:

- Stable: `bookcovermatcher-v2.7.0.apk`.
- Development: `bookcovermatcher-v2.7.0-dev.1.apk`.

Do not append `-debug`, `-release`, a version code, date, architecture or any other suffix to the public APK name unless the owner explicitly requests a different multi-artifact convention. Identify the actual build/signing variant honestly in the changelog/report instead of changing the filename. Gradle's internal app-debug.apk/app-release.apk paths may remain unchanged; copy or name the uploaded distribution asset correctly.

After upload or rename, verify the public asset's exact name, size and digest against the intended APK. Renaming an existing asset must not substitute different bytes, create a duplicate APK, or move the release tag. Record the final download URL in the agent report.


### 11.3 GitHub release title

The GitHub release title/name must be exactly the release tag: `v` followed by the complete versionName, including a development suffix. Examples: `v2.7.0` and `v2.7.0-dev.1`. Do not prepend BookCoverMatcher, add a feature description, date, version code or other text. This simple GitHub title is separate from the descriptive release heading required inside CHANGELOG.md; retain that heading in the verbatim release body. Verify the GitHub API `name` exactly equals the intended tag after creation/update.
