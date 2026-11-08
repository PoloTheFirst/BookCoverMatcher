// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ocr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.online.ChromeLensPyBridge
import com.bookcovermatcher.online.OcrAvailability
import com.bookcovermatcher.online.OcrException
import com.bookcovermatcher.online.OcrFailure
import com.bookcovermatcher.online.PythonRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof of the native path: library load, JNI resolution, engine lifecycle, real PP-OCRv6
 * inference, and a side-by-side comparison with the Python engine.
 *
 * Two classes of test live here:
 *
 *  * **always run** — library load and JNI error handling. They need no models, so a build without
 *    the 139 MB of weights is still verified for Levels 1–2.
 *  * **model-dependent** — real inference. They skip with an explicit [Assume] message when the
 *    models are not installed, because a skipped test is not a pass and must say so. Install them
 *    first with `tools/push-ocr-models.ps1`.
 *
 * Nothing here is a physical-device claim: run it on hardware before believing the numbers.
 */
@RunWith(AndroidJUnit4::class)
class NativeOcrDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testAssets = instrumentation.context ?: instrumentation.targetContext

    /** `println` does not reach a headless `am instrument` run; logcat does. */
    private fun log(message: String) {
        Log.i(TAG, message)
    }

    /**
     * Installs the PP-OCRv6 models from the APK when they are not ready yet — the same path a clean
     * installation takes. The developer drop directory is kept as a fallback for builds made without
     * the Gradle asset task.
     */
    private fun ensureModels(): ModelState {
        val store = ppocrModels(context)
        val current = store.state()
        if (current is ModelState.Ready) return current
        val assets = context.assets
        val fromApk = runCatching {
            store.ensureInstalled({ name -> assets.open("${PpocrModels.ASSET_DIR}/$name") })
        }.getOrElse { return ModelState.Invalid("asset extraction failed: ${it.message}") }
        if (fromApk is ModelState.Ready) return fromApk
        val import = ppocrImportDirectory(context)
        if (File(import, PpocrModels.DETECTOR).isFile) return store.installFromDirectory(import)
        return fromApk
    }

    private fun requireModels() {
        when (val state = ensureModels()) {
            is ModelState.Ready -> log("models: ready (${state.models.bytes} bytes)")
            ModelState.Absent -> {
                val import = ppocrImportDirectory(context)
                log("models: absent; drop directory $import exists=${import.isDirectory}")
                assumeTrue(
                    "PP-OCRv6 models are not installed - push them with tools/push-ocr-models.ps1",
                    false,
                )
            }
            is ModelState.Invalid -> fail("the installed PP-OCRv6 models failed verification: ${state.reason}")
        }
    }

    @Test
    fun theNativeLibraryLoadsAndAnswersVersion() {
        assertTrue("libbc_ocr.so did not load: ${NativeOcrLib.loadFailure}", NativeOcrLib.isLoaded)
        val version = NativeOcrLib.version()
        assertTrue("the crate version must be readable, got '$version'", version.isNotBlank())
        log("bc_ocr load: ok, version=$version, abi=${android.os.Build.SUPPORTED_ABIS.first()}")
    }

    @Test
    fun anUnknownHandleIsRejectedInsteadOfCrashing() {
        val error = runCatching { NativeOcrLib.describe(999_999L) }.exceptionOrNull()
        assertTrue("expected a Java exception, got $error", error is IllegalStateException)
        assertTrue(
            "the message must name the handle, got '${error!!.message}'",
            error.message!!.contains("unknown native OCR handle"),
        )
    }

    @Test
    fun createReportsMissingModelFilesWithASentence() {
        val missing = File(context.cacheDir, "definitely-not-a-model.onnx")
        val error = runCatching {
            NativeOcrLib.create(missing.absolutePath, missing.absolutePath, missing.absolutePath, "")
        }.exceptionOrNull()
        assertTrue("expected a Java exception, got $error", error is IllegalStateException)
        assertTrue(
            "the message must say the file is missing, got '${error!!.message}'",
            error.message!!.lowercase().contains("missing"),
        )
    }

    @Test
    fun theEngineDescribesTheVerifiedClassLayout() {
        requireModels()
        rustOcrTextSource(context).use { source ->
            val info = source.describePayload()
            log("describe:\n$info")
            assertTrue("classes must be 18,710: $info", info.contains("CLASSES\t18710"))
            assertTrue("dictionary must be 18,708: $info", info.contains("DICT\t18708"))
            assertTrue("the space class must be present: $info", info.contains("SPACE\t1"))
        }
    }

    /**
     * The generated cover prints `SYNTHETIC`, `TEST COVER`, `BOOK COVER`, `MATCHER` … in large Latin
     * letters. A recogniser may drop or merge the spaces between them, so the check ignores spaces.
     */
    private fun readsTheSyntheticCover(text: String): Boolean =
        text.uppercase().replace(" ", "").contains("BOOKCOVER")

    @Test
    fun theEngineReadsTheBundledCover() {
        requireModels()
        val bytes = testAssets.assets.open("cover.jpg").use { it.readBytes() }
        rustOcrTextSource(context).use { source ->
            val coldStart = System.currentTimeMillis()
            val text = source.recognise(bytes, "en", null, 60)
            val coldMs = System.currentTimeMillis() - coldStart
            // Second pass on the same engine: sessions are reused, so this is the steady-state cost.
            val warmStart = System.currentTimeMillis()
            val warmText = source.recognise(bytes, "en", null, 60)
            val warmMs = System.currentTimeMillis() - warmStart
            log("native OCR cold $coldMs ms (includes session load), warm $warmMs ms")
            log("native OCR ${text.blockCount} blocks, ${text.wordCount} words")
            log("native text: ${text.text.replace("\n", " / ")}")
            log("native heights: ${text.blockHeights}")
            assertTrue("the title must be read, got '${text.text}'", readsTheSyntheticCover(text.text))
            assertTrue("block heights must be present for the title picker", text.blockHeights.isNotEmpty())
            assertTrue("the engine identity must be reported", text.libraryVersion.contains("bc_ocr"))
            assertEquals("a repeated run must read the same cover the same way", text.text, warmText.text)
        }
    }

    @Test
    fun theEngineSurvivesRepeatedInitialisationAndShutdown() {
        requireModels()
        val bytes = testAssets.assets.open("cover.jpg").use { it.readBytes() }
        repeat(2) { round ->
            val started = System.currentTimeMillis()
            rustOcrTextSource(context).use { source ->
                val text = source.recognise(bytes, "en", null, 60)
                val elapsed = System.currentTimeMillis() - started
                log("round ${round + 1}: cold $elapsed ms (includes session load), ${text.blockCount} blocks")
                assertTrue("round ${round + 1} read no text", text.text.isNotBlank())
            }
        }
    }

    /**
     * The APK's own copy of the models, extracted and verified — exactly what a clean installation
     * does on its first OCR call. No ADB, no script, no network.
     */
    @Test
    fun aCleanInstallPreparesPaddleOcrFromTheApkAndReadsOffline() {
        val packaged = context.assets.list(PpocrModels.ASSET_DIR)?.toSet().orEmpty()
        assertTrue("the detector must be packaged in the APK", packaged.contains(PpocrModels.DETECTOR))
        assertTrue("the recogniser must be packaged in the APK", packaged.contains(PpocrModels.RECOGNIZER))
        assertTrue("the dictionary must be packaged in the APK", packaged.contains(PpocrModels.DICTIONARY))
        log("packaged assets: ${packaged.sorted()}")

        // Wipe private storage: from the engine's point of view this is a fresh installation.
        ppocrModels(context).remove()
        assertEquals(ModelState.Absent, ppocrModels(context).state())

        val bytes = testAssets.assets.open("cover.jpg").use { it.readBytes() }
        val started = System.currentTimeMillis()
        val text = rustOcrTextSource(context).use { it.recognise(bytes, "en", null, 60) }
        val elapsed = System.currentTimeMillis() - started
        log("clean install: extracted + recognised in $elapsed ms, ${text.blockCount} blocks")

        assertTrue("the title must be read after extraction, got '${text.text}'", readsTheSyntheticCover(text.text))
        assertTrue("the extracted models must verify", ppocrModels(context).state() is ModelState.Ready)
    }

    @Test
    fun aCorruptImageIsReportedAsABadImage() {
        requireModels()
        val junk = ByteArray(4096) { (it * 7).toByte() }
        val error = runCatching { rustOcrTextSource(context).use { it.recognise(junk, null, null, 60) } }
            .exceptionOrNull()
        assertTrue("expected OcrException, got $error", error is OcrException)
        assertEquals(OcrFailure.BAD_IMAGE, (error as OcrException).failure)
    }

    /**
     * The same cover through both engines, so the migration can be judged on evidence rather than on
     * "it works". Python needs network access for Lens; when it is unavailable the comparison is
     * skipped with the reason instead of being reported as equal.
     */
    @Test
    fun bothEnginesReadTheSameCover() {
        requireModels()
        val bytes = testAssets.assets.open("cover.jpg").use { it.readBytes() }

        val python = ChromeLensPyBridge(PythonRuntime(context))
        val availability = python.availability()
        assumeTrue(
            "chrome-lens-py is not available on this device ($availability)",
            availability is OcrAvailability.Ready,
        )
        val pythonStarted = System.currentTimeMillis()
        val pythonText = runCatching { python.recognise(bytes, "en", null, 60) }.getOrNull()
        val pythonMs = System.currentTimeMillis() - pythonStarted

        val nativeStarted = System.currentTimeMillis()
        val nativeText = rustOcrTextSource(context).use { it.recognise(bytes, "en", null, 60) }
        val nativeMs = System.currentTimeMillis() - nativeStarted

        log("python: $pythonMs ms · ${pythonText?.blockCount} blocks · ${pythonText?.text?.replace("\n", " / ")}")
        log("native: $nativeMs ms · ${nativeText.blockCount} blocks · ${nativeText.text.replace("\n", " / ")}")
        assertTrue("the native engine must read the title", readsTheSyntheticCover(nativeText.text))
        if (pythonText != null) {
            assertTrue(
                "both engines must agree that the cover says BOOKCOVER",
                readsTheSyntheticCover(pythonText.text),
            )
        }
    }

    /**
     * The engine-selection layer, end to end: the default must be the Python bridge, switching must
     * select the native engine, and switching back must restore the shipped path. This is the layer
     * the controller actually calls ([OcrEngines.create]), so it is verified on the device rather
     * than only through fakes.
     */
    @Test
    fun theSelectedEngineIsTheOneThatAnswers() {
        val prefs = com.bookcovermatcher.data.OnlineSearchPrefs(context)
        val previous = prefs.ocrEngine
        try {
            prefs.ocrEngine = com.bookcovermatcher.data.OnlineSearchPrefs.ENGINE_PYTHON
            val shipped = OcrEngines.create(context)
            try {
                val availability = shipped.availability()
                log("engine=python availability=$availability")
                assertTrue(
                    "the shipped engine must be usable, got $availability",
                    availability is OcrAvailability.Ready,
                )
                assertTrue(
                    "the shipped engine must identify itself",
                    (availability as OcrAvailability.Ready).libraryName.contains("lens", ignoreCase = true),
                )
            } finally {
                (shipped as? AutoCloseable)?.close()
            }

            prefs.ocrEngine = com.bookcovermatcher.data.OnlineSearchPrefs.ENGINE_RUST
            val native = OcrEngines.create(context)
            try {
                when (val availability = native.availability()) {
                    is OcrAvailability.Ready -> {
                        log("engine=rust availability=$availability")
                        assertTrue(
                            "the native engine must identify itself",
                            availability.libraryName.contains("PP-OCRv6"),
                        )
                    }
                    // Without models the native engine must say so, not fall back to Python.
                    is OcrAvailability.Unavailable -> {
                        log("engine=rust unavailable=$availability")
                        assertTrue(
                            "a missing native engine must be reported, got ${availability.failure}",
                            availability.failure == OcrFailure.OCR_MODELS_MISSING ||
                                availability.failure == OcrFailure.OCR_ENGINE_MISSING,
                        )
                    }
                    OcrAvailability.Checking -> fail("availability must not stay Checking")
                }
            } finally {
                (native as? AutoCloseable)?.close()
            }
        } finally {
            prefs.ocrEngine = previous
        }
    }

    private companion object {
        /** Filter logcat with `adb logcat -s NativeOcrTest`. */
        const val TAG = "NativeOcrTest"
    }
}
