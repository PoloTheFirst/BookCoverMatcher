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
import androidx.compose.foundation.text.selection.SelectionContainer
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
 * Online-search panel: OCR, the editable query and the catalogue results.
 *
 * Two clearly separated steps, because they are two different services:
 *  * **A · Read the cover** — OCR through the embedded `chrome-lens-py` package.
 *  * **B · Search the catalogues** — structured book records from Open Library and Google Books.
 *
 * The switch is off by default and the panel says so: the app used to ship with no `INTERNET`
 * permission at all, and the offline matcher must stay usable with the switch off and the phone
 * in airplane mode.
 */
@Composable
fun OnlineSearchPanel(
    ui: OnlineSearchUi,
    controller: OnlineSearchController,
    onOpenUrl: (String) -> Unit,
    bookmarkedKeys: Set<String>,
    wishlistedKeys: Set<String>,
    onToggleBookmark: (com.bookcovermatcher.ProductResultUi) -> Unit,
    onAddToWishlist: (com.bookcovermatcher.ProductResultUi) -> Unit,
    onBulkAdd: () -> Unit,
    onBookmarkAll: () -> Unit,
) {
    Panel("Online search") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Use online services", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Off by default. Sends the cover to Google Lens; the text goes to Open Library and Google Books.",
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
        CatalogueSection(
            ui = ui,
            controller = controller,
            onOpenUrl = onOpenUrl,
            bookmarkedKeys = bookmarkedKeys,
            wishlistedKeys = wishlistedKeys,
            onToggleBookmark = onToggleBookmark,
            onAddToWishlist = onAddToWishlist,
            onBulkAdd = onBulkAdd,
            onBookmarkAll = onBookmarkAll,
        )
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

/**
 * The recognised text.
 *
 * Read-only on purpose: OCR output is a record of what the cover says, and the *editable* copy is the
 * query field in section B. It is selectable ([SelectionContainer]), so a long-press can select part
 * or all of it and the system Copy command can paste it into the query field.
 */
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
        SelectionContainer {
            Text(
                value.ifEmpty { "—" },
                color = Palette.Text,
                fontSize = 12.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


// ---------------------------------------------------------------------------------------------
// B · Catalogue search
// ---------------------------------------------------------------------------------------------

@Composable
private fun CatalogueSection(
    ui: OnlineSearchUi,
    controller: OnlineSearchController,
    onOpenUrl: (String) -> Unit,
    bookmarkedKeys: Set<String>,
    wishlistedKeys: Set<String>,
    onToggleBookmark: (com.bookcovermatcher.ProductResultUi) -> Unit,
    onAddToWishlist: (com.bookcovermatcher.ProductResultUi) -> Unit,
    onBulkAdd: () -> Unit,
    onBookmarkAll: () -> Unit,
) {
    FieldLabel("B · Search the catalogues (Open Library + Google Books)")

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

    // Who is selected, what each one did, and the result maximum. Drawn before the results so a
    // partial answer can never look like a complete one.
    PlatformPicker(
        providers = ui.providers,
        onToggle = controller::toggleProvider,
        onOpenLink = onOpenUrl,
    )
    Spacer(Modifier.height(10.dp))
    ResultLimitPicker(ui.resultLimit, controller::setResultLimit)
    Spacer(Modifier.height(10.dp))

    when (val catalogue = ui.catalogue) {
        is CatalogueUi.Running -> BusyRow("Searching the selected platforms for " + catalogue.query + " ...", controller::cancel)
        is CatalogueUi.Failed -> Text(catalogue.message, color = Palette.Danger, fontSize = 11.sp)
        is CatalogueUi.BrowserOnly -> {
            Text(catalogue.message, color = Palette.Warn, fontSize = 11.sp)
            Text(
                "Their search pages are one tap away: use Open \u2197 on the selected platform above. " +
                    "Nothing was sent from the app.",
                color = Palette.Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        is CatalogueUi.Empty -> {
            Text(
                "No selected platform has anything for " + catalogue.query + ". Try a shorter title.",
                color = Palette.Warn,
                fontSize = 11.sp,
            )
            catalogue.note?.let {
                Text(it, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        is CatalogueUi.Found -> {
            Text(
                buildString {
                    append(catalogue.items.size).append(" result(s) shown")
                    append(" \u00B7 max ").append(ui.resultLimit)
                    append(" \u00B7 ").append(catalogue.source)
                    if (catalogue.items.size < ui.resultLimit) {
                        // Never let a short list look like a truncated one.
                        append(" \u00B7 fewer than the maximum: no more matches were returned")
                    }
                },
                color = Palette.Muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            catalogue.note?.let {
                Text(it, color = Palette.Warn, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            catalogue.items.forEachIndexed { index, item ->
                val shown = item.copy(
                    bookmarked = item.key in bookmarkedKeys,
                    wishlisted = item.key in wishlistedKeys,
                )
                ProductResultCard(
                    item = shown,
                    index = index,
                    onOpen = onOpenUrl,
                    onToggleBookmark = onToggleBookmark,
                    onAddToWishlist = onAddToWishlist,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Two bulk actions with different scopes, both separate from the per-card chips:
            // bookmarking writes to `Bookmarks` only, adding to the wishlist writes to one profile.
            GradientButton(
                onClick = onBookmarkAll,
                colors = Palette.NavyGradient,
                contentColor = Palette.Text,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 44.dp,
            ) { ButtonLabel("Bookmark all results", Palette.Text, 13f) }
            Text(
                "Saves every result above to the Bookmarks collection. No profile is created.",
                modifier = Modifier.padding(top = 5.dp),
                color = Palette.Muted,
                fontSize = 10.sp,
            )

            Spacer(Modifier.height(8.dp))
            GradientButton(
                onClick = onBulkAdd,
                colors = Palette.GreenGradient,
                contentColor = Color.White,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 46.dp,
            ) { ButtonLabel("Add entire result profile to wishlist", Color.White, 13.5f) }
            Text(
                "Includes the workbook matches and every online result above; you choose the profile.",
                modifier = Modifier.padding(top = 6.dp),
                color = Palette.Muted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Clear session cache",
                color = Palette.Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = controller::clearSession)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
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
        ) { ButtonLabel("Search selected platforms", Palette.OnAccent2, 13.5f) }
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
