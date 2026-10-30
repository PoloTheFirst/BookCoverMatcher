package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.DiagnosticLevel
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages

/**
 * Builds the Spreadsheet Health Report from data that was actually parsed.
 *
 * Rules that matter:
 *  * An optional field that is absent is [HealthSeverity.INFO], never an error; the required title is an
 *    error when it is blank *and* when it is not mapped.
 *  * A field the profile does not map is not "missing": mapping issues report it once, and the summary
 *    names the unmapped fields.
 *  * Image findings come from the same association results the browser shows (`WorkbookAnalysis.images`),
 *    so a record cannot look healthy in the report and cover-less in the gallery.
 *  * One underlying issue produces one finding (findings are keyed by [HealthFinding.id]); per-record
 *    findings are capped so a large workbook cannot produce an unbounded report.
 */
object HealthReportBuilder {

    /** Per-record findings kept per kind before an aggregate note replaces the rest. */
    const val MAX_PER_RECORD_FINDINGS = 400

    /** Unreferenced media findings listed individually before an aggregate note. */
    const val MAX_UNREFERENCED = 200

    /** Diagnostics copied from the scan before an aggregate note. */
    const val MAX_DIAGNOSTICS = 20

    fun build(
        catalog: WorkbookCatalog,
        analysis: WorkbookAnalysis,
        model: WorkbookCommentImages?,
    ): HealthReport {
        val findings = LinkedHashMap<String, HealthFinding>()
        structure(findings, catalog, analysis, model)
        records(findings, catalog)
        images(findings, catalog, analysis)
        consistency(findings, catalog)

        val ordered = findings.values.sortedWith(
            compareBy<HealthFinding> { it.severity.ordinal }
                .thenBy { it.category.ordinal }
                .thenBy { it.id },
        )
        val summary = HealthSummary(
            errors = ordered.count { it.severity == HealthSeverity.ERROR },
            warnings = ordered.count { it.severity == HealthSeverity.WARNING },
            info = ordered.count { it.severity == HealthSeverity.INFO },
            total = ordered.size,
            byCategory = HealthCategory.entries.associateWith { category ->
                ordered.count { it.category == category }
            },
        )
        return HealthReport(
            findings = ordered,
            summary = summary,
            records = catalog.entries.size,
            sheets = catalog.sheetNames.size,
            withCover = catalog.withVerifiedCover,
            withoutCover = catalog.withoutVerifiedCover,
            unmappedFields = unmappedFields(catalog),
        )
    }

    // ---- workbook structure ------------------------------------------------------------------

    private fun structure(
        out: MutableMap<String, HealthFinding>,
        catalog: WorkbookCatalog,
        analysis: WorkbookAnalysis,
        model: WorkbookCommentImages?,
    ) {
        for (sheet in analysis.unreadableSheets) {
            out.putIfAbsent(
                "structure:unreadable:$sheet",
                HealthFinding(
                    id = "structure:unreadable:$sheet",
                    category = HealthCategory.STRUCTURE,
                    severity = HealthSeverity.ERROR,
                    title = "Worksheet could not be read",
                    detail = "\u201C$sheet\u201D is declared by the workbook but its part could not be parsed, so its " +
                        "records and images are not in this report.",
                    action = "Open the workbook in Excel and re-save it, then rescan.",
                    resolution = HealthResolution.MANUAL,
                    sheetName = sheet,
                ),
            )
        }

        if (model?.truncated == true) {
            out.putIfAbsent(
                "structure:truncated",
                HealthFinding(
                    id = "structure:truncated",
                    category = HealthCategory.STRUCTURE,
                    severity = HealthSeverity.WARNING,
                    title = "The scan stopped at a safety limit",
                    detail = "The workbook has more comments or media than this previewer scans in one pass, so " +
                        "this report covers only what was read.",
                    action = "Treat the counts as a lower bound; split very large workbooks if you need all of them.",
                    resolution = HealthResolution.INFORMATIONAL,
                ),
            )
        }

        val diagnostics = model?.diagnostics.orEmpty()
        diagnostics.take(MAX_DIAGNOSTICS).forEachIndexed { index, diagnostic ->
            if (diagnostic.level != DiagnosticLevel.WARNING) return@forEachIndexed
            val id = "structure:diagnostic:$index"
            out.putIfAbsent(
                id,
                HealthFinding(
                    id = id,
                    category = HealthCategory.STRUCTURE,
                    severity = HealthSeverity.WARNING,
                    title = "Parsing problem in ${diagnostic.part ?: "the workbook"}",
                    detail = diagnostic.message,
                    action = "Check that part in Excel; the rest of the workbook was still read.",
                    resolution = HealthResolution.INFORMATIONAL,
                ),
            )
        }
        if (diagnostics.size > MAX_DIAGNOSTICS) {
            out.putIfAbsent(
                "structure:diagnostic:more",
                HealthFinding(
                    id = "structure:diagnostic:more",
                    category = HealthCategory.STRUCTURE,
                    severity = HealthSeverity.INFO,
                    title = "More parsing diagnostics were not listed",
                    detail = "${diagnostics.size - MAX_DIAGNOSTICS} further diagnostic message(s) were recorded.",
                    action = "Open the Comments tab to see the full diagnostic list.",
                    resolution = HealthResolution.INFORMATIONAL,
                    count = diagnostics.size - MAX_DIAGNOSTICS,
                ),
            )
        }

        if (catalog.tables.isEmpty()) {
            out.putIfAbsent(
                "structure:no-table",
                HealthFinding(
                    id = "structure:no-table",
                    category = HealthCategory.STRUCTURE,
                    severity = HealthSeverity.INFO,
                    title = "No catalogue table was detected",
                    detail = "No worksheet here has a header row with at least three columns and rows under it, so " +
                        "there are no records to check.",
                    action = "If this workbook does contain a catalogue, check that its header row is near the top of " +
                        "the sheet.",
                    resolution = HealthResolution.INFORMATIONAL,
                ),
            )
        }
    }
    // ---- records ----------------------------------------------------------------------------

    private fun records(out: MutableMap<String, HealthFinding>, catalog: WorkbookCatalog) {
        var listed = 0
        var omitted = 0
        for (entry in catalog.entries) {
            when (entry.state(CatalogField.TITLE)) {
                FieldState.MAPPED -> Unit
                FieldState.MISSING -> out.putIfAbsent(
                    "record:title-empty:${entry.id}",
                    recordFinding(
                        entry, "record:title-empty", CatalogField.TITLE, HealthSeverity.ERROR,
                        "Title is empty",
                        "The title cell is blank, so this record cannot be identified.",
                        HealthResolution.MANUAL,
                    ),
                )
                FieldState.UNMAPPED -> out.putIfAbsent(
                    "record:title-unmapped:${entry.id}",
                    recordFinding(
                        entry, "record:title-unmapped", CatalogField.TITLE, HealthSeverity.ERROR,
                        "Title is not mapped",
                        "No title column is mapped for ${entry.sheetName}, so this record has no title.",
                        HealthResolution.MANUAL,
                    ),
                )
                FieldState.AMBIGUOUS -> out.putIfAbsent(
                    "record:title-ambiguous:${entry.id}",
                    recordFinding(
                        entry, "record:title-ambiguous", CatalogField.TITLE, HealthSeverity.ERROR,
                        "Title column is ambiguous",
                        "The mapped title header appears in more than one column, so no title was chosen.",
                        HealthResolution.MANUAL,
                    ),
                )
            }

            // Optional fields: a blank cell is information, never an error, and an unmapped field is
            // reported once by the mapping issues instead of once per record.
            for (field in CatalogField.ordered) {
                if (field.required) continue
                if (entry.state(field) != FieldState.MISSING) continue
                if (listed >= MAX_PER_RECORD_FINDINGS) {
                    omitted++
                    continue
                }
                listed++
                out.putIfAbsent(
                    "record:missing:${field.name}:${entry.id}",
                    recordFinding(
                        entry, "record:missing:${field.name}", field, HealthSeverity.INFO,
                        "${field.label} is empty",
                        "The ${field.label.lowercase()} cell is blank. That is optional information, not an error.",
                        HealthResolution.INFORMATIONAL,
                    ),
                )
            }

            entry.text(CatalogField.URL)?.let { url ->
                if (!validUrl(url)) {
                    out.putIfAbsent(
                        "record:url:${entry.id}",
                        recordFinding(
                            entry, "record:url", CatalogField.URL, HealthSeverity.WARNING,
                            "URL looks invalid",
                            "\u201C$url\u201D is not an http(s) address with a host.",
                            HealthResolution.MANUAL,
                        ),
                    )
                }
            }
            entry.text(CatalogField.PUBLISHED)?.let { raw ->
                // The publication field is a date column by definition, so an Excel serial is readable here.
                if (CatalogDates.isMalformed(raw, allowSerial = true)) {
                    out.putIfAbsent(
                        "record:date:${entry.id}",
                        recordFinding(
                            entry, "record:date", CatalogField.PUBLISHED, HealthSeverity.WARNING,
                            "Date cannot be read",
                            "\u201C$raw\u201D is not a date this app can read without guessing.",
                            HealthResolution.MANUAL,
                        ),
                    )
                }
            }
            entry.text(CatalogField.PRICE)?.let { raw ->
                if (raw.none { it.isDigit() }) {
                    out.putIfAbsent(
                        "record:price:${entry.id}",
                        recordFinding(
                            entry, "record:price", CatalogField.PRICE, HealthSeverity.WARNING,
                            "Price contains no number",
                            "\u201C$raw\u201D contains no digits, so it is not a price.",
                            HealthResolution.MANUAL,
                        ),
                    )
                }
            }
        }

        if (omitted > 0) {
            out.putIfAbsent(
                "record:missing:more",
                HealthFinding(
                    id = "record:missing:more",
                    category = HealthCategory.RECORDS,
                    severity = HealthSeverity.INFO,
                    title = "More empty optional fields were not listed individually",
                    detail = "$omitted further blank optional cell(s) were found.",
                    action = "Use the Gallery's missing-metadata filter to see them.",
                    resolution = HealthResolution.INFORMATIONAL,
                    count = omitted,
                ),
            )
        }
    }

    private fun recordFinding(
        entry: CatalogEntry,
        prefix: String,
        field: CatalogField,
        severity: HealthSeverity,
        title: String,
        detail: String,
        resolution: HealthResolution,
    ): HealthFinding = HealthFinding(
        id = "$prefix:${entry.id}",
        category = HealthCategory.RECORDS,
        severity = severity,
        title = title,
        detail = detail,
        action = "Open ${entry.locationLabel} to review it.",
        resolution = resolution,
        sheetName = entry.sheetName,
        recordId = entry.id,
        row = entry.row,
        field = field,
    )

    private fun validUrl(text: String): Boolean {
        val value = text.trim()
        val scheme = listOf("https://", "http://").firstOrNull { value.startsWith(it, ignoreCase = true) }
            ?: return false
        val host = value.substring(scheme.length).substringBefore('/').substringBefore('?')
        return host.isNotBlank() && host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }
    // ---- images -----------------------------------------------------------------------------

    private fun images(
        out: MutableMap<String, HealthFinding>,
        catalog: WorkbookCatalog,
        analysis: WorkbookAnalysis,
    ) {
        for (row in analysis.images.filter { it.state == ImageState.UNRESOLVED }) {
            val place = row.associations.firstOrNull()
            val id = "image:unresolved:${row.key}"
            out.putIfAbsent(
                id,
                HealthFinding(
                    id = id,
                    category = HealthCategory.IMAGES,
                    severity = HealthSeverity.WARNING,
                    title = "Image reference cannot be resolved",
                    detail = listOfNotNull(
                        place?.let { "A legacy note on ${it.sheetName}!${it.cell} points at this image." },
                        row.unresolvedDetail,
                    ).joinToString(" ").ifEmpty { "A legacy note points at an image that is not in the workbook." },
                    action = "Open the comment to see which reference failed; the note text is still readable.",
                    resolution = HealthResolution.MANUAL,
                    sheetName = place?.sheetName,
                    imagePath = row.fileName,
                ),
            )
        }

        val unreferenced = analysis.images.filter { it.state == ImageState.UNREFERENCED }
        unreferenced.take(MAX_UNREFERENCED).forEach { row ->
            val id = "image:unreferenced:${row.key}"
            out.putIfAbsent(
                id,
                HealthFinding(
                    id = id,
                    category = HealthCategory.IMAGES,
                    severity = HealthSeverity.INFO,
                    title = "Image is not associated with a record",
                    detail = "${row.fileName} exists in the package, but no legacy note this previewer reads points at it.",
                    action = "Nothing to do: it may belong to a drawing or a threaded comment, which this previewer " +
                        "does not read.",
                    resolution = HealthResolution.INFORMATIONAL,
                    imagePath = row.mediaPath ?: row.fileName,
                ),
            )
        }
        if (unreferenced.size > MAX_UNREFERENCED) {
            val extra = unreferenced.size - MAX_UNREFERENCED
            out.putIfAbsent(
                "image:unreferenced:more",
                HealthFinding(
                    id = "image:unreferenced:more",
                    category = HealthCategory.IMAGES,
                    severity = HealthSeverity.INFO,
                    title = "More unassociated images were not listed individually",
                    detail = "$extra further media file(s) have no association found by this previewer.",
                    action = "Use the Images tab's Unreferenced filter to see them.",
                    resolution = HealthResolution.INFORMATIONAL,
                    count = extra,
                ),
            )
        }

        for ((sheet, entries) in catalog.entriesBySheet) {
            val missing = entries.count { !it.hasVerifiedCover }
            if (missing == 0) continue
            val id = "image:no-cover:$sheet"
            out.putIfAbsent(
                id,
                HealthFinding(
                    id = id,
                    category = HealthCategory.IMAGES,
                    severity = HealthSeverity.INFO,
                    title = "$missing record(s) on $sheet have no verified cover",
                    detail = "A record counts as covered only when a legacy note on its own row resolved to an image " +
                        "in the workbook.",
                    action = "Open the Gallery and filter by \u201Cno cover\u201D to see those records.",
                    resolution = HealthResolution.INFORMATIONAL,
                    sheetName = sheet,
                    count = missing,
                ),
            )
        }
    }
    // ---- consistency ------------------------------------------------------------------------

    private fun consistency(out: MutableMap<String, HealthFinding>, catalog: WorkbookCatalog) {
        val identifiers = catalog.entries
            .mapNotNull { entry ->
                entry.text(CatalogField.IDENTIFIER)?.trim()?.takeIf { it.isNotEmpty() }?.let { it to entry }
            }
            .groupBy({ it.first.lowercase() }, { it.second })
            .filterValues { it.size > 1 }
        for ((key, group) in identifiers) {
            val id = "consistency:duplicate-id:$key"
            out.putIfAbsent(
                id,
                HealthFinding(
                    id = id,
                    category = HealthCategory.CONSISTENCY,
                    severity = HealthSeverity.WARNING,
                    title = "Duplicate product ID",
                    detail = "\u201C${group.first().text(CatalogField.IDENTIFIER)}\u201D appears on ${group.size} records: " +
                        group.take(5).joinToString(", ") { it.locationLabel } +
                        if (group.size > 5) "\u2026" else "",
                    action = "Check whether these are the same product listed twice.",
                    resolution = HealthResolution.MANUAL,
                    sheetName = group.first().sheetName,
                    count = group.size,
                ),
            )
        }

        val duplicates = catalog.entries
            .filter { !it.title.isNullOrBlank() }
            .groupBy { entry ->
                listOf(
                    entry.title.orEmpty().trim().lowercase(),
                    entry.text(CatalogField.CIRCLE).orEmpty().trim().lowercase(),
                    entry.text(CatalogField.ARTIST).orEmpty().trim().lowercase(),
                )
            }
            .filterValues { it.size > 1 }
        duplicates.entries.forEachIndexed { index, (key, group) ->
            val id = "consistency:possible-duplicate:" + key.joinToString("|") + ":" + index
            out.putIfAbsent(
                id,
                HealthFinding(
                    id = id,
                    category = HealthCategory.CONSISTENCY,
                    severity = HealthSeverity.INFO,
                    title = "Possible duplicate record",
                    detail = "${group.size} records share the same title, circle and artist: " +
                        group.take(5).joinToString(", ") { it.locationLabel } +
                        if (group.size > 5) "\u2026" else "",
                    action = "Review them; the app cannot prove two rows are the same product.",
                    resolution = HealthResolution.MANUAL,
                    sheetName = group.first().sheetName,
                    count = group.size,
                ),
            )
        }

        for (issue in catalog.issues) {
            val severity = when (issue.kind) {
                MappingIssueKind.REQUIRED_UNMAPPED -> HealthSeverity.ERROR
                MappingIssueKind.HEADER_MISSING -> HealthSeverity.WARNING
                MappingIssueKind.HEADER_AMBIGUOUS -> HealthSeverity.WARNING
                MappingIssueKind.SHARED_HEADER -> HealthSeverity.WARNING
            }
            val title = when (issue.kind) {
                MappingIssueKind.REQUIRED_UNMAPPED -> "Title column is not mapped"
                MappingIssueKind.HEADER_MISSING -> "Mapped column is missing"
                MappingIssueKind.HEADER_AMBIGUOUS -> "Mapped column is ambiguous"
                MappingIssueKind.SHARED_HEADER -> "One column is mapped to two fields"
            }
            val id = "consistency:mapping:${issue.id()}"
            out.putIfAbsent(
                id,
                HealthFinding(
                    id = id,
                    category = HealthCategory.CONSISTENCY,
                    severity = severity,
                    title = title,
                    detail = issue.detail,
                    action = "Open Fields and map the column explicitly.",
                    resolution = HealthResolution.MANUAL,
                    sheetName = issue.sheetName,
                    field = issue.field,
                ),
            )
        }
    }

    /** Fields the current mapping leaves unmapped on every worksheet it covers. */
    private fun unmappedFields(catalog: WorkbookCatalog): List<CatalogField> {
        val mappings = catalog.mappings.values
        if (mappings.isEmpty()) return emptyList()
        return CatalogField.ordered.filter { field -> mappings.none { it.isMapped(field) } }
    }
}