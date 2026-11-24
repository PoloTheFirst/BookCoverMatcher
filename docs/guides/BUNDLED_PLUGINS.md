# Developing and importing a BCM plugin

Use Kotlin `.kt` files to implement a plugin. Give users a small `.bcmplugin` descriptor to import in **Plugins → Import plugin**. Adding new Kotlin code requires a new app build. Importing or switching an implementation already bundled with BCM takes effect in the running app.

## Use the explicit edition

On a fresh install, open **Plugins**. The marketplace and all five group switches are on from the first launch, including offline. Disable a group to hide its actions. Disabling the marketplace pauses its children while retaining each switch position. Re-enabling the marketplace resumes the groups you selected. Use **Remove** to remove a group. With the marketplace on, choose **Restore** to restore it from the APK and choose whether to enable it.

If you remove the marketplace, import its [descriptor](../../plugins/manifests/marketplace.bcmplugin) under **Plugins → Import plugin** to bring the marketplace back. Android can store descriptors under **Documents → BookCoverMatcher → Plugins**. Previously removed groups stay removed until you restore them.

The standalone `plugin/plugin-marketplace` branch uses manual setup. Import its marketplace descriptor, enable the marketplace and choose which downloaded groups to activate. The Play edition on `google_play` has an empty registry.

## Platform groups in the GitHub edition

Each provider branch supplies one group and its descriptor in `plugins/manifests/`. A combined developer build can include several. Only implementations compiled into the installed GitHub APK can be imported. Play excludes these GitHub provider descriptors.

| Index | Branch under `plugin/` | Descriptor | Platforms in display order |
| --- | --- | --- | --- |
| 0 | ordinary-library-fetch | ordinary-library.bcmplugin | Open Library, Google Books |
| 1 | dlsite-fetch | dlsite.bcmplugin | DLsite |
| 2 | doujin-shop-shortcut | doujin-shop.bcmplugin | Melonbooks, Suruga-ya, Toranoana |
| 3 | explicit-database-shortcut | explicit-database.bcmplugin | nhentai, E-Hentai, Doujin no Mori, MyAnimeList, Doujinshi.info |
| 4 | reverse-image-discovery | reverse-image-discovery.bcmplugin | Separate discovery section for SauceNAO and IQDB |

1. On standalone provider branches, copy the matching descriptor to the device’s `Documents/BookCoverMatcher/Plugins/` folder. Open **Plugins → Import plugin** and select the file.
2. Turn on its switch. In **Search → Online → Platforms**, enabled groups appear by ascending index. Doujinshi.info appears only with Explicit Database Shortcuts enabled. Import order and restart do not affect sorting.
3. A catalogue search sends text to selected fetch providers when online services are enabled. General-book sources and per-library selections still control Open Library and Google Books. Shortcut groups open a site only when you tap its browser action. Some sites contain mature material.
4. On standalone provider branches, a newly imported group starts off unless a previous selection enables it. The explicit main edition starts with all groups enabled on a fresh install. Saved plugin choices take precedence on subsequent imports. The `plugin/dlsite-fetch` branch retains the original descriptor and provider ID. Disabling removes transient results and links for that group and preserves saved workbooks and bookmarks.

The [demo module](../../plugins/demo) illustrates the plugin contract on the framework branch. Main packages only the marketplace and five provider groups in its explicit flavor.

## Define a platform group

Place an internal Kotlin class ending in `Group` in the GitHub source package `com.bookcovermatcher.plugins.groups`, one class per matching `*Group.kt` file. Extend `PlatformGroup`, supply a unique plugin ID and a display index, and declare ordered provider specifications. Fetch groups supply a factory creating fresh adapters on activation. Shortcut groups declare browser links. Gradle generates typed registrations using the classes present in that checkout. Concrete groups live on their own plugin branches, outside the framework branch.

### Choose IDs and an index for a custom group

Choose a stable plugin ID, for example `org.example.library`, and give each platform its own stable provider ID. Check both against the target build before compiling. Reusing an official ID would collide with its registration or saved settings. A marketplace-owned plugin ID is managed through its marketplace switch.

The official groups currently occupy indices 0–4. For a new custom group, 100 is a convenient starting point. BCM sorts by ascending index, then by plugin ID when two groups choose the same number. For example, `org.example.archive` and `org.example.library` can both use 100 and appear in that order. The platforms within each group keep the author’s declared order. Set the index in the Kotlin implementation. The user’s descriptor contains only the format header and plugin ID.

For installation steps, see [Import a custom catalogue plugin](USER_GUIDE.md#import-a-custom-catalogue-plugin).

## Create the descriptor

Save the following two lines as UTF-8 plain text in a file named `dlsite.bcmplugin`. For another plugin, replace the second line with its registered ID.

```text
BCM-PLUGIN/1
bcm.dlsite
```

BCM reads up to 256 bytes and checks the format, ID, host API and edition registry. The descriptor contains only a format header and plugin ID. The Kotlin implementation ships inside the app. Each manual import and switch choice persists in an atomic file under `filesDir/plugin-settings/`. Restoring an imported plugin after restart requires no further access to the descriptor file.

## Verify

Build the target editions and run their debug unit suites. Check the compiled APKs for edition-only implementations. The GitHub emulator fixture `DlsitePluginDeviceTest` exercises the actual controller with local OCR, catalogue and cover fixtures. See [distribution variants](DISTRIBUTION_VARIANTS.md) for Gradle commands and signing details.

## Reverse Image Discovery

Import `plugins/manifests/reverse-image-discovery.bcmplugin` on the standalone GitHub branch and turn on **Reverse Image Discovery**. With online search enabled, Search displays SauceNAO and the IQDB browser handoff. Switching the plugin off cancels discovery, clears temporary results, and retains your saved key, bookmarks and workbooks. The Play APK excludes both discovery clients. Chrome Lens OCR is built in and works independently of discovery.
