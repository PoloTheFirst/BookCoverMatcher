# Developing and importing a BCM plugin

Use Kotlin `.kt` files to implement a plugin. Give users a small `.bcmplugin` descriptor to import in **Plugins → Import plugin**. Adding new Kotlin code requires a new app build. Importing or switching an implementation already bundled with BCM takes effect in the running app.

The plugin supplied by this branch is **Doujin Shop Shortcuts**.

## Platform groups in the GitHub edition

Each local plugin branch supplies one group and its descriptor in `plugins/manifests/`. A combined developer build can include several. Only implementations compiled into the installed GitHub APK can be imported. Play rejects all four descriptors.

| Index | Branch under `plugin/` | Descriptor | Platforms in display order |
| --- | --- | --- | --- |
| 0 | ordinary-library-fetch | ordinary-library.bcmplugin | Open Library, Google Books |
| 1 | dlsite-fetch | dlsite.bcmplugin | DLsite |
| 2 | doujin-shop-shortcut | doujin-shop.bcmplugin | Melonbooks, Suruga-ya, Toranoana |
| 3 | explicit-database-shortcut | explicit-database.bcmplugin | nhentai, E-Hentai, Doujin no Mori, MyAnimeList |

1. Copy the matching descriptor to Downloads on the device. Open **Plugins → Import plugin** and select the file.
2. Turn on its switch. In **Search → Online → Platforms**, enabled groups appear by ascending index. Doujinshi.info belongs to Explicit Database Shortcuts and is absent from this standalone build. Import order and restart do not affect sorting.
3. A catalogue search sends text to selected fetch providers when online services are enabled. General-book sources and per-library selections still control Open Library and Google Books. Shortcut groups open a site only when you tap its browser action. Some sites contain mature material.
4. Fresh installs start off. An explicit legacy selection can enable a fetch group on its first import. Saved plugin choices take precedence on subsequent imports. The DLsite branch retains the original descriptor and provider ID. Disabling removes transient results and links for that group and preserves saved workbooks and bookmarks.

Both debug editions accept the [demo card descriptor](../../plugins/manifests/demo.bcmplugin).

## Define a platform group

Place an internal Kotlin class ending in `Group` in the GitHub source package `com.bookcovermatcher.plugins.groups`, one class per matching `*Group.kt` file. Extend `PlatformGroup`, supply a unique ID and index, and declare ordered provider specifications. Fetch groups supply a factory creating fresh adapters on activation. Shortcut groups declare browser links. Gradle generates typed registrations using the classes present in that checkout. Concrete groups live on their own plugin branches, outside the framework branch.

## Create the descriptor

Save the following two lines as UTF-8 plain text in a file named `dlsite.bcmplugin`. For another plugin, replace the second line with its registered ID.

```text
BCM-PLUGIN/1
bcm.dlsite
```

BCM reads up to 256 bytes and checks the format, ID, host API and edition registry. The descriptor contains only a format header and plugin ID. The Kotlin implementation ships inside the app. Each manual import and switch choice persists in an atomic file under `filesDir/plugin-settings/`. Restoring an imported plugin after restart requires no further access to the descriptor file.

## Verify

Build the target editions and run their debug unit suites. Check the compiled APKs for edition-only implementations. The GitHub emulator fixture `DlsitePluginDeviceTest` exercises the actual controller with local OCR, catalogue and cover fixtures. See [distribution variants](DISTRIBUTION_VARIANTS.md) for Gradle commands and signing details.
