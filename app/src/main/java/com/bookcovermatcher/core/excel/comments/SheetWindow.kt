package com.bookcovermatcher.core.excel.comments

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One column of the window: [letter] is display-only, [column] is the identity. */
data class SheetWindowColumn(val column: Int, val letter: String)

/** One row of the window; [values] is aligned with [SheetWindow.columns], null where the cell is blank. */
data class SheetWindowRow(val row: Int, val values: List<String?>)

/**
 * A small read-only window of a worksheet, centred on a verified row.
 *
 * This is what the "go to source" view draws: real cell values from the worksheet part, the row that
 * was verified, and (when the worksheet is a detected catalogue) its header row. It is a window, not a
 * spreadsheet: a fixed number of rows around the target. Every column the worksheet uses is included —
 * a wide worksheet is never cut down to the columns nearest the target — and [hiddenColumns] reports the
 * remainder only if a sheet is wider than the hard bound.
 */
data class SheetWindow(
    val sheetName: String,
    val sheetPath: String,
    /** Zero-based, inclusive bounds of the rows this window shows. */
    val firstRow: Int,
    val lastRow: Int,
    val columns: List<SheetWindowColumn>,
    val rows: List<SheetWindowRow>,
    val targetRow: Int,
    val targetColumn: Int,
    /** The detected catalogue header row, when this worksheet is a table. */
    val headerRow: Int? = null,
    /** Values of [headerRow] for the same columns; null when the header is inside the window already. */
    val headerValues: List<String?>? = null,
    /**
     * Columns of the worksheet this window does not draw, when the sheet is wider than [maxColumns].
     * Reported so a wide worksheet is never silently shortened; 0 in the normal case.
     */
    val hiddenColumns: Int = 0,
) {
    val targetCell: String get() = SheetCells.cellRef(targetRow, targetColumn)
    val targetColumnShown: Boolean get() = columns.any { it.column == targetColumn }

    /** Every column the worksheet actually uses, drawn or not. */
    val totalColumns: Int get() = columns.size + hiddenColumns

    /** `122–134` — the row range as a person reads it (one-based), for the header line. */
    val rowRangeLabel: String get() = "${firstRow + 1}\u2013${lastRow + 1}"
}

/**
 * Builds [SheetWindow]s from cells that were already read for the sheet.
 *
 * Deterministic and dependency-free: the same cells and target always produce the same window, which
 * is what the tests pin. The target row is always present, even when the sheet has no values on it
 * (a note can sit on an empty cell); the surrounding rows simply show blanks.
 */
object SheetWindows {

    /** Rows shown above and below the target row. */
    const val CONTEXT_ROWS = 6

    /**
     * Columns drawn at most. Well above any real catalogue (the sample `LIST` sheet has 24), so the whole
     * width is shown and the view scrolls horizontally; only an extreme sheet is bounded, and the window
     * then says how many columns it left out instead of pretending the sheet ended.
     */
    const val MAX_COLUMNS = 96

    fun build(
        sheetName: String,
        sheetPath: String,
        cells: List<CellValue>,
        targetRow: Int,
        targetColumn: Int,
        headerRow: Int? = null,
        context: Int = CONTEXT_ROWS,
        maxColumns: Int = MAX_COLUMNS,
    ): SheetWindow {
        val row = targetRow.coerceAtLeast(0)
        val column = targetColumn.coerceAtLeast(0)
        val byRow = cells.groupBy { it.row }
        val sheetMaxRow = cells.maxOfOrNull { it.row } ?: row
        val firstRow = max(0, row - context.coerceAtLeast(0))
        val lastRow = max(row, min(sheetMaxRow, row + context.coerceAtLeast(0)))

        val visibleRows = (firstRow..lastRow).filter { it in byRow || it == row }
        val usedColumns = visibleRows
            .flatMap { byRow[it].orEmpty() }
            .map { it.col }
            .plus(column)
            .distinct()
            .sorted()

        val chosen = if (usedColumns.size <= maxColumns.coerceAtLeast(1)) {
            usedColumns
        } else {
            // A sheet wider than the hard bound: keep the verified cell visible and say what was left out.
            listOf(column).plus(usedColumns.sortedBy { abs(it - column) }).distinct()
                .take(maxColumns.coerceAtLeast(1)).sorted()
        }
        val hidden = (usedColumns.size - chosen.size).coerceAtLeast(0)
        val columns = chosen.map { SheetWindowColumn(it, CatalogueFiltering.columnLetter(it)) }

        val windowRows = visibleRows.map { rowIndex ->
            val texts = byRow[rowIndex].orEmpty().associate { it.col to it.text }
            SheetWindowRow(rowIndex, columns.map { texts[it.column] })
        }

        val headerInsideWindow = headerRow != null && headerRow in firstRow..lastRow
        val headerValues = if (headerRow != null && !headerInsideWindow) {
            val texts = byRow[headerRow].orEmpty().associate { it.col to it.text }
            columns.map { texts[it.column] }
        } else {
            null
        }

        return SheetWindow(
            sheetName = sheetName,
            sheetPath = sheetPath,
            firstRow = firstRow,
            lastRow = lastRow,
            columns = columns,
            rows = windowRows,
            targetRow = row,
            targetColumn = column,
            headerRow = headerRow,
            headerValues = headerValues,
            hiddenColumns = hidden,
        )
    }
}
