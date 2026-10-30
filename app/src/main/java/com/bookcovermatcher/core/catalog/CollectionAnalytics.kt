package com.bookcovermatcher.core.catalog

/** One labelled bucket of an aggregation. */
data class MetricCount(val label: String, val count: Int)

/**
 * Statistics for one workbook catalog.
 *
 * Rules, so every number can be checked:
 *  * One [CatalogEntry] is one source record (identity = worksheet part + row); nothing is counted twice.
 *  * A blank cell counts as *missing*, never as a value, and never as zero.
 *  * Values are counted as the workbook writes them. Artist strings are not split on separators (that
 *    would invent artists), while tags are split on `,` and `\u3001` because a tag list is a list.
 *  * Potential duplicates are reported, never subtracted from the totals.
 *  * A record whose publication date is absent or unreadable is counted in [unknownPublicationYear],
 *    never in a fabricated year.
 */
data class CollectionAnalytics(
    val records: Int,
    val sheets: Int,
    val withVerifiedCover: Int,
    val withoutVerifiedCover: Int,
    val missingTitle: Int,
    val missingCircle: Int,
    val missingArtist: Int,
    val missingIdentifier: Int,
    val duplicateIdentifierGroups: Int,
    val duplicateIdentifierRecords: Int,
    val potentialDuplicateGroups: Int,
    val byCircle: List<MetricCount>,
    val byArtist: List<MetricCount>,
    val byTags: List<MetricCount>,
    val byWorksheet: List<MetricCount>,
    val byPublicationYear: List<MetricCount>,
    val unknownPublicationYear: Int,
    val mappedFields: List<CatalogField>,
    val unmappedFields: List<CatalogField>,
)

object CollectionAnalyticsBuilder {

    /** How many values of a grouped metric are shown; the rest stay in the workbook, not in the chart. */
    const val TOP_VALUES = 50

    fun build(catalog: WorkbookCatalog, scope: SheetScope = SheetScope.ALL): CollectionAnalytics {
        val entries = catalog.entries.filter { scope.allows(it.sheetPath, it.sheetName) }
        val tables = catalog.tables.values.filter { table ->
            val path = catalog.sheets.firstOrNull { it.name == table.sheetName }?.partPath.orEmpty()
            scope.allows(path, table.sheetName)
        }
        val mappings = tables.mapNotNull { table -> catalog.mappings[table.sheetName] }
        val mappedFields = CatalogField.ordered.filter { field -> mappings.any { it.isMapped(field) } }

        val identifiers = entries
            .mapNotNull { entry -> entry.text(CatalogField.IDENTIFIER)?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }

        val duplicates = entries
            .filter { !it.title.isNullOrBlank() }
            .groupBy { entry ->
                listOf(
                    entry.title.orEmpty().trim().lowercase(),
                    entry.text(CatalogField.CIRCLE).orEmpty().trim().lowercase(),
                    entry.text(CatalogField.ARTIST).orEmpty().trim().lowercase(),
                )
            }
            .filterValues { it.size > 1 }

        val years = HashMap<Int, Int>()
        var unknownYear = 0
        for (entry in entries) {
            // The publication field is a date column by definition: an Excel serial is a real date here.
            val date = CatalogDates.parse(entry.text(CatalogField.PUBLISHED), allowSerial = true)
            if (date == null) unknownYear++ else years[date.year] = (years[date.year] ?: 0) + 1
        }

        return CollectionAnalytics(
            records = entries.size,
            sheets = tables.size,
            withVerifiedCover = entries.count { it.hasVerifiedCover },
            withoutVerifiedCover = entries.count { !it.hasVerifiedCover },
            missingTitle = entries.count { it.state(CatalogField.TITLE) == FieldState.MISSING },
            missingCircle = entries.count { it.state(CatalogField.CIRCLE) == FieldState.MISSING },
            missingArtist = entries.count { it.state(CatalogField.ARTIST) == FieldState.MISSING },
            missingIdentifier = entries.count { it.state(CatalogField.IDENTIFIER) == FieldState.MISSING },
            duplicateIdentifierGroups = identifiers.size,
            duplicateIdentifierRecords = identifiers.values.sum(),
            potentialDuplicateGroups = duplicates.size,
            byCircle = top(entries.mapNotNull { it.text(CatalogField.CIRCLE) }),
            byArtist = top(entries.mapNotNull { it.text(CatalogField.ARTIST) }),
            byTags = top(entries.flatMap { tagsOf(it) }),
            byWorksheet = top(entries.map { it.sheetName }),
            byPublicationYear = years.entries.sortedBy { it.key }.map { MetricCount(it.key.toString(), it.value) },
            unknownPublicationYear = unknownYear,
            mappedFields = mappedFields,
            unmappedFields = CatalogField.ordered - mappedFields.toSet(),
        )
    }

    /** `a, b` and `a\u3001b` are both tag lists; blank pieces are dropped. */
    fun tagsOf(entry: CatalogEntry): List<String> =
        entry.text(CatalogField.TAGS).orEmpty()
            .split(',', '\u3001', ';', '\uFF1B')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Values counted, most frequent first, ties by label so the list is deterministic. */
    private fun top(values: List<String>): List<MetricCount> = values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
        .take(TOP_VALUES)
        .map { MetricCount(it.key, it.value) }
}