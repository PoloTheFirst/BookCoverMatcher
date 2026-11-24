# Version and revision identity

## Android releases and internal rounds

The owner chooses a new target release/version, or explicitly authorizes a derivation such as incrementing the preceding dev suffix. Routine documentation work uses the inspected current baseline; it does not require a new release allocation. Never guess a versionCode from a filename. Inspect app/build.gradle.kts, current branch/history and, when built, packaged artifact metadata.

Preserved owner rules:

- Standard releases normally increment the preceding code once if no RC has reserved the target's code.
- The first internal RC for a stable target reserves the next code; later RCs for that same target and its final stable release reuse it. Finalizing that stable version must not increment the code again.
- RCs are internal testing, never public release tags, GitHub Releases or public prereleases. Do not push RC-only test work into public release branches; any approved private testing branch is a separate scope.
- Every published development version, including each dev.N, consumes the next code. Use dotted `-dev.N` and `-rc.N` forms unless the owner explicitly names another task filename.
- `crash-fix-N` describes focused work against a parent, not a new Android release. Retain parent versionName/code unless the owner explicitly changes them.

Examples are independent sequences, not allocations for the current task: from 223 / 2.6.0, two dev releases consume 224 and 225, so a subsequent stable version with no reserved RC would use 226. Alternatively, a first RC could reserve 224, and later RCs and its final stable reuse 224. An RC after dev.1/code224 would reserve 225. Inspect actual intervening allocations before applying any example.

## Registry and historical evidence

[docs/_meta/versions.json](../_meta/versions.json) records document grouping, evidence and current source identity. Codes attributed to old reports are historical claims, not fresh APK checks. Null means unresolved/not applicable, never permission to calculate a code. Multiple builds can share a code; record checkpoint, commit and artifact hash when available.

Historical conflicts are preserved explicitly in the catalog. The v2.5.0-dev.2 prompt has a stable-v2.4.0 title; the v2.6.0-stable-2 prompt records that its 2.6.1 proposal was re-scoped to 2.6.0. Do not manufacture releases to reconcile those names. QA observations labelled 2.4.0 used different codes/checkpoints; inspect the exact report.

When an owner-authorized new version is established, register its identity, evidence, root, document key and categories before creating records. Mark it authorable only when its intended scope is established. Preserve existing identities referenced by finalized records; add a new entry rather than retagging history. Update the registry's current field when source actually changes and keep it consistent with the build configuration.

## Document revisions and publication identity

Document `r01`, `r02` and so on track a particular record, independent of app code, website v1.0.1 or policy revision .6. File routine reports against the supported parent baseline and say when changes are unreleased. A new document does not require a new app version.

GitHub release name equals its full tag, for example `v2.7.0-dev.1`. The complete release body equals that release's CHANGELOG.md text exactly. The standard APK is `BCM-v<complete-versionName>.apk` and the Plus APK is `BCM-Plus-v<complete-versionName>.apk` without an extra debug/release suffix. Verify remote name/body and artifact name/size/digest if publication is authorized. See [changelog guidelines](CHANGELOG_GUIDELINES.md); none of these naming rules authorize publication by themselves.
