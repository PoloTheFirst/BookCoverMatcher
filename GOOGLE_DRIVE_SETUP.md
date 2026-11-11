# Set up Google Drive for BookCoverMatcher

Use this walkthrough on your computer's browser. It configures Google Cloud for the development APK already installed on the emulator: **2.7.0-dev.1 / 224**.

You will use two Google accounts, or one account for both roles:

- **Project owner:** the account you use in Google Cloud Console.
- **Test user:** the account whose My Drive files you want to browse in the app.

Keep the same Cloud project selected throughout steps 1-5. Your credentials in `.env` do not create this configuration. Do not paste passwords or tokens into Cloud configuration fields or chat.

## 1. Select or create the project

1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Sign in with the project-owner account.
3. Click the project selector beside the Google Cloud logo at the top.
4. Select your existing project, or choose **New Project**, enter `BookCoverMatcher`, then click **Create**.
5. Select the project you just created. Its name should appear in the top bar.

**Checkpoint:** the top bar shows the project you intend to use. A project name is not an OAuth client ID; you do not need to copy it into the app.

Google's reference: [Create a Cloud project](https://developers.google.com/workspace/guides/create-project).

## 2. Enable Google Drive API

1. In that project, open [the Google Drive API page](https://console.cloud.google.com/apis/library/drive.googleapis.com).
2. Check the project selector again.
3. Click **Enable**. If the page offers **Manage** instead, the API is already enabled.

**Checkpoint:** **APIs & Services > Enabled APIs & services** lists **Google Drive API**. Enabling Google Sheets API does not replace this step.

## 3. Configure Google Auth Platform and the test account

Open [Google Auth Platform](https://console.cloud.google.com/auth/overview). You can also search for `Google Auth Platform` in the Console search bar. If you reach the older **OAuth consent screen** entry under APIs & Services, follow it into Auth Platform.

### First-time setup

If you see **Get started**, click it and complete the wizard:

| Field | Enter/select |
| --- | --- |
| App name | `BookCoverMatcher` |
| User support email | An email address you control |
| Audience | **External** for testing with a personal Gmail account |
| Contact information | An email address you monitor |

Accept the required policy acknowledgement and create/save the configuration. Supply genuine URLs if the form requires a homepage/privacy policy; do not invent them.

### Add the test user

1. Open **Audience** in Auth Platform's left menu.
2. Keep the publishing status **Testing** for this development test.
3. Under **Test users**, click **Add users**.
4. Enter the exact Google email address you will select inside BookCoverMatcher. Add it even if it is also the project-owner account.
5. Save.

**Checkpoint:** the intended test email appears under Test users. **Internal** is for a Google Workspace organization; it will not permit an unrelated personal Gmail account. Do not click **Publish app** just to perform this test.

Google's reference: [Configure OAuth consent](https://developers.google.com/workspace/guides/configure-oauth-consent).

## 4. Add the read-only Drive scope

1. Open **Data Access** in Google Auth Platform.
2. Click **Add or remove scopes**.
3. Find and select the scope with this exact value:

```text
https://www.googleapis.com/auth/drive.readonly
```

4. If it is not in the list, use the dialog's manual-entry option, enter the value above, and add it.
5. Click **Update**, then **Save** if the page shows a separate Save button.

**Checkpoint:** Data Access lists `drive.readonly`. Do not substitute `drive.file`, full `drive`, or a Google Sheets scope for this implementation.

Google classifies this as a restricted scope. It permits reading/downloading Drive files broadly; the app itself filters imports to `.xlsx` and `.xlsm`. Public-distribution verification is separate from this test setup. See [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

## 5. Register the installed Android build

1. Open **Clients** in Google Auth Platform.
2. Click **Create client**.
3. For **Application type**, choose **Android**.
4. Fill in these values exactly:

| Field | Value |
| --- | --- |
| Name | `BookCoverMatcher local debug` (a descriptive label) |
| Package name | `com.bookcovermatcher` |
| SHA-1 certificate fingerprint | `EF:2F:B0:53:4A:6D:52:0C:B5:58:C1:C3:62:BF:4F:4D:19:84:B6:11` |

5. Click **Create**. If Android ownership verification is offered, it is not needed for this local test.

**Checkpoint:** Clients contains an **Android** client with the package and fingerprint above. If Google says that package/certificate pair is already registered, locate the existing client/project rather than repeatedly creating duplicates; its Drive API, consent settings and test-user list must be configured in that same project.

You are finished with Cloud setup. This app uses on-device authorization, not server auth codes: do not create a web-client secret or service account for it. There is no JSON file to put in the repository, no Firebase setup, and no client-ID value to add to `.env` for this implementation.

The fingerprint above was verified against the local APK's actual signing certificate. A differently signed APK needs its own Android client registration. To inspect your current Gradle signing configuration later:

```powershell
Set-Location 'C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher'
.\gradlew.bat :app:signingReport
```

Use the **debug variant's SHA1** for a local debug APK. Google Play distribution uses the relevant Play signing certificate instead.

Google's reference: [Android OAuth client setup](https://developer.android.com/identity/authorization).

## 5A. Register the signed release APK used on a physical phone

The emulator APK and the locally exported signed release APK use **different signing keys**. If you install `app/release/app-release.apk` on your phone, registering only the emulator's debug fingerprint is insufficient.

Both artifacts were inspected with apksigner:

| APK | Android package | SHA-1 |
| --- | --- | --- |
| Emulator/debug: `app/build/outputs/apk/debug/app-debug.apk` | `com.bookcovermatcher` | `EF:2F:B0:53:4A:6D:52:0C:B5:58:C1:C3:62:BF:4F:4D:19:84:B6:11` |
| Locally exported signed release: `app/release/app-release.apk` | `com.bookcovermatcher` | `27:97:2D:88:B1:8D:0F:EC:A4:4F:DF:98:7C:75:3E:A6:22:38:16:3A` |

For the signed release:

1. Select the **same Cloud project** configured in steps 1-4.
2. Open **Google Auth Platform > Clients > Create client**.
3. Choose **Android**.
4. Name it `BookCoverMatcher signed release`.
5. Enter package `com.bookcovermatcher`.
6. Enter SHA-1 `27:97:2D:88:B1:8D:0F:EC:A4:4F:DF:98:7C:75:3E:A6:22:38:16:3A`.
7. Create/save the client. **Keep the original debug client.**
8. Retry From Google Drive > Sign in on the phone, using the test account.

This is an additional registration for a signing key, not a registration for each phone. No app uninstall, account removal, or .env change is needed for this Cloud configuration correction. The debug diagnostic APK cannot update an app signed with the release key; do not uninstall a working saved-workbook library merely to switch signing keys. If the phone's APK came from another build or Google Play, inspect that APK's actual signing certificate instead of assuming either row above applies.

## 6. Test in the emulator

1. Let me know steps 1-5 are saved; no password, token, client secret or JSON download is needed in chat.
2. Make sure the emulator is online and has Google Play services. The development APK is already installed on `emulator-5554`.
3. In BookCoverMatcher, open **More > Open saved workbooks > From Google Drive > Sign in**.
4. Select the exact account added as a test user in step 3. Complete sign-in and Google's consent screen yourself.
5. Grant the requested read access. If you see an unverified-app warning, confirm this is your own project/test app and use only an official testing continuation Google offers. A blocked screen without a continuation is a configuration/policy issue to resolve, not something to bypass.
6. The app should show **My Drive** and its folders. Open a folder containing an existing Excel file, select it, and wait for the saved result.
7. Choose **Use it now**, then confirm the workbook opens. Repeat with both `.xlsx` and `.xlsm`.
8. Restart the app, turn off the emulator's network, and open the saved workbook again. It should use the private local copy. Restore networking afterward.

Use existing files or upload your own test workbooks through Google Drive. Native Google Sheets documents are not Excel files, even if renamed `.xlsx`; this app does not convert them. Shared drives, Shared with me and shortcuts are outside this first version.

**Success means:** folder navigation works, both genuine Excel formats import, and saved copies reopen offline. Seeing an account chooser alone is not a completed test.

## If a step fails

| What you see | Check next |
| --- | --- |
| No Get started button | Auth Platform may already be configured; open Branding, Audience, Data Access and Clients directly. |
| Only Internal audience is available | Check organization/project restrictions; a personal-account test needs a project that permits External users. |
| Access blocked / account not allowed | Confirm the selected Google account is in the same project's Test users list; check organization restrictions. |
| Developer error / app not configured | Compare the Android client's package and SHA-1 with step 5; check that it belongs to the project configured in steps 2-4. |
| API disabled / access not configured | Enable Google Drive API in the OAuth client's project. |
| Consent succeeds but folders fail | Record the app's exact error; verify Drive API, scope and account access. Do not assume sign-in proves Drive access. |
| No workbook appears | Navigate to My Drive; use an existing binary `.xlsx`/`.xlsm`, not a native Sheet, shortcut, trashed file or download-restricted file. |
| Access expires later | Use Sign in again; external testing authorizations can expire. |

Tell me the step number, screen title and exact error text if you get stuck. Keep passwords, MFA codes and tokens private.

## What the app retains

Saved copies work offline and rescan locally. No Google synchronization, upload, write-back, remote deletion or cloud refresh is added. Duplicate content keeps its existing library identity/provider. New Google entries store their origin but no account ID, access token or remote file ID. **Disconnect** clears the app's in-memory session; it does not revoke the Google grant or remove your device account.


## If the emulator signs in but a physical phone does not

**Vivo X100 Pro case resolved:** the owner confirmed the selected account was not a registered test user. Using a registered testing account resolved the failure. Earlier signing-certificate checks remain useful generally, but were not confirmed as the cause of that case.


Using the same account rules out one common test-user mismatch, but it does not prove the phone uses the same signed APK or has the same Google Play services environment. Record the exact error and the installed app version first. A copied, identical APK uses the same certificate on both devices; Google OAuth registration is for package/certificate, not a separate registration for every phone.

The initial 2.7.0-dev.1 APK labelled every non-success Android consent result as "cancelled or denied" before inspecting Google's result payload. That wording cannot establish that the user actually denied consent. The revised diagnostic APK parses any returned Google status, shows its numeric code, and reports a missing result separately. It logs only numeric status/result codes and whether data was present, under `GoogleDriveAuth`; it never logs tokens, account names, Intent contents or exception messages.

To inspect the phone, connect it by USB, enable USB debugging and accept the computer authorization prompt. Check the actual device serial with `adb devices -l`; do not assume it is emulator-5554. Then reproduce once and inspect only the diagnostic tag:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
# Replace PHONE_SERIAL with the physical phone's serial from that list.
& $adb -s PHONE_SERIAL logcat -d -s GoogleDriveAuth:I
```

Google code 10 indicates a developer/configuration error; compare the installed APK's signing certificate and package with the Android OAuth client. Code 7 indicates a network error. Cancellation codes indicate what Google reported, not proof of a manual denial. A missing Intent requires further device-side diagnosis. Avoid clearing Play services storage or removing accounts as a first troubleshooting step.


## Planned dev.2 Google Play and OAuth production agenda

As of 2026-11-11, the owner reports completed Google Play developer-account verification and the ability to create apps. Publishing **2.7.0-dev.2 / 225** on Play and moving Google Auth Platform Audience from Testing to In production are part of the dev.2 review agenda. The OAuth switch is still planned. Account verification does not mean a specific app release has passed review or is available on Play.

### Play-distributed signing and upload

For a new Play app, prepare a signed Android App Bundle, not the GitHub debug APK. The repository's default release signing is currently debug; production upload signing must be configured with the intended local upload key before the artifact is represented as ready. Never place keys/passwords in tracked files or chat. Confirm the target app/track and whether code 225 is available before uploading; do not silently allocate another code.

Google Play's delivered app may use a different certificate from both your upload key and locally exported APK. In the app's Play Console, open its app-signing/integrity page, copy the **app signing key certificate SHA-1**, and create the matching Android OAuth client for com.bookcovermatcher in the same Google Cloud project. Do not substitute the upload-key fingerprint. Keep the other signing clients for their respective distributions. Recheck the OneDrive/MSAL Android redirect for this delivered signing identity and test the actual Play-installed build on the phone.

References: [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756), [OAuth client signing identity](https://developers.google.com/android/guides/client-auth), [Android App Bundles](https://developer.android.com/guide/app-bundle).

### OAuth Testing to In production

This setting is in **Google Auth Platform > Audience**, separate from Google Play Console. Review the app's current branding, public homepage/privacy/terms URLs, authorized domains and requested scopes before publishing. A production audience does not, by itself, establish approval for restricted drive.readonly access or remove every unverified-app limitation. Verify branding and data-access status separately in the Verification Center.

Logo, privacy policy, terms, Play release preparation and the OAuth transition now all belong to the same dev.2 review prompt. Complete their reviewed implementation and verify the required public documents and signing setup before any approved submission; do not claim Google approval from this agenda entry. The intended Console project/app and Play track must be identified before a concrete external submission/rollout.

Reference: [Google Auth audience and publishing statuses](https://support.google.com/cloud/answer/15549945).


## Planned dev.2 preparation order and repository links

Complete branding materials before creating the Play app, as requested by the owner. This section describes future work; the PNG and policy files are not claimed as created or published yet.

Planned repository locations:

- Home/source: https://github.com/PoloTheFirst/BookCoverMatcher
- Privacy Policy: https://github.com/PoloTheFirst/BookCoverMatcher/blob/main/PRIVACY_POLICY.md
- Terms and Conditions: https://github.com/PoloTheFirst/BookCoverMatcher/blob/main/TERMS_AND_CONDITIONS.md
- Logo for review/download: https://github.com/PoloTheFirst/BookCoverMatcher/blob/main/branding/bookcovermatcher-logo-120.png

The Markdown files remain the shared source for repository and in-app copies. Verify these links anonymously after dev.2 files are actually published; until then, a planned URL may return 404. The Branding logo control takes the actual local PNG upload, not a GitHub file-page URL.

### Branding first

1. In the correct Google Cloud project, open Google Auth Platform > Branding.
2. Upload `branding/bookcovermatcher-logo-120.png`: PNG, exactly 120 x 120, below 1 MB.
3. Set the app name and owner-approved support contact. Fill the homepage, Privacy Policy and Terms of Service fields using the prepared public destinations.
4. Save and inspect the verification result. Keep the URLs consistent with the homepage and in-app text.

**Repository links are the requested starting point, not a guarantee of acceptance.** Google requires a homepage on a verified domain you own and policy-domain consistency. Owning this repository does not give ownership of github.com. Do not claim or register github.com as an owned domain. If Google rejects the repository/blob URLs, retain the Markdown sources in this repository and use an owner-approved hosted rendering on a verifiable domain; document the actual accepted URLs and required owner steps before submission.

Reference: [Branding and public-URL requirements](https://developers.google.com/identity/protocols/oauth2/production-readiness/brand-verification).

### OAuth audience after branding preparation

Open Google Auth Platform > Audience, confirm External and the intended project, then review **Publish app** / **In production** when the owner authorizes that transition. Production audience removes the Testing-list-only audience restriction, but unapproved restricted scopes can still carry warnings/limits or require review. Check branding and data-access verification independently; do not equate the audience switch with approval.

Reference: [Audience and publishing status](https://support.google.com/cloud/answer/15549945).

### Then create the app in Google Play Console

1. Sign in at https://play.google.com/console with the verified developer account; select **Create app**.
2. Enter BookCoverMatcher, package `com.bookcovermatcher` if requested, and the default language.
3. Select App rather than Game. The owner chooses Free/Paid; do not infer pricing from GPL or choose it silently.
4. Provide the owner-approved support contact. Read and accept required declarations, including the applicable Play signing terms, then select Create app.
5. Use the Dashboard tasks to complete the listing/app-content declarations with the audited privacy/data facts and public policy link.
6. Configure signing and an owner-selected testing track, then upload the properly signed dev.2 AAB. Register the actual Play app-signing OAuth certificate and test that delivered build before an approved rollout.

Creating an app record is distinct from submitting a release or making it public. No Console click, declaration acceptance or rollout is authorized by this draft guide alone.

Reference: [Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152).
