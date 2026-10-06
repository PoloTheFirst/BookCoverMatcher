package com.bookcovermatcher.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.ModelUi
import com.bookcovermatcher.ui.theme.Palette

/** Root of the UI: page background, either the main screen or the model setup, and all overlays. */
@Composable
fun AppScreen(vm: MainViewModel, lifecycleOwner: LifecycleOwner) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ---- camera permission ----
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onCameraPermission(granted)
    }
    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun ensureCamera(announce: Boolean) {
        vm.noteCameraStartIntent(announce)
        if (hasCameraPermission()) vm.onCameraPermission(true) else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ---- pickers ----
    val pickWorkbook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onWorkbookPicked(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onImagePicked(uri)
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    // When the camera permission was granted earlier, start the viewfinder as soon as the model is ready.
    val modelReady = ui.model is ModelUi.Ready
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(modelReady) {
        if (modelReady && !autoStarted) {
            autoStarted = true
            if (hasCameraPermission()) ensureCamera(false)
        }
    }

    val actions = ScreenActions(
        onScan = { if (!ui.cameraWanted) ensureCamera(true) else vm.captureManual() },
        onToggleAuto = {
            if (!ui.isBusy) {
                val turningOn = !ui.autoScan
                vm.toggleAutoScan()
                if (turningOn && !ui.cameraWanted) ensureCamera(false)
            }
        },
        onRestart = { ensureCamera(false) },
        onPickWorkbook = { pickWorkbook.launch(arrayOf("*/*")) },
        onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val background = Palette.pageBackground(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Box(Modifier.fillMaxSize().background(background)) {

            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 48.dp),
                ) {
                    if (ui.model is ModelUi.Ready) {
                        MainContent(ui, vm, lifecycleOwner, actions)
                    } else {
                        SetupScreen(ui.model, onImport = { pickModel.launch(arrayOf("*/*")) })
                    }
                }
            }

            WishlistOverlay(ui, vm)
            WishlistPrompt(ui.prompt, onRemove = vm::removePromptRecord, onClose = vm::dismissPrompt)
            ToastHost(ui.toast)
            ConfirmDeleteDialog(
                visible = ui.pendingDeleteId != null,
                onCancel = vm::cancelDelete,
                onConfirm = vm::confirmDelete,
            )
            RenameDialog(ui.renaming, onCancel = vm::cancelRename, onSave = vm::commitRename)
        }
    }
}
