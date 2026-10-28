package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filter values come from the workbook's own cells: empty, duplicate, Unicode and wide fields. */
class CatalogueFilterValuesTest {

    private fun table(vararg rows: List<String>): CatalogueTable = CatalogueTable(
        sheetName = "LIST",
        headerRow = 1,
        fields = listOf(CatalogueField("タイトル", 0), CatalogueField("サークル名", 1), CatalogueField("メモ", 2)),
        records = rows.mapIndexed { index, values -> CatalogueRecord(index + 2, values) },
    )

    @Test fun valuesAreDistinctSortedAndNeverIncludeBlanks() {
        val field = CatalogueField("サークル名", 1)
        val values = table(
            listOf("A", "上海アリス幻樂団", ""),
            listOf("B", "上海アリス幻樂団", ""),
            listOf("C", "  ", ""),
            listOf("D", "N/A", ""),
        ).valuesOf(field)

        assertEquals(listOf("N/A", "上海アリス幻樂団"), values)
    }

    @Test fun aFieldWithTooManyDistinctValuesIsNotOfferedAsAFilter() {
        val wide = table(
            *(1..12).map { listOf("title $it", "circle ${'A' + (it % 2)}", "memo ${'A' + (it % 2)}") }.toTypedArray(),
        )

        val fields = wide.categoricalFields(maxValues = 4).map { it.name }

        assertEquals(listOf("サークル名", "メモ"), fields)
        assertTrue("a column with one value per row is not a filter", "タイトル" !in fields)
    }

    @Test fun searchingOneRecordNeverMatchesAnUnrelatedOne() {
        val table = table(
            listOf("東方紅魔郷", "上海アリス幻樂団", "ZUN"),
            listOf("Blue Archive", "N/A", "Yostar"),
        )

        assertTrue(table.matches(table.records[0], "紅魔郷"))
        assertTrue(table.matches(table.records[1], "yostar"))
        assertTrue("an empty search must not filter everything out", table.matches(table.records[0], "  "))
        assertTrue(!table.matches(table.records[0], "Blue"))
    }
}
