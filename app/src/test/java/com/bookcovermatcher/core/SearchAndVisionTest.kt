package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.ImageSource
import com.bookcovermatcher.core.search.CatalogIndex
import com.bookcovermatcher.core.search.CatalogIndexStore
import com.bookcovermatcher.core.search.FlatIpIndex
import com.bookcovermatcher.core.search.IndexedImage
import com.bookcovermatcher.core.search.MatchLevel
import com.bookcovermatcher.core.search.ScoreScale
import com.bookcovermatcher.core.vision.ClipPreprocessor
import com.bookcovermatcher.core.vision.ImageOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import kotlin.math.abs

class FlatIpIndexTest {
    private fun unit(vararg v: Float) = FlatIpIndex.l2Normalize(floatArrayOf(*v))

    @Test fun returnsBestInnerProductsFirst() {
        val idx = FlatIpIndex(2)
        idx.add(unit(1f, 0f)); idx.add(unit(0.7f, 0.7f)); idx.add(unit(0f, 1f)); idx.add(unit(-1f, 0f))
        val hits = idx.search(unit(1f, 0.1f), 3)
        assertEquals(listOf(0, 1, 2), hits.map { it.id })
        assertTrue(hits[0].score > hits[1].score && hits[1].score > hits[2].score)
    }

    @Test fun kLargerThanSizeReturnsEverything_andTiesPreferLowerId() {
        val idx = FlatIpIndex(2)
        repeat(3) { idx.add(unit(1f, 1f)) }
        assertEquals(listOf(0, 1, 2), idx.search(unit(1f, 1f), 10).map { it.id })
        assertTrue(idx.search(unit(1f, 1f), 0).isEmpty())
        assertTrue(FlatIpIndex(2).search(unit(1f, 1f), 3).isEmpty())
    }

    @Test fun matchesBruteForceOnRandomData() {
        val rnd = Random(1)
        val dim = 64
        val idx = FlatIpIndex(dim)
        val all = List(150) { FlatIpIndex.l2Normalize(FloatArray(dim) { rnd.nextGaussian().toFloat() }) }
        all.forEach { idx.add(it) }
        val q = FlatIpIndex.l2Normalize(FloatArray(dim) { rnd.nextGaussian().toFloat() })
        val expected = all.indices.sortedByDescending { i -> all[i].indices.sumOf { (all[i][it] * q[it]).toDouble() } }.take(10)
        assertEquals(expected, idx.search(q, 10).map { it.id })
    }

    @Test fun normalizeProducesUnitLength_andLeavesZeroVectorAlone() {
        val n = FlatIpIndex.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1.0, Math.sqrt((n[0] * n[0] + n[1] * n[1]).toDouble()), 1e-6)
        assertArrayEquals(floatArrayOf(0f, 0f), FlatIpIndex.l2Normalize(floatArrayOf(0f, 0f)), 0f)
    }
}

class CatalogIndexTest {
    private fun item(cell: String, vararg v: Float) =
        IndexedImage(cell, 0, 0, ImageSource.COMMENT_FILL, "m/$cell", byteArrayOf(1, 2, 3), FlatIpIndex.l2Normalize(floatArrayOf(*v)))

    @Test fun ranksByScoreThenByCellAddress() {
        val c = CatalogIndex(
            2, listOf(item("B2", 0f, 1f), item("A10", 1f, 0f), item("A2", 1f, 0f), item("C1", 1f, 0.5f)), baseline = 0.5f,
        )
        val ranked = c.rank(floatArrayOf(1f, 0f), 3)
        // A10 and A2 are identical copies of the query -> tie broken by plain string order like v1's localeCompare
        assertEquals(listOf("A10", "A2", "C1"), ranked.map { it.image.cell })
        assertEquals(1.0, ranked[0].cosine.toDouble(), 1e-6)
        assertEquals(100.0, ranked[0].percent.toDouble(), 1e-3)
        assertEquals(MatchLevel.HIGH, ranked[0].level)
    }

    @Test fun percentIsRelativeToTheCatalogBaseline() {
        assertEquals(0f, ScoreScale.percent(0.4f, 0.5f), 0f)
        assertEquals(100f, ScoreScale.percent(1.0f, 0.5f), 1e-4f)
        assertEquals(50f, ScoreScale.percent(0.75f, 0.5f), 1e-4f)
        assertEquals(50f, ScoreScale.percent(0.6f, 0.2f), 1e-4f)
        assertEquals(MatchLevel.HIGH, ScoreScale.level(70f))
        assertEquals(MatchLevel.MID, ScoreScale.level(69.9f))
        assertEquals(MatchLevel.MID, ScoreScale.level(45f))
        assertEquals(MatchLevel.LOW, ScoreScale.level(44.9f))
    }

    @Test fun baselineIsTheMedianPairwiseCosine() {
        val vs = listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(-1f, 0f), floatArrayOf(0f, -1f))
        // pair cosines: 0,-1,0,0,-1,0 -> median 0
        assertEquals(0f, CatalogIndex.estimateBaseline(vs), 1e-6f)
        assertEquals(ScoreScale.DEFAULT_BASELINE, CatalogIndex.estimateBaseline(vs.take(2)), 0f)
    }

    @Test fun storeRoundTripsEverything() {
        val c = CatalogIndex(3, listOf(item("A1", 1f, 2f, 3f), item("B7", -1f, 0.5f, 2f)), baseline = 0.42f)
        val bytes = ByteArrayOutputStream().also { CatalogIndexStore.write(c, it) }.toByteArray()
        val back = CatalogIndexStore.read(ByteArrayInputStream(bytes))
        assertEquals(3, back.dim); assertEquals(0.42f, back.baseline, 0f)
        assertEquals(listOf("A1", "B7"), back.items.map { it.cell })
        assertEquals(ImageSource.COMMENT_FILL, back.items[0].source)
        assertEquals("m/B7", back.items[1].imageId)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.items[0].thumbJpeg)
        assertArrayEquals(c.items[1].embedding, back.items[1].embedding, 0f)
    }

    @Test fun storeRejectsTruncatedAndForeignFiles() {
        val c = CatalogIndex(3, listOf(item("A1", 1f, 2f, 3f)), baseline = 0.5f)
        val bytes = ByteArrayOutputStream().also { CatalogIndexStore.write(c, it) }.toByteArray()
        for (bad in listOf(bytes.copyOf(bytes.size - 5), byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), ByteArray(0))) {
            try { CatalogIndexStore.read(ByteArrayInputStream(bad)); fail("expected IOException") } catch (_: IOException) {}
        }
    }

    @Test fun cacheKeyChangesWithEveryInput() {
        val base = CatalogIndexStore.cacheKey("aa", "0,19,0,1", "model1", true)
        assertEquals(base, CatalogIndexStore.cacheKey("aa", "0,19,0,1", "model1", true))
        assertTrue(base != CatalogIndexStore.cacheKey("ab", "0,19,0,1", "model1", true))
        assertTrue(base != CatalogIndexStore.cacheKey("aa", "0,19,0,2", "model1", true))
        assertTrue(base != CatalogIndexStore.cacheKey("aa", "0,19,0,1", "model2", true))
        assertTrue(base != CatalogIndexStore.cacheKey("aa", "0,19,0,1", "model1", false))
    }
}

class VisionTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun clipPreprocessorMatchesPillowOnAGoldenCase() {
        // 8x6 pattern resized to 4x3 with Pillow's Image.resize(..., Image.BICUBIC)
        val w = 8; val h = 6
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            argb((x * 31 + y * 7) % 256, (x * 13 + y * 53) % 256, (x * 5 + y * 91 + 17) % 256)
        }
        val planes = ClipPreprocessor.resizeBicubicRgb(px, w, h, 4, 3)
        val r = intArrayOf(21, 80, 145, 204, 35, 94, 159, 218, 48, 107, 172, 231)
        val g = intArrayOf(36, 61, 91, 116, 149, 171, 174, 197, 126, 141, 65, 81)
        val b = intArrayOf(82, 91, 102, 111, 120, 129, 140, 149, 157, 166, 177, 186)
        for (i in r.indices) {
            assertTrue("R[$i] ${planes[0][i]} vs ${r[i]}", abs(planes[0][i] - r[i]) <= 1)
            assertTrue("G[$i] ${planes[1][i]} vs ${g[i]}", abs(planes[1][i] - g[i]) <= 1)
            assertTrue("B[$i] ${planes[2][i]} vs ${b[i]}", abs(planes[2][i] - b[i]) <= 1)
        }
    }

    @Test fun tensorLayoutAndNormalisation() {
        val size = 8
        val px = IntArray(10 * 14) { argb(255, 128, 0) }
        val t = ClipPreprocessor.toChw(px, 10, 14, size)
        assertEquals(3 * size * size, t.size)
        val n = size * size
        assertEquals((1f - ClipPreprocessor.MEAN[0]) / ClipPreprocessor.STD[0], t[0], 1e-4f)
        assertEquals((128f / 255f - ClipPreprocessor.MEAN[1]) / ClipPreprocessor.STD[1], t[n + 5], 1e-4f)
        assertEquals((0f - ClipPreprocessor.MEAN[2]) / ClipPreprocessor.STD[2], t[2 * n + n - 1], 1e-4f)
    }

    @Test fun sameSizeResizeIsTheIdentity() {
        val rnd = Random(5)
        val px = IntArray(16 * 16) { argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val planes = ClipPreprocessor.resizeBicubicRgb(px, 16, 16, 16, 16)
        for (i in px.indices) {
            assertEquals((px[i] shr 16) and 0xFF, planes[0][i])
            assertEquals((px[i] shr 8) and 0xFF, planes[1][i])
            assertEquals(px[i] and 0xFF, planes[2][i])
        }
    }

    @Test fun transparentPixelsBecomeWhite() {
        assertEquals(argb(255, 255, 255), ImageOps.flattenOnWhite(argb(10, 20, 30, 0)))
        assertEquals(argb(10, 20, 30), ImageOps.flattenOnWhite(argb(10, 20, 30, 255)))
    }

    @Test fun areaResizeAveragesBlocks() {
        val px = intArrayOf(argb(0, 0, 0), argb(100, 100, 100), argb(200, 200, 200), argb(100, 100, 100))
        val out = ImageOps.areaResizeArgb(px, 2, 2, 1, 1)
        assertEquals(argb(100, 100, 100), out[0])
        val flat = IntArray(100 * 80) { argb(40, 90, 200) }
        assertTrue(ImageOps.areaResizeArgb(flat, 100, 80, 7, 5).all { it == argb(40, 90, 200) })
    }
}
