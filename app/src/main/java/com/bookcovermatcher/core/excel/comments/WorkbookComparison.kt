package com.bookcovermatcher.core.excel.comments

/** Per-worksheet metrics used by the comparison; every number comes from parsed data or is unknown. */
data class SheetMetrics(
    val name: String,
    val visibility: String,
    /** The worksheet's declared used range (`A1:P120`), or null when the part declares none. */
    val dimension: String?,
    val comments: Int,
    val linkedImages: Int,
    val unresolved: Int,
)

/**
 * One side of a comparison.
 *
 * [catalogueRecords] is **null when the parser found no catalogue table** — unknown, never zero.
 * [failure] marks a side that could not be parsed at all; its counts must not be read as data.
 */
data class WorkbookMetrics(
    val label: String,
    val originalName: String,
    val sha256: String,
    val sheets: List<SheetMetrics> = emptyList(),
    val mediaTotal: Int = 0,
    val mediaLinked: Int = 0,
    val mediaUnreferenced: Int = 0,
    val unresolvedAssociations: Int = 0,
    val catalogueRecords: Int? = null,
    val catalogueHeaders: List<String> = emptyList(),
    val note: String? = null,
    val failure: String? = null,
) {
    val usable: Boolean get() = failure == null
    val sheetCount: Int? get() = if (usable) sheets.size else null
    val commentTotal: Int? get() = if (usable) sheets.sumOf { it.comments } else null
    val linkedTotal: Int? get() = if (usable) mediaLinked else null
    val unresolvedTotal: Int? get() = if (usable) unresolvedAssociations else null
}

enum class SheetPresence { BOTH, LEFT_ONLY, RIGHT_ONLY }

/** One field that differs; a null side means "not applicable" (the sheet exists on one side only). */
data class MetricChange(val field: String, val left: String?, val right: String?) {
    val unknown: Boolean get() = left == null || right == null
}

data class SheetDiff(
    val name: String,
    val presence: SheetPresence,
    val left: SheetMetrics?,
    val right: SheetMetrics?,
    val changes: List<MetricChange>,
) {
    val identical: Boolean get() = presence == SheetPresence.BOTH && changes.isEmpty()
}

/**
 * A metadata-level comparison of two workbooks: names, order, visibility, used range and the counts the
 * parser can establish. It is **not** a cell-by-cell spreadsheet diff, and equal totals are never
 * treated as proof that two workbooks are equivalent.
 */
data class WorkbookDiff(
    val left: WorkbookMetrics,
    val right: WorkbookMetrics,
    val sheets: List<SheetDiff>,
    val totals: List<MetricChange>,
    val orderChanged: Boolean,
    val headersChanged: Boolean,
    val selfComparison: Boolean,
) {
    val additions: List<SheetDiff> get() = sheets.filter { it.presence == SheetPresence.RIGHT_ONLY }
    val removals: List<SheetDiff> get() = sheets.filter { it.presence == SheetPresence.LEFT_ONLY }
    val changed: List<SheetDiff> get() = sheets.filter { it.presence == SheetPresence.BOTH && it.changes.isNotEmpty() }
    val identicalSheets: List<SheetDiff> get() = sheets.filter { it.identical }
}

object WorkbookComparison {

    /** Builds one side from the scan + analysis; a [failure] produces an unusable, empty side. */
    fun metrics(
        label: String,
        originalName: String,
        sha256: String,
        model: WorkbookCommentImages,
        analysis: WorkbookAnalysis,
        note: String? = null,
    ): WorkbookMetrics {
        val linkedRows = analysis.images.filter { it.state == ImageState.LINKED }
        val unresolvedRows = analysis.images.filter { it.state == ImageState.UNRESOLVED }
        val sheets = analysis.sheets.map { sheet ->
            SheetMetrics(
                name = sheet.name,
                visibility = sheet.visibility,
                dimension = sheet.dimension,
                comments = model.comments.count { it.sheetName == sheet.name },
                linkedImages = linkedRows.count { row -> row.associations.any { it.sheetName == sheet.name } },
                unresolved = unresolvedRows.count { row -> row.associations.any { it.sheetName == sheet.name } },
            )
        }
        val catalogueRecords = if (analysis.tables.isEmpty()) null else analysis.tables.sumOf { it.records.size }
        return WorkbookMetrics(
            label = label,
            originalName = originalName,
            sha256 = sha256,
            sheets = sheets,
            mediaTotal = analysis.images.count { it.mediaPath != null },
            mediaLinked = linkedRows.size,
            mediaUnreferenced = analysis.images.count { it.state == ImageState.UNREFERENCED },
            unresolvedAssociations = unresolvedRows.size,
            catalogueRecords = catalogueRecords,
            catalogueHeaders = analysis.tables.firstOrNull()?.fields?.map { it.name }.orEmpty(),
            note = note,
        )
    }

    fun failure(label: String, originalName: String, sha256: String, reason: String): WorkbookMetrics =
        WorkbookMetrics(label = label, originalName = originalName, sha256 = sha256, failure = reason)

    /**
     * Compares two sides by **worksheet name** (Excel treats sheet names case-insensitively), never by
     * position: a renamed sheet becomes one removal plus one addition instead of being silently matched.
     * Order changes among the sheets both sides share are reported separately.
     */
    fun compare(left: WorkbookMetrics, right: WorkbookMetrics): WorkbookDiff {
        val rightByName = right.sheets.associateBy { it.name.lowercase() }
        val leftNames = left.sheets.map { it.name.lowercase() }.toSet()
        val diffs = ArrayList<SheetDiff>()

        for (sheet in left.sheets) {
            val match = rightByName[sheet.name.lowercase()]
            diffs.add(
                SheetDiff(
                    name = sheet.name,
                    presence = if (match == null) SheetPresence.LEFT_ONLY else SheetPresence.BOTH,
                    left = sheet,
                    right = match,
                    changes = if (match == null) emptyList() else changes(sheet, match),
                ),
            )
        }
        for (sheet in right.sheets) {
            if (sheet.name.lowercase() in leftNames) continue
            diffs.add(SheetDiff(sheet.name, SheetPresence.RIGHT_ONLY, null, sheet, emptyList()))
        }

        val commonLeft = left.sheets.map { it.name.lowercase() }.filter { it in rightByName.keys }
        val commonRight = right.sheets.map { it.name.lowercase() }.filter { it in leftNames }

        return WorkbookDiff(
            left = left,
            right = right,
            sheets = diffs,
            totals = totals(left, right),
            orderChanged = commonLeft != commonRight,
            headersChanged = left.catalogueHeaders.map { it.lowercase() } !=
                right.catalogueHeaders.map { it.lowercase() },
            selfComparison = left.usable && right.usable && left.sha256.isNotBlank() &&
                left.sha256 == right.sha256,
        )
    }

    private fun changes(left: SheetMetrics, right: SheetMetrics): List<MetricChange> = buildList {
        if (!left.visibility.equals(right.visibility, ignoreCase = true)) {
            add(MetricChange("Visibility", left.visibility, right.visibility))
        }
        if (left.comments != right.comments) add(MetricChange("Comments", "${left.comments}", "${right.comments}"))
        if (left.linkedImages != right.linkedImages) {
            add(MetricChange("Linked images", "${left.linkedImages}", "${right.linkedImages}"))
        }
        if (left.unresolved != right.unresolved) {
            add(MetricChange("Unresolved references", "${left.unresolved}", "${right.unresolved}"))
        }
        if (left.dimension != right.dimension) {
            add(MetricChange("Used range", left.dimension ?: "not declared", right.dimension ?: "not declared"))
        }
    }

    /** Overall counts; an unknown value stays **null** (the UI renders it as "unknown"). */
    private fun totals(left: WorkbookMetrics, right: WorkbookMetrics): List<MetricChange> = buildList {
        fun pair(field: String, a: Int?, b: Int?) {
            if (a != b) add(MetricChange(field, a?.toString(), b?.toString()))
        }
        pair("Worksheets", left.sheetCount, right.sheetCount)
        pair("Legacy comments", left.commentTotal, right.commentTotal)
        pair("Linked images", left.linkedTotal, right.linkedTotal)
        pair("Media files", if (left.usable) left.mediaTotal else null, if (right.usable) right.mediaTotal else null)
        pair(
            "Unreferenced media",
            if (left.usable) left.mediaUnreferenced else null,
            if (right.usable) right.mediaUnreferenced else null,
        )
        pair("Unresolved references", left.unresolvedTotal, right.unresolvedTotal)
        pair("Catalogue records", left.catalogueRecords, right.catalogueRecords)
    }
}
