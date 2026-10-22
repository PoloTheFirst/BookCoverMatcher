package com.bookcovermatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.BridgeUi
import com.bookcovermatcher.CatalogueUi
import com.bookcovermatcher.OcrUi
import com.bookcovermatcher.OnlineSearchUi
import com.bookcovermatcher.online.OnlineSearchController
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.FieldLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.theme.Palette

/**
 * Panel "2.5 · Online search" — the number v1 used for its online panel.
 *
 * Two clearly separated steps, because they are two different services:
 *  * **A · Read the cover** — OCR through the embedded `chrome-lens-py` package.
 *  * **B · Search the catalogue** — structured book records from Open Library.
 *
 * The switch is off by default and the panel says so: the app used to ship with no `INTERNET`
 * permission at all, and the offline matcher must stay usable with the switch off and the phone
 * in airplane mode.
 */
@Composable
fun OnlineSearchPanel(ui: OnlineSearchUi, controller: OnlineSearchController, onOpenUrl: (String) -> Unit) {
    Panel("2.5 · Online search") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Use online services", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Off by default. Sends the cover to Google Lens and the title to Open Library.",
                    color = Palette.Muted,
                    fontSize = 10.5.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = ui.enabled,
                onCheckedChange = controller::setEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.OnAccent,
                    checkedTrackColor = Palette.Accent,
                    uncheckedThumbColor = Palette.Muted,
                    uncheckedTrackColor = Palette.Navy650,
                ),
            )
        }

        if (!ui.enabled) {
            Text(
                "Offline matching above works without this and without any connection.",
                modifier = Modifier.padding(top = 8.dp),
                color = Palette.Muted,
                fontSize = 11.sp,
            )
            return@Panel
        }

        BridgeLine(ui.bridge)
        Spacer(Modifier.height(12.dp))
        OcrSection(ui, controller)
        Spacer(Modifier.height(12.dp))
        CatalogueSection(ui, controller, onOpenUrl)
    }
}


// ---------------------------------------------------------------------------------------------
// A · OCR
// ---------------------------------------------------------------------------------------------

@Composable
private fun BridgeLine(bridge: BridgeUi) {
    val (dot, text) = when (bridge) {
        BridgeUi.Checking -> Palette.Muted to "Starting the embedded Python runtime…"
        is BridgeUi.Ready -> Palette.Ok to "${bridge.library} ${bridge.version} · OCR only, no product search"
        is BridgeUi.Unavailable -> Palette.Danger to bridge.reason
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, color = Palette.Muted, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OcrSection(ui: OnlineSearchUi, controller: OnlineSearchController) {
    FieldLabel("A · Read the cover (Google Lens OCR)")

    when (val ocr = ui.ocr) {
        is OcrUi.Running -> BusyRow(ocr.label, controller::cancel)
        is OcrUi.Failed -> {
            Text(ocr.message, color = Palette.Danger, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            GradientButton(
                onClick = controller::readCover,
                colors = Palette.NavyGradient,
                contentColor = Palette.Text,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 44.dp,
            ) { ButtonLabel("Try again", Palette.Text, 13.5f) }
        }
        is OcrUi.NoText -> Text(
            "Lens found no text on this cover (v${ocr.libraryVersion}). Type a title below instead.",
            color = Palette.Warn,
            fontSize = 11.sp,
        )
        is OcrUi.Done -> {
            Text(
                buildString {
                    append("Recognised")
                    ocr.language?.let { append(" · language $it") }
                    append(" · chrome-lens-py ${ocr.libraryVersion}")
                },
                color = Palette.Muted,
                fontSize = 10.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            OcrTextField(ocr.text)
            if (ocr.suggestion.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.Accent2.copy(alpha = 0.12f))
                        .clickable(role = Role.Button) { controller.setQuery(ocr.suggestion); controller.search(ocr.suggestion) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Searched as: ${ocr.suggestion}  ↻", color = Palette.Accent2, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        OcrUi.Idle -> Text(
            "Scan a cover, then read its text. chrome-lens-py only does OCR and translation — it has no product search.",
            color = Palette.Muted,
            fontSize = 11.sp,
        )
    }

    if (ui.ocr !is OcrUi.Running) {
        Spacer(Modifier.height(8.dp))
        GradientButton(
            onClick = controller::readCover,
            colors = Palette.NavyGradient,
            contentColor = Palette.Text,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 46.dp,
        ) { ButtonLabel("Read cover text", Palette.Text, 13.5f) }
    }
}

/** The recognised text. Editable: OCR gets covers wrong, and the user knows better. */
@Composable
private fun OcrTextField(value: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            value.ifEmpty { "—" },
            color = Palette.Text,
            fontSize = 12.sp,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


// ---------------------------------------------------------------------------------------------
// B · Catalogue search
// ---------------------------------------------------------------------------------------------

@Composable
private fun CatalogueSection(ui: OnlineSearchUi, controller: OnlineSearchController, onOpenUrl: (String) -> Unit) {
    FieldLabel("B · Search the catalogue (Open Library)")

    BasicTextField(
        value = ui.query,
        onValueChange = controller::setQuery,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        textStyle = TextStyle(color = Palette.Text, fontSize = 13.sp),
        cursorBrush = SolidColor(Palette.Accent),
        maxLines = 2,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { controller.search() }),
        decorationBox = { inner ->
            Box {
                if (ui.query.isEmpty()) {
                    Text(
                        "Title (and author). Filled from the cover text, editable.",
                        color = Palette.Muted,
                        fontSize = 13.sp,
                    )
                }
                inner()
            }
        },
    )

    Spacer(Modifier.height(10.dp))

    when (val catalogue = ui.catalogue) {
        is CatalogueUi.Running -> BusyRow("Searching Open Library for “${catalogue.query}”…", controller::cancel)
        is CatalogueUi.Failed -> Text(catalogue.message, color = Palette.Danger, fontSize = 11.sp)
        is CatalogueUi.Empty -> Text(
            "Open Library has nothing for “${catalogue.query}”. Try a shorter title.",
            color = Palette.Warn,
            fontSize = 11.sp,
        )
        is CatalogueUi.Found -> {
            Text(
                buildString {
                    append("${catalogue.items.size} of ${catalogue.totalAvailable ?: catalogue.items.size} record(s)")
                    append(" · ${catalogue.source} · “${catalogue.query}”")
                },
                color = Palette.Muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            catalogue.items.forEachIndexed { index, item ->
                ProductResultCard(item = item, index = index, onOpen = onOpenUrl)
                Spacer(Modifier.height(8.dp))
            }
        }
        CatalogueUi.Idle -> Unit
    }

    if (ui.catalogue !is CatalogueUi.Running) {
        GradientButton(
            onClick = { controller.search() },
            colors = Palette.AccentGradient,
            contentColor = Palette.OnAccent2,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 46.dp,
            glow = Palette.Accent2.copy(alpha = 0.5f),
        ) { ButtonLabel("Search catalogue", Palette.OnAccent2, 13.5f) }
    }
}

@Composable
private fun BusyRow(label: String, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = Palette.Accent,
            strokeWidth = 2.dp,
        )
        Text(
            label,
            color = Palette.Muted,
            fontSize = 11.5.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Cancel",
            color = Palette.Danger,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
