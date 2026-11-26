# Privacy Policy

Document revision: 2026-11-26.3. Applies to BCM and BCM Plus 3.0.0-dev.9 (2999909).

## About this policy

BCM and BCM Plus are Android applications developed by **PoloTheFirst**. For privacy questions or requests, contact **[polo1st2023@gmail.com](mailto:polo1st2023@gmail.com)**.

This policy covers BCM and BCM Plus 3.0.0-dev.9. It carries forward the data practices reviewed for the [standard dev.7 edition](https://github.com/PoloTheFirst/BookCoverMatcher/tree/v3.0.0-dev.7) and [dev.7 Plus edition](https://github.com/PoloTheFirst/BookCoverMatcher/tree/v3.0.0-dev.7-plus). The previous v2.7.0 policy is retained as a historical document. Its [versioned text](https://github.com/PoloTheFirst/BookCoverMatcher/blob/v2.7.0/PRIVACY_POLICY.md) remains available.

**BCM** is the standard edition intended for Google Play. **BCM Plus** is the GitHub community test edition with bundled optional plugins. Dev.9 is a development version. This review does not cover future releases or independently modified builds.

The app matches cover images against your Excel collection on your device. Optional online features contact other organisations directly. There is no BCM account registration or developer-operated account service in these versions. A Google or Microsoft sign-in connects that provider; it does not create a BCM account.

## Data on your device

- **Photos and matching:** camera captures or images you select are decoded for cover matching. The app derives thumbnails, image fingerprints and embeddings, and generates ranked results. Saved result profiles may retain a re-encoded copy of the selected image, thumbnails and result metadata. A re-encoded image is not an untouched original photograph.
- **Workbooks:** selected `.xlsx` and `.xlsm` files are copied to app-managed storage when imported into Saved Workbooks. The app reads workbook metadata, worksheets, cells, comments and embedded images. Local parsed caches, image caches and matching indexes help it reopen and search files. Macros are not executed.
- **Saved collections:** folders, sessions, result profiles, bookmarks, wishlist items, workbook mappings and preferences are stored locally. The custom icon uses the accent hue saved on your device. Saving a result is separate from the temporary online session cache.
- **Credentials and preferences:** online-service settings and, in BCM Plus, a SauceNAO API key you enter are stored by the app. The SauceNAO key is encrypted using Android Keystore-backed key material, separately from ordinary preferences. Google and Microsoft authorization handling is described below. This is not a promise of additional encryption for every file or preference.
- **Exports:** files you export or share are placed in the destination you choose. They are separate copies and can outlive the app or the corresponding saved item.

Camera and file access are used for the functions you request. You can manage Android permissions in your device settings. Core matching and opening saved workbook copies can work offline; this does not mean the app makes no network requests.

## Google Drive import

BCM's use and transfer of information received from Google APIs is subject to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including its Limited Use restrictions.

When you choose **More → Open saved workbooks → From Google Drive → Sign in**, Google's authorization service handles account selection and consent. The app requests `https://www.googleapis.com/auth/drive.readonly`. This permission permits reading files across the authorized Drive account; the app's Excel-only filter does **not** narrow the OAuth permission itself.

The implemented browser lists My Drive folders and eligible `.xlsx`/`.xlsm` files and downloads the file you select to create a validated, app-private copy. Google receives authorization, account-information, listing, source-availability and download requests. Native Google Sheets conversion, shared-drive browsing, uploads, write-back, remote deletion and background synchronization are not implemented here.

The app uses AppAuth browser sign-in with PKCE. Access tokens and any refresh token returned by Google remain only in process memory, including after closing the Drive browser. Tokens can be renewed during that session. Disconnect or process termination clears the session; restarting the app requires sign-in again. No new token cache is written to disk. Direct Google Drive imports store the remote file identifier and version/checksum, size, modified time and check/download timestamps with the local copy. The app reads the signed-in user’s Drive permission identifier and email/display name using the existing Drive permission. The importing account identifier and display label, together with the last known source path, are stored with newly imported workbooks so the app can show their source and avoid checking or updating them under a different account. Opening a saved workbook or choosing Check source can check remote availability; explicit Rescan checks for changed content and offers a download. These actions do not create a background synchronization service. No access or refresh token is stored in the workbook library. Identical copies retain their existing source association. Older cloud copies without an account binding require explicit source-account confirmation before remote checking or updating. Google and the external browser may retain their own account, cookie or consent state independently.

**Import** opens Android's document picker for general file imports. The dedicated Google Drive action uses the direct API browser; it has no document-picker fallback. For general Import, the selected document provider handles account access. The app reads the selected file and stores a local copy. Where supported, it retains read access to that selected document and records its document URI and available location label to check whether the original is still accessible. This is not broad storage access or permission to write the original, and it does not create a direct cloud-update link. Android may not provide a full device path. Re-link original file verifies identical content before recording a replacement source link. Only `.xlsx` and `.xlsm` imports are accepted, although other files may be visible in the picker.

**Disconnect** clears the app's Google import session; it does not revoke the permission at Google, remove your Google account from Android, or delete a cloud file. To revoke access, manage the BCM connection (which may still be named BookCoverMatcher) in your Google Account's third-party connections. Saved local copies remain usable until you remove them.

## OneDrive import and checks

The optional OneDrive connection uses AppAuth browser sign-in with PKCE and Microsoft Graph with `Files.Read` and `User.Read` permissions for a personal Microsoft account, plus `offline_access` for token renewal. Microsoft handles authentication, consent and token issuance. The app keeps access and refresh tokens only in process memory; restarting the app requires sign-in again. The previous MSAL cache is not read or migrated by this version; older installations may retain that unused cache until app data is cleared or the app is uninstalled. Clearing app data also deletes saved local workbooks. The app reads the signed-in Microsoft user identifier and email/username/display name through the existing User.Read permission. The importing account identifier and display label, last known path, source file identifier and version metadata are retained with OneDrive-origin workbook entries for source display and account-matched checks/updates.

Selecting a workbook downloads a local copy. Opening that saved copy is local. Opening a saved workbook or choosing Check source can check source availability. Rescan can check for a newer version and offer a download; there is no background workbook synchronization service. The app does not upload workbook edits or delete the cloud original.

**Sign out** clears the app's in-memory Microsoft connection. It does not clear the external browser's cookies or an old MSAL cache. It is separate from withdrawing permission through your Microsoft account and does not delete your Microsoft account, cloud files or saved workbook copies.

## Optional services and edition differences

**Use online services** is off on a fresh installation. The app remembers your choice. In standard BCM, on-device **PP-OCRv6** is the default OCR engine; optional **Chrome Lens** sends an image to Google. The reviewed standard APK does not bundle catalogue adapters, SauceNAO or IQDB discovery clients.

BCM Plus bundles five provider groups and a local plugin marketplace. The groups start enabled on a fresh install; saved choices take precedence after that. Enabling a group alone does not run a search. The separate online-services switch and the action you take determine what is sent. Plus defaults to Chrome Lens for OCR, and its automatic catalogue search after successful OCR starts enabled. Review these settings before selecting an image. Choosing on-device OCR does **not** prevent Plus from sending the resulting title to selected catalogue providers when automatic search remains enabled.

The bundled groups cover Open Library/Google Books, DLsite, shop shortcuts, other catalogue shortcuts, and reverse-image discovery. A `.bcmplugin` file identifies a compiled plugin; it is not arbitrary downloaded executable code. Disabling a group clears its transient results and hides its actions, but preserves saved workbooks, bookmarks and any saved SauceNAO key. See [Support & data removal](https://blog.p2nd.online/apps/bookcover-matcher/support/) for separate removal steps.

### What is sent, where, and when

| Feature | Recipient and data | Action that initiates it |
| --- | --- | --- |
| Chrome Lens OCR — both editions | Google Lens receives the selected cover image and requested OCR/translation language settings through `chrome-lens-py`. | Enable online services, select Chrome Lens, then capture/select an image or use **Read the cover text**. Selecting/capturing a new cover while enabled can start OCR immediately; a separate search-button press is not required. |
| PP-OCRv6 — both editions | The OCR image is processed on the device. In Plus, the extracted title can subsequently go to the catalogue recipients below. | Select the on-device engine and read/capture a cover. Disable automatic catalogue search or online services if you do not want onward text searches. Engine failure does not silently switch engines. |
| Catalogue fetch plugins — Plus | The selected Open Library (`openlibrary.org`), Google Books (`www.googleapis.com/books/v1/volumes`) and/or DLsite (`www.dlsite.com`) receive your typed, pasted or OCR-derived query and search parameters. | With the relevant plugin and online services enabled, submit the query to selected providers, select a query suggestion, or let the enabled automatic-search setting search a successful OCR result. |
| SauceNAO — Plus | SauceNAO (`saucenao.com`) receives the selected cover image and your stored API key. | Enable reverse-image discovery and online services, supply a key, then choose **Search by this cover**. Without a key the SauceNAO request is not sent. |
| IQDB — Plus | Opening `iqdb.org` makes a browser request. An image reaches IQDB only if you upload it on that website. | Open the IQDB page, then separately choose an upload in your browser. BCM does not automatically upload an image or query IQDB. |
| Provider shortcuts — Plus | Your browser opens a URL that can include the current query. Destinations include Open Library, Google Books, DLsite, Melonbooks, Suruga-ya, Toranoana, nhentai, E-Hentai, Doujin no Mori, MyAnimeList and Doujinshi.info. | Tap a provider's **Open** action. Browser-only providers are not queried by the app's automated catalogue search. |
| Result pictures and page metadata — Plus | The provider's image host or the linked result's page/image host receives the requested URL and ordinary connection information. | Run a search or load its results; previews can be fetched automatically as results appear, before you open a result in a browser. |

The browser-shortcut domains are `openlibrary.org`, `google.com`/`books.google.com`, `dlsite.com`, `melonbooks.co.jp`, `suruga-ya.jp`, `ec.toranoana.jp`, `nhentai.net`, `e-hentai.org`, `doujinnomori.com`, `myanimelist.net` and `doujinshi.info`. Result links and redirects can lead to other hosts. Some catalogue destinations contain adult material; review the destination and its rules before opening it.

### Content derived from a Drive-imported workbook

A saved Drive workbook is a local copy, but its content is **not automatically barred from later online features**. The following paths are present in the reviewed code and also apply to other workbook sources:

- **Title or other cell text:** open a workbook cell, choose **Copy**, paste its value into Plus's online query and submit a search. The selected catalogue recipients above receive that text. Using a provider shortcut instead places it in a browser URL for that provider.
- **Embedded cover image:** export a workbook image, then select that exported image as a cover. If online services and Chrome Lens are enabled, selecting it starts an image transfer to Google Lens. In Plus, successful OCR can also send its derived title to selected catalogue providers through automatic search. Choosing **Search by this cover** can send the image to SauceNAO; a separate manual browser upload can send it to IQDB.
- **Workbook hyperlink:** choose **Open Link** on a web-linked cell. Your browser receives the cell's destination URL, and the destination receives the request, including any workbook-derived text in that URL. Internal worksheet links stay in the app's source view.
- **Export or share:** choosing a destination or recipient outside the app places a copy there. A cloud document provider or recipient may receive that material under its own service rules.

Importing a workbook does not itself submit its contents to a catalogue or reverse-image service. No automatic whole-workbook upload to those services was identified in the reviewed source. These later actions can nevertheless transfer workbook-derived data; the search/image inputs do not enforce a Drive-origin exclusion. Do not send private material unless you intend and are authorized to share it. The source review does not establish the adequacy of consent for every workflow or independently verify each provider's retention practices.

These services receive your IP address and ordinary network/request information in addition to the feature data above. Their own policies govern their processing and retention. Clearing this app's data cannot withdraw an image or query already sent.

## Author information, diagnostics and support

The Author page in More opens expanded and displays bundled or cached content. At startup the app attempts a background refresh of the public profile README and permitted images. Requests can go to GitHub's raw-content hosting and Shields.io, even if you do not run online matching. These hosts receive ordinary connection information; this refresh does not upload your collection.

The app writes operational diagnostics to Android's device logs, including request stages, status/error codes, engine choices and some exception details. Google import diagnostics avoid tokens and account details; OneDrive diagnostics include service request identifiers. The app's OCR failure logger records a fixed failure category and byte count, without exception messages, causes or Python tracebacks. The app does not deliberately log OAuth payloads or tokens. Do not assume every log is free of sensitive information: review and redact logs before sharing them. No dedicated developer analytics or crash-upload service was identified in the reviewed application code. Authentication services, browsers and other providers still operate under their own practices; this is not a claim that no SDK or provider processes diagnostic data.

If you email support, the developer and email provider receive your address, message and any attachments you send to resolve your request. GitHub issues and their attachments are public. Do not include passwords, access tokens, API keys, private workbooks or unredacted account information. Support correspondence is retained as needed to address and follow up the request; contact the address above about deletion. The developer cannot delete records controlled independently by a provider.

## Retention, deletion and your choices

Saved workbooks and collections remain in app storage until you remove them, clear app storage or uninstall. Temporary online results are held in a bounded memory cache and can be cleared separately; parsed workbook and image caches may remain until cleared or replaced. Author data is cached and refreshed. There is no general time-based deletion promise for saved items.

- In **More → Open saved workbooks**, use a workbook's **Remove** action and confirm to remove the library entry and its saved copy. The app reports a file-removal failure if it cannot remove the copy. This does not remove the original local/cloud file, previously saved result profiles or exports.
- **Clear parsed caches** removes regenerable workbook caches, not saved workbooks. **More → Clear online session cache** clears temporary search results, not bookmarks or wishlist data.
- Remove saved folders, profiles and bookmarked/wishlist items through their own controls. Copies or independently saved results require separate removal.
- Android **Settings → Apps → BCM or BCM Plus → Storage → Clear storage/data**, or uninstalling, removes app-managed data through Android. Device labels vary. Back up anything you want to keep first. This build disables Android automatic app backup; exports, device/vendor transfers and provider-managed copies require separate attention.
- Revoke cloud permissions through the relevant provider to prevent future access. Disconnecting, deleting app data and revoking access are different actions.

See [Support & data removal](https://blog.p2nd.online/apps/bookcover-matcher/support/) for practical instructions. Contact the developer about data you sent directly to support. Contact the provider about its records, consent or account.

## This website is separate from the Android app

Polo's Atelier introduces the app and hosts documentation. It does not accept cover/workbook uploads or run matching in your browser. Appearance settings are saved in browser local storage; clear site data in your browser to reset them. The current site configuration disables its optional Umami analytics and comments integrations.

Requests still pass through the website's hosting, reverse proxy and any network/CDN services in use. These systems can process IP addresses, requested URLs, timestamps, browser details and security/access logs to deliver and protect the site. That processing is separate from app matching. Contact the developer for hosting-log questions; no universal retention period or worldwide access guarantee is made here.

## Source metadata retention

Source account labels and identifiers are provenance for saved workbook copies, not account passwords or tokens. Signing out clears the active connection but keeps the saved workbook and its source metadata. Removing a saved workbook removes its recorded provenance; a retained local-document read grant is released when no saved entry uses it. Re-linking a local original releases the old grant when no other saved entry uses it. Clearing app data or uninstalling also removes app-held source records and grants. Provider/browser account state remains separate. Legacy copies with no recorded original location cannot reconstruct it automatically.

## Changes and contact

Policy changes will be published at this stable URL with a new revision and effective date. Review the version relevant to the app you install. Questions, corrections and requests can be sent to **[polo1st2023@gmail.com](mailto:polo1st2023@gmail.com)**.
