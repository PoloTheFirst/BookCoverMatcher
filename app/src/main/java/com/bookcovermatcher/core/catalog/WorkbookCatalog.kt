// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.SheetInfo

/** One image as a record sees it: the verified media part, or the reason there is none. */
data class CatalogImage(val mediaPath: String?, val state: ImageState, val detail: String? = null) {
    val isVerified: Boolean get() = state == ImageState.LINKED && mediaPath != null
}

/**
 * One catalogue record, in the canonical shape the five features share.
 *
 * [id] is the record identity: the worksheet part plus the zero-based source row. It is stable while the
 * workbook is unchanged, so a health finding, a gallery card, an analytics bucket and a timeline entry
 * all refer to the same thing. [values] keeps every canonical field, including the ones the profile did
 * not map, so "absent" and "unmapped" never look alike.
 */
data class CatalogEntry(
    val id: String,
    val workbookSha256: String,
    val sheetName: String,
    val sheetPath: String,
    /** Zero-based source row; the UI shows [locationLabel] (one-based). */
    val row: Int,
    val values: Map<CatalogField, FieldValue>,
    val images: List<CatalogImage>,
) {
    fun value(field: CatalogField): FieldValue = values[field] ?: FieldValue(field, FieldState.UNMAPPED)
    fun text(field: CatalogField): String? = value(field).text
    fun state(field: CatalogField): FieldState = value(field).state

    val title: String? get() = text(CatalogField.TITLE)
    val hasVerifiedCover: Boolean get() = images.any { it.isVerified }
    val hasUnresolvedImage: Boolean get() = images.any { it.state == ImageState.UNRESOLVED }

    /** The workbook's own ordering value for this record, exactly as the cell spells it (null = none). */
    val defaultOrder: String? get() = text(CatalogField.DEFAULT_ORDER)?.takeIf { it.isNotBlank() }

    /** `LIST!128` — the worksheet and the one-based row, for display. */
    val locationLabel: String get() = "$sheetName!${row + 1}"
}

/**
 * Everything one workbook's records look like through one mapping profile.
 *
 * Built once from the parsed analysis (`WorkbookAnalysis`) and the profile; the health report, the
 * gallery, the analytics and the timeline all read this, so they cannot disagree about a record.
 */
data class WorkbookCatalog(
    val workbookSha256: String,
    val profileName: String,
    val entries: List<CatalogEntry>,
    val sheetNames: List<String>,
    val tables: Map<String, CatalogueTable>,
    val mappings: Map<String, ResolvedMapping>,
    val issues: List<MappingIssue>,
    val unresolvedImages: Int,
    val unreferencedImages: Int,
    val sheets: List<SheetInfo>,
) {
    val entriesBySheet: Map<String, List<CatalogEntry>> get() = entries.groupBy { it.sheetName }
    val withVerifiedCover: Int get() = entries.count { it.hasVerifiedCover }
    val withoutVerifiedCover: Int get() = entries.size - withVerifiedCover
    val isMapped: Boolean get() = mappings.values.any { it.isMapped(CatalogField.TITLE) }

    /** The header of the mapped source column for [field] on [sheetName], if it is mapped. */
    fun headerOf(sheetName: String, field: CatalogField): String? =
        mappings[sheetName]?.columns?.get(field)?.header
}

/**
 * Builds the canonical catalog from already-parsed data.
 *
 * Nothing is re-read from the workbook and nothing is inferred: a value comes from the record's own cell
 * through the resolved mapping, and an image comes from the association the comment scanner already
 * verified. A field the profile leaves unmapped, a field whose column disappeared, and a field whose
 * header is ambiguous are all reported as their own state instead of a blank.
 *
 * Each worksheet is resolved with **its own** mapping: the entry in [sheetMappings] for its identity, else
 * [profile]. A **null** [profile] means "no workbook-wide mapping": every worksheet without an entry is detected
 * from its own headers, which is the state an untouched workbook is in — and the reason one worksheet's applied
 * columns can never unmap another worksheet's fields. A non-null profile (even one with no columns, meaning
 * "nothing is mapped") is used for every worksheet that has no entry of its own.
 */
object WorkbookCatalogBuilder {

    fun build(
        workbookSha256: String,
        sheets: List<SheetInfo>,
        tables: List<CatalogueTable>,
        images: List<ImageRow>,
        profile: ColumnMappingProfile? = null,
        sheetMappings: Map<String, SheetMapping> = emptyMap(),
    ): WorkbookCatalog {
        val identityBySheet = sheets.associate { it.name to it.partPath.ifEmpty { "name:${it.name}" } }
        val imagesByRecord = indexImages(images, identityBySheet)
        val mappings = LinkedHashMap<String, ResolvedMapping>()
        val issues = LinkedHashMap<String, MappingIssue>()
        val entries = ArrayList<CatalogEntry>()
        val usedNames = LinkedHashSet<String>()

        for (table in tables) {
            val sheetPath = sheets.firstOrNull { it.name == table.sheetName }?.partPath.orEmpty()
            val identitySheet = sheetPath.ifEmpty { "name:${table.sheetName}" }
            // The worksheet's own mapping, never the one that happens to be on screen in another worksheet.
            val sheetProfile = profileFor(table, identitySheet, profile, sheetMappings)
            usedNames.add(sheetProfile.name)
            val mapping = ColumnMappings.resolve(table, sheetProfile)
            mappings[table.sheetName] = mapping
            mapping.issues.forEach { issues.putIfAbsent(it.id(), it) }

            for (record in table.records) {
                val values = LinkedHashMap<CatalogField, FieldValue>()
                for (field in CatalogField.ordered) {
                    values[field] = resolveValue(table, record, mapping, sheetProfile, field)
                }
                val id = "$identitySheet#${record.row}"
                entries.add(
                    CatalogEntry(
                        id = id,
                        workbookSha256 = workbookSha256,
                        sheetName = table.sheetName,
                        sheetPath = sheetPath,
                        row = record.row,
                        values = values,
                        images = imagesByRecord[id].orEmpty(),
                    ),
                )
            }
        }

        return WorkbookCatalog(
            workbookSha256 = workbookSha256,
            profileName = when {
                usedNames.size == 1 -> usedNames.first()
                usedNames.isEmpty() -> profile?.name ?: ColumnMappingProfile.DETECTED_NAME
                else -> ColumnMappingProfile.PER_SHEET_NAME
            },
            entries = entries,
            sheetNames = tables.map { it.sheetName },
            tables = tables.associateBy { it.sheetName },
            mappings = mappings,
            issues = issues.values.toList(),
            unresolvedImages = images.count { it.state == ImageState.UNRESOLVED },
            unreferencedImages = images.count { it.state == ImageState.UNREFERENCED },
            sheets = sheets,
        )
    }

    /**
     * The profile one worksheet is read with.
     *
     * Its own applied entry wins; otherwise [fallback] decides — and a null fallback means "detect this
     * worksheet", which is a decision about **this** table's headers, so it is taken here rather than by the
     * caller for the whole workbook. A profile is asked for **this worksheet's** entry
     * ([ColumnMappingProfile.columnsFor]), so a profile that carries per-worksheet mappings never hands one
     * worksheet's columns to another.
     */
    private fun profileFor(
        table: CatalogueTable,
        sheetKey: String,
        fallback: ColumnMappingProfile?,
        sheetMappings: Map<String, SheetMapping>,
    ): ColumnMappingProfile {
        sheetMappings.entryFor(sheetKey)?.let { return it.asProfile() }
        val profile = fallback ?: return ColumnMappings.detectedProfile(table)
        val columns = profile.columnsFor(sheetKey) ?: return ColumnMappings.detectedProfile(table)
        return ColumnMappingProfile(profile.name, columns = columns)
    }

    private fun resolveValue(
        table: CatalogueTable,
        record: CatalogueRecord,
        mapping: ResolvedMapping,
        profile: ColumnMappingProfile,
        field: CatalogField,
    ): FieldValue {
        val blocking = ColumnMappings.blockingIssue(mapping, field)
        if (blocking != null) {
            return if (blocking.kind == MappingIssueKind.HEADER_AMBIGUOUS) {
                FieldValue(field, FieldState.AMBIGUOUS, candidates = listOfNotNull(blocking.header))
            } else {
                FieldValue(field, FieldState.UNMAPPED)
            }
        }
        if (field !in profile.columns) return FieldValue(field, FieldState.UNMAPPED)
        val column = mapping.columnOf(field) ?: return FieldValue(field, FieldState.UNMAPPED)
        val source = table.fields.firstOrNull { it.column == column } ?: return FieldValue(field, FieldState.UNMAPPED)
        val text = record.value(source).trim()
        return if (text.isEmpty()) FieldValue(field, FieldState.MISSING) else FieldValue(field, FieldState.MAPPED, text)
    }

    /** record id -> the images the comment scanner associated with that exact worksheet row. */
    private fun indexImages(
        images: List<ImageRow>,
        identityBySheet: Map<String, String>,
    ): Map<String, List<CatalogImage>> {
        val out = LinkedHashMap<String, MutableList<CatalogImage>>()
        for (row in images) {
            val image = CatalogImage(row.mediaPath, row.state, row.unresolvedDetail)
            for (hit in row.catalogue) {
                val identity = identityBySheet[hit.sheetName] ?: continue
                val bucket = out.getOrPut("$identity#${hit.row}") { ArrayList() }
                if (bucket.none { it.mediaPath == image.mediaPath && it.detail == image.detail }) bucket.add(image)
            }
        }
        return out
    }
}