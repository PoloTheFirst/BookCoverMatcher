package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.data.ColumnMappingStore
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The saved mapping profiles on disk: one small JSON file, tolerant reads, atomic-ish writes. */
class ColumnMappingStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("bcm-mappings").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a missing store loads as no profiles`() {
        assertTrue(ColumnMappingStore(File(dir, "none")).load().isEmpty())
    }

    @Test
    fun `saving a profile persists it across instances`() {
        ColumnMappingStore(dir).save(CatalogFixtures.profile("Saved"))
        val loaded = ColumnMappingStore(dir).load()
        assertEquals(1, loaded.size)
        assertEquals("Saved", loaded.single().name)
        assertEquals("タイトル", loaded.single().columns[CatalogField.TITLE])
    }

    @Test
    fun `saving the same name replaces that profile only`() {
        val store = ColumnMappingStore(dir)
        store.save(CatalogFixtures.profile("One"))
        store.save(CatalogFixtures.profile("Two"))
        store.save(ColumnMappingProfile("One", mapOf(CatalogField.TITLE to "Name")))
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("Name", loaded.first { it.name == "One" }.columns[CatalogField.TITLE])
        assertEquals("タイトル", loaded.first { it.name == "Two" }.columns[CatalogField.TITLE])
    }

    @Test
    fun `delete removes only the named profile`() {
        val store = ColumnMappingStore(dir)
        store.save(CatalogFixtures.profile("One"))
        store.save(CatalogFixtures.profile("Two"))
        val left = store.delete("One")
        assertEquals(listOf("Two"), left.map { it.name })
        assertEquals(listOf("Two"), store.load().map { it.name })
    }

    @Test
    fun `a corrupt file loads as no profiles and is left alone`() {
        val file = File(dir, "profiles.json")
        file.writeText("{ not json")
        assertTrue(ColumnMappingStore(dir).load().isEmpty())
        assertEquals("{ not json", file.readText())
    }
}