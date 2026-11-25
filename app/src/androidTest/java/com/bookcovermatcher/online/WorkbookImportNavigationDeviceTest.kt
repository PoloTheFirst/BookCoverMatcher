// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import android.os.SystemClock
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.allViews
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import com.bookcovermatcher.*
import com.bookcovermatcher.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual Compose button and viewer routing without requiring a live cloud account. */
@RunWith(AndroidJUnit4::class)
class WorkbookImportNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun workbook(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            mapOf(
                "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "xl/workbook.xml" to "<workbook xmlns=\"$ns\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Navigation fixture\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"$ns\"><sheetData/></worksheet>",
            ).forEach { (path, text) -> zip.putNextEntry(ZipEntry(path).apply { time = 0L }); zip.write(text.toByteArray()); zip.closeEntry() }
        }
    }.toByteArray()

    @Suppress("UNCHECKED_CAST")
    private fun state(vm: MainViewModel): MutableStateFlow<UiState> = MainViewModel::class.java.getDeclaredField("_ui")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<UiState>

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Condition was not reached", condition())
    }

    private fun findText(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) findText(text, node.getChild(index))?.let { return it }
        return null
    }

    private fun clickUseNow() {
        var clicked = false
        waitUntil {
            if (!clicked) {
                val node = findText("Use it now")
                if (node != null) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
            }
            clicked
        }
    }

    private fun SemanticsNode.withLabel(label: String): SemanticsNode? {
        if (config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) return this
        return children.firstNotNullOfOrNull { it.withLabel(label) }
    }

    private fun ActivityScenario<MainActivity>.onLabel(label: String, action: (SemanticsNode) -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var found = false
            onActivity { activity ->
                val node = activity.window.decorView.allViews.filterIsInstance<RootForTest>()
                    .firstNotNullOfOrNull { it.semanticsOwner.unmergedRootSemanticsNode.withLabel(label) }
                if (node != null) {
                    action(node)
                    found = true
                }
            }
            if (found) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Missing label: $label")
    }

    private fun clickControl(label: SemanticsNode) {
        val button = generateSequence(label) { it.parent }.first { SemanticsActions.OnClick in it.config }
        assertFalse(SemanticsProperties.Disabled in button.config)
        assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
    }

    @Test fun homeOpensTheSelectedWorkbookAndReturnsToHome() {
        val library = WorkbookLibrary(File(context.filesDir, "workbooks"))
        val imported = library.import(workbook().inputStream(), "home-navigation.xlsx")
        val entry = when (imported) {
            is ImportResult.Added -> imported.workbook
            is ImportResult.AlreadySaved -> imported.workbook
            is ImportResult.Rejected -> error(imported.message)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                lateinit var vm: MainViewModel
                screen.onActivity {
                    vm = ViewModelProvider(it)[MainViewModel::class.java]
                    vm.selectTab(AppTab.HOME)
                }
                waitUntil { vm.ui.value.model is ModelUi.Ready }
                screen.onLabel("Choose a workbook above to browse its contents.") { label ->
                    assertTrue(generateSequence(label) { it.parent }.any { SemanticsProperties.Disabled in it.config })
                }
                screen.onActivity { vm.useSavedWorkbook(entry.id) }
                waitUntil { vm.ui.value.workbook != null && !vm.ui.value.workbookLoading }
                screen.onLabel("Workbook comments & images", ::clickControl)
                waitUntil { findText("Workbook comments") != null }
                assertEquals(entry.id, vm.ui.value.openWorkbookId)
                assertEquals(entry.displayName, vm.ui.value.comments.workbookName)
                screen.onActivity { vm.closeWorkbookComments() }
                screen.onLabel("Workbook comments & images") { assertEquals(AppTab.HOME, vm.ui.value.tab) }
            }
        } finally {
            if (imported is ImportResult.Added) library.remove(entry.id)
        }
    }

    @Test fun moreShowsBothLanguageHintsAndStartsWithExpandedAuthor() {
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            screen.onActivity { ViewModelProvider(it)[MainViewModel::class.java].selectTab(AppTab.MORE) }
            screen.onLabel("Leave blank for Lens to detect it, or enter a two-letter code such as ja for Japanese.") {}
            screen.onLabel("Leave blank to keep the original text, or enter a two-letter code such as en for English.") {}
            screen.onLabel("Collapse", ::clickControl)
            screen.onLabel("Expand", ::clickControl)
            screen.onLabel("Collapse") {}
        }
    }

    @Test fun bothCloudButtonsOpenTheExactSavedWorkbookIncludingDuplicates() {
        val library = WorkbookLibrary(File(context.filesDir, "workbooks"))
        val imported = library.import(workbook().inputStream(), "navigation-fixture.xlsx")
        val entry = when (imported) {
            is ImportResult.Added -> imported.workbook
            is ImportResult.AlreadySaved -> imported.workbook
            is ImportResult.Rejected -> error(imported.message)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MainViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
                for (google in listOf(true, false)) for (duplicate in listOf(false, true)) {
                    scenario.onActivity {
                        vm.closeWorkbookComments()
                        val current = state(vm)
                        current.value = current.value.copy(libraryOpen = true,
                            googleDrive = GoogleDriveUi(open = google, imported = if (google) GoogleDriveImportedUi(entry.id, entry.displayName, duplicate) else null),
                            oneDrive = OneDriveUi(open = !google, imported = if (!google) OneDriveImportedUi(entry.id, entry.displayName, duplicate) else null))
                    }
                    try { clickUseNow() } catch (error: AssertionError) {
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(context.getExternalFilesDir(null), "source-ui-test-failure.png").outputStream().use {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                        }
                        throw error
                    }
                    waitUntil { vm.ui.value.comments.open && !vm.ui.value.workbookLoading }
                    assertEquals(entry.id, vm.ui.value.openWorkbookId)
                    assertEquals(entry.displayName, vm.ui.value.comments.workbookName)
                    assertFalse(vm.ui.value.googleDrive.open)
                    assertFalse(vm.ui.value.oneDrive.open)
                    waitUntil { findText("Workbook comments") != null && findText("Use it now") == null }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(context.getExternalFilesDir(null), "source-ui-viewer.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    scenario.onActivity { vm.closeWorkbookComments() }
                    assertTrue(vm.ui.value.libraryOpen)
                }
                scenario.onActivity {
                    val current = state(vm)
                    current.value = current.value.copy(googleDrive = GoogleDriveUi(open = true,
                        imported = GoogleDriveImportedUi("missing-fixture", "Missing", false)))
                }
                clickUseNow()
                waitUntil { !vm.ui.value.workbookLoading && vm.ui.value.toast != null }
                assertFalse(vm.ui.value.comments.open)
                assertTrue(vm.ui.value.googleDrive.open)
            }
        } finally {
            if (imported is ImportResult.Added) library.remove(entry.id)
        }
    }

    @Test fun localGrantStatesDistinguishUnknownAndRevokedAccess() {
        val source = LocalWorkbookSource(context)
        val original = LocalOrigin("content://invalid-test-provider/document/fixture", "Test provider/fixture.xlsx", false)
        assertTrue(source.check(original).text.contains("not retained"))
        assertTrue(source.check(original.copy(retained = true)).text.contains("revoked"))
    }
}
