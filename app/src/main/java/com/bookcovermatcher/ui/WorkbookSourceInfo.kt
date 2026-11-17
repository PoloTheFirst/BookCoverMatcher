// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.UiState
import com.bookcovermatcher.data.*
import com.bookcovermatcher.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

@Composable
fun WorkbookSourceInfo(entry: SavedWorkbook, ui: UiState, vm: MainViewModel) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var confirmAccount by rememberSaveable(entry.id) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.relinkLocalWorkbook(entry.id, uri)
    }
    val google = entry.origin == WorkbookOrigin.GOOGLE_DRIVE
    val local = entry.origin == WorkbookOrigin.LOCAL
    val account = if (google) ui.googleAccount else ui.microsoftAccount
    val signedIn = if (google) ui.googleDrive.signedIn else ui.oneDrive.signedIn
    val provider = when (entry.origin) {
        WorkbookOrigin.LOCAL -> "Device / document provider"
        WorkbookOrigin.ONEDRIVE -> "OneDrive"
        WorkbookOrigin.GOOGLE_DRIVE -> "Google Drive"
    }
    val label = if (local) provider else "$provider · ${entry.remote?.accountLabel ?: "Account not recorded"}"
    val problem = WorkbookSourceStatus.accountProblem(entry, account, signedIn)
    val observation = ui.sourceChecks[entry.id]
    val status = problem ?: observation?.text ?: if (local) {
        if (entry.local == null) "Unlinked · original location not recorded" else "Linked · not checked this session"
    } else "Linked · not checked this session"
    val location = if (local) entry.local?.location ?: "Original location not recorded"
        else entry.remote?.path?.takeIf { it.isNotBlank() } ?: "Drive path unavailable"

    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Show source details") {
        expanded = true
    }.padding(vertical = 6.dp), horizontalAlignment = Alignment.End) {
        Text(label, color = Palette.Text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, color = Palette.Muted, fontSize = 10.sp)
        Text(location, color = Palette.Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Source details", color = Palette.Accent, fontSize = 10.sp)
    }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text("Workbook source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(entry.displayName)
                Spacer(Modifier.height(8.dp))
                Text(label)
                Text(status)
                Text("Last known location: $location")
                observation?.checkedAt?.let { Text("Last attempt: ${DateFormat.getDateTimeInstance().format(Date(it))}") }
                entry.remote?.checkedAt?.let { Text("Last verified: ${DateFormat.getDateTimeInstance().format(Date(it))}") }
                Text("The saved copy remains available independently of the original.")
                TextButton(onClick = { vm.checkWorkbookSource(entry.id) }, enabled = observation?.text != "Checking source…") { Text("Check source") }
                if (local) TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ui.libraryBusy) { Text("Re-link original file") }
                else {
                    TextButton(onClick = { expanded = false; vm.chooseCloudSource(entry.id) }) { Text("Choose source file to re-link") }
                    if (!signedIn || problem == "Different account signed in") TextButton(onClick = {
                        expanded = false
                        if (google) vm.openGoogleDriveBrowser() else vm.openOneDriveBrowser()
                    }) { Text("Open $provider sign-in") }
                    if (entry.remote != null && entry.remote.accountKey == null && account != null && signedIn)
                        TextButton(onClick = { confirmAccount = true }) { Text("Confirm source account") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } },
    )
    if (confirmAccount) AlertDialog(
        onDismissRequest = { confirmAccount = false },
        title = { Text("Confirm original account") },
        text = { Text("Only continue if ${account?.label ?: "this account"} is the account used to import this workbook. The app will verify access to its recorded file before saving this association. Saved workbook bytes will not change.") },
        confirmButton = { TextButton(onClick = { confirmAccount = false; vm.confirmSourceAccount(entry.id) }) { Text("Confirm and verify") } },
        dismissButton = { TextButton(onClick = { confirmAccount = false }) { Text("Cancel") } },
    )
}

@Composable
fun CloudImportLabel(provider: String, label: String?, signedIn: Boolean, expired: Boolean, signingIn: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("From $provider", color = Palette.Text, fontSize = 11.sp)
        Text(when {
            signingIn -> "Signing in…"
            expired -> "Session expired"
            !signedIn -> "Not signed in"
            else -> label ?: "Account unavailable"
        }, color = Palette.Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
