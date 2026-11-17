## v2.7.0-dev.2 (2026-11-17)

# Development Checkpoint: Privacy Documents and Publication Preparation

This development source checkpoint adds offline policy readers and original-derived branding assets, and prepares the Android build for further release testing. Current discovery providers are preserved. Google Play submission and OAuth production changes remain deferred until stable v2.7.0 is verified and ready; this is not a production approval.

### Added

- Source details on saved workbook cards and the workbook viewer: importing account, source status, last known location, checks, explicit cloud re-linking and local-original re-linking with content verification.
- Connected-account labels on both cloud-import buttons, using existing OAuth scopes.

- Privacy Policy and Terms and Conditions in More, side by side immediately above Author, readable offline from canonical Markdown copied into the APK.
- Original-derived 120px OAuth PNG, 512px Play icon, 1024x500 feature graphic, editable SVG and 1024px PNG master with provenance and SHA-256 inventory.
- Policy packaging regression coverage for byte identity, Markdown readability and absolute public links.

### Changed

- Compile/target SDK increased to 36; development version is 225 / 2.7.0-dev.2.
- Release signing now uses optional owner-supplied BCM_UPLOAD_* environment variables. Without them release artifacts are unsigned; release builds no longer silently use the debug key. The locally installable debug APK remains debug-signed.
- Public homepage references now use https://blog.p2nd.online/apps/bookcover-matcher/. Privacy revision is 2026-11-14.1 (source-account metadata and retained selected-document access); terms remain 2026-11-13.2; website synchronization/publication is separate.

- CameraX updated to 1.4.2 and ONNX Runtime to 1.23.2. Python dependencies are version-pinned; reviewed source-built FreeType/JPEG wheels include provenance, licences and hash verification.

### Fixed

- Both cloud import results now open the exact workbook when Use it now is tapped, including duplicate results. Home scanning-source selection remains separate.
- Rescan targets the open workbook rather than a previously selected scanning source. Duplicate imports preserve source ownership; mismatched accounts and late session results cannot update a workbook.

- Corrected policy wording about IQDB: the app uses a manual browser/result-import workflow and does not automatically upload images to IQDB; SauceNAO image/API-key uploads remain disclosed.

- Fixed native 16 KB alignment in OCR, CameraX and ONNX JNI, plus incompatible FreeType/JPEG binaries hidden inside Python asset archives. Added recursive ELF/RELRO verification.
- Removed OCR exception/traceback and discovery URL/error payloads from diagnostics; removed proprietary authentication SDKs in the later local AppAuth prototype; OAuth tokens remain memory-only.

### Removed

- Automatic debug-key fallback from release-build signing. No discovery provider was removed.
- Direct Google and Microsoft proprietary authentication SDKs and the former display-mask workaround are replaced by the local AppAuth browser prototype; publication remains blocked pending its outstanding validation.

### Tests

- CachyOS closeout (17 November): JVM results **1,429 passed, 0 failures/errors, 16 skipped**; debug assembly and lint passed (0 errors, 58 warnings). See the final dev.2 closeout report for full command results and limits.

- Workbook-source UI checkpoint (14 November): **1,429 JVM tests passed, 0 failed, 16 skipped** (1,445 total); debug and Android-test APK assembly passed. Lint: **0 errors, 56 warnings**. Earlier checkpoint totals below are historical, not fresh runs.
- API 37 emulator: two targeted instrumentation tests passed, including actual Use it now taps for both providers/new and duplicate results, exact viewer identity, Back, missing-copy failure, and local access-not-retained versus revoked states. No live provider authentication is implied by these fixture tests.

- Full JVM suite: **1,396 passed, 0 failed, 16 skipped** (1,412 total), including policy packaging and payload-free OCR diagnostics.
- Readiness device suite: **14 passed, 0 failed, 0 skipped on each of two emulators**: API 35 with 16 KB pages/compatibility mode disabled, and API 37 with 4 KB pages. Covers native OCR, ONNX CLIP inference, Pillow JPEG/font operations, camera JNI loading and forced missing-display-mask fallback. These are 14 distinct tests, not 28 distinct cases.
- Earlier policy-reader checkpoint: 40 existing OCR/Author device tests passed; those full-suite results precede this readiness follow-up. Final readiness builds include debug, Android-test APK, unsigned release bundle and lint.
- Lint: **0 errors, 49 warnings**. git diff --check passed. Repository licence checker still reports two pre-existing BOM failures in unchanged Author source/test files; this is separate from dependency metadata review.

### Verified

- Workbook-source UI checkpoint: Google and Microsoft browser sign-in/profile lookup succeeded live on the API 37 emulator, and both import buttons displayed the account labels. Use it now fixture tests and viewer screenshots passed at normal/150% fonts; Rescan/Back clipping was corrected. The activity remains portrait-locked.
- The new debug APK packages privacy revision 2026-11-14.1 and unchanged terms byte-for-byte from the canonical files. It remains an unpublished local dev.2 build.

- APK metadata: com.bookcovermatcher, 225 / 2.7.0-dev.2, target/compile API 36; debug signature verified. Both APK and AAB contain policy text byte-identical to the canonical roots.
- Emulator: policy control placement/order, both documents offline, full-screen/scroll/Back behaviour and 100%/150% font sizes checked; final header inspected at 150%.
- OAuth PNG decodes to 120 x 120 and is 1,041 bytes. Original launcher resources preserved; feature graphic and logo inspected visually.
- Release bundle is unsigned, confirmed by jarsigner; it is not ready for Play upload.

- Recursive scan: **164 ELF64 libraries, zero LOAD/congruence/RELRO failures** in the rebuilt APK, including Python archives; APK ZIP alignment passes. Runtime testing uses x86_64; arm64 physical-device verification remains outstanding.

### Known limitations

- Existing cloud copies need explicit source-account confirmation or re-selection; old local copies have no recoverable original path until re-linked. Android providers may not expose full paths or retained read access. Generic 404 and network errors do not prove deletion.
- Source metadata privacy revision 2026-11-14.1 still needs website synchronization; physical Vivo behavior and live cross-account switching remain separate checks. No publication or Google submission was performed for this update.
- Existing providers are preserved by owner decision. The completed code/policy audit identifies adult-gallery shortcuts, mixed-catalogue and reverse-result filtering blockers before stable submission; an adult audience setting does not resolve prohibited-content issues.
- Direct GIS/MSAL dependencies have been removed in favour of AppAuth. Final dependency/licence review, token expiry/revocation and process-lifecycle verification, production signing and restricted Drive scope verification remain outstanding. No Google approval or OAuth production transition is claimed.
- Public policy/support routes await separate website synchronization and deployment. A local policy reader does not prove those links are live.
- No new physical-device cloud test was performed at closeout. Earlier live emulator authorization results are recorded above; prior dev.1 Vivo resolution remains owner-reported. Existing My Drive-only scope and unsupported Drive operations are unchanged.
- This is a source/documentation checkpoint on online_improvement, not a GitHub binary release or Google Play submission. Stable v2.7.0 follows prompt review, verification and necessary fixes, before Play submission preparation.

**Version code/name: 225 / 2.7.0-dev.2.**
