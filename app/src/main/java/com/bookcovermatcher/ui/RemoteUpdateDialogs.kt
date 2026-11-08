// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.OneDriveDownloadUi
import com.bookcovermatcher.UiState
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.theme.Palette

/**
 * The two dialogs of the explicit remote check (v2.6.0-dev.1 Phase 5).
 *
 * The question comes first and nothing is downloaded while it is on screen; progress replaces it only
 * after the user says yes. Neither dialog decides anything: they call the ViewModel, which owns the
 * operation, its cancellation and the state.
 */
@Composable
fun RemoteUpdateDialogs(ui: UiState, vm: MainViewModel) {
    val state = ui.remoteUpdate
    state.offer?.let { offer ->
        ConfirmUpdateDialog(offer.text, onConfirm = vm::acceptRemoteUpdate, onDismiss = vm::declineRemoteUpdate)
        return
    }
    state.download?.let { download ->
        UpdateProgressDialog(download, vm::cancelRemoteUpdate)
    }
}

@Composable
private fun ConfirmUpdateDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Text(
                "Newer version on OneDrive",
                color = Palette.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(6.dp))
            // The agreed wording, verbatim: the size, the date, and the promise about the current copy.
            Text(text, color = Palette.Muted, fontSize = 10.5.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientButton(
                    onClick = onConfirm,
                    colors = Palette.PrimaryGradient,
                    contentColor = Palette.OnAccent,
                    modifier = Modifier.width(110.dp),
                    minHeight = 36.dp,
                ) { ButtonLabel("Update", Palette.OnAccent, 11f) }
                Spacer(Modifier.width(10.dp))
                GradientButton(
                    onClick = onDismiss,
                    colors = Palette.GreyGradient,
                    contentColor = Palette.Text,
                    modifier = Modifier.width(120.dp),
                    minHeight = 36.dp,
                ) { ButtonLabel("Keep current", Palette.Text, 10.5f) }
            }
        }
    }
}

@Composable
private fun UpdateProgressDialog(download: OneDriveDownloadUi, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Updating the saved copy",
                    color = Palette.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(download.name, color = Palette.Muted, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            val fraction = download.fraction
            val bar = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = bar,
                    color = Palette.Accent,
                    trackColor = Color.White.copy(alpha = 0.09f),
                )
            } else {
                // No size from OneDrive: an indeterminate bar rather than an invented percentage.
                LinearProgressIndicator(
                    modifier = bar,
                    color = Palette.Accent,
                    trackColor = Color.White.copy(alpha = 0.09f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (fraction != null) {
                    "${Formatting.fileSize(download.bytesWritten)} of " +
                        "${Formatting.fileSize(download.totalBytes ?: 0L)}"
                } else {
                    "${Formatting.fileSize(download.bytesWritten)} downloaded \u00B7 size unknown"
                },
                color = Palette.Muted,
                fontSize = 9.sp,
            )
            Spacer(Modifier.height(10.dp))
            GradientButton(
                onClick = onCancel,
                colors = Palette.RedGradient,
                contentColor = Palette.Text,
                modifier = Modifier.width(110.dp),
                minHeight = 36.dp,
            ) { ButtonLabel("Cancel", Palette.Text, 11f) }
        }
    }
}

