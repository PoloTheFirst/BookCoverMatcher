# BookCoverMatcher

An Android app for matching book covers against images in an Excel workbook, keeping comparison sessions and bookmarks, and using optional online discovery and cloud-workbook imports.

## Current local build

The source is configured as **2.7.0 / versionCode 226**. The owner-signed APK was installed as a compatible update on Vivo; App Website appears below the author-profile button. JVM/build/lint, emulator regression and native-packaging checks passed. See the [execution report](docs/history/android/v2.7.0/release/reports/2026-11-17__android-2.7.0-code226__release__stable-finalization__report__r01.md) for evidence and limits.

Website policies are published and the plain URLs were rechecked on 17 November 2026 (privacy 2026-11-17.2; terms 2026-11-17.1). The regenerated owner-signed APK now contains privacy .2 and passed API36 emulator smoke checks; this latest artifact has not been installed on Vivo. Stable publication readiness is not yet complete: excluded live cloud/lifecycle checks and distribution review remain. The explicit `verifyAuthPublicationEligibility` guard remains. On 18 November 2026, the owner reported Google OAuth verification submitted and under review, and authorized private Google Play submission preparation while waiting. This is not OAuth approval or a Play submission. Stable publication gates remain; no stable tag or hosted release is claimed. Read [current status](docs/STATUS.md) and the [AppAuth guide](docs/integrations/APP_AUTH_PROTOTYPE.md) before publishing.

Local `github` and `play` editions now share the same app ID and storage. GitHub retains every provider; Play excludes catalogue discovery and shortcuts while retaining SauceNAO/IQDB. Reverse-image content review and signed update testing remain required. See [distribution variants and build commands](docs/guides/DISTRIBUTION_VARIANTS.md). Nothing has been published from this split.

The Plugins tab supports live switches for bundled features. Both debug editions include a local demo card. See the [bundled plugin guide](docs/guides/BUNDLED_PLUGINS.md).

## Find the right documentation

- Start using the app: [installation](docs/guides/INSTALL.md) and [user guide](docs/guides/USER_GUIDE.md).
- Understand the implementation: [architecture](docs/reference/ARCHITECTURE.md), [online search](docs/reference/ONLINE_SEARCH.md), [native OCR](docs/reference/NATIVE_OCR_ANDROID.md) and [recognition diagnostics](docs/diagnostics/ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md).
- Configure cloud access: [current AppAuth setup](docs/integrations/APP_AUTH_PROTOTYPE.md), [Google Drive history/setup](docs/integrations/GOOGLE_DRIVE_SETUP.md) and [OneDrive history/setup](docs/integrations/ONEDRIVE_SETUP.md). Their older GIS/MSAL evidence is historical where the AppAuth guide says it is superseded.
- Maintain or hand off work: [AGENTS.md](AGENTS.md), [workflow](docs/maintainers/WORKFLOW.md), [authoring and organization](docs/maintainers/AUTHORING.md), [version rules](docs/maintainers/VERSIONING.md) and [templates](docs/maintainers/TEMPLATES.md).
- Find evidence: [documentation directory](docs/README.md), [version map](docs/VERSIONS.md) and [file index](docs/FILE_INDEX.md). Historical filenames and conflicts remain searchable through the catalog.

The former long README is preserved exactly as a [historical reference](docs/history/cross-version/legacy/references/README-before-doc-reorganization.md). It combines several release-era descriptions; use the maintained guides and current source to establish present behavior.

## Licensing and policies

Project-owned code is **GPL-3.0-or-later**. Third-party components retain their own terms; see [LICENSE](LICENSE), [NOTICE](NOTICE) and [third-party notices](THIRD_PARTY_NOTICES.md). The [Privacy Policy](PRIVACY_POLICY.md) and [Terms and Conditions](TERMS_AND_CONDITIONS.md) remain canonical root files because Gradle copies them into the APK. Generated license assets must not be edited as separate policy sources.

The companion app page is [BookCoverMatcher on Polo's Atelier](https://blog.p2nd.online/apps/bookcover-matcher/). Website versions and policy revisions are independent of Android version codes. The [changelog](CHANGELOG.md) retains the owner's single-current-release format; this documentation migration does not publish a new release.

## Documentation maintenance

Run from the repository root on CachyOS (or equivalent Python commands in PowerShell) after relevant edits:

```bash
python tools/docs/manage.py refresh
python tools/docs/manage.py check
```

The commands update/check documentation navigation, classification and preservation. They do not build the app, stage files, commit, push, create releases or infer new factual status. Ordinary user requests are enough for an agent to perform routine filing; see AGENTS.md for the intake workflow and its limits.
