# Play and GitHub editions

## Development prerelease v3.0.0-dev.4

The APK with the original filename on GitHub is the standard Play edition from `google_play`, version code 230. [Release and changelog](https://github.com/PoloTheFirst/BookCoverMatcher/releases/tag/v3.0.0-dev.4) · [Download the signed APK](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.4/bookcovermatcher-v3.0.0-dev.4.apk)

On the standard Play build, **Plugins** opens an empty host showing **Import plugin** and **No imported plugins**. The built-in OCR choices are on-device PaddleOCR and opt-in Chrome Lens. Workbooks, wishlist entries and bookmarks use the same storage as v2.7.0. Both flavors display **BCM**, use version code 230 and share the existing application ID and signing configuration. Install the same signing lineage to keep Android app data.

The additional **BCM Plus** download, `BCM Plus-v3.0.0-dev.4.apk`, contains the explicit edition with bundled providers. Both editions use the same package and signing key.

## v2.7.0 distribution checkpoints


Validated locally on CachyOS on 18 November 2026 against Android 2.7.0 / code 226, branch `google_play`. These are build variants in one checkout; neither has been published by this task.

## What each edition contains

- **GitHub:** all existing catalogue providers, browser shortcuts, OCR choices, SauceNAO and IQDB.
- **Play:** no public catalogue searches or provider shortcuts. Cover text uses on-device PaddleOCR by default. Select Chrome Lens to send covers to Google for OCR. Play excludes SauceNAO and IQDB. Offline matching, local/cloud workbook import, source information, Rescan and personal library features remain.
- The edition is visible in More's version information. Play is an intended distribution destination, not a claim of Google approval.

Catalogue registries, capability metadata, network adapters and edition help are selected through `app/src/github` and `app/src/play`. Play has empty catalogue registries and no catalogue adapter implementations. Shared controller guards also reject catalogue requests when old GitHub settings are restored. Chrome Lens runs through the built-in chrome-lens-py bridge. Play stores its engine choice separately so a GitHub preference cannot enable network OCR during an update.

Play also uses neutral bookmark-form examples; GitHub retains its existing examples. These are placeholders only and do not rewrite saved values.

Existing catalogue bookmarks are retained in storage but displayed as unavailable in Play, with their thumbnail, metadata and link hidden. Workbook matches, custom bookmarks and results whose provider provenance consists only of SauceNAO/IQDB remain usable. Switching back to GitHub restores access to retained catalogue records. This boundary does not filter arbitrary personal workbook content, custom URLs or reverse-image results.

## Plugins navigation

On the `google_play` branch, **Plugins → Import plugin** opens the Android file picker for a local `.bcmplugin` descriptor. The v2.7.0 Play debug and release builds register zero plugins. Neither DLsite nor Plugin Marketplace can be imported or restored from an earlier edition. Reverse-image discovery is excluded from the Play APK.

## Build and verify locally

Run from `/home/polo01/Projects/BookCoverMatcher` with the existing JDK, Android SDK and native/Python build prerequisites:

```bash
./gradlew :app:assemblePlayDebug :app:assembleGithubDebug
./gradlew :app:testPlayDebugUnitTest :app:testGithubDebugUnitTest :app:lintPlayDebug :app:lintGithubDebug
./gradlew :app:assemblePlayDebugAndroidTest :app:assembleGithubDebugAndroidTest
./gradlew :app:assemblePlayRelease :app:assembleGithubRelease
```

Debug APKs are `app/build/outputs/apk/play/debug/app-play-debug.apk` and `app/build/outputs/apk/github/debug/app-github-debug.apk`. Release output directories are `app/build/outputs/apk/play/release/` and `app/build/outputs/apk/github/release/`. Without release-signing configuration the APK filenames end in `-unsigned.apk`; these cannot be installed. Generic tasks such as `assembleDebug` now build both editions, so prefer explicit variant tasks. In Android Studio select `playRelease` or `githubRelease` before generating a signed artifact.

When separately authorized and publication gates are resolved, bundle tasks are `:app:bundlePlayRelease` and `:app:bundleGithubRelease`. This checkpoint built APKs, not AABs, and uploaded nothing. Signing uses the existing `BCM_UPLOAD_STORE_FILE`, `BCM_UPLOAD_STORE_PASSWORD`, `BCM_UPLOAD_KEY_ALIAS` and `BCM_UPLOAD_KEY_PASSWORD` environment configuration; do not put passwords in commands, source control or reports. Use the owner's existing release key, not a newly generated key.

Device tests use `:app:connectedPlayDebugAndroidTest` or `:app:connectedGithubDebugAndroidTest` on an isolated test emulator. They were compiled but not run in this checkpoint. Both editions have the same package ID, so do not run Gradle installation tests over a personal installation. Never uninstall or clear app data to resolve a signing mismatch.

## Running on the existing CachyOS emulator

Verified on 18 November 2026 after the owner reported an emulator run problem. ADB initially listed no device. The Studio log recorded shutdown after an unsupported Vulkan snapshot warning; this alone does not prove why the owner's attempt failed. A cold boot of `BCM_API36_CachyOS` with `-no-snapshot-load -no-snapshot-save` succeeded without wiping userdata.

The emulator's installed app uses the local **debug** certificate. The owner-generated APKs in `app/play/release/` and `app/github/release/` use a different **release** certificate, so they cannot replace that debug installation. Keep these artifacts distinct from the unsigned command-line release outputs described above. Do not uninstall to work around this mismatch.

In Android Studio, sync the project, open **Build Variants**, select **playDebug** for `app` (or **githubDebug** for the full edition), select **BCM_API36_CachyOS** and run the `app` configuration. If the emulator cannot resume, use Device Manager's **Cold Boot**; do not use **Wipe Data**. The historical Windows emulator target in project metadata is not the current Linux AVD.

For the verified debug-signed emulator only, run from the repository root:

```bash
./gradlew :app:assemblePlayDebug
/home/polo01/Android/Sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/play/debug/app-play-debug.apk
/home/polo01/Android/Sdk/platform-tools/adb -s emulator-5554 shell am start -W -n com.bookcovermatcher/.MainActivity
```

Check `adb devices -l` first: the emulator serial can change. These are not instructions for updating the release-signed Vivo installation. To test release signing, use a separate clean emulator rather than deleting this emulator's existing data.

Observed recovery: matching debug certificate confirmed before installation; `install -r` succeeded, launch returned `Status: ok`, Play-specific More help rendered, and the crash buffer remained empty. Package first-install time remained 16 November 2026 after the update. No uninstall, data clear, workbook import/edit, cloud sign-in or grant change was performed. This is a launch/navigation smoke test, not complete data-retention, provider, OCR or release-signing verification.

## Update and data compatibility

Both editions retain `com.bookcovermatcher`, versionName `2.7.0`, versionCode `226`, the existing signing setup, storage schemas and preference keys. They replace one another rather than installing side by side. No workbook migration or deletion was added. Play ignores restricted settings without overwriting the stored GitHub choices.

For an eventual update, compare the installed app's signing certificate with the new APK before installing it. A different certificate requires stopping and correcting signing, not uninstalling. Play App Signing must use the compatible **app signing key**; matching only the upload key does not guarantee update compatibility. Future Play releases also need an eligible higher version code, allocated under the owner's version rules. This task does not allocate another code or establish that 226 is still unused in Console.

The previous owner-signed `app/build/outputs/apk/release/app-release.apk` is unchanged and is not the new Play build. No emulator/phone installation, cloud sign-in or personal workbook operation was performed for this split. Actual signed cross-edition update/data-retention testing remains outstanding.

## Before publication

```bash
./gradlew :app:verifyPlayPublicationEligibility
```

This explicit check intentionally fails while review is unresolved. It depends on the existing authentication publication check, which currently fails first. It is not automatically attached to local assemble/bundle tasks and cannot prevent a manual Console upload; do not treat a successful build as permission to publish.

Before submitting Play, review Chrome Lens image transfers, saved links, ratings, Data safety, reviewer access, store assets and edition-specific privacy wording. No age declaration alone resolves prohibited-content concerns. Existing authentication, licensing, signing and public-policy-access checks also remain. Do not publish either variant as part of this local implementation.

See the [implementation report](../history/android/v2.7.0/development/reports/2026-11-18__android-2.7.0-code226__development__distribution-variants__report__r01.md), [current status](../STATUS.md) and [AppAuth publication gates](../integrations/APP_AUTH_PROTOTYPE.md).
