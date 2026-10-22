package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.ProductSearchOutcome
import com.bookcovermatcher.online.GoogleBooksSource
import com.bookcovermatcher.online.OpenLibraryBooksSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opt-in **live** checks of the two real catalogue endpoints through the app's own source classes.
 *
 * They are not part of the normal suite: a unit test must not fail because the build machine is
 * offline. Run them when the network is available with:
 *
 * ```
 * ./gradlew testDebugUnitTest -DonlineCheck=1
 * ```
 *
 * This proves request construction, HTTP status handling and response parsing against the services
 * themselves. It does not prove anything about OCR on a phone, and it is not a visual search.
 */
class LiveCatalogueTest {

    @Before
    fun onlyWhenAsked() {
        assumeTrue("pass -DonlineCheck=1 to run live catalogue checks", System.getProperty("onlineCheck") == "1")
    }

    @Test
    fun openLibraryAnswersARealQuery() {
        val outcome = OpenLibraryBooksSource().search("the hobbit tolkien", 3)

        assertTrue("Open Library returned ${outcome.javaClass.simpleName}", outcome is ProductSearchOutcome.Found)
        val found = outcome as ProductSearchOutcome.Found
        assertFalse(found.results.isEmpty())
        assertTrue(found.results.size <= 3)
        assertTrue(found.results.all { it.detailUrl.startsWith("https://openlibrary.org/") })
        assertTrue(found.results.any { it.title.contains("Hobbit", ignoreCase = true) })
        assertEquals("ol", found.results.first().providerId)
    }

    @Test
    fun googleBooksAnswersARealQuery() {
        val outcome = GoogleBooksSource().search("the hobbit tolkien", 3)

        when (outcome) {
            is ProductSearchOutcome.Found -> {
                assertFalse(outcome.results.isEmpty())
                assertTrue(outcome.results.size <= 3)
                assertTrue(outcome.results.all { it.detailUrl.startsWith("https://") })
                assertTrue(outcome.results.any { it.title.contains("Hobbit", ignoreCase = true) })
                assertEquals("gb", outcome.results.first().providerId)
            }
            // Google Books answers 429 without an API key on some networks. That is a real,
            // correctly classified answer: the app reports it and keeps the other catalogue.
            is ProductSearchOutcome.Failed -> assertEquals(
                "Google Books must either answer or be reported as rate-limited, not as something else",
                com.bookcovermatcher.core.online.OnlineFailure.RATE_LIMITED,
                outcome.failure,
            )
            is ProductSearchOutcome.Empty -> Unit
        }
    }
}
