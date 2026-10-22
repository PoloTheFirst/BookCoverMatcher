package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.CustomBookmarkUi
import com.bookcovermatcher.PickerUi
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.FieldLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.theme.Palette

/**
 * The shared profile picker (§6.5): existing profiles only, a real new folder when asked for,
 * "already contains" shown instead of silently skipped, and nothing written until Confirm.
 */
@Composable
fun ProfilePickerDialog(
    picker: PickerUi?,
    onCancel: () -> Unit,
    onConfirm: (profileId: String?, newProfileName: String) -> Unit,
) {
    val state = picker ?: return
    BackHandler(enabled = true) { onCancel() }
    var selectedId by remember(state) { mutableStateOf(state.defaultFolderId) }
    var newName by remember(state) { mutableStateOf("") }
    val creating = newName.trim().isNotEmpty()
    val summary = when (state.mode) {
        com.bookcovermatcher.PickerMode.SAVE_SESSION ->
            "${state.itemCount} comparison result(s) will be saved as one session in this folder."
        com.bookcovermatcher.PickerMode.SAVE_RESULT_PROFILE ->
            "${state.itemCount} result(s) will be saved as one session in this folder."
        com.bookcovermatcher.PickerMode.ADD_ITEMS ->
            "${state.itemCount} item(s) will be added directly to this folder. Bookmarks stay in Bookmarks."
    }

    Scrim(Modifier.imePadding()) {
        DialogCard {
            Text(state.headline, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                summary,
                color = Palette.Muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(10.dp))

            Column(
                Modifier
                    .heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.folders.isEmpty()) {
                    Text(
                        "No folders yet. Create one below; a folder is just the name you give it.",
                        color = Palette.Muted,
                        fontSize = 11.sp,
                    )
                }
                state.folders.forEach { profile ->
                    val active = selectedId == profile.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Palette.Accent.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.22f))
                            .border(1.dp, if (active) Palette.Accent else Palette.Line, RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) {
                                selectedId = if (active) null else profile.id
                                newName = ""
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.name,
                                color = Palette.Text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (profile.alreadyContains) {
                                    "${profile.itemCount} item(s) · already contains this"
                                } else {
                                    "${profile.itemCount} item(s)"
                                },
                                color = if (profile.alreadyContains) Palette.Warn else Palette.Muted,
                                fontSize = 10.sp,
                            )
                        }
                        if (active) Text("Selected", color = Palette.Accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            FormField("Create new folder (optional)", newName, {
                newName = it
                if (it.isNotBlank()) selectedId = null
            }, "e.g. Doujinshi")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(onCancel, Palette.GreyGradient, Color(0xFFEAEEF5), Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Cancel", Color(0xFFEAEEF5))
                }
                GradientButton(
                    onClick = { onConfirm(selectedId, if (creating) newName.trim() else "") },
                    colors = Palette.AccentGradient,
                    contentColor = Palette.OnAccent2,
                    modifier = Modifier.weight(1f),
                    minHeight = 46.dp,
                    enabled = creating || selectedId != null,
                ) { ButtonLabel("Confirm", Palette.OnAccent2) }
            }
        }
    }
}

/**
 * `+ Create bookmark`: title and URL are the only required fields. Nothing is fetched — the app
 * stores exactly what was typed, and the URL must be `https` because that is all it may open.
 */
@Composable
fun CustomBookmarkDialog(
    state: CustomBookmarkUi?,
    onCancel: () -> Unit,
    onCreate: (title: String, url: String, authors: String, description: String, identifier: String, price: String) -> Unit,
) {
    state ?: return
    BackHandler(enabled = true) { onCancel() }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var authors by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    Scrim(Modifier.imePadding()) {
        DialogCard {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Create bookmark", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Saved as-is. The app does not fetch the page, a cover, a price or a stock status.",
                    color = Palette.Muted,
                    fontSize = 10.5.sp,
                )
                FormField("Title (required)", title, { title = it }, "Title or product name")
                FormField("URL (required, https)", url, { url = it }, "https://…")
                FormField("Author / artist / circle / publisher", authors, { authors = it }, "Optional")
                FormField("Product identifier", identifier, { identifier = it }, "Optional")
                FormField("Price", price, { price = it }, "Optional")
                FormField("Description / notes", notes, { notes = it }, "Optional")
                state.error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(onCancel, Palette.GreyGradient, Color(0xFFEAEEF5), Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Cancel", Color(0xFFEAEEF5))
                }
                GradientButton(
                    onClick = { onCreate(title, url, authors, notes, identifier, price) },
                    colors = Palette.AccentGradient,
                    contentColor = Palette.OnAccent2,
                    modifier = Modifier.weight(1f),
                    minHeight = 46.dp,
                ) { ButtonLabel("Save", Palette.OnAccent2) }
            }
        }
    }
}

/** A labelled single-line field, matching the range/text fields of the main screen. */
@Composable
private fun FormField(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    Column(Modifier.fillMaxWidth()) {
        FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 13.sp),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
    }
}

