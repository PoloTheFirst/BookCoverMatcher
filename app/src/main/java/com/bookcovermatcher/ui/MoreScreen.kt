// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.ModelUi
import com.bookcovermatcher.UiState
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.core.licensing.LicenseDocuments
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.FieldLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * More: settings and information that do not belong to Home, Search or Wishlist.
 *
 * The online settings here are the same preferences the Search panel reads; there is one store and
 * one controller, so the switch and the behaviour cannot drift apart.
 */
@Composable
fun MoreScreen(ui: UiState, vm: MainViewModel) {
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
        PanelsLicence()
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
private fun PanelsLicence() {
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
    }
    open?.let { document -> LicenseDialog(document) { open = null } }
}

/** Shows one licence document, read from the APK's own assets. */
@Composable
private fun LicenseDialog(start: LicenseDocuments, onClose: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(start) }
    val text by produceState(initialValue = "Reading ${current.title}\u2026", current) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(current.assetPath).bufferedReader().use { it.readText() }
            }.getOrElse { "This document could not be read from the APK: ${it.message}" }
        }
    }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Navy900)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Text(current.title, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(current.subtitle, color = Palette.Muted, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LicenseDocuments.ordered.forEach { document ->
                    GradientButton(
                        onClick = { current = document },
                        colors = if (document == current) Palette.AccentGradient else Palette.NavyGradient,
                        contentColor = Palette.Text,
                        modifier = Modifier.weight(1f),
                        minHeight = 34.dp,
                    ) { ButtonLabel(document.shortLabel, Palette.Text, 10.5f) }
                }
            }
            Text(
                text,
                color = Palette.Text,
                fontSize = 9.5.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
            GradientButton(
                onClick = onClose,
                colors = Palette.GreyGradient,
                contentColor = Color(0xFFEAEEF5),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                minHeight = 38.dp,
            ) { ButtonLabel("Close", Color(0xFFEAEEF5), 12f) }
        }
    }
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

