// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.OneDriveDownloadUi
import com.bookcovermatcher.OneDriveUi
import com.bookcovermatcher.UiState
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.core.remote.RemoteItem
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The OneDrive import surface (v2.6.0-dev.1 Phase 4).
 *
 * A dialog rather than a destination, so the Saved workbooks screen stays underneath and Back needs no
 * new route (the same reasoning as `SavedWorkbookSourceDialog`). It draws state and calls ViewModel
 * functions — it never talks to MSAL, to Graph, or to a file.
 *
 * Three panels: sign-in when there is no account, the folder browser, and the "saved" result with
 * **Use it now**. Rows carry the remote **id**; the name is only ever drawn.
 */
@Composable
fun OneDriveBrowserDialog(ui: UiState, vm: MainViewModel) {
    val state = ui.oneDrive
    if (!state.open) return
    val activity = LocalContext.current.findActivity()

    Dialog(onDismissRequest = vm::closeOneDriveBrowser) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Header(state, vm)
            Spacer(Modifier.height(8.dp))
            when {
                state.imported != null -> ImportedPanel(state, vm)
                state.needsSignIn -> SignInPanel(state, vm, activity)
                else -> BrowserPanel(state, vm)
            }
        }
    }
}

@Composable
private fun Header(state: OneDriveUi, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("From OneDrive", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                state.accountLabel?.let { "Signed in as $it" } ?: "Personal Microsoft accounts only",
                color = Palette.Muted,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
        if (state.signedIn && state.imported == null) {
            TextAction("Sign out", Palette.Muted) { vm.signOutOfOneDrive() }
            Spacer(Modifier.width(10.dp))
        }
        TextAction("Close", Palette.Muted) { vm.closeOneDriveBrowser() }
    }
}

@Composable
private fun SignInPanel(state: OneDriveUi, vm: MainViewModel, activity: Activity?) {
    Text(
        "BookCoverMatcher copies the workbook from your OneDrive into its own storage. The OneDrive " +
            "file is never modified, and the app asks only for read access.",
        color = Palette.Muted,
        fontSize = 10.sp,
    )
    Spacer(Modifier.height(10.dp))
    if (state.error != null) {
        Message(state.error, Palette.Warn)
        Spacer(Modifier.height(8.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientButton(
            onClick = { vm.signInToOneDrive(activity) },
            colors = Palette.PrimaryGradient,
            contentColor = Palette.OnAccent,
            modifier = Modifier.width(140.dp),
            minHeight = 36.dp,
            enabled = !state.signingIn,
        ) {
            if (state.signingIn) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Palette.OnAccent, strokeWidth = 2.dp)
            } else {
                ButtonLabel("Sign in", Palette.OnAccent, 11f)
            }
        }
        if (state.signingIn) {
            Spacer(Modifier.width(10.dp))
            Text("Waiting for the browser\u2026", color = Palette.Muted, fontSize = 9.5.sp)
        }
    }
}

@Composable
private fun BrowserPanel(state: OneDriveUi, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Breadcrumb(state, vm)
        Spacer(Modifier.height(6.dp))

        val download = state.download
        when {
            download != null -> DownloadPanel(download, vm)
            state.busy -> LoadingRow("Reading the folder\u2026")
            state.error != null -> ErrorPanel(state, vm)
            state.emptyFolder -> Message(
                "This folder has no .xlsx or .xlsm workbooks. Folders are always listed.",
                Palette.Muted,
            )
            else -> Unit
        }

        if (download == null && state.error == null && !state.busy) {
            if (state.browse.rows.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.browse.rows, key = { it.id }) { item ->
                        RemoteRow(item) {
                            // The id decides the destination; the name is never used to build one.
                            if (item.isFolder) vm.openOneDriveFolder(item.id) else vm.importOneDriveFile(item.id)
                        }
                    }
                }
            }
            if (state.browse.hasMorePages) {
                Spacer(Modifier.height(6.dp))
                if (state.loadingMore) {
                    LoadingRow("Loading more\u2026")
                } else {
                    TextAction("Load more", Palette.Accent) { vm.loadMoreOneDrive() }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Only .xlsx and .xlsm files can be imported. The workbook is copied; the OneDrive file is untouched.",
            color = Palette.Muted,
            fontSize = 8.5.sp,
        )
    }
}

@Composable
private fun Breadcrumb(state: OneDriveUi, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextAction(
            "\u2191 Up",
            if (state.browse.canGoUp) Palette.Accent else Palette.Line,
            enabled = state.browse.canGoUp && !state.busy && !state.downloading,
        ) { vm.oneDriveUp() }
        Spacer(Modifier.width(10.dp))
        Text(
            state.browse.path.joinToString(" / ") { it.name },
            color = Palette.Text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DownloadPanel(download: OneDriveDownloadUi, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("Downloading ${download.name}", color = Palette.Text, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(6.dp))
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
            // No size from OneDrive: the bar stays indeterminate rather than inventing a percentage.
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
        Spacer(Modifier.height(8.dp))
        TextAction("Cancel", Palette.Danger) { vm.cancelOneDriveDownload() }
    }
}

@Composable
private fun ErrorPanel(state: OneDriveUi, vm: MainViewModel) {
    Message(state.error.orEmpty(), Palette.Warn)
    if (state.retryVisible) {
        Spacer(Modifier.height(6.dp))
        TextAction("Retry", Palette.Accent) { vm.retryOneDrive() }
    }
}


@Composable
private fun ImportedPanel(state: OneDriveUi, vm: MainViewModel) {
    val imported = state.imported ?: return
    Text(
        if (imported.alreadySaved) "Already in your Saved workbooks" else "Saved to your workbooks",
        color = Palette.Ok,
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(imported.displayName, color = Palette.Text, fontSize = 11.sp, maxLines = 2)
    Text(
        if (imported.alreadySaved) {
            "The same file was already saved; its OneDrive source is now recorded."
        } else {
            "A private copy is on this device. Opening it never needs a connection."
        },
        color = Palette.Muted,
        fontSize = 9.sp,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientButton(
            onClick = vm::useOneDriveImportNow,
            colors = Palette.PrimaryGradient,
            contentColor = Palette.OnAccent,
            modifier = Modifier.width(120.dp),
            minHeight = 36.dp,
        ) { ButtonLabel("Use it now", Palette.OnAccent, 10.5f) }
        Spacer(Modifier.width(10.dp))
        TextAction("Done", Palette.Muted) { vm.dismissOneDriveImport() }
    }
}

@Composable
private fun RemoteRow(item: RemoteItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Palette.Navy700)
            .border(1.dp, Palette.Line, RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (item.isFolder) "\uD83D\uDCC1 ${item.name}" else item.name,
                color = Palette.Text,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            val detail = if (item.isFolder) {
                "Folder"
            } else {
                listOfNotNull(
                    item.sizeBytes?.let { Formatting.fileSize(it) },
                    item.lastModified?.let { remoteStamp(it) },
                ).joinToString(" \u00B7 ").ifBlank { "Workbook" }
            }
            Text(detail, color = Palette.Muted, fontSize = 9.sp, maxLines = 1)
        }
        Text(
            if (item.isFolder) "Open" else "Import",
            color = Palette.Accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Palette.Muted, fontSize = 10.sp)
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Palette.Navy900).padding(9.dp),
    ) {
        Text(text, color = color, fontSize = 9.5.sp)
    }
}

@Composable
private fun TextAction(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** `2026-11-08 12:34` — the remote modified time, in the device's own zone. */
private fun remoteStamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** The Activity MSAL needs in order to launch the sign-in browser and receive the redirect. */
private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

