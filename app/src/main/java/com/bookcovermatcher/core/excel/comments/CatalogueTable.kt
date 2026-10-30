package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.ZipSource

/**
 * One column of a catalogue table, as the workbook spells it.
 *
 * [index] is the column's position in [CatalogueTable.fields] and therefore in every record's
 * [CatalogueRecord.values]; [column] is its zero-based source column. The two are only equal while no
 * column is skipped, so nothing may use one as the other.
 *
 * A column can exist **without** a header: a blank header cell between two named columns is still a
 * column of the table, and dropping it would shift every value to its right by one. [name] is then empty
 * and [label] falls back to the column letter, so nothing in the UI prints an unnamed blank.
 */
data class CatalogueField(val name: String, val index: Int, val column: Int = index) {

    /** False when the workbook left this column's header cell blank. The column is still real. */
    val hasHeader: Boolean get() = name.trim().isNotEmpty()

    /** What the UI prints: the workbook's own header, or `Column F` when the header cell is blank. */
    val label: String get() = if (hasHeader) name.trim() else "Column " + CatalogueFiltering.columnLetter(column)

    /** Excel's letter for this column, so a header can be read as a cell reference. */
    val letter: String get() = CatalogueFiltering.columnLetter(column)
}

/** One catalogue row: the values of [CatalogueTable.fields], in field order. */
data class CatalogueRecord(val row: Int, val values: List<String>) {
    fun value(field: CatalogueField): String = values.getOrNull(field.index)?.trim().orEmpty()
}

/**
 * A worksheet that looks like a catalogue table.
 *
 * **Nothing here is hardcoded to a sheet name, a column number or a Japanese header.** The header row is
 * found structurally, the field names are the workbook's own header cells, and the alias lists below are
 * only *presentation hints*: they decide which columns a compact card shows first. When no alias
 * matches, the first three headers are used instead — the table stays usable.
 *
 * An image is associated with a record only through a verified legacy comment on the **same worksheet
 * row**. Nothing is inferred from image order, filenames or neighbouring cells.
 */
data class CatalogueTable(
    val sheetName: String,
    val headerRow: Int,
    val fields: List<CatalogueField>,
    val records: List<CatalogueRecord>,
) {
    private fun alias(vararg needles: String): CatalogueField? = fields.firstOrNull { field ->
        val name = field.name.lowercase()
        needles.any { name.contains(it) }
    }

    val titleField: CatalogueField? by lazy {
        alias("タイトル", "書名", "ブック名", "作品名", "title", "book name")
    }
    val authorField: CatalogueField? by lazy { alias("作者", "著者", "author", "writer") }
    val circleField: CatalogueField? by lazy { alias("サークル", "circle", "社团", "社團") }
    val genreField: CatalogueField? by lazy { alias("ジャンル", "genre", "分類", "类型", "類型") }
    val dateField: CatalogueField? by lazy { alias("発行", "発売", "出版", "日付", "年月", "date", "year") }
    val formatField: CatalogueField? by lazy { alias("形式", "種別", "タイプ", "サイズ", "format", "type") }

    /** The columns a compact card shows: aliases when present, otherwise the first three. */
    val identifyingFields: List<CatalogueField>
        get() {
            val picked = listOfNotNull(titleField, authorField, circleField).distinctBy { it.index }
            return if (picked.isNotEmpty()) picked else fields.take(3)
        }

    /** Columns worth offering as filters: 2..40 distinct non-blank values, not one value per row. */
    fun categoricalFields(maxValues: Int = 40): List<CatalogueField> = fields.filter { field ->
        val distinct = records.asSequence().map { it.value(field) }.filter { it.isNotEmpty() }.toSet()
        distinct.size in 2..maxValues && distinct.size < records.size
    }

    fun valuesOf(field: CatalogueField): List<String> =
        records.asSequence().map { it.value(field) }.filter { it.isNotEmpty() }.distinct().sorted().toList()

    fun recordAt(row: Int): CatalogueRecord? = records.firstOrNull { it.row == row }

    /** Case-insensitive search across every column of [record]. */
    fun matches(record: CatalogueRecord, needle: String): Boolean {
        val n = needle.trim()
        if (n.isEmpty()) return true
        return fields.any { field -> record.value(field).contains(n, ignoreCase = true) }
    }
}

object CatalogueReader {

    /** Headers are looked for only in the first rows of a sheet, where real tables start. */
    const val HEADER_SCAN_ROWS = 12
    const val MAX_RECORDS = 5000
    const val MIN_HEADER_CELLS = 3

    /**
     * Detects a catalogue table in [sheetPath] and reads its records.
     *
     * Returns null when the sheet does not look like a table (fewer than three header cells, or no data
     * row under them): a worksheet of pictures is not a catalogue and is never guessed into one.
     */
    fun read(
        zip: ZipSource,
        sheetPath: String,
        sheetName: String,
        shared: List<String> = SheetCells.sharedStrings(zip),
        maxRecords: Int = MAX_RECORDS,
    ): CatalogueTable? {
        val cells = SheetCells.read(zip, sheetPath, shared)
        return fromCells(cells, sheetName, maxRecords)
    }

    /** Same detection over already-read cells, so a caller that scanned a sheet once can reuse them. */
    fun fromCells(cells: List<CellValue>, sheetName: String, maxRecords: Int = MAX_RECORDS): CatalogueTable? {
        if (cells.isEmpty()) return null
        val byRow = cells.groupBy { it.row }
        val headerRow = detectHeaderRow(byRow) ?: return null
        val headerCells = byRow[headerRow].orEmpty().sortedBy { it.col }
        if (headerCells.size < MIN_HEADER_CELLS) return null

        // Every column between the first and the last header belongs to the table. A blank header cell is
        // still a column: dropping it would shift every value to its right one column to the left, which
        // is exactly how the columns at the right edge of a wide worksheet end up unreadable. A gap column
        // is kept when it has a header or carries data; a completely empty one adds nothing and is skipped.
        val firstColumn = headerCells.first().col
        val lastColumn = headerCells.last().col
        val dataColumns = cells.asSequence()
            .filter { it.row > headerRow && it.col in firstColumn..lastColumn }
            .map { it.col }
            .toSet()
        val columns = (firstColumn..lastColumn).filter { col ->
            headerCells.any { it.col == col } || col in dataColumns
        }
        val headerByColumn = headerCells.associateBy { it.col }
        val fields = columns.mapIndexed { index, col ->
            CatalogueField(headerByColumn[col]?.text.orEmpty(), index, col)
        }
        val byRowCol = cells.associateBy { it.row * 100_000L + it.col }
        val headerTexts = columns.map { col -> headerByColumn[col]?.text?.trim().orEmpty() }

        val records = ArrayList<CatalogueRecord>()
        for (row in (headerRow + 1)..(headerRow + maxRecords)) {
            val values = columns.map { col -> byRowCol[row * 100_000L + col]?.text.orEmpty() }
            if (values.all { it.isEmpty() }) {
                if (records.isNotEmpty()) break else continue
            }
            // A repeated header row is not a record.
            if (values.map { it.trim() } == headerTexts) continue
            records.add(CatalogueRecord(row, values))
        }
        if (records.isEmpty()) return null
        return CatalogueTable(sheetName, headerRow, fields, records)
    }

    /** The row with the most short, text-like, distinct cells near the top of the sheet. */
    private fun detectHeaderRow(byRow: Map<Int, List<CellValue>>): Int? {
        var best: Int? = null
        var bestCount = 0
        for (row in 0 until HEADER_SCAN_ROWS) {
            val cells = byRow[row] ?: continue
            val usable = cells.filter { cell ->
                val text = cell.text.trim()
                text.isNotEmpty() && text.length <= 40 && text.count { it.isDigit() } < text.length
            }.distinctBy { it.col }
            if (usable.size > bestCount) {
                bestCount = usable.size
                best = row
            }
        }
        return if (bestCount >= MIN_HEADER_CELLS) best else null
    }
}
