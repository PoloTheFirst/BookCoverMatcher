// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.author

import com.bookcovermatcher.core.author.AuthorProfile
import com.bookcovermatcher.core.author.AuthorSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Author page refresh (v2.6.0 amendment): once per launch, offline-safe, and never destructive.
 *
 * Only the network is fake — the cache on disk and the refresh decisions are the real ones, so these tests show
 * what is committed, what is kept, and how many requests a launch makes.
 */
class AuthorRefreshTest {

    private lateinit var dir: File
    private lateinit var cache: AuthorFileCache
    private lateinit var remote: FakeRemote
    private lateinit var repository: AuthorContentRepository<String>

    private val banner = "https://github.com/PoloTheFirst/PoloTheFirst/blob/main/assets/2_PC-A.png"
    private val bannerRaw = "https://raw.githubusercontent.com/PoloTheFirst/PoloTheFirst/main/assets/2_PC-A.png"
    private val badge = "https://img.shields.io/badge/Python-3670A0?&logo=python&logoColor=ffdd54"

    private val png = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val readme = """
        ## Hi there, I am Polo

        ![banner]($banner)

        <div align="center"><img alt="Python" src="$badge"></div>
    """.trimIndent()

    private val bundled = "## Bundled copy\n\nnothing fetched yet\n"

    @Before fun setUp() {
        dir = Files.createTempDirectory("bcm-author-refresh").toFile()
        cache = AuthorFileCache(dir)
        remote = FakeRemote()
        repository = AuthorContentRepository(
            cache = cache,
            remote = remote,
            bundledReadme = { bundled },
            decode = { bytes -> "picture:${bytes.size}" },
        )
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun beforeAnythingIsFetchedTheBundledCopyIsShown() {
        repository.loadCached()

        assertEquals(bundled, repository.state.value.markdown)
        assertEquals(AuthorSource.BUNDLED, repository.state.value.source)
    }

    @Test fun aCachedCopyIsShownImmediatelyAndBeatsTheBundledOne() {
        cache.writeReadme(readme)

        repository.loadCached()

        assertEquals(readme, repository.state.value.markdown)
        assertEquals(AuthorSource.CACHED, repository.state.value.source)
    }

    @Test fun aRefreshStoresTheReadmeAndEveryPictureItReferences() = runBlocking {
        remote.answer(AuthorProfile.README_RAW_URL, FetchResult.Ok(readme.toByteArray(), "etag-readme", null, "text/plain"))
        remote.answer(bannerRaw, FetchResult.Ok(png, "etag-banner", null, "image/png"))
        remote.answer(badge, FetchResult.Ok("<svg width=\"67\" height=\"20\"/>".toByteArray(), null, "x", "image/svg+xml"))

        repository.loadCached()
        val updated = repository.refresh()

        assertTrue(updated)
        val state = repository.state.value
        assertEquals(readme, state.markdown)
        assertEquals(AuthorSource.REFRESHED, state.source)
        assertEquals("both pictures decoded", 2, state.images.size)
        assertNotNull(state.images[banner])
        assertNotNull(state.images[badge])
        assertFalse(state.refreshing)
        assertNotNull("the validators are kept for the next launch", cache.readReadmeMeta().etag)
        assertEquals("the banner is fetched from the raw host, not the blob page", 1, remote.count(bannerRaw))
        assertEquals(0, remote.count(banner))
        assertTrue(cache.readReadme()!!.contains("Hi there"))
    }

    @Test fun twoRefreshesAtOnceMakeOneSetOfRequests() = runBlocking {
        remote.delayMs = 60
        remote.answer(AuthorProfile.README_RAW_URL, FetchResult.Ok(readme.toByteArray(), null, null, "text/plain"))

        repository.loadCached()
        val jobs = (1..2).map { launch { repository.refresh() } }
        jobs.joinAll()

        assertEquals("a launch must not duplicate the refresh", 1, remote.count(AuthorProfile.README_RAW_URL))
    }

    @Test fun anErrorPageNeverReplacesTheCachedReadme() = runBlocking {
        cache.writeReadme(readme)
        repository.loadCached()
        remote.answer(AuthorProfile.README_RAW_URL, FetchResult.Ok("<html>503</html>".toByteArray(), null, null, "text/html"))

        repository.refresh()

        val state = repository.state.value
        assertEquals("the good copy stays", readme, state.markdown)
        assertEquals(AuthorSource.CACHED, state.source)
        assertEquals("readme-rejected", state.lastFailure)
    }

    @Test fun notModifiedKeepsTheTextAndStillRevalidatesThePictures() = runBlocking {
        cache.writeReadme(readme)
        cache.writeImage(bannerRaw, png)
        repository.loadCached()
        remote.answer(AuthorProfile.README_RAW_URL, FetchResult.NotModified)
        remote.answer(bannerRaw, FetchResult.NotModified)
        remote.answer(badge, FetchResult.NotModified)

        repository.refresh()

        val state = repository.state.value
        assertEquals(readme, state.markdown)
        assertEquals("the cached picture is decoded for the first frame", "picture:${png.size}", state.images[banner])
        assertEquals("generated pictures are revalidated every launch", 1, remote.count(badge))
        assertEquals(1, remote.count(AuthorProfile.README_RAW_URL))
    }

    @Test fun anOfflineLaunchKeepsEverythingAndSaysNothingAlarming() = runBlocking {
        cache.writeReadme(readme)
        cache.writeImage(bannerRaw, png)
        repository.loadCached()
        remote.failEverything("no-host")

        repository.refresh()

        val state = repository.state.value
        assertEquals(readme, state.markdown)
        assertEquals("picture:${png.size}", state.images[banner])
        assertEquals("no-host", state.lastFailure)
        assertFalse("the spinner must stop", state.refreshing)
    }

    @Test fun aFailedPictureKeepsTheOneAlreadyOnScreen() = runBlocking {
        remote.answer(AuthorProfile.README_RAW_URL, FetchResult.Ok(readme.toByteArray(), null, null, "text/plain"))
        remote.answer(bannerRaw, FetchResult.Ok(png, null, null, "image/png"))
        remote.answer(badge, FetchResult.Ok("<svg/>".toByteArray(), null, null, "image/svg+xml"))
        repository.loadCached()
        repository.refresh()
        val before = repository.state.value.images[banner]
        assertNotNull(before)

        remote.answer(badge, FetchResult.Failed("timeout"))
        remote.answer(bannerRaw, FetchResult.Ok("<html>oops</html>".toByteArray(), null, null, "text/html"))
        repository.refresh()

        assertEquals("an undecodable body must not replace a good picture", before, repository.state.value.images[banner])
        assertNotNull("the other picture is untouched", repository.state.value.images[badge])
    }

    @Test fun picturesTheReadmeDropsAreNotKeptForever() = runBlocking {
        cache.writeReadme(readme)
        cache.writeImage(bannerRaw, png)
        cache.writeImage(badge, png)
        repository.loadCached()

        remote.answer(AuthorProfile.README_RAW_URL, FetchResult.Ok("## just text now\n".toByteArray(), null, null, "text/plain"))
        repository.refresh()

        assertEquals("no pictures are referenced any more", 0, repository.state.value.images.size)
        assertEquals(0, cache.readImage(bannerRaw)?.size ?: 0)
    }

    /** A deterministic stand-in for the network. */
    private class FakeRemote : AuthorRemote {
        private val answers = ConcurrentHashMap<String, FetchResult>()
        private val calls = ConcurrentHashMap<String, AtomicInteger>()

        @Volatile var delayMs: Long = 0

        fun answer(url: String, result: FetchResult) {
            answers[url] = result
        }

        fun failEverything(reason: String) {
            answers.clear()
            answers["*"] = FetchResult.Failed(reason)
        }

        fun count(url: String): Int = calls[url]?.get() ?: 0

        override suspend fun get(url: String, etag: String?, lastModified: String?, maxBytes: Int): FetchResult {
            calls.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return answers[url] ?: answers["*"] ?: FetchResult.Failed("offline")
        }
    }
}

