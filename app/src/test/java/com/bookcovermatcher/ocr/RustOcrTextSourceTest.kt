// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ocr

import com.bookcovermatcher.core.vision.PixelImage
import com.bookcovermatcher.online.OcrAvailability
import com.bookcovermatcher.online.OcrException
import com.bookcovermatcher.online.OcrFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The adapter's decisions — when to open the engine, what each failure means, and how pixels become
 * [com.bookcovermatcher.online.OcrText] — with the JNI and the decoder replaced by fakes, so this runs
 * on a desktop JVM and never needs the models.
 */
class RustOcrTextSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val payload = """
        BC-OCR-RESULT 1
        REGIONS	2
        LINE	1	0.990000	0	10.00,20.00,110.00,20.00,110.00,40.00,10.00,40.00	THE HOBBIT
        LINE	2	0.500000	1	5.00,50.00,15.00,50.00,15.00,150.00,5.00,150.00	縦書き
        END
    """.trimIndent() + "\n"

    private class FakeNative(
        override val isLoaded: Boolean = true,
        override val loadFailure: String? = null,
        private val payload: String = "",
        private val createFailure: Throwable? = null,
    ) : NativeOcrApi {
        var created = 0
        var destroyed = 0
        var recognised = 0
        var lastPixels = 0
        var lastHandle = 0L
        var lastRuntimePath: String? = null

        override fun version(): String = "0.1.0"
        override fun create(detector: String, recognizer: String, dictionary: String, runtimeLibrary: String): Long {
            createFailure?.let { throw it }
            created++
            lastRuntimePath = runtimeLibrary
            return 7L
        }
        override fun describe(handle: Long): String = "BC-OCR-INFO 1\nVERSION\t0.1.0\nEND\n"
        override fun recognise(handle: Long, argb: IntArray, width: Int, height: Int): String {
            recognised++
            lastHandle = handle
            lastPixels = argb.size
            return payload
        }
        override fun destroy(handle: Long) {
            destroyed++
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installedModels(): PpocrModels {
        val root = temp.newFolder("models")
        for (name in PpocrModels.NAMES) File(root, name).writeText("fixture:$name")
        val pins = PpocrModels.NAMES.associateWith { sha256(File(root, it)) }
        return PpocrModels(root, pins)
    }

    private fun source(
        native: NativeOcrApi,
        models: PpocrModels = installedModels(),
        decode: (ByteArray, Int) -> PixelImage? = { _, _ -> PixelImage(IntArray(100 * 200), 100, 200) },
    ) = RustOcrTextSource(
        models = models,
        runtimeLibraryPath = "",
        native = native,
        decode = decode,
    )

    @Test
    fun availabilityNamesTheEngineAndTheModelSet() {
        val availability = source(FakeNative()).availability()
        assertTrue("expected Ready, got $availability", availability is OcrAvailability.Ready)
        val ready = availability as OcrAvailability.Ready
        assertTrue(ready.libraryName.contains("PP-OCRv6"))
        assertTrue(ready.libraryVersion.contains("0.1.0"))
        assertTrue(ready.libraryVersion.contains(PpocrModels.FINGERPRINT))
    }

    @Test
    fun availabilityReportsAMissingLibraryInsteadOfCrashing() {
        val availability = source(FakeNative(isLoaded = false, loadFailure = "libbc_ocr.so not found")).availability()
        assertTrue(availability is OcrAvailability.Unavailable)
        val unavailable = availability as OcrAvailability.Unavailable
        assertEquals(OcrFailure.OCR_ENGINE_MISSING, unavailable.failure)
        assertTrue(unavailable.reason.contains("libbc_ocr.so not found"))
    }

    @Test
    fun availabilityReportsMissingModels() {
        val availability = source(FakeNative(), models = PpocrModels(temp.newFolder("empty"))).availability()
        assertTrue(availability is OcrAvailability.Unavailable)
        assertEquals(OcrFailure.OCR_MODELS_MISSING, (availability as OcrAvailability.Unavailable).failure)
    }

    @Test
    fun recogniseReturnsTextGeometryAndEngineIdentity() {
        val native = FakeNative(payload = payload)
        val text = source(native).recognise(byteArrayOf(1, 2, 3), "en", null, 30)

        assertEquals("THE HOBBIT\n縦書き", text.text)
        assertEquals(2, text.blockCount)
        assertEquals(listOf(0.1f, 0.5f), text.blockHeights)
        // "THE" + "HOBBIT" + "縦書き" — the native engine splits on spaces, not on script.
        assertEquals(3, text.wordCount)
        assertEquals(100 * 200, native.lastPixels)
        assertTrue(text.libraryVersion.contains("bc_ocr"))
        assertTrue(text.libraryVersion.contains("0.1.0"))
        assertEquals(
            "an empty runtime path asks the loader for the soname (what Android requires)",
            "",
            native.lastRuntimePath,
        )
        // No language is guessed and nothing is translated.
        assertEquals(null, text.detectedLanguage)
        assertEquals(null, text.translatedText)
    }

    @Test
    fun theEngineIsOpenedOnceReusedAndClosedOnce() {
        val native = FakeNative(payload = payload)
        val source = source(native)

        source.recognise(byteArrayOf(1), null, null, 30)
        source.recognise(byteArrayOf(1), null, null, 30)

        assertEquals(1, native.created)
        assertEquals(2, native.recognised)
        assertEquals(7L, native.lastHandle)

        source.close()
        source.close()
        assertEquals("destroy must be idempotent", 1, native.destroyed)
    }

    @Test
    fun anUndecodableCaptureIsABadImage() {
        val source = source(FakeNative(payload = payload), decode = { _, _ -> null })
        val error = runCatching { source.recognise(byteArrayOf(1), null, null, 30) }.exceptionOrNull()
        assertTrue("expected OcrException, got $error", error is OcrException)
        assertEquals(OcrFailure.BAD_IMAGE, (error as OcrException).failure)
    }

    @Test
    fun anEmptyCaptureIsRefusedBeforeTheEngineOpens() {
        val native = FakeNative(payload = payload)
        val error = runCatching { source(native).recognise(ByteArray(0), null, null, 30) }.exceptionOrNull()
        assertTrue(error is OcrException)
        assertEquals(OcrFailure.BAD_IMAGE, (error as OcrException).failure)
        assertEquals("no engine may be opened for an empty capture", 0, native.created)
    }

    @Test
    fun aCoverWithNoTextIsASuccessfulEmptyResult() {
        val native = FakeNative(payload = "BC-OCR-RESULT 1\nREGIONS\t0\nEND\n")
        val text = source(native).recognise(byteArrayOf(1), null, null, 30)
        assertEquals("", text.text)
        assertEquals(0, text.blockCount)
        assertTrue(text.isEmpty)
    }

    @Test
    fun perRegionFailuresWithNoLinesBecomeAnInternalError() {
        val native = FakeNative(
            payload = "BC-OCR-RESULT 1\nREGIONS\t1\nERROR\tregion 1: recognition failed\nEND\n",
        )
        val error = runCatching { source(native).recognise(byteArrayOf(1), null, null, 30) }.exceptionOrNull()
        assertTrue(error is OcrException)
        assertEquals(OcrFailure.INTERNAL, (error as OcrException).failure)
        assertTrue(error.detail!!.contains("recognition failed"))
    }

    @Test
    fun aCreateFailureIsReportedAsARuntimeProblem() {
        val native = FakeNative(payload = payload, createFailure = IllegalStateException("only one environment"))
        val error = runCatching { source(native).recognise(byteArrayOf(1), null, null, 30) }.exceptionOrNull()
        assertTrue(error is OcrException)
        assertEquals(OcrFailure.RUNTIME_MISSING, (error as OcrException).failure)
        assertTrue(error.detail!!.contains("only one environment"))
    }

    @Test
    fun aGarbledPayloadBecomesAnInternalError() {
        val native = FakeNative(payload = "not the format at all")
        val error = runCatching { source(native).recognise(byteArrayOf(1), null, null, 30) }.exceptionOrNull()
        assertTrue(error is OcrException)
        assertEquals(OcrFailure.INTERNAL, (error as OcrException).failure)
    }

    @Test
    fun aMissingModelSetIsRefusedBeforeTheEngineOpens() {
        val native = FakeNative(payload = payload)
        val source = source(native, models = PpocrModels(temp.newFolder("empty")))
        val error = runCatching { source.recognise(byteArrayOf(1), null, null, 30) }.exceptionOrNull()
        assertTrue(error is OcrException)
        assertEquals(OcrFailure.OCR_MODELS_MISSING, (error as OcrException).failure)
        assertEquals(0, native.created)
    }
}
