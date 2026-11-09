// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.author

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Author page's disk cache (v2.6.0 amendment).
 *
 * The property that matters is that a refresh can never leave the user with less than they had: invalid bytes are
 * not committed, and every write lands through a `.part` file so an interrupted one leaves the old file intact.
 */
class AuthorFileCacheTest {

    private lateinit var dir: File
    private lateinit var cache: AuthorFileCache

    @Before fun setUp() {
        dir = Files.createTempDirectory("bcm-author-cache").toFile()
        cache = AuthorFileCache(dir)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun aReadmeRoundTripsThroughTheCache() {
        assertNull(cache.readReadme())
        assertTrue(cache.writeReadme("## Hello\n\nsome words about the owner\n"))
        assertEquals("## Hello\n\nsome words about the owner\n", cache.readReadme())
    }

    @Test fun anErrorPageIsNeverWritten() {
        assertFalse(cache.writeReadme("<html><body>500</body></html>"))
        assertNull(cache.readReadme())
    }

    @Test fun anImageRoundTripsAndInvalidBytesAreRefused() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val url = "https://raw.githubusercontent.com/o/r/main/a.png"
        assertNull(cache.readImage(url))
        assertTrue(cache.writeImage(url, png))
        assertNotNull(cache.readImage(url))

        assertFalse(cache.writeImage("https://raw.githubusercontent.com/o/r/main/b.png", "<html>nope</html>".toByteArray()))
        assertNull(cache.readImage("https://raw.githubusercontent.com/o/r/main/b.png"))
    }

    @Test fun noPartialFilesAreLeftBehindAndTheOldCopySurvivesAFailedWrite() {
        assertTrue(cache.writeReadme("## first copy\n\nwords\n"))
        // A write that fails validation must leave the previous file exactly as it was.
        assertFalse(cache.writeReadme("<html>bad</html>"))
        assertTrue(cache.readReadme()!!.contains("first copy"))
        assertEquals(
            "a .part file leaked: ${dir.listFiles()?.map { it.name }}",
            emptyList<String>(),
            dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }.map { it.name },
        )
    }

    @Test fun validatorsAreStoredBesideTheContent() {
        cache.writeReadmeMeta(AuthorFileCache.Meta("etag-1", "Mon, 09 Nov 2026 03:55:53 GMT"))
        val meta = cache.readReadmeMeta()
        assertEquals("etag-1", meta.etag)
        assertEquals("Mon, 09 Nov 2026 03:55:53 GMT", meta.lastModified)

        val url = "https://img.shields.io/badge/x-y"
        cache.writeImageMeta(url, AuthorFileCache.Meta(null, "Tue, 10 Nov 2026 00:00:00 GMT"))
        assertEquals("Tue, 10 Nov 2026 00:00:00 GMT", cache.readImageMeta(url).lastModified)
        assertNull(cache.readImageMeta(url).etag)
    }

    @Test fun imagesTheReadmeNoLongerMentionsAreDeleted() {
        val keep = "https://raw.githubusercontent.com/o/r/main/keep.png"
        val drop = "https://raw.githubusercontent.com/o/r/main/drop.png"
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        cache.writeImage(keep, png)
        cache.writeImage(drop, png)

        cache.retainOnly(listOf(keep))

        assertNotNull(cache.readImage(keep))
        assertNull(cache.readImage(drop))
    }

    @Test fun aKeyIsStableAndNeverContainsUrlCharacters() {
        val url = "https://img.shields.io/badge/Python-3670A0?&logo=python"
        val key = cache.key(url)
        assertEquals(key, cache.key(url))
        assertTrue("unexpected characters in $key", key.all { it.isDigit() || it in 'a'..'f' })
        assertNull("a different URL must not collide", cache.key("$url&x=1").takeIf { it == key })
    }
}
