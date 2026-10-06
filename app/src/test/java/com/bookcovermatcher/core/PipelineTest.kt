package com.bookcovermatcher.core

import com.bookcovermatcher.core.autoscan.ScanBadge
import com.bookcovermatcher.core.autoscan.ScanPhase
import com.bookcovermatcher.core.autoscan.ScanTick
import com.bookcovermatcher.core.excel.CellRange
import com.bookcovermatcher.core.excel.ImageSource
import com.bookcovermatcher.core.excel.MapZipSource
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.core.search.CatalogBuilder
import com.bookcovermatcher.core.search.CatalogIndex
import com.bookcovermatcher.core.search.FlatIpIndex
import com.bookcovermatcher.core.search.IndexCache
import com.bookcovermatcher.core.vision.GuideGeometry
import com.bookcovermatcher.core.vision.ImageCodec
import com.bookcovermatcher.core.vision.ImageEmbedder
import com.bookcovermatcher.core.vision.PixelImage
import com.bookcovermatcher.core.wishlist.MiniJson
import com.bookcovermatcher.core.wishlist.NewWishlistMatch
import com.bookcovermatcher.core.wishlist.WishlistRepository
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GuideGeometryTest {
    @Test fun portraitFrameInThreeByFourBoxShowsTheWholeFrame() {
        val c = GuideGeometry.coverRect(960, 1280, 0.75f)
        assertEquals(listOf(0, 0, 960, 1280), listOf(c.x, c.y, c.w, c.h))
        val g = GuideGeometry.guideRect(960, 1280, 0.75f)
        assertEquals(672, g.w)                       // 70 %
        assertEquals(998, g.h)                       // 78 %
        assertEquals(144, g.x)
        assertEquals(141, g.y)
    }

    @Test fun squareBoxCropsTopAndBottomOfPortraitFrame() {
        val c = GuideGeometry.coverRect(960, 1280, 1.0f)
        assertEquals(listOf(0, 160, 960, 960), listOf(c.x, c.y, c.w, c.h))
    }

    @Test fun tallBoxCropsTheSidesOfALandscapeFrame() {
        val c = GuideGeometry.coverRect(1280, 960, 0.5f)
        assertEquals(480, c.w)
        assertEquals(400, c.x)
        assertEquals(960, c.h)
    }

    @Test fun guideStaysInsideTheFrameForAnyAspect() {
        for (aspect in listOf(0.3f, 0.5f, 0.75f, 1f, 1.5f, 3f)) for ((w, h) in listOf(640 to 480, 480 to 640, 1280 to 720, 33 to 17)) {
            val g = GuideGeometry.guideRect(w, h, aspect)
            assertTrue("$aspect $w x $h", g.x >= 0 && g.y >= 0 && g.w >= 1 && g.h >= 1 && g.x + g.w <= w && g.y + g.h <= h)
        }
    }

    @Test fun badAspectFallsBackToTheGuideAspect() {
        val g = GuideGeometry.guideRect(640, 480, Float.NaN)
        assertTrue(g.w >= 1 && g.h >= 1)
    }

    @Test fun downscaleNeverEnlarges() {
        assertEquals(1f, GuideGeometry.downscale(300, 200, 800), 0f)
        assertEquals(0.5f, GuideGeometry.downscale(1600, 1000, 800), 1e-6f)
    }
}

class FormattingAndBadgeTest {
    @Test fun timestampMatchesV1Layout() {
        // 2026-10-05 01:24 in Hong Kong == 2026-10-04 17:24 UTC
        val ms = java.time.Instant.parse("2026-10-04T17:24:00Z").toEpochMilli()
        assertEquals("2026-10-05 01:24", Formatting.timestamp(ms, ZoneId.of("Asia/Hong_Kong")))
        assertEquals("2026-10-04 17:24", Formatting.timestamp(ms, ZoneId.of("UTC")))
    }

    @Test fun numbersUseDotAndFixedDecimals() {
        assertEquals("83.4", Formatting.percent(83.449f))
        assertEquals("0.0", Formatting.percent(0f))
        assertEquals("0.812", Formatting.cosine(0.8123f))
        assertEquals(0.02f, Formatting.barFraction(0f), 1e-6f)
        assertEquals(1f, Formatting.barFraction(250f), 1e-6f)
        assertEquals("96.5 MB", Formatting.fileSize(101_187_584L))
    }

    private fun tick(phase: ScanPhase, n: Int = 0) = ScanTick(phase, n, 3, true, true, true, false, 1f, 0.5f)

    @Test fun badgeTextsAreTheV1Strings() {
        assertEquals("Hold still…", ScanBadge.text(tick(ScanPhase.HOLD_STILL)))
        assertEquals("Too dark", ScanBadge.text(tick(ScanPhase.TOO_DARK)))
        assertEquals("Too bright", ScanBadge.text(tick(ScanPhase.TOO_BRIGHT)))
        assertEquals("Stabilizing… 2/3", ScanBadge.text(tick(ScanPhase.STABILIZING, 2)))
        assertEquals("Same cover — waiting", ScanBadge.text(tick(ScanPhase.SAME_COVER)))
        assertEquals("Captured ✓", ScanBadge.text(tick(ScanPhase.CAPTURED)))
    }
}

class MiniJsonTest {
    @Test fun roundTripsNestedValuesAndEscapes() {
        val v = mapOf(
            "s" to "quote\" back\\slash\nnew\ttab \u0001 ünï ✓ 😀",
            "n" to listOf(1, 2.5, -3, 1e-7),
            "t" to true, "f" to false, "z" to null,
            "o" to mapOf("k" to emptyList<Any?>(), "m" to emptyMap<String, Any?>()),
        )
        val parsed = MiniJson.parse(MiniJson.write(v)) as Map<*, *>
        assertEquals(v["s"], parsed["s"])
        assertEquals(listOf(1.0, 2.5, -3.0, 1e-7), parsed["n"])
        assertEquals(true, parsed["t"]); assertEquals(false, parsed["f"]); assertNull(parsed["z"])
        assertTrue(parsed.containsKey("z"))
    }

    @Test fun parsesUnicodeEscapesAndWhitespace() {
        assertEquals("Aé😀", MiniJson.parse(" \"A\\u00e9\\ud83d\\ude00\" "))
        assertEquals(listOf(1.0, 2.0), MiniJson.parse("[ 1 ,\n2 ]"))
    }

    @Test fun rejectsBrokenInput() {
        for (bad in listOf("", "{", "[1,", "{\"a\"}", "{\"a\":1,}", "\"abc", "tru", "[1] x", "{\"a\":\"\\q\"}", "01x", "\"a\nb\"")) {
            try { MiniJson.parse(bad); fail("accepted: $bad") } catch (e: MiniJson.JsonException) { /* expected */ }
        }
    }

    @Test fun limitsNestingDepth() {
        val deep = "[".repeat(200) + "]".repeat(200)
        try { MiniJson.parse(deep); fail() } catch (e: MiniJson.JsonException) { /* expected */ }
    }
}

class WishlistRepositoryTest {
    private fun tmp(): File = Files.createTempDirectory("wl").toFile()
    private fun jpeg(tag: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), tag.toByte())

    private fun matches(n: Int) = (0 until n).map { NewWishlistMatch("A${it + 1}", 0.9f - it * 0.1f, 80f - it * 10, "comment-fill", jpeg(it)) }

    @Test fun addsRecordsWithV1Naming() {
        var t = 1_000L
        var seq = 0
        val repo = WishlistRepository(tmp(), { t++ }, { "id${seq++}" })
        val a = repo.add(jpeg(9), matches(3))
        val b = repo.add(null, emptyList())
        assertEquals("#1", a.name); assertEquals("#2", b.name)
        val loaded = repo.load()
        assertEquals(listOf("id0", "id1"), loaded.map { it.id })
        assertEquals(3, loaded[0].matches.size)
        assertEquals("A2", loaded[0].matches[1].cell)
        assertEquals(0.8f, loaded[0].matches[1].cosine, 1e-6f)
        assertEquals(70f, loaded[0].matches[1].percent, 1e-6f)
        assertEquals("comment-fill", loaded[0].matches[0].source)
        assertArrayEquals(jpeg(9), repo.thumbFile(loaded[0].scanThumbName!!).readBytes())
        assertArrayEquals(jpeg(2), repo.thumbFile(loaded[0].matches[2].thumbName!!).readBytes())
        assertNull(loaded[1].scanThumbName)
    }

    @Test fun renameTrimsAndKeepsOldNameWhenBlank() {
        val repo = WishlistRepository(tmp())
        val r = repo.add(null, emptyList())
        assertTrue(repo.rename(r.id, "  Dune  "))
        assertEquals("Dune", repo.load()[0].name)
        assertTrue(repo.rename(r.id, "   "))
        assertEquals("Dune", repo.load()[0].name)
        assertFalse(repo.rename("nope", "x"))
    }

    @Test fun deleteRemovesRecordAndItsThumbnails() {
        val repo = WishlistRepository(tmp())
        val a = repo.add(jpeg(1), matches(2))
        val b = repo.add(jpeg(2), matches(1))
        val files = listOf(a.scanThumbName!!) + a.matches.map { it.thumbName!! }
        assertTrue(files.all { repo.thumbFile(it).exists() })
        assertTrue(repo.delete(a.id))
        assertTrue(files.none { repo.thumbFile(it).exists() })
        assertEquals(listOf(b.id), repo.load().map { it.id })
        assertTrue(repo.thumbFile(b.scanThumbName!!).exists())
        assertFalse(repo.delete(a.id))
    }

    @Test fun newRecordGetsNumberOfExistingRecordsPlusOneEvenAfterDeletes() {
        val repo = WishlistRepository(tmp())
        val a = repo.add(null, emptyList()); repo.add(null, emptyList())
        repo.delete(a.id)
        assertEquals("#2", repo.add(null, emptyList()).name)       // same quirk as v1
    }

    @Test fun corruptFileIsKeptAndTreatedAsEmpty() {
        val dir = tmp()
        File(dir, "records.json").writeText("{ not json")
        val repo = WishlistRepository(dir)
        assertEquals(0, repo.load().size)
        assertTrue(File(dir, "records.json.corrupt").exists())
        assertEquals("#1", repo.add(null, emptyList()).name)
    }

    @Test fun thumbnailLookupCannotEscapeTheFolder() {
        val dir = tmp()
        val repo = WishlistRepository(dir)
        assertEquals("passwd", repo.thumbFile("../../etc/passwd").name)
        assertEquals(File(dir, "thumbs"), repo.thumbFile("../../etc/passwd").parentFile)
    }
}

/** Embedding = one-hot-ish vector derived from the first byte of the picture (after the 4-byte header). */
private class FakeCodec : ImageCodec {
    var decodeCalls = 0
    override fun decode(bytes: ByteArray, mime: String, maxEdge: Int): PixelImage? {
        decodeCalls++
        if (bytes.size < 5 || String(bytes, Charsets.ISO_8859_1).substring(4).startsWith("BAD")) return null
        val tag = bytes[4].toInt() and 0xFF
        return PixelImage(intArrayOf(tag), 1, 1)
    }
    override fun makeThumbJpeg(image: PixelImage, boxW: Int, boxH: Int) = byteArrayOf(image.argb[0].toByte(), boxW.toByte())
}

private class FakeEmbedder : ImageEmbedder {
    var calls = 0
    override val dim = 8
    override val fingerprint = "fake"
    override fun embed(image: PixelImage): FloatArray {
        calls++
        val v = FloatArray(dim)
        v[image.argb[0] % dim] = 1f
        v[(image.argb[0] + 1) % dim] = 0.2f
        return FlatIpIndex.l2Normalize(v)
    }
}

class CatalogBuilderAndCacheTest {
    private fun png(tag: Char) = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + tag.code.toByte().let { byteArrayOf(it) }

    private fun shape(row: Int, col: Int, rid: String) = """
 <v:shape id="s$row$col" type="#t"><v:fill o:relid="$rid" type="frame"/>
  <x:ClientData ObjectType="Note"><x:Row>$row</x:Row><x:Column>$col</x:Column></x:ClientData></v:shape>"""

    private fun workbook(): MapZipSource {
        // A1..A4 -> tags 1..4 ; A3 is undecodable ("BAD")
        val shapes = (0..3).joinToString("") { shape(it, 0, "rId${it + 1}") }
        val rels = (0..3).joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"image\" Target=\"../media/p$it.png\"/>" }
        val m = linkedMapOf<String, ByteArray>(
            "xl/workbook.xml" to "<workbook><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>".toByteArray(),
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Type=\"http://x/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>".toByteArray(),
            "xl/worksheets/sheet1.xml" to "<worksheet/>".toByteArray(),
            "xl/drawings/vmlDrawing1.vml" to "<xml xmlns:v=\"v\" xmlns:o=\"o\" xmlns:x=\"x\">$shapes</xml>".toByteArray(),
            "xl/drawings/_rels/vmlDrawing1.vml.rels" to "<Relationships>$rels</Relationships>".toByteArray(),
        )
        m["xl/media/p0.png"] = png(Char(1)); m["xl/media/p1.png"] = png(Char(2))
        m["xl/media/p2.png"] = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + "BAD".toByteArray()
        m["xl/media/p3.png"] = png(Char(4))
        return MapZipSource(m)
    }

    @Test fun buildsSearchableCatalogAndSkipsUndecodablePictures() {
        val codec = FakeCodec(); val emb = FakeEmbedder()
        val progress = ArrayList<Pair<Int, Int>>()
        val cat = CatalogBuilder(codec, emb).build(workbook(), CellRange.parse("A1:B20"), false, { d, t -> progress.add(d to t) })
        assertEquals(listOf("A1", "A2", "A4"), cat.items.map { it.cell })
        assertEquals(3, emb.calls)                                 // the broken picture is never embedded
        assertEquals(0 to 4, progress.first())
        assertEquals(4 to 4, progress.last())
        assertEquals(ImageSource.COMMENT_FILL, cat.items[0].source)
        assertEquals(Config.THUMB_MATCH_W * Config.THUMB_PIXEL_SCALE, cat.items[0].thumbJpeg[1].toInt() and 0xFF)

        val top = cat.rank(FakeEmbedder().embed(PixelImage(intArrayOf(4), 1, 1)), 5)
        assertEquals("A4", top[0].image.cell)
        assertEquals(3, top.size)
        assertTrue(top[0].percent > top[1].percent)
    }

    @Test fun cancellationAbortsTheBuild() {
        var seen = 0
        try {
            CatalogBuilder(FakeCodec(), FakeEmbedder()).build(workbook(), CellRange.parse("A1:B20"), false, checkCancelled = {
                if (++seen == 2) throw CancellationException("stop")
            })
            fail("should have been cancelled")
        } catch (e: CancellationException) { /* expected */ }
        assertEquals(2, seen)
    }

    @Test fun emptyRangeGivesEmptyCatalog() {
        val cat = CatalogBuilder(FakeCodec(), FakeEmbedder()).build(workbook(), CellRange.parse("D1:E5"), false)
        assertEquals(0, cat.size)
        assertEquals(0, cat.rank(FloatArray(8) { 1f }, 5).size)
    }

    @Test fun indexCacheRoundTripsAndEvictsLeastRecentlyUsed() {
        val dir = Files.createTempDirectory("ic").toFile()
        val cache = IndexCache(dir, maxEntries = 2)
        val cat = CatalogBuilder(FakeCodec(), FakeEmbedder()).build(workbook(), CellRange.parse("A1:B20"), false)

        assertNull(cache.get("k1"))
        cache.put("k1", cat); File(dir, "k1.bcmi").setLastModified(1_000)
        cache.put("k2", cat); File(dir, "k2.bcmi").setLastModified(2_000)
        val back = cache.get("k1")                                  // touches k1 -> k2 is now the oldest
        assertNotNull(back)
        assertEquals(cat.items.map { it.cell }, back!!.items.map { it.cell })
        assertEquals(cat.baseline, back.baseline, 0f)
        assertArrayEquals(cat.items[2].embedding, back.items[2].embedding, 0f)
        cache.put("k3", cat)
        assertNotNull(cache.get("k1")); assertNull(cache.get("k2")); assertNotNull(cache.get("k3"))
    }

    @Test fun corruptCacheFileIsDroppedNotThrown() {
        val dir = Files.createTempDirectory("ic").toFile()
        File(dir, "bad.bcmi").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(IndexCache(dir).get("bad"))
        assertFalse(File(dir, "bad.bcmi").exists())
    }
}
