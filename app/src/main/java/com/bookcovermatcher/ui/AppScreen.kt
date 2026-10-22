package com.bookcovermatcher.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcovermatcher.AppTab
import com.bookcovermatcher.DeleteKind
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.ModelUi
import com.bookcovermatcher.PendingDeleteUi
import com.bookcovermatcher.ToastKind
import com.bookcovermatcher.ui.theme.Palette

/**
 * Root of the UI: the bottom-navigation shell, the four destinations, and every overlay dialog.
 *
 * Navigation is Compose state ([AppTab]) owned by the ViewModel, so a configuration change or a
 * tab switch never re-creates a repository or drops a session. Persistent data lives in the store,
 * never in this composable.
 */
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
        // Catalogue links go to the system browser: the app never renders third-party pages.
        onOpenUrl = { url -> openExternal(context, url, vm) },
    )

    // Ask the embedded Python runtime whether chrome-lens-py is usable, once, when the feature is on.
    LaunchedEffect(ui.online.enabled) {
        if (ui.online.enabled) vm.online.checkBridge()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val background = Palette.pageBackground(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Box(Modifier.fillMaxSize().background(background)) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { BottomBar(ui.tab, vm::selectTab) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    val ready = ui.model is ModelUi.Ready
                    when (ui.tab) {
                        AppTab.HOME -> ScreenScroll {
                            if (ready) HomeScreen(ui, vm, lifecycleOwner, actions)
                            else SetupScreen(ui.model, onImport = { pickModel.launch(arrayOf("*/*")) })
                        }
                        AppTab.SEARCH -> ScreenScroll {
                            if (ready) SearchScreen(ui, vm, actions)
                            else SetupScreen(ui.model, onImport = { pickModel.launch(arrayOf("*/*")) })
                        }
                        AppTab.WISHLIST -> Box(
                            Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 8.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Column(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                                WishlistScreen(ui, vm, actions.onOpenUrl)
                            }
                        }
                        AppTab.MORE -> ScreenScroll {
                            MoreScreen(ui, vm)
                        }
                    }
                }
            }

            WishlistPrompt(ui.prompt, onRemove = vm::removePromptRecord, onClose = vm::dismissPrompt)
            ToastHost(ui.toast)
            ConfirmDeleteDialog(
                visible = ui.pendingDelete != null,
                onCancel = vm::cancelDelete,
                onConfirm = vm::confirmDelete,
                message = deleteMessage(ui.pendingDelete),
            )
            RenameDialog(ui.renaming, onCancel = vm::cancelRename, onSave = vm::commitRename)
            ProfilePickerDialog(ui.picker, onCancel = vm::closePicker, onConfirm = vm::confirmPicker)
            CustomBookmarkDialog(ui.customBookmark, onCancel = vm::closeCustomBookmark, onCreate = vm::createCustomBookmark)
        }
    }
}

/** Scroll container shared by Home, Search and More. Wishlist manages its own lists. */
@Composable
private fun ScreenScroll(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 32.dp),
        ) { content() }
    }
}

@Composable
private fun BottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B121C))
            .navigationBarsPadding()
            .height(62.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomItem(AppTab.HOME, "Home", Icons.Filled.Home, selected, onSelect, Modifier.weight(1f))
        BottomItem(AppTab.SEARCH, "Search", Icons.Filled.Search, selected, onSelect, Modifier.weight(1f))
        BottomItem(AppTab.WISHLIST, "Wishlist", Icons.Filled.Star, selected, onSelect, Modifier.weight(1f))
        BottomItem(AppTab.MORE, "More", Icons.Filled.MoreVert, selected, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun BottomItem(
    tab: AppTab,
    label: String,
    icon: ImageVector,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier,
) {
    val active = selected == tab
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Palette.Accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(role = Role.Tab) { onSelect(tab) }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Palette.Accent else Palette.Muted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (active) Palette.Accent else Palette.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
    }
}


/** One sentence per kind of removal, so the confirmation never promises the wrong thing. */
private fun deleteMessage(target: PendingDeleteUi?): String = when (target?.kind) {
    DeleteKind.FOLDER ->
        "Delete the folder \u201C${target.title}\u201D? Its sessions are removed. Bookmarks and items shared with other folders stay."
    DeleteKind.SESSION ->
        "Delete the session \u201C${target.title}\u201D? Its results are removed. Bookmarks and other sessions stay."
    DeleteKind.BOOKMARK ->
        "Remove the bookmark \u201C${target.title}\u201D? Any wishlist folder membership stays."
    DeleteKind.MEMBERSHIP ->
        "Remove \u201C${target.title}\u201D from this folder? It stays in Bookmarks and in any other folder."
    null -> ""
}

/**
 * Opens a catalogue page in the user's browser.
 *
 * The app never renders third-party pages itself: no WebView, no extra permission beyond
 * `INTERNET`, and the user sees in the browser which site they landed on.
 */
private fun openExternal(context: Context, url: String, vm: MainViewModel) {
    if (!url.startsWith("https://")) {
        vm.showToast("Refusing to open a non-HTTPS link.", ToastKind.ERR)
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        vm.showToast("No browser on this device.", ToastKind.ERR)
    } catch (e: Exception) {
        vm.showToast("Could not open the link.", ToastKind.ERR)
    }
}

