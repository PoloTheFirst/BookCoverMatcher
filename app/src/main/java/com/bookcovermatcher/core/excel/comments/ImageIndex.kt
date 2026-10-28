package com.bookcovermatcher.core.excel.comments

/** What the inventory knows about one image (or one association that could not be resolved). */
enum class ImageState {
    /** A verified legacy comment references this media part. */
    LINKED,

    /** The media part exists but nothing in the supported pipeline references it. */
    UNREFERENCED,

    /** A comment references an image that could not be resolved to a package part. */
    UNRESOLVED,
}

enum class ImageSort { NAME, SIZE, FORMAT, WORKSHEET, CELL, TITLE }

/** The catalogue record an image belongs to, through a legacy comment on the same worksheet row. */
data class CatalogueHit(
    val sheetName: String,
    val row: Int,
    val title: String?,
    val author: String?,
    val circle: String?,
    val fields: Map<String, String>,
)

/** One row of the All Images inventory. */
data class ImageRow(
    /** Stable key: the media path, or `unresolved:<ref>` for an association without a media part. */
    val key: String,
    val mediaPath: String?,
    val fileName: String,
    val format: String,
    /** -1 when the bytes were not read (the list never reads every file). */
    val sizeBytes: Long,
    /** Filled by the UI once a thumbnail is decoded; null means "not known yet", never 0. */
    val width: Int?,
    val height: Int?,
    val state: ImageState,
    val associations: List<MediaAssociation>,
    val catalogue: List<CatalogueHit>,
    /** Why an association is unresolved, when it is. */
    val unresolvedDetail: String?,
) {
    val sheetName: String? get() = associations.firstOrNull()?.sheetName
    val cell: String? get() = associations.firstOrNull()?.cell

    /** Union of every catalogue field of every hit — used by the filters and the search. */
    val catalogueValues: Map<String, String>
        get() {
            if (catalogue.size <= 1) return catalogue.firstOrNull()?.fields.orEmpty()
            val merged = LinkedHashMap<String, String>()
            catalogue.forEach { hit -> hit.fields.forEach { (k, v) -> if (v.isNotBlank()) merged.putIfAbsent(k, v) } }
            return merged
        }
}

/** Catalogue-image coverage, computed from parsed data only. */
data class ImageCoverage(
    val recordsTotal: Int,
    val recordsWithImage: Int,
    val unresolvedAssociations: Int,
    val unreferencedMedia: Int,
) {
    val recordsWithoutImage: Int get() = (recordsTotal - recordsWithImage).coerceAtLeast(0)
}

object ImageIndex {

    /**
     * Builds the inventory from the scan result and the detected catalogue tables.
     *
     * Media with a verified association is LINKED, media nothing references is UNREFERENCED, and a
     * comment reference that could not be resolved is UNRESOLVED — it is never folded into
     * "unreferenced", because the pipeline failed before it could decide.
     */
    fun build(model: WorkbookCommentImages, tables: List<CatalogueTable>): List<ImageRow> {
        val hitsByMedia = HashMap<String, MutableList<CatalogueHit>>()
        val unresolved = ArrayList<ImageRow>()

        for (comment in model.comments) {
            val cell = SheetCells.refToCell(comment.cell)
            val table = tables.firstOrNull { it.sheetName == comment.sheetName }
            val record = cell?.let { table?.recordAt(it.first) }
            val hit = if (record != null && table != null) {
                CatalogueHit(
                    sheetName = comment.sheetName,
                    row = record.row,
                    title = table.titleField?.let { field -> record.value(field).ifBlank { null } },
                    author = table.authorField?.let { field -> record.value(field).ifBlank { null } },
                    circle = table.circleField?.let { field -> record.value(field).ifBlank { null } },
                    fields = table.fields.associate { field -> field.name to record.value(field) },
                )
            } else {
                null
            }

            for (path in comment.mediaPaths) {
                if (hit != null) hitsByMedia.getOrPut(path) { ArrayList() }.add(hit)
            }
            for (ref in comment.media.filter { it.resolution == MediaResolution.UNRESOLVED }) {
                unresolved.add(
                    ImageRow(
                        key = "unresolved:${comment.sheetName}!${comment.cell}:${ref.reference}",
                        mediaPath = null,
                        fileName = ref.reference,
                        format = "unknown",
                        sizeBytes = -1L,
                        width = null,
                        height = null,
                        state = ImageState.UNRESOLVED,
                        associations = listOf(
                            MediaAssociation(comment.sheetName, comment.cell, preview(comment.text)),
                        ),
                        catalogue = listOfNotNull(hit),
                        unresolvedDetail = ref.detail,
                    ),
                )
            }
        }

        val media = model.media.map { entry ->
            ImageRow(
                key = entry.path,
                mediaPath = entry.path,
                fileName = entry.fileName,
                format = entry.format,
                sizeBytes = entry.sizeBytes,
                width = null,
                height = null,
                state = if (entry.isReferenced) ImageState.LINKED else ImageState.UNREFERENCED,
                associations = entry.associations,
                catalogue = hitsByMedia[entry.path].orEmpty().distinctBy { "${it.sheetName}!${it.row}" },
                unresolvedDetail = null,
            )
        }
        return media + unresolved
    }

    /**
     * Coverage from parsed data only: a record counts as "with image" when a verified comment on its row
     * resolved to an existing media part. Nothing is assumed about the rest.
     */
    fun coverage(tables: List<CatalogueTable>, rows: List<ImageRow>): ImageCoverage {
        val linkedPairs = rows
            .filter { it.state == ImageState.LINKED && it.mediaPath != null }
            .flatMap { row -> row.catalogue.map { it.sheetName to it.row } }
            .toSet()
        val withImage = tables.sumOf { table ->
            table.records.count { record -> (table.sheetName to record.row) in linkedPairs }
        }
        return ImageCoverage(
            recordsTotal = tables.sumOf { it.records.size },
            recordsWithImage = withImage,
            unresolvedAssociations = rows.count { it.state == ImageState.UNRESOLVED },
            unreferencedMedia = rows.count { it.state == ImageState.UNREFERENCED },
        )
    }

    /** Case-insensitive over filename, worksheet, cell, comment text, unresolved detail and every field. */
    fun matches(row: ImageRow, needle: String): Boolean {
        val n = needle.trim()
        if (n.isEmpty()) return true
        if (row.fileName.contains(n, ignoreCase = true)) return true
        if (row.unresolvedDetail?.contains(n, ignoreCase = true) == true) return true
        if (row.associations.any {
                it.sheetName.contains(n, ignoreCase = true) ||
                    it.cell.contains(n, ignoreCase = true) ||
                    it.commentPreview.contains(n, ignoreCase = true)
            }
        ) {
            return true
        }
        return row.catalogue.any { hit -> hit.fields.values.any { it.contains(n, ignoreCase = true) } }
    }

    /** Every requested catalogue filter must match the row (exact value, after trimming). */
    fun matchesCatalogueFilters(
        row: ImageRow,
        filters: List<CatalogueFilter>,
        tables: List<CatalogueTable>,
    ): Boolean {
        if (filters.isEmpty()) return true
        // Column identity (worksheet + column), not header text: duplicate headers stay independent.
        val bySheet = tables.associateBy { it.sheetName }
        return filters.all { filter ->
            val table = bySheet[filter.sheetName] ?: return@all false
            val field = table.fields.firstOrNull { it.column == filter.column } ?: return@all false
            row.catalogue.any { hit ->
                hit.sheetName == filter.sheetName &&
                    table.recordAt(hit.row)?.value(field)?.trim() == filter.value.trim()
            }
        }
    }

    /**
     * Deterministic ordering. A row without the sort field goes last in ascending order (missing size,
     * missing worksheet, missing title) and the [ImageRow.key] breaks every tie, so paging and tests are
     * stable regardless of the map iteration order.
     */
    fun sortRows(rows: List<ImageRow>, sort: ImageSort, ascending: Boolean): List<ImageRow> {
        val base: Comparator<ImageRow> = when (sort) {
            // Natural order: image2.jpg before image10.jpg. The exact-name comparison keeps
            // "image2.jpg" / "image02.jpg" deterministic, and `key` below breaks any remaining tie.
            ImageSort.NAME -> Comparator { a, b -> NaturalSort.compareNames(a.fileName, b.fileName) }
            ImageSort.SIZE -> compareBy { if (it.sizeBytes < 0) Long.MAX_VALUE else it.sizeBytes }
            ImageSort.FORMAT -> compareBy { it.format.lowercase() }
            ImageSort.WORKSHEET -> compareBy { it.sheetName?.lowercase() ?: "\uFFFF" }
            ImageSort.CELL -> compareBy<ImageRow> { it.sheetName?.lowercase() ?: "\uFFFF" }
                .thenBy { SheetCells.refToCell(it.cell)?.first ?: Int.MAX_VALUE }
                .thenBy { SheetCells.refToCell(it.cell)?.second ?: Int.MAX_VALUE }
            ImageSort.TITLE -> compareBy { it.catalogue.firstOrNull()?.title?.lowercase() ?: "\uFFFF" }
        }
        val ordered = rows.sortedWith(base.thenBy { it.key })
        return if (ascending) ordered else ordered.reversed()
    }

    /** The visible rows: state filter + free-text search + catalogue filters, then sorting. */
    fun query(
        rows: List<ImageRow>,
        needle: String,
        states: Set<ImageState>,
        filters: List<CatalogueFilter> = emptyList(),
        sort: ImageSort = ImageSort.NAME,
        ascending: Boolean = true,
        tables: List<CatalogueTable> = emptyList(),
        sheetName: String? = null,
    ): List<ImageRow> = sortRows(
        rows.filter { row ->
            (states.isEmpty() || row.state in states) &&
                (sheetName == null || row.associations.any { it.sheetName == sheetName }) &&
                matches(row, needle) &&
                matchesCatalogueFilters(row, filters, tables)
        },
        sort,
        ascending,
    )

    private fun preview(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= 120) line else line.take(119) + "\u2026"
    }
}
