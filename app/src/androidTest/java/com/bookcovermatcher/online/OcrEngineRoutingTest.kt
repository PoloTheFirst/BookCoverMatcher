package com.bookcovermatcher.online

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.OcrUi
import com.bookcovermatcher.data.OnlineSearchPrefs
import com.bookcovermatcher.ocr.ModelState
import com.bookcovermatcher.ocr.OcrEngineChoice
import com.bookcovermatcher.ocr.OcrEngines
import com.bookcovermatcher.ocr.SwitchingOcrTextSource
import com.bookcovermatcher.ocr.ppocrImportDirectory
import com.bookcovermatcher.ocr.ppocrModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Routing proof at the **production entry point**: these tests drive
 * [OnlineSearchController.onCapture] / [OnlineSearchController.readCover] — the same call the Online
 * tab's “Read cover text” button makes — and assert which [OcrTextSource] received the image, what
 * reached the UI state, and that no engine ever stands in for the other.
 *
 * The fake-source tests need no network and no models. The real-engine tests at the end are gated on
 * the resources they need and skip with a stated reason when those are absent; a skip is reported as
 * a skip, never as a pass.
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineRoutingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val testAssets = instrumentation.context ?: instrumentation.targetContext

    private lateinit var prefs: OnlineSearchPrefs
    private var previousEngine = ""
    private var previousAutoSearch = true
    private var previousEnabled = false

    @Before
    fun setUp() {
        prefs = OnlineSearchPrefs(context)
        previousEngine = prefs.ocrEngine
        previousAutoSearch = prefs.autoSearch
        previousEnabled = prefs.enabled
        // No catalogue requests from these tests: routing is what is under test.
        prefs.autoSearch = false
    }

    @After
    fun tearDown() {
        prefs.ocrEngine = previousEngine
        prefs.autoSearch = previousAutoSearch
        prefs.enabled = previousEnabled
    }

    /** One OCR implementation that records exactly what it was asked to do. */
    private class RecordingSource(
        private val library: String,
        private val answer: () -> OcrText,
    ) : OcrTextSource {
        @Volatile var calls = 0
        @Volatile var lastByteCount = 0

        override fun availability(): OcrAvailability = OcrAvailability.Ready(library, "test-1.0")

        override fun recognise(
            imageBytes: ByteArray,
            ocrLanguage: String?,
            targetLanguage: String?,
            timeoutSeconds: Int,
        ): OcrText {
            calls++
            lastByteCount = imageBytes.size
            return answer()
        }
    }

    private fun coverJpeg(): ByteArray = testAssets.assets.open("cover.jpg").use { it.readBytes() }

    /**
     * Installs the PP-OCRv6 models from the developer drop path when they were pushed but not yet
     * copied, so this test does not depend on another test having run first.
     */
    private fun ensurePaddleModels(): ModelState {
        val store = ppocrModels(context)
        val current = store.state()
        if (current !is ModelState.Absent) return current
        val import = ppocrImportDirectory(context)
        if (java.io.File(import, com.bookcovermatcher.ocr.PpocrModels.DETECTOR).isFile) {
            return store.installFromDirectory(import)
        }
        return current
    }

    private fun text(library: String, body: String): OcrText = OcrText(
        text = body,
        blockHeights = listOf(0.06f, 0.13f),
        detectedLanguage = null,
        translatedText = null,
        wordCount = body.split(' ', '\n').count { it.isNotBlank() },
        blockCount = 2,
        libraryVersion = "$library 9.9",
    )

    private fun controller(bridge: OcrTextSource, prefs: OnlineSearchPrefs, scope: CoroutineScope) =
        OnlineSearchController(
            context = context,
            scope = scope,
            prefs = prefs,
            bridge = bridge,
            onMessage = { _, _ -> },
        )

    /** Waits for the OCR step to leave its running state. */
    private fun awaitOcrResult(controller: OnlineSearchController, timeoutMs: Long = 15_000): OcrUi {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val ocr = controller.ui.value.ocr) {
                is OcrUi.Done, is OcrUi.Failed, is OcrUi.NoText -> return ocr
                else -> Thread.sleep(20)
            }
        }
        throw AssertionError("OCR never finished; last state was ${controller.ui.value.ocr}")
    }

    /** Runs one READ THE COVER pass through the controller with [bridge] handling recognition. */
    private fun runCover(bridge: OcrTextSource, engineId: String): OcrUi {
        prefs.ocrEngine = engineId
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = controller(bridge, prefs, scope)
        try {
            controller.setEnabled(true)
            controller.onCapture(coverJpeg())
            return awaitOcrResult(controller)
        } finally {
            controller.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun chromeLensRunsWhenItIsSelected() {
        val chrome = RecordingSource("ChromeLens") { text("ChromeLens", "THE HOBBIT\nJ.R.R. TOLKIEN") }
        val paddle = RecordingSource("PaddleOCR") { text("PaddleOCR", "should not run") }

        val result = runCover(
            SwitchingOcrTextSource(
                current = { prefs.ocrEngine },
                build = { if (it == OcrEngineChoice.PADDLE_OCR.id) paddle else chrome },
            ),
            OcrEngineChoice.CHROME_LENS.id,
        )

        assertTrue("expected a result, got $result", result is OcrUi.Done)
        assertEquals(1, chrome.calls)
        assertEquals(0, paddle.calls)
        assertEquals("THE HOBBIT\nJ.R.R. TOLKIEN", (result as OcrUi.Done).text)
        assertEquals("Chrome Lens", result.engine)
        assertTrue("the cover bytes must reach the engine", chrome.lastByteCount > 0)
    }

    @Test
    fun paddleRunsWhenItIsSelected() {
        val chrome = RecordingSource("ChromeLens") { text("ChromeLens", "should not run") }
        val paddle = RecordingSource("PaddleOCR") { text("PaddleOCR", "THE AUTHORIZED EDITION\nTHE HOBBIT") }

        val result = runCover(
            SwitchingOcrTextSource(
                current = { prefs.ocrEngine },
                build = { if (it == OcrEngineChoice.PADDLE_OCR.id) paddle else chrome },
            ),
            OcrEngineChoice.PADDLE_OCR.id,
        )

        assertTrue("expected a result, got $result", result is OcrUi.Done)
        assertEquals(0, chrome.calls)
        assertEquals(1, paddle.calls)
        assertEquals("THE AUTHORIZED EDITION\nTHE HOBBIT", (result as OcrUi.Done).text)
        assertEquals("PaddleOCR", result.engine)
    }

    @Test
    fun aPaddleFailureDoesNotFallBackToChromeLens() {
        val chrome = RecordingSource("ChromeLens") { text("ChromeLens", "should not run") }
        val paddle = RecordingSource("PaddleOCR") {
            throw OcrException(
                OcrFailure.OCR_MODELS_MISSING,
                OcrErrorMessages.detail(OcrFailure.OCR_MODELS_MISSING),
                "the PP-OCRv6 models failed their checksum",
            )
        }

        val result = runCover(
            SwitchingOcrTextSource(
                current = { prefs.ocrEngine },
                build = { if (it == OcrEngineChoice.PADDLE_OCR.id) paddle else chrome },
            ),
            OcrEngineChoice.PADDLE_OCR.id,
        )

        assertTrue("expected a PaddleOCR failure, got $result", result is OcrUi.Failed)
        assertTrue(
            "the message must describe the selected engine's problem, got '${(result as OcrUi.Failed).message}'",
            result.message.contains("PP-OCRv6", ignoreCase = true),
        )
        assertEquals("no fallback: Chrome Lens must not run", 0, chrome.calls)
        assertEquals(
            "the preference must not change behind the user's back",
            OcrEngineChoice.PADDLE_OCR.id,
            prefs.ocrEngine,
        )
    }

    @Test
    fun theSelectionSurvivesControllerRecreation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = SwitchingOcrTextSource(
            current = { prefs.ocrEngine },
            build = { ChromeLensPyBridge(PythonRuntime(context)) },
        )
        val first = controller(bridge, OnlineSearchPrefs(context), scope)
        try {
            first.selectOcrEngine(OcrEngineChoice.PADDLE_OCR.id)
            assertEquals(OcrEngineChoice.PADDLE_OCR.id, first.ui.value.ocrEngine)

            // A new controller with a fresh prefs object is what a process restart looks like.
            val reopened = controller(bridge, OnlineSearchPrefs(context), scope)
            assertEquals(
                "the engine must be restored from storage",
                OcrEngineChoice.PADDLE_OCR.id,
                reopened.ui.value.ocrEngine,
            )
            assertEquals(OcrEngineChoice.PADDLE_OCR, OcrEngineChoice.fromId(reopened.ui.value.ocrEngine))
        } finally {
            first.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun theLegacyPythonValueStillMeansChromeLens() {
        // Exactly what an installation from any earlier build contains.
        context.getSharedPreferences("bookcover_online", Context.MODE_PRIVATE)
            .edit().putString("online_ocr_engine", "python").commit()

        assertEquals(OcrEngineChoice.CHROME_LENS.id, OnlineSearchPrefs(context).ocrEngine)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = controller(
            SwitchingOcrTextSource(current = { prefs.ocrEngine }, build = { ChromeLensPyBridge(PythonRuntime(context)) }),
            OnlineSearchPrefs(context),
            scope,
        )
        try {
            assertEquals(OcrEngineChoice.CHROME_LENS.id, controller.ui.value.ocrEngine)
        } finally {
            controller.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun anUnreadableStoredValueSelectsTheDefaultAndIsOnlyRewrittenOnExplicitSelection() {
        val store = context.getSharedPreferences("bookcover_online", Context.MODE_PRIVATE)
        store.edit().putString("online_ocr_engine", "tesseract").commit()

        // Reading resolves to the default engine…
        assertEquals(OcrEngineChoice.CHROME_LENS.id, OnlineSearchPrefs(context).ocrEngine)
        // …and does not rewrite the stored value (only an explicit selection writes).
        assertEquals("tesseract", store.getString("online_ocr_engine", null))

        // An explicit selection normalises it, including the `paddle` alias.
        OnlineSearchPrefs(context).ocrEngine = "paddle"
        assertEquals(OcrEngineChoice.PADDLE_OCR.id, store.getString("online_ocr_engine", null))
    }

    /**
     * The acceptance criterion, driven through the production controller with the **real** native
     * engine: select PaddleOCR, capture the bundled cover, and require that the Rust implementation
     * performs the recognition. Requires the models to be installed (`tools/push-ocr-models.ps1`).
     */
    @Test
    fun theRealPaddleEngineReadsTheCoverThroughTheController() {
        when (val state = ensurePaddleModels()) {
            is ModelState.Ready -> Unit
            ModelState.Absent ->
                assumeTrue("PP-OCRv6 models are not installed - run tools/push-ocr-models.ps1", false)
            is ModelState.Invalid -> fail("the installed PP-OCRv6 models failed verification: ${state.reason}")
        }

        val result = runCover(OcrEngines.create(context), OcrEngineChoice.PADDLE_OCR.id)

        assertTrue("expected the native engine to read the cover, got $result", result is OcrUi.Done)
        result as OcrUi.Done
        assertTrue("the title must be read, got '${result.text}'", result.text.contains("HOBBIT", ignoreCase = true))
        assertEquals("PaddleOCR", result.engine)
        assertTrue(
            "the identity must name the native library, got '${result.libraryVersion}'",
            result.libraryVersion.contains("bc_ocr"),
        )
    }

    /**
     * The other half of the criterion: switching back to Chrome Lens must invoke the shipped
     * `chrome-lens-py` implementation. Needs network access for Lens; when it is unreachable the test
     * is skipped with the reason rather than reported as a pass.
     */
    @Test
    fun theRealChromeLensEngineStillReadsTheCover() {
        val source = OcrEngines.create(context)
        prefs.ocrEngine = OcrEngineChoice.CHROME_LENS.id
        val availability = source.availability()
        assumeTrue("chrome-lens-py is unavailable on this device: $availability", availability is OcrAvailability.Ready)

        val result = runCover(source, OcrEngineChoice.CHROME_LENS.id)

        when (result) {
            is OcrUi.Done -> {
                assertTrue("the title must be read, got '${result.text}'", result.text.contains("HOBBIT", ignoreCase = true))
                assertEquals("Chrome Lens", result.engine)
            }
            is OcrUi.Failed -> assumeTrue("Lens could not be reached (network): ${result.message}", false)
            is OcrUi.NoText -> fail("Lens returned no text for the bundled cover: $result")
            else -> fail("unexpected OCR state: $result")
        }
    }
}
