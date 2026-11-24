## v3.0.0-dev.4 (2026-11-24)

# Standard and explicit BCM editions

Choose **BCM** for the standard app or **BCM-plus** for the edition with bundled catalogue plugins.

### Added

- A separately signed explicit-edition download alongside the standard APK.

### Changed

- Both editions use Android version code 230. The standard edition carries forward the dev.3 feature set.

### Fixed

- In the explicit edition, turning off DLsite in Online skips its searches and leaves the row available to select again. The Plugins switch controls whether the row appears.

### Removed

- Removed the explicit edition’s shortcut from the Online selection control to the plugin disable action.

### Tests

- Built the Play and explicit release APKs and ran both editions’ debug JVM suites. Five DLsite instrumentation tests passed on an Android 16 emulator using a local provider fixture.

### Verified

- Verified the application ID and version metadata for both APKs. The standard APK contains zero bundled plugin descriptors and the explicit APK contains six.

### Known limitations

- If a previous build disabled DLsite Fetch, re-enable it once in Plugins to restore the Online row. Android uses the same application ID for both editions and installs one at a time.

**Version code/name: 230 / 3.0.0-dev.4.**
