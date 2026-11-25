# Version and revision identity

## Android release codes

Set the owner-approved Android version name in [gradle/android-version.txt](../../gradle/android-version.txt). Gradle and the documentation checker use the same [version calculator](../../tools/android_version.py). Run `python3 tools/android_version.py` to print the configured manifest name and code. Run `python3 -m unittest discover -s tools/tests` to verify the boundaries.

Use `M.m.p` for a stable build and `M.m.p-dev.N` for development. Use canonical decimal segments with no leading zeros. Major ranges from 0 to 2099, minor and patch from 0 to 98, and the dev suffix from 1 to 99. Zero version 0.0.0 is unsupported. Codes must be positive and at most 2,100,000,000.

A stable code is `major × 1,000,000 + minor × 10,000 + patch × 100`. A dev build uses its target stable code minus 100 plus N. For example, 3.0.0-dev.8 is 2999908, 3.0.0 is 3000000, and 3.0.1-dev.1 is 3000001. One integer calculation handles borrowing across patch, minor and major versions. Decimal width varies with the version.

Both editions share the Android version name and code. Resolve a release tag or the matching BCM Plus source tag using `python3 tools/android_version.py --source-tag v3.0.0-dev.8-plus`. The manifest name for this tag is `3.0.0-dev.8`. Keep original signed downloads at their existing names. Append `-code<versionCode>` before `.apk` on the separately published historical rebuild so each URL identifies one artifact. Before uploading a new Play bundle, verify the package, signer and code, and check all previously uploaded versions. Plan backports against the installed version codes because Android treats lower codes as downgrades.

New builds support stable and development versions. Historical RC tags and records remain readable through the normal Git and documentation indexes. Existing code values in archived records describe those specific artifacts. Prior releases used a sequential allocation, including internal RC reservations and reuse at stable release. A crash-fix suffix identifies work against a parent version and does not allocate an Android build identity.

Use the [historical rebuild instructions](../reference/ARCHIVE_REBUILDS.md) to reconstruct an individual archived APK and verify its inputs.

## Registry and historical evidence

[docs/_meta/versions.json](../_meta/versions.json) records document grouping, evidence and current source identity. Codes attributed to old reports are historical claims, not fresh APK checks. Null means unresolved/not applicable, never permission to calculate a code. Multiple builds can share a code; record checkpoint, commit and artifact hash when available.

Historical conflicts are preserved explicitly in the catalog. The v2.5.0-dev.2 prompt has a stable-v2.4.0 title; the v2.6.0-stable-2 prompt records that its 2.6.1 proposal was re-scoped to 2.6.0. Do not manufacture releases to reconcile those names. QA observations labelled 2.4.0 used different codes/checkpoints; inspect the exact report.

When an owner-authorized new version is established, register its identity, evidence, root, document key and categories before creating records. Mark it authorable only when its intended scope is established. Preserve existing identities referenced by finalized records; add a new entry rather than retagging history. Update the registry's current field when source actually changes and keep it consistent with the build configuration.

## Document revisions and publication identity

Document `r01`, `r02` and so on track a particular record, independent of app code, website v1.0.1 or policy revision .6. File routine reports against the supported parent baseline and say when changes are unreleased. A new document does not require a new app version.

GitHub release name equals its full tag, for example `v2.7.0-dev.1`. The complete release body equals that release's CHANGELOG.md text exactly. The standard APK is `BCM-v<complete-versionName>.apk` and the Plus APK is `BCM-Plus-v<complete-versionName>.apk` without an extra debug/release suffix. Verify remote name/body and artifact name/size/digest if publication is authorized. See [changelog guidelines](CHANGELOG_GUIDELINES.md); none of these naming rules authorize publication by themselves.
