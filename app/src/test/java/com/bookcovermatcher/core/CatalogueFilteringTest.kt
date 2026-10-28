package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dynamic catalogue filters: fields and values come from the workbook's own header row and records. */
class CatalogueFilteringTest {

    private fun table(
        sheet: String = "LIST",
        headers: List<Pair<String, Int>>,
        rows: List<List<String>>,
    ): CatalogueTable = CatalogueTable(
        sheetName = sheet,
        headerRow = 0,
        fields = headers.mapIndexed { index, (name, column) -> CatalogueField(name, index, column) },
        records = rows.mapIndexed { index, values -> CatalogueRecord(index + 1, values) },
    )

    private fun simple(): CatalogueTable = table(
        headers = listOf(
            "即売会" to 0, "ジャンル" to 1, "ブック名" to 2, "作者" to 3, "サークル名" to 4, "サイズ" to 5,
        ),
        rows = listOf(
            listOf("C108", "男の娘", "本A", "作者A", "サークルA", "B5"),
            listOf("C107", "催眠", "本B", "作者B", "サークルB", "A5"),
            listOf("C108", "催眠", "本C", "作者C", "サークルA", "B5"),
        ),
    )

    @Test fun everyFieldWithEnoughValuesIsOfferedNotJustTwo() {
        val fields = CatalogueFiltering.fields(listOf(simple()))
        assertEquals(listOf("即売会", "ジャンル", "ブック名", "作者", "サークル名", "サイズ"), fields.map { it.label })
    }

    @Test fun aFieldWithManyValuesIsNotTruncated() {
        val rows = (1..40).map { listOf("本$it") }
        val table = table(headers = listOf("ブック名" to 0), rows = rows)

        val values = CatalogueFiltering.values(table, 0)

        assertEquals(40, values.size)
        assertEquals(40, CatalogueFiltering.fields(listOf(table)).single().valueCount)
    }

    @Test fun duplicateHeadersKeepIndependentColumnIdentity() {
        val table = table(
            headers = listOf("数" to 1, "%" to 5, "%" to 7),
            rows = listOf(listOf("3", "10", "90"), listOf("4", "20", "80")),
        )

        val fields = CatalogueFiltering.fields(listOf(table))

        assertEquals(listOf("数", "% (F)", "% (H)"), fields.map { it.label })
        assertEquals(listOf(1, 5, 7), fields.map { it.column })
        assertEquals(listOf("10", "20"), CatalogueFiltering.values(table, 5))
        assertEquals(listOf("80", "90"), CatalogueFiltering.values(table, 7))

        val first = table.records.first()
        assertTrue(CatalogueFiltering.matches(table, first, listOf(fields[1].filter("10"))))
        assertFalse("the same text in another column must not match", CatalogueFiltering.matches(table, first, listOf(fields[2].filter("10"))))
    }

    @Test fun blankHeadersGetAColumnLabelAndKeepTheirIdentity() {
        val table = table(headers = listOf("タイトル" to 0, "" to 3), rows = listOf(listOf("A", "メモ1"), listOf("B", "メモ2")))

        val blank = CatalogueFiltering.fields(listOf(table)).first { it.name.isEmpty() }

        assertEquals("Column D", blank.label)
        assertEquals(3, blank.column)
    }

    @Test fun blankValuesAreExcludedAndValuesAreSortedAndDistinct() {
        val table = table(
            headers = listOf("サークル名" to 0),
            rows = listOf(
                listOf(" 上海アリス幻樂団 "), listOf("N/A"), listOf(""), listOf("   "), listOf("上海アリス幻樂団"),
            ),
        )

        val values = CatalogueFiltering.values(table, 0)

        assertEquals(listOf("N/A", "上海アリス幻樂団"), values)
    }

    @Test fun aConstantColumnIsNotOfferedAsAFilter() {
        val table = table(
            headers = listOf("種類" to 0, "ジャンル" to 1),
            rows = listOf(listOf("個人誌", "催眠"), listOf("個人誌", "男の娘")),
        )

        assertEquals(listOf("ジャンル"), CatalogueFiltering.fields(listOf(table)).map { it.label })
    }

    @Test fun filtersCombineWithAndAcrossFields() {
        val table = simple()
        val junre = CatalogueFiltering.fields(listOf(table)).first { it.label == "ジャンル" }
        val kaisai = CatalogueFiltering.fields(listOf(table)).first { it.label == "即売会" }

        val onlyC108Neru = listOf(kaisai.filter("C108"), junre.filter("催眠"))

        assertEquals(
            listOf("本C"),
            table.records.filter { CatalogueFiltering.matches(table, it, onlyC108Neru) }.map { it.values[2] },
        )
        assertEquals(3, table.records.count { CatalogueFiltering.matches(table, it, emptyList()) })
    }

    @Test fun aFilterNeverMatchesAnotherWorksheet() {
        val list = simple()
        val data = table(sheet = "DATA", headers = listOf("ジャンル" to 0), rows = listOf(listOf("催眠"), listOf("男の娘")))

        val fields = CatalogueFiltering.fields(listOf(list, data))
        assertEquals(listOf("LIST", "DATA"), CatalogueFiltering.tablesFor(listOf(list, data), null).map { it.sheetName })
        assertEquals(listOf("DATA"), CatalogueFiltering.tablesFor(listOf(list, data), "DATA").map { it.sheetName })
        assertTrue(fields.any { it.sheetName == "DATA" })
        assertFalse(
            "a LIST record cannot satisfy a DATA filter",
            CatalogueFiltering.matches(list, list.records.first(), listOf(CatalogueFiltering.fields(listOf(data)).first().filter("催眠"))),
        )
    }

    @Test fun columnLettersFollowExcel() {
        assertEquals("A", CatalogueFiltering.columnLetter(0))
        assertEquals("F", CatalogueFiltering.columnLetter(5))
        assertEquals("Z", CatalogueFiltering.columnLetter(25))
        assertEquals("AA", CatalogueFiltering.columnLetter(26))
        assertEquals("AB", CatalogueFiltering.columnLetter(27))
    }
}
