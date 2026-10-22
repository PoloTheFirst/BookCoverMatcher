package com.bookcovermatcher.online

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of the complete Kotlin → Chaquopy → chrome-lens-py path.
 *
 * Run with `./gradlew connectedDebugAndroidTest` on an emulator or phone with network access.
 * The bundled cover is The Hobbit (Open Library cover id 14627509); the assertion is on text that
 * Lens actually reads from it, not on a fake bridge.
 *
 * This test intentionally fails when Lens cannot be reached: an unverified OCR path must not be
 * reported as working. It is not part of `testDebugUnitTest`.
 */
@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {

    @Test
    fun lensBridgeReadsTheBundledCover() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context ?: instrumentation.targetContext
        val bytes = testContext.assets.open("cover.jpg").use { it.readBytes() }
        val bridge = ChromeLensPyBridge(PythonRuntime(instrumentation.targetContext))

        val availability = bridge.availability()
        assertTrue(
            "chrome-lens-py must load on this device, got: $availability",
            availability is OcrAvailability.Ready,
        )

        val text = bridge.recognise(bytes, "en", null, 60)
        assertTrue(
            "Lens should read the cover title, got: ${text.text}",
            text.text.contains("HOBBIT", ignoreCase = true),
        )
        assertTrue("block geometry must be present for the title picker", text.blockHeights.isNotEmpty())
    }

    /**
     * The whole controller path with a real network search: toggle on -> both sources -> merge ->
     * `CatalogueUi.Found`. OCR is stubbed because it is verified separately above.
     */
    @Test
    fun onlineControllerSearchesAndPublishesResults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = com.bookcovermatcher.data.OnlineSearchPrefs(context)
        val bridge = object : OcrTextSource {
            override fun availability(): OcrAvailability = OcrAvailability.Unavailable(
                OcrFailure.LIBRARY_MISSING, "stubbed in the catalogue test",
            )
            override fun recognise(
                imageBytes: ByteArray,
                ocrLanguage: String?,
                targetLanguage: String?,
                timeoutSeconds: Int,
            ): OcrText = throw OcrException(OcrFailure.LIBRARY_MISSING, "stubbed")
        }
        val controller = OnlineSearchController(
            context = context,
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            ),
            prefs = prefs,
            bridge = bridge,
        )
        try {
            controller.setEnabled(true)
            controller.search("the hobbit tolkien")
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                val catalogue = controller.ui.value.catalogue
                if (catalogue is com.bookcovermatcher.CatalogueUi.Found) {
                    assertTrue(catalogue.items.isNotEmpty())
                    assertTrue(catalogue.source.contains("Open Library"))
                    assertTrue(catalogue.items.all { it.detailUrl.startsWith("https://") })
                    return
                }
                if (catalogue is com.bookcovermatcher.CatalogueUi.Failed) {
                    throw AssertionError("online search failed: ${catalogue.message}")
                }
                Thread.sleep(200)
            }
            throw AssertionError("online search did not finish in 30 s")
        } finally {
            controller.shutdown()
            prefs.enabled = false
        }
    }

    /**
     * The real catalogue path from the device: request construction, HTTP, JSON parsing and
     * normalisation. Google Books may answer 429 without an API key; that is an accepted,
     * correctly classified answer. Open Library must return at least one real record.
     */
    @Test
    fun bothCatalogueSourcesAnswerOrReportAFailure() {
        val openLibrary = OpenLibraryBooksSource().search("the hobbit tolkien", 3)
        assertTrue(
            "Open Library should answer, got: $openLibrary",
            openLibrary is com.bookcovermatcher.core.online.ProductSearchOutcome.Found,
        )
        val openResults = (openLibrary as com.bookcovermatcher.core.online.ProductSearchOutcome.Found).results
        assertTrue(openResults.isNotEmpty())
        assertTrue(openResults.all { it.detailUrl.startsWith("https://openlibrary.org/") })

        val google = GoogleBooksSource().search("the hobbit tolkien", 3)
        when (google) {
            is com.bookcovermatcher.core.online.ProductSearchOutcome.Found -> {
                assertTrue(google.results.isNotEmpty())
                assertTrue(google.results.all { it.detailUrl.startsWith("https://") })
            }
            is com.bookcovermatcher.core.online.ProductSearchOutcome.Failed -> org.junit.Assert.assertEquals(
                com.bookcovermatcher.core.online.OnlineFailure.RATE_LIMITED,
                google.failure,
            )
            is com.bookcovermatcher.core.online.ProductSearchOutcome.Empty -> Unit
        }
    }
}
