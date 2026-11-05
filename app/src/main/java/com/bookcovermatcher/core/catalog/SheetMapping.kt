package com.bookcovermatcher.core.catalog

/**
 * The mapping in effect for **one** worksheet.
 *
 * A workbook-wide mapping is what the Fields screen used to keep, and it is wrong: two worksheets of one
 * workbook rarely share a header row (`LIST` has 24 columns, a notes sheet has three), so applying the columns of
 * the worksheet on screen to every worksheet silently unmapped the others. Each worksheet therefore owns an
 * entry, keyed by its identity (`SheetScope.keyOf(partPath, name)`), and only the worksheet the user is editing
 * is ever written.
 *
 * [profileName] names the saved profile this worksheet is read with, or is null when the columns were chosen by
 * hand. [columns] holds the workbook's own header text per canonical field — never a column position, so
 * inserting a column does not shift what a field reads. An entry with no columns is not an entry: it means
 * "detect this worksheet's headers", which is why [Map.withSheet] removes it.
 */
data class SheetMapping(
    val profileName: String? = null,
    val columns: Map<CatalogField, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = columns.isEmpty()

    /** This mapping as a profile the catalog builder can resolve against the worksheet's headers. */
    fun asProfile(name: String = MANUAL_NAME): ColumnMappingProfile =
        ColumnMappingProfile(profileName ?: name, columns)

    /** The same columns without the profile: what is left when the profile they named is deleted. */
    fun detached(): SheetMapping = if (profileName == null) this else copy(profileName = null)

    companion object {
        /** The name a manual (unsaved) mapping carries, so a restored mapping is never called a profile. */
        const val MANUAL_NAME = "Mapping saved with this workbook"

        val NONE = SheetMapping()
    }
}

/**
 * Whether two column mappings mean the same thing.
 *
 * This is the rule the **Detected** status uses, so the indicator reports a fact about the mapping instead of a
 * fact about what the user clicked: applying a mapping that detection would have produced anyway keeps the
 * worksheet on its detected headers, and applying a genuinely different one does not. Comparison is by
 * **meaning**, never by object identity:
 *
 *  * a blank header is "not assigned" and takes no part in either side, so an empty value can never make two
 *    mappings differ (and, on the other side, can never make them equal by accident);
 *  * headers are compared the way [ColumnMappings.resolve] compares them — trimmed, case-folded, inner
 *    whitespace collapsed — so `"  タイトル "` and `"タイトル"` are one column, exactly as they are when the
 *    catalog is built;
 *  * fields present on one side only are a difference (one side maps the field, the other does not).
 */
object MappingEquality {

    fun sameColumns(a: Map<CatalogField, String>, b: Map<CatalogField, String>): Boolean =
        canonical(a) == canonical(b)

    fun sameColumns(a: SheetMapping?, b: SheetMapping?): Boolean =
        sameColumns(a?.columns.orEmpty(), b?.columns.orEmpty())

    /** The comparable form of a mapping: only assigned fields, each header in its normalized spelling. */
    fun canonical(columns: Map<CatalogField, String>): Map<CatalogField, String> = buildMap {
        for ((field, header) in columns) {
            if (header.isBlank()) continue
            put(field, ColumnMappings.normalize(header))
        }
    }
}

/** The mapping applied to [key], or null when that worksheet is on its detected headers. */
fun Map<String, SheetMapping>.entryFor(key: String?): SheetMapping? =
    if (key == null) null else this[key]?.takeIf { !it.isEmpty }

/**
 * Writes one worksheet's entry, or removes it when [mapping] carries no columns.
 *
 * The returned map is a **new** map and the entry a new value: no worksheet can ever share a mutable object with
 * another, which is what keeps one worksheet's mapping from following the user to the next one.
 */
fun Map<String, SheetMapping>.withSheet(key: String, mapping: SheetMapping): Map<String, SheetMapping> =
    if (mapping.isEmpty) this - key else this + (key to mapping)

/** Removes one worksheet's entry (the worksheet returns to its own detected headers). */
fun Map<String, SheetMapping>.withoutSheet(key: String): Map<String, SheetMapping> =
    if (key in this) this - key else this

