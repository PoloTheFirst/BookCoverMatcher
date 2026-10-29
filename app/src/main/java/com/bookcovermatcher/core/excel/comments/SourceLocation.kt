package com.bookcovermatcher.core.excel.comments

/**
 * Where an image or a catalogue record comes from in the workbook — or why that cannot be stated.
 *
 * Every destination is one the parser established: a legacy comment's own cell, or the worksheet row
 * of a record the structural table reader detected. **Nothing is derived from image order, file names,
 * media numbering or list position**, and a source that cannot be established is modelled as its own
 * case so no caller has to invent one.
 *
 * The destination is addressed by [Candidate.sheetPath] (the package part) rather than by the display
 * name, because the name is a label: it may contain spaces, punctuation, Unicode or a trailing space,
 * and it must not be normalised away.
 *
 * Plain Kotlin (no `android.*`), so the whole mapping is unit-tested on a desktop JVM.
 */
sealed interface SourceLocation {

    /**
     * One worksheet destination the parser can prove.
     *
     * [row] and [column] are zero-based. [rowAnchor] is true for a catalogue record, where [cell] is
     * the **first field** cell of the record's row: the row is what the parser verified, so the UI must
     * call it a row, not "the record's cell".
     */
    data class Candidate(
        val sheetName: String,
        val sheetPath: String,
        val cell: String,
        val row: Int,
        val column: Int,
        val rowAnchor: Boolean = false,
    )

    /** Every verified destination. More than one candidate means the workbook itself states more than one. */
    data class Verified(val destinations: List<Candidate>) : SourceLocation

    /**
     * A comment references an image that could not be resolved to a package part. The note's cell is
     * known, but the image is not in the workbook, so there is no image source to open.
     */
    data class Unresolved(val sheetName: String?, val cell: String?, val detail: String?) : SourceLocation

    /** The media part exists, but nothing in the supported pipeline points at it. */
    data object Unreferenced : SourceLocation

    /**
     * The record exists but its worksheet is not in this workbook any more (stale analysis), or the
     * cell reference cannot be parsed. No destination is offered instead of guessing one.
     */
    data object Unknown : SourceLocation

    /** The verified destinations, or an empty list for every case that has none. */
    val candidates: List<Candidate> get() = (this as? Verified)?.destinations.orEmpty()
}

/**
 * Builds [SourceLocation]s from the parsed models.
 *
 * The functions are deliberately total: an association that cannot be resolved to a worksheet part and
 * a parseable cell becomes [SourceLocation.Unknown] rather than a fabricated location.
 */
object SourceLocations {

    /** The worksheet with exactly this name (the workbook's own spelling), or null. */
    fun sheetOf(name: String?, sheets: List<SheetInfo>): SheetInfo? {
        if (name == null) return null
        return sheets.firstOrNull { it.name == name }
    }

    /**
     * An inventory row.
     *
     * `LINKED` uses the media part's verified comment associations; `UNREFERENCED` and `UNRESOLVED`
     * have no destination by definition. An association whose worksheet is missing, or whose cell
     * reference does not parse, contributes nothing — the row is then [SourceLocation.Unknown].
     */
    fun ofImage(row: ImageRow, sheets: List<SheetInfo>): SourceLocation = when (row.state) {
        ImageState.UNREFERENCED -> SourceLocation.Unreferenced
        ImageState.UNRESOLVED -> SourceLocation.Unresolved(
            sheetName = row.associations.firstOrNull()?.sheetName,
            cell = row.associations.firstOrNull()?.cell,
            detail = row.unresolvedDetail,
        )
        ImageState.LINKED -> {
            val destinations = row.associations
                .mapNotNull { association -> cellCandidate(association.sheetName, association.cell, sheets) }
                .distinctBy { "${it.sheetPath}!${it.cell}" }
            if (destinations.isEmpty()) SourceLocation.Unknown else SourceLocation.Verified(destinations)
        }
    }

    /** A legacy comment's cell: the comment XML's own `ref`, which is the cell it is attached to. */
    fun ofComment(entry: LegacyCommentEntry, sheets: List<SheetInfo>): SourceLocation {
        val destination = cellCandidate(entry.sheetName, entry.cell, sheets) ?: return SourceLocation.Unknown
        return SourceLocation.Verified(listOf(destination))
    }

    /**
     * A detected catalogue record. The verified location is the record's **row**; [Candidate.cell] is
     * the row's first field (an anchor for the window), and [Candidate.rowAnchor] says so.
     */
    fun ofRecord(table: CatalogueTable, record: CatalogueRecord, sheets: List<SheetInfo>): SourceLocation {
        val sheet = sheetOf(table.sheetName, sheets) ?: return SourceLocation.Unknown
        val anchor = table.fields.minByOrNull { it.column } ?: return SourceLocation.Unknown
        if (record.row < 0) return SourceLocation.Unknown
        return SourceLocation.Verified(
            listOf(
                SourceLocation.Candidate(
                    sheetName = sheet.name,
                    sheetPath = sheet.partPath,
                    cell = SheetCells.cellRef(record.row, anchor.column),
                    row = record.row,
                    column = anchor.column,
                    rowAnchor = true,
                ),
            ),
        )
    }

    private fun cellCandidate(sheetName: String, cell: String, sheets: List<SheetInfo>): SourceLocation.Candidate? {
        val sheet = sheetOf(sheetName, sheets) ?: return null
        val (row, column) = SheetCells.refToCell(cell) ?: return null
        return SourceLocation.Candidate(
            sheetName = sheet.name,
            sheetPath = sheet.partPath,
            cell = SheetCells.cellRef(row, column),
            row = row,
            column = column,
        )
    }
}
