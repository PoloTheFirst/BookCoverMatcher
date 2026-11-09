## v2.6.0 (2026-11-09)

# BookCoverMatcher 2.6.0: Offline Licence Reader, Author Page, OneDrive Folder Access and a Contribution Graph That Draws Each Sprite Once

This is the 2.6.0 stable build (`versionCode 223`). It adds a Markdown reader for the licence documents with a
full-screen reading mode, and an Author section that shows the owner's GitHub avatar, `PoloTheFirst`, `Polo01`
and a bundled snapshot of the `PoloTheFirst/PoloTheFirst` README. It also ships the OneDrive work that had been
in development: workbook import, the rescan version check, and the sign-in repair that finally made real OneDrive
folders list. Everything the offline app already does — scanning, matching, comments, saved workbooks — is
unchanged, and the workbook is always read from the app's own storage: there is no synchronisation, no background
refresh, no upload or write-back, and losing the network can never make a saved workbook unopenable. The last change
folded into this build is a defect in the animated contribution graph: a ghost sprite sat motionless in the top-left
corner because the still scenery kept a copy of every mover, so each sprite was drawn twice — once parked and once
where its own animation put it. The scenery now removes whole elements, outermost span first, and the corner is empty
in every frame.

**This entry documents the published 2.6.0 build.** The ghost fix above was prepared as its own patch release and is
instead the final test of this one: the release is a single artefact, `versionCode 223`, tagged `v2.6.0`. Two stable
gates remain open and are recorded under *Known limitations* rather than hidden — the release is still signed with a
local debug key, and `com.microsoft.device.display:display-mask:0.3.0` is still licence-unresolved.

### Added

- **A Markdown reader for the licence documents.** The Licence page now renders what the APK ships instead of one
  block of plain text: `Markdown` parses headings, paragraphs and hard breaks, bold/italic/strikethrough, inline
  and fenced code, ordered, unordered and nested lists, links, blockquotes, tables and horizontal rules, and
  `MarkdownDocument` draws them in the app's own palette. The GNU GPL is *plain text*, not Markdown, and still
  displays correctly: a test parses the shipped `LICENSE` and asserts that no line's words are lost. Embedded HTML
  is never executed — a run of badge `<img>` tags becomes one line naming what was not displayed. **No dependency
  was added**: the reader is project code, so there is no new licence to audit.
- **Full-screen reading.** The reader opens at dialog size and can switch to a full-window view that maximises the
  text column, with an always-visible *Exit full screen* control. Back leaves full screen first and only then
  closes the reader. The chosen document and the scroll position live above the mode switch (`rememberSaveable`
  plus one hoisted `ScrollState`), so switching modes neither reloads the document nor jumps back to the top, and
  both survive rotation.
- **An Author section under the Licence page.** It shows the GitHub username `PoloTheFirst`, the nickname `Polo01`
  as its own labelled field, the profile picture and the profile address. Nothing is fetched: the picture and the
  README are packaged in the APK, so the section is complete with no network.
- **The profile README, bundled and rendered as Markdown.** A snapshot of the `PoloTheFirst/PoloTheFirst`
  `README.md` (5,038 bytes, retrieved 2026-11-09 from `raw.githubusercontent.com`) is rendered with the same reader
  and expands in place. Its multi-line HTML comment is stripped and its badge images are reported by alt text
  instead of being executed.
- **A working `Visit GitHub Profile` button.** It opens `https://github.com/PoloTheFirst` through the app's
  existing HTTPS-only external-link handler; a device with no browser gets a message, not a crash.
- **A safe OneDrive trace** (`RemoteDiagnostics`), because every earlier diagnosis of "folders do not open" had to
  be inferred: the app logged nothing at all. One line per operation records the stage (`signIn`, `token`,
  `list`, `probe`, `download`), the HTTP status, Graph's own `error.code`, Microsoft's `request-id` and the
  classified failure. The endpoint path is templated (`/items/{id}/children`) and the query string — which
  carries `$select`, page tokens and, on the content endpoint, a pre-authenticated storage URL — is dropped
  entirely. No header, token, body, account, folder or workbook name is ever recorded, the error body is read
  only up to 8 KiB and only for `code`/`request-id`, and a test asserts that a URL with a `tempauth` query logs
  nothing of it. The trace goes to logcat under the tag `OneDrive`.
- **Import from OneDrive** (`More → Saved workbooks → From OneDrive`). Sign in with a personal Microsoft
  account (public client, MSAL, `consumers` authority, exactly two delegated scopes: `Files.Read` and
  `User.Read`), browse your drive folder by folder with ↑ Up, a breadcrumb path and **Load more** pagination,
  and import a workbook. Only `.xlsx`/`.xlsm` are listed; folders are always shown. The download shows
  determinate progress when OneDrive reports a size and an indeterminate bar ("size unknown") when it does
  not, can be cancelled, and ends in *Use it now* / *Done*.
- **Remote check on Rescan.** For a workbook imported from OneDrive, **Rescan** still re-parses the local copy
  and additionally probes OneDrive once. The result is one of: *Up to date with OneDrive.*, a newer version
  (see below), *Could not tell whether the OneDrive copy changed…* when the metadata is not comparable, the
  agreed "no longer in OneDrive" sentence on a genuine `NOT_FOUND`, or the matching
  offline/timeout/throttled/auth sentence. A workbook that came from a file keeps the old behaviour exactly.
- **Confirmed, validated replacement.** A newer version is offered with the agreed wording ("A newer version
  is on OneDrive (<size>, <date>). Update the saved copy? The current copy stays until the new one is
  verified."). **Update** downloads to a temporary `.part`, validates it as a real OOXML workbook, swaps it in
  atomically, records the new version and re-opens the workbook under its **new** SHA-256. **Keep current**
  changes nothing but the check time. **Cancel** stops the download and deletes the temporary file.
- **Library schema 2** with optional remote metadata (`itemId`, `path`, `etag`, `ctag`, `modified`, `bytes`,
  `synced`, `checked`). A schema-1 library — including one with no `schema` key at all — is migrated in memory
  and rewritten once, keeping ids, names, files, pins, recency and parsed counts.
- **`ConnectivityMonitor`** (`core/net`) as the single answer to "may a remote operation start?", with a pure
  `UsableNetwork` rule (validated default network with `INTERNET`) and an injectable fake.
- **`SafeWorkbookDownload` + `WorkbookDownloadValidator`**: streams to `<itemId>.part`, validates extension,
  ZIP signature and OOXML parts, and deletes the temporary file on **every** failure path.
- **`GraphFailures`**: the HTTP status → `RemoteFailure` mapping (401/403/404/410/429/5xx, `Retry-After`
  parsing that tolerates absence and junk) as a pure, unit-tested object, plus the `ENOSPC`-versus-transport
  distinction.
- **Context-aware error wording** (`RemoteNotices`) and a Retry rule (`RemoteFailure.isRetryable()`): Retry is
  offered for a bad moment (offline, timeout, unstable, throttled, 5xx) and never for a state the user must
  change (sign in again, free space, pick another file, the remote file is gone).

### Changed

- **The build identifies itself as `2.6.0` (`versionCode 223`).** The 2.6.0 checkpoints shipped as `2.5.0 / 221`
  and then `2.6.0-dev.1 / 222`, which made a development build hard to tell apart from the released 2.5.0 app in
  the package metadata and on the More screen. The *THIS BUILD → Version* row now reads `2.6.0`, and `versionCode
  223` is above both earlier codes, so Android accepts this build as an upgrade. Stored data is untouched: the
  workbook library, wishlist, profiles and settings keep the same schema on disk.
- **The Licence page is now a reader plus an author section.** It keeps the summary lines and the single
  "Open-source licences" entry point, and gains the Author panel underneath. Licence documents are no longer
  shown as one plain-text blob; they open in the Markdown reader.
- **Rescan is routed through `MainViewModel.rescanOpenWorkbook()`** instead of calling
  `WorkbookCommentsController.reload()` from the screen. For local workbooks this is the same local reload,
  with no connectivity check and no account.
- **`WorkbookLibrary.writeAll` writes atomically** (temp file + `Files.move(ATOMIC_MOVE)`, with a backup and
  restore fallback) instead of delete-then-rename, closing a data-loss window in the index write.
- **`WorkbookLibrary.replaceFrom` recovers a lone backup before cleaning up**: if the workbook file
  is gone but `<file>.previous` survives, the backup is restored rather than deleted, so a recovery attempt
  can no longer destroy the only copy.
- **The Pacman graph now plays at the display's own refresh rate — 60 fps, measured — instead of 18.** Two things had
  to change. First, sampling: the source emits one Pacman position per 200 ms, which reads as a stutter, so
  `SmilAnimation` honours `calcMode` — the day cells declare `discrete` and stay a step (a cell is never half-eaten),
  while Pacman's `fill`, `translate` and `rotate` declare no mode, SMIL's `linear` default, so an instant *between* two
  keyTimes has a real interpolated value. The loop, the positions and the speed are untouched; the same timeline is
  simply sampled more often. Second, and this was the real limit: `AndroidSVG` cannot animate. Measured on the device,
  one frame of the whole-document path cost **77 ms** — 16.5 ms to parse the 85 KB document and 70.6 ms to rasterise
  it — against the 16.7 ms a 60 Hz display allows, so no scheduler could have gone faster. `SmilTimeline` therefore
  splits the document into the two things it is: **scenery** (canvas, 371 cells, maze walls, month labels), rendered
  once by `AndroidSVG`, and **five movers** whose only continuous property is a translation. `PacmanCompositor` draws a
  frame as one blit, at most twenty rounded rectangles for the cells Pacman has not eaten, four sprite blits and one
  rotated blit — **1.53 ms at the median, 3.06 ms at the 90th percentile** — with the ghost PNGs decoded once and
  Pacman's mouth shapes pre-rasterised once per shape and colour, so nothing is parsed or allocated per frame. Playback
  is driven by `withFrameNanos`, i.e. the compositor's own vsync, instead of a fixed delay, and a tick that would look
  identical draws nothing. Measured with `dumpsys gfxinfo` on the emulator (1080 × 2400, 420 dpi, 60 Hz, host GPU):
  **1,484 frames in 25.02 s = 59.3 fps**, **1.28 % janky**, and zero frames in 15 s while the graph is off screen.


- **The OpenCLIP weights row in `README.md`** now states their declared licence (MIT,
  `cardData.license = "mit"`) instead of "no declared licence", matching `THIRD_PARTY_NOTICES.md` and the
  LAION model card.

### Fixed

- **No OneDrive folder could ever be listed, on any device, because the silent token request was built in a way
  MSAL rejects.** `accessToken()` called the *synchronous* `acquireTokenSilent(parameters)` while attaching a
  `SilentAuthenticationCallback`, and never supplied an authority. MSAL 8.6.0's `acquireTokenSilentInternal`
  opens with `if (getCallback() != null) throw new IllegalArgumentException("Do not provide callback for
  synchronous methods")`, then installs its own callback and calls
  `validateAcquireTokenSilentParameters`, which requires a non-null authority as well — and `forAccount(...)`
  does not set one. Either way the call threw, so a Graph access token was never obtained: sign-in appeared to
  work and every folder listing failed afterwards. Verified end to end against a real Microsoft account: the
  request was reduced to `.forAccount(account).fromAuthority(account.authority).withScopes(scopes)`, and the
  root, child-folder and content requests then answered **200**.
- **An unclassified sign-in failure was reported as a lost connection.** `AuthFailure.UNKNOWN` mapped to
  `RemoteFailure.Unstable`, whose sentence is *"try again when you are back online"*. MSAL's
  `IllegalArgumentException` from the bug above therefore reached the user as a network problem while the device
  was online — the misleading message this project has been chasing since crash-fix-4. An unattributed failure
  now has its own sentence (`RemoteFailure.AuthUnexpected`), which names neither the network nor Microsoft.
- **A sign-in whose browser never came back left the panel spinning forever.** MSAL answers through
  `BrowserTabActivity`; when the browser never redirects — a redirect-URI mismatch on the app registration, an
  error page, a tab the user simply left open — no callback is ever invoked, and the panel waited for one
  indefinitely with a disabled button and no message. That reads from the outside as "OneDrive folders do not
  open". The wait is now bounded (`MsalOneDriveAuth.INTERACTIVE_SIGN_IN_WINDOW_MS`, five minutes) and reports
  *"The OneDrive sign-in did not finish, so no folders were loaded. Sign in again; if the browser showed an
  error instead of returning to the app, the Azure app registration for this build needs checking."* Closing
  the panel cancels the wait immediately, so it is a backstop rather than the way out.
- **A failed OneDrive sign-in was reported as a lost connection.** Everything MSAL raised that was not a
  `MsalUiRequiredException` or an `IOException` was collapsed into `UNKNOWN`, and `UNKNOWN` renders as *"The
  connection to OneDrive failed, so nothing was saved. Try again when you are back online."* — so a cancelled
  page, a declined permission, an unregistered redirect or a rejected client all looked exactly like being
  offline. MSAL's exceptions are now classified through the pure, unit-tested `MsalFailureRules`
  (`MsalUserCancelException` → cancelled and deliberately silent, `MsalDeclinedScopeException` → a permission
  decision, `MsalClientException`/`MsalServiceException` → a rejected sign-in, with
  `DEVICE_NETWORK_NOT_AVAILABLE` the only client code treated as connectivity). A body the app cannot read, a
  3xx with no `Location`, and an unexpected exception during a transfer are no longer called "connection"
  failures either; they are `UnexpectedResponse`. Only `Offline` and `Unstable` now mention the network, and a
  test enforces that. A failed sign-in is also no longer invisible: `signIn` used to flatten every failure to
  "signed out", which the panel treats as cancellation, and the reason now travels back as `AuthState.Failed`
  with its own sentence and no misleading Retry.
- **Tapping Sign in with no network opened a browser onto an unreachable page.** The interactive sign-in had no
  connectivity preflight of its own, unlike the panel that hosts it: the app launched a Custom Tab at
  `login.microsoftonline.com`, MSAL could never deliver a callback, and the panel stayed in its signing-in
  state. The same preflight now runs before MSAL is started. Verified on the emulator with airplane mode on:
  the browser no longer opens, the offline sentence appears, and the app stays in the foreground.
- **Microsoft sign-in left the browser on a blank page instead of returning to the app.** The
  `com.microsoft.identity.client.BrowserTabActivity` intent filter declared the signature hash exactly as the
  Azure portal prints it, percent-encoded (`…%2B…%3D`), but Android compares a filter's literal `android:path`
  with the **decoded** `Uri.getPath()`. `msauth://com.bookcovermatcher/<hash>` therefore matched no activity:
  Chrome had nothing to hand the response to, showed a blank page, and the app never resumed — even though
  sign-in and consent had both succeeded. The path is now percent-decoded when the manifest placeholder is
  generated (`redirectParts`/`decodeManifestPath` in `app/build.gradle.kts`); the `redirect_uri` handed to MSAL
  is unchanged, so MSAL still receives exactly the redirect Azure registered.
  `MsalRedirectPath` (`core/remote`) is the single written rule, pinned by `MsalRedirectPathTest` (9 tests),
  including a test that a base64 `+` survives decoding instead of becoming a space. Verified on the emulator:
  before the change, `am start` with Azure's own encoded redirect resolved **no** activity; after it, the same
  intent resolves `com.microsoft.identity.client.BrowserTabActivity`, and the decoded form resolves too.
- **`@odata.nextLink` was appended to a built URL** instead of replacing it, producing a malformed request; a
  page token now replaces the URL and is validated as a trusted Graph API URL before use.
- **`acceptRemoteUpdate` could never run**: it was guarded by `canRescan`, which is false while the update
  question is on screen. It now checks only whether a probe or a download is in flight.
- **A cancelled check could leave the panel spinning**: every OneDrive job now settles its in-flight flags in
  a `finally`, guarded by a per-flow sequence number so a superseded job cannot clear a newer one's spinner.
- **The import panel offered Retry for failures retrying cannot fix** (a file that is not a workbook, a full
  device, a deleted remote file); Retry now follows `isRetryable()`.
- **`com.microsoft.identity:common4j` was marked "not separately verified"** in the notices; its POM declares
  MIT and it is now recorded as verified. The `display-mask` row was rewritten with the full evidence trail
  (POM, artifact, Maven Central, publisher feed, candidate repositories) instead of a bare claim.

- **Every picture in the profile README was drawn at the panel's full width, so a Shields.io badge became a
  screen-wide band.** `MarkdownDocument` sized every image with `fillMaxWidth()` plus `aspectRatio(...)`, which suits
  the banner and the contribution graph and not a 100×20 badge: thirteen of them were each about 186 dp tall and
  owned a row apiece. One rule now decides the box (`AuthorImageSizing`): a badge is drawn at a fixed 20 dp height,
  as wide as its own aspect ratio needs and never wider than 60 % of the row, while the banner and the graph keep
  the full width; consecutive badges are grouped into a wrapping row (`MdImageGrouping`) instead of one row each.
  Measured on the emulator, the badge block fell from roughly 2 400 px to three rows of 56 px, and the contribution
  graph moved from far below the fold to a few hundred pixels under the heading.
- **`:computer:` in the README heading was displayed literally instead of as `💻`.** The inline parser now expands
  GitHub emoji shortcodes (`EmojiShortcodes`) for prose only: a recognised name becomes its emoji, an unrecognised
  one is preserved as written, and shortcode-looking text inside inline code, a fenced block or a link target is
  never touched. No dependency was added; the mapping is a small curated table.
- **The Pacman contribution graph was frozen in its first frame.** The source is an SMIL-animated SVG — 54
  `<animate>` elements and six `<animateTransform>` elements over a declared `dur` of 29 400 ms at 5 frames per
  second — and `AndroidSVG` renders only static geometry, so a successful parse proved nothing about animation.
  `SmilAnimation` samples the document's own timeline and rewrites the animated attributes into a still document
  for the requested instant, which the existing renderer then draws; the picture is replaced at the document's own
  frame rate, and only while the README is expanded and the app is RESUMED. The graph now visibly moves while the
  banner, the badges and the text around it stay pixel-identical.


- **Every Shields.io badge drew its background and its text but left the technology icon blank.** The icon is a
  nested SVG document carried in a `data:` URI —
  `<image x="5" y="3" width="14" height="14" href="data:image/svg+xml;base64,…"/>` — and `AndroidSVG 1.4` renders
  that as nothing for two independent reasons: it reads SVG 1.1's `xlink:href`, not SVG 2's bare `href`, and its
  `<image>` handler decodes with `BitmapFactory`, so an `image/svg+xml` payload yields no bitmap. `SvgEmbeddedImages`
  now converts the document instead of the renderer: an embedded SVG becomes a `<g transform="…">` holding its own
  content, positioned and scaled exactly where the `<image>` would have drawn it (`x`, `y`, `width`, `height`,
  `viewBox`, `preserveAspectRatio` honoured, the embedded root's `fill`/`stroke` inherited), and a remaining raster
  `data:` reference has its attribute rewritten to `xlink:href` (the Bash badge's icon is a PNG). Script, title,
  desc, metadata and style elements inside an embedded icon are dropped, payloads are capped at 256 KB and 48 per
  document, external references are never touched, and nothing is fetched or executed. Measured on the emulator: the
  Java badge's icon region held **0** non-background pixels before and **478** after, with the badge's size, text and
  background unchanged.
- **A picture was drawn 1:1 and cropped whenever it had to be scaled, because these SVG documents declare no
  `viewBox`.** `AndroidSVG` does not scale a document that specifies only `width`/`height`: setting a different
  document size enlarges the viewport but leaves the content at its own coordinates, so the right-hand and bottom
  edges were cut off. The contribution graph — 1166×184 decoded at 0.86× to 1000×157 — lost its last columns and its
  bottom rows that way. The renderer now scales the canvas explicitly, so the whole picture fits the bitmap. The
  graph's day cells were measured spanning the full width of its box afterwards (x 77–1002 of the box).
- **The Pacman graph appeared static in normal use, because playback was tied to the panel being expanded.** A
  reader who scrolled the *collapsed* README down to the graph saw a frozen picture: `animateImages` was passed
  `expanded`, so the frame loop never started in that state. Playback is now governed by whether the picture is on
  screen at all — it plays whenever the Author README is composed (the *More* tab) and the app is RESUMED, and is
  cancelled with the composition. Verified on the emulator with the panel collapsed: Pacman's horizontal position
  advanced 109 → 180 → 320 → 461 px across four captures, where the same state previously showed no change at all.
  Frames are also rendered at the size the picture occupies rather than at a fixed 1000 px, and a frame that cannot
  be drawn now logs `AuthorPage` instead of failing silently.


- **Shields.io icons looked soft because every vector was rasterised at no more than its intrinsic size.** A badge is
  53×20 *units*; drawn at its 20 dp height on a 2.625× screen it occupies 139×52 px, but it was decoded at 53×20 px
  and then enlarged 2.6× by the compositor — a small bitmap stretched, which is exactly what "blurred" looks like.
  `AuthorImageDecoder.decode` now takes the display density and rasterises **vectors** at the pixels they will occupy
  (capped at 4× and by the existing pixel budget); rasters are still only ever subsampled, so a photograph is never
  enlarged. Measured on the emulator's Java badge icon: the antialiasing band fell from **525 to 212** pixels, the
  distinct shades from **637 to 350**, and solid icon-ink pixels rose from **37 to 105**, while the badge's box and
  its 20 dp height were unchanged.
- **The contribution graph was a white panel in a dark page.** The README's `<picture>` offers two variants and the
  parser always took the `<img>` fallback, which is the *light* graph. It now takes the `prefers-color-scheme: dark`
  `<source>` when a picture has one, so the graph carries the generator's own dark theme — `#0d1117` canvas,
  `#161b22` empty cells, `#8b949e` month/weekday labels, the dark `#0e4429`… ramp — instead of a recolour. On the
  emulator 91.3 % of the graph's band is now dark pixels (canvas sampled at `#0D1117`, cells at `#161B22`, labels
  light), in the app's normal dark surface and with no light-theme frame at any instant.
- **The contribution graph's dark canvas stopped short of October and November, and an empty band under the last
  row looked like an extra heat-map row.** Both are one defect. The document declares `width="1166" height="184"` and
  **no `viewBox`**, and its background is `<rect width="100%" height="100%" fill="#0d1117"/>`: without a `viewBox`,
  `100%` is resolved against the *viewport*, which the decoder shrinks to 1000×157, while the content keeps its own
  1166×184 coordinates. The background therefore painted 1000×157 of a 1166×184 canvas — the last 166 units across
  (the newest weeks, whose month labels are `Oct` at x=1044 and `Nov` at x=1132) and the bottom 27 units were never
  painted at all, so the page's navy panel showed through exactly there. `SvgCanvas.normalise` now gives such a
  document the missing `viewBox="0 0 width height"` and trims a canvas taller than its content to the drawing's own
  bottom (the grid ends at y=167, so the box is `0 0 1166 168`; the declared height is rewritten with it, or the
  shorter box would be letterboxed back into 16 empty pixels). The decoder no longer scales the canvas as well when a
  `viewBox` is present, since the renderer now maps the box itself. Measured on the emulator, in the gap rows between
  cells: before, the two right-hand 100 px bands held **0–2** canvas pixels and **5–10** panel-navy pixels each; after,
  every band holds canvas pixels and **0** navy (`x 890–989: 98/0`), with no transparent pixel anywhere in the
  picture. The picture is also 168 units tall instead of 184, so the strip below the last row is gone while all
  **371** cells (53 columns × 7 weekday rows) and all seven rows remain — the count is asserted on the real asset.


- **A ghost sat stuck in the top-left corner of the contribution graph, drawn twice: once parked and once where it
  belonged.** `SmilTimeline` builds the still scenery behind the compositor by cutting spans out of the document — the
  five movers and every `<animate>` element. A mover *contains* its own animations, so those spans overlap by
  construction, and the removal helper removed the children first and then treated the parent as a duplicate and
  skipped it. All four `<g id="ghost0..3">` groups therefore survived into the scenery and were rasterised at their
  static `transform="translate(0,0)"` — the top-left of the grid — while `PacmanCompositor` drew the same ghosts at
  their animated positions; the parked copy is the sprite users saw. `strip` now keeps only the outermost spans and
  cuts each element once, children included. That is a general correction to the span arithmetic, not a
  ghost-specific workaround: it applies to Pacman's single `<path>`, to any nested element, and to spans produced by
  any future document, and it removes work rather than adding any (four ghost groups and Pacman are no longer
  rasterised into the scenery). Verified on the emulator: the corner region (x 80–220, y 1630–1700 of the
  1080 × 2400 capture) contains **0** saturated sprite pixels in five successive captures, and the sprite centroids
  move (685, 1697) → (853, 1732) → (963, 1732) → (929, 1732) → (828, 1730) as the animation advances.

### Removed

- None.

### Tests

- Run conditions: each suite was forced with `:app:cleanTestDebugUnitTest`, because Gradle otherwise reports the
  task `UP-TO-DATE` and repeats an old result. Figures are from 2026-11-09, after the sign-in redirect change.

- Full JVM suite: **1,295 tests, 0 failures, 0 errors, 16 skipped, 151 classes**
  (`.\gradlew.bat :app:testDebugUnitTest --no-build-cache`, executed 2026-11-09). The 16 skips are
  environmental `assumeTrue` guards — 4 live-network catalogue tests needing `-DonlineCheck=1`, 3
  real-workbook tests and 9 sample-workbook tests whose fixtures live outside the repository — not disabled
  tests.
- `MarkdownTest` **20 passed**: heading levels, paragraph soft-wrap, an explicit hard break, bold/italic/code/
  strikethrough, link targets, a `javascript:` link kept as text but refused, nested bullet and numbered levels,
  blockquotes, fenced code with a language, tables, horizontal rules, HTML badges reported by alt text, HTML
  wrappers, licence placeholders such as `<one line ...>` staying text, comments stripped, standalone and inline
  images, and unknown markup staying visible. Its `theShippedLicenseLosesNoText` case parses the real `LICENSE` and
  asserts that every line's words survive, which is the legal-accuracy guard for this feature.
- `AuthorProfileTest` **6 passed**: the exact username/nickname distinction, the profile URL, the HTTPS-only link
  rule (http, javascript, intent and file targets refused), link trimming, the avatar description, and that the
  bundled README (5,038 bytes) and avatar (15,848 bytes, real JPEG magic) exist and are not placeholders. Both new
  classes read files from the working directory behind an `assumeTrue` guard, and that guard did not fire, so they
  genuinely executed.
- Instrumented tests: **21 passed, 0 failed, 0 skipped** on `emulator-5554` (`Medium_Phone_API_37.0`, Android 17 /
  API 37) via `:app:compileDebugAndroidTestKotlin` then `:app:connectedDebugAndroidTest`. They cover on-device OCR
  only; nothing instrumented covers the licence reader, the author section or OneDrive. The first attempt reported
  0 tests because the emulator still held a differently-signed copy of the app and test packages; it ran properly
  only after uninstalling both, which is recorded rather than hidden.

- OneDrive/local regression classes added across Phases 1–7, with the number of tests in each:
  `OneDriveUpdateSessionTest` (25), `OneDriveSessionTest` (18), `RemoteFailureNoticesTest` (16),
  `GraphFailuresTest` (15), `WorkbookLibrarySchemaTest` (13), `WorkbookLibraryCrashRecoveryTest` (11),
  `OneDriveOfflineBehaviourTest` (11), `RemoteChangeTest` (11), `OneDriveNavigationTest` (10),
  `WorkbookLibraryReplaceTest` (10), `GraphUrlsTest` (10), `SafeWorkbookDownloadTest` (10),
  `ConnectivityMonitorTest` (10), `WorkbookLibraryTest` (9), `RemoteFailureMessageTest` (8), `GraphJsonTest`
  (8), `RemoteBrowsingTest` (8), `MsalConfigTest` (8), `WorkbookDownloadValidatorTest` (7),
  `OneDriveIntegrationInvariantsTest` (7), `OneDriveUiStateTest` (7), `RemoteUpdateUiTest` (7),
  `GraphRedirectTest` (5), `GraphWorkbookSourceAuthTest` (5), `OneDriveUseItNowTest` (4),
  `RemoteWorkbookFilterTest` (4), `LocalWorkbookOperationsTest` (3).
- The sign-in redirect rule added in the previous checkpoint: `MsalRedirectPathTest` **9 passed**, covering the
  percent-decoded intent-filter path, `+` not being turned into a space, MSAL's derived
  `msal<client-id>://auth` form, malformed and multi-byte escapes, and the packaged redirect end to end.
- The sign-in failure rules added in the previous checkpoint: `MsalFailureRulesTest` **8 passed**, covering
  cancellation, declined consent, a rejected client and service, the one MSAL client code that really is a
  network failure, and that a failed sign-in is reported while a cancelled one stays silent;
  `GraphWorkbookSourceAuthTest` **8 passed**, including that a rejected sign-in never becomes a connection
  failure and that only a genuine network failure still says "offline".
- The trace added in crash-fix-5: `RemoteDiagnosticsTest` **10 passed**, covering query stripping, the `{id}` placeholder,
  a pre-authenticated storage URL losing its `tempauth` value, unusable URLs, the line's fields, control-character
  injection and a sink that throws.
  consent, a rejected client and service, the one MSAL client code that really is a network failure, and that a
  failed sign-in is reported while a cancelled one stays silent; `GraphWorkbookSourceAuthTest` **8 passed**,
  including that a rejected sign-in never becomes a connection failure and that only a genuine network failure
  still says "offline".
- Instrumented tests: **compiled and executed** on `emulator-5554` (`Medium_Phone_API_37.0`, Android 17 /
  API 37, `sdk_gphone64_x86_64`), re-run on 2026-11-09 after the redirect change: `:app:compileDebugAndroidTestKotlin`
  then `:app:connectedDebugAndroidTest` → **21 tests, 0 failures, 0 errors, 0 skipped** across
  `NativeOcrDeviceTest` (10), `OcrDeviceTest` (3) and `OcrEngineRoutingTest` (8). These cover the on-device OCR
  paths only; there is no instrumented test for the OneDrive UI or for the sign-in callback.

- `SmilAnimationTest` (7 tests) against the real 85 444-byte Pacman graph: the timeline is read from the document
  (29 400 ms, 5 fps, 147 frames, ≥ 50 animated elements), a still SVG and an animation with no `dur` are refused, a
  frame carries no `<animate>`/`animateTransform`, three instants of the loop differ, Pacman's transform both moves
- `SvgEmbeddedImagesTest` (11 JVM tests) against the four real badge documents: the Shields badge really does carry
  its icon as a nested SVG in a data URI; the Java icon becomes `<g transform="translate(5,3) scale(0.58333)">` with
  its geometry and inherited colour; Java, Kotlin and Git all keep an icon *and* their own text; the Bash PNG icon is
  rewritten to `xlink:href` with the namespace declared; a document with no embedded image is returned unchanged; the
  clip path, gradient wash and scaled text group survive; a script inside an embedded icon does not survive; a
  malformed or oversized payload and an external reference are left untouched; and an embedded SVG without geometry
  is refused rather than guessed at.
- `AuthorRenderTest` (5 **instrumented** tests, `emulator-5554`): rasterises the real documents and counts pixels —
  the unprepared Java badge leaves the icon box empty (**0** white pixels) and the prepared one draws it (**>100**);
  Java, Kotlin and Git each draw an icon; two frames half a loop apart differ by **>1 000** pixels; two *consecutive*
  frames differ too; and a prepared badge fills the bitmap it was asked for instead of being drawn 1:1 and cropped.
  These are the assertions that separate "the document parses" from "the picture is drawn".
- `:app:testDebugUnitTest` — **1 369 test cases in 160 classes, 0 failures**.
- `:app:connectedDebugAndroidTest -P…class=com.bookcovermatcher.author.AuthorRenderTest` — **5 tests, 0 failures**.


  and turns, the eaten-cell count grows across the loop, and `<rect>`/`<use>`/`<path>` counts survive the rewrite.
- `EmojiShortcodeTest` (8 tests): `:computer:` → `💻` both directly and through `Markdown.parse`, other shortcodes,
  unknown names preserved verbatim, shortcode text inside inline code and a fenced block left alone, and ordinary
  colon text, a Windows path and a URL untouched.
- `AuthorImageSizingTest` (7 tests): a badge cannot exceed its configured height or 60 % of the row even when it
  claims a 500:1 ratio, it keeps its own aspect ratio, the banner and the graph keep the full width, and zero,
  negative, NaN, infinite and very tall ratios all produce a usable bounded box.
- `MdImageGroupingTest` (5 tests): consecutive badges form one row, the banner and the graph never join it, and no
  block is dropped or reordered.
- `:app:testDebugUnitTest` — the whole JVM suite, including every pre-existing test — **BUILD SUCCESSFUL, 0
  failures**.


- `SmilAnimationTest` grew to **12 tests**, four of them about timing: a quarter/half/three-quarters sample of a
  `linear` animation reads 25/50/75 of the way (so intermediate instants are real, not rounded), a `discrete`
  animation stays a step at 40 % and 60 % (a cell is never half-eaten), the real graph differs 40 ms apart *inside*
  one of the generator's own frames, and the page's 18 fps is asserted to sit inside the requested 15–20 band while
  the document's own 5 fps is still read correctly.
- `MarkdownTest` and `AuthorContentTest` were updated for the new `<picture>` rule (`aPictureElementYieldsItsDark
  SourceOnceAndNotBothSources`, `picturesInsidePictureElementsResolveToTheirDarkSource`) and a companion test asserts
  that a picture with only a light source still falls back to its `<img>`.
- `AuthorRenderTest` grew to **9 instrumented tests**, three of them new: the badge is rasterised at least as large
  as the 139×52 px it is drawn at (the production decoder, at density 2.625, not a copy of its arithmetic), the
  density-rendered icon has a narrower antialiasing band than the same bitmap enlarged, and the dark graph is dark
  with legible labels at three instants — including the first frame, so there is no white flash.
- `:app:testDebugUnitTest` — **1 374 test cases in 160 classes, 0 failures**; `:app:connectedDebugAndroidTest`
  (this class) — **9 tests, 0 failures**.


- **11 new JVM tests in `SmilTimelineTest`** (the ghost fix above), run against the project's real dark graph asset
  rather than a simplified fixture: the source declares four ghost groups, one Pacman path and six transform
  animations; the scenery contains no mover and no animation at all (the regression test for this defect — it failed
  before the fix with *"ghost0 must not survive into the scenery"* and passes after); the scenery keeps all 371
  cells, 26 labels, the dark canvas and the `0 0 1166 168` box; every ghost is away from the top-left at five sampled
  instants; the four ghosts trace different positions and do not stack; Pacman moves, turns and chomps; each
  animation is sampled against its own duration (the 0.5 s mouth cycle repeats every 500 ms and differs half-way
  through); the 20 flashing cells step once each; a short single-ghost document yields a scene whose scenery still
  has no mover; empty, still and malformed documents fail safely.
- **One new instrumented test in `PacmanCompositorTest`** — `noSpriteIsParkedInTheTopLeftCorner` — renders the real
  frame through the production compositor at six instants and requires zero strongly saturated pixels in the corner
  region, which is the pixel-level signature of a parked sprite.
- **Final counts for this build:** `:app:testDebugUnitTest` **1,396 tests, 0 failures, 16 skipped, 0 errors**
  (1,385 before the ghost fix, plus the 11 new tests above); `:app:connectedDebugAndroidTest` **40 tests,
  0 failures** (`PacmanCompositorTest` 6, `AuthorRenderTest` 12, `PacmanFrameCostTest` 1, `NativeOcrDeviceTest` 10,
  `OcrDeviceTest` 3, `OcrEngineRoutingTest` 8).

### Verified

- **Real Microsoft account, real Graph, on the emulator** — the first time OneDrive folder access has been
  observed working. With a dedicated test account signed in through MSAL's own browser flow: the root children
  request answered **200** (two folders rendered), a folder opened (`/items/{id}/children` **200**, the file
  inside rendered with its size and modified time), *Up* returned to the root, an empty folder reported *"This
  folder has no .xlsx or .xlsm workbooks"* rather than an error, a 38.6 MB workbook downloaded
  (`/items/{id}/content` **200**) and was saved to the library, and the saved copy opened **with airplane mode on**
  with **zero** OneDrive log lines — the offline-first rule intact. Every Graph line was
  `status=200 … requestId=<guid>`; no token, account or file name was logged.
- `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin` and `:app:assembleDebug` succeed.
- **Sign-in redirect handoff, verified on the emulator** (the fix in *Fixed*): the installed
  `BrowserTabActivity` filter now registers `Path: "PatternMatcher{LITERAL: /<signature-hash>}"` in its decoded
  form (base64 `+` and `=` present, no `%2B`/`%3D`), and `am start -a android.intent.action.VIEW -c
  android.intent.category.BROWSABLE -d "msauth://com.bookcovermatcher/<signature-hash>"` resolves
  `com.microsoft.identity.client.BrowserTabActivity` both for Azure's percent-encoded form and for the decoded
  form. Against the pre-fix build the same two intents failed with
  `Error: Activity not started, unable to resolve Intent` — which is exactly the browser side of the blank page
  the user saw. A double-encoded control URI resolved on the old build and no longer resolves on the new one,
  confirming the filter's literal is now the decoded path.
- **The same OneDrive chain re-run on this 2.6.0 build** (`versionCode 223`): sign-in completed, `/me/drive/root/children`
  **200**, the folder opened (`/items/{id}/children` **200**), a 38.6 MB workbook downloaded (`/items/{id}/content`
  **200**) and saved, and the saved copy opened with airplane mode on, producing **zero** OneDrive log lines.
- **Licence reader, on the emulator** (`emulator-5554`, Android 17 / API 37): the GPL and the third-party notices
  render as structured text — headings, joined paragraphs, and one of the notices' tables — and both documents still
  render with airplane mode enabled. Switching to full screen keeps the document *and* the reading position (the
  view stayed in the section it was left on), *Exit full screen* is always present, and Back leaves full screen
  first and closes the reader only on a second press. That Back behaviour was a defect found on the device and fixed
  before the final build, not a description of the first one.
- **Author section, on the emulator**: the bundled avatar decoded (the placeholder branch was not taken),
  `PoloTheFirst` and `Polo01` appear in two labelled fields, the bundled README rendered (its heading, the banner
  image row, and a line naming the badges that were not displayed), and *Visit GitHub Profile* opened Chrome — the
  running task's intent carried exactly `dat=https://github.com/PoloTheFirst`.
- **Packaging**: inside the built APK, `assets/author/README.md` (5,038 bytes), `assets/author/avatar.jpg`
  (15,848 bytes), `assets/licenses/GPL-3.0.txt` (35,149 bytes) and `assets/licenses/THIRD_PARTY_NOTICES.md`
  (17,736 bytes) are present; this was read from the archive, not assumed.
- **Release artefact**: `:app:assembleRelease` succeeds and produces a `223 / 2.6.0` APK, but `apksigner` reports it
  signed with `CN=Android Debug`. It is therefore installable and **not** a production release (see *Known
  limitations*).

- Debug APK (this build): `app/build/outputs/apk/debug/app-debug.apk`, **340,818,486 bytes**, sha256
  `64ac24564f90df0113a14cf58320c3381ca7f91ab89e30ded2f9e94cc7d4fad7`, `versionCode 223`,
  `versionName 2.6.0`, containing 28 native libraries across `arm64-v8a` and `x86_64` and 242 MB of assets (OCR and
  OpenCLIP models) — read from inside the archive, not assumed. This is the artefact every device check below ran
  on, including the one after the full-screen Back defect was fixed; the emulator's package manager reports
  `versionName=2.6.0` for it and the in-app *Version* row reads `2.6.0`.
- **The request the app builds is accepted by the live service.** With a deliberately invalid bearer token, the
  exact root-children URL (`…/v1.0/me/drive/root/children?$select=…&$top=200`, the same `$select` and `$top`
  the app sends) answered **HTTP 401 `InvalidAuthenticationToken`**, not a 400: the endpoint, the query and the
  property names are valid at Microsoft's end. `GET /me/drive/root/children` and every `$select` field used here
  are also documented for this exact call, and `Files.Read` is the least-privileged delegated permission for a
  personal account.
- **Offline sign-in, verified on the emulator** (the fix in *Fixed*): with airplane mode on and the OneDrive
  panel open, tapping **Sign in** on the previous build launched Chrome at `login.microsoftonline.com`
  (`ERR_INTERNET_DISCONNECTED`) and the app never received a callback; on this build the browser does not open,
  `MainActivity` stays the resumed activity, and the panel shows *"No internet connection. OneDrive workbooks
  can only be imported online…"*.
- APK inspection: 733 entries, **no** `.env`; the packaged `res/raw/msal_config_default.json` carries
  `client_id` and **no** `client_secret`, and the `.env` `Directory_ID` value does not occur anywhere in the
  APK. Permissions: `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `NFC` and the generated
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `DisplayMask` is present in `classes18.dex`/`classes19.dex`, so
  the unresolved artifact really is shipped.
- Signing state: the APK is signed with this machine's regenerated debug key (`CN=Android Debug`, SHA-1
  `ef2fb053…b611`, SHA-256 `169876f8…ce24`), and the MSAL redirect in `.env` carries the matching signature hash
  (the decoded form of that certificate's digest), i.e. the redirect agrees with the key actually in use. The
  `release` build type still uses `signingConfigs.getByName("debug")`.
- `python tools/check-licenses.py --refs --history` → **licensing audit passed**: canonical `LICENSE`
  (35,149 B, sha256 `3972dc9744f6…`), 378 project-owned sources carrying SPDX headers, every tag and every
  reachable commit clean.
- Device: the debug APK installs on `emulator-5554` (`Medium_Phone_API_37.0`, Android 17 / API 37,
  `sdk_gphone64_x86_64`), launches (`com.bookcovermatcher.MainActivity` becomes the resumed activity), renders
  Home and More, and does the same with airplane mode enabled; `logcat` contains no `FATAL EXCEPTION` and no
  `ANR in com.bookcovermatcher`. The in-app "THIS BUILD → Version" row reads `2.6.0`.
- **Still not verified:** a folder *inside* a folder, a listing long enough to paginate, a declined or failed
  consent, an expired token being refreshed mid-session, a real `Retry-After` from Graph, and an update check
  interrupted by losing the connection.

- **The reported Author-page crash could not be reproduced, and the attached screenshot is not a PNG.** The file
  `BookCoverMatcher-QA/phase9-evidence/author-expanded-1.png` begins `FF FE` — a UTF-16LE byte-order mark — and
  continues `50 00 4E 00 47 00`, i.e. `PNG` with a NUL between every byte; the PNG signature's `0x89` arrived as
  `U+FFFD`, the replacement character a lossy text conversion writes when it meets a byte it cannot decode. Read as
  UTF-16 the payload is a complete 1080×2400 screenshot whose IHDR is intact, so the picture was captured correctly
  and then re-encoded as text. The mechanism was reproduced deliberately: `adb exec-out screencap -p > file.png` in
  PowerShell re-encodes the binary stream as UTF-16LE and produces exactly this corruption. Capturing through the
  device filesystem instead (`adb shell screencap -p /sdcard/x.png` then `adb pull`) yields a valid PNG at the same
  resolution, which is how every screenshot below was taken. The image-ingestion failure was therefore the evidence
  file, not the app.
- **The Author page, exercised on `emulator-5554` (Android 17 / API 37, 1080×2400 at 420 dpi) with the debug build
  installed from `:app:assembleDebug`:** launched, opened *More*, scrolled to *Author*, expanded the README, scrolled
  it, collapsed and expanded it five times, opened the licence reader, switched it to full screen, and closed both.
  No crash, no ANR, nothing in `adb logcat -b crash` and no `E/` line from the app; `TOTAL PSS` 325 691 KB with the
  10 MB banner and thirteen decoded badges resident.
- **The banner renders the real asset.** The README's `github.com/…/blob/main/assets/2_PC-A.png` is translated to
  `raw.githubusercontent.com` and decoded at a subsample of 4 (960×540); the on-screen region has mean brightness
  **198.1** against the source PNG's **205.1**, and the same corner pixel (`255,254,249` against `255,254,248`), so
  what is drawn is the photograph, not a placeholder.
- **The badges are badge-sized.** Measured from the running app: three rows of badges, each **56 px** tall
  (20 dp at 2.625× density), replacing the previous thirteen full-width bands.
- `:computer:` renders as `💻 Tech Stack` in the heading, read back from the device's own view hierarchy.
- **The contribution graph animates.** Two captures 1.4 s apart were compared pixel by pixel: **569 of 36 800**
  samples in the graph's region differ, while the banner (0 of 103 500), the badge rows (0 of 43 700) and the
  heading text (0 of 12 880) are identical — the change is the animation, not the page.
- **Playback is bounded.** With the panel expanded, `dumpsys gfxinfo` records 43–51 frames in 10–12 s (≈4.3 fps,
  the 5 fps the document declares, on a software-rendered emulator with a 38–57 ms frame time); with the panel
  collapsed the same measurement is **0 frames in 10 s**.
- `:app:compileDebugKotlin`, `:app:assembleDebug` and the full `:app:testDebugUnitTest` suite succeed.
- `result_profile.svg` was inspected rather than used: it is a **Vision Cortex VTracer 1.0.0-alpha.4 trace** of a
  537×594 raster, 3 969 947 bytes of 66 621 `<path>` elements with 46 386 distinct hexadecimal fills and **no**
  `<image>`, gradient or animation element. It is a lossy vectorisation of a small screenshot — the source of the
  "somewhat lossy" appearance the user reported — so it is kept as a diagnostic artefact and is not a substitute for
  the README or its assets.


- **Badge icons, measured on `emulator-5554` (Android 17 / API 37, 1080×2400 at 420 dpi) with the debug build from
  `:app:assembleDebug`.** Locating the Java badge by its `#ed8b00` run (x 847–985, rows from y 1392 — exactly where
  it was before this change, so its size did not move), the icon's region (x 860–897, y 1398–1434) held **0 of 1 406**
  pixels differing from the badge background before the fix and **478 of 1 406** after it, with the text region
  unchanged. Captures: `BookCoverMatcher-QA/phase9-evidence/cf2-badges-before.png` and `cf2-badges-after.png`.
- **The contribution graph animates in both panel states.** Collapsed: Pacman's yellow pixels moved **109 → 180 →
  320 → 461 px** over four captures ~0.9 s apart, and the graph region differed in **353–579 of 36 800** sampled
  pixels between captures. Expanded, scrolled to the graph: **127 → 250 → 408**. The same measurement in the
  collapsed state before this change produced no change at all.
- **Pause and resume, verified.** Home then relaunch (the activity was brought to the front, not recreated): Pacman
  resumed at **232 → 373 → 531**. With the *Home* tab selected, `dumpsys gfxinfo` recorded **0 frames in 9 s**; with
  the Author page visible, **40 frames in 9 s** (≈4.4 fps, one loop, after many expand/collapse and background
  cycles — not the ≈20 fps several loops would produce). No new `AuthorPage` frame-failure line appeared, and
  `TOTAL PSS` stayed at 324 442 KB.
- `result_profile.svg` remained untouched and unused; the banner, the emoji heading (`💻 Tech Stack`), the badge
  rows, the README layout and the full-screen reader were unchanged by this fix.


- **Icon sharpness and the dark graph, measured on `emulator-5554` (Android 17 / API 37, 1080×2400 at 420 dpi) with
  the debug build from `:app:assembleDebug`.** Locating the Java badge by its `#ed8b00` run and measuring the icon
  column (the left 34 % of the badge) before and after: solid icon-ink pixels **37 → 105**, antialiasing band
  **525 → 212**, distinct shades **637 → 350**, with the badge at the same place (rows from y 1392) and the same
  height. Captures: `cf2-badges-after.png` (before) and `cf3-badges-after.png` (after).
- **The graph is dark on the device.** Sampling the graph band of `cf3-graph-dark.png`: canvas `#0D1117`, empty cells
  `#161B22`, light labels, and **91.3 %** of the band's pixels dark. The app's cache now holds the **84 944-byte dark
  document** rather than the 85 444-byte light one, so the dark variant is what is fetched and kept.
- **Animation rate, measured rather than asserted:** `dumpsys gfxinfo` after a reset recorded **156 frames in 10 s
  (15.6 fps)** with the graph visible, against **40 frames in 9 s (4.4 fps)** before the change, and the graph band
  differed in 454–805 of 36 800 sampled pixels between captures 0.7 s apart while Pacman's coloured pixels moved
  (span 642→569→863 px). With the *Home* tab selected: **0 frames in 9 s**; returning to the page: **140 frames in
  9 s (15.6 fps)** — pause and resume both work, and one loop is running, not several.
- **No regression:** the heading still reads `💻 Tech Stack`, the banner still renders the photograph (region mean
  205.6 against the source's 205.1), the badge rows keep their 20 dp height and three-row layout, the README renders,
  the refresh line reads *"Saved copy, last refreshed 11/9/26 7:36 PM"*, and no `AuthorPage` frame-failure line
  appeared in logcat.


- **The ghost fix, on the emulator** (`Medium_Phone_API_37.0`, Android 17, 1080 × 2400, 420 dpi, 60 Hz): five
  captures ~900 ms apart, each with **0** saturated sprite pixels in the graph's top-left region, while the sprite
  centroids move — the ghost *moves*, it is not hidden, cropped or covered. Pausing (another tab) still stops the
  animation: **0 frames in 9 s**, and it resumes on return.
- **The composed frame was re-compared with the whole-document renderer** at three instants and now differs by
  **2,225 / 2,072 / 1,530** of 124,888 pixels (was 2,388 / 2,277 / 1,735 before the fix): the duplicate sprite is
  gone, so the two renderers agree more closely, not less.
- **Everything else in the graph is unchanged:** seven contribution rows, 371 cells, dark canvas `#0d1117` under the
  whole width including October and November, no surplus bottom row, Pacman and the remaining ghosts correctly
  placed, and the per-frame cost measured at **3.33 ms** median (6.60 ms p90) against the 16.67 ms budget — the fix
  adds no per-frame work.

### Known limitations
- **Release signing is not production-ready, and this is the primary stable-release blocker.**
  `app/build.gradle.kts` still sets `signingConfig = signingConfigs.getByName("debug")`, so `assembleRelease`
  produces a `223 / 2.6.0` APK signed with the local debug key (`CN=Android Debug`, self-signed, created
  2026-11-08, living in `~/.android/`). It installs and was verified on the emulator, but it is not distributable:
  the key has no ownership or custody story, no backup, and it is the same key that signs debug builds. A real
  release keystore, kept outside the repository, is required before any store or public release.
- **`com.microsoft.device.display:display-mask:0.3.0` is still licence-unresolved — the second blocker.** It is a
  transitive runtime dependency of `com.microsoft.identity:common` and is packaged in the APK; its POM declares no
  licence and no authoritative statement was found in this environment. It has not been relabelled, and it has not
  been excluded, because excluding it has not been shown to be safe.
- **This build is published as stable at the owner's instruction, with both gates above still open.** It is tagged
  `v2.6.0` and delivered to both branches, after the ghost fix above was verified as the final test of the release.
  Publication is a delivery decision, not a statement that the signing key or the `display-mask` licence has been
  resolved: a store-distributable build still needs a real release keystore, and the `display-mask` question still
  needs an answer from the owner. Anyone reusing this binary should treat it as a verified developer release built
  from tag `v2.6.0`, signed with a throw-away key.


- **Nested folder navigation and pagination are still unexercised against a real drive.** The test account's root
  holds two folders; one is empty and the other holds a single workbook with no subfolder, so neither the
  "folder inside a folder" case nor a second page was reachable. The `items/{id}/children` request itself — the
  path a nested folder uses — was verified with a real id, and pagination remains covered only by unit tests with
  recorded payloads.
- **The test account's password appeared in this emulator's accessibility dump** while an automated sign-in typed
  it, because Chrome exposes the field's value to `uiautomator`. Nothing was written to a tracked file and no
  credential is in this changelog, but that account's password should be rotated, and unattended sign-in should
  assume the field is readable by anything that can dump the screen.
- **Licence blocker:** `com.microsoft.device.display:display-mask:0.3.0` — a transitive runtime dependency of
  `com.microsoft.identity:common`, **packaged in the APK** — has no licence declaration in its POM, no licence
  file in its artifact, no Maven Central entry, and is not part of either Microsoft repository that could own
  it. v2.6.0 is therefore **not licence-clear** until the project owner decides (see
  `THIRD_PARTY_NOTICES.md` §1.1). A row-by-row pass over the ~200 shared AndroidX/Compose artifacts is also
  still outstanding; one Compose family was verified as the sample.
- **Signing blocker:** the `release` build type is signed with the **debug** key
  (`signingConfig = signingConfigs.getByName("debug")`), and no release keystore exists in this repository.
  A v2.6.0 binary produced today would therefore be signed with a throw-away debug key that was in fact
  regenerated on 2026-11-08 — it cannot be published or upgraded to. The owner must supply (or authorise the
  creation of) a real release keystore before a stable artifact is built.
- **Azure registration not verifiable here:** the redirect's signature hash matches the current debug
  certificate, but whether that hash is registered on the Azure app registration can only be checked in the
  Azure portal, and a real sign-in was never attempted.
- **Personal Microsoft accounts only**; work/school accounts are unsupported by construction.
- **No background synchronisation, no automatic refresh on open, no upload/write-back, no remote deletion.**
  Retry is manual: `Retry-After` is shown as a sentence, not enforced as a cool-down.
- **`MainViewModel`, the MSAL wrapper and the Compose UI have no executed tests** (the project has no
  Robolectric/Compose-test dependency); they are covered by inspection plus the pure/session-level tests they
  delegate to.
- **Persistence limitations A–E** (documented in `ARCHITECTURE.md` §"OneDrive"): stale metadata after a kill
  between the swap and the index write; an orphaned file cannot be re-imported; a listed entry whose file is
  missing cannot be repaired by re-import; no startup sweep of `.part`/`.previous`; no `fsync`. The recovery
  guard prevents deletion of the only copy but does not resolve these.
- **Sign-in and folder access are verified for one personal Microsoft account only.** Work or school accounts,
  conditional access, MFA-enforced sign-in and passkey enrolment were not exercised — the passkey prompt that
  appeared was dismissed, not completed — and no failure path (a declined consent, an expired token) was driven
  through the live service.
- **The version name is `2.6.0` and `versionCode 223`, and the two are one identity.** The ghost fix that was prepared
  as its own patch release was folded into this single artefact instead, so there is exactly one 2.6.0 binary, one
  `v2.6.0` tag and one changelog entry; the debug signature and the `display-mask` licence remain open questions
  recorded above.
- **A very large workbook** is bounded only by free storage; there is no application-level size limit, and a
  multi-hundred-megabyte import has not been exercised.
- **The contribution graph's animation is re-rendered, not played by an animation engine.** `AndroidSVG` has no SMIL
  support and this app renders no third-party pages in a WebView on purpose, so the still scenery is rasterised once
  and every frame is produced by drawing the movers on top of it at their rewritten positions. That reproduces the
  sampled instants faithfully and costs **3.33 ms per frame at the median (6.60 ms at the 90th percentile)** on a
  software-rendered emulator against a 16.67 ms budget — an earlier design that re-parsed and re-rasterised the whole
  document per frame cost 38–57 ms, which is why the scenery/mover split exists. `calcMode="linear"` colour
  interpolation is rendered as a step, which matches what the generator's paired `keyTimes` produce in practice; a
  document using other SMIL features (motion paths, `keySplines`, `begin`/`end` event timing) is refused rather than
  approximated, and such a picture is shown still.
- **The badge rule is keyed on the Shields.io host**, which is what every badge in this README uses. A badge served
  from another host would be drawn at the full width, as before; the width cap still prevents any picture from
  exceeding the panel.


- **The animation now runs whenever the Author README is on screen, including in the collapsed panel.** That is what
  makes it visible where it used to be frozen, but it also means the 5 fps re-render continues while the *More* tab
  is displayed even if the reader has scrolled the graph out of the small collapsed box. The cost is bounded by the
  composition (leaving the tab or backgrounding the app cancels it: measured **0 frames in 9 s** on the Home tab) and
  by the source's own rate, but it is more work than pausing on true visibility would be; a scroll-aware visibility
  test would need the README's viewport threaded into the renderer.
- **The icon conversion covers the structures these badges use.** Shields.io's `<image>` + SVG/PNG data URI is
  handled generically, but an icon delivered as an external URL, a CSS `background-image`, or a `<use>` reference to
  another document is still not drawn — those are refused rather than fetched.


- **The animation is rendered into the Compose frame loop, so its cost is the frame budget, not a fixed rate.** The
  loop runs at the display's rate and each frame is drawn from a cached scenery bitmap; measured on the emulator it
  costs 3.33 ms at the median and 6.60 ms at the 90th percentile. The work is bounded by the loop being cancelled when
  the picture leaves the composition (measured **0 frames in 9 s** on another tab) rather than by a frame counter, and
  an emulator session that rasterises more slowly than another can move these numbers without the app changing.
- **The dark graph is the generator's dark variant, chosen by media query.** A `<picture>` with only a light source
  still yields the light graph, and a repository that renames its dark output would fall back the same way. The
  alternative — recolouring the light document — was rejected because the light variant carries a white canvas rect
  and dark labels that would have had to be rewritten by string surgery.
- **Icon crispness is bounded by the 4× raster ceiling.** A display denser than 4× would still enlarge the badge
  bitmap; no Android device currently reports that.



**Version code/name: 223 / 2.6.0.**

