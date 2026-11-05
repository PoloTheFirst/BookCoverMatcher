package com.bookcovermatcher.core.excel.comments

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One column of the window: [letter] is display-only, [column] is the identity. */
data class SheetWindowColumn(val column: Int, val letter: String)

/**
 * One cell of the window, with everything the preview and its cell dialog need.
 *
 * [text] is exactly what the workbook stores — a date stays a serial number, a percentage stays a fraction —
 * so nothing on this path rewrites a source value. [display] is the same cell **as the workbook shows it**
 * (`2024-01-01`, `12.35%`), produced by the same number-format rule the Catalogue table uses, and null when no
 * format applies (a text cell, a `General` cell, a workbook without a styles part).
 *
 * [link] is the hyperlink the workbook itself stores for this cell. It is never inferred from the text: a cell
 * that merely reads like a URL has no link, and a link never replaces the value the cell holds.
 */
data class SheetWindowCell(
    val text: String?,
    val display: String? = null,
    val link: CellLink? = null,
) {
    val isBlank: Boolean get() = text.isNullOrEmpty()

    /** What the preview draws — and what Copy puts on the clipboard: the cell as it is displayed. */
    val shown: String get() = display ?: text.orEmpty()

    val isLinked: Boolean get() = link != null
}

/**
 * One row of the window; [cells] is aligned with [SheetWindow.columns].
 *
 * [row] is the **original worksheet row** (zero-based), never the position of the row on screen: the preview
 * shows a window around the verified row, so the two differ and must never be confused.
 */
data class SheetWindowRow(val row: Int, val cells: List<SheetWindowCell>) {

    /** The stored text of every cell, aligned with [SheetWindow.columns]; null where the cell is blank. */
    val values: List<String?> get() = cells.map { it.text }

    /** The displayed text of every cell, aligned with [SheetWindow.columns]. */
    val displays: List<String?> get() = cells.map { it.display }
}

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
    /**
     * Cells of [headerRow] for the same columns, when the header sits outside the window (it is then carried
     * separately so the values stay readable). Null when the header is inside the window already — it is one of
     * [rows] then, and drawing it twice would be a lie about the worksheet.
     */
    val headerCells: List<SheetWindowCell>? = null,
    /**
     * Columns of the worksheet this window does not draw, when the sheet is wider than [maxColumns].
     * Reported so a wide worksheet is never silently shortened; 0 in the normal case.
     */
    val hiddenColumns: Int = 0,
    /** The header text of each drawn column, when this worksheet has a header row: the cell dialog's label. */
    val headers: Map<Int, String> = emptyMap(),
) {
    /** The header row's stored text, aligned with [columns]; null when the header is inside the window. */
    val headerValues: List<String?>? get() = headerCells?.map { it.text }

    val targetCell: String get() = SheetCells.cellRef(targetRow, targetColumn)
    val targetColumnShown: Boolean get() = columns.any { it.column == targetColumn }

    /** Every column the worksheet actually uses, drawn or not. */
    val totalColumns: Int get() = columns.size + hiddenColumns

    /** `122–134` — the row range as a person reads it (one-based), for the header line. */
    val rowRangeLabel: String get() = "${firstRow + 1}\u2013${lastRow + 1}"

    /** The workbook's own header for [column], or null when this worksheet has no header there. */
    fun headerOf(column: Int): String? = headers[column]

    fun rowAt(row: Int): SheetWindowRow? = rows.firstOrNull { it.row == row }

    /**
     * The cell at a **source** [row] and [column], or null when this window does not draw it.
     *
     * This is the only way the cell dialog is opened from the preview: the caller passes the row and column it
     * actually drew, and the answer is looked up by identity — never by a screen position, so scrolling,
     * repositioning or a wide worksheet cannot make the dialog describe a different cell.
     */
    fun cellAt(row: Int, column: Int): SheetWindowCell? {
        val index = columns.indexOfFirst { it.column == column }
        if (index < 0) return null
        rowAt(row)?.let { line -> return line.cells.getOrNull(index) }
        if (row == headerRow) return headerCells?.getOrNull(index)
        return null
    }
}

/**
 * Builds [SheetWindow]s from cells that were already read for the sheet.
 *
 * Deterministic and dependency-free: the same cells and target always produce the same window, which
 * is what the tests pin. The target row is always present, even when the sheet has no values on it
 * (a note can sit on an empty cell); the surrounding rows simply show blanks.
 * [styles] and [links] are the workbook's own metadata: with them a date cell reads as a date and a linked cell
 * knows its destination; without them (a fixture, or a package with no styles part) every cell shows exactly
 * the text it stores. The stored text is never replaced either way.
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
        styles: WorkbookStyles? = null,
        links: Map<Long, CellLink> = emptyMap(),
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
            val line = byRow[rowIndex].orEmpty().associateBy { it.col }
            SheetWindowRow(
                row = rowIndex,
                cells = columns.map { columnInfo ->
                    cellOf(line[columnInfo.column], styles, links[SheetLinks.key(rowIndex, columnInfo.column)])
                },
            )
        }

        val headerInsideWindow = headerRow != null && headerRow in firstRow..lastRow
        val headerLine = if (headerRow != null) byRow[headerRow].orEmpty().associateBy { it.col } else emptyMap()
        val headerCells = if (headerRow != null && !headerInsideWindow) {
            columns.map { columnInfo ->
                cellOf(headerLine[columnInfo.column], styles, links[SheetLinks.key(headerRow, columnInfo.column)])
            }
        } else {
            null
        }
        // The header text of a column, whether the header row is drawn or carried: it labels the cell dialog.
        val headers = headerLine.entries
            .filter { (col, cell) -> cell.text.isNotBlank() && col in chosen }
            .associate { (col, cell) -> col to cell.text }

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
            headerCells = headerCells,
            hiddenColumns = hidden,
            headers = headers,
        )
    }

    /**
     * One cell, read the way the workbook shows it.
     *
     * The display text comes from the cell's **own** number format through [WorkbookStyles.display] — the same
     * call the Catalogue table makes — so the two views can never disagree about a value. A blank cell (or a row
     * the worksheet left empty) has no text and no format; nothing is filled in.
     */
    private fun cellOf(cell: CellValue?, styles: WorkbookStyles?, link: CellLink?): SheetWindowCell {
        if (cell == null) return SheetWindowCell(null)
        val display = styles?.display(cell.styleIndex, cell.text, cell.numeric) ?: cell.text
        return SheetWindowCell(text = cell.text, display = display, link = link)
    }
}
