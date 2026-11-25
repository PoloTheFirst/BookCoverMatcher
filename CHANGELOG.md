## v2.0.0-dev.1 (2026-10-04)

# First native Kotlin migration checkpoint

The native Android migration starts with a Jetpack Compose home screen, navigation and a ViewModel placeholder. The earlier WebView assets remain in the source as a migration reference.

### Added

- Set up MainActivity, the Compose navigation host, a Home route and Material 3 theme files.

### Changed

- Enable Compose in the Android Gradle build and add navigation and lifecycle dependencies.

### Fixed

- None recorded.

### Removed

- Remove the placeholder online-search implementation from the web reference layer.

### Tests

- The original development report gives no test count or completed camera, matching or wishlist workflow.

### Verified

- The public GitHub tag is v2.0.0-dev.1. The tagged native project and downloadable APK identify as com.polo.bookcovermatcher, version 1.1.0-dev.1, code 2.

### Known limitations

- The home screen is a placeholder. Camera capture, matching and wishlist workflows had yet to be ported. The old release text proposed code 20001. It was never the value in this APK.

**Version code/name: 2 / 1.1.0-dev.1.**
