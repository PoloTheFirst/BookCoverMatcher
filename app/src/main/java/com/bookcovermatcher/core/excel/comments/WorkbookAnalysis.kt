package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.ZipSource

/** One worksheet as the previewer shows it: display name, workbook order and Excel's own visibility. */
data class SheetInfo(
    val name: String,
    /** `visible`, `hidden` or `veryhidden` — exactly what `xl/workbook.xml` declares. */
    val visibility: String,
    /** The worksheet's declared used range (`A1:P120`), or null when the part declares none. */
    val dimension: String? = null,
) {
    val hidden: Boolean get() = !visibility.equals("visible", ignoreCase = true)
    val label: String get() = when (visibility.lowercase()) {
        "veryhidden" -> "Very hidden"
        "hidden" -> "Hidden"
        else -> "Visible"
    }
}

/** Everything the browser, catalogue search, manifests and comparison read for one workbook. */
data class WorkbookAnalysis(
    val sheets: List<SheetInfo>,
    val tables: List<CatalogueTable>,
    val images: List<ImageRow>,
    val coverage: ImageCoverage,
    /** Sheets that could not be read at all (missing/corrupt part); reported, never hidden. */
    val unreadableSheets: List<String>,
)

/**
 * Joins the scanned comments/media with the workbook's catalogue tables.
 *
 * Only legacy-comment associations already verified by [CommentImageScanner] are used, so this cannot
 * invent a link between an image and a row. A sheet that is not a table is simply not a catalogue.
 */
object WorkbookAnalyzer {

    fun analyze(zip: ZipSource, model: WorkbookCommentImages): WorkbookAnalysis {
        val sheetRefs = runCatching { WorkbookSheets.list(zip) }.getOrDefault(emptyList())
        val shared = runCatching { SheetCells.sharedStrings(zip) }.getOrDefault(emptyList())
        val tables = ArrayList<CatalogueTable>()
        val unreadable = ArrayList<String>()

        for (sheet in sheetRefs) {
            // A pivot-table sheet is not a catalogue: its first "header" row is the pivot's own
            // `Row Labels` / `Grand Total` band and every value is an aggregate. Excel records the
            // relationship, so this is structural — no sheet name or column index is assumed.
            if (hostsPivotTable(zip, sheet.partPath)) continue
            val table = try {
                CatalogueReader.read(zip, sheet.partPath, sheet.name, shared)
            } catch (e: Exception) {
                unreadable.add(sheet.name)
                null
            }
            if (table != null) tables.add(table)
        }

        val images = ImageIndex.build(model, tables)
        return WorkbookAnalysis(
            sheets = sheetRefs.map { sheet ->
                SheetInfo(sheet.name, sheet.visibility, SheetCells.dimension(zip, sheet.partPath))
            },
            tables = tables,
            images = images,
            coverage = ImageIndex.coverage(tables, images),
            unreadableSheets = unreadable,
        )
    }

    /** True when the worksheet carries a `pivotTable` relationship (the pivot's own target sheet). */
    private fun hostsPivotTable(zip: ZipSource, sheetPath: String): Boolean {
        val rels = zip.readText(OoxmlRelationships.relsPathOf(sheetPath)) ?: return false
        return OoxmlRelationships.parse(rels).any { it.shortType == "pivottable" }
    }
}
