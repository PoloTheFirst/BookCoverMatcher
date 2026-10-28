package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.SessionCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stale-response guard. This is the regression for "covers only appear on the second click": a
 * search's cover download must carry the generation token from **after** its results were stored,
 * because storing a new capture selects it and bumps the generation.
 */
class SessionCacheRegressionTest {

    private data class Results(val query: String)

    @Test fun storingANewCaptureBumpsTheGeneration() {
        val cache = SessionCache<Results>()
        val beforeSearch = cache.token()

        cache.store("cover-a", Results("東方"))

        assertFalse("a token captured before the store is stale", cache.isCurrent(beforeSearch))
        assertTrue("the token taken after the store is current", cache.isCurrent(cache.token()))
    }

    @Test fun storingTheSameCaptureAgainKeepsTheToken() {
        val cache = SessionCache<Results>()
        cache.store("cover-a", Results("東方"))
        val token = cache.token()

        cache.store("cover-a", Results("東方"))

        assertEquals("re-storing the same cover must not invalidate in-flight covers", token, cache.token())
        assertTrue(cache.isCurrent(token))
    }

    @Test fun aStoredSearchIsReusedInsteadOfRecomputed() {
        val cache = SessionCache<Results>()
        val stored = Results("東方")
        cache.store("cover-a", stored)

        assertSame(stored, cache.cached("cover-a"))
        assertEquals("東方", cache.cached("cover-a")?.query)
    }

    @Test fun switchingCoversDropsTheOtherCapturesResults() {
        val cache = SessionCache<Results>()
        cache.store("cover-a", Results("東方"))

        cache.select("cover-b")

        assertNull("results never leak across captures", cache.cached("cover-a"))
        assertTrue(cache.isCurrent(cache.token()))
    }

    @Test fun invalidateMakesEveryInFlightCoverStale() {
        val cache = SessionCache<Results>()
        cache.store("cover-a", Results("東方"))
        val token = cache.token()

        cache.invalidate()

        assertFalse(cache.isCurrent(token))
        assertNull(cache.cached("cover-a"))
    }

    @Test fun aCacheMissDoesNotCreateAnEntry() {
        val cache = SessionCache<Results>()

        assertNull(cache.cached("cover-a"))
        assertTrue(cache.isCurrent(cache.token()))
    }
}
