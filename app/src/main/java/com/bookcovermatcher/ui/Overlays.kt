package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.PromptUi
import com.bookcovermatcher.RenameUi
import com.bookcovermatcher.ToastKind
import com.bookcovermatcher.ToastUi
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.components.rememberLastNonNull
import com.bookcovermatcher.ui.theme.Palette

/** The green "Record added to wishlist" bar at the top, with Remove and close. */
@Composable
fun WishlistPrompt(prompt: PromptUi?, onRemove: () -> Unit, onClose: () -> Unit) {
    val shown = rememberLastNonNull(prompt)
    Box(
        Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp, start = 12.dp, end = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = prompt != null,
            enter = slideInVertically { -it * 2 } + fadeIn(),
            exit = slideOutVertically { -it * 2 } + fadeOut(),
        ) {
            if (shown != null) {
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .clip(shape)
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(Palette.GreenGradient), shape)
                        .border(1.dp, Palette.Ok.copy(alpha = 0.65f), shape)
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(shown.text, color = Color(0xFFEAFFF2), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (shown.recordId != null) {
                        GradientButton(
                            onClick = onRemove,
                            colors = Palette.RedGradient,
                            contentColor = Color.White,
                            minHeight = 34.dp,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = 12.dp,
                        ) { ButtonLabel("Remove", Color.White, 12.5f) }
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onClose)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) { Text("✕", color = Color.White, fontSize = 16.sp) }
                }
            }
        }
    }
}

/** Bottom message bar (`#toast`). */
@Composable
fun ToastHost(toast: ToastUi?) {
    val shown = rememberLastNonNull(toast)
    Box(
        Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 18.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { it * 2 } + fadeIn(),
            exit = slideOutVertically { it * 2 } + fadeOut(),
        ) {
            if (shown != null) {
                val shape = RoundedCornerShape(12.dp)
                val border = when (shown.kind) {
                    ToastKind.ERR -> Palette.Danger.copy(alpha = 0.6f)
                    ToastKind.OK -> Palette.Ok.copy(alpha = 0.55f)
                    ToastKind.INFO -> Palette.Line
                }
                Text(
                    shown.text,
                    modifier = Modifier
                        .clip(shape)
                        .background(Color(0xF7001E37), shape)
                        .border(BorderStroke(1.dp, border), shape)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    color = if (shown.kind == ToastKind.ERR) Color(0xFFFFD9D9) else Palette.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Blocking "Do you really want to delete this record?" dialog: it can only be answered, not dismissed. */
@Composable
fun ConfirmDeleteDialog(visible: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    if (!visible) return
    BackHandler(enabled = true) { }
    Scrim {
        DialogCard {
            Text(
                "Do you really want to delete this record?",
                modifier = Modifier.fillMaxWidth(),
                color = Palette.Text,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(onCancel, Palette.GreyGradient, Color(0xFFEAEEF5), Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Cancel", Color(0xFFEAEEF5))
                }
                GradientButton(onConfirm, Palette.RedGradient, Color.White, Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Confirm", Color.White)
                }
            }
        }
    }
}

/** Replaces v1's `window.prompt('Rename record')`. */
@Composable
fun RenameDialog(renaming: RenameUi?, onCancel: () -> Unit, onSave: (id: String, name: String) -> Unit) {
    val target = renaming ?: return
    BackHandler(enabled = true) { onCancel() }

    var value by remember(target.recordId) {
        mutableStateOf(TextFieldValue(target.currentName, TextRange(0, target.currentName.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(target.recordId) { focus.requestFocus() }

    Scrim(Modifier.imePadding()) {
        DialogCard {
            Text("Rename record", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            val shape = RoundedCornerShape(12.dp)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(target.recordId, value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(Color.Black.copy(alpha = 0.3f))
                            .border(1.dp, Palette.Accent, shape)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) { inner() }
                },
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(onCancel, Palette.GreyGradient, Color(0xFFEAEEF5), Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Cancel", Color(0xFFEAEEF5))
                }
                GradientButton(
                    { onSave(target.recordId, value.text) },
                    Palette.AccentGradient,
                    Palette.OnAccent2,
                    Modifier.weight(1f),
                    minHeight = 46.dp,
                ) { ButtonLabel("Save", Palette.OnAccent2) }
            }
        }
    }
}

@Composable
private fun Scrim(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DialogCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .padding(20.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF1A1F2B), shape)
            .border(1.dp, Color(0xFF2A3444), shape)
            .padding(18.dp),
    ) { content() }
}
