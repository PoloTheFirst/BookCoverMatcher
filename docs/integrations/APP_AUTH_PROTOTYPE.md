# Browser authentication prototype

Local, unpublished work for **225 / 2.7.0-dev.2**, authorized on 2026-11-13. Local debug and release APK builds are allowed for device testing. The explicit `verifyAuthPublicationEligibility` publication check deliberately fails until the remaining gates are closed. Building or signing an APK does not authorize uploading it. This document supersedes the GIS/MSAL authentication instructions for this local build. It does not authorize changing Google publishing status, deploying the website, or uploading an app.

## Implemented scope

- Google and personal Microsoft accounts use AppAuth 0.11.1 with an external browser, authorization code, random state and S256 PKCE. No embedded WebView, client secret, desktop OAuth client or developer backend is used.
- Direct Drive/OneDrive folder browsing, `.xlsx`/`.xlsm` download into Saved Workbooks and explicit Rescan remain. The Google action has no document-picker fallback. The independent general Import action remains.
- Access and refresh tokens stay in memory. AppAuth refreshes tokens during the process lifetime. Closing a cloud browser preserves the session; disconnect clears it. Restarting the app requires sign-in again. Browser cookies and provider consent remain independent.
- Source identity: Drive about.user (permissionId/emailAddress/displayName) and Graph /me (id/mail/userPrincipalName/displayName) use the existing scopes. Labels appear on import buttons; new workbook links retain the importing account key/label locally. Old links require explicit source-account confirmation. Source checks never interpret 403, sign-out or network failure as deletion.
- Old MSAL caches are neither read nor migrated. Existing workbook data and cloud metadata are preserved. Do not clear app data to test authentication: that would delete saved workbooks.
- Direct MSAL and Google SDK dependencies are removed, including the proprietary Google libraries previously pulled transitively through MSAL. Other app dependencies still need their existing release review.

## Google owner setup

1. Open Google Cloud / Google Auth Platform in the existing project with Drive API enabled. Keep the audience in Testing while validating this prototype and keep the selected account in Test users.
2. Under Clients, select an **Android** OAuth client for `com.bookcovermatcher`, with the SHA-1 of the certificate used to sign the installed APK. Obtain the local fingerprints using `./gradlew.bat :app:signingReport`. A Play-distributed app will need its Play App Signing certificate registered separately.
3. In that Android client's Advanced settings, enable the custom-URI scheme option. Google's guidance permits opting in when its recommended SDK does not meet the app's needs; this does not itself establish publication approval. If this option is unavailable, stop and investigate rather than substituting a Web/Desktop client.
4. Put only its public client ID in the ignored repository-root `.env`: `GOOGLE_OAUTH_CLIENT_ID=PREFIX.apps.googleusercontent.com`. Do not add a client secret. The app does not read account passwords from this file. During authorized testing, credentials may be entered directly into the provider's browser page; never log them or include them in reports.
5. The build derives `com.googleusercontent.apps.PREFIX:/oauth2redirect` and registers its scheme with AppAuth. Rebuild and reinstall after changing the client ID. Do not manually use the package-name scheme in place of this client-derived scheme.
6. Keep `https://www.googleapis.com/auth/drive.readonly` declared on the consent configuration. The app asks for it plus offline access through the authorization parameter, without additional Google profile/email scopes. Excel filtering does not reduce the scope's account-wide access.
7. Sign in interactively on the emulator. Test consent, cancellation, folder browsing, XLSX and XLSM imports, unchanged/changed rescans, disconnect, process restart, revoked access and actual token expiry. Never paste callback URLs or tokens into logs/reports.

## Microsoft owner setup

The prototype reuses the existing `.env` public `Application_ID` and exact `Redirect_URL`. `Directory_ID` is not used: this app uses the personal-account `consumers` authority.

1. In Microsoft Entra App registrations, select the existing registration and confirm it supports personal Microsoft accounts.
2. Under Authentication, verify that the exact `Redirect_URL` is registered as a native/mobile public-client redirect, not a Web or SPA redirect. Preserve the working Android URI, including its encoded signature hash. The manifest decodes only the path for Android matching; the OAuth request uses the original registered string.
3. Retain delegated Graph `Files.Read` and `User.Read`. The prototype also requests `offline_access` to renew tokens. No client secret or implicit grant is needed. Do not enable unrelated password/device-code flows as a troubleshooting shortcut.
4. Sign in through the browser, then browse and download an existing workbook. Browser cookies can allow account reuse, but an old MSAL session is not imported. If the provider rejects the redirect or public-client setup, record the sanitized error code and verify registration before changing settings.

## Local checks (PowerShell from repository root)

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --console=plain
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5554 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb -s emulator-5554 shell am instrument -w -e class com.bookcovermatcher.online.MsalDependencyDeviceTest com.bookcovermatcher.test/androidx.test.runner.AndroidJUnitRunner
# Expected failure: confirms that publication approval remains blocked.
.\gradlew.bat :app:verifyAuthPublicationEligibility --console=plain
```

The instrumentation class retains its historical filename but now checks that proprietary auth classes are absent, AppAuth loads, PKCE/state are generated, and the Microsoft redirect resolves. It is not a substitute for live provider authentication.

## Gates before publication

Live sign-in and both workbook workflows; expiry/refresh and revocation; cancellation, stale responses and process recreation; a reviewed decision on encrypted persistent sessions for daily use; final dependency/licence inventory; owner-approved policy text synchronized by the website agent; reachable public policy URLs; Google restricted-scope verification and separate Google Play requirements. Discovery-provider review remains a stable-release blocker; provider configurations are unchanged.

The current prototype intentionally does not claim daily-use persistence or Google approval. A debug APK is a local testing artifact, not an approved release. Prior APK/AAB copies may be stale. Do not upload them as evidence for this implementation.

References: [AppAuth](https://github.com/openid/AppAuth-Android), [Google AppAuth redirect example](https://github.com/openid/AppAuth-Android/blob/master/app/README-Google.md), [Google custom-URI restrictions and opt-in](https://developers.googleblog.com/improving-user-safety-in-oauth-flows-through-new-oauth-custom-uri-scheme-restrictions/), [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [Microsoft authorization code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow).
