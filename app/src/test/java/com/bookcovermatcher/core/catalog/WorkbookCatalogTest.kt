package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The canonical records every feature shares: identity, field states and image associations. */
class WorkbookCatalogTest {

    @Test
    fun `one entry per record with a stable identity`() {
        val catalog = CatalogFixtures.catalog(
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
        )
        assertEquals(2, catalog.entries.size)
        assertEquals("xl/worksheets/sheet1.xml#1", catalog.entries[0].id)
        assertEquals("xl/worksheets/sheet1.xml#2", catalog.entries[1].id)
        assertEquals("LIST!2", catalog.entries[0].locationLabel)
        assertEquals(1, catalog.entries[0].row)
        assertEquals(CatalogFixtures.SHA, catalog.entries[0].workbookSha256)
    }

    @Test
    fun `mapped missing and unmapped are three different states`() {
        val catalog = CatalogFixtures.catalog(
            records = listOf(CatalogFixtures.catalogueRecord(1, circle = "")),
            profile = ColumnMappingProfile("partial", mapOf(CatalogField.TITLE to "タイトル", CatalogField.CIRCLE to "サークル")),
        )
        val entry = catalog.entries.single()
        assertEquals(FieldState.MAPPED, entry.state(CatalogField.TITLE))
        assertEquals("Book 1", entry.title)
        assertEquals(FieldState.MISSING, entry.state(CatalogField.CIRCLE))
        assertNull(entry.text(CatalogField.CIRCLE))
        assertEquals(FieldState.UNMAPPED, entry.state(CatalogField.ARTIST))
        assertNull(entry.text(CatalogField.ARTIST))
    }

    @Test
    fun `an ambiguous header produces an ambiguous value, not a blank`() {
        val table = CatalogFixtures.table(
            fields = CatalogFixtures.fields("タイトル", "タイトル", "サークル"),
            records = listOf(CatalogFixtures.record(1, "First", "Second", "Circle")),
        )
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            CatalogFixtures.sheets(),
            listOf(table),
            emptyList(),
            CatalogFixtures.profile(),
        )
        val entry = catalog.entries.single()
        assertEquals(FieldState.AMBIGUOUS, entry.state(CatalogField.TITLE))
        assertEquals(listOf("タイトル"), entry.value(CatalogField.TITLE).candidates)
        assertNull(entry.title)
        assertTrue(catalog.issues.any { it.kind == MappingIssueKind.HEADER_AMBIGUOUS })
    }

    @Test
    fun `images attach to the record row the scanner verified`() {
        val catalog = CatalogFixtures.catalog(
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
            images = listOf(CatalogFixtures.linked("xl/media/image3.png", row = 2)),
        )
        assertFalse(catalog.entries[0].hasVerifiedCover)
        assertTrue(catalog.entries[1].hasVerifiedCover)
        assertEquals("xl/media/image3.png", catalog.entries[1].images.single().mediaPath)
        assertEquals(1, catalog.withVerifiedCover)
        assertEquals(1, catalog.withoutVerifiedCover)
    }

    @Test
    fun `an unresolved reference stays unresolved on the record`() {
        val catalog = CatalogFixtures.catalog(images = listOf(CatalogFixtures.unresolved("media/x.png")))
        assertTrue(catalog.unresolvedImages == 1)
        assertTrue(catalog.entries.none { it.hasVerifiedCover })
    }

    @Test
    fun `unreferenced media is counted but never attached to a record`() {
        val catalog = CatalogFixtures.catalog(images = listOf(CatalogFixtures.unreferenced("xl/media/image9.png")))
        assertEquals(1, catalog.unreferencedImages)
        assertTrue(catalog.entries.all { it.images.isEmpty() })
        assertEquals(0, catalog.withVerifiedCover)
    }

    @Test
    fun `two worksheets never share a record identity`() {
        val a = CatalogFixtures.table("LIST", records = listOf(CatalogFixtures.catalogueRecord(1)))
        val b = CatalogFixtures.table("EXTRA", records = listOf(CatalogFixtures.catalogueRecord(1)))
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            CatalogFixtures.sheets("LIST", "EXTRA"),
            listOf(a, b),
            emptyList(),
            CatalogFixtures.profile(),
        )
        assertEquals(2, catalog.entries.size)
        assertEquals(2, catalog.entries.map { it.id }.distinct().size)
        assertEquals(listOf("LIST", "EXTRA"), catalog.sheetNames)
        assertEquals(1, catalog.entriesBySheet["EXTRA"]?.size)
    }

    @Test
    fun `the profile that produced the records is recorded`() {
        val catalog = CatalogFixtures.catalog(profile = CatalogFixtures.profile("My profile"))
        assertEquals("My profile", catalog.profileName)
        assertEquals("タイトル", catalog.headerOf("LIST", CatalogField.TITLE))
        assertNull(catalog.headerOf("MISSING", CatalogField.TITLE))
    }

    @Test
    fun `a blank cell is never turned into a placeholder value`() {
        val catalog = CatalogFixtures.catalog(
            records = listOf(CatalogFixtures.catalogueRecord(1, title = "", artist = "", price = "")),
        )
        val entry = catalog.entries.single()
        assertEquals(FieldState.MISSING, entry.state(CatalogField.TITLE))
        assertNull(entry.text(CatalogField.PRICE))
        assertEquals("", entry.title.orEmpty())
    }
}