// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.AuthorUi
import com.bookcovermatcher.ModelUi
import com.bookcovermatcher.UiState
import com.bookcovermatcher.core.author.AuthorProfile
import com.bookcovermatcher.core.author.AuthorSource
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.core.licensing.LicenseDocuments
import com.bookcovermatcher.core.markdown.Markdown
import com.bookcovermatcher.core.markdown.MdBlock
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.FieldLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.markdown.MarkdownDocument
import com.bookcovermatcher.ui.markdown.MarkdownImage
import com.bookcovermatcher.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * More: settings and information that do not belong to Home, Search or Wishlist.
 *
 * The online settings here are the same preferences the Search panel reads; there is one store and
 * one controller, so the switch and the behaviour cannot drift apart.
 */
@Composable
fun MoreScreen(ui: UiState, vm: MainViewModel, onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("More", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text("Settings, storage and an honest description of what each feature does.", color = Palette.Muted, fontSize = 10.5.sp)
        Spacer(Modifier.height(12.dp))

        Panel("Online search settings") {
            SettingSwitch(
                title = "Use online services",
                subtitle = "Off by default. Sends the cover to Google Lens for OCR and the title to the catalogues.",
                checked = ui.online.enabled,
                onChange = vm.online::setEnabled,
            )
            SettingSwitch(
                title = "Search after reading the cover",
                subtitle = "When off, the recognised text is shown and you press Search yourself.",
                checked = ui.online.autoSearch,
                onChange = vm.online::setAutoSearch,
            )
            Spacer(Modifier.height(8.dp))
            LanguageField(
                label = "OCR language (two letters, empty = detect)",
                value = ui.online.ocrLanguage,
                onChange = vm.online::setOcrLanguage,
            )
            Spacer(Modifier.height(8.dp))
            LanguageField(
                label = "Translate to before searching (optional)",
                value = ui.online.targetLanguage,
                onChange = vm.online::setTargetLanguage,
            )
        }

        Spacer(Modifier.height(14.dp))
        Panel("What runs where") {
            InfoLine("Offline matching", "On-device OpenCLIP comparison against the chosen workbook. No network.")
            InfoLine("OCR", "Google Lens through chrome-lens-py, inside the app. Text only.")
            InfoLine("Online search", "Open Library, Google Books and DLsite are queried with that text. Text match, not visual.")
            InfoLine("Providers", "Eight of the eleven cards are browser-only shortcuts. Every card has its own browser link and never changes the selection.")
            InfoLine("Providers not queried", "Melonbooks (product decision), Suruga-ya (robots.txt disallows /search/), Doujin no Mori (robots.txt disallows /comics/free-search), E-Hentai and nhentai (adult sites; robots/Cloudflare block automated access), Doujinshi.info (answers HTTP 403 to automated requests), MyAnimeList (no unauthenticated API) and Toranoana (no documented public API; 18+ catalogue). Their cards open the site's own search page and can never be selected.")
            InfoLine("Visual cover search", "Not implemented: it requires a third-party image-similarity service and credentials the app does not ship.")
            InfoLine("Prices / stock", "Only a DLsite listing's own current price is shown. No provider sends stock, so none is invented.")
        }

        Spacer(Modifier.height(14.dp))
        Panel("Saved workbooks") {
            InfoLine("Library", "An import is copied into app storage, so a saved workbook keeps working when the original file moves.")
            InfoLine("Ordering", "Pinned workbooks come first, then the most recently opened.")
            InfoLine("Storage", "Saved copies and regenerable caches are listed separately; clearing a cache never deletes a workbook.")
            GradientButton(
                onClick = vm::openLibrary,
                colors = Palette.NavyGradient,
                contentColor = Palette.Text,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                minHeight = 42.dp,
            ) { ButtonLabel("Open saved workbooks", Palette.Text, 12.5f) }
        }

        Spacer(Modifier.height(14.dp))
        Panel("Workbook comments & images") {
            InfoLine("Legacy notes", "Note text, author and the pictures stored with each note, read offline from the chosen workbook.")
            InfoLine("All images", "Every file under xl/media, with the cell it belongs to when that can be determined.")
            InfoLine("Export", "Copies the image's original bytes to a location you pick. The workbook is never modified.")
            GradientButton(
                onClick = vm::openWorkbookComments,
                colors = Palette.NavyGradient,
                contentColor = Palette.Text,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                minHeight = 42.dp,
            ) { ButtonLabel("Open workbook comments", Palette.Text, 12.5f) }
        }

        Spacer(Modifier.height(14.dp))
        Panel("Storage") {
            InfoLine("Folders", "${ui.folders.size}")
            InfoLine("Bookmarks", "${ui.bookmarks.size}")
            Text(
                "Folders, sessions, results and bookmarks live in one records.json under the app's files directory. " +
                    "Clearing the online session cache never touches them.",
                color = Palette.Muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            GradientButton(
                onClick = vm::clearSessionCache,
                colors = Palette.NavyGradient,
                contentColor = Palette.Text,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                minHeight = 42.dp,
            ) { ButtonLabel("Clear online session cache", Palette.Text, 12.5f) }
        }

        Spacer(Modifier.height(14.dp))
        Panel("This build") {
            when (val model = ui.model) {
                is ModelUi.Ready -> InfoLine("Image model", "${model.dim}-d \u00B7 ${Formatting.fileSize(model.sizeBytes)}")
                ModelUi.Checking -> InfoLine("Image model", "checking")
                ModelUi.Missing -> InfoLine("Image model", "not installed")
                is ModelUi.Installing -> InfoLine("Image model", model.label)
                is ModelUi.Failed -> InfoLine("Image model", model.message)
            }
            InfoLine("Saved data", "folders \u2192 sessions \u2192 results; bookmarks independent")
            InfoLine("Version", com.bookcovermatcher.BuildConfig.VERSION_NAME)
        }

        Spacer(Modifier.height(14.dp))
        PanelsLicence(onOpenUrl)
        Spacer(Modifier.height(12.dp))
        PanelsAuthor(ui, onOpenUrl)
    }
}

/**
 * The licence section of the More tab.
 *
 * A GPLv3 binary has to carry the licence and the third-party statements, so both travel in the APK
 * (`assets/licenses/`, copied from the repository root at build time) and are readable here without a
 * network connection. The panel also states plainly what is *not* covered: the bundled models and the
 * test assets keep their own terms.
 */
@Composable
private fun PanelsLicence(onOpenUrl: (String) -> Unit) {
    var open by remember { mutableStateOf<LicenseDocuments?>(null) }
    Panel("Licences") {
        InfoLine("This app", "GNU GPL v3.0 or later \u2014 your option")
        InfoLine("Your rights", "Redistribute and/or modify under GPLv3, or any later version published by the FSF.")
        InfoLine("Third-party", "PaddleOCR models Apache-2.0; ONNX Runtime MIT; OpenCLIP weights unresolved.")
        GradientButton(
            onClick = { open = LicenseDocuments.GPL },
            colors = Palette.NavyGradient,
            contentColor = Palette.Text,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            minHeight = 42.dp,
        ) { ButtonLabel("Open-source licences", Palette.Text, 12.5f) }
        Text(
            "Read in the app, in a larger full-screen view if you prefer, from the copies packaged in the APK.",
            color = Palette.Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    open?.let { document ->
        LicenseReaderDialog(
            start = document,
            onClose = { open = null },
            onOpenUrl = onOpenUrl,
            onLinkRefused = onOpenUrl,
        )
    }
}

/**
 * The Author section (v2.6.0): the GitHub identity, the bundled profile picture, the bundled profile README
 * rendered as Markdown, and a button that opens the profile.
 *
 * Everything here reads from the APK's own assets. Opening this page never contacts GitHub, so the section is
 * complete offline; only the two explicit link actions use the network, and they go through the app's
 * HTTPS-only external-link handler.
 */
@Composable
private fun PanelsAuthor(ui: UiState, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    val avatar by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(AuthorProfile.AVATAR_ASSET).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.asImageBitmap()
        }
    }
    // The repository publishes the cached README (or the bundled copy) at startup and then refreshes it in the
    // background. The asset read below is only the first-frame fallback for the instant before that lands; it is a
    // local read, never a network call, so nothing here depends on the Licences tab being opened.
    val bundled by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(AuthorProfile.README_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }
    val markdown = ui.author.markdown.ifBlank { bundled.orEmpty() }
    val blocks = remember(markdown) { if (markdown.isBlank()) null else Markdown.parse(markdown) }
    val pictures = remember(ui.author.images) {
        ui.author.images.mapValues { (_, image) ->
            MarkdownImage(image.bitmap, image.aspectRatio, image.animation)
        }
    }

    Panel("Author") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AuthorAvatar(avatar)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    AuthorProfile.USERNAME,
                    color = Palette.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text("GitHub username", color = Palette.Muted, fontSize = 9.5.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Nickname  ${AuthorProfile.NICKNAME}",
                    color = Palette.Accent2,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Text(
            "github.com/${AuthorProfile.USERNAME}",
            color = Palette.Accent,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 8.dp).clickable { onOpenUrl(AuthorProfile.GITHUB_URL) },
        )

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Profile README", color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (expanded) "Collapse" else "Expand",
                color = Palette.Accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        Text(
            authorStatus(ui.author),
            color = Palette.Muted,
            fontSize = 9.5.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )

        val loadedBlocks = blocks
        if (loadedBlocks == null) {
            Text("Reading the profile README\u2026", color = Palette.Muted, fontSize = 10.5.sp)
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (expanded) 460.dp else 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                MarkdownDocument(
                    blocks = loadedBlocks,
                    onOpenUrl = onOpenUrl,
                    baseSize = 10.5.sp,
                    onLinkRefused = onOpenUrl,
                    images = pictures,
                    // The graph plays whenever it is on screen, in either state (v2.6.0 crash-fix 2): the previous
                    // rule tied playback to "expanded", so a reader who scrolled the collapsed README down to the
                    // graph saw it frozen. Work stays bounded by composition (the More tab) and by RESUMED.
                    animateImages = true,
                )
            }
        }

        GradientButton(
            onClick = { onOpenUrl(AuthorProfile.GITHUB_URL) },
            colors = Palette.AccentGradient,
            contentColor = Palette.OnAccent,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            minHeight = 42.dp,
        ) { ButtonLabel("Visit GitHub Profile", Palette.OnAccent, 12.5f) }

        Text(
            "Opens https://github.com/${AuthorProfile.USERNAME} in your browser. No GitHub account is needed.",
            color = Palette.Muted,
            fontSize = 9.5.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * A truthful, quiet line about where the README on screen came from.
 *
 * The amendment requires the page to keep working offline without an error dialog, so a failed refresh is never
 * presented as a failure: the line simply keeps saying which copy is being shown.
 */
private fun authorStatus(author: AuthorUi): String {
    val at = author.refreshedAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
    }
    return when {
        author.refreshing && at != null -> "Refreshing from GitHub\u2026 showing the saved copy from $at."
        author.refreshing -> "Refreshing from GitHub\u2026"
        author.source == AuthorSource.REFRESHED && at != null ->
            "Updated from GitHub at $at. Pictures come from the README's own sources."
        author.source == AuthorSource.CACHED && at != null ->
            "Saved copy, last refreshed $at. A refresh runs on every app launch."
        author.source == AuthorSource.CACHED ->
            "Saved copy. A refresh runs on every app launch."
        else ->
            "Copy bundled in the app, retrieved ${AuthorProfile.README_RETRIEVED}. " +
                "A refresh runs in the background on every app launch."
    }
}

/** The bundled profile picture, or a neutral placeholder if the image cannot be decoded. */
@Composable
private fun AuthorAvatar(avatar: ImageBitmap?) {
    val shape = CircleShape
    if (avatar == null) {
        Box(
            Modifier
                .size(56.dp)
                .clip(shape)
                .background(Palette.Navy800)
                .border(1.dp, Palette.Line, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text("P1", color = Palette.Muted, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
        return
    }
    Image(
        bitmap = avatar,
        contentDescription = AuthorProfile.avatarDescription(),
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(56.dp).clip(shape).border(1.dp, Palette.Line, shape),
    )
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Palette.Muted, fontSize = 10.5.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.Muted,
                uncheckedTrackColor = Palette.Navy650,
            ),
        )
    }
}

@Composable
private fun LanguageField(label: String, value: String, onChange: (String) -> Unit) {
    FieldLabel(label)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Palette.Text, fontSize = 13.sp),
        cursorBrush = SolidColor(Palette.Accent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text("e.g. en, ja, fr", color = Palette.Muted, fontSize = 12.sp)
                inner()
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Palette.Accent2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

