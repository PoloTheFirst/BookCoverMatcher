package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.SheetInfo

/**
 * Which worksheets a report is about.
 *
 * [keys] are worksheet **identities**, not display names: the package part path
 * (`xl/worksheets/sheet5.xml`), or `name:<display name>` when the analysis could not read the path. That is
 * the same identity a [CatalogEntry] uses, so a sheet renamed in Excel keeps its key (`sheet5.xml`) and a
 * selection cannot silently move to a different sheet. An empty set means "every worksheet" ([isAll]) —
 * which is also the honest state when a stale selection no longer matches anything.
 */
data class SheetScope(val keys: Set<String> = emptySet()) {

    val isAll: Boolean get() = keys.isEmpty()

    /** True when the worksheet with this path (or, without one, this name) is in scope. */
    fun allows(sheetPath: String, sheetName: String): Boolean =
        isAll || keyOf(sheetPath, sheetName) in keys

    fun allows(sheet: SheetInfo): Boolean = allows(sheet.partPath, sheet.name)

    /**
     * Drops identities this workbook no longer has.
     *
     * Selecting LIST and DATA and then deleting DATA keeps LIST; selecting only DATA and deleting it falls
     * back to [ALL] instead of reporting on nothing, and the screen then says "All worksheets" again.
     */
    fun reconciled(available: Collection<String>): SheetScope =
        if (isAll) this else SheetScope(keys intersect available.toSet())

    companion object {
        val ALL = SheetScope()

        /** `xl/worksheets/sheet5.xml`, or `name:LIST` when the part path is unknown. */
        fun keyOf(sheetPath: String, sheetName: String): String =
            sheetPath.ifEmpty { "name:$sheetName" }
    }
}

/**
 * One selectable worksheet of the open workbook.
 *
 * [records] is how many records this worksheet contributes to the catalog (0 for a sheet with no table),
 * so the picker can show that a choice is meaningful instead of an empty list of names.
 */
data class SheetChoice(
    val key: String,
    val name: String,
    val records: Int,
    val hidden: Boolean = false,
    val isTable: Boolean = false,
)

/** Selectable worksheets of this workbook, in workbook order — the list a scope picker offers. */
val WorkbookCatalog.sheetChoices: List<SheetChoice>
    get() {
        val counts = entries.groupingBy { SheetScope.keyOf(it.sheetPath, it.sheetName) }.eachCount()
        return sheets.map { sheet ->
            val key = SheetScope.keyOf(sheet.partPath, sheet.name)
            SheetChoice(
                key = key,
                name = sheet.name,
                records = counts[key] ?: 0,
                hidden = sheet.hidden,
                isTable = tables.containsKey(sheet.name),
            )
        }
    }

/** Every worksheet identity this catalog knows, for reconciling a stored selection. */
val WorkbookCatalog.sheetKeys: Set<String>
    get() = sheets.map { SheetScope.keyOf(it.partPath, it.name) }.toSet()
