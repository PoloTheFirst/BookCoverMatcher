package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.CatalogueTable

/**
 * Which source column carries each date meaning on **one worksheet**, chosen by the user.
 *
 * The app never fills these in by itself: a header that *looks* like a date is not evidence that it is
 * the date the user means (`出版日` may be the release date, `購入日` the day they bought it, and another
 * workbook may use `発売日` for something else). Both assignments are independent, and either can be
 * absent — "no date for this timeline" is a valid, honest configuration.
 *
 * The header **name** is stored, never the column position: inserting a column to the left must not move
 * the date assignment to its neighbour. A header that has since been renamed or removed is reported as
 * stale and the user is asked to choose again.
 */
data class SheetDateChoice(
    val publishedHeader: String? = null,
    val acquiredHeader: String? = null,
) {
    val isEmpty: Boolean get() = publishedHeader == null && acquiredHeader == null

    fun headerFor(mode: TimelineMode): String? = when (mode) {
        TimelineMode.PUBLICATION -> publishedHeader
        TimelineMode.ACQUISITION -> acquiredHeader
        // The import date comes from the saved-workbook library, never from a worksheet column.
        TimelineMode.IMPORT -> null
    }

    fun withHeader(mode: TimelineMode, header: String?): SheetDateChoice {
        val cleaned = header?.trim()?.takeIf { it.isNotEmpty() }
        return when (mode) {
            TimelineMode.PUBLICATION -> copy(publishedHeader = cleaned)
            TimelineMode.ACQUISITION -> copy(acquiredHeader = cleaned)
            TimelineMode.IMPORT -> this
        }
    }
}

/**
 * The date-column choices of one workbook, per worksheet.
 *
 * Scope is deliberate: the workbook (its SHA-256) plus the worksheet name. A mapping profile is reusable
 * across workbooks, so a date choice stored there would silently follow the profile into a different
 * collection; this one cannot.
 */
data class TimelineDateSettings(
    val workbookSha256: String = "",
    val bySheet: Map<String, SheetDateChoice> = emptyMap(),
) {
    val isConfigured: Boolean get() = bySheet.values.any { !it.isEmpty }

    /** Worksheets that have at least one date column chosen. */
    val configuredSheets: List<String> get() = bySheet.filterValues { !it.isEmpty }.keys.toList()

    fun choiceOf(sheetName: String): SheetDateChoice = bySheet[sheetName] ?: SheetDateChoice()

    /** Stores [choice] for [sheetName], dropping the entry entirely when nothing is chosen. */
    fun withChoice(sheetName: String, choice: SheetDateChoice): TimelineDateSettings = when {
        sheetName.isBlank() -> this
        choice.isEmpty -> copy(bySheet = bySheet - sheetName)
        else -> copy(bySheet = bySheet + (sheetName to choice))
    }

    fun withHeader(sheetName: String, mode: TimelineMode, header: String?): TimelineDateSettings =
        withChoice(sheetName, choiceOf(sheetName).withHeader(mode, header))

    fun withoutSheet(sheetName: String): TimelineDateSettings = withChoice(sheetName, SheetDateChoice())

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** How one chosen column stands: usable, gone, duplicated, or without a single readable date. */
enum class DateColumnState(val label: String) {
    /** The user has not chosen a column for this date meaning. */
    NOT_SET("Not chosen"),

    /** The column resolves to exactly one worksheet column and has at least one readable date. */
    READY("Ready"),

    /** The chosen header is not in the worksheet any more (renamed or deleted). */
    HEADER_MISSING("Column gone"),

    /** The chosen header now appears in more than one column; the app refuses to pick one. */
    HEADER_AMBIGUOUS("Duplicate header"),

    /** The column exists but holds no value that can be read as a date. */
    NO_READABLE_DATES("No readable dates"),
}

/** One previewed cell of the chosen column: the text as stored, and the date it could be read as. */
data class DateSample(val raw: String, val date: CatalogDate?) {
    val isReadable: Boolean get() = date != null
    val label: String get() = date?.label ?: "not a date"
}

/**
 * What one chosen date column actually contains.
 *
 * This is the evidence shown before (and after) confirming an assignment: how many values could be read,
 * how many cells are blank, how many cannot be read, and a few representative values. Nothing is
 * corrected, so a column of mixed formats says so instead of being quietly half-read.
 */
data class DateColumnReport(
    val sheetName: String,
    val mode: TimelineMode,
    val header: String?,
    /** The zero-based source column the header resolved to; null when it did not resolve. */
    val column: Int? = null,
    val state: DateColumnState = DateColumnState.NOT_SET,
    val readable: Int = 0,
    val blank: Int = 0,
    val malformed: Int = 0,
    val samples: List<DateSample> = emptyList(),
) {
    val isUsable: Boolean get() = state == DateColumnState.READY
    val hasChoice: Boolean get() = header != null

    /** Records in the column, the unreadable ones included. */
    val total: Int get() = readable + malformed + blank

    /** `G · 出版日付` — the letter makes the choice checkable against the worksheet itself. */
    val columnLabel: String
        get() = if (column == null) header.orEmpty() else CatalogueFiltering.columnLetter(column) + " \u00B7 " + header.orEmpty()

    /** One sentence a person can act on; never a silent zero. */
    val message: String
        get() = when (state) {
            DateColumnState.NOT_SET ->
                "No column is chosen for the ${mode.label.lowercase()} date on $sheetName."
            DateColumnState.HEADER_MISSING ->
                "The column \u201C${header.orEmpty()}\u201D chosen for $sheetName is not in this worksheet any " +
                    "more. Choose the column again."
            DateColumnState.HEADER_AMBIGUOUS ->
                "The header \u201C${header.orEmpty()}\u201D now appears in more than one column of $sheetName, so " +
                    "the app will not pick one. Choose the column again."
            DateColumnState.NO_READABLE_DATES ->
                if (malformed == 0) {
                    "The column \u201C${header.orEmpty()}\u201D has no values on $sheetName."
                } else {
                    "None of the $malformed value(s) in \u201C${header.orEmpty()}\u201D can be read as a date. " +
                        "The timeline keeps those records without a date."
                }
            DateColumnState.READY ->
                "$readable of ${readable + malformed} filled value(s) in \u201C${header.orEmpty()}\u201D can be " +
                    "read as a date" + if (blank > 0) ", $blank cell(s) are blank" else ""
        }
}

/**
 * Resolving a chosen header against a worksheet, and reading what it holds.
 *
 * Pure and deterministic: the same table and header always produce the same report, so the dialog can
 * show the truth before anything is saved, and the tests pin it without a workbook.
 */
object TimelineDates {

    /** Representative values previewed per column; a preview, not a summary of the whole column. */
    const val SAMPLE_LIMIT = 4

    /** The column [header] resolves to, or null when it is absent or ambiguous. Both are reported. */
    fun columnOf(table: CatalogueTable, header: String?): Int? {
        if (header.isNullOrBlank()) return null
        val wanted = ColumnMappings.normalize(header)
        val matches = table.fields.filter { ColumnMappings.normalize(it.name) == wanted }
        return if (matches.size == 1) matches.single().column else null
    }

    /**
     * Inspects [header] on [table] for [mode].
     *
     * A serial number is accepted here because the user said this column holds dates; outside these
     * configured columns a bare number is never read as a date.
     */
    fun inspect(table: CatalogueTable, mode: TimelineMode, header: String?): DateColumnReport {
        if (header.isNullOrBlank()) {
            return DateColumnReport(table.sheetName, mode, null, null, DateColumnState.NOT_SET)
        }
        val wanted = ColumnMappings.normalize(header)
        val matches = table.fields.filter { ColumnMappings.normalize(it.name) == wanted }
        if (matches.isEmpty()) {
            return DateColumnReport(table.sheetName, mode, header, null, DateColumnState.HEADER_MISSING)
        }
        if (matches.size > 1) {
            return DateColumnReport(table.sheetName, mode, header, null, DateColumnState.HEADER_AMBIGUOUS)
        }
        val field = matches.single()
        val texts = table.records.map { record -> record.value(field).trim() }
        val filled = texts.filter { it.isNotEmpty() }
        val parsed = filled.map { raw -> DateSample(raw, CatalogDates.parse(raw, allowSerial = true)) }
        val readable = parsed.count { it.isReadable }
        val malformed = parsed.size - readable
        return DateColumnReport(
            sheetName = table.sheetName,
            mode = mode,
            header = field.label,
            column = field.column,
            state = if (readable > 0) DateColumnState.READY else DateColumnState.NO_READABLE_DATES,
            readable = readable,
            blank = texts.size - filled.size,
            malformed = malformed,
            samples = parsed.take(SAMPLE_LIMIT),
        )
    }

    /** Every worksheet of [catalog] inspected for [mode] under [settings]; one report per table. */
    fun inspectAll(
        catalog: WorkbookCatalog,
        settings: TimelineDateSettings,
        mode: TimelineMode,
    ): List<DateColumnReport> = catalog.tables.values.map { table ->
        inspect(table, mode, settings.choiceOf(table.sheetName).headerFor(mode))
    }

    /**
     * The value of [column] for the record on [row], read from the table it belongs to.
     *
     * The record identity is the worksheet row, so this cannot hand a neighbouring row's date to a record.
     */
    fun valueAt(table: CatalogueTable, row: Int, column: Int): String? {
        val field: CatalogueField = table.fields.firstOrNull { it.column == column } ?: return null
        val record = table.records.firstOrNull { it.row == row } ?: return null
        return record.value(field)
    }

    /** Every column of [table] the user could choose, labelled with its letter and header. */
    fun choices(table: CatalogueTable): List<CatalogueField> = table.fields
}
