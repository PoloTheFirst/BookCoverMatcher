// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.ZipSource
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * One hyperlink the **workbook itself** stores for a cell.
 *
 * [target] is exactly what the file contains — an external URL, or an in-workbook reference such as
 * `LIST!A5`. Nothing is derived from the cell's text: a cell that merely reads like a URL is not a link,
 * and a link's own display text is kept beside its destination rather than replacing it.
 *
 * [internal] links point inside the workbook (a `location` attribute or a `#`-prefixed target). The app
 * navigates those with its own source view instead of handing them to a browser.
 */
data class CellLink(
    val target: String,
    val internal: Boolean = false,
    /** The workbook's own display text for the link, when it declares one. */
    val display: String? = null,
) {
    /** What the UI shows for the link: the workbook's display text, else the destination. */
    val label: String get() = display?.takeIf { it.isNotBlank() } ?: target

    /** A destination a browser intent can open. Nothing else is guessed. */
    val isWeb: Boolean
        get() = !internal && (
            target.startsWith("http://", ignoreCase = true) ||
                target.startsWith("https://", ignoreCase = true) ||
                target.startsWith("mailto:", ignoreCase = true)
            )

    /** True when the app can act on it: open a page, or jump inside the workbook. */
    val isOpenable: Boolean get() = isWeb || (internal && target.isNotBlank())

    /**
     * An internal reference split into its worksheet name and cell (`LIST!A5` -> `LIST` and `A5`).
     *
     * A defined name or a bare `A5` has no sheet part, so it returns null and the caller reports that it
     * cannot follow the link instead of guessing a worksheet.
     */
    fun internalTarget(): Pair<String, String>? {
        if (!internal) return null
        val text = target.trim().removePrefix("#")
        val bang = text.lastIndexOf('!')
        if (bang <= 0 || bang == text.lastIndex) return null
        val sheet = text.substring(0, bang).trim().removeSurrounding("'")
        val cell = text.substring(bang + 1).trim()
        if (sheet.isEmpty() || cell.isEmpty()) return null
        return sheet to cell
    }

    companion object {
        /** A link's range is bounded: a whole-column link must not expand into a huge map. */
        const val MAX_RANGE_CELLS = 1024
    }
}

/** What one worksheet relationship (`rId…`) says about a hyperlink. */
data class LinkTarget(val target: String, val external: Boolean)

/**
 * Reads the hyperlinks of one worksheet.
 *
 * Excel stores them in the worksheet part — `<hyperlinks><hyperlink ref="A2" r:id="rId1"/></hyperlinks>` for
 * an external link (the target lives in the worksheet's `.rels`) and
 * `<hyperlink ref="A2" location="LIST!B4"/>` for an internal one. Both are read; a link whose relationship is
 * missing, or whose reference cannot be parsed, contributes nothing rather than a guessed destination.
 *
 * Pure XML plus one relationship lookup, so the whole mapping is unit-tested on a desktop JVM.
 */
object SheetLinks {

    /** A cell's key in the returned map: row and column, zero-based. */
    fun key(row: Int, column: Int): Long = row * 100_000L + column

    /** Hyperlinks of [sheetPath], keyed by [key]. Empty when the worksheet has none. */
    fun read(zip: ZipSource, sheetPath: String): Map<Long, CellLink> {
        val xml = zip.readText(sheetPath) ?: return emptyMap()
        val rels = runCatching { targetsOf(OoxmlRelationships.of(zip, sheetPath)) }.getOrDefault(emptyMap())
        return parse(xml, rels)
    }

    /** `rId` -> target, for the relationships whose type is a hyperlink. */
    fun targetsOf(rels: List<OoxmlRelationship>): Map<String, LinkTarget> = rels
        .filter { it.shortType == "hyperlink" && it.target.isNotBlank() }
        .associate { it.id to LinkTarget(it.target, external = it.external) }

    /** The pure part: worksheet XML plus its relationship table. */
    fun parse(xml: String, rels: Map<String, LinkTarget>): Map<Long, CellLink> {
        val out = LinkedHashMap<Long, CellLink>()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                if (XlsxXml.local(qName ?: "") != "hyperlink" || attributes == null) return
                val ref = WorkbookSheets.attributeByLocal(attributes, "ref").orEmpty().trim()
                if (ref.isEmpty()) return
                val display = WorkbookSheets.attributeByLocal(attributes, "display")
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val location = WorkbookSheets.attributeByLocal(attributes, "location")?.trim()
                val rid = WorkbookSheets.attributeByLocal(attributes, "id")?.trim()
                val link = when {
                    // An internal reference: the workbook's own sheet!cell (or a defined name).
                    !location.isNullOrEmpty() -> CellLink(location, internal = true, display = display)
                    // An external one, whose target is spelled in the worksheet's relationships.
                    rid != null -> rels[rid]?.let { target ->
                        CellLink(target.target, internal = !target.external, display = display)
                    }
                    else -> null
                } ?: return
                for ((row, column) in cellsOf(ref)) out[key(row, column)] = link
            }
        }
        XlsxXml.parse(xml, handler)
        return out
    }

    /**
     * The cells a `ref` covers: `A2` is one cell, `A2:A4` is three.
     *
     * A range larger than [CellLink.MAX_RANGE_CELLS] is not expanded; its first cell is used instead, so a
     * whole-column link cannot turn into a hundred thousand entries.
     */
    fun cellsOf(ref: String): List<Pair<Int, Int>> {
        val parts = ref.split(':')
        val start = SheetCells.refToCell(parts.firstOrNull()) ?: return emptyList()
        if (parts.size < 2) return listOf(start)
        val end = SheetCells.refToCell(parts[1]) ?: return listOf(start)
        val rows = minOf(start.first, end.first)..maxOf(start.first, end.first)
        val columns = minOf(start.second, end.second)..maxOf(start.second, end.second)
        val count = rows.count().toLong() * columns.count().toLong()
        if (count > CellLink.MAX_RANGE_CELLS) return listOf(start)
        val out = ArrayList<Pair<Int, Int>>(count.toInt())
        for (row in rows) for (column in columns) out.add(row to column)
        return out
    }
}
