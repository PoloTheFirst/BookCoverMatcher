package com.bookcovermatcher.core.excel.comments

/**
 * A filter over one column of one catalogue table.
 *
 * Identity is **(worksheet, column)**, never the header text: two columns both titled `%` stay
 * independent, and a filter can never silently match a same-named column of another table.
 */
data class CatalogueFilter(val sheetName: String, val column: Int, val value: String) {
    val key: String get() = "$sheetName#$column"
}

/** One selectable filter field. [label] is display-only; [sheetName] + [column] are the identity. */
data class FilterField(
    val key: String,
    val label: String,
    val name: String,
    val sheetName: String,
    val column: Int,
    val valueCount: Int,
) {
    fun filter(value: String): CatalogueFilter = CatalogueFilter(sheetName, column, value)
}

/**
 * Turns the discovered catalogue tables into selectable filters — fields and values come from the
 * workbook's own header row and records, so nothing here depends on a sheet name, header text or
 * column position.
 */
object CatalogueFiltering {

    /** The tables in scope: all of them, or only those of one worksheet. */
    fun tablesFor(tables: List<CatalogueTable>, sheetName: String?): List<CatalogueTable> =
        if (sheetName == null) tables else tables.filter { it.sheetName == sheetName }

    /**
     * Every field worth filtering, in worksheet/column order.
     *
     * A field needs at least [minValues] distinct non-blank values (a constant or empty column filters
     * nothing). Repeated header names are labelled with their column letter (`% (F)`, `% (H)`), blank
     * headers fall back to `Column F`, and both keep their real column identity.
     */
    fun fields(tables: List<CatalogueTable>, minValues: Int = 2): List<FilterField> {
        val out = ArrayList<FilterField>()
        for (table in tables) {
            val occurrences = table.fields.groupingBy { it.name.trim().lowercase() }.eachCount()
            for (field in table.fields) {
                val name = field.name.trim()
                val column = field.column
                val letter = columnLetter(column)
                val distinct = table.valuesOf(field).size
                if (distinct < minValues) continue
                val label = when {
                    name.isEmpty() -> "Column $letter"
                    (occurrences[name.lowercase()] ?: 0) > 1 -> "$name ($letter)"
                    else -> name
                }
                out.add(FilterField("${table.sheetName}#$column", label, name, table.sheetName, column, distinct))
            }
        }
        return out
    }

    /** Distinct, sorted, non-blank values of [column]; empty when the column is not in [table]. */
    fun values(table: CatalogueTable, column: Int): List<String> {
        val field = table.fields.firstOrNull { it.column == column } ?: return emptyList()
        return table.valuesOf(field)
    }

    /** All filters must match (AND across fields); a filter for another table never matches this one. */
    fun matches(table: CatalogueTable, record: CatalogueRecord, filters: List<CatalogueFilter>): Boolean {
        if (filters.isEmpty()) return true
        // Strict: a filter for another worksheet cannot be satisfied by this table, so its records stay
        // out of the result instead of silently ignoring the condition.
        return filters.all { filter ->
            if (filter.sheetName != table.sheetName) return@all false
            val field = table.fields.firstOrNull { it.column == filter.column } ?: return@all false
            record.value(field).trim() == filter.value.trim()
        }
    }

    /** Excel-style column letters for a zero-based column index (0 -> A, 25 -> Z, 26 -> AA). */
    fun columnLetter(column: Int): String {
        if (column < 0) return "?"
        var rest = column
        val out = StringBuilder()
        while (true) {
            out.append(('A' + rest % 26))
            rest = rest / 26 - 1
            if (rest < 0) break
        }
        return out.reverse().toString()
    }
}
