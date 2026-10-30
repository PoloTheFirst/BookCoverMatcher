package com.bookcovermatcher.core

import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.GalleryFilter
import com.bookcovermatcher.core.catalog.GallerySort
import com.bookcovermatcher.core.catalog.GalleryView
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The key the Insights gallery resets its scroll on.
 *
 * It must change exactly when what the user is looking at changes order or contents — and must not change
 * for a thumbnail finishing, a dialog opening or a report being rebuilt, because those would jump the grid
 * under the user's finger.
 */
class GalleryScrollTest {

    private fun catalog(sha: String = CatalogFixtures.SHA) = WorkbookCatalogBuilder.build(
        workbookSha256 = sha,
        sheets = CatalogFixtures.sheets(),
        tables = listOf(
            CatalogFixtures.table(
                records = listOf(
                    CatalogFixtures.catalogueRecord(1, title = "B"),
                    CatalogFixtures.catalogueRecord(2, title = "A"),
                ),
            ),
        ),
        images = emptyList(),
        profile = CatalogFixtures.profile(),
    )

    private fun state(catalogSha: String = CatalogFixtures.SHA) = WorkbookCommentsUi(
        open = true,
        sha256 = catalogSha,
        catalog = catalog(catalogSha),
    )

    @Test
    fun `the same ordering keeps the same key`() {
        val state = state()
        assertEquals(state.galleryOrderKey, state.copy().galleryOrderKey)
        assertEquals(
            "a query of spaces is the same as no query",
            state.galleryOrderKey,
            state.copy(galleryFilter = GalleryFilter(query = "  ")).galleryOrderKey,
        )
    }

    @Test
    fun `changing the sort or its direction changes the key`() {
        val state = state()
        assertNotEquals(state.galleryOrderKey, state.copy(gallerySort = GallerySort.CIRCLE).galleryOrderKey)
        assertNotEquals(state.galleryOrderKey, state.copy(galleryAscending = false).galleryOrderKey)
    }

    @Test
    fun `changing a filter changes the key, because the dataset changes`() {
        val state = state()
        assertNotEquals(
            state.galleryOrderKey,
            state.copy(galleryFilter = GalleryFilter(query = "A")).galleryOrderKey,
        )
        assertNotEquals(
            state.galleryOrderKey,
            state.copy(galleryFilter = GalleryFilter(onlyWithCover = true)).galleryOrderKey,
        )
        assertNotEquals(
            state.galleryOrderKey,
            state.copy(galleryFilter = GalleryFilter(onlyMissingTitle = true)).galleryOrderKey,
        )
        assertNotEquals(
            state.galleryOrderKey,
            state.copy(galleryFilter = GalleryFilter(sheetName = "LIST")).galleryOrderKey,
        )
    }

    @Test
    fun `unrelated state changes keep the key, so the grid is not moved`() {
        val state = state()
        assertEquals(state.galleryOrderKey, state.copy(insightsBusy = true).galleryOrderKey)
        assertEquals(state.galleryOrderKey, state.copy(insightsError = "nope").galleryOrderKey)
        assertEquals(
            state.galleryOrderKey,
            state.copy(healthSeverity = com.bookcovermatcher.core.catalog.HealthSeverity.WARNING).galleryOrderKey,
        )
        assertEquals(state.galleryOrderKey, state.copy(mappingDraft = emptyMap()).galleryOrderKey)
    }

    @Test
    fun `opening another workbook changes the key`() {
        assertNotEquals(state("sha-one").galleryOrderKey, state("sha-two").galleryOrderKey)
    }

    @Test
    fun `the ordering really does change the first card`() {
        val state = state()
        assertEquals("A", state.visibleCatalog.first().title)
        val descending = state.copy(galleryAscending = false)
        assertEquals("B", descending.visibleCatalog.first().title)
        assertEquals(
            listOf("B", "A"),
            GalleryView.rows(
                state.catalog!!.entries,
                GalleryFilter(),
                GallerySort.TITLE,
                ascending = false,
            ).map { it.title },
        )
    }
}
