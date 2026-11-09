// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookcovermatcher.core.licensing.LicenseDocuments
import com.bookcovermatcher.core.markdown.Markdown
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.markdown.MarkdownDocument
import com.bookcovermatcher.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A full reader for the licence documents the APK ships (v2.6.0).
 *
 * The document is read from `assets/licenses/…` — the same files the build copies from the repository root — and
 * parsed with [Markdown], so the GPL text (plain) and the third-party notices (Markdown) both display correctly
 * with no network at all.
 *
 * Two reading modes: a dialog-sized view for a quick look, and a full-screen view that uses the whole window. The
 * chosen document and the scroll position live above the mode switch, so switching modes never reloads the
 * document and never throws the reader back to the top.
 */
@Composable
fun LicenseReaderDialog(
    start: LicenseDocuments,
    onClose: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onLinkRefused: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var current by rememberSaveable { mutableStateOf(start) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // Read once per document; assets never change while the app runs.
    val loaded by produceState<LoadedDocument>(LoadedDocument.Loading, current) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(current.assetPath).bufferedReader().use { it.readText() }
            }.fold(
                onSuccess = { LoadedDocument.Ready(Markdown.parse(it)) },
                onFailure = { LoadedDocument.Failed },
            )
        }
    }

    // Back leaves full screen first, so the reader can never trap the user in it.
    BackHandler(enabled = fullScreen) { fullScreen = false }

    val dialogProperties = if (fullScreen) {
        DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    } else {
        DialogProperties()
    }

    // Back and the dismiss gesture leave full screen first, then close the reader. A Dialog owns the back key
    // while it is open, so this is the handler that actually runs; the BackHandler above covers the case where
    // the dialog window is not the back owner.
    Dialog(
        onDismissRequest = {
            if (fullScreen) fullScreen = false else onClose()
        },
        properties = dialogProperties,
    ) {
        val surface = if (fullScreen) {
            Modifier.fillMaxSize().background(Palette.Navy950).systemBarsPadding()
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 660.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Navy900)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
        }
        Column(surface.padding(14.dp)) {
            Header(
                document = current,
                fullScreen = fullScreen,
                onToggleFullScreen = { fullScreen = !fullScreen },
                onClose = onClose,
            )
            Spacer(Modifier.height(8.dp))
            DocumentTabs(current) { current = it }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val document = loaded) {
                    LoadedDocument.Loading -> Text(
                        "Reading ${current.title}\u2026",
                        color = Palette.Muted,
                        fontSize = 11.sp,
                    )

                    LoadedDocument.Failed -> Text(
                        "This document could not be read from the APK, so nothing is shown rather than a " +
                            "partial copy of a licence.",
                        color = Palette.Warn,
                        fontSize = 11.sp,
                    )

                    is LoadedDocument.Ready -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(end = 4.dp),
                    ) {
                        MarkdownDocument(
                            blocks = document.blocks,
                            onOpenUrl = onOpenUrl,
                            baseSize = if (fullScreen) 13.sp else 10.5.sp,
                            onLinkRefused = onLinkRefused,
                        )
                    }
                }
            }
            if (!fullScreen) {
                GradientButton(
                    onClick = onClose,
                    colors = Palette.NavyGradient,
                    contentColor = Palette.Text,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    minHeight = 38.dp,
                ) { ButtonLabel("Close", Palette.Text, 11.5f) }
            }
        }
    }
}

/** What the reader has for the current document. */
private sealed interface LoadedDocument {
    data object Loading : LoadedDocument

    data object Failed : LoadedDocument

    data class Ready(val blocks: List<com.bookcovermatcher.core.markdown.MdBlock>) : LoadedDocument
}

@Composable
private fun Header(
    document: LicenseDocuments,
    fullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(document.title, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(document.subtitle, color = Palette.Muted, fontSize = 10.sp)
        }
        Text(
            text = if (fullScreen) "Exit full screen" else "Full screen",
            color = Palette.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = 10.dp)
                .clickable { onToggleFullScreen() }
                .padding(4.dp),
        )
        Text(
            text = "Close",
            color = Palette.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = 8.dp)
                .clickable { onClose() }
                .padding(4.dp),
        )
    }
}

@Composable
private fun DocumentTabs(current: LicenseDocuments, onSelect: (LicenseDocuments) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LicenseDocuments.ordered.forEach { document ->
            GradientButton(
                onClick = { onSelect(document) },
                colors = if (document == current) Palette.AccentGradient else Palette.NavyGradient,
                contentColor = Palette.Text,
                modifier = Modifier.weight(1f),
                minHeight = 34.dp,
            ) { ButtonLabel(document.shortLabel, Palette.Text, 10.5f) }
        }
    }
}
