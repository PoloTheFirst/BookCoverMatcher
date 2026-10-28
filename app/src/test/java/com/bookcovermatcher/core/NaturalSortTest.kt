package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.comments.ImageIndex
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.NaturalSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Natural filename ordering: digit runs compare numerically, everything else deterministically. */
class NaturalSortTest {

    private fun sorted(vararg names: String): List<String> =
        names.sortedWith { a, b -> NaturalSort.compareNames(a, b) }

    @Test fun numberedNamesSortNumerically() {
        assertEquals(
            listOf("image1.jpg", "image2.jpg", "image3.jpg", "image10.jpg", "image20.jpg", "image100.jpg"),
            sorted("image100.jpg", "image2.jpg", "image20.jpg", "image1.jpg", "image10.jpg", "image3.jpg"),
        )
    }

    @Test fun nonNumericNamesStillSortAlphabeticallyIgnoringCase() {
        assertEquals(listOf("Amabile.jpg", "beta.jpg", "Zeta.jpg"), sorted("Zeta.jpg", "beta.jpg", "Amabile.jpg"))
    }

    @Test fun leadingZerosCompareNumericallyAndTiesAreDeterministic() {
        // Equal numeric value: the exact text decides, so "image02.jpg" sorts before "image2.jpg".
        assertTrue(NaturalSort.compareNames("image02.jpg", "image2.jpg") < 0)
        assertTrue(NaturalSort.compareNames("image2.jpg", "image02.jpg") > 0)
        assertEquals(
            -NaturalSort.compareNames("image02.jpg", "image2.jpg"),
            NaturalSort.compareNames("image2.jpg", "image02.jpg"),
        )
        // Numeric value still dominates: 2 < 10 regardless of zero padding.
        assertTrue(NaturalSort.compareNames("image02.jpg", "image10.jpg") < 0)
    }

    @Test fun multipleNumericSectionsCompareSectionBySection() {
        assertEquals(
            listOf("cover2_part3.jpg", "cover2_part12.jpg", "cover10_part1.jpg"),
            sorted("cover10_part1.jpg", "cover2_part12.jpg", "cover2_part3.jpg"),
        )
    }

    @Test fun extensionsAreComparedWithoutSurprises() {
        assertEquals(
            listOf("img1.bmp", "img1.jpg", "img1.png", "img2.jpg"),
            sorted("img2.jpg", "img1.png", "img1.jpg", "img1.bmp"),
        )
    }

    @Test fun veryLongDigitRunsDoNotOverflow() {
        val short = "image" + "9".repeat(30) + ".png"
        val long = "image" + "1" + "0".repeat(40) + ".png"

        assertTrue("a 31-digit number is smaller than a 41-digit number", NaturalSort.compareNames(short, long) < 0)
        assertEquals(0, NaturalSort.compareNames(long, long))
        // Comparison is still a total order for equal lengths.
        assertTrue(NaturalSort.compareNames("a" + "1".repeat(500) + "b", "a" + "1".repeat(499) + "2b") < 0)
    }

    @Test fun emptyAndNumericOnlyNamesAreOrderedConsistently() {
        assertEquals(listOf("", "0", "1", "2", "10"), sorted("10", "2", "1", "0", ""))
    }

    @Test fun equalNamesCompareEqualToZero() {
        assertEquals(0, NaturalSort.compareNames("image1.jpg", "image1.jpg"))
    }

    @Test fun imageIndexNameSortIsNaturalThroughTheRealSortPath() {
        val rows = listOf("image100.jpg", "image2.jpg", "image10.jpg").map { name ->
            ImageRow(
                key = name,
                mediaPath = "xl/media/$name",
                fileName = name,
                format = "jpeg",
                sizeBytes = 1L,
                width = null,
                height = null,
                state = ImageState.LINKED,
                associations = emptyList(),
                catalogue = emptyList(),
                unresolvedDetail = null,
            )
        }

        assertEquals(
            listOf("image2.jpg", "image10.jpg", "image100.jpg"),
            ImageIndex.sortRows(rows, ImageSort.NAME, ascending = true).map { it.fileName },
        )
        assertEquals(
            listOf("image100.jpg", "image10.jpg", "image2.jpg"),
            ImageIndex.sortRows(rows, ImageSort.NAME, ascending = false).map { it.fileName },
        )
    }

    @Test fun nameSortMatchesTheQueryPathTheImagesTabUses() {
        val rows = listOf("image10.jpg", "image2.jpg").map { name ->
            ImageRow(
                key = name, mediaPath = "xl/media/$name", fileName = name, format = "jpeg", sizeBytes = 1L,
                width = null, height = null, state = ImageState.LINKED, associations = emptyList(),
                catalogue = emptyList(), unresolvedDetail = null,
            )
        }

        assertEquals(
            listOf("image2.jpg", "image10.jpg"),
            ImageIndex.query(rows, needle = "", states = emptySet(), sort = ImageSort.NAME).map { it.fileName },
        )
    }
}
