# BookCoverMatcher

An Android app for matching book covers against images in an Excel workbook, keeping comparison sessions and bookmarks, and using optional online discovery and cloud-workbook imports.

## Standard Android build

Version **2.7.0 (226)** uses the **Play edition** from the `google_play` branch as the standard GitHub APK. [Download v2.7.0](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.7.0/bookcovermatcher-v2.7.0.apk). It defaults to on-device PaddleOCR and offers Chrome Lens as an explicit OCR choice. It excludes public catalogue discovery, provider shortcuts, SauceNAO and IQDB. The other edition and plugin branches have separate feature sets. See the [v2.7.0 changelog](CHANGELOG.md) and [distribution guide](docs/guides/DISTRIBUTION_VARIANTS.md).

The GitHub release decision applies to distribution on GitHub. Google OAuth review, Google Play submission and the open items in the [AppAuth guide](docs/integrations/APP_AUTH_PROTOTYPE.md) are tracked separately.

On the `google_play` branch, **Plugins → Import plugin** opens the Android file picker for a local `.bcmplugin` descriptor. The v2.7.0 Play debug and release builds register zero plugins. Neither DLsite nor Plugin Marketplace can be imported or restored from an earlier edition. Reverse-image discovery is excluded from the Play APK.

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
