# BCM

An Android app for matching book covers against images in an Excel workbook, keeping comparison sessions and bookmarks, and using optional online discovery and cloud-workbook imports.

## Local development build

**3.0.0-dev.4 (230)** lets you deselect DLsite in **Online** and select it again from the same row. **Plugins → DLsite Fetch** controls whether that row is available. The light mark pairs rose-outlined blush and ivory pages with a cream tile. The dark mark pairs blush and ivory pages with a rose tile. The **BCM Plus** GitHub edition includes **Plugin Marketplace**, **Ordinary Library Fetch**, **DLsite Fetch**, **Doujin Shop Shortcuts**, **Explicit Database Shortcuts**, and **Reverse Image Discovery**. On a fresh installation the six switches are on, including offline. Open **Plugins** to change them. Switching the marketplace off pauses its groups and preserves their choices. Search, upload, cloud sign-in and OCR use their existing controls.

Open **More → Display** to follow the phone’s light/dark setting or choose Light or Dark. Both modes use the same Sophie color scheme as the scanner.

The current public standard APK is **v2.7.0 (226)** from `google_play`. [Published release](https://github.com/PoloTheFirst/BookCoverMatcher/releases/tag/v2.7.0) · [Distribution guide](docs/guides/DISTRIBUTION_VARIANTS.md)

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
