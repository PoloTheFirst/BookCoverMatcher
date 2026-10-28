package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.bookcovermatcher.core.wishlist.BookmarkForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.bookcovermatcher.core.wishlist.ArtistFields
import androidx.compose.foundation.layout.Box
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
                            .background(if (active) Palette.Accent.copy(alpha = 0.15f) else Palette.Navy900)
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomBookmarkDialog(
    state: CustomBookmarkUi?,
    onCancel: () -> Unit,
    onSave: (title: String, url: String, artists: List<String>, circle: String, description: String, price: String) -> Unit,
    onPickImage: (Uri) -> Unit,
    onRemoveImage: () -> Unit,
) {
    val target = state ?: return
    BackHandler(enabled = true) { onCancel() }

    // Prefilled when editing, empty when creating. Keyed on the target so switching resets the fields.
    var title by remember(target.editingId) { mutableStateOf(target.title) }
    var url by remember(target.editingId) { mutableStateOf(target.url) }
    var artists by remember(target.editingId) { mutableStateOf(ArtistFields.rows(target.artists)) }
    var circle by remember(target.editingId) { mutableStateOf(target.circle) }
    var price by remember(target.editingId) { mutableStateOf(target.price) }
    var notes by remember(target.editingId) { mutableStateOf(target.notes) }

    val titleRequester = remember { BringIntoViewRequester() }
    val urlRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    // Field errors sit under their fields; the topmost invalid field is scrolled into view (also when
    // the keyboard is open) so the user never has to hunt for the warning.
    val firstInvalid = BookmarkForm.firstInvalid(target.fieldErrors)
    // After a failed submit every error stays visible, and each one clears as soon as its field becomes
    // valid — errors for other fields are not hidden while the user fixes one.
    val liveErrors: Map<String, String> =
        if (target.fieldErrors.isEmpty()) emptyMap() else BookmarkForm.validate(title, url)
    LaunchedEffect(firstInvalid, target.error) {
        if (firstInvalid != null) {
            scope.launch {
                when (firstInvalid) {
                    BookmarkForm.FIELD_TITLE -> titleRequester.bringIntoView()
                    else -> urlRequester.bringIntoView()
                }
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onPickImage)
    }

    Scrim(Modifier.imePadding()) {
        DialogCard {
            Column(
                Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (target.isEditing) "Edit bookmark" else "Create bookmark",
                    color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "Saved as-is. The app does not fetch the page, a cover, a price or a stock status.",
                    color = Palette.Muted,
                    fontSize = 10.5.sp,
                )
                BookmarkField(
                    label = "Title (Required)",
                    value = title,
                    onChange = { title = it },
                    placeholder = BookmarkForm.TITLE_PLACEHOLDER,
                    error = liveErrors[BookmarkForm.FIELD_TITLE],
                    modifier = Modifier.bringIntoViewRequester(titleRequester),
                )
                BookmarkField(
                    label = "URL (REQUIRED)",
                    value = url,
                    onChange = { url = it },
                    placeholder = BookmarkForm.URL_PLACEHOLDER,
                    error = liveErrors[BookmarkForm.FIELD_URL],
                    modifier = Modifier.bringIntoViewRequester(urlRequester),
                )
                ArtistSection(artists) { artists = it }
                BookmarkField(
                    label = "Circle (Optional)",
                    value = circle,
                    onChange = { circle = it },
                    placeholder = BookmarkForm.CIRCLE_PLACEHOLDER,
                )
                BookmarkField(
                    label = "Price (Optional)",
                    value = price,
                    onChange = { price = it },
                    placeholder = BookmarkForm.PRICE_PLACEHOLDER,
                )
                BookmarkField(
                    label = "Notes (Optional)",
                    value = notes,
                    onChange = { notes = it },
                    placeholder = BookmarkForm.NOTES_PLACEHOLDER,
                )
                BookmarkImageSection(
                    imagePath = target.imagePath,
                    onPick = { pickImage.launch("image/*") },
                    onRemove = onRemoveImage,
                )
                target.error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(onCancel, Palette.GreyGradient, Color(0xFFEAEEF5), Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Cancel", Color(0xFFEAEEF5))
                }
                GradientButton(
                    onClick = {
                        if (!target.saving) onSave(title, url, artists, circle, notes, price)
                    },
                    colors = Palette.AccentGradient,
                    contentColor = Palette.OnAccent2,
                    modifier = Modifier.weight(1f),
                    minHeight = 46.dp,
                    enabled = !target.saving,
                ) {
                    ButtonLabel(if (target.saving) "Saving…" else "Save", Palette.OnAccent2)
                }
            }
        }
    }
}

/** Artist rows: labelled section; the circular `+` stays on the first row, extras carry their own `×`. */
@Composable
private fun ArtistSection(artists: List<String>, onChange: (List<String>) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        FieldLabel("Artist (Optional)")
        artists.forEachIndexed { index, value ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The input keeps all the width it is given; the button owns its own 48 dp box plus an
                // 8 dp gap, so the two never overlap and every row's input has exactly the same width.
                Box(Modifier.weight(1f)) {
                    BookmarkField(
                        label = null,
                        value = value,
                        onChange = { next ->
                            onChange(artists.toMutableList().also { it[index] = next })
                        },
                        placeholder = BookmarkForm.ARTIST_PLACEHOLDER,
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Fixed arrangement: row 0 keeps the `+` forever; every additional row has its own `×`.
                when (ArtistFields.actionFor(index, artists.size)) {
                    ArtistFields.RowAction.ADD -> CircleIconButton(
                        icon = Icons.Filled.Add,
                        description = "Add another artist",
                        tint = Palette.Accent,
                        onClick = { onChange(ArtistFields.add(artists)) },
                    )
                    ArtistFields.RowAction.REMOVE -> CircleIconButton(
                        icon = Icons.Filled.Close,
                        description = "Remove this artist",
                        tint = Palette.Muted,
                        onClick = { onChange(ArtistFields.removeAt(artists, index)) },
                    )
                }
            }
        }
    }
}

/** A 48 dp touch target with a circular background and outline; the icon itself stays small. */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Palette.Navy700)
            .border(1.dp, tint.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * One labelled field: gray sample text while empty (a placeholder, never a value), a red border and the
 * message directly underneath when the field is invalid.
 */
@Composable
private fun BookmarkField(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (label != null) FieldLabel(label)
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
                        .background(Palette.Navy900)
                        .border(
                            1.dp,
                            if (error != null) Palette.Danger else Palette.Line,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(placeholder, color = Palette.Muted, fontSize = 13.sp, maxLines = 1)
                        }
                        inner()
                    }
                }
            },
        )
        if (error != null) {
            Text(error, color = Palette.Danger, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/**
 * A simple "photo" glyph built from a vector path locally: material-icons-core ships no image icon and
 * this keeps the form dependency-free.
 */
private val PhotoGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "Photo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2z" +
                "M8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
        ),
        fill = SolidColor(Color.White),
    ).build()
}

/** Optional local image: preview, choose/replace and remove. Nothing is written until Save. */
@Composable
private fun BookmarkImageSection(imagePath: String?, onPick: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        FieldLabel("Image (optional)")
        val bitmap by produceState<Bitmap?>(initialValue = null, imagePath) {
            value = if (imagePath == null) null else withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imagePath) }
        }
        val preview = bitmap
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Selected bookmark image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GradientButton(
                onClick = onPick,
                colors = Palette.NavyGradient,
                contentColor = Palette.Text,
                // Intrinsic width in both states: a weighted share can be a few pixels narrower than
                // the label, which clipped its final glyph.
                modifier = Modifier.wrapContentWidth(),
                minHeight = 44.dp,
                // Intrinsic width, so the label's final glyph is never clipped by a weighted share
                // that is a few pixels too small; the Remove button keeps the remaining space.
                contentPadding = 16.dp,
            ) {
                Icon(
                    PhotoGlyph,
                    contentDescription = null,
                    tint = Palette.Accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                ButtonLabel("Choose image", Palette.Text, 12f)
            }
            if (imagePath != null) {
                GradientButton(
                    onClick = onRemove,
                    colors = Palette.GreyGradient,
                    contentColor = Palette.Text,
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                    contentPadding = 14.dp,
                ) {
                    ButtonLabel("Remove image", Palette.Text, 12f)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (imagePath == null) {
                "Choose an image from your device. It is copied into the app's own storage; no storage permission is requested."
            } else {
                "This image is stored with the bookmark. Replace it or remove it above."
            },
            color = Palette.Muted, fontSize = 9.sp,
        )
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
                        .background(Palette.Navy900)
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

