# Developing a bundled BCM plugin

In 2.7.0, open **Plugins → Demo card** in either debug edition. Turn the switch on to show the card and off to remove it. The choice survives tab changes and app restarts. Release editions currently have an empty registry.

## Write and include a plugin

1. Use Kotlin `.kt` files. The [demo implementation](../../plugins/demo/src/main/kotlin/com/bookcovermatcher/plugins/demo/DemoPlugin.kt) is a complete example. Its plain JVM module produces a JAR that Gradle includes in the debug app. A future Android library requiring resources can use an AAR as a build dependency.
2. Depend on `:plugin-api`. Declare a stable lowercase ID, display name, description, plugin version, host API range, and `INFORMATION_CARD` capability. API version 1 supports a single read-only text card per activation. Keep the ID stable across upgrades so its setting carries forward.
3. Implement `BundledPlugin`. Return an `InfoCard` from the suspending `activate()` method. Use structured concurrency and cancellation-aware operations for initialization. Put listener removal and cleanup of partially acquired resources in `deactivate()`. Trusted bundled code runs in the host process.
4. Supply a descriptor and a factory creating a fresh instance per activation. Include the module as a Gradle project dependency for the desired variants and add its registration to the matching build source set. The current demo uses `debugImplementation` and `app/src/debug`. The registry in `app/src/release` is empty.
5. Run `./gradlew :app:testPlayDebugUnitTest :app:testGithubDebugUnitTest :app:assemblePlayDebug :app:assembleGithubDebug`. For a new module verify the release dependency graph and APK contents as well. A new compiled implementation takes effect with a new app build. Switching a bundled implementation takes effect immediately.

## Activation and persistence

The host ViewModel owns a manager whose StateFlow drives the switch, status and card. Each ID has its own serialized transitions. Successful activation saves the enabled choice before exposing the card. A disabled plugin's work is cancelled and its card disappears. If a setting cannot be saved, the row reports the failure. A slow cleanup can delay retry for that ID. A failed cleanup requires reopening the app. Duplicate registrations fail registry validation. An incompatible descriptor is unavailable even if enabled in a previous build.

The host stores each known ID's choice in a private atomic file under `filesDir/plugin-settings/`. It reads registered IDs individually, preserving preferences for absent modules. The contract exposes the information card and lifecycle only. The existing providers, cloud accounts and workbook repositories have their own settings and lifetimes.

External package import and downloadable plugins are deferred. No user-facing plugin file format has been adopted. See [distribution variants](DISTRIBUTION_VARIANTS.md) for build and signing instructions.
