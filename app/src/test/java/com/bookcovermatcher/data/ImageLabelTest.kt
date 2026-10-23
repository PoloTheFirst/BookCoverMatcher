package com.bookcovermatcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The image-status label: the selected file's own name, extension included, with an honest fallback
 * when the picker's provider has no display name. The empty state (`No image chosen`) is the panel's
 * placeholder and is only shown while the state holds no name at all.
 */
class ImageLabelTest {

    @Test fun aJpegKeepsItsCompleteName() {
        assertEquals(
            "book_cover.jpg",
            ImageFiles.labelFor("book_cover.jpg", "content://media/1234", "image/jpeg"),
        )
    }

    @Test fun aPngKeepsItsCompleteName() {
        assertEquals(
            "scan_001.png",
            ImageFiles.labelFor("scan_001.png", "content://media/99", "image/png"),
        )
    }

    @Test fun aWebpNameIsNotRewritten() {
        assertEquals(
            "cover.webp",
            ImageFiles.labelFor("cover.webp", "content://media/7", "image/webp"),
        )
    }

    @Test fun selectingAnotherImageReplacesTheLabel() {
        val first = ImageFiles.labelFor("book_cover.jpg", null, "image/jpeg")
        val second = ImageFiles.labelFor("scan_001.png", null, "image/png")

        assertNotEquals(first, second)
        assertEquals("scan_001.png", second)
    }

    @Test fun aMissingDisplayNameFallsBackToTheUrisLastSegment() {
        assertEquals(
            "IMG_2024.jpg",
            ImageFiles.labelFor(null, "/document/image/IMG_2024.jpg", "image/jpeg"),
        )
        assertEquals(
            "photo.png",
            ImageFiles.labelFor("   ", "content://downloads/photo.png", "image/png"),
        )
    }

    @Test fun aNameWithoutAnExtensionGetsOneFromTheMimeType() {
        assertEquals("book_cover.jpg", ImageFiles.labelFor("book_cover", null, "image/jpeg"))
        assertEquals("scan.png", ImageFiles.labelFor(null, "content://x/scan", "image/png"))
    }

    @Test fun nothingUsableStillProducesAMeaningfulLabel() {
        // No display name, no path segment, no MIME type: never an empty status line.
        assertEquals("selected image", ImageFiles.labelFor(null, null, null))
        assertEquals("selected image", ImageFiles.labelFor("", "  ", "application/pdf"))
        // A known MIME type is better than a generic word.
        assertEquals("image.jpg", ImageFiles.labelFor(null, null, "image/jpeg"))
        assertEquals("image.webp", ImageFiles.labelFor(null, null, "image/webp"))
    }

    @Test fun theMimeTypeMappingIsExplicit() {
        assertEquals("jpg", ImageFiles.extensionFor("image/jpeg"))
        assertEquals("png", ImageFiles.extensionFor("IMAGE/PNG"))
        assertEquals("webp", ImageFiles.extensionFor("image/webp"))
        assertEquals("heic", ImageFiles.extensionFor("image/heic"))
        assertEquals(null, ImageFiles.extensionFor("application/octet-stream"))
        assertEquals(null, ImageFiles.extensionFor(null))
    }
}