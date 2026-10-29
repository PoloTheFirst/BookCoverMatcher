package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping layer: a profile maps workbook headers to canonical fields, and anything it cannot settle
 * is reported instead of guessed.
 */
class ColumnMappingTest {

    private val profile = CatalogFixtures.profile()
    private val table = CatalogFixtures.table(records = listOf(CatalogFixtures.catalogueRecord(1)))

    @Test
    fun `detected profile uses the workbook's own headers`() {
        val detected = ColumnMappings.detectedProfile(table)
        assertEquals(ColumnMappingProfile.DETECTED_NAME, detected.name)
        assertEquals("タイトル", detected.columns[CatalogField.TITLE])
        assertEquals("サークル", detected.columns[CatalogField.CIRCLE])
        assertEquals("作者", detected.columns[CatalogField.ARTIST])
        assertEquals("発行日", detected.columns[CatalogField.PUBLISHED])
        assertEquals("ID", detected.columns[CatalogField.IDENTIFIER])
        assertEquals("URL", detected.columns[CatalogField.URL])
        assertEquals("価格", detected.columns[CatalogField.PRICE])
    }

    @Test
    fun `a sheet without matching headers detects only what it has`() {
        val odd = CatalogFixtures.table(fields = CatalogFixtures.fields("Alpha", "Beta", "Gamma"))
        val detected = ColumnMappings.detectedProfile(odd)
        assertTrue(detected.columns.isEmpty())
    }

    @Test
    fun `headers are matched case and whitespace insensitively`() {
        val spaced = CatalogFixtures.table(
            fields = CatalogFixtures.fields("  TITLE ", "circle", "Author"),
            records = listOf(CatalogFixtures.record(1, "A", "B", "C")),
        )
        val mapping = ColumnMappings.resolve(
            spaced,
            ColumnMappingProfile("loose", mapOf(CatalogField.TITLE to "title", CatalogField.ARTIST to "AUTHOR")),
        )
        assertEquals(0, mapping.columnOf(CatalogField.TITLE))
        assertEquals(2, mapping.columnOf(CatalogField.ARTIST))
        assertNull(mapping.columnOf(CatalogField.CIRCLE))
        assertTrue(mapping.issues.isEmpty())
    }

    @Test
    fun `columns resolve by header name, not by position`() {
        val reversed = CatalogFixtures.table(
            fields = CatalogFixtures.fields("メモ", "ID", "発行日", "タイトル"),
            records = listOf(CatalogFixtures.record(1, "note", "ID-1", "2024", "A title")),
        )
        val mapping = ColumnMappings.resolve(reversed, profile)
        assertEquals(3, mapping.columnOf(CatalogField.TITLE))
        assertEquals(1, mapping.columnOf(CatalogField.IDENTIFIER))
        assertEquals(2, mapping.columnOf(CatalogField.PUBLISHED))
    }

    @Test
    fun `a renamed header leaves the field unmapped instead of guessing`() {
        val renamed = CatalogFixtures.table(
            fields = CatalogFixtures.fields("タイトル", "サークル", "著者名", "ID"),
            records = listOf(CatalogFixtures.record(1, "A", "B", "C", "D")),
        )
        val mapping = ColumnMappings.resolve(renamed, profile)
        assertNull(mapping.columnOf(CatalogField.ARTIST))
        val issue = mapping.issues.first { it.field == CatalogField.ARTIST }
        assertEquals(MappingIssueKind.HEADER_MISSING, issue.kind)
        assertEquals("作者", issue.header)
        assertTrue(issue.detail.contains("作者"))
        // Every other mapping still works.
        assertEquals(0, mapping.columnOf(CatalogField.TITLE))
    }

    @Test
    fun `a duplicated header is ambiguous and no column is chosen`() {
        val duplicated = CatalogFixtures.table(
            fields = CatalogFixtures.fields("タイトル", "タイトル", "サークル"),
            records = listOf(CatalogFixtures.record(1, "A", "B", "C")),
        )
        val mapping = ColumnMappings.resolve(duplicated, profile)
        assertNull(mapping.columnOf(CatalogField.TITLE))
        val issue = mapping.issues.first { it.kind == MappingIssueKind.HEADER_AMBIGUOUS }
        assertEquals(CatalogField.TITLE, issue.field)
        assertTrue(issue.detail.contains("2 columns"))
        assertEquals(MappingIssueKind.HEADER_AMBIGUOUS, ColumnMappings.blockingIssue(mapping, CatalogField.TITLE)?.kind)
    }

    @Test
    fun `one column mapped to two fields is reported but still resolves`() {
        val shared = ColumnMappingProfile(
            "shared",
            mapOf(CatalogField.TITLE to "タイトル", CatalogField.DESCRIPTION to "タイトル"),
        )
        val mapping = ColumnMappings.resolve(table, shared)
        assertEquals(0, mapping.columnOf(CatalogField.TITLE))
        assertEquals(0, mapping.columnOf(CatalogField.DESCRIPTION))
        val issue = mapping.issues.first { it.kind == MappingIssueKind.SHARED_HEADER }
        assertTrue(issue.detail.contains("A"))
    }

    @Test
    fun `a profile without a title column reports the required field`() {
        val noTitle = ColumnMappingProfile("no-title", mapOf(CatalogField.CIRCLE to "サークル"))
        val mapping = ColumnMappings.resolve(table, noTitle)
        assertFalse(mapping.isMapped(CatalogField.TITLE))
        assertTrue(mapping.issues.any { it.kind == MappingIssueKind.REQUIRED_UNMAPPED })
    }

    @Test
    fun `circle and artist stay separate fields`() {
        val mapping = ColumnMappings.resolve(table, profile)
        assertEquals("サークル", mapping.columns[CatalogField.CIRCLE]?.header)
        assertEquals("作者", mapping.columns[CatalogField.ARTIST]?.header)
        assertNotNull(mapping.columnOf(CatalogField.CIRCLE))
        assertNotNull(mapping.columnOf(CatalogField.ARTIST))
        assertTrue(mapping.columnOf(CatalogField.CIRCLE) != mapping.columnOf(CatalogField.ARTIST))
    }

    @Test
    fun `an empty profile maps nothing and is honest about it`() {
        val mapping = ColumnMappings.resolve(table, ColumnMappingProfile("empty", emptyMap()))
        assertTrue(mapping.columns.isEmpty())
        assertTrue(mapping.issues.all { it.kind == MappingIssueKind.REQUIRED_UNMAPPED })
    }

    @Test
    fun `normalize trims and collapses whitespace`() {
        assertEquals("a b", ColumnMappings.normalize("  A   B "))
        assertEquals("タイトル", ColumnMappings.normalize("タイトル "))
    }

    @Test
    fun `encode and decode preserve the profile`() {
        val encoded = ColumnMappingCodec.encode(listOf(profile))
        val decoded = ColumnMappingCodec.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(profile.name, decoded.single().name)
        assertEquals(profile.columns, decoded.single().columns)
        assertEquals(ColumnMappingProfile.SCHEMA_VERSION, decoded.single().schemaVersion)
    }

    @Test
    fun `decode skips unknown fields and broken entries`() {
        val json = """{"schema":1,"profiles":[""" +
            """{"name":"ok","columns":{"TITLE":"タイトル","NOT_A_FIELD":"x"}},""" +
            """{"name":"","columns":{"TITLE":"x"}},""" +
            """{"name":"no-columns"}]}"""
        val decoded = ColumnMappingCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(mapOf(CatalogField.TITLE to "タイトル"), decoded.single().columns)
    }

    @Test
    fun `decode of a non-JSON file yields no profiles`() {
        assertTrue(ColumnMappingCodec.decode("not json at all").isEmpty())
        assertTrue(ColumnMappingCodec.decode("").isEmpty())
        assertTrue(ColumnMappingCodec.decode("{\"profiles\":3}").isEmpty())
    }
}