# Rebuilding a historical BCM APK

The [release index](RELEASE_INDEX.md) identifies original and renumbered builds. Both files belong to the same GitHub release. The original file keeps its published name, and the renumbered file adds `-code<versionCode>.apk`. The [artifact manifest](../../tools/archive-rebuild/artifacts.json) records each original APK SHA-256, source tag and commit, edition, manifest version name, old and new version codes, and checksum of the rebuilt Gradle configuration. The full and lite v2.0.0 APKs use different model contents. The BCM Plus editions use their own source tags.

Dev.8 keeps the data practices and permissions described in policy revision 2026-11-26.2. Its bundled policy text records the original dev.8 code 234. Code 2999908 identifies the rebuild of that same application version.

## Prepare the source

1. Clone the public repository and fetch its tags with `git fetch origin --tags`. Use a branch containing this guide.
2. From the relevant GitHub release, download the **original** APK for the edition you want to reproduce. The helper identifies the file by SHA-256, so its local name can differ from the release filename.
3. Use Python 3.12 or newer and Git. From the repository root, supply the APK path and a new destination directory. For example, these commands prepare the original BCM dev.8 edition:

   ```sh
   python3 tools/archive-rebuild/prepare.py "$HOME/Downloads/BCM-v3.0.0-dev.8.apk" ../bcm-dev8-rebuild
   cd ../bcm-dev8-rebuild
   ```

The artifact manifest pins the source commit used to build each APK. A release tag can point directly to that commit or to its immediate child that updates only the root `CHANGELOG.md` with the two APK codes. In both cases the helper exports the pinned commit, applies the version-code change and checks the resulting build file against its recorded SHA-256. For v1.0.0, v1.1.0 and v2.0.0-dev.1 it also applies the [two XML comment corrections](../../tools/archive-rebuild/early-xml-comments.patch) needed by the Android resource compiler. The backup rules retain their original values. The v2.5.0 stable build also removes a [misplaced UTF-8 byte-order mark](../../tools/archive-rebuild/v2.5.0-kotlin-bom.patch) before a Kotlin package declaration. The original v2.5.0 APK also included a license reader absent from its tagged Android UI source. The [license reader patch](../../tools/archive-rebuild/v2.5.0-license-ui.patch) restores the reader, its entry buttons and its Gradle license-copy task from [the public source commit](https://github.com/PoloTheFirst/BookCoverMatcher/commit/a490ca0228331c244c6a04d1af782343110fb088). The helper verifies the two tagged legal texts against the original packaged copies and preserves their exact line endings, including CRLF in the third-party notices.

Several early release tags share an embedded Android name. The artifact manifest records the actual name for each build.

Model and custom native OCR inputs come from the verified original APK. The archived source includes the corresponding application code, native sources and build scripts provided at that release. For editions with cloud sign-in, the helper recovers the public client registrations already present in that APK. It writes only those values and the presence-only tenant placeholder used by the tagged Gradle check. Cloud passwords, client secrets, testing accounts and signing keys are unnecessary for source preparation.

## Build and verify

Install JDK 21 and the Android SDK components requested by the tagged Gradle configuration. Set `ANDROID_HOME` to your SDK. The tagged Gradle wrapper selects the required Gradle version. Chaquopy builds also require Python 3.12. If it is outside the regular search path, pass `-PchaquopyBuildPython=/path/to/python3.12` to Gradle. The helper prints the exact Gradle task for that source and edition and includes the dependency constraint script where needed.

For the dev.8 BCM example, run:

```sh
bash gradlew -I .archive-rebuild/python.init.gradle :app:assemblePlayRelease
```

Older builds use `:app:assembleRelease`. BCM Plus uses `:app:assembleGithubRelease`. The tagged build files select Android, Kotlin, Maven and Chaquopy dependencies. Checked-in Python constraints pin the distribution versions found in the intended rebuild, including transitive dependencies. A fresh build environment requires access to the dependency repositories and wheels referenced in the tag.

Build outputs appear under `app/build/outputs/apk/`. Inspect the selected APK using Android SDK Build Tools:

```sh
aapt2 dump badging /path/to/rebuilt.apk
apksigner verify --print-certs /path/to/published.apk
```

Compare the package, version name, version code and edition with the artifact manifest. Its `rebuildSha256` identifies the owner-signed GitHub download. An independent build may differ in dependency packaging, timestamps or signatures. The original and rebuild signing certificates differ for some older versions. Preserve local workbook data before installing an archived application on a device that already carries a newer build.

## Historical debug APKs

The original downloads for `v2.0.0 (Lite)`, `v2.2.1`, `v2.7.0-dev.1` were debuggable and exposed the Compose tooling preview activity. The release rebuilds disable debugging and omit that preview activity. The artifact manifest marks these originals with `originalDebugPreview`.
