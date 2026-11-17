# Privacy Policy

Document revision: 2026-11-14.1. Effective for development build 2.7.0-dev.2.

## About this policy

BookCoverMatcher is an Android application developed by **PoloTheFirst**. For privacy questions or requests, contact **[polo1st2023@gmail.com](mailto:polo1st2023@gmail.com)**. This policy describes BookCoverMatcher 2.7.0-dev.2 (225), including its Google Drive development-preview import.

The app matches cover images against your Excel collection on your device. Optional online features contact other organisations directly. There is no BookCoverMatcher account registration or developer-operated account service in these versions. A Google or Microsoft sign-in connects that provider; it does not create a BookCoverMatcher account.

## Data on your device

- **Photos and matching:** camera captures or images you select are decoded for cover matching. The app derives thumbnails, image fingerprints and embeddings, and generates ranked results. Saved result profiles may retain a re-encoded copy of the selected image, thumbnails and result metadata. A re-encoded image is not an untouched original photograph.
- **Workbooks:** selected `.xlsx` and `.xlsm` files are copied to app-managed storage when imported into Saved Workbooks. The app reads workbook metadata, worksheets, cells, comments and embedded images. Local parsed caches, image caches and matching indexes help it reopen and search files. Macros are not executed.
- **Saved collections:** folders, sessions, result profiles, bookmarks, wishlist items, workbook mappings and preferences are stored locally. Saving a result is separate from the temporary online session cache.
- **Credentials and preferences:** online-service settings and a SauceNAO API key you enter are stored by the app. The SauceNAO key is encrypted using Android Keystore-backed key material, separately from ordinary preferences. Google and Microsoft authorization handling is described below. This is not a promise of additional encryption for every file or preference.
- **Exports:** files you export or share are placed in the destination you choose. They are separate copies and can outlive the app or the corresponding saved item.

Camera and file access are used for the functions you request. You can manage Android permissions in your device settings. Core matching and opening saved workbook copies can work offline; this does not mean the app makes no network requests.

## Google Drive import — development preview

When you choose **More → Open saved workbooks → From Google Drive → Sign in**, Google's authorization service handles account selection and consent. The app requests `https://www.googleapis.com/auth/drive.readonly`. This permission permits reading files across the authorized Drive account; the app's Excel-only filter does **not** narrow the OAuth permission itself.

The implemented browser lists My Drive folders and eligible `.xlsx`/`.xlsm` files and downloads the file you select to create a validated, app-private copy. Google receives authorization, account-information, listing, source-availability and download requests. Native Google Sheets conversion, shared-drive browsing, uploads, write-back, remote deletion and background synchronization are not implemented here.

This local prototype uses AppAuth browser sign-in with PKCE. Access tokens and any refresh token returned by Google remain only in process memory, including after closing the Drive browser. Tokens can be renewed during that session. Disconnect or process termination clears the session; restarting the app requires sign-in again. No new token cache is written to disk. Direct Google Drive imports store the remote file identifier and version/checksum, size, modified time and check/download timestamps with the local copy. The app reads the signed-in user’s Drive permission identifier and email/display name using the existing Drive permission. The importing account identifier and display label, together with the last known source path, are stored with newly imported workbooks so the app can show their source and avoid checking or updating them under a different account. Opening a saved workbook or choosing Check source can check remote availability; explicit Rescan checks for changed content and offers a download. These actions do not create a background synchronization service. No access or refresh token is stored in the workbook library. Identical copies retain their existing source association. Older cloud copies without an account binding require explicit source-account confirmation before remote checking or updating. Google and the external browser may retain their own account, cookie or consent state independently.

**Import** opens Android's document picker for general file imports. The dedicated Google Drive action uses the direct API browser; it has no document-picker fallback. For general Import, the selected document provider handles account access. The app reads the selected file and stores a local copy. Where supported, it retains read access to that selected document and records its document URI and available location label to check whether the original is still accessible. This is not broad storage access or permission to write the original, and it does not create a direct cloud-update link. Android may not provide a full device path. Re-link original file verifies identical content before recording a replacement source link. Only `.xlsx` and `.xlsm` imports are accepted, although other files may be visible in the picker.

**Disconnect** clears the app's Google import session; it does not revoke the permission at Google, remove your Google account from Android, or delete a cloud file. To revoke access, manage BookCoverMatcher in your Google Account's third-party connections. Saved local copies remain usable until you remove them.

## OneDrive import and checks

The optional OneDrive connection uses AppAuth browser sign-in with PKCE and Microsoft Graph with `Files.Read` and `User.Read` permissions for a personal Microsoft account, plus `offline_access` for token renewal. Microsoft handles authentication, consent and token issuance. This prototype keeps access and refresh tokens only in process memory; restarting the app requires sign-in again. The previous MSAL cache is not read or migrated by this prototype; older installations may retain that unused cache until app data is cleared or the app is uninstalled. Clearing app data also deletes saved local workbooks. The app reads the signed-in Microsoft user identifier and email/username/display name through the existing User.Read permission. The importing account identifier and display label, last known path, source file identifier and version metadata are retained with OneDrive-origin workbook entries for source display and account-matched checks/updates.

Selecting a workbook downloads a local copy. Opening that saved copy is local. Opening a saved workbook or choosing Check source can check source availability. Rescan can check for a newer version and offer a download; there is no background workbook synchronization service. The app does not upload workbook edits or delete the cloud original.

**Sign out** clears this prototype's in-memory Microsoft connection. It does not clear the external browser's cookies or an old MSAL cache. It is separate from withdrawing permission through your Microsoft account and does not delete your Microsoft account, cloud files or saved workbook copies.

## Optional OCR, discovery and catalogue searches

The data sent depends on the feature and provider you select:

- **Chrome Lens OCR:** the app sends the selected cover image to Google Lens through the `chrome-lens-py` integration to obtain text, with the requested OCR/translation language settings. **PP-OCRv6** is an alternative on-device OCR engine; it processes the image locally. Engine failure does not silently switch to the other engine.
- **Reverse-image discovery:** using SauceNAO sends the selected cover image and the API key you supplied to SauceNAO. IQDB is a manual browser workflow: the app opens its search page but does not automatically upload an image or query IQDB. If you choose to upload an image on that website, your browser sends it to IQDB. You can bring results back into the app yourself. These workflows are different from local workbook matching and searching for a title as text.
- **Catalogue searches:** selected providers, such as Open Library, Google Books and supported shops/catalogues, receive the query you enter or derive from OCR, together with request parameters. Result thumbnails and page metadata may be retrieved from the provider or the result's image/page host. A catalogue result is not proof of a visual cover match.
- **Browser destinations:** opening a provider shortcut or result hands its URL, which may include your query, to your browser. The destination applies its own cookies, account settings, content rules and privacy practices. Some available catalogue destinations contain adult material; review the destination before opening it.

These services can receive your IP address and ordinary network/request information, in addition to the feature data above. Their own policies govern their processing and retention; clearing this app's data cannot withdraw an image or query already sent. Avoid sending private workbook material or personal information to a provider unless you intend to share it.

## Author information, diagnostics and support

At startup the app displays bundled or cached author information and attempts a background refresh of the public profile README and permitted images. Requests can go to GitHub's raw-content hosting and Shields.io, even if you do not run online matching. These hosts receive ordinary connection information; this refresh does not upload your collection.

The app writes operational diagnostics to Android's device logs, including request stages, status/error codes, engine choices and some exception details. Google import diagnostics avoid tokens and account details; OneDrive diagnostics include service request identifiers. The app's OCR failure logger records a fixed failure category and byte count, without exception messages, causes or Python tracebacks. The app does not deliberately log OAuth payloads or tokens. Do not assume every log is free of sensitive information: review and redact logs before sharing them. No dedicated developer analytics or crash-upload service was identified in the reviewed application code. Authentication services, browsers and other providers still operate under their own practices; this is not a claim that no SDK or provider processes diagnostic data.

If you email support, the developer and email provider receive your address, message and any attachments you send to resolve your request. GitHub issues and their attachments are public. Do not include passwords, access tokens, API keys, private workbooks or unredacted account information. Support correspondence is retained as needed to address and follow up the request; contact the address above about deletion. The developer cannot delete records controlled independently by a provider.

## Retention, deletion and your choices

Saved workbooks and collections remain in app storage until you remove them, clear app storage or uninstall. Temporary online results are held in a bounded memory cache and can be cleared separately; parsed workbook and image caches may remain until cleared or replaced. Author data is cached and refreshed. There is no general time-based deletion promise for saved items.

- In **More → Open saved workbooks**, use a workbook's **Remove** action and confirm to remove the library entry and its saved copy. The app reports a file-removal failure if it cannot remove the copy. This does not remove the original local/cloud file, previously saved result profiles or exports.
- **Clear parsed caches** removes regenerable workbook caches, not saved workbooks. **More → Clear online session cache** clears temporary search results, not bookmarks or wishlist data.
- Remove saved folders, profiles and bookmarked/wishlist items through their own controls. Copies or independently saved results require separate removal.
- Android **Settings → Apps → BookCoverMatcher → Storage → Clear storage/data**, or uninstalling, removes app-managed data through Android. Device labels vary. Back up anything you want to keep first. This build disables Android automatic app backup; exports, device/vendor transfers and provider-managed copies require separate attention.
- Revoke cloud permissions through the relevant provider to prevent future access. Disconnecting, deleting app data and revoking access are different actions.

See [Support & data removal](https://blog.p2nd.online/apps/bookcover-matcher/support/) for practical instructions. Contact the developer about data you sent directly to support. Contact the provider about its records, consent or account.

## This website is separate from the Android app

Polo's Atelier introduces the app and hosts documentation. It does not accept cover/workbook uploads or run matching in your browser. Appearance settings are saved in browser local storage; clear site data in your browser to reset them. The current site configuration disables its optional Umami analytics and comments integrations.

Requests still pass through the website's hosting, reverse proxy and any network/CDN services in use. These systems can process IP addresses, requested URLs, timestamps, browser details and security/access logs to deliver and protect the site. That processing is separate from app matching. Contact the developer for hosting-log questions; no universal retention period or worldwide access guarantee is made here.

## Source metadata retention

Source account labels and identifiers are provenance for saved workbook copies, not account passwords or tokens. Signing out clears the active connection but keeps the saved workbook and its source metadata. Removing a saved workbook removes its recorded provenance; a retained local-document read grant is released when no saved entry uses it. Re-linking a local original releases the old grant when no other saved entry uses it. Clearing app data or uninstalling also removes app-held source records and grants. Provider/browser account state remains separate. Legacy copies with no recorded original location cannot reconstruct it automatically.

## Changes and contact

Policy changes will be published at this stable URL with a new revision and effective date. Review the version relevant to the app you install. Questions, corrections and requests can be sent to **[polo1st2023@gmail.com](mailto:polo1st2023@gmail.com)**.
