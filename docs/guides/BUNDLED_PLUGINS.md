# Developing and importing a BCM plugin

Use Kotlin `.kt` files to implement a plugin. Give users a small `.bcmplugin` descriptor to import in **Plugins → Import plugin**. Adding new Kotlin code requires a new app build. Importing or switching an implementation already bundled with BCM takes effect in the running app.

## Use the GitHub marketplace

The small `marketplace.bcmplugin` setup file identifies the marketplace bundled in the GitHub build from `plugin/plugin-marketplace`. Importing it once adds the **Plugin Marketplace** switch.

1. Save [marketplace.bcmplugin](../../plugins/manifests/marketplace.bcmplugin) to **Internal storage → Documents → BookCoverMatcher → Plugins** on the phone. In Documents, create a folder named **BookCoverMatcher** and open it. Inside **BookCoverMatcher**, create a folder named **Plugins**. If a folder already exists, open the existing one. If the browser uses Downloads, move the file with Android’s Files app. On GitHub, use **Download raw file**. Return to BCM and tap **Plugins** (puzzle piece next to **More** in the bottom bar). Tap **Import plugin** at the top, then select **marketplace.bcmplugin** from the Plugins folder in the Android file picker.
2. Turn on **Plugin Marketplace**. BCM fetches missing group descriptors from pinned GitHub commits, validates their format and IDs, and places each group’s switch under the marketplace. GitHub receives the file requests and normal connection information. BCM sends no workbook, search query or provider credential in these requests.
3. Turn on the groups you want. **DLsite Fetch** includes the DLsite books search. If your old build had a DLsite switch, BCM uses the same saved choice here. New groups start off unless an explicit legacy selection exists.
4. Turn off the marketplace to pause its providers. Downloaded cards are dimmed and marked **Inactive · Marketplace is off**. Their switches retain their positions. Turning the marketplace on restores selected providers immediately, including offline.
5. Use **Remove** under a group to drop it and clear its switch. BCM remembers the removal across restarts. With the marketplace on, tap **Download** to add it again, initially off. If a download fails, use **Retry download**. Removing the marketplace pauses its groups and keeps their choices for a later marketplace import.

For a published GitHub edition that includes Plugin Marketplace, attach `plugins/manifests/marketplace.bcmplugin` to the GitHub Release alongside the APK. Link the setup file in the release notes. The recommended device path is `Documents/BookCoverMatcher/Plugins/marketplace.bcmplugin` in shared internal storage. The setup file can be retained for re-import alongside custom descriptors. BCM manages marketplace-fetched providers through their switches.

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
4. Fresh installs start off. An explicit legacy selection can enable a fetch group on its first import. Saved plugin choices take precedence on subsequent imports. The `plugin/dlsite-fetch` branch retains the original descriptor and provider ID. Disabling removes transient results and links for that group and preserves saved workbooks and bookmarks.

Both debug editions accept the [demo card descriptor](../../plugins/manifests/demo.bcmplugin).

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

Import `plugins/manifests/reverse-image-discovery.bcmplugin` on the standalone GitHub branch and turn on **Reverse Image Discovery**. With online search enabled, Search displays SauceNAO and the IQDB browser handoff. Switching the plugin off cancels discovery, clears temporary results, and retains your saved key, bookmarks and workbooks. Play retains its existing reverse-image tools.
