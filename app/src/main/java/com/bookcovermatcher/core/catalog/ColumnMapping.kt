package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.CatalogueTable

/**
 * A reusable mapping from a workbook's own headers to the canonical [CatalogField]s.
 *
 * The profile stores **header names**, not column positions: a column inserted at the left of a sheet
 * must not silently shift every mapping by one. [schemaVersion] is written so an older app can refuse,
 * rather than misread, a newer profile.
 */
data class ColumnMappingProfile(
    val name: String,
    /** Canonical field -> the workbook's header text, exactly as the workbook spells it. */
    val columns: Map<CatalogField, String>,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        /** The name the auto-detected mapping carries; it is never written to the profile store. */
        const val DETECTED_NAME = "Detected headers"
    }
}

enum class MappingIssueKind {
    /** The profile names a header the worksheet no longer has (a renamed or removed column). */
    HEADER_MISSING,

    /** The header appears in more than one column, so the app refuses to choose one. */
    HEADER_AMBIGUOUS,

    /** Two canonical fields were mapped to the same column. Allowed, but the user should confirm. */
    SHARED_HEADER,

    /** The required title field is not mapped, so records cannot be identified. */
    REQUIRED_UNMAPPED,
}

/**
 * One thing a human should look at before trusting a mapping.
 *
 * Issues are *reported*, never resolved by guessing: an ambiguous or missing header leaves the field
 * unmapped for this worksheet, and the health report and the browser show it as unmapped.
 */
data class MappingIssue(
    val kind: MappingIssueKind,
    val field: CatalogField?,
    val header: String?,
    val sheetName: String?,
    val detail: String,
) {
    fun id(): String = kind.name + ":" + (sheetName ?: "-") + ":" + (field?.name ?: "-") + ":" + (header ?: "-")
}

/** One resolved column of one worksheet. */
data class ResolvedColumn(val field: CatalogField, val header: String, val column: Int)

/** The columns actually resolved for one worksheet, plus what could not be resolved. */
data class ResolvedMapping(
    val sheetName: String,
    val columns: Map<CatalogField, ResolvedColumn>,
    val issues: List<MappingIssue>,
) {
    fun columnOf(field: CatalogField): Int? = columns[field]?.column
    fun isMapped(field: CatalogField): Boolean = field in columns
}

/**
 * Resolving a profile against a worksheet's real header row.
 *
 * Everything here is pure and deterministic, so the mapping layer is tested on the desktop JVM.
 */
object ColumnMappings {

    /** Header text normalized for comparison only: trimmed, lower-cased, inner whitespace collapsed. */
    fun normalize(header: String): String =
        header.trim().lowercase().replace(Regex("\\s+"), " ")

    /** The header of [table] that matches [field]: an exact alias first, then a substring. */
    fun fieldFor(table: CatalogueTable, field: CatalogField): CatalogueField? {
        val aliases = field.aliases.map { it.lowercase() }
        val exact = table.fields.firstOrNull { header -> header.name.trim().lowercase() in aliases }
        if (exact != null) return exact
        return table.fields.firstOrNull { header ->
            val name = header.name.trim().lowercase()
            aliases.any { name.contains(it) }
        }
    }

    /**
     * The mapping a workbook suggests for itself, from the existing header aliases.
     *
     * It is a starting point, never applied silently as a saved profile: the user sees it on the mapping
     * screen and can change or save it. Fields whose header is not present stay unmapped.
     */
    fun detectedProfile(table: CatalogueTable): ColumnMappingProfile {
        val columns = LinkedHashMap<CatalogField, String>()
        for (field in CatalogField.ordered) {
            val header = when (field) {
                CatalogField.TITLE -> table.titleField
                CatalogField.CIRCLE -> table.circleField
                CatalogField.ARTIST -> table.authorField
                CatalogField.PUBLISHED -> table.dateField
                CatalogField.TAGS -> table.genreField
                else -> null
            } ?: fieldFor(table, field)
            if (header != null) columns[field] = header.name
        }
        return ColumnMappingProfile(ColumnMappingProfile.DETECTED_NAME, columns)
    }

    /**
     * Applies [profile] to [table].
     *
     * A field is resolved only when exactly one column carries the mapped header. A missing or duplicated
     * header is an issue; the field is then left unmapped for this worksheet instead of picking a column.
     */
    fun resolve(table: CatalogueTable, profile: ColumnMappingProfile): ResolvedMapping {
        val occurrences = table.fields.groupBy { normalize(it.name) }
        val resolved = LinkedHashMap<CatalogField, ResolvedColumn>()
        val issues = ArrayList<MappingIssue>()

        for (field in CatalogField.ordered) {
            val header = profile.columns[field] ?: continue
            val matches = occurrences[normalize(header)].orEmpty()
            when {
                matches.isEmpty() -> issues.add(
                    MappingIssue(
                        MappingIssueKind.HEADER_MISSING,
                        field,
                        header,
                        table.sheetName,
                        "The profile maps ${field.label} to \u201C$header\u201D, but this worksheet has no such column.",
                    ),
                )
                matches.size > 1 -> issues.add(
                    MappingIssue(
                        MappingIssueKind.HEADER_AMBIGUOUS,
                        field,
                        header,
                        table.sheetName,
                        "The header \u201C$header\u201D appears in ${matches.size} columns, so ${field.label} stays " +
                            "unmapped instead of picking one.",
                    ),
                )
                else -> resolved[field] = ResolvedColumn(field, matches.single().name, matches.single().column)
            }
        }

        // The same column mapped to two canonical fields is allowed, but it is usually a mistake.
        resolved.values.groupBy { it.column }.forEach { (column, columns) ->
            if (columns.size > 1) {
                for (entry in columns) {
                    issues.add(
                        MappingIssue(
                            MappingIssueKind.SHARED_HEADER,
                            entry.field,
                            entry.header,
                            table.sheetName,
                            "Column ${CatalogueFiltering.columnLetter(column)} (${entry.header}) is mapped to " +
                                columns.joinToString(", ") { it.field.label } + ".",
                        ),
                    )
                }
            }
        }

        if (CatalogField.TITLE !in resolved) {
            issues.add(
                MappingIssue(
                    MappingIssueKind.REQUIRED_UNMAPPED,
                    CatalogField.TITLE,
                    profile.columns[CatalogField.TITLE],
                    table.sheetName,
                    "No title column is mapped, so records on ${table.sheetName} cannot be identified.",
                ),
            )
        }

        return ResolvedMapping(table.sheetName, resolved, issues)
    }

    /** The issue that makes [field]'s values unusable for this worksheet, if there is one. */
    fun blockingIssue(resolved: ResolvedMapping, field: CatalogField): MappingIssue? =
        resolved.issues.firstOrNull {
            it.field == field &&
                (it.kind == MappingIssueKind.HEADER_MISSING || it.kind == MappingIssueKind.HEADER_AMBIGUOUS)
        }
}
