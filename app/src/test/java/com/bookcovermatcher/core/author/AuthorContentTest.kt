// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Format sniffing, the "may this replace what we have" checks, and the image references of the real README
 * (v2.6.0 amendment).
 */
class AuthorContentTest {

    @Test fun formatsComeFromBytesNotFromTheUrl() {
        assertEquals(AuthorImageFormat.PNG, AuthorImageFormat.of(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(AuthorImageFormat.JPEG, AuthorImageFormat.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte())))
        assertEquals(
            AuthorImageFormat.WEBP,
            AuthorImageFormat.of("RIFF????WEBPVP8 ".toByteArray()),
        )
        assertEquals(AuthorImageFormat.GIF, AuthorImageFormat.of("GIF89a".toByteArray()))
        assertEquals(AuthorImageFormat.SVG, AuthorImageFormat.of("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertEquals(AuthorImageFormat.SVG, AuthorImageFormat.of("<?xml version=\"1.0\"?>\n<svg/>".toByteArray()))
        assertEquals(AuthorImageFormat.UNKNOWN, AuthorImageFormat.of("<html><body>504</body></html>".toByteArray()))
        assertEquals(AuthorImageFormat.UNKNOWN, AuthorImageFormat.of(ByteArray(0)))
    }

    @Test fun anHtmlErrorPageIsNeverMistakenForAnSvg() {
        val html = "<!DOCTYPE html><html><head><title>oops</title></head></html>".toByteArray()
        assertFalse(AuthorImageFormat.isRenderable(html))
        assertFalse(AuthorContentValidator.acceptsImage(html))
    }

    @Test fun aReadmeIsAcceptedOnlyWhenItLooksLikeTheOwnersReadme() {
        val real = "## Hi there, I am Polo\n\n![banner](https://example.org/a.png)\n"
        assertTrue(AuthorContentValidator.acceptsReadme(real.toByteArray(), "text/plain; charset=utf-8"))
        assertTrue(AuthorContentValidator.acceptsReadme(real.toByteArray(), null))
    }

    @Test fun anErrorPageJsonOrEmptyBodyIsRejectedAsAReadme() {
        assertFalse(AuthorContentValidator.acceptsReadme("<html><body>503</body></html>".toByteArray()))
        assertFalse(AuthorContentValidator.acceptsReadme("<!DOCTYPE html><html></html>".toByteArray()))
        assertFalse(AuthorContentValidator.acceptsReadme("{\"message\":\"API rate limit\"}".toByteArray()))
        assertFalse(AuthorContentValidator.acceptsReadme(ByteArray(0)))
        assertFalse(AuthorContentValidator.acceptsReadme("x".repeat(AuthorContentValidator.MAX_README_BYTES + 1).toByteArray()))
        assertFalse(AuthorContentValidator.acceptsReadme("## hi".toByteArray(), "image/png"))
    }

    @Test fun anImageMustCarryRealImageBytes() {
        assertTrue(AuthorContentValidator.acceptsImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(AuthorContentValidator.acceptsImage("not an image".toByteArray()))
        assertFalse(AuthorContentValidator.acceptsImage(ByteArray(0)))
        assertFalse(AuthorContentValidator.acceptsImage(ByteArray(AuthorContentValidator.MAX_IMAGE_BYTES + 1)))
    }

    @Test fun theRealReadmeReferencesTheBannerTheBadgesAndTheGraph() {
        val readme = locateReadme() ?: run {
            assumeTrue("the bundled README is not readable from the working directory", false)
            return
        }
        val refs = AuthorImageRefs.of(readme.readText())

        assertTrue("the banner must be referenced: $refs", refs.any { it.contains("2_PC-A.png") })
        assertTrue("a shields badge must be referenced: $refs", refs.any { it.contains("img.shields.io/badge/") })
        assertTrue("the contribution graph must be referenced: $refs", refs.any { it.contains("pacman-contribution-graph") })
        assertEquals("reads must not duplicate a URL", refs.size, refs.distinct().size)

        // The banner's README URL is a blob page; it has to survive the gate as a raw file for the decoder to see it.
        val banner = refs.first { it.contains("2_PC-A.png") }
        assertTrue(AuthorUrls.resolve(banner)!!.startsWith("https://raw.githubusercontent.com/"))
        assertTrue(refs.filter { it.contains("img.shields.io") }.all { AuthorUrls.resolve(it) != null })
    }

    @Test fun picturesInsidePictureElementsResolveToTheirDarkSource() {
        // The graph's own dark variant, not a hand-made recolour: GitHub's dark canvas (#0d1117), light labels
        // (#8b949e) and the dark contribution ramp (v2.6.0 crash-fix 3).
        val markdown = """
            <picture>
            <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/o/r/output/g-dark.svg">
            <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/o/r/output/g.svg">
            <img alt="graph" src="https://raw.githubusercontent.com/o/r/output/g.svg">
            </picture>
        """.trimIndent()
        val refs = AuthorImageRefs.of(markdown)
        assertEquals(listOf("https://raw.githubusercontent.com/o/r/output/g-dark.svg"), refs)
    }

    private fun locateReadme(): File? = listOf(
        File("src/main/assets/author/README.md"),
        File("app/src/main/assets/author/README.md"),
    ).firstOrNull { it.isFile }
}
