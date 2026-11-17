# Developing and importing a BCM plugin

Use Kotlin `.kt` files to implement a plugin. Give users a small `.bcmplugin` descriptor to import in **Plugins → Import plugin**. Adding new Kotlin code requires a new app build. Importing or switching an implementation already bundled with BCM takes effect in the running app.

## Import DLsite

1. Copy [dlsite.bcmplugin](../../plugins/manifests/dlsite.bcmplugin) to a location accessible in Android’s file picker, such as Downloads.
2. In the GitHub edition, select that file using **Plugins → Import plugin**. A fresh installation shows DLsite switched off. On first import after an upgrade, BCM honors an explicitly stored DLsite selection. Subsequent imports preserve the plugin switch.
3. Enable **DLsite** and open Search. With online search enabled, the next manual or OCR-triggered catalogue search can send its query to DLsite. Listings may include mature content. Toggling alone sends no query. Disabling removes active DLsite discovery immediately. GitHub bookmarks and workbooks retain their saved contents.
4. To try the information card in either debug edition, import [demo.bcmplugin](../../plugins/manifests/demo.bcmplugin). The Play edition rejects the DLsite descriptor.

## Define an implementation

Depend on `:plugin-api` and implement `BundledPlugin` in Kotlin. Use a stable lowercase ID and declare the supported host API range and capability. Host API 2 accepts an `InfoCard` or a `CatalogueProvider`. Return a fresh contribution from `activate()` and release resources in `deactivate()`. The host serializes transitions for each ID. Observe the [demo implementation](../../plugins/demo/src/main/kotlin/com/bookcovermatcher/plugins/demo/DemoPlugin.kt) or the [GitHub DLsite registration](../../app/src/github/java/com/bookcovermatcher/plugins/EditionPlugins.kt). Register code in the edition or build type that provides it.

## Create the descriptor

Save the following two lines as UTF-8 plain text in a file named `dlsite.bcmplugin`. For another plugin, replace the second line with its registered ID.

```text
BCM-PLUGIN/1
bcm.dlsite
```

BCM reads up to 256 bytes and checks the format, ID, host API and edition registry. The descriptor contains only a format header and plugin ID. The Kotlin implementation ships inside the app. Each manual import and switch choice persists in an atomic file under `filesDir/plugin-settings/`. Restoring an imported plugin after restart requires no further access to the descriptor file.

## Verify

Build the target editions and run their debug unit suites. Check the compiled APKs for edition-only implementations. The GitHub emulator fixture `DlsitePluginDeviceTest` exercises the actual controller with local OCR, catalogue and cover fixtures. See [distribution variants](DISTRIBUTION_VARIANTS.md) for Gradle commands and signing details.
